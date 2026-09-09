import { expect, test } from 'bun:test';
import { BackgroundJournal, BackgroundOutcome, BackgroundTask } from '../platforms/harmony/entry/src/main/ets/BackgroundJournal';
import { ApprovedBackgroundSource } from '../platforms/harmony/entry/src/main/ets/BackgroundPackage';
function fixture() {
  let raw: string | null = null; let uncertain = false; let busy = false; let pauses = 0;
  const store = { async read() { if (busy) throw { code: 'busy' }; return raw; },
    async compareExchange(old: string | null, desired: string) {
      if (old !== raw) return false; raw = desired;
      if (uncertain) { uncertain = false; throw { code: 'storage_error' }; } return true;
    } };
  const approved = new ApprovedBackgroundSource(); approved.appId = 'dev.podjs.watch'; approved.handlerId = 'refresh';
  approved.sha256 = 'a'.repeat(64); approved.grants = ['kv.get'];
  const task = new BackgroundTask(); task.id = 'refresh-feed'; task.handler = 'refresh'; task.payload = { label: '后台😀' };
  const open = () => new BackgroundJournal(approved.appId, store, async () => { pauses++; });
  return { task, approved, open, raw: () => raw, corrupt(value: string) { raw = value; },
    uncertain() { uncertain = true; }, busy() { busy = true; }, pauses: () => pauses };
}
function result(status = 'success') { const value = new BackgroundOutcome(); value.status = status; value.code = 'ok'; value.elapsedMs = 1; return value; }
test('prune requires exact OS absence, keeps latest status, and never rewinds allocation', async () => {
  const f = fixture(); const journal = f.open();
  const old = await journal.register(f.task, f.approved, '1'.repeat(32));
  await journal.cancelTask(f.task.id); await journal.confirmCancelled(old.identity);
  const latest = await journal.register(f.task, f.approved, '2'.repeat(32));
  expect(await journal.prune([{ workId: old.identity.workId, runId: 'a'.repeat(32) }])).toBe(0);
  expect(await journal.prune([old.identity, latest.identity])).toBe(1);
  await journal.cancelTask(f.task.id); await journal.confirmCancelled(latest.identity);
  expect(await journal.prune([latest.identity])).toBe(0);
  const next = await f.open().register(f.task, f.approved, '3'.repeat(32));
  expect(next.identity.workId).toBe(latest.identity.workId + 1);
  expect(await journal.claim(old.identity, 'b'.repeat(32), 0)).toBeNull();
});
test('active cancelling records survive pruning even if OS job is absent', async () => {
  const f = fixture(); const journal = f.open(); const old = await journal.register(f.task, f.approved, '1'.repeat(32));
  await journal.claim(old.identity, 'a'.repeat(32), 0); f.task.payload = { changed: true };
  await journal.register(f.task, f.approved, '2'.repeat(32));
  expect(await journal.prune([old.identity])).toBe(0);
  await journal.finish(old.identity, 'a'.repeat(32), result());
  expect(await journal.prune([old.identity])).toBe(1);
});
test('durable registration allocates non-reused IDs and retries uncertain commits', async () => {
  const f = fixture(); f.uncertain();
  await expect(f.open().register(f.task, f.approved, '1'.repeat(32))).rejects.toEqual({ code: 'storage_error' });
  const first = await f.open().register(f.task, f.approved, '1'.repeat(32));
  expect(first.identity.workId).toBe(0x60000000);
  expect((await f.open().register(f.task, f.approved, '2'.repeat(32))).identity).toEqual(first.identity);
  await f.open().cancelTask(f.task.id); expect(await f.open().confirmCancelled(first.identity)).toBe(true);
  const second = await f.open().register(f.task, f.approved, '3'.repeat(32));
  expect(second.identity.workId).toBe(first.identity.workId + 1);
  expect(JSON.parse(f.raw()!).revision).toBe(4);
});
test('concurrent journals cannot claim two application tasks', async () => {
  const f = fixture(); const one = await f.open().register(f.task, f.approved, '1'.repeat(32));
  f.task.id = 'another'; const two = await f.open().register(f.task, f.approved, '2'.repeat(32));
  const claims = await Promise.all([f.open().claim(one.identity, 'a'.repeat(32), 100), f.open().claim(two.identity, 'b'.repeat(32), 100)]);
  expect(claims.filter(Boolean)).toHaveLength(1);
  expect((await f.open().records()).filter(r => r.executionActive)).toHaveLength(1);
});
test('replacement waits for old execution release and stale completion cannot change the new task', async () => {
  const f = fixture(); const journal = f.open(); const old = await journal.register(f.task, f.approved, '1'.repeat(32));
  expect(await journal.claim(old.identity, 'a'.repeat(32), 0)).not.toBeNull();
  f.task.payload = { version: 2 }; const current = await journal.register(f.task, f.approved, '2'.repeat(32));
  expect(await journal.confirmCancelled(old.identity)).toBe(false);
  expect(await journal.claim(current.identity, 'b'.repeat(32), 0)).toBeNull();
  expect(await journal.finish(old.identity, 'c'.repeat(32), result())).toBe(false);
  expect(await journal.finish(old.identity, 'a'.repeat(32), result())).toBe(true);
  expect(await journal.claim(current.identity, 'b'.repeat(32), 0)).not.toBeNull();
  expect(await journal.finish(old.identity, 'a'.repeat(32), result('failure'))).toBe(false);
  const records = await journal.records(); expect(records[0].phase).toBe('cancelled'); expect(records[1].phase).toBe('running');
});
test('clock passage and restart never release an active claim; premature execution is rejected', async () => {
  const f = fixture(); f.task.earliestAt = 1000;
  const record = await f.open().register(f.task, f.approved, '1'.repeat(32));
  expect(await f.open().claim(record.identity, 'a'.repeat(32), 999)).toBeNull();
  expect(await f.open().claim(record.identity, 'a'.repeat(32), 1000)).not.toBeNull();
  expect(await f.open().claim(record.identity, 'b'.repeat(32), 10 ** 12)).toBeNull();
  expect((await f.open().records())[0].executionActive).toBe(true);
});
test('corrupt cursor or multiple execution claims cannot be repaired by overwriting the journal', async () => {
  const f = fixture(); await f.open().register(f.task, f.approved, '1'.repeat(32));
  const state = JSON.parse(f.raw()!); state.nextWorkId--; const damaged = JSON.stringify(state); f.corrupt(damaged);
  await expect(f.open().cancelTask(f.task.id)).rejects.toThrow('corrupt_storage'); expect(f.raw()).toBe(damaged);
  const first = state.records[0]; first.phase = 'running'; first.executionActive = true; first.claimId = 'a'.repeat(32);
  const second = JSON.parse(JSON.stringify(first)); second.identity.workId++; second.identity.runId = '2'.repeat(32);
  second.task.id = 'another'; second.claimId = 'b'.repeat(32); state.records.push(second); state.nextWorkId += 2;
  const overlap = JSON.stringify(state); f.corrupt(overlap);
  await expect(f.open().records()).rejects.toThrow('corrupt_storage'); expect(f.raw()).toBe(overlap);
});
test('contention is bounded and payload quota counts UTF-8 bytes', async () => {
  const f = fixture(); f.task.payload = '中'.repeat(22000);
  await expect(f.open().register(f.task, f.approved, '1'.repeat(32))).rejects.toThrow('invalid_argument');
  expect(f.raw()).toBeNull(); f.task.payload = null; f.busy();
  await expect(f.open().register(f.task, f.approved, '1'.repeat(32))).rejects.toThrow('busy'); expect(f.pauses()).toBe(32);
});
test('retry persists a fresh identity and backoff, and duplicate requests do not create extra work', async () => {
  const f = fixture(); const journal = f.open(); const first = await journal.register(f.task, f.approved, '1'.repeat(32));
  await journal.claim(first.identity, 'a'.repeat(32), 0); await journal.finish(first.identity, 'a'.repeat(32), result('retry'));
  const retry = await journal.retry(first.identity, '2'.repeat(32), 1000);
  expect(retry?.identity.workId).toBe(first.identity.workId + 1); expect(retry?.task.earliestAt).toBe(31000);
  expect(retry?.retryCount).toBe(1); expect((await journal.retry(first.identity, '3'.repeat(32), 99999))?.identity).toEqual(retry?.identity);
  expect((await journal.records()).length).toBe(2);
  await journal.claim(retry!.identity, 'b'.repeat(32), 31000); await journal.finish(retry!.identity, 'b'.repeat(32), result('retry'));
  const next = await journal.retry(retry!.identity, '4'.repeat(32), 32000);
  expect(next?.task.earliestAt).toBe(92000); expect(next?.retryCount).toBe(2);
});
test('cancel or newer registration prevents a completed retry from resurrecting a task', async () => {
  const f = fixture(); const journal = f.open(); const first = await journal.register(f.task, f.approved, '1'.repeat(32));
  await journal.claim(first.identity, 'a'.repeat(32), 0); await journal.finish(first.identity, 'a'.repeat(32), result('retry'));
  await journal.cancelTask(f.task.id); expect(await journal.retry(first.identity, '2'.repeat(32), 1000)).toBeNull();
  const newer = await journal.register(f.task, f.approved, '3'.repeat(32));
  expect(await journal.retry(first.identity, '4'.repeat(32), 1000)).toBeNull();
  expect((await journal.records()).at(-1)?.identity).toEqual(newer.identity);
});
test('retry commit uncertainty is idempotent after reopening the journal', async () => {
  const f = fixture(); const journal = f.open(); const first = await journal.register(f.task, f.approved, '1'.repeat(32));
  await journal.claim(first.identity, 'a'.repeat(32), 0); await journal.finish(first.identity, 'a'.repeat(32), result('retry'));
  f.uncertain(); await expect(journal.retry(first.identity, '2'.repeat(32), 0)).rejects.toEqual({ code: 'storage_error' });
  expect((await f.open().retry(first.identity, '3'.repeat(32), 99999))?.task.earliestAt).toBe(30000);
  expect((await f.open().records()).length).toBe(2);
});
