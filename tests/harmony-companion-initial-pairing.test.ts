import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionInitialPairing } from '../platforms/harmony/companion/src/main/ets/CompanionInitialPairing';
import { CompanionPairingInvitation } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitation';
import { CompanionPairingInvitationLease } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitationLease';
import { CompanionPairings } from '../platforms/harmony/companion/src/main/ets/CompanionPairings';
import { CompanionSyncAttempt } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
class Timer {
  expire: () => void = () => {};
  schedule(_ms: number, expire: () => void) { this.expire = expire; return () => {}; }
}
class Link {
  peer!: Link; closed = false; queue: Uint8Array[] = [];
  waiting: ((bytes: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> {
    if (this.queue.length) return this.queue.shift()!;
    if (this.closed) return null;
    return new Promise(resolve => { this.waiting = resolve; });
  }
  async write(bytes: Uint8Array) {
    if (this.closed || this.peer.closed) throw Error('closed');
    if (this.peer.waiting) { const resolve = this.peer.waiting; this.peer.waiting = null; resolve(bytes.slice()); }
    else this.peer.queue.push(bytes.slice());
  }
  close() { this.closed = true; this.peer.closed = true; this.waiting?.(null); this.waiting = null; this.peer.waiting?.(null); this.peer.waiting = null; }
}
const crypto = (n: number) => ({
  async challenge() { return new Uint8Array(32).fill(n); },
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
});
function owner(local: string) {
  let raw: string | null = null, failImport = false;
  const keys = new Map<string, Uint8Array>();
  const pairings = new CompanionPairings('app', local, {
    assertOwned() {}, close() {}, async read() { return raw; },
    async compareExchange(old, next) { if (old !== raw) return false; raw = next; return true; }
  }, {
    async exists(id) { return keys.has(id); }, async importKey(id, key) { if (failImport) throw Error('keystore failed'); keys.set(id, key.slice()); },
    async remove(id) { keys.delete(id); }, async sign(id, bytes) { return crypto(1).hmacSha256(keys.get(id)!, bytes); }
  }, crypto(5));
  return { pairings, keys, fail: () => { failImport = true; } };
}
async function fixture(leftConfirm: () => Promise<boolean> = async () => true, rightConfirm: () => Promise<boolean> = async () => true,
  transform: (text: string) => string = text => text) {
  let random = 10;
  const issued = await CompanionPairingInvitation.create('app', 'watch', 1000, { async challenge() { return new Uint8Array(32).fill(++random); } });
  const scanned = CompanionPairingInvitation.parseQr(transform(issued.encodeForQr(1000)), 'app', 'phone', 1000);
  const left = owner('phone'), right = owner('watch'), a = new Link(), b = new Link(); a.peer = b; b.peer = a;
  const timerA = new Timer(), timerB = new Timer();
  let prompts = 0;
  function build(invitation: CompanionPairingInvitation, data: ReturnType<typeof owner>, link: Link, nonce: number, timer: Timer, confirm: () => Promise<boolean>) {
    return new CompanionInitialPairing(invitation, new CompanionPairingInvitationLease(invitation, { now: () => 1000 }, timer, () => {}), data.pairings,
      new CompanionSyncAttempt({ async connect() { return link; }, cancel() { link.close(); } }, crypto(nonce), new Timer(), 1000), crypto(nonce),
      { async confirm() { prompts++; return confirm(); }, dismiss() {} });
  }
  return { left, right, a, b, timerA, timerB, prompts: () => prompts,
    x: build(scanned, left, a, 1, timerA, leftConfirm), y: build(issued, right, b, 2, timerB, rightConfirm) };
}
test('both approved hosts derive identical non-bootstrap keys and finish only after both save', async () => {
  const f = await fixture();
  await Promise.all([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
  expect(f.prompts()).toBe(2); expect((await f.left.pairings.list())[0].phase).toBe('approved'); expect((await f.right.pairings.list())[0].phase).toBe('approved');
  const a = [...f.left.keys.values()][0], b = [...f.right.keys.values()][0];
  expect(a).toEqual(b); expect(a).not.toEqual(new Uint8Array(32).fill(12));
  expect(f.x.localCredentialMayExist() && f.y.localCredentialMayExist()).toBe(true);
  expect(f.a.closed && f.b.closed).toBe(true);
});
test('either host declining leaves both credential stores empty', async () => {
  for (const leftDeclines of [true, false]) {
    const f = await fixture(async () => !leftDeclines, async () => leftDeclines);
    const results = await Promise.allSettled([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
    expect(results.every(result => result.status === 'rejected')).toBe(true);
    expect(f.left.keys.size + f.right.keys.size).toBe(0);
    expect(f.x.localCredentialMayExist() || f.y.localCredentialMayExist()).toBe(false);
  }
});
test('expiry interrupts an unresponsive approval and a late approval cannot import keys', async () => {
  let approved!: (value: boolean) => void, prompted!: () => void;
  const entered = new Promise<void>(resolve => { prompted = resolve; });
  const f = await fixture(() => { prompted(); return new Promise(resolve => { approved = resolve; }); });
  const results = Promise.allSettled([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
  await entered; f.timerA.expire(); expect((await results).every(result => result.status === 'rejected')).toBe(true);
  approved(true); await Promise.resolve(); await Promise.resolve();
  expect(f.left.keys.size + f.right.keys.size).toBe(0);
});
test('keystore failure is not reported as mutual completion', async () => {
  const f = await fixture(); f.right.fail();
  const results = await Promise.allSettled([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
  expect(results.every(result => result.status === 'rejected')).toBe(true);
  expect(f.x.localCredentialMayExist() && f.y.localCredentialMayExist()).toBe(true);
  expect((await f.right.pairings.list())[0].phase).toBe('importing');
  expect(f.right.keys.size).toBe(0);
});
test('preexisting pairing rejects bootstrap before approval and is never replaced', async () => {
  const f = await fixture(); await f.left.pairings.importApproved('watch', new Uint8Array(32).fill(9));
  const results = await Promise.allSettled([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
  expect(results.every(result => result.status === 'rejected')).toBe(true); expect(f.prompts()).toBe(0);
  expect([...f.left.keys.values()][0]).toEqual(new Uint8Array(32).fill(9)); expect(f.right.keys.size).toBe(0);
});
test('matching bootstrap secret cannot authorize a different invitation ID', async () => {
  const f = await fixture(async () => true, async () => true, text => {
    const value = JSON.parse(text); value.invitationId = '77'.repeat(32); return JSON.stringify(value);
  });
  const results = await Promise.allSettled([f.x.start('phone', 'watch', true), f.y.start('watch', 'phone', false)]);
  expect(results.every(result => result.status === 'rejected')).toBe(true);
  expect(f.left.keys.size + f.right.keys.size).toBe(0);
});
