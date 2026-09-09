const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const api = require(process.argv[2]);
async function main() {
  if (process.argv[3] === 'exit-owned') {
    const lease = await api.companionPairingLeaseOpen(process.argv[4], 'app');
    api.companionPairingLeaseAssert(lease); process.exit(0);
  }
  if (process.argv[3] === 'blocked') {
    await assert.rejects(api.companionPairingLeaseOpen(process.argv[4], 'app'), /already owned/); return;
  }
  if (process.argv[3] === 'reopen') {
    const lease = await api.companionPairingLeaseOpen(process.argv[4], 'app');
    assert.equal(await api.companionPairingLeaseRead(lease), '{"phase":"importing"}');
    api.companionPairingLeaseClose(lease); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-pairing-lease-'));
  try {
    const lease = await api.companionPairingLeaseOpen(root, 'app');
    api.companionPairingLeaseAssert(lease);
    assert.equal(await api.companionPairingLeaseRead(lease), null);
    assert.equal(await api.companionPairingLeaseCompareExchange(lease, null, '{"phase":"importing"}'), true);
    assert.equal(await api.companionPairingLeaseCompareExchange(lease, null, '{}'), false);
    // No file operation is in flight: ownership still spans a hypothetical HUKS await.
    const child = spawnSync(process.execPath, [__filename, process.argv[2], 'blocked', root], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr);
    await assert.rejects(api.companionPairingLeaseOpen(root, 'app'), /already owned/);
    await assert.rejects(api.companionPairingsCompareExchange(root, 'app', '{"phase":"importing"}', '{}'), /already owned/);
    const other = await api.companionPairingLeaseOpen(root, 'other'); api.companionPairingLeaseClose(other);
    const pending = api.companionPairingLeaseRead(lease);
    assert.throws(() => api.companionPairingLeaseRead(lease), /busy/); await pending;
    api.companionPairingLeaseClose(lease); api.companionPairingLeaseClose(lease);
    assert.throws(() => api.companionPairingLeaseAssert(lease), /closed/);
    assert.throws(() => api.companionPairingLeaseRead(lease), /closed/);
    const reopened = spawnSync(process.execPath, [__filename, process.argv[2], 'reopen', root], { encoding: 'utf8' });
    assert.equal(reopened.status, 0, reopened.stderr);
    const exited = spawnSync(process.execPath, [__filename, process.argv[2], 'exit-owned', root], { encoding: 'utf8' });
    assert.equal(exited.status, 0, exited.stderr);
    const late = await api.companionPairingLeaseOpen(root, 'app');
    const reading = api.companionPairingLeaseRead(late); api.companionPairingLeaseClose(late);
    await assert.rejects(reading, /closed/);
    const final = await api.companionPairingLeaseOpen(root, 'app'); api.companionPairingLeaseClose(final);
    fs.chmodSync(path.join(root, 'podjs-companion-pairings-app'), 0o755);
    await assert.rejects(api.companionPairingLeaseOpen(root, 'app'), /Unsafe/);
    console.log('Pairing native lifetime lease: cross-process and CAS exclusion, reopen, busy, late close and permissions PASS');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
