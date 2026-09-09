import { test, expect } from 'bun:test';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { CompanionState } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
import { CompanionSyncConnection } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAttempt';
class Port {
  raw: string | null = null;
  async read() { return this.raw; }
  async compareExchange(_app: string, old: string | null, next: string) { if (old !== this.raw) return false; this.raw = next; return true; }
}
class Timer { expire: () => void = () => {}; schedule(_ms: number, expire: () => void) { this.expire = expire; return () => {}; } }
class Link {
  peer!: Link; closed = false; failAck = false; sequences: number[] = []; stateIds: string[] = [];
  queue: Uint8Array[] = []; waiting: ((packet: Uint8Array | null) => void) | null = null;
  async read(): Promise<Uint8Array | null> {
    if (this.closed) return null;
    if (this.queue.length) return this.queue.shift()!;
    return new Promise(resolve => { this.waiting = resolve; });
  }
  async write(packet: Uint8Array) {
    if (this.closed || this.peer.closed) throw Error('link closed');
    const frame = JSON.parse(new TextDecoder().decode(packet)); this.sequences.push(frame.sequence);
    if (frame.channel === 'state') this.stateIds.push(frame.messageId);
    if (this.failAck && frame.channel === 'ack') throw Error('ACK write failed');
    if (this.peer.waiting) { const resolve = this.peer.waiting; this.peer.waiting = null; resolve(packet.slice()); }
    else this.peer.queue.push(packet.slice());
  }
  close() {
    this.closed = true; this.waiting?.(null); this.waiting = null;
    this.peer.closed = true; this.peer.waiting?.(null); this.peer.waiting = null;
  }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
const stateCrypto = { async sha256(text: string) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return randomUUID(); } };
async function peers(aPort = new Port(), bPort = new Port(), nonce = 2) {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(nonce));
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, ['state', 'ack'], crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, ['state', 'ack'], crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  const left = new Link(), right = new Link(); left.peer = right; right.peer = left;
  return { sa: new CompanionState('app', 'phone', aPort, stateCrypto), sb: new CompanionState('app', 'watch', bPort, stateCrypto),
    ca: new CompanionSyncConnection(a, left), cb: new CompanionSyncConnection(b, right), left, right, aPort, bPort };
}
test('bidirectional 600-entry sync drains matched ACKs and keeps state/ACK wire sequences ordered', async () => {
  const p = await peers();
  for (let i = 0; i < 600; i++) await p.sa.set('key' + i, '中文' + i);
  await p.sb.set('watchOnly', 'watch');
  const a = p.sa.synchronize(p.ca, new Timer(), 1000), b = p.sb.synchronize(p.cb, new Timer(), 1000);
  try {
    await Promise.all([a.synchronize(), b.synchronize()]);
    expect(await p.sb.get('key599')).toBe('中文599'); expect(await p.sa.get('watchOnly')).toBe('watch');
    expect((await p.sb.snapshot()).entries.length).toBe(601);
    await p.sa.set('later', 'next cycle'); await a.synchronize(); expect(await p.sb.get('later')).toBe('next cycle');
    let observed!: () => void;
    const changed = new Promise<void>(resolve => { observed = resolve; });
    const unsubscribe = p.sb.subscribe(snapshot => { if (snapshot.entries.some(entry => entry.key === 'automatic')) observed(); });
    await p.sa.set('automatic', true); await changed; unsubscribe(); await a.synchronize();
    expect(await p.sb.get('automatic')).toBe(true);
    for (const link of [p.left, p.right]) expect(link.sequences).toEqual(link.sequences.map((_, i) => i + 1));
    expect(new Set(p.left.stateIds).size).toBe(p.left.stateIds.length);
  } finally { a.close(); b.close(); }
});
test('failed ACK write leaves durable pending batch for a fresh session replay', async () => {
  const p = await peers(); await p.sa.set('key', 1); p.right.failAck = true;
  const a = p.sa.synchronize(p.ca, new Timer(), 1000), b = p.sb.synchronize(p.cb, new Timer(), 1000);
  const outcome = Promise.allSettled([a.synchronize()]);
  expect((await b.stopped).reason).toBe('failed'); expect((await outcome)[0].status).toBe('rejected');
  a.close(); b.close();
  const pending = (await p.sa.prepare('watch'))!;
  const next = await peers(p.aPort, p.bPort, 3);
  const x = next.sa.synchronize(next.ca, new Timer(), 1000), y = next.sb.synchronize(next.cb, new Timer(), 1000);
  try {
    await Promise.all([x.synchronize(), y.synchronize()]);
    expect(next.left.stateIds[0]).toBe(pending.messageId); expect(await next.sb.get('key')).toBe(1);
    expect(await next.sa.prepare('watch')).toBeNull();
  } finally { x.close(); y.close(); }
});
test('deadline settles drain even if storage never returns and suppresses late sends', async () => {
  const p = await peers(); const timer = new Timer();
  let finish!: (value: string | null) => void, enter!: () => void;
  const entered = new Promise<void>(resolve => { enter = resolve; });
  p.aPort.read = () => { enter(); return new Promise(resolve => { finish = resolve; }); };
  const a = p.sa.synchronize(p.ca, timer, 1000), drain = Promise.allSettled([a.synchronize()]);
  await entered; timer.expire();
  expect((await a.stopped).reason).toBe('deadline'); expect((await drain)[0].status).toBe('rejected');
  finish(null); await Promise.resolve(); await Promise.resolve();
  expect(p.left.sequences.length).toBe(0); expect(p.left.closed).toBe(true);
});
