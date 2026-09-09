import { CompanionFileManifest, CompanionFileRequest, validateFileManifest, decodeFileRequest } from './CompanionFileWire';
import { CompanionFileRequests } from './CompanionFileRequests';
import { decodeFileReply } from './CompanionFileReply';
import { CompanionMessageDigest } from './CompanionMessageOutbox';

/** The application retains immutable source bytes and manifest across restarts. */
export interface CompanionFileSource { readChunk(index: number): Promise<Uint8Array>; }
/** One driver owns a peer's file request queue. step queues at most one request;
 * the shared authenticated transport sends it. Call again after a durable reply,
 * pacing waiting_consent from foreground UI rather than busy polling. A crash
 * between consuming a reply and enqueueing restarts with an idempotent offer. */
export class CompanionFileSender {
  private requests: CompanionFileRequests;
  private peer: string;
  private manifest: CompanionFileManifest;
  private source: CompanionFileSource;
  private crypto: CompanionMessageDigest;
  private busy: boolean = false;
  constructor(requests: CompanionFileRequests, peer: string, manifest: CompanionFileManifest, source: CompanionFileSource, crypto: CompanionMessageDigest) {
    validateFileManifest(manifest);
    this.requests = requests; this.peer = peer; this.source = source; this.crypto = crypto;
    this.manifest = new CompanionFileManifest();
    this.manifest.transfer_id = manifest.transfer_id; this.manifest.size = manifest.size;
    this.manifest.sha256 = manifest.sha256; this.manifest.mime = manifest.mime; this.manifest.chunk_hashes = manifest.chunk_hashes.slice();
  }
  private check(request: CompanionFileRequest): void {
    const id = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
    if (id !== this.manifest.transfer_id) throw new Error('peer file queue belongs to another transfer');
    if (request.method === 'offer') {
      const offered = request.manifest as CompanionFileManifest;
      if (offered.size !== this.manifest.size || offered.sha256 !== this.manifest.sha256 || offered.mime !== this.manifest.mime ||
        JSON.stringify(offered.chunk_hashes) !== JSON.stringify(this.manifest.chunk_hashes)) throw new Error('source manifest changed');
    }
  }
  matchesQueue(requests: CompanionFileRequests | null, peer: string): boolean { return requests === this.requests && peer === this.peer; }
  async step(cancelled: () => boolean = () => false): Promise<string> {
    if (this.busy) throw new Error('file sender already busy'); this.busy = true;
    const check = (): void => { if (cancelled()) throw new Error('file sender cancelled'); };
    try {
      check();
      const pending = await this.requests.next(this.peer);
      check();
      if (pending !== null) { this.check(decodeFileRequest(pending.payload)); return 'awaiting_reply'; }
      const completed = await this.requests.completed(this.peer);
      check();
      if (completed.length > 1) throw new Error('file sender requires exclusive peer queue');
      const next = new CompanionFileRequest(); next.transfer_id = this.manifest.transfer_id;
      let state = 'queued';
      if (completed.length === 0) { next.method = 'offer'; next.manifest = this.manifest; }
      else {
        const record = completed[0], request = decodeFileRequest(record.payload); this.check(request);
        const value = decodeFileReply(record.reply as Uint8Array, request, record.digest).value;
        if (value.phase === 'complete' || value.phase === 'cancelled') return value.phase;
        if (value.phase === 'offered' || value.phase === 'accepting' || value.phase === 'cancelling') {
          next.method = 'status'; state = 'waiting_consent';
        } else if (request.method === 'missing') {
          const missing = value.missing as number[];
          if (missing.some((index: number) => index >= this.manifest.chunk_hashes.length)) throw new Error('peer requested unknown chunk');
          if (missing.length === 0) next.method = 'finish';
          else {
            next.method = 'chunk'; next.index = missing[0]; next.data = (await this.source.readChunk(next.index)).slice();
            check();
            if (next.data.length !== Math.min(65536, this.manifest.size - next.index * 65536)) throw new Error('source chunk size changed');
            const digest = await this.crypto.sha256(next.data); check(); let hash = '';
            for (const byte of digest) hash += byte.toString(16).padStart(2, '0');
            if (hash !== this.manifest.chunk_hashes[next.index]) throw new Error('source chunk hash changed');
          }
        } else next.method = 'missing';
        // Keep the observation if source validation failed, so retry is safe.
        if (!await this.requests.forgetCompleted(this.peer, record.messageId, cancelled)) throw new Error('file observation changed');
        check();
      }
      check();
      await this.requests.enqueue(this.peer, next, cancelled); return state;
    } finally { this.busy = false; }
  }
}
