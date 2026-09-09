import { test, expect } from 'bun:test';
import { CompanionFileManifest, CompanionFileRequest, encodeFileRequest, decodeFileRequest, encodeFileChunk, decodeFileChunk } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
const utf8 = (text: string) => new TextEncoder().encode(text);
test('file requests round-trip manifest and full 64 KiB canonical standard base64 chunks', () => {
  const manifest = new CompanionFileManifest(); manifest.transfer_id = 'file-1'; manifest.size = 65537;
  manifest.sha256 = 'a'.repeat(64); manifest.chunk_hashes = ['b'.repeat(64), 'c'.repeat(64)]; manifest.mime = 'application/octet-stream';
  const offer = new CompanionFileRequest(); offer.method = 'offer'; offer.manifest = manifest;
  expect(decodeFileRequest(encodeFileRequest(offer)).manifest).toEqual(manifest);
  for (const size of [0, 1, 2, 3, 65536]) {
    const bytes = new Uint8Array(size).map((_, i) => i % 256), chunk = new CompanionFileRequest();
    chunk.method = 'chunk'; chunk.transfer_id = 'file-1'; chunk.index = 0; chunk.data = bytes;
    expect(encodeFileChunk(bytes)).toBe(Buffer.from(bytes).toString('base64'));
    expect(decodeFileRequest(encodeFileRequest(chunk)).data).toEqual(bytes);
  }
});
test('file protocol refuses remote acceptance, paths, duplicate nested keys and invalid chunk encoding', () => {
  for (const request of [
    '{"version":1,"method":"accept","transfer_id":"x"}',
    '{"version":1,"method":"status","transfer_id":"x","path":"/tmp/x"}',
    '{"version":1,"method":"status","transfer_id":"../x"}',
    '{"version":1,"method":"offer","manifest":{"transfer_id":"x","transfer_id":"y","size":0,"sha256":"' + 'a'.repeat(64) + '","chunk_hashes":[],"mime":""}}'
  ]) expect(() => decodeFileRequest(utf8(request))).toThrow();
  for (const text of ['AB==', 'AAB=', 'AA', 'AA==\n', '_A==', '====']) expect(() => decodeFileChunk(text)).toThrow();
  expect(() => decodeFileRequest(new Uint8Array([0xc0, 0x80]))).toThrow();
});
test('file manifest quota, hash count, MIME bytes and chunk index bounds match storage limits', () => {
  const request = new CompanionFileRequest(); request.method = 'offer';
  const manifest = new CompanionFileManifest(); manifest.transfer_id = 'x'; manifest.sha256 = 'a'.repeat(64); request.manifest = manifest;
  manifest.size = 16777217; expect(() => encodeFileRequest(request)).toThrow();
  manifest.size = 1; expect(() => encodeFileRequest(request)).toThrow();
  manifest.chunk_hashes = ['a'.repeat(64)]; manifest.mime = '中'.repeat(43); expect(() => encodeFileRequest(request)).toThrow();
  manifest.mime = '\u0080'; expect(() => encodeFileRequest(request)).toThrow();
  request.method = 'chunk'; request.transfer_id = 'x'; request.index = 256; expect(() => encodeFileRequest(request)).toThrow();
});
