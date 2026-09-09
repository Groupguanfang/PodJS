import { test, expect } from 'bun:test';
import { CompanionFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
import { CompanionFileReply, encodeFileReply, decodeFileReply } from '../platforms/harmony/companion/src/main/ets/CompanionFileReply';
const digest = 'a'.repeat(64);
const bytes = (value: object) => new TextEncoder().encode(JSON.stringify(value));
test('file replies bind exact request digest and preserve bounded ordered missing indexes', () => {
  const request = new CompanionFileRequest(); request.method = 'missing'; request.transfer_id = 'x';
  const reply = new CompanionFileReply(); reply.request_sha256 = digest; reply.value.phase = 'accepted'; reply.value.missing = Array.from({ length: 256 }, (_, i) => i);
  expect(decodeFileReply(encodeFileReply(reply, request), request, digest).value.missing).toEqual(reply.value.missing);
  expect(() => decodeFileReply(encodeFileReply(reply, request), request, 'b'.repeat(64))).toThrow('mismatch');
  for (const missing of [[1, 1], [1, 0], [-1], [256], [0.5]]) {
    reply.value.missing = missing; expect(() => encodeFileReply(reply, request)).toThrow();
  }
});
test('file replies reject false completion, terminal missing chunks and leaked local fields', () => {
  for (const method of ['finish', 'cancel', 'chunk', 'missing']) {
    const request = new CompanionFileRequest(); request.method = method;
    const reply = { version: 1, type: 'reply', request_sha256: digest, value: { phase: 'offered', ...(method === 'missing' ? { missing: [] } : {}) } };
    expect(() => decodeFileReply(bytes(reply), request, digest)).toThrow();
  }
  const request = new CompanionFileRequest(); request.method = 'missing';
  for (const phase of ['complete', 'cancelled']) expect(() => decodeFileReply(bytes({ version: 1, type: 'reply', request_sha256: digest, value: { phase, missing: [0] } }), request, digest)).toThrow();
  request.method = 'status';
  expect(() => decodeFileReply(bytes({ version: 1, type: 'reply', request_sha256: digest, value: { phase: 'accepted', path: '/private' } }), request, digest)).toThrow();
});
