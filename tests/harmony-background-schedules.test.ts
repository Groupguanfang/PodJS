import { expect, test } from 'bun:test';
import { BackgroundSchedules, BackgroundCoordinationLease } from '../platforms/harmony/entry/src/main/ets/BackgroundSchedules';
import { BackgroundJournal, BackgroundTask, BackgroundOutcome } from '../platforms/harmony/entry/src/main/ets/BackgroundJournal';
import { ApprovedBackgroundSource } from '../platforms/harmony/entry/src/main/ets/BackgroundPackage';
import { WorkIdentity } from '../platforms/harmony/entry/src/main/ets/SystemWorkScheduler';
function fixture() {
  let raw: string | null = null; let token = 0; let fail = false;
  const works = new Map<number, string>(); const events: string[] = [];
  const journal = new BackgroundJournal('app', { async read() { return raw; }, async compareExchange(old, next) {
    if (old !== raw) return false; raw = next; return true;
  } }, async () => {});
  const approved = new ApprovedBackgroundSource(); approved.appId = 'app'; approved.handlerId = 'refresh'; approved.sha256 = 'a'.repeat(64);
  const system = { async schedule(key: WorkIdentity) {
    expect((await journal.records()).some(r => r.identity.runId === key.runId)).toBe(true);
    works.set(key.workId, key.runId); events.push('start'); if (fail) { fail = false; throw Error('lost acceptance'); }
  }, async cancel(key: WorkIdentity) { works.delete(key.workId); events.push('cancel'); } };
  const open = (lease: (() => Promise<BackgroundCoordinationLease>) | null = null) => new BackgroundSchedules(journal, system, async () => approved,
    async () => (++token).toString(16).padStart(32, '0'), () => 1000, lease);
  const task = new BackgroundTask(); task.id = 'task'; task.handler = 'refresh';
  return { journal, open, task, works, events, fail() { fail = true; } };
}
test('durable intent precedes OS submission and lost acceptance recovers after reopen', async () => {
  const f = fixture(); f.fail(); await expect(f.open().register(f.task)).rejects.toThrow('lost acceptance');
  expect((await f.journal.records())[0].phase).toBe('pending'); expect(f.works.size).toBe(1);
  await f.open().reconcile(); expect((await f.open().status(f.task.id))?.phase).toBe('scheduled'); expect(f.works.size).toBe(1);
});
test('coordination lease spans OS effects and releases even when accepted scheduling reports failure', async () => {
  const f = fixture(); let held = false; let closes = 0;
  const schedules = f.open(async () => { expect(held).toBe(false); held = true; return { close() { expect(f.events.length).toBeGreaterThan(0); held = false; closes++; } }; });
  f.fail(); await expect(schedules.register(f.task)).rejects.toThrow('lost acceptance');
  expect(held).toBe(false); expect(closes).toBe(1);
  await schedules.reconcile(); expect(closes).toBe(2); expect(f.works.size).toBe(1);
});
test('unavailable coordinator lease rejects before durable intent or system effects', async () => {
  const f = fixture(); const schedules = f.open(async () => { throw { code: 'busy' }; });
  await expect(schedules.register(f.task)).rejects.toEqual({ code: 'busy' });
  expect(await f.journal.records()).toEqual([]); expect(f.events).toEqual([]);
});
test('retry removes the old OS work and schedules a fresh durable generation', async () => {
  const f = fixture(); const initial = await f.open().register(f.task);
  await f.journal.claim(initial.identity, 'a'.repeat(32), 0);
  const result = new BackgroundOutcome(); result.status = 'retry'; result.code = 'retry';
  await f.journal.finish(initial.identity, 'a'.repeat(32), result); await f.open().reconcile();
  const retry = await f.open().status(f.task.id);
  expect(retry?.identity.workId).toBe(initial.identity.workId + 1); expect(retry?.task.earliestAt).toBe(31000);
  expect(f.works.has(initial.identity.workId)).toBe(false); expect(f.works.size).toBe(1);
  expect(f.events).toEqual(['start', 'cancel', 'start']);
});
test('cancelled retry intent stays cancelled through repeated reconciliation', async () => {
  const f = fixture(); const initial = await f.open().register(f.task); await f.journal.claim(initial.identity, 'a'.repeat(32), 0);
  const result = new BackgroundOutcome(); result.status = 'retry'; result.code = 'retry';
  await f.journal.finish(initial.identity, 'a'.repeat(32), result);
  await f.open().cancel(f.task.id); await f.open().reconcile();
  expect(f.works.size).toBe(0); expect((await f.journal.records()).length).toBe(1);
  expect((await f.open().status(f.task.id))?.phase).toBe('cancelled');
});
test('completed generations can be replaced more than journal capacity while preserving current status', async () => {
  const f = fixture(); const schedules = f.open(); let lastId = 0;
  for (let n = 0; n < 140; n++) {
    const record = await schedules.register(f.task); expect(record.identity.workId).toBeGreaterThan(lastId); lastId = record.identity.workId;
    await schedules.cancel(f.task.id);
  }
  expect((await f.journal.records()).length).toBe(1);
  expect((await schedules.status(f.task.id))?.phase).toBe('cancelled'); expect(f.works.size).toBe(0);
});
