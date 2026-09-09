import { test, expect } from 'bun:test';
import { createHash } from 'node:crypto';
import { CompanionIncomingFiles, CompanionIncomingFilePort } from '../platforms/harmony/companion/src/main/ets/CompanionIncomingFiles';
import { CompanionFileRequest, CompanionFileManifest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
class Port implements CompanionIncomingFilePort {
  raw: string | null = null; journalFail = false; reserveFail = false; removeFail = false;
  allocations = 0; removals = 0; chunks = new Map<number, Uint8Array>();
  async exclusive<T>(work: () => Promise<T>) { return work(); }
  async readJournal() { return this.raw; }
  async writeJournal(text: string) { if (this.journalFail) throw new Error('journal failed'); this.raw = text; }
  async reserve() { if (this.reserveFail) throw new Error('reserve failed'); this.allocations++; }
  async remove() { if (this.removeFail) throw new Error('remove failed'); this.removals++; this.chunks.clear(); }
  async writeChunk(_peer: string, _manifest: CompanionFileManifest, index: number, bytes: Uint8Array) { this.chunks.set(index, bytes.slice()); }
  async readCompleteChunk(_peer: string, _manifest: CompanionFileManifest, index: number) { return this.chunks.get(index)!.slice(); }
  async missing(_peer: string, manifest: CompanionFileManifest) { return manifest.chunk_hashes.map((_, i) => i).filter(i => !this.chunks.has(i) || hash(this.chunks.get(i)!) !== manifest.chunk_hashes[i]); }
  async finish(peer: string, manifest: CompanionFileManifest) {
    if ((await this.missing(peer, manifest)).length) throw new Error('missing');
    const bytes = Buffer.concat([...this.chunks.keys()].sort((a, b) => a - b).map(i => this.chunks.get(i)!));
    if (hash(bytes) !== manifest.sha256) throw new Error('whole file hash mismatch');
  }
}
const open = (port: Port) => new CompanionIncomingFiles('app', 'watch', port, { async sha256(bytes) { return new Uint8Array(createHash('sha256').update(bytes).digest()); } });
test('local consent UI can list and reject without allocation or remote impersonation', async () => {
  const port = new Port(), files = open(port);
  await files.executeAuthenticated('phone', offer());
  const snapshot = await files.listLocal();
  expect(snapshot[0].manifest.size).toBe(3);
  snapshot[0].manifest.size = 999;
  expect((await files.listLocal())[0].manifest.size).toBe(3);
  await files.cancelLocal('phone', 'file');
  expect(port.allocations).toBe(0);
  expect(port.removals).toBe(0);
  expect((await open(port).listLocal())[0].phase).toBe('cancelled');
  await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('cancelled');
  await files.cancelLocal('phone', 'file');
});
function command(method: string) { const request = new CompanionFileRequest(); request.method = method; request.transfer_id = 'file'; return request; }
function offer() { const request = command('offer'), manifest = new CompanionFileManifest(); manifest.transfer_id = 'file'; manifest.size = 3; manifest.mime = 'application/octet-stream'; manifest.sha256 = hash(new Uint8Array([1, 2, 3])); manifest.chunk_hashes = [manifest.sha256]; request.manifest = manifest; return request; }
test('incoming offer does not allocate or grant consent; accepted chunks verify before publishing', async () => {
  const port = new Port(), files = open(port);
  expect((await files.executeAuthenticated('phone', offer())).phase).toBe('offered'); expect(port.allocations).toBe(0);
  await expect(files.executeAuthenticated('phone', command('missing'))).rejects.toThrow('not accepted');
  expect((await open(port).pendingConsent()).length).toBe(1); await files.acceptLocal('phone', 'file'); expect(port.allocations).toBe(1);
  const chunk = command('chunk'); chunk.data = new Uint8Array([4, 5, 6]);
  await expect(files.executeAuthenticated('phone', chunk)).rejects.toThrow('hash mismatch'); expect(port.chunks.size).toBe(0);
  chunk.data = new Uint8Array([1, 2, 3]); await files.executeAuthenticated('phone', chunk);
  expect((await open(port).executeAuthenticated('phone', command('missing'))).missing).toEqual([]);
  expect((await files.executeAuthenticated('phone', command('finish'))).phase).toBe('complete');
  await expect(files.acceptLocal('other', 'file')).rejects.toThrow('unknown');
});
test('accept and cancel intents persist before side effects and recover after interrupted backend work', async () => {
  const port = new Port(), files = open(port); await files.executeAuthenticated('phone', offer());
  port.journalFail = true; await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('journal failed'); expect(port.allocations).toBe(0);
  port.journalFail = false; port.reserveFail = true; await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('reserve failed');
  expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('accepting');
  port.reserveFail = false; await open(port).recover(); expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('accepted');
  port.removeFail = true; await expect(files.executeAuthenticated('phone', command('cancel'))).rejects.toThrow('remove failed');
  expect((await files.executeAuthenticated('phone', command('status'))).phase).toBe('cancelling');
  port.removeFail = false; await open(port).recover(); expect((await files.executeAuthenticated('phone', command('missing'))).missing).toEqual([]);
  await expect(files.acceptLocal('phone', 'file')).rejects.toThrow('cancelled');
});
