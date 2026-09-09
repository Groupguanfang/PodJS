const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { Worker } = require('node:worker_threads');
const api = require(process.argv[2]);
const config = (loop = false) => JSON.stringify({ app_id: 'lease-test', task_id: 'work',
  source: loop ? 'globalThis.backgroundHandler = () => { while(true) {} }' : "globalThis.backgroundHandler = () => 'success'",
  budget_ms: loop ? 30000 : 1000, memory_bytes: 8388608 });
function probe(root, busy) {
  const child = spawnSync(process.execPath, [__filename, process.argv[2], busy ? 'busy' : 'free', root], { encoding: 'utf8', timeout: 5000 });
  assert.equal(child.status, 0, child.stderr || String(child.error));
}
async function main() {
  const mode = process.argv[3];
  if (mode === 'scheduler-busy') {
    await assert.rejects(api.backgroundOpenSchedulerLease(process.argv[4]), error => error.code === 'busy'); return;
  }
  if (mode === 'busy') {
    await assert.rejects(api.backgroundOpenLeased(config(), process.argv[4]), error => error.code === 'busy'); return;
  }
  if (mode === 'free') {
    const id = await api.backgroundOpenLeased(config(), process.argv[4]); api.backgroundClose(id); return;
  }
  if (mode === 'hold') {
    await api.backgroundOpenLeased(config(), process.argv[4]);
    process.stdout.write('ready\n'); setInterval(() => {}, 1000); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-execution-lease-'));
  try {
    const scheduler = await api.backgroundOpenSchedulerLease(root);
    await assert.rejects(api.backgroundOpenSchedulerLease(root), error => error.code === 'busy');
    assert.throws(() => api.backgroundConfigure(scheduler, config()), /cannot be configured/);
    assert.throws(() => api.backgroundExecute(scheduler), /missing or already started/);
    const other = spawnSync(process.execPath, [__filename, process.argv[2], 'scheduler-busy', root], { encoding: 'utf8', timeout: 5000 });
    assert.equal(other.status, 0, other.stderr);
    const independent = await api.backgroundOpenLeased(config(), root);
    assert.equal(JSON.parse(await api.backgroundExecute(independent)).status, 'success');
    api.backgroundClose(independent); api.backgroundClose(scheduler);
    const schedulerAgain = await api.backgroundOpenSchedulerLease(root); api.backgroundClose(schedulerAgain);
    assert.throws(() => api.backgroundOpenLeased(config(), 'relative'), /Invalid/);
    const idle = await api.backgroundOpenLeased(config(), root);
    await assert.rejects(api.backgroundOpenLeased(config(), root), error => error.code === 'busy');
    probe(root, true); api.backgroundClose(idle); probe(root, false);

    const configurable = await api.backgroundOpenLeased(config(true), root);
    api.backgroundConfigure(configurable, config(false)); probe(root, true);
    const configuredWork = api.backgroundExecute(configurable);
    assert.throws(() => api.backgroundConfigure(configurable, config(true)), /cannot be configured/);
    assert.equal(JSON.parse(await configuredWork).status, 'success'); api.backgroundClose(configurable);
    const cancelled = await api.backgroundOpenLeased(config(false), root); api.backgroundCancel(cancelled);
    assert.throws(() => api.backgroundConfigure(cancelled, config(false)), /cannot be configured/);
    assert.equal(JSON.parse(await api.backgroundExecute(cancelled)).code, 'cancelled'); api.backgroundClose(cancelled);
    const unleased = api.backgroundOpen(config(false));
    assert.throws(() => api.backgroundConfigure(unleased, config(false)), /cannot be configured/); api.backgroundClose(unleased);

    const executing = await api.backgroundOpenLeased(config(true), root);
    const work = api.backgroundExecute(executing);
    await new Promise(resolve => setTimeout(resolve, 20));
    probe(root, true);
    api.backgroundClose(executing);
    // The native Work retains Run even after registry removal. Blocking this
    // JS thread also prevents completion disposal, making this check deterministic.
    probe(root, true);
    assert.equal(JSON.parse(await work).code, 'cancelled'); probe(root, false);

    const worker = new Worker(`
      const {workerData} = require('node:worker_threads');
      require(workerData.path).backgroundOpenLeased(workerData.config, workerData.root);
    `, { eval: true, workerData: { path: process.argv[2], root, config: config() } });
    await new Promise((resolve, reject) => { worker.once('exit', resolve); worker.once('error', reject); });
    probe(root, false);

    const holder = spawn(process.execPath, [__filename, process.argv[2], 'hold', root], { stdio: ['ignore', 'pipe', 'pipe'] });
    const exited = new Promise(resolve => holder.once('exit', resolve));
    try {
      await new Promise((resolve, reject) => {
        holder.stdout.once('data', resolve); holder.once('error', reject);
        holder.once('exit', () => reject(Error('holder exited before ready')));
      });
      probe(root, true);
    } finally { holder.kill('SIGKILL'); await exited; }
    probe(root, false);
    console.log('Execution lease: same/cross-process exclusion, close-during-run retention, environment cleanup and process-death release passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
