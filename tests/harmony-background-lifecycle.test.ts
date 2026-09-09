import { expect, test } from 'bun:test';
import { BackgroundLifecycle } from '../platforms/harmony/entry/src/main/ets/BackgroundLifecycle';
import { WorkIdentity } from '../platforms/harmony/entry/src/main/ets/SystemWorkScheduler';

function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve; }); return { promise, release }; }
function identity(n = 1) { const value = new WorkIdentity(); value.workId = 0x60000000 + n; value.runId = n.toString(16).padStart(32, '0'); return value; }
test('stop while factory loads prevents late execution and coalesces duplicate callbacks', async () => {
  const ready = gate(); let starts = 0; let reconciles = 0;
  const lifecycle = new BackgroundLifecycle(async () => { await ready.promise; return { async start() { starts++; return null; }, stop() {} }; }, async () => { reconciles++; });
  const work = lifecycle.start(identity()); expect(lifecycle.start(identity())).toBe(work);
  lifecycle.stop(identity()); ready.release(); await work;
  expect(starts).toBe(0); expect(reconciles).toBe(0);
});
test('active stop targets frozen generation; result then reconciles durable intents', async () => {
  const entered = gate(); const done = gate(); const stopped: number[] = []; let reconciles = 0;
  const lifecycle = new BackgroundLifecycle(async () => ({ async start() { entered.release(); await done.promise; return null; }, stop(id) { stopped.push(id.workId); } }), async () => { reconciles++; });
  const mutable = identity(); const work = lifecycle.start(mutable); mutable.workId++;
  await entered.promise; lifecycle.stop(identity(2)); expect(stopped).toEqual([]);
  lifecycle.stop(identity()); expect(stopped).toEqual([0x60000001]); done.release(); await work;
  expect(reconciles).toBe(1);
});
test('destroy cancels active runs, suppresses reconciliation and rejects late starts without loading', async () => {
  const entered = gate(); const done = gate(); let loads = 0; let stops = 0; let reconciles = 0;
  const lifecycle = new BackgroundLifecycle(async () => { loads++; return { async start() { entered.release(); await done.promise; return null; }, stop() { stops++; } }; }, async () => { reconciles++; });
  const work = lifecycle.start(identity()); await entered.promise; lifecycle.destroy(); done.release(); await work;
  await lifecycle.start(identity(2)); expect(loads).toBe(1); expect(stops).toBe(1); expect(reconciles).toBe(0);
});
test('factory failure removes in-flight entry and allows a later callback to retry', async () => {
  let loads = 0;
  const lifecycle = new BackgroundLifecycle(async () => { if (++loads === 1) throw new Error('load'); return { async start() { return null; }, stop() {} }; }, async () => {});
  await expect(lifecycle.start(identity())).rejects.toThrow('load'); await lifecycle.start(identity()); expect(loads).toBe(2);
});
