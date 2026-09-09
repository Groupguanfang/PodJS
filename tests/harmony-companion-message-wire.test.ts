import { test, expect } from 'bun:test';
import { CompanionMessageEnvelope, encodeMessageEnvelope, decodeMessageEnvelope, CompanionMessageAck, encodeMessageAck, decodeMessageAck } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';

test('message envelope matches big-endian Java ByteBuffer layout including safe integer limit', () => {
  for (const expiry of [0, 4294967297, Number.MAX_SAFE_INTEGER]) {
    for (const high of [false, true]) {
      const payload = new Uint8Array([0, 255, 128]);
      const expected = Buffer.alloc(12); expected.writeBigUInt64BE(BigInt(expiry)); expected[8] = high ? 1 : 0; expected.set(payload, 9);
      const encoded = encodeMessageEnvelope(new CompanionMessageEnvelope(expiry, high, payload));
      expect(encoded).toEqual(new Uint8Array(expected));
      const padded = new Uint8Array(20); padded.set(encoded, 3);
      const decoded = decodeMessageEnvelope(padded.subarray(3, 15));
      expect(decoded.expiresAt).toBe(expiry); expect(decoded.highPriority).toBe(high); expect(decoded.payload).toEqual(payload);
      padded.fill(7); expect(decoded.payload).toEqual(payload);
    }
  }
});
test('message envelope preserves full opaque payload, rejects overflow and invalid metadata', () => {
  const payload = new Uint8Array(262144).fill(255), message = new CompanionMessageEnvelope(99, true, payload);
  payload.fill(0); expect(decodeMessageEnvelope(encodeMessageEnvelope(message)).payload).toEqual(new Uint8Array(262144).fill(255));
  for (const expiry of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) expect(() => new CompanionMessageEnvelope(expiry, false, new Uint8Array())).toThrow();
  for (const size of [0, 8, 262154]) expect(() => decodeMessageEnvelope(new Uint8Array(size))).toThrow();
  const invalid = new Uint8Array(9); invalid[8] = 2; expect(() => decodeMessageEnvelope(invalid)).toThrow('priority');
  invalid[8] = 0; invalid[0] = 128; expect(() => decodeMessageEnvelope(invalid)).toThrow('expiry');
});
test('message ACK kinds are isolated from state ACK and copy the content digest', () => {
  for (const expired of [false, true]) {
    const digest = new Uint8Array(32).fill(17), ack = new CompanionMessageAck(expired, digest); digest.fill(9);
    const encoded = encodeMessageAck(ack); expect(encoded[0]).toBe(expired ? 2 : 1);
    const decoded = decodeMessageAck(encoded); encoded.fill(0);
    expect(decoded.expired).toBe(expired); expect(decoded.digest).toEqual(new Uint8Array(32).fill(17));
  }
  for (const kind of [0, 3, 255]) { const bytes = new Uint8Array(33); bytes[0] = kind; expect(() => decodeMessageAck(bytes)).toThrow(); }
  expect(() => decodeMessageAck(new Uint8Array(41).fill(3))).toThrow();
});
