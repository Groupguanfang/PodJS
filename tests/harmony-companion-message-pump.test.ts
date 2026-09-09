import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionMessageOutbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageOutbox';
import { CompanionMessageInbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageInbox';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
import { CompanionMessagePump } from '../platforms/harmony/companion/src/main/ets/CompanionMessagePump';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
class Store {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(old: string | null, next: string) { if (this.fail) throw new Error('disk failure'); if (old !== this.raw) return false; this.raw = next; return true; }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
async function peers(stores = [new Store(), new Store(), new Store(), new Store()], nonce = 2) {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(nonce));
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, ['message', 'ack'], crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, ['message', 'ack'], crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  const outA = new CompanionMessageOutbox('app', 'phone', stores[0], crypto), inA = new CompanionMessageInbox('app', 'phone', stores[1], crypto);
  const outB = new CompanionMessageOutbox('app', 'watch', stores[2], crypto), inB = new CompanionMessageInbox('app', 'watch', stores[3], crypto);
  return { a, b, outA, inB, stores, pa: new CompanionMessagePump(a, outA, inA), pb: new CompanionMessagePump(b, outB, inB) };
}
test('message pump lost ACK reconnect replays unchanged data without repeating business work', async () => {
  const first = await peers(); let applied = 0;
  await first.outA.enqueue('watch', 'id', new CompanionMessageEnvelope(100, true, new Uint8Array([1, 2])), 1);
  const frame = (await first.pa.sendNext(2))!;
  await first.pb.receive(frame, 2, { async apply(delivery) { applied++; delivery.digest.fill(0); delivery.messageId = 'mutated'; } });
  first.pa.close(); first.pb.close();
  const next = await peers(first.stores, 3), replay = (await next.pa.sendNext(3))!;
  expect(replay.payload).toEqual(frame.payload); expect(replay.sessionId).not.toBe(frame.sessionId);
  const result = await next.pb.receive(replay, 3, { async apply() { applied++; } });
  expect(result.status).toBe('applied'); expect(applied).toBe(1);
  expect((await next.pa.receive(result.reply!, 3)).status).toBe('ack'); expect(await next.pa.sendNext(3)).toBeNull();
});
test('deferred delivery advances transport but does not ACK before durable business completion', async () => {
  const p = await peers(); await p.outA.enqueue('watch', 'id', new CompanionMessageEnvelope(100, false, new Uint8Array([3])), 1);
  const result = await p.pb.receive((await p.pa.sendNext(2))!, 2);
  expect(result.status).toBe('pending'); expect(result.reply).toBeNull(); expect((await p.outA.pending('watch', 2, 1)).length).toBe(1);
  const ack = await p.pb.acknowledge(result.delivery!, 3); await p.pa.receive(ack, 3);
  expect(await p.pa.sendNext(3)).toBeNull();
});
test('business or receipt failure closes without ACK and retains pending work for new session', async () => {
  for (const diskFailure of [false, true]) {
    const p = await peers(); await p.outA.enqueue('watch', 'id', new CompanionMessageEnvelope(100, false, new Uint8Array()), 1);
    await expect(p.pb.receive((await p.pa.sendNext(2))!, 2, { async apply() {
      if (diskFailure) p.stores[3].fail = true; else throw new Error('business failure');
    } })).rejects.toThrow('failure');
    await expect(p.b.commit(1)).rejects.toThrow('closed'); p.stores[3].fail = false;
    expect((await p.inB.pending(3, 1)).length).toBe(1); expect((await p.outA.pending('watch', 3, 1)).length).toBe(1);
  }
});
