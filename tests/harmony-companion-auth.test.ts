import { test, expect } from 'bun:test';
import { createHash, createHmac } from 'node:crypto';
import { CompanionSyncBinding, CompanionSyncHandshake, CompanionSyncSession, encodeSyncBinding } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';

const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
const binding = () => new CompanionSyncBinding('example.app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(2));
const handshake = (initiator: boolean) => new CompanionSyncHandshake(new Uint8Array(32).fill(7), binding(), initiator, crypto);

test('identity-bound non-exportable signer interoperates with raw-key peers and rejects revoked signing', async () => {
  let revoked = false;
  const signer = {
    matchesIdentity: (app: string, local: string, peer: string) => app === 'example.app' && local === 'phone' && peer === 'watch',
    async sign(bytes: Uint8Array) { if (revoked) throw Error('key revoked'); return crypto.hmacSha256(new Uint8Array(32).fill(7), bytes); }
  };
  const noExport = { sha256: crypto.sha256, async hmacSha256() { throw Error('raw-key path forbidden'); } };
  const left = new CompanionSyncSession(signer, binding(), true, ['message'], noExport);
  const right = new CompanionSyncSession(new Uint8Array(32).fill(7), binding(), false, ['message'], crypto);
  expect(await left.proof()).toEqual(await handshake(true).proof());
  await left.authenticate(await right.proof()); await right.authenticate(await left.proof());
  const frame = await left.send('message', 'hello', [1, 2]);
  expect((await right.verify(frame)).delivery).toBe('pending');
  expect(() => new CompanionSyncSession(signer, binding(), false, ['message'], noExport)).toThrow('identity mismatch');
  revoked = true; await expect(left.send('message', 'later', [])).rejects.toThrow('revoked');
  left.close(); right.close();
});

test('both roles agree on session, reflection fails and failed authentication is consumed', async () => {
  const a = handshake(true), b = handshake(false);
  expect(await a.authenticate(await b.proof())).toBe(await b.authenticate(await a.proof()));
  const reflected = handshake(true);
  await expect(reflected.authenticate(await reflected.proof())).rejects.toThrow('authentication failed');
  await expect(reflected.proof()).rejects.toThrow('closed');
});

test('copies key and binding; rejects invalid and reused challenges', async () => {
  const key = new Uint8Array(32).fill(7), source = binding();
  const session = new CompanionSyncHandshake(key, source, true, crypto);
  key.fill(0); source.initiator_nonce.fill(9);
  expect(await session.proof()).toEqual(await handshake(true).proof());
  source.responder_nonce = source.initiator_nonce.slice();
  expect(() => encodeSyncBinding(source)).toThrow('challenge');
});

test('wrong keys and changed remote challenges cannot authenticate', async () => {
  const proof = await handshake(false).proof();
  const wrongKey = new CompanionSyncHandshake(new Uint8Array(32).fill(8), binding(), true, crypto);
  await expect(wrongKey.authenticate(proof)).rejects.toThrow('authentication failed');
  const changed = binding(); changed.responder_nonce.fill(3);
  const wrongChallenge = new CompanionSyncHandshake(new Uint8Array(32).fill(7), changed, true, crypto);
  await expect(wrongChallenge.authenticate(proof)).rejects.toThrow('authentication failed');
  const a = handshake(true);
  const pending = a.authenticate(proof);
  await expect(a.authenticate(proof)).rejects.toThrow('already started');
  expect((await pending).length).toBe(64);
});

test('close invalidates in-flight crypto and wipes its temporary key', async () => {
  let finish!: (bytes: Uint8Array) => void;
  let held!: Uint8Array;
  const port = { sha256: crypto.sha256, hmacSha256(key: Uint8Array) {
    held = key; return new Promise<Uint8Array>(resolve => { finish = resolve; });
  } };
  const session = new CompanionSyncHandshake(new Uint8Array(32).fill(7), binding(), true, port);
  const pending = session.proof(); session.close(); finish(new Uint8Array(32));
  await expect(pending).rejects.toThrow('closed');
  expect(held.every(byte => byte === 0)).toBe(true);
});

test('Rust-generated canonical binding, role proofs and session ID match exactly', async () => {
  const result = Bun.spawnSync(['cargo', 'run', '--quiet', '-p', 'podjs-runtime', '--example', 'sync-auth-vectors']);
  expect(result.exitCode).toBe(0);
  const vector = JSON.parse(result.stdout.toString());
  expect(new TextDecoder().decode(encodeSyncBinding(binding()))).toBe(JSON.stringify(vector.binding, ['version', 'app_id', 'initiator', 'responder', 'initiator_nonce', 'responder_nonce']));
  const a = handshake(true), b = handshake(false);
  expect(Array.from(await a.proof())).toEqual(vector.initiator);
  expect(Array.from(await b.proof())).toEqual(vector.responder);
  expect(await a.authenticate(new Uint8Array(vector.responder))).toBe(vector.sessionId);
  const sender = new CompanionSyncSession(new Uint8Array(32).fill(7), binding(), true, ['state'], crypto);
  const receiver = new CompanionSyncSession(new Uint8Array(32).fill(7), binding(), false, ['state'], crypto);
  await sender.authenticate(new Uint8Array(vector.responder));
  await receiver.authenticate(new Uint8Array(vector.initiator));
  expect(JSON.parse(JSON.stringify(await sender.send('state', 'state-1', [0, 127, 128, 255])))).toEqual(vector.frame);
  expect((await receiver.verify(vector.frame)).acknowledged).toBe(0);
  await expect(receiver.commit(2)).rejects.toThrow('no verified');
  expect(await receiver.commit(1)).toBe(1);
  expect((await receiver.verify(vector.frame)).delivery).toBe('duplicate');
}, 120000);

async function peers(grants = ['state', 'ack']) {
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding(), true, ['state', 'ack'], crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding(), false, grants, crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof()); return { a, b };
}

test('serialized sends and copied payloads; pending replay does not ACK until commit', async () => {
  const { a, b } = await peers();
  const payload = [1]; const first = a.send('state', 'one', payload); payload[0] = 9;
  const second = a.send('state', 'two', []);
  const frame = await first; expect(frame.payload).toEqual([1]); expect((await second).sequence).toBe(2);
  expect((await b.verify(frame)).acknowledged).toBe(0);
  expect((await b.verify(frame)).delivery).toBe('pending');
  expect(await b.commit(1)).toBe(1);
});

test('tampering, reflection, gaps and unauthorized channels fail closed', async () => {
  for (const kind of ['tamper', 'reflection', 'gap', 'channel', 'fields']) {
    const { a, b } = await peers(kind === 'channel' ? ['ack'] : ['state', 'ack']);
    let frame = await a.send('state', 'one', [1]);
    if (kind === 'tamper') frame.payload[0] = 2;
    if (kind === 'gap') frame = await a.send('state', 'two', []);
    if (kind === 'fields') Object.assign(frame, { extra: true });
    const target = kind === 'reflection' ? a : b;
    await expect(target.verify(frame)).rejects.toThrow();
    await expect(target.proof()).rejects.toThrow('closed');
  }
});

test('valid MAC cannot replace a pending sequence; verify owns its queued input', async () => {
  const { a, b } = await peers();
  const frame = await a.send('state', 'one', [1]);
  const pending = b.verify(frame); frame.payload[0] = 9; frame.tag.fill(0);
  expect((await pending).delivery).toBe('pending');
  const other = (await peers()).a;
  const replacement = await other.send('state', 'one', [2]);
  await expect(b.verify(replacement)).rejects.toThrow('pending sequence payload changed');
  await expect(b.commit(1)).rejects.toThrow('closed');
});

test('invalid sends do not consume a sequence, sparse bytes and oversized payloads reject', async () => {
  const { a } = await peers();
  await expect(a.send('file', 'one', [])).rejects.toThrow('not authorized');
  await expect(a.send('state', 'one', new Array(2))).rejects.toThrow('bytes');
  await expect(a.send('state', 'one', new Array(263169).fill(0))).rejects.toThrow('too large');
  expect((await a.send('state', 'one', [])).sequence).toBe(1);
});
