import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionMessageOutbox, CompanionMessageStore } from '../platforms/harmony/companion/src/main/ets/CompanionMessageOutbox';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
class Store implements CompanionMessageStore {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(expected: string | null, desired: string) {
    if (this.fail || this.raw !== expected) return false; this.raw = desired; return true;
  }
}
const crypto = { async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } };
const envelope = (expiry = 100, high = false, payload = new Uint8Array([1])) => new CompanionMessageEnvelope(expiry, high, payload);
const open = (store: Store) => new CompanionMessageOutbox('app', 'phone', store, crypto);
test('outbox reopens unchanged, preserves priority FIFO and removes only peer/digest-bound ACK', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'normal', envelope(), 1);
  await queue.enqueue('watch', 'first-high', envelope(100, true), 1);
  await queue.enqueue('watch', 'next-high', envelope(100, true), 1);
  await queue.enqueue('other', 'normal', envelope(), 1);
  const before = store.raw, reopened = open(store), messages = await reopened.pending('watch', 2, 10);
  expect(messages.map(m => m.messageId)).toEqual(['first-high', 'next-high', 'normal']);
  expect(store.raw).toBe(before);
  await expect(reopened.acknowledgeAuthenticated('watch', 'normal', new Uint8Array(32))).rejects.toThrow('content mismatch');
  expect(store.raw).toBe(before);
  expect(await reopened.acknowledgeAuthenticated('unknown', 'normal', messages[2].digest)).toBe(false);
  expect(await reopened.acknowledgeAuthenticated('watch', 'normal', messages[2].digest)).toBe(true);
  expect((await open(store).pending('other', 2, 10)).length).toBe(1);
});
test('outbox rejects changed ID and failed durable CAS without losing stored work', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'id', envelope(), 1); const before = store.raw;
  await queue.enqueue('watch', 'id', envelope(), 1); expect(store.raw).toBe(before);
  await expect(queue.enqueue('watch', 'id', envelope(101), 1)).rejects.toThrow('content mismatch');
  store.fail = true;
  await expect(queue.enqueue('watch', 'new', envelope(), 1)).rejects.toThrow('conflict');
  const message = (await queue.pending('watch', 1, 1))[0];
  await expect(queue.acknowledgeAuthenticated('watch', 'id', message.digest)).rejects.toThrow('conflict');
  expect(store.raw).toBe(before); store.fail = false;
  expect(await queue.expire(100)).toBe(1); expect(await open(store).pending('watch', 100, 1)).toEqual([]);
});
test('outbox copies queued input and returned bytes and detects tampered persistent content', async () => {
  const store = new Store(), queue = open(store), item = envelope(100, false, new Uint8Array(262144).fill(255));
  const saving = queue.enqueue('watch', 'large', item, 1); item.payload.fill(0); await saving;
  const row = (await queue.pending('watch', 2, 1))[0]; expect(row.envelope.payload[0]).toBe(255);
  row.envelope.payload.fill(1); row.digest.fill(0);
  expect((await open(store).pending('watch', 2, 1))[0].envelope.payload[0]).toBe(255);
  const snapshot = JSON.parse(store.raw!); snapshot.messages[0].payload = '00'; store.raw = JSON.stringify(snapshot);
  await expect(open(store).pending('watch', 2, 1)).rejects.toThrow('digest mismatch');
});
test('outbox enforces byte capacity without evicting live messages and binds snapshot identity', async () => {
  const store = new Store(), queue = open(store);
  await queue.enqueue('watch', 'large', envelope(100, false, new Uint8Array(262144)), 1);
  const snapshot = JSON.parse(store.raw!);
  snapshot.messages = Array.from({ length: 31 }, (_, i) => ({ ...snapshot.messages[0], id: 'id' + i }));
  store.raw = JSON.stringify(snapshot); const before = store.raw;
  await expect(queue.enqueue('watch', 'overflow', envelope(100, true, new Uint8Array(262144)), 1)).rejects.toThrow('queue full');
  expect(store.raw).toBe(before);
  await expect(new CompanionMessageOutbox('different', 'phone', store, crypto).pending('watch', 1, 1)).rejects.toThrow('snapshot');
});
