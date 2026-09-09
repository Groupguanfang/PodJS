const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const api = require(process.argv[2]);
const { CompanionMessageOutbox, CompanionMessageEnvelope, CompanionMessageInbox, encodeMessageEnvelope } = require(process.argv[3]);
function inbox(root) {
  return new CompanionMessageInbox('app', 'phone', {
    read: () => api.companionInboxRead(root, 'app'),
    compareExchange: (old, next) => api.companionInboxCompareExchange(root, 'app', old, next),
  }, { sha256: async bytes => new Uint8Array(createHash('sha256').update(bytes).digest()) });
}
function incomingWire() { return encodeMessageEnvelope(new CompanionMessageEnvelope(100, true, new Uint8Array(262144).fill(255))); }
function queue(root) {
  return new CompanionMessageOutbox('app', 'phone', {
    read: () => api.companionOutboxRead(root, 'app'),
    compareExchange: (old, next) => api.companionOutboxCompareExchange(root, 'app', old, next),
  }, { sha256: async bytes => new Uint8Array(createHash('sha256').update(bytes).digest()) });
}
async function main() {
  if (process.argv[4] === 'pairing-child') {
    const raw = await api.companionPairingsRead(process.argv[5], 'app');
    assert.equal(raw, '{"keyId":"public-id","state":"revoking"}');
    assert.equal(await api.companionPairingsCompareExchange(process.argv[5], 'app', raw, '{"state":"revoked"}'), true); return;
  }
  if (process.argv[4] === 'inbox-child') {
    const sdk = inbox(process.argv[5]), rows = await sdk.pending(2, 1);
    assert.equal(rows.length, 1); assert.equal(rows[0].messageId, 'received');
    assert.deepEqual(rows[0].envelope.payload, new Uint8Array(262144).fill(255));
    await sdk.acknowledge('watch', 'received', rows[0].digest, 2); return;
  }
  if (process.argv[4] === 'inbox-replay') {
    assert.equal((await inbox(process.argv[5]).receiveAuthenticated('watch', 'received', incomingWire(), 3)).status, 'applied'); return;
  }
  if (process.argv[4] === 'child') {
    const sdk = queue(process.argv[5]), rows = await sdk.pending('watch', 2, 1);
    assert.equal(rows.length, 1); assert.equal(rows[0].messageId, 'id');
    assert.deepEqual(rows[0].envelope.payload, new Uint8Array(262144).fill(255));
    assert.equal(await sdk.acknowledgeAuthenticated('watch', 'id', rows[0].digest), true); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-outbox-native-'));
  try {
    assert.equal(await api.companionStateCompareExchange(root, 'app', null, '{"unrelated":true}'), true);
    const sdk = queue(root);
    await sdk.enqueue('watch', 'id', new CompanionMessageEnvelope(100, true, new Uint8Array(262144).fill(255)), 1);
    const saved = await api.companionOutboxRead(root, 'app');
    assert.equal(await api.companionOutboxRead(root, 'other'), null);
    assert.equal(await api.companionOutboxCompareExchange(root, 'app', null, '{}'), false);
    assert.equal(await api.companionOutboxRead(root, 'app'), saved);
    const journal = path.join(root, 'podjs-companion-outbox-app', 'journal.json');
    fs.chmodSync(journal, 0o644);
    await assert.rejects(sdk.pending('watch', 2, 1), /Unsafe/); fs.chmodSync(journal, 0o600);
    const child = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'child', root], { encoding: 'utf8' });
    assert.equal(child.status, 0, child.stderr); assert.deepEqual(await queue(root).pending('watch', 2, 1), []);
    assert.equal(await api.companionStateRead(root, 'app'), '{"unrelated":true}');
    const outboxSaved = await api.companionOutboxRead(root, 'app');
    assert.equal((await inbox(root).receiveAuthenticated('watch', 'received', incomingWire(), 1)).status, 'pending');
    const inboxSaved = await api.companionInboxRead(root, 'app');
    assert.equal(await api.companionPairingsRead(root, 'app'), null);
    assert.equal(await api.companionPairingsCompareExchange(root, 'app', null, '{"keyId":"public-id","state":"revoking"}'), true);
    assert.equal(await api.companionPairingsCompareExchange(root, 'app', null, '{}'), false);
    assert.equal(await api.companionPairingsRead(root, 'other'), null);
    const pairedChild = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'pairing-child', root], { encoding: 'utf8' });
    assert.equal(pairedChild.status, 0, pairedChild.stderr);
    assert.equal(await api.companionPairingsRead(root, 'app'), '{"state":"revoked"}');
    const pairingJournal = path.join(root, 'podjs-companion-pairings-app', 'journal.json');
    fs.chmodSync(pairingJournal, 0o644);
    await assert.rejects(api.companionPairingsRead(root, 'app'), /Unsafe/); fs.chmodSync(pairingJournal, 0o600);
    assert.equal(await api.companionInboxRead(root, 'app'), inboxSaved);
    assert.equal(await api.companionInboxCompareExchange(root, 'app', null, '{}'), false);
    assert.equal(await api.companionInboxRead(root, 'app'), inboxSaved);
    assert.equal(await api.companionInboxRead(root, 'other'), null);
    for (const mode of ['inbox-child', 'inbox-replay']) {
      const processResult = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], mode, root], { encoding: 'utf8' });
      assert.equal(processResult.status, 0, processResult.stderr);
    }
    assert.deepEqual(await inbox(root).pending(3, 1), []);
    assert.equal(await api.companionOutboxRead(root, 'app'), outboxSaved);
    assert.equal(await api.companionStateRead(root, 'app'), '{"unrelated":true}');
    const inboxJournal = path.join(root, 'podjs-companion-inbox-app', 'journal.json');
    fs.chmodSync(inboxJournal, 0o644);
    await assert.rejects(inbox(root).pending(3, 1), /Unsafe/); fs.chmodSync(inboxJournal, 0o600);
    for (const app of ['', '../escape', 'a/b', 'x'.repeat(129)]) assert.throws(() => api.companionOutboxRead(root, app), /Invalid/);
    console.log('Companion inbox/outbox native: full payload process reopen, durable ACK and applied replay, namespace isolation, CAS and unsafe file checks passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
