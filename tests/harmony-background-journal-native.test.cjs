const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const api = require(process.argv[2]);
const { BackgroundJournal, BackgroundTask } = require(process.argv[3]);
function journal(root) {
  return new BackgroundJournal('dev.podjs.watch', {
    read: () => api.backgroundStoreRead(root),
    compareExchange: (old, desired) => api.backgroundStoreCompareExchange(root, old, desired)
  }, () => new Promise(resolve => setTimeout(resolve, 2)));
}
async function register(root, prefix) {
  const store = journal(root);
  for (let i = 0; i < 8; i++) {
    const task = new BackgroundTask(); task.id = `task-${prefix}-${i}`; task.handler = 'refresh';
    task.payload = { text: '跨进程😀', index: i };
    const approved = { appId: 'dev.podjs.watch', handlerId: 'refresh', sha256: 'a'.repeat(64), grants: [] };
    await store.register(task, approved, (prefix * 100 + i).toString(16).padStart(32, '0'));
  }
}
async function main() {
  if (process.argv[4] === 'child') { await register(process.argv[5], Number(process.argv[6])); return; }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-background-journal-native-'));
  try {
    const children = [1, 2, 3, 4].map(prefix => new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [__filename, process.argv[2], process.argv[3], 'child', root, String(prefix)]);
      let errors = ''; child.stderr.on('data', data => { errors += data; });
      child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(Error(errors)));
    }));
    await Promise.all([...children, register(root, 5)]);
    const reopened = journal(root); const records = await reopened.records();
    assert.equal(records.length, 40); assert.equal(new Set(records.map(r => r.identity.workId)).size, 40);
    assert.equal(new Set(records.map(r => r.identity.runId)).size, 40);
    assert.equal(records.every(r => r.task.payload.text === '跨进程😀'), true);
    const state = JSON.parse(await api.backgroundStoreRead(root));
    assert.equal(state.nextWorkId, 0x60000000 + 40); assert.equal(state.revision, 40);
    const claims = await Promise.all(records.slice(0, 2).map((r, i) =>
      journal(root).claim(r.identity, String(i + 1).repeat(32), 0)));
    assert.equal(claims.filter(Boolean).length, 1);
    assert.equal((await journal(root).records()).filter(r => r.executionActive).length, 1);
    console.log('Background journal + native CAS: 5 processes, 40 durable unique task IDs and exclusive claim passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
