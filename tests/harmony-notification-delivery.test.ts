import { expect, test } from 'bun:test';
import { NotificationDelivery } from '../platforms/harmony/entry/src/main/ets/NotificationDelivery';
import { ReminderJournal } from '../platforms/harmony/entry/src/main/ets/ReminderJournal';
import { ReminderNotification } from '../platforms/harmony/entry/src/main/ets/ReminderContract';
async function fixture(count = 1) {
  let raw: string | null = null;
  const journal = new ReminderJournal({ async read() { return raw; }, async write(value) { raw = value; } }, {
    async list() { return []; }, async publish(n, notificationId, token) { return { reminderId: 1, notificationId, token }; }, async cancel() {}
  });
  const n = new ReminderNotification(); n.id = 'n'; n.payload = { value: 1 };
  for (let i = 0; i < count; i++) {
    const token = i.toString(16).padStart(32, '0');
    await journal.publish(n, i + 1, token); await journal.captureOpen(token, 'n');
  }
  return journal;
}
test('hidden and unauthorized controllers do not open storage', async () => {
  let authorized = false; let loads = 0;
  const d = new NotificationDelivery(async () => { loads++; throw Error('unexpected'); }, () => authorized, () => true, () => 0);
  await d.pump(); d.setActive(true); await d.pump(); authorized = true; d.setActive(false); await d.pump();
  expect(loads).toBe(0);
});
test('accepted enqueue is not an ACK; unconfirmed events retry and ACK persists', async () => {
  const journal = await fixture(); let now = 0; const output: string[] = [];
  const d = new NotificationDelivery(async () => journal, () => true, json => { output.push(json); return true; }, () => now);
  d.setActive(true); await d.pump(); await d.pump(); expect(output).toHaveLength(1);
  now = 1000; await d.pump(); expect(output).toHaveLength(2);
  const event = JSON.parse(output[0]); expect(await d.acknowledge('forged.open')).toBe(false);
  expect(await d.acknowledge(event.eventId)).toBe(true); expect(await journal.pendingOpens()).toEqual([]);
  now = 2000; await d.pump(); expect(output).toHaveLength(2);
});
test('native backpressure retains pending event without allowing premature ACK', async () => {
  const journal = await fixture(); let now = 0; let blocked = true;
  const d = new NotificationDelivery(async () => journal, () => true, () => !blocked, () => now);
  d.setActive(true); await d.pump(); expect(await d.acknowledge('0'.repeat(32) + '.open')).toBe(false);
  blocked = false; now = 1000; await d.pump(); expect(await d.acknowledge('0'.repeat(32) + '.open')).toBe(true);
});
test('hide while storage read is pending prevents delivery from the stale foreground epoch', async () => {
  const journal = await fixture(); let release: (value: ReminderJournal) => void = () => {};
  const loading = new Promise<ReminderJournal>(resolve => { release = resolve; }); let posts = 0;
  const d = new NotificationDelivery(() => loading, () => true, () => { posts++; return true; }, () => 0);
  d.setActive(true); const pumping = d.pump(); d.setActive(false); d.setActive(true); release(journal); await pumping;
  expect(posts).toBe(0); await d.pump(); expect(posts).toBe(1);
});
test('unacknowledged first batch cannot starve later pending events', async () => {
  const journal = await fixture(32); let now = 0; const seen = new Set<string>();
  const d = new NotificationDelivery(async () => journal, () => true, json => { seen.add(JSON.parse(json).eventId); return true; }, () => now);
  d.setActive(true); await d.pump(); expect(seen.size).toBe(16);
  now = 1000; await d.pump(); expect(seen.size).toBe(32);
});
test('revocation during ACK storage acquisition leaves the event pending', async () => {
  const journal = await fixture(); let authorized = true;
  let defer = false; let release: (value: ReminderJournal) => void = () => {};
  const loading = new Promise<ReminderJournal>(resolve => { release = resolve; });
  const d = new NotificationDelivery(() => defer ? loading : Promise.resolve(journal),
    () => authorized, () => true, () => 0);
  d.setActive(true); await d.pump(); defer = true;
  const id = '0'.repeat(32) + '.open';
  const ack = d.acknowledge(id); authorized = false; release(journal);
  expect(await ack).toBe(false);
  expect(await journal.pendingOpens()).toHaveLength(1);
  authorized = true;
  expect(await d.acknowledge(id)).toBe(true);
  expect(await journal.pendingOpens()).toHaveLength(0);
});
test('failed ACK persistence permits retry without losing delivery eligibility', async () => {
  const journal = await fixture(); let fail = false;
  const d = new NotificationDelivery(async () => {
    if (fail) throw Error('storage unavailable'); return journal;
  }, () => true, () => true, () => 0);
  d.setActive(true); await d.pump(); fail = true;
  const id = '0'.repeat(32) + '.open';
  const first = d.acknowledge(id);
  expect(d.acknowledge(id)).toBe(first);
  await expect(first).rejects.toThrow('storage unavailable');
  expect(await journal.pendingOpens()).toHaveLength(1);
  fail = false;
  expect(await d.acknowledge(id)).toBe(true);
});
