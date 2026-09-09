const assert = require('node:assert/strict');
const { Worker } = require('node:worker_threads');
const api = require(process.argv[2]);
const config = (source = "globalThis.backgroundHandler = c => c.payload.text === '后台😀' ? 'success' : 'failure'", budget = 1000) =>
  JSON.stringify({ app_id: 'harmony-test', task_id: 'refresh', source, budget_ms: budget,
    memory_bytes: 8388608, payload: { text: '后台😀' } });
async function main() {
  assert.throws(() => api.backgroundOpen('{}'), /Invalid/);
  const first = api.backgroundOpen(config());
  const work = api.backgroundExecute(first);
  assert.throws(() => api.backgroundExecute(first), /already started/);
  const result = JSON.parse(await work);
  assert.equal(result.status, 'success');
  api.backgroundClose(first);
  assert.throws(() => api.backgroundExecute(first), /missing/);
  for (const bad of [0, -1, 1.5, NaN, Infinity, first + 2 ** 32, String(first)])
    assert.throws(() => api.backgroundCancel(bad), /missing/);

  const loop = "globalThis.backgroundHandler = () => { while (true) {} }";
  const second = api.backgroundOpen(config(loop, 30000));
  const pending = api.backgroundExecute(second);
  // Cancellation can run while QuickJS occupies the native worker, not the JS loop.
  await new Promise(resolve => setTimeout(resolve, 30));
  api.backgroundClose(second);
  const stopped = JSON.parse(await pending);
  assert.equal(stopped.code, 'cancelled');
  const third = api.backgroundOpen(config(loop, 30));
  const timed = JSON.parse(await api.backgroundExecute(third));
  assert.equal(timed.code, 'deadline_exceeded'); api.backgroundClose(third);

  const handles = Array.from({ length: 16 }, () => api.backgroundOpen(config()));
  assert.throws(() => api.backgroundOpen(config()), /capacity/);
  // Numeric IDs are scoped to the native environment, not just the process.
  const worker = new Worker(`
    const {parentPort, workerData} = require('node:worker_threads');
    const api = require(workerData.path);
    try { api.backgroundCancel(workerData.id); parentPort.postMessage(false); }
    catch (_) { parentPort.postMessage(true); }
  `, { eval: true, workerData: { path: process.argv[2], id: handles[0] } });
  assert.equal(await new Promise((resolve, reject) => { worker.once('message', resolve); worker.once('error', reject); }), true);
  await worker.terminate();
  handles.forEach(id => api.backgroundClose(id));
  const abandoned = new Worker(`
    const {parentPort, workerData} = require('node:worker_threads');
    const api = require(workerData.path);
    for (let i = 0; i < 16; i++) api.backgroundOpen(workerData.config);
    parentPort.postMessage('opened');
  `, { eval: true, workerData: { path: process.argv[2], config: config() } });
  await new Promise((resolve, reject) => { abandoned.once('exit', resolve); abandoned.once('error', reject); });
  const afterCleanup = Array.from({ length: 16 }, () => api.backgroundOpen(config()));
  afterCleanup.forEach(id => api.backgroundClose(id));
  console.log('Harmony background NAPI: real QuickJS Unicode, one-shot, cancellation, timeout, bounds and environment isolation passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
