import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionPairings } from '../platforms/harmony/companion/src/main/ets/CompanionPairings';
import { CompanionPairedAttempt } from '../platforms/harmony/companion/src/main/ets/CompanionPairedAttempt';
import { CompanionSyncAttempt } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
class Timer {
  expire: () => void = () => {}; cleared = false;
  schedule(_ms: number, expire: () => void) { this.expire = expire; return () => { this.cleared = true; }; }
}
class Link {
  peer!: Link; closed = false;
  queue: Uint8Array[] = []; waiting: ((packet: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> {
    if (this.closed) return null;
    if (this.queue.length) return this.queue.shift()!;
    return new Promise(resolve => { this.waiting = resolve; });
  }
  async write(packet: Uint8Array) {
    if (this.closed || this.peer.closed) throw Error('link closed');
    if (this.peer.waiting) { const resolve = this.peer.waiting; this.peer.waiting = null; resolve(packet.slice()); }
    else this.peer.queue.push(packet.slice());
  }
  close() { this.closed = true; this.waiting?.(null); this.waiting = null; this.peer.closed = true; this.peer.waiting?.(null); this.peer.waiting = null; }
}
const crypto = (nonce: number) => ({
  async challenge() { return new Uint8Array(32).fill(nonce); },
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256() { throw Error('raw key path forbidden'); }
});
async function owner(local: string, peer: string) {
  let raw: string | null = null;
  const store = { assertOwned() {}, close() {}, async read() { return raw; }, async compareExchange(old: string | null, next: string) { if (old !== raw) return false; raw = next; return true; } };
  const keys = new Map<string, Uint8Array>();
  const pairings = new CompanionPairings('app', local, store, {
    async exists(id) { return keys.has(id); }, async importKey(id, key) { keys.set(id, key.slice()); }, async remove(id) { keys.delete(id); },
    async sign(id, bytes) { return new Uint8Array(createHmac('sha256', keys.get(id)!).update(bytes).digest()); }
  }, crypto(3));
  await pairings.importApproved(peer, new Uint8Array(32).fill(7)); return { pairings, store };
}
test('paired attempts authenticate with non-exportable keys; revocation closes handed-off connection', async () => {
  const left = await owner('phone', 'watch'), right = await owner('watch', 'phone');
  const a = new Link(), b = new Link(); a.peer = b; b.peer = a;
  const ta = new Timer(), tb = new Timer();
  const x = new CompanionPairedAttempt(left.pairings, new CompanionSyncAttempt({ async connect() { return a; }, cancel() { a.close(); } }, crypto(1), new Timer(), 1000), ta, 1000);
  const y = new CompanionPairedAttempt(right.pairings, new CompanionSyncAttempt({ async connect() { return b; }, cancel() { b.close(); } }, crypto(2), new Timer(), 1000), tb, 1000);
  const [ca, cb] = await Promise.all([x.start('app', 'phone', 'watch', true, ['message']), y.start('app', 'watch', 'phone', false, ['message'])]);
  expect(ta.cleared && tb.cleared).toBe(true);
  expect((await cb.session.verify(await ca.session.send('message', 'one', [1]))).delivery).toBe('pending');
  x.cancel(); expect(a.closed).toBe(false); // handoff owns the connection now
  await left.pairings.revoke('watch'); expect(a.closed && b.closed).toBe(true);
  await expect(ca.session.send('message', 'two', [2])).rejects.toThrow('closed'); cb.close();
  expect((await right.pairings.list())[0].phase).toBe('approved');
});
test('deadline bounds a stalled pairing lookup and closes late signer without connecting', async () => {
  const f = await owner('phone', 'watch'), timer = new Timer(); let connected = 0;
  let entered!: () => void, release!: (raw: string | null) => void;
  const ready = new Promise<void>(resolve => { entered = resolve; });
  const read = f.store.read, raw = await read();
  f.store.read = () => { entered(); return new Promise(resolve => { release = resolve; }); };
  const attempt = new CompanionPairedAttempt(f.pairings, new CompanionSyncAttempt({ async connect() { connected++; throw Error('must not connect'); }, cancel() {} }, crypto(1), new Timer(), 1000), timer, 1000);
  const pending = attempt.start('app', 'phone', 'watch', true, ['message']); await ready;
  timer.expire(); await expect(pending).rejects.toThrow('deadline');
  f.store.read = read; release(raw); await f.pairings.list();
  expect(connected).toBe(0); expect((await f.pairings.list())[0].phase).toBe('approved');
});
test('revocation during handshake cancels blocked IO', async () => {
  const f = await owner('phone', 'watch'); let connected!: () => void;
  const ready = new Promise<void>(resolve => { connected = resolve; });
  const a = new Link(), b = new Link(); a.peer = b; b.peer = a;
  const attempt = new CompanionPairedAttempt(f.pairings, new CompanionSyncAttempt({ async connect() { connected(); return a; }, cancel() { a.close(); } }, crypto(1), new Timer(), 1000), new Timer(), 1000);
  const pending = attempt.start('app', 'phone', 'watch', true, ['message']); await ready;
  const result = Promise.allSettled([pending]); await f.pairings.revoke('watch');
  const outcome = (await result)[0]; expect(outcome.status).toBe('rejected');
  if (outcome.status === 'rejected') expect(String(outcome.reason)).toContain('revoked');
  expect(a.closed).toBe(true);
});
