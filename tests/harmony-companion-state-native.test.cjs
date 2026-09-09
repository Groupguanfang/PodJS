const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash, randomUUID } = require('node:crypto');
const api = require(process.argv[2]);
const { CompanionState, CasStateDatabase, HarmonyCompanionState, encodeStateAck, decodeStateAck } = require(process.argv[3]);
const wirePayload = JSON.stringify({ version: 1, from: 0, to: 1, entries: [{ key: 'wire', value: 'receipt', counter: 20, deviceId: 'wire-peer', deleted: false }] });
function harmony(root) {
  return new HarmonyCompanionState('harmony-app', 'phone', {
    read: app => api.companionStateRead(root, app),
    compareExchange: (app, old, next) => api.companionStateCompareExchange(root, app, old, next),
  }, {
    sha256: async text => createHash('sha256').update(text, 'utf8').digest('hex'),
    messageId: async () => randomUUID(),
  });
}
function database(root) {
  return new CasStateDatabase({
    read: app => api.companionStateRead(root, app),
    compareExchange: (app, old, next) => api.companionStateCompareExchange(root, app, old, next),
  });
}
async function main() {
  if (process.argv[4] === 'harmony-replay') {
    const receipt = await harmony(process.argv[5]).receiveBatchAuthenticated('wire-peer', 'wire-batch', wirePayload);
    assert.equal(receipt.duplicate, true);
    assert.equal(decodeStateAck(encodeStateAck(receipt.cursor, receipt.digest)).cursor, 1);
    return;
  }
  if (process.argv[4] === 'companion-only') {
    assert.equal(api.backgroundStoreRead, undefined);
    assert.equal(api.backgroundStoreCompareExchange, undefined);
    assert.equal(typeof api.companionStateRead, 'function');
    assert.equal(typeof api.companionStateCompareExchange, 'function');
  }
  if (process.argv[4] === 'harmony-child') {
    const sdk = harmony(process.argv[5]);
    assert.deepEqual(await sdk.get('state'), { text: 'Harmony 中文😀' });
    await sdk.delete('state'); return;
  }
  if (process.argv[4] === 'child') {
    const sdk = new CompanionState('app', 'phone', database(process.argv[5]));
    assert.deepEqual(await sdk.get('state'), { text: '中文😀' });
    await sdk.delete('state'); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-companion-native-'));
  try {
    const db = database(root), sdk = new CompanionState('app', 'phone', db);
    await sdk.set('state', { text: '中文😀' });
    const child = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'child', root], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr);
    assert.equal(await sdk.get('state'), undefined);
    assert.equal((await sdk.snapshot()).entries[0].deleted, true);
    assert.equal(await new CompanionState('other', 'phone', db).get('state'), undefined);
    assert.equal(fs.existsSync(path.join(root, 'podjs-background')), false);
    assert.equal(fs.existsSync(path.join(root, 'podjs-notifications')), false);
    const snapshot = await api.companionStateRead(root, 'app');
    assert.equal(await api.companionStateCompareExchange(root, 'app', null, '{}'), false);
    assert.equal(await api.companionStateRead(root, 'app'), snapshot);
    for (const app of ['', '../escape', 'a/b', 'a\0b', 'x'.repeat(129)]) {
      assert.throws(() => api.companionStateRead(root, app), /Invalid/);
    }
    let invoked = 0;
    const conflicting = new CasStateDatabase({
      read: app => api.companionStateRead(root, app),
      compareExchange: async (app, old, next) => {
        const winner = JSON.parse(old); winner.clock++;
        assert.equal(await api.companionStateCompareExchange(root, app, old, JSON.stringify(winner)), true);
        return api.companionStateCompareExchange(root, app, old, next);
      },
    });
    await assert.rejects(conflicting.transaction('app', saved => { invoked++; return saved; }), /conflict/);
    assert.equal(invoked, 1);
    const journal = path.join(root, 'podjs-companion-state-app', 'journal.json');
    const bytes = fs.readFileSync(journal);
    fs.chmodSync(journal, 0o644);
    await assert.rejects(sdk.set('new', true), /Unsafe/);
    assert.deepEqual(fs.readFileSync(journal), bytes); fs.chmodSync(journal, 0o600);
    const remote = [{ key: 'remote', value: 1, counter: 9, deviceId: 'watch', deleted: false }];
    assert.equal(await sdk.receiveAuthenticated('watch', 0, 1, remote), 1);
    const reopened = new CompanionState('app', 'phone', database(root));
    assert.equal((await reopened.snapshot()).cursors.watch, 1);
    assert.equal(await reopened.get('remote'), 1);
    const harmonySdk = harmony(root);
    await harmonySdk.set('state', { text: 'Harmony 中文😀' });
    const pending = await harmonySdk.prepare('watch');
    const harmonyChild = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'harmony-child', root], { encoding: 'utf8' });
    assert.equal(harmonyChild.status, 0, harmonyChild.stderr);
    assert.equal(await harmonySdk.get('state'), undefined);
    assert.equal((await harmonySdk.snapshot()).entries[0].deleted, true);
    assert.deepEqual(await harmony(root).prepare('watch'), JSON.parse(JSON.stringify(pending)));
    assert.equal(await harmonySdk.acknowledgeAuthenticated('watch', pending.messageId, pending.to, pending.digest), true);
    const deletion = await harmony(root).prepare('watch');
    assert.equal(JSON.parse(deletion.payload).entries[0].deleted, true);
    assert.equal(await harmonySdk.receiveAuthenticated('watch', 0, 1, remote), 1);
    assert.equal((await harmony(root).snapshot()).cursors.watch, 1);
    const receipt = await harmonySdk.receiveBatchAuthenticated('wire-peer', 'wire-batch', wirePayload);
    assert.equal(receipt.duplicate, false);
    const replayChild = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'harmony-replay', root], { encoding: 'utf8' });
    assert.equal(replayChild.status, 0, replayChild.stderr);
    await assert.rejects(harmony(root).receiveBatchAuthenticated('wire-peer', 'changed-id', wirePayload), /replay/);
    assert.equal(await harmony(root).get('wire'), 'receipt');
    console.log('Companion native state: SDK-to-file persistence, process reopen, tombstone, app isolation, CAS conflict, unsafe-file refusal and durable receive cursor passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
