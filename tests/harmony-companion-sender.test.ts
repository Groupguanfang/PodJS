import { expect, test } from 'bun:test';
import { createHash, randomUUID } from 'node:crypto';
import { CompanionState, type CompanionStatePort, type CompanionStateCrypto } from '../platforms/harmony/companion/src/main/ets/CompanionState';
class Port implements CompanionStatePort {
  raw: string | null = null;
  fail: 'before' | 'after' | null = null;
  async read() { return this.raw; }
  async compareExchange(_app: string, old: string | null, next: string) {
    if (old !== this.raw) return false;
    const fail = this.fail; this.fail = null;
    if (fail === 'before') throw Error('before commit');
    this.raw = next;
    if (fail === 'after') throw Error('after commit');
    return true;
  }
}
const crypto: CompanionStateCrypto = {
  async sha256(text) { return createHash('sha256').update(text, 'utf8').digest('hex'); },
  async messageId() { return randomUUID(); },
};
function sdk(port: Port) { return new CompanionState('app', 'phone', port, crypto); }
test('600-entry frozen cycle splits, reopens unchanged and retains edits until the next cycle', async () => {
  const port = new Port(), sender = sdk(port), receiver = new CompanionState('app', 'watch', new Port());
  const entries = Array.from({ length: 600 }, (_, i) => ({ key: 'k' + String(i).padStart(3, '0'), value: i, counter: i + 1, deviceId: 'phone', deleted: false }));
  await sender.receiveAuthenticated('seed', 0, 1, entries.slice(0, 512));
  await sender.receiveAuthenticated('seed', 1, 2, entries.slice(512));
  const first = (await sender.prepare('watch'))!;
  expect(JSON.parse(first.payload).entries).toHaveLength(512);
  expect(await sdk(port).prepare('watch')).toEqual(first);
  await sender.set('k599', 'later');
  const incoming = JSON.parse(first.payload);
  await receiver.receiveAuthenticated('phone', incoming.from, incoming.to, incoming.entries);
  expect(await sender.acknowledgeAuthenticated('watch', first.messageId, first.to, first.digest)).toBe(true);
  const second = (await sdk(port).prepare('watch'))!;
  const body = JSON.parse(second.payload); expect(body.entries).toHaveLength(88); expect(body.entries.at(-1).value).toBe(599);
  await receiver.receiveAuthenticated('phone', body.from, body.to, body.entries);
  expect(await sender.acknowledgeAuthenticated('watch', second.messageId, second.to, second.digest)).toBe(true);
  const nextCycle = (await sdk(port).prepare('watch'))!; expect(nextCycle.from).toBe(2);
  expect(await receiver.get('k599')).toBe(599);
  const third = JSON.parse(nextCycle.payload);
  await receiver.receiveAuthenticated('phone', third.from, third.to, third.entries);
  await sender.acknowledgeAuthenticated('watch', nextCycle.messageId, nextCycle.to, nextCycle.digest);
  const last = (await sender.prepare('watch'))!, fourth = JSON.parse(last.payload);
  await receiver.receiveAuthenticated('phone', fourth.from, fourth.to, fourth.entries);
  await sender.acknowledgeAuthenticated('watch', last.messageId, last.to, last.digest);
  expect(await receiver.get('k599')).toBe('later'); expect(await sender.prepare('watch')).toBeNull();
});
test('empty initial cycle and tombstones survive ACK and unchanged state stops sending', async () => {
  const port = new Port(), sender = sdk(port);
  const initial = (await sender.prepare('watch'))!; expect(JSON.parse(initial.payload).entries).toEqual([]);
  await sender.acknowledgeAuthenticated('watch', initial.messageId, initial.to, initial.digest);
  expect(await sdk(port).prepare('watch')).toBeNull();
  await sender.delete('gone'); const tombstone = (await sender.prepare('watch'))!;
  expect(JSON.parse(tombstone.payload).entries[0].deleted).toBe(true);
  await sender.acknowledgeAuthenticated('watch', tombstone.messageId, tombstone.to, tombstone.digest);
  expect(await sdk(port).prepare('watch')).toBeNull();
});
test('ACK peer, identity, cursor and digest must all match and failed commit retains pending', async () => {
  const port = new Port(), sender = sdk(port), pending = (await sender.prepare('watch'))!;
  for (const [peer, id, to, hash] of [
    ['other', pending.messageId, pending.to, pending.digest], ['watch', 'wrong', pending.to, pending.digest],
    ['watch', pending.messageId, pending.to + 1, pending.digest], ['watch', pending.messageId, pending.to, '0'.repeat(64)],
  ] as const) expect(await sender.acknowledgeAuthenticated(peer, id, to, hash)).toBe(false);
  port.fail = 'before'; await expect(sender.acknowledgeAuthenticated('watch', pending.messageId, pending.to, pending.digest)).rejects.toThrow();
  expect(await sdk(port).prepare('watch')).toEqual(pending);
  port.fail = 'after'; await expect(sender.acknowledgeAuthenticated('watch', pending.messageId, pending.to, pending.digest)).rejects.toThrow();
  expect(await sdk(port).prepare('watch')).toBeNull();
});
test('uncertain prepare returns no success but reopening adopts identical durable batch', async () => {
  const port = new Port(), sender = sdk(port); let notifications = 0; sender.subscribe(() => { notifications++; });
  port.fail = 'after'; await expect(sender.prepare('watch')).rejects.toThrow();
  const saved = JSON.parse(port.raw!).outgoing[0].pending;
  expect(await sdk(port).prepare('watch')).toEqual(saved); expect(notifications).toBe(0);
  const broken = JSON.parse(port.raw!); broken.outgoing[0].pending.digest = '0'.repeat(64); port.raw = JSON.stringify(broken);
  await expect(sdk(port).prepare('watch')).rejects.toThrow('digest');
});
test('UTF-8 byte budgets bound batches and old state-only schema migrates without cursor reset', async () => {
  const port = new Port(), sender = sdk(port);
  for (let i = 0; i < 5; i++) await sender.set('k' + i, '中'.repeat(30000));
  const state = JSON.parse(port.raw!); state.schema = 1; delete state.outgoing; delete state.incoming; port.raw = JSON.stringify(state);
  const batch = (await sdk(port).prepare('watch'))!;
  expect(Buffer.byteLength(batch.payload)).toBeLessThanOrEqual(256 * 1024);
  expect(JSON.parse(batch.payload).entries).toHaveLength(2);
  expect(JSON.parse(port.raw!).schema).toBe(3);
  expect((await sender.snapshot()).clock).toBe(5);
  const downgraded = JSON.parse(port.raw!); downgraded.schema = 1; port.raw = JSON.stringify(downgraded);
  await expect(sender.prepare('watch')).rejects.toThrow('legacy');
});
