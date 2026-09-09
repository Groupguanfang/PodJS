import { expect, test } from 'bun:test';
import { SystemWorkScheduler, WorkIdentity, WorkPlan } from '../platforms/harmony/entry/src/main/ets/SystemWorkScheduler';
function fixture() {
  let supported = true; let now = 1000; let plans: WorkPlan[] = []; let reads = 0;
  const key = new WorkIdentity(); key.workId = 42; key.runId = 'a'.repeat(32);
  const api = { supported: () => supported, async list() { reads++; return plans; },
    start(plan: WorkPlan) { plans.push(plan); }, stop(plan: WorkPlan) { plans = plans.filter(p => p !== plan); } };
  return { key, api, scheduler: new SystemWorkScheduler('dev.podjs.watch', api, () => now),
    plans: () => plans, reads: () => reads, disable() { supported = false; }, setNow(value: number) { now = value; } };
}
test('OS receives persisted one-shot work with minimum start delay and network condition', async () => {
  const f = fixture(); await f.scheduler.schedule(f.key, 2500, true);
  expect(f.plans()).toHaveLength(1); const plan = f.plans()[0];
  expect(plan.earliestStartTime).toBe(1500); expect(plan.networkType).toBe(0);
  expect(plan.batteryStatus).toBe(2); expect(plan.isPersisted).toBe(true); expect(plan.isRepeat).toBe(false);
  expect(await f.scheduler.list()).toEqual([f.key]);
  await f.scheduler.schedule(f.key, 2500, true); expect(f.plans()).toHaveLength(1);
  await expect(f.scheduler.schedule(f.key, 2501, true)).rejects.toThrow('conflict');
  await f.scheduler.cancel(f.key); await f.scheduler.cancel(f.key); expect(f.plans()).toEqual([]);
});
test('missing timing API rejects before OS IO; malformed identity is never truncated', async () => {
  const f = fixture(); f.disable();
  await expect(f.scheduler.schedule(f.key, 0, false)).rejects.toThrow('unsupported'); expect(f.reads()).toBe(0);
  for (const id of [0, -1, 1.5, NaN, Infinity, 2 ** 32 + 42]) {
    f.key.workId = id; await expect(f.scheduler.cancel(f.key)).rejects.toThrow('invalid_argument');
  }
});
test('foreign generations and other abilities cannot be overwritten or cancelled', async () => {
  const f = fixture(); await f.scheduler.schedule(f.key, 0, false);
  f.plans()[0].parameters.runId = 'b'.repeat(32);
  await expect(f.scheduler.cancel(f.key)).rejects.toThrow('conflict');
  await expect(f.scheduler.schedule(f.key, 0, false)).rejects.toThrow('conflict');
  f.plans()[0].abilityName = 'OtherAbility'; expect(await f.scheduler.list()).toEqual([]);
  expect(f.plans()).toHaveLength(1);
});
test('caller mutation during OS enumeration cannot redirect the scheduled identity', async () => {
  const f = fixture(); const work = f.scheduler.schedule(f.key, 900, false);
  f.key.workId = 99; f.key.runId = 'c'.repeat(32); await work;
  expect(f.plans()[0].workId).toBe(42); expect(f.plans()[0].parameters.runId).toBe('a'.repeat(32));
  expect(f.plans()[0].earliestStartTime).toBe(0); expect(f.plans()[0].networkType).toBeUndefined();
});
test('lost start completion is recoverable by the original durable identity', async () => {
  const f = fixture(); const original = f.api.start;
  f.api.start = plan => { original(plan); throw Error('lost completion'); };
  await expect(f.scheduler.schedule(f.key, 2000, false)).rejects.toThrow('lost completion');
  expect(await f.scheduler.list()).toEqual([f.key]);
  await f.scheduler.schedule(f.key, 2000, false);
  expect(f.plans()).toHaveLength(1);
});
test('accepted stop without observable OS removal does not authorize pruning', async () => {
  const f = fixture(); await f.scheduler.schedule(f.key, 0, false); const stop = f.api.stop;
  f.api.stop = () => {};
  await expect(f.scheduler.cancel(f.key)).rejects.toThrow('busy'); expect(f.plans()).toHaveLength(1);
  f.api.stop = stop; await f.scheduler.cancel(f.key); expect(f.plans()).toHaveLength(0);
});
