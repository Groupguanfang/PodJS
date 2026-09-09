import { expect, test } from 'bun:test';
import { NotificationOpenReceiver } from '../platforms/harmony/entry/src/main/ets/NotificationOpenReceiver';
import { ReminderJournal } from '../platforms/harmony/entry/src/main/ets/ReminderJournal';
import { ReminderNotification } from '../platforms/harmony/entry/src/main/ets/ReminderContract';
const token = '0123456789abcdef0123456789abcdef';
test('invalid launch metadata does not open storage', async () => {
  let loads = 0;
  const receiver = new NotificationOpenReceiver(async () => { loads++; throw Error('unexpected'); });
  for (const [t, id] of [[undefined, undefined], ['bad', 'n'], [token, 'bad\0id'], [token, 42]])
    expect(await receiver.capture(t, id)).toBe(false);
  expect(loads).toBe(0);
});
test('concurrent opens deduplicate, unknown tokens cannot invent payload, and failed capture retries', async () => {
  let raw: string | null = null; let fail = false; let loads = 0;
  const journal = new ReminderJournal({ async read() { return raw; }, async write(value) { if (fail) throw Error('disk'); raw = value; } }, {
    async list() { return []; }, async publish(value, notificationId, token) { return { reminderId: 1, notificationId, token }; }, async cancel() {}
  });
  const value = new ReminderNotification(); value.id = 'n'; value.title = 'title'; value.payload = { private: 'payload' };
  await journal.publish(value, 1, token);
  const receiver = new NotificationOpenReceiver(async () => { loads++; return journal; });
  fail = true;
  const first = receiver.capture(token, 'n'); const second = receiver.capture(token, 'n');
  expect(first).toBe(second); await expect(first).rejects.toThrow('disk'); expect(loads).toBe(1);
  fail = false; await receiver.retry();
  expect((await journal.pendingOpens())[0].payload).toEqual({ private: 'payload' });
  expect(await receiver.capture('ffffffffffffffffffffffffffffffff', 'n')).toBe(false);
  expect(await journal.pendingOpens()).toHaveLength(1);
});
