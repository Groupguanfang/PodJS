import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionChannelPump } from '../platforms/harmony/companion/src/main/ets/CompanionChannelPump';
import { CompanionState } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { CompanionMessageOutbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageOutbox';
import { CompanionMessageInbox } from '../platforms/harmony/companion/src/main/ets/CompanionMessageInbox';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
class Store {
  raw: string | null = null;
  async read() { return this.raw; }
  async compareExchange(old: string | null, next: string) { if (old !== this.raw) return false; this.raw = next; return true; }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
async function peers() {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(2));
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, ['state', 'message', 'ack'], crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, ['state', 'message', 'ack'], crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  function side(local: string, session: CompanionSyncSession) {
    const store = new Store();
    const state = new CompanionState('app', local, { read: () => store.read(), compareExchange: (_app, old, next) => store.compareExchange(old, next) },
      { async sha256(text) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return 'same-id'; } });
    const outbox = new CompanionMessageOutbox('app', local, new Store(), crypto), inbox = new CompanionMessageInbox('app', local, new Store(), crypto);
    return { state, outbox, inbox, pump: new CompanionChannelPump(session, state, outbox, inbox) };
  }
  return { left: side('phone', a), right: side('watch', b), a, b };
}
test('mixed state/message same ID ACKs are isolated and deferred messages do not block state', async () => {
  const { left, right } = await peers();
  await left.state.set('key', 'value');
  await left.outbox.enqueue('watch', 'same-id', new CompanionMessageEnvelope(100, true, new Uint8Array([3])), 1);
  const message = (await left.pump.sendMessage(2))!;
  const pending = await right.pump.receive(message, 2); expect(pending.status).toBe('pending'); expect(pending.reply).toBeNull();
  const state = (await left.pump.sendState())!; expect(state.messageId).toBe(message.messageId);
  const stateResult = await right.pump.receive(state, 2); expect(stateResult.channel).toBe('state');
  expect((await left.pump.receive(stateResult.reply!, 2)).channel).toBe('state');
  expect(await right.state.get('key')).toBe('value'); expect((await left.outbox.pending('watch', 2, 1)).length).toBe(1);
  const ack = await right.pump.acknowledgeMessage(pending.delivery!, 3);
  expect((await left.pump.receive(ack, 3)).channel).toBe('message'); expect(await left.outbox.pending('watch', 3, 1)).toEqual([]);
  left.pump.close(); right.pump.close();
});
test('failed message application closes mixed session before a following state frame can commit', async () => {
  const { left, right } = await peers();
  await left.outbox.enqueue('watch', 'id', new CompanionMessageEnvelope(100, false, new Uint8Array()), 1); await left.state.set('key', true);
  const message = (await left.pump.sendMessage(2))!, state = (await left.pump.sendState())!;
  await expect(right.pump.receive(message, 2, { async apply() { throw new Error('business failure'); } })).rejects.toThrow('business failure');
  await expect(right.pump.receive(state, 2)).rejects.toThrow('closed'); expect(await right.state.get('key')).toBeUndefined();
  left.pump.close();
});
test('tampering ACK routing hint cannot mutate either durable channel', async () => {
  const { left, right } = await peers(); await left.state.set('key', true);
  const reply = (await right.pump.receive((await left.pump.sendState())!, 2)).reply!; reply.payload[0] = 1;
  await expect(left.pump.receive(reply, 2)).rejects.toThrow(); expect(await left.state.prepare('watch')).not.toBeNull(); right.pump.close();
});
