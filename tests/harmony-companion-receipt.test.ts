import { expect, test } from 'bun:test';
import { createHash, randomUUID } from 'node:crypto';
import { CompanionState, type CompanionStatePort, type CompanionStateCrypto } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { encodeStateAck, decodeStateAck } from '../platforms/harmony/companion/src/main/ets/CompanionStateAck';
class Port implements CompanionStatePort {
  raw: string | null = null; fail: 'before' | 'after' | null = null;
  async read() { return this.raw; }
  async compareExchange(_app: string, old: string | null, next: string) {
    if (old !== this.raw) return false;
    const fail = this.fail; this.fail = null;
    if (fail === 'before') throw Error('before commit');
    this.raw = next;
    if (fail === 'after') throw Error('after commit');
    return true;
  }
}
const crypto: CompanionStateCrypto = {
  async sha256(text) { return createHash('sha256').update(text).digest('hex'); },
  async messageId() { return randomUUID(); },
};
function sdk(port: Port, device = 'watch') { return new CompanionState('app', device, port, crypto); }
const payload = JSON.stringify({ version: 1, from: 0, to: 1, entries: [{ key: 'x', value: '中文', counter: 1, deviceId: 'phone', deleted: false }] });
test('durable wire receipts recognize exact replay and reject changed ID/bytes, stale ranges and gaps', async () => {
  const port = new Port(), receiver = sdk(port);
  expect((await receiver.receiveBatchAuthenticated('phone', 'batch1', payload)).duplicate).toBe(false);
  const reopened = sdk(port), replay = await reopened.receiveBatchAuthenticated('phone', 'batch1', payload);
  expect(replay.duplicate).toBe(true); expect(replay.cursor).toBe(1); expect(replay.digest).toBe(await crypto.sha256(payload));
  await expect(reopened.receiveBatchAuthenticated('phone', 'changed', payload)).rejects.toThrow('replay');
  await expect(reopened.receiveBatchAuthenticated('phone', 'batch1', payload + ' ')).rejects.toThrow('replay');
  const next = JSON.stringify({ version: 1, from: 1, to: 2, entries: [] });
  await expect(reopened.receiveBatchAuthenticated('phone', 'batch1', next)).rejects.toThrow('reused');
  await reopened.receiveBatchAuthenticated('phone', 'batch2', next);
  await expect(reopened.receiveBatchAuthenticated('phone', 'batch1', payload)).rejects.toThrow('stale');
  await expect(reopened.receiveBatchAuthenticated('phone', 'batch3', JSON.stringify({ version: 1, from: 3, to: 4, entries: [] }))).rejects.toThrow('gap');
  await expect(reopened.receiveAuthenticated('phone', 2, 3, [])).rejects.toThrow('mix');
  expect((await reopened.snapshot()).cursors.phone).toBe(2);
});
test('receipt and state commit together across failed and uncertain writes', async () => {
  const port = new Port(), receiver = sdk(port);
  port.fail = 'before'; await expect(receiver.receiveBatchAuthenticated('phone', 'batch', payload)).rejects.toThrow();
  expect(port.raw).toBeNull();
  port.fail = 'after'; await expect(receiver.receiveBatchAuthenticated('phone', 'batch', payload)).rejects.toThrow();
  const stored = JSON.parse(port.raw!);
  expect(stored.state.cursors.phone).toBe(1); expect(stored.incoming[0].messageId).toBe('batch');
  const reopened = sdk(port); expect((await reopened.receiveBatchAuthenticated('phone', 'batch', payload)).duplicate).toBe(true);
  expect(await reopened.get('x')).toBe('中文');
  stored.incoming[0].to = 2; port.raw = JSON.stringify(stored);
  await expect(reopened.snapshot()).rejects.toThrow('receipt');
});
test('sender-to-receiver lost ACK loop uses kind-3 bytes and survives both SDKs reopening', async () => {
  const sendPort = new Port(), receivePort = new Port(), sender = sdk(sendPort, 'phone');
  await sender.set('x', 'roundtrip'); const batch = (await sender.prepare('watch'))!;
  await sdk(receivePort).receiveBatchAuthenticated('phone', batch.messageId, batch.payload); // ACK lost.
  const resent = (await sdk(sendPort, 'phone').prepare('watch'))!;
  const receipt = await sdk(receivePort).receiveBatchAuthenticated('phone', resent.messageId, resent.payload);
  expect(receipt.duplicate).toBe(true);
  const ack = decodeStateAck(encodeStateAck(receipt.cursor, receipt.digest));
  expect(await sdk(sendPort, 'phone').acknowledgeAuthenticated('watch', resent.messageId, ack.cursor, ack.digest)).toBe(true);
  expect(await sdk(sendPort, 'phone').prepare('watch')).toBeNull();
  expect(await sdk(receivePort).get('x')).toBe('roundtrip');
});
test('ACK codec matches Android big-endian wire format and safe counters including subviews', () => {
  const hash = 'ab'.repeat(32);
  const encoded = encodeStateAck(0x01020304050607, hash);
  expect([...encoded.slice(0, 9)]).toEqual([3, 0, 1, 2, 3, 4, 5, 6, 7]);
  for (const cursor of [0, 1, 0xffffffff, 0x100000000, Number.MAX_SAFE_INTEGER]) {
    const bytes = encodeStateAck(cursor, hash), padded = new Uint8Array(50); padded.set(bytes, 5);
    expect(decodeStateAck(padded.subarray(5, 46))).toMatchObject({ cursor, digest: hash });
  }
  for (const kind of [0, 1, 2, 4]) { const bytes = encoded.slice(); bytes[0] = kind; expect(() => decodeStateAck(bytes)).toThrow(); }
  expect(() => decodeStateAck(encoded.slice(0, 40))).toThrow();
  const unsafe = encoded.slice(); unsafe[1] = 0xff; expect(() => decodeStateAck(unsafe)).toThrow('Unsafe');
  expect(() => encodeStateAck(Number.MAX_SAFE_INTEGER + 1, hash)).toThrow();
  expect(() => encodeStateAck(-1, hash)).toThrow();
});
test('schema-two senders migrate receipts without losing their pending batch', async () => {
  const port = new Port(), sender = sdk(port, 'phone'), batch = (await sender.prepare('watch'))!;
  const old = JSON.parse(port.raw!); old.schema = 2; delete old.incoming; port.raw = JSON.stringify(old);
  expect(await sdk(port, 'phone').prepare('watch')).toEqual(batch);
  expect(JSON.parse(port.raw!).schema).toBe(3);
});
