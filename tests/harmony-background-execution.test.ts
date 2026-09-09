import { expect, test } from 'bun:test';
import { BackgroundExecution } from '../platforms/harmony/entry/src/main/ets/BackgroundExecution';
import { BackgroundJournal, BackgroundOutcome, BackgroundTask } from '../platforms/harmony/entry/src/main/ets/BackgroundJournal';
import { ApprovedBackgroundSource } from '../platforms/harmony/entry/src/main/ets/BackgroundPackage';
function deferred() { let release: () => void = () => {}; const work = new Promise<void>(resolve => { release = resolve; }); return { work, release }; }
async function fixture() {
  let raw: string | null = null; let held = false; let opens = 0; let executions = 0; let cancels = 0;
  let denied = false; let failCommit = false; let prepareWait: Promise<void> = Promise.resolve();
  const events: string[] = []; let startedPrepare = deferred();
  const journal = new BackgroundJournal('app', { async read() { return raw; }, async compareExchange(old, desired) {
    if (old !== raw) return false;
    if (JSON.parse(desired).records.some((r: { result: Object | null }) => r.result)) {
      expect(held).toBe(true); events.push('persist'); if (failCommit) throw { code: 'storage_error' };
    }
    raw = desired; return true;
  } }, async () => {});
  const task = new BackgroundTask(); task.id = 'task'; task.handler = 'handler';
  const approved = new ApprovedBackgroundSource(); approved.appId = 'app'; approved.handlerId = 'handler'; approved.sha256 = 'a'.repeat(64);
  const record = await journal.register(task, approved, '1'.repeat(32));
  const host = { async openLease() {
    if (held) throw { code: 'busy' }; held = true; opens++; events.push('lease');
    return { async prepare() { events.push('prepare'); startedPrepare.release(); await prepareWait; if (denied) throw { code: 'background_permission_denied' }; },
      async execute() { executions++; events.push('execute'); const result = new BackgroundOutcome(); result.status = 'success'; result.code = 'ok'; return result; },
      cancel() { cancels++; }, close() { events.push('close'); held = false; } };
  }, async token() { return 'a'.repeat(32); }, now() { return 1000; } };
  const driver = new BackgroundExecution(journal, host);
  return { driver, journal, record, host, events, executions: () => executions, opens: () => opens, cancels: () => cancels,
    held: () => held, deny() { denied = true; }, fail(value: boolean) { failCommit = value; },
    pausePrepare() { const wait = deferred(); prepareWait = wait.work; return wait; }, prepared: () => startedPrepare.work };
}
test('lease spans recovery, claim, approval, execution and durable result', async () => {
  const f = await fixture(); const result = await f.driver.start(f.record.identity);
  expect(result?.status).toBe('success'); expect(f.events).toEqual(['lease', 'prepare', 'execute', 'persist', 'close']);
  expect((await f.journal.records())[0].phase).toBe('completed'); expect(f.held()).toBe(false);
});
test('duplicate start joins the same execution; stop during approval never executes code', async () => {
  const f = await fixture(); const wait = f.pausePrepare();
  const work = f.driver.start(f.record.identity); expect(f.driver.start(f.record.identity)).toBe(work);
  await f.prepared(); f.driver.stop(f.record.identity); wait.release();
  expect((await work)?.status).toBe('retry'); expect(f.executions()).toBe(0); expect(f.opens()).toBe(1); expect(f.cancels()).toBe(1);
  expect((await f.journal.records())[0].result?.code).toBe('cancelled'); expect(f.held()).toBe(false);
});
test('current-package denial is durable and never executes the lease-holder source', async () => {
  const f = await fixture(); f.deny();
  expect((await f.driver.start(f.record.identity))?.code).toBe('background_permission_denied');
  expect(f.executions()).toBe(0); expect((await f.journal.records())[0].phase).toBe('failed');
});
test('failed result commit leaves active state; next lease owner reconciles without reexecuting', async () => {
  const f = await fixture(); f.fail(true);
  await expect(f.driver.start(f.record.identity)).rejects.toEqual({ code: 'storage_error' });
  expect((await f.journal.records())[0].executionActive).toBe(true); expect(f.held()).toBe(false);
  f.fail(false); const restarted = new BackgroundExecution(f.journal, f.host);
  expect(await restarted.start(f.record.identity)).toBeNull(); expect(f.executions()).toBe(1);
  expect((await f.journal.records())[0].result?.code).toBe('execution_abandoned');
});
test('another live lease prevents recovery of its active claim', async () => {
  const f = await fixture(); const wait = f.pausePrepare(); const original = f.driver.start(f.record.identity); await f.prepared();
  const competing = new BackgroundExecution(f.journal, f.host);
  await competing.recoverAbandoned(); expect((await f.journal.records())[0].phase).toBe('running');
  await expect(competing.start(f.record.identity)).rejects.toEqual({ code: 'busy' });
  expect((await f.journal.records())[0].phase).toBe('running');
  await f.journal.cancelTask(f.record.task.id); f.driver.stop(f.record.identity); wait.release(); await original;
  expect((await f.journal.records())[0].phase).toBe('cancelled');
});
test('foreground recovery finishes abandoned claims under lease without starting guest code', async () => {
  const f = await fixture(); await f.journal.claim(f.record.identity, 'a'.repeat(32), 0);
  await f.driver.recoverAbandoned();
  const record = (await f.journal.records())[0];
  expect(record.result?.code).toBe('execution_abandoned'); expect(record.phase).toBe('failed');
  expect(f.executions()).toBe(0); expect(f.events).toEqual(['lease', 'persist', 'close']);
  const opens = f.opens(); await f.driver.recoverAbandoned(); expect(f.opens()).toBe(opens);
});
test('inactive maintenance leaves claim untouched; cancelling abandoned claim ends cancelled', async () => {
  const f = await fixture(); await f.journal.claim(f.record.identity, 'a'.repeat(32), 0);
  await f.driver.recoverAbandoned(() => false); expect(f.opens()).toBe(0);
  expect((await f.journal.records())[0].executionActive).toBe(true);
  await f.journal.cancelTask(f.record.task.id); await f.driver.recoverAbandoned();
  expect((await f.journal.records())[0].phase).toBe('cancelled'); expect(f.executions()).toBe(0);
});
