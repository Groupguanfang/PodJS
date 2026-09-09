const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const api = require(process.argv[2]);
async function retry(operation) {
  for (let i = 0; i < 1000; i++) {
    try { return await operation(); } catch (error) {
      if (error.code !== 'busy') throw error;
      await new Promise(resolve => setTimeout(resolve, 2));
    }
  }
  throw Error('lock did not become available');
}
async function increment(root, count) {
  for (let i = 0; i < count; i++) {
    let saved = false;
    for (let tries = 0; tries < 1000 && !saved; tries++) {
      const old = await retry(() => api.backgroundStoreRead(root));
      const value = JSON.parse(old); value.counter++;
      saved = await retry(() => api.backgroundStoreCompareExchange(root, old, JSON.stringify(value)));
    }
    assert.equal(saved, true);
  }
}
async function main() {
  if (process.argv[3] === 'child') { await increment(process.argv[4], 20); return; }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-background-store-test-'));
  try {
    assert.equal(await api.backgroundStoreRead(root), null);
    const initial = JSON.stringify({ counter: 0, label: '后台😀' });
    assert.equal(await api.backgroundStoreCompareExchange(root, null, initial), true);
    assert.equal(await api.backgroundStoreCompareExchange(root, null, '{}'), false);
    assert.equal(await api.backgroundStoreRead(root), initial);
    assert.throws(() => api.backgroundStoreCompareExchange(root, initial, 'bad\0bytes'), /Invalid/);
    assert.throws(() => api.backgroundStoreCompareExchange(root, undefined, '{}'), /Invalid/);
    const children = Array.from({ length: 4 }, () => new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [__filename, process.argv[2], 'child', root], { stdio: ['ignore', 'pipe', 'pipe'] });
      let errors = ''; child.stderr.on('data', chunk => { errors += chunk; });
      child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(Error(errors)));
    }));
    await Promise.all([...children, increment(root, 20)]);
    const result = JSON.parse(await api.backgroundStoreRead(root));
    assert.equal(result.counter, 100); assert.equal(result.label, '后台😀');
    assert.equal(fs.existsSync(path.join(root, 'podjs-notifications')), false);
    const journal = path.join(root, 'podjs-background', 'journal.json');
    const saved = fs.readFileSync(journal);
    fs.chmodSync(journal, 0o644);
    await assert.rejects(api.backgroundStoreRead(root), /Unsafe/);
    assert.deepEqual(fs.readFileSync(journal), saved); fs.chmodSync(journal, 0o600);
    assert.equal(await api.backgroundStoreCompareExchange(root, initial, '{}'), false);
    console.log('Background store NAPI: 5-process CAS, 100 retained updates, UTF-8, stale-write rejection and unsafe-file refusal passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
