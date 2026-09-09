const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const addon = require(process.argv[2]);
(async () => {
  if (process.argv[3] === 'compete') {
    await assert.rejects(addon.open(process.argv[4]), /already owned/); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-journal-napi-'));
  try {
    await assert.rejects(addon.read(), /not open/);
    assert.throws(() => addon.write(null), /bounded/);
    await addon.open(root); assert.equal(await addon.read(), null);
    await addon.write('{"label":"中文😀"}');
    assert.equal(await addon.read(), '{"label":"中文😀"}');
    await assert.rejects(addon.open(root), /already opened/);
    const child = spawnSync(process.execPath, [__filename, process.argv[2], 'compete', root], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr);
    await Promise.all(Array.from({ length: 16 }, (_, id) => addon.write(JSON.stringify({ id, data: 'x'.repeat(65536) }))));
    const result = JSON.parse(await addon.read());
    assert.equal(result.data.length, 65536); assert.ok(result.id >= 0 && result.id < 16);
    await assert.rejects(addon.write('bad\0bytes'), /Invalid journal bytes/);
    assert.equal(JSON.parse(await addon.read()).data.length, 65536);
    console.log('Journal NAPI: async IO, UTF-8, atomic writes and cross-process ownership passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
})().catch(error => { console.error(error); process.exitCode = 1; });
