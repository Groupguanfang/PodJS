import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionMessageInbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageInbox';
import { CompanionMessageEnvelope, encodeMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
class Store {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(old: string | null, next: string) { if (this.fail || this.raw !== old) return false; this.raw = next; return true; }
}
const crypto = { async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } };
const open = (store: Store) => new CompanionMessageInbox('app', 'phone', store, crypto);
const wire = (expiry = 100, high = false) => encodeMessageEnvelope(new CompanionMessageEnvelope(expiry, high, new Uint8Array([1, 2, 3])));
test('inbox persists pending before delivery and keeps applied receipt across reopen and lost ACK replay', async () => {
  const store = new Store(), inbox = open(store), bytes = wire();
  const pending = inbox.receiveAuthenticated('watch', 'id', bytes, 1); bytes.fill(0);
  const delivery = await pending; expect(delivery.status).toBe('pending'); expect(store.raw).not.toBeNull();
  expect((await open(store).pending(2, 10)).length).toBe(1);
  expect((await open(store).receiveAuthenticated('watch', 'id', wire(), 2)).status).toBe('pending');
  await inbox.acknowledge('watch', 'id', delivery.digest, 2);
  expect(await open(store).pending(2, 10)).toEqual([]);
  expect((await open(store).receiveAuthenticated('watch', 'id', wire(), 3)).status).toBe('applied');
  await expect(inbox.receiveAuthenticated('watch', 'id', wire(101), 3)).rejects.toThrow('content changed');
});
test('inbox never reports applied after failed receipt commit and refuses stale tokens or expired completion', async () => {
  const store = new Store(), inbox = open(store), delivery = await inbox.receiveAuthenticated('watch', 'id', wire(), 1);
  const before = store.raw; store.fail = true;
  await expect(inbox.acknowledge('watch', 'id', delivery.digest, 2)).rejects.toThrow('conflict'); expect(store.raw).toBe(before);
  expect((await open(store).pending(2, 1))[0].status).toBe('pending'); store.fail = false;
  await expect(inbox.acknowledge('watch', 'id', new Uint8Array(32), 2)).rejects.toThrow('changed');
  await expect(inbox.acknowledge('other', 'id', delivery.digest, 2)).rejects.toThrow('unknown');
  await expect(inbox.acknowledge('watch', 'id', delivery.digest, 100)).rejects.toThrow('expired');
  expect((await inbox.receiveAuthenticated('watch', 'id', wire(), 100)).status).toBe('expired');
});
test('inbox retains live receipts at count limit and rejects corrupt snapshot identity', async () => {
  const store = new Store(), inbox = open(store), delivery = await inbox.receiveAuthenticated('watch', 'id', wire(), 1);
  await inbox.acknowledge('watch', 'id', delivery.digest, 2);
  const snapshot = JSON.parse(store.raw!); snapshot.records = Array.from({ length: 1000 }, (_, i) => ({ ...snapshot.records[0], id: 'id' + i }));
  store.raw = JSON.stringify(snapshot); const before = store.raw;
  await expect(inbox.receiveAuthenticated('watch', 'new', wire(), 3)).rejects.toThrow('full'); expect(store.raw).toBe(before);
  snapshot.local = 'wrong'; store.raw = JSON.stringify(snapshot); await expect(inbox.pending(2, 1)).rejects.toThrow('snapshot');
});
