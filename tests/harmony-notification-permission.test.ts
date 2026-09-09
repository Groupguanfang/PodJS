import { expect, test } from 'bun:test';
import { NotificationPermissions, NotificationPermissionError } from '../platforms/harmony/entry/src/main/ets/NotificationPermissions';
import { ServiceRequest, ServiceReply } from '../platforms/harmony/entry/src/main/ets/ServicePump';

function request(id: number, method = 'notifications.requestPermission') {
  const value = new ServiceRequest(); value.id = id; value.method = method; return value;
}
async function settle() { for (let i = 0; i < 15; i++) await Promise.resolve(); }

test('status reflects OS boolean and does not prompt; existing grant skips prompt', async () => {
  let enabled = false; let prompts = 0;
  const service = new NotificationPermissions({ async isEnabled() { return enabled; }, async requestEnable() { prompts++; } });
  const results: ServiceReply[] = [];
  service.handle(request(1, 'notifications.status'), r => results.push(r)); await settle();
  expect(results[0]).toMatchObject({ ok: true, value: 'denied' });
  enabled = true; service.handle(request(2), r => results.push(r)); await settle();
  expect(results[1]).toMatchObject({ ok: true, value: 'granted' }); expect(prompts).toBe(0);
});

test('concurrent permission callers share one dialog and requery system state', async () => {
  let enabled = false; let prompts = 0; let finish = () => {};
  const dialog = new Promise<void>(resolve => { finish = resolve; });
  const service = new NotificationPermissions({ async isEnabled() { return enabled; }, requestEnable() { prompts++; return dialog; } });
  const results: ServiceReply[] = [];
  service.handle(request(1), r => results.push(r)); service.handle(request(2), r => results.push(r));
  await settle(); expect(prompts).toBe(1); expect(results).toHaveLength(0);
  enabled = true; finish(); await settle();
  expect(results.map(r => r.value)).toEqual(['granted', 'granted']);
});

test('cancel before status response suppresses prompt and cancelled dialog result is ignored', async () => {
  let state = (value: boolean) => {}; let prompts = 0;
  const query = new Promise<boolean>(resolve => { state = resolve; });
  let finish = () => {}; const dialog = new Promise<void>(resolve => { finish = resolve; });
  const service = new NotificationPermissions({ isEnabled() { return query; }, requestEnable() { prompts++; return dialog; } });
  let completions = 0;
  service.handle(request(1), () => completions++); service.cancel(1); state(false); await settle();
  expect(prompts).toBe(0); expect(completions).toBe(0);
  service.handle(request(2), () => completions++); await settle(); expect(prompts).toBe(1);
  service.cancel(2); finish(); await settle(); expect(completions).toBe(0);
});

test('denial is a permission result; busy and system errors remain errors and can retry', async () => {
  let code = 'denied';
  const service = new NotificationPermissions({ async isEnabled() { return false; }, async requestEnable() { throw new NotificationPermissionError(code); } });
  const results: ServiceReply[] = [];
  service.handle(request(1), r => results.push(r)); await settle();
  expect(results[0]).toMatchObject({ ok: true, value: 'denied' });
  code = 'busy'; service.handle(request(2), r => results.push(r)); await settle();
  expect(results[1]).toMatchObject({ ok: false, code: 'busy' });
  code = 'host_error'; service.handle(request(3), r => results.push(r)); await settle();
  expect(results[2]).toMatchObject({ ok: false, code: 'host_error' });
});

test('unimplemented notification methods never touch OS permission APIs', () => {
  const service = new NotificationPermissions({ isEnabled() { throw Error('unexpected'); }, requestEnable() { throw Error('unexpected'); } });
  service.handle(request(1, 'notifications.schedule'), r => expect(r.code).toBe('unsupported'));
});
