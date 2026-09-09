import { expect, test } from 'bun:test';
import { ReminderJournal, type ReminderStore } from '../platforms/harmony/entry/src/main/ets/ReminderJournal';
import { ReminderHandle, ReminderNotification, type ReminderApi } from '../platforms/harmony/entry/src/main/ets/ReminderContract';
const token = '0123456789abcdef0123456789abcdef';
class Store implements ReminderStore {
  raw: string | null = null; writes = 0; failAt = 0;
  async read() { return this.raw; }
  async write(raw: string) { if (++this.writes === this.failAt) throw Error('disk'); this.raw = raw; }
}
class Api implements ReminderApi {
  handles: ReminderHandle[] = []; publishes = 0; cancels = 0;
  async list() { return this.handles; }
  async publish(n: ReminderNotification, id: number, token: string) {
    this.publishes++; const h = new ReminderHandle(); h.notificationId = id; h.token = token; h.reminderId = 99;
    this.handles = [h]; return h;
  }
  async cancel(h: ReminderHandle) { this.cancels++; this.handles = []; }
}
function value() { const n = new ReminderNotification(); n.id = 'n'; n.title = '中文'; n.body = 'body'; n.payload = { answer: 42 }; return n; }
test('failed intent persistence performs no OS mutation', async () => {
  const s = new Store(); s.failAt = 1; const a = new Api(); const j = new ReminderJournal(s, a);
  await expect(j.publish(value(), 1, token)).rejects.toThrow('disk'); expect(a.publishes).toBe(0);
});
test('restart adopts OS acceptance after lost completion write without republishing', async () => {
  const s = new Store(); s.failAt = 2; const a = new Api();
  await expect(new ReminderJournal(s, a).publish(value(), 1, token)).rejects.toThrow('disk');
  const recovered = await new ReminderJournal(s, a).recover();
  expect(recovered[0].phase).toBe('ready'); expect(recovered[0].value.payload).toEqual({ answer: 42 });
  await new ReminderJournal(s, a).publish(value(), 1, token); expect(a.publishes).toBe(1);
});
test('ambiguous already-fired publication is retained, not silently duplicated or discarded', async () => {
  const s = new Store(); s.failAt = 2; const a = new Api();
  await expect(new ReminderJournal(s, a).publish(value(), 1, token)).rejects.toThrow(); a.handles = [];
  const j = new ReminderJournal(s, a); expect((await j.recover())[0].phase).toBe('uncertain');
  await expect(j.publish(value(), 1, token)).rejects.toThrow('uncertain_delivery'); expect(a.publishes).toBe(1);
  await j.cancel(token); expect((await j.recover())[0].phase).toBe('cancelled');
});
test('cancellation completion-write failure recovers idempotently and retains metadata', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  await j.publish(value(), 1, token); s.failAt = 4;
  await expect(j.cancel(token)).rejects.toThrow('disk'); expect(a.cancels).toBe(1);
  const records = await new ReminderJournal(s, a).recover();
  expect(records[0].phase).toBe('cancelled'); expect(records[0].value.id).toBe('n'); expect(a.cancels).toBe(1);
});
test('corrupt persisted data fails closed and caller mutation cannot change queued payload', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  const n = value(); const pending = j.publish(n, 1, token); n.title = 'changed'; await pending;
  expect((await j.recover())[0].value.title).toBe('中文');
  s.raw = '{"version":2,"records":[]}'; await expect(j.recover()).rejects.toThrow('journal_corrupt');
  expect(a.publishes).toBe(1);
});
test('open events persist across restart, bind old generation payload and deduplicate after ACK', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  await j.publish(value(), 1, token); await j.cancel(token);
  const secondToken = 'fedcba9876543210fedcba9876543210';
  const newer = value(); newer.payload = { answer: 99 }; await j.publish(newer, 2, secondToken);
  expect(await j.captureOpen(token, 'wrong')).toBe(false);
  expect(await j.captureOpen('unknown', 'n')).toBe(false);
  expect(await j.acknowledgeOpen(token + '.open')).toBe(false);
  expect(await j.captureOpen(token, 'n')).toBe(true);
  const reopened = new ReminderJournal(s, a);
  const events = await reopened.pendingOpens(); expect(events).toHaveLength(1);
  expect(events[0]).toMatchObject({ t: 'notification.open', notificationId: 'n', payload: { answer: 42 } });
  expect(await reopened.captureOpen(token, 'n')).toBe(true);
  expect(await reopened.acknowledgeOpen(events[0].eventId)).toBe(true);
  const again = new ReminderJournal(s, a); await again.captureOpen(token, 'n');
  expect(await again.pendingOpens()).toEqual([]);
});
test('open persistence failure is reported and retry captures exactly one pending event', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  await j.publish(value(), 1, token); s.failAt = 3;
  await expect(j.captureOpen(token, 'n')).rejects.toThrow('disk');
  expect(await j.pendingOpens()).toEqual([]);
  await j.captureOpen(token, 'n'); expect(await j.pendingOpens()).toHaveLength(1);
});
test('only acknowledged terminal records are reclaimed and IDs never rewind after restart', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  const id = await j.allocateNotificationId(); await j.publish(value(), id, token);
  await j.captureOpen(token, 'n'); await j.acknowledgeOpen(token + '.open');
  expect(await j.reclaimAcknowledged()).toBe(0); // Still scheduled in OS.
  await j.cancel(token); expect(await j.reclaimAcknowledged()).toBe(1);
  const reopened = new ReminderJournal(s, a);
  expect(await reopened.allocateNotificationId()).toBe(id + 1);
  expect(await reopened.captureOpen(token, 'n')).toBe(false);
  const next = 'f'.repeat(32); await reopened.publish(value(), id + 2, next); await reopened.cancel(next);
  expect(await reopened.reclaimAcknowledged()).toBe(0); // Never acknowledged.
});
test('version one migrates cursor while version two missing cursor fails closed', async () => {
  const s = new Store(); const a = new Api();
  await new ReminderJournal(s, a).publish(value(), 0x50000010, token);
  const legacy = JSON.parse(s.raw!); legacy.version = 1; delete legacy.nextNotificationId; s.raw = JSON.stringify(legacy);
  expect(await new ReminderJournal(s, a).allocateNotificationId()).toBe(0x50000011);
  expect(JSON.parse(s.raw!).version).toBe(2);
  const corrupt = JSON.parse(s.raw!); delete corrupt.nextNotificationId; s.raw = JSON.stringify(corrupt);
  await expect(new ReminderJournal(s, a).allocateNotificationId()).rejects.toThrow('journal_corrupt');
});
test('uncertain allocation commit leaves a gap rather than reusing an ID', async () => {
  const s = new Store(); const a = new Api();
  const write = s.write.bind(s);
  s.write = async raw => { await write(raw); throw Error('after commit'); };
  await expect(new ReminderJournal(s, a).allocateNotificationId()).rejects.toThrow('after commit');
  s.write = write;
  expect(await new ReminderJournal(s, a).allocateNotificationId()).toBe(0x50000001);
  expect(a.publishes).toBe(0);
});
test('recovery can reclaim newly finished acknowledged records in the same transaction', async () => {
  const s = new Store(); const a = new Api(); const j = new ReminderJournal(s, a);
  await j.publish(value(), 1, token); await j.captureOpen(token, 'n'); await j.acknowledgeOpen(token + '.open');
  a.handles = [];
  expect(await j.recover(true)).toEqual([]);
  expect(await new ReminderJournal(s, a).captureOpen(token, 'n')).toBe(false);
});
