import { expect, test } from 'bun:test';
import { BackgroundServices } from '../platforms/harmony/entry/src/main/ets/BackgroundServices';
import { BackgroundSchedules } from '../platforms/harmony/entry/src/main/ets/BackgroundSchedules';
import { BackgroundJournal } from '../platforms/harmony/entry/src/main/ets/BackgroundJournal';
import { ApprovedBackgroundSource } from '../platforms/harmony/entry/src/main/ets/BackgroundPackage';
import { ServiceReply, ServiceRequest, UnsupportedServices } from '../platforms/harmony/entry/src/main/ets/ServicePump';
function fixture() {
  let raw: string | null = null; let token = 0; let loads = 0; let approval = Promise.resolve();
  const journal = new BackgroundJournal('app', { async read() { return raw; }, async compareExchange(old, next) { if (raw !== old) return false; raw = next; return true; } }, async () => {});
  const approved = new ApprovedBackgroundSource(); approved.appId = 'app'; approved.handlerId = 'refresh'; approved.sha256 = 'a'.repeat(64);
  const schedules = new BackgroundSchedules(journal, { async schedule() {}, async cancel() {} }, async () => { await approval; return approved; }, async () => (++token).toString(16).padStart(32, '0'), () => 0);
  const service = new BackgroundServices(new UnsupportedServices(), async () => { loads++; return schedules; });
  let sequence = 0;
  const request = (method: string, args: Object) => { const req = new ServiceRequest(); req.id = ++sequence; req.method = method; req.args = args; return req; };
  const call = (method: string, args: Object) => new Promise<ServiceReply>(resolve => service.handle(request(method, args), resolve));
  return { journal, service, request, call, loads: () => loads, pause() { let release!: () => void; approval = new Promise<void>(resolve => { release = resolve; }); return release; } };
}
test('guest register defaults optional fields and returns only public status, followed by cancel/status', async () => {
  const f = fixture(); const reply = await f.call('background.register', { task: { id: 'task', handler: 'refresh', earliestAt: 0, grants: ['evil'], sourceHash: 'evil' } });
  expect(reply.ok).toBe(true); expect(JSON.parse(JSON.stringify(reply.value))).toEqual({ id: 'task', state: 'scheduled' });
  const stored = (await f.journal.records())[0]; expect(stored.task.requiresNetwork).toBe(false); expect(stored.task.payload).toBeNull(); expect(stored.grants).toEqual([]);
  expect((await f.call('background.cancel', { id: 'task' })).ok).toBe(true);
  expect((await f.call('background.status', { id: 'task' })).value).toEqual({ id: 'task', state: 'cancelled' });
  expect((await f.call('background.status', { id: 'missing' })).code).toBe('not_found');
});
test('invalid and unsupported periodic tasks are rejected before loading storage', async () => {
  const f = fixture(); expect((await f.call('background.register', { task: { id: 'task', handler: 'refresh', earliestAt: 0, intervalMs: 1 } })).code).toBe('unsupported');
  expect((await f.call('background.cancel', { id: '../bad' })).code).toBe('invalid_argument'); expect(f.loads()).toBe(0);
});
test('request cancellation during package approval prevents durable registration and late reply', async () => {
  const f = fixture(); const release = f.pause(); let replies = 0;
  const req = f.request('background.register', { task: { id: 'task', handler: 'refresh', earliestAt: 0 } });
  f.service.handle(req, () => { replies++; }); await Promise.resolve(); await Promise.resolve();
  f.service.cancel(req.id); release(); await new Promise(resolve => setTimeout(resolve, 0));
  expect(await f.journal.records()).toEqual([]); expect(replies).toBe(0);
});
