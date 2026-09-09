import { expect, test } from 'bun:test';
import { BackgroundRecovery } from '../platforms/harmony/entry/src/main/ets/BackgroundRecovery';
function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve; }); return { promise, release }; }
test('foreground pumps coalesce; hide while factory loads suppresses late reconciliation', async () => {
  const ready = gate(); let loads = 0; let reconciles = 0;
  const recovery = new BackgroundRecovery(async () => { loads++; await ready.promise; return { async reconcile() { reconciles++; } }; });
  await recovery.pump(); expect(loads).toBe(0); recovery.setActive(true);
  const work = recovery.pump(); expect(recovery.pump()).toBe(work);
  recovery.setActive(false); ready.release(); await work; expect(reconciles).toBe(0);
  recovery.setActive(true); await recovery.pump(); expect(reconciles).toBe(1);
});
test('queued reconciliation guard is invalidated even after foreground returns', async () => {
  const entered = gate(); const queued = gate(); let effects = 0;
  const recovery = new BackgroundRecovery(async () => ({ async reconcile(current) { entered.release(); await queued.promise; if (current()) effects++; } }));
  recovery.setActive(true); const work = recovery.pump(); await entered.promise;
  recovery.setActive(false); recovery.setActive(true); queued.release(); await work; expect(effects).toBe(0);
  await recovery.pump(); expect(effects).toBe(1);
});
test('failed initialization permits the next maintenance attempt', async () => {
  let attempts = 0;
  const recovery = new BackgroundRecovery(async () => { if (++attempts === 1) throw new Error('busy'); return { async reconcile() {} }; });
  recovery.setActive(true); await expect(recovery.pump()).rejects.toThrow('busy'); await recovery.pump(); expect(attempts).toBe(2);
});
