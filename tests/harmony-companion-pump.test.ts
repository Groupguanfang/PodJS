import { test, expect } from 'bun:test';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { CompanionState } from '../platforms/harmony/companion/src/main/ets/CompanionState';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
import { CompanionStatePump, encodeStateUtf8, decodeStateUtf8 } from '../platforms/harmony/companion/src/main/ets/CompanionStatePump';
class Port {
  raw: string | null = null; fail = false;
  async read() { return this.raw; }
  async compareExchange(_app: string, old: string | null, next: string) {
    if (this.fail) throw Error('disk failure');
    if (old !== this.raw) return false; this.raw = next; return true;
  }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
const stateCrypto = { async sha256(text: string) { return createHash('sha256').update(text).digest('hex'); }, async messageId() { return randomUUID(); } };
async function peers(left = new Port(), right = new Port(), nonce = 2) {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(nonce));
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, ['state', 'ack'], crypto);
  const b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, ['state', 'ack'], crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  const sa = new CompanionState('app', 'phone', left, stateCrypto), sb = new CompanionState('app', 'watch', right, stateCrypto);
  return { a, b, sa, sb, pa: new CompanionStatePump(a, sa), pb: new CompanionStatePump(b, sb), left, right };
}
test('authenticated state and ACK route survives lost reply and fresh-session reopen', async () => {
  const first = await peers(); await first.sa.set('key', '中文😀');
  const frame = (await first.pa.sendNext())!;
  expect((await first.pb.receive(frame)).status).toBe('applied');
  first.pa.close(); first.pb.close();
  const next = await peers(first.left, first.right, 3);
  const replay = (await next.pa.sendNext())!;
  expect(replay.messageId).toBe(frame.messageId); expect(replay.payload).toEqual(frame.payload);
  expect(replay.sessionId).not.toBe(frame.sessionId);
  const result = await next.pb.receive(replay); expect(result.status).toBe('duplicate');
  expect((await next.pa.receive(result.reply!)).status).toBe('ack');
  expect(await next.pa.sendNext()).toBeNull(); expect(await next.sb.get('key')).toBe('中文😀');
});
test('storage failure closes without ACK or transient commit, durable batch remains', async () => {
  const p = await peers(); await p.sa.set('key', 1); p.right.fail = true;
  await expect(p.pb.receive((await p.pa.sendNext())!)).rejects.toThrow('disk failure');
  await expect(p.b.commit(1)).rejects.toThrow('closed'); expect(p.right.raw).toBeNull();
  expect(await p.sa.prepare('watch')).not.toBeNull();
});
test('sender ACK write failure preserves batch and closes before sequence commit', async () => {
  const p = await peers(); await p.sa.set('key', 1);
  const frame = (await p.pa.sendNext())!;
  const result = await p.pb.receive(frame);
  p.left.fail = true;
  await expect(p.pa.receive(result.reply!)).rejects.toThrow('disk failure');
  await expect(p.a.commit(1)).rejects.toThrow('closed');
  p.left.fail = false;
  expect((await p.sa.prepare('watch'))!.messageId).toBe(frame.messageId);
});
test('strict UTF-8 rejects malformed, surrogate and overlong encodings', () => {
  for (const text of ['', '\0', '中文😀', '\ufeffx']) expect(decodeStateUtf8(encodeStateUtf8(text))).toBe(text);
  for (const bytes of [[0xc0, 0x80], [0xed, 0xa0, 0x80], [0xf4, 0x90, 0x80, 0x80], [0xe2], [0x80], [0xe0, 0x80, 0x80]])
    expect(() => decodeStateUtf8(bytes)).toThrow('UTF-8');
  expect(() => encodeStateUtf8('\ud800')).toThrow('UTF-16');
});
test('state binding mismatch and signed malformed UTF-8 reject before persistence', async () => {
  const p = await peers();
  expect(() => new CompanionStatePump(p.a, p.sb)).toThrow('identity mismatch');
  await expect(p.pb.receive(await p.a.send('state', 'bad', [0xc0, 0x80]))).rejects.toThrow('UTF-8');
  expect(p.right.raw).toBeNull();
});
