import { expect, test } from 'bun:test';
import { NotificationSchedules, NotificationServices } from '../platforms/harmony/entry/src/main/ets/NotificationSchedules';
import { ReminderJournal } from '../platforms/harmony/entry/src/main/ets/ReminderJournal';
import { ReminderHandle, ReminderNotification } from '../platforms/harmony/entry/src/main/ets/ReminderContract';
import { ServiceRequest, ServiceReply, UnsupportedServices } from '../platforms/harmony/entry/src/main/ets/ServicePump';
function fixture() {
  let raw: string | null = null; let nonce = 0;
  const state = { handles: [] as ReminderHandle[], publishes: 0, cancels: 0 };
  const journal = new ReminderJournal({ async read() { return raw; }, async write(value) { raw = value; } }, {
    async list() { return state.handles; },
    async publish(value, notificationId, token) {
      const handle = new ReminderHandle(); handle.notificationId = notificationId; handle.token = token;
      handle.reminderId = ++state.publishes; state.handles.push(handle); return handle;
    },
    async cancel(handle) { state.cancels++; state.handles = state.handles.filter(h => h.token !== handle.token); }
  });
  const schedules = new NotificationSchedules(journal, async () => (++nonce).toString(16).padStart(32, '0'));
  return { journal, schedules, state };
}
function value() { const n = new ReminderNotification(); n.id = 'same'; n.title = '中文'; n.body = 'hello'; n.payload = { version: 1 }; return n; }
test('concurrent duplicate schedules publish once and use host identities', async () => {
  const f = fixture(); await Promise.all([f.schedules.schedule(value(), () => true), f.schedules.schedule(value(), () => true)]);
  expect(f.state.publishes).toBe(1); expect(f.state.handles[0].notificationId).toBe(0x50000000);
  expect((await f.schedules.list(() => true))[0].payload).toEqual({ version: 1 });
});
test('replacement cancels old generation and retains its payload while publishing a fresh identity', async () => {
  const f = fixture(); await f.schedules.schedule(value(), () => true);
  const next = value(); next.payload = { version: 2 }; await f.schedules.schedule(next, () => true);
  expect(f.state.publishes).toBe(2); expect(f.state.cancels).toBe(1); expect(f.state.handles).toHaveLength(1);
  const records = await f.journal.recover(); expect(records.map(r => r.phase)).toEqual(['cancelled', 'ready']);
  expect(records[0].value.payload).toEqual({ version: 1 }); expect(records[1].value.payload).toEqual({ version: 2 });
  expect(records[0].handle.token).not.toBe(records[1].handle.token);
});
test('cancel is idempotent, expired OS records leave pending list, and later scheduling uses a new generation', async () => {
  const f = fixture(); await f.schedules.schedule(value(), () => true);
  f.state.handles = []; expect(await f.schedules.list(() => true)).toEqual([]);
  await f.schedules.schedule(value(), () => true); expect(f.state.publishes).toBe(2);
  await f.schedules.cancel('same', () => true); await f.schedules.cancel('same', () => true);
  expect(f.state.cancels).toBe(1); expect(await f.schedules.list(() => true)).toEqual([]);
});
test('cancelled queued operations and invalid replacements do not mutate the OS', async () => {
  const f = fixture(); await f.schedules.schedule(value(), () => false); expect(f.state.publishes).toBe(0);
  await f.schedules.schedule(value(), () => true);
  const bad = value(); bad.actions = [{ id: 'x', title: 'x' }];
  await expect(f.schedules.schedule(bad, () => true)).rejects.toThrow('unsupported');
  expect(f.state.cancels).toBe(0); expect(f.state.publishes).toBe(1);
});
test('guest service methods route through the coordinator and return pending data', async () => {
  const f = fixture(); const service = new NotificationServices(new UnsupportedServices(), async () => f.schedules);
  const invoke = (method: string, args: Object) => new Promise<ServiceReply>(resolve => {
    const request = new ServiceRequest(); request.id = 1; request.method = method; request.args = args;
    service.handle(request, resolve);
  });
  expect((await invoke('notifications.schedule', { notification: value() })).ok).toBe(true);
  expect((await invoke('notifications.listPending', {})).value).toEqual([value()]);
  expect((await invoke('notifications.cancel', { id: 'same' })).ok).toBe(true);
  expect((await invoke('notifications.listPending', {})).value).toEqual([]);
});
