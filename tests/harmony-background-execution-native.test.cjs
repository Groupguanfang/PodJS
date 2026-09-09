const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createHash, randomBytes } = require('node:crypto');
const api = require(process.argv[2]);
const { BackgroundJournal, BackgroundTask, BackgroundExecution, BackgroundPackage } = require(process.argv[3]);
const appId = 'dev.podjs.executiontest';
const source = "globalThis.backgroundHandler = async c => { await c.request('kv.set', {key:'verified',value:c.payload}); return 'success'; }";
const sourceBytes = Buffer.from(source); const hash = createHash('sha256').update(sourceBytes).digest('hex');
const filename = 'background/' + 'a'.repeat(64) + '.js';
const manifest = { schema: 1, target: 'harmonyos-watch', backgroundServices: ['kv.set'],
  background: { refresh: { file: filename, bytes: sourceBytes.length, sha256: hash } } };
const assets = { async read(file) { if (file === 'pod.manifest.json') return Buffer.from(JSON.stringify(manifest));
  assert.equal(file, filename); return sourceBytes; },
  decode(bytes) { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); },
  async sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); } };
const placeholder = () => JSON.stringify({ app_id: appId, task_id: 'lease', source: "globalThis.backgroundHandler = () => 'failure'",
  budget_ms: 1, memory_bytes: 8388608 });
async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-execution-native-'));
  let executions = 0; let protectedCommits = 0;
  try {
    const journal = new BackgroundJournal(appId, {
      read: () => api.backgroundStoreRead(root),
      async compareExchange(old, desired) {
        const prior = old && JSON.parse(old); const next = JSON.parse(desired);
        if (prior?.records.some(r => r.executionActive && next.records.some(n => n.identity.runId === r.identity.runId && n.result))) {
          await assert.rejects(api.backgroundOpenLeased(placeholder(), root), error => error.code === 'busy'); protectedCommits++;
        }
        return api.backgroundStoreCompareExchange(root, old, desired);
      }
    }, () => new Promise(resolve => setTimeout(resolve, 2)));
    const host = {
      async openLease() {
        const handle = await api.backgroundOpenLeased(placeholder(), root); let prepared = false;
        return {
          async prepare(record) {
            const installed = await BackgroundPackage.installed(appId, assets);
            if (!installed.permits(record.sourceHash, record.grants)) throw { code: 'background_permission_denied' };
            const approved = await installed.resolve(record.task.handler);
            assert.equal(approved.sha256, record.sourceHash);
            api.backgroundConfigure(handle, JSON.stringify({ app_id: appId, task_id: record.task.id, source: approved.source,
              budget_ms: record.budgetMs, memory_bytes: 8388608, payload: record.task.payload,
              allowed_methods: record.grants, kv_root: path.join(root, 'podjs') })); prepared = true;
          },
          async execute() { assert.equal(prepared, true); executions++; return JSON.parse(await api.backgroundExecute(handle)); },
          cancel() { api.backgroundCancel(handle); }, close() { api.backgroundClose(handle); }
        };
      }, async token() { return randomBytes(16).toString('hex'); }, now() { return Date.now(); }
    };
    const task = new BackgroundTask(); task.id = 'refresh'; task.handler = 'refresh'; task.payload = { text: '真实执行😀' };
    const approved = await (await BackgroundPackage.installed(appId, assets)).resolve(task.handler);
    const first = await journal.register(task, approved, '1'.repeat(32));
    const driver = new BackgroundExecution(journal, host);
    assert.equal((await driver.start(first.identity)).status, 'success');
    const kvPath = path.join(root, 'podjs', 'podjs-kv.json');
    assert.deepEqual(JSON.parse(fs.readFileSync(kvPath, 'utf8')).verified, task.payload);
    assert.equal((await journal.records())[0].phase, 'completed');
    const second = await journal.register(task, approved, '2'.repeat(32));
    manifest.backgroundServices = [];
    assert.equal((await driver.start(second.identity)).code, 'background_permission_denied');
    assert.equal(executions, 1); assert.equal(protectedCommits, 2);
    assert.equal((await journal.records())[1].phase, 'failed');
    const free = await api.backgroundOpenLeased(placeholder(), root); api.backgroundClose(free);
    const abandoned = await journal.register(task, approved, '3'.repeat(32));
    await journal.claim(abandoned.identity, 'a'.repeat(32), Date.now());
    const liveOwner = await api.backgroundOpenLeased(placeholder(), root);
    await driver.recoverAbandoned();
    assert.equal((await journal.records())[2].executionActive, true);
    api.backgroundClose(liveOwner);
    await driver.recoverAbandoned();
    assert.equal((await journal.records())[2].result.code, 'execution_abandoned');
    assert.equal((await journal.records())[2].executionActive, false);
    assert.equal(executions, 1); assert.equal(protectedCommits, 3);
    console.log('Execution driver: verified source -> leased claim -> real QuickJS KV write -> durable result, commit lock and grant revocation passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
