import { CompanionSyncSession, CompanionSyncFrame } from './CompanionSyncAuth';
import { CompanionFileRequests } from './CompanionFileRequests';
import { CompanionFileRequest, decodeFileObject, decodeFileRequest } from './CompanionFileWire';
import { CompanionFileValue, CompanionFileReply, encodeFileReply } from './CompanionFileReply';
import { CompanionMessageDigest } from './CompanionMessageOutbox';

/** Host receiver contract. execute MUST durably apply before resolving; offer
 * stores metadata only and MUST NOT imply local acceptance. No native paths or
 * raw errors may be returned. This port does not implement receiver storage. */
export interface CompanionFileReceiver {
  matchesIdentity(app: string, local: string): boolean;
  executeAuthenticated(peer: string, request: CompanionFileRequest): Promise<CompanionFileValue>;
}
export class CompanionFileChannels {
  requests: CompanionFileRequests | null;
  receiver: CompanionFileReceiver | null;
  crypto: CompanionMessageDigest;
  constructor(requests: CompanionFileRequests | null, receiver: CompanionFileReceiver | null, crypto: CompanionMessageDigest) {
    this.requests = requests; this.receiver = receiver; this.crypto = crypto;
  }
}
export class CompanionFilePumpResult {
  status: string;
  reply: CompanionSyncFrame | null;
  constructor(status: string, reply: CompanionSyncFrame | null) { this.status = status; this.reply = reply; }
}
/** Exclusive file dispatcher, or child of a single shared channel owner.
 * Transport must bound IO, write signed frames in order and close on failures. */
export class CompanionFilePump {
  private session: CompanionSyncSession;
  private requests: CompanionFileRequests | null;
  private receiver: CompanionFileReceiver | null;
  private crypto: CompanionMessageDigest;
  private closed: boolean = false;
  private busy: boolean = false;
  constructor(session: CompanionSyncSession, requests: CompanionFileRequests | null, receiver: CompanionFileReceiver | null, crypto: CompanionMessageDigest) {
    if (requests === null && receiver === null) throw new Error('file channels unavailable');
    if ((requests !== null && !requests.matchesIdentity(session.appId(), session.localId())) ||
      (receiver !== null && !receiver.matchesIdentity(session.appId(), session.localId()))) throw new Error('file session identity mismatch');
    this.session = session; this.requests = requests; this.receiver = receiver; this.crypto = crypto;
  }
  close(): void { this.closed = true; this.session.close(); }
  private check(): void { if (this.closed) throw new Error('file pump closed'); }
  private async run<T>(work: () => Promise<T>): Promise<T> {
    this.check(); if (this.busy) { this.close(); throw new Error('file operation already active'); } this.busy = true;
    try { const result = await work(); this.check(); return result; }
    catch (error) { this.close(); throw error; }
    finally { this.busy = false; }
  }
  sendNext(): Promise<CompanionSyncFrame | null> {
    return this.run(async () => {
      if (this.requests === null) throw new Error('outgoing file requests unavailable');
      const request = await this.requests.next(this.session.peerId()); this.check();
      if (request === null) return null;
      return this.session.send('file', request.messageId, Array.from(request.payload));
    });
  }
  async receive(frame: CompanionSyncFrame): Promise<CompanionFilePumpResult> {
    let copy: CompanionSyncFrame;
    try {
      const fields = ['protocolVersion', 'sessionId', 'sequence', 'channel', 'messageId', 'payload', 'tag'];
      if (Object.keys(frame).length !== 7 || !Object.keys(frame).every((key: string) => fields.includes(key))) throw new Error('invalid file frame fields');
      copy = new CompanionSyncFrame(frame.sessionId, frame.sequence, frame.channel, frame.messageId, frame.payload);
      copy.protocolVersion = frame.protocolVersion; copy.tag = frame.tag.slice();
    } catch (error) { this.close(); throw error; }
    return this.run(async () => {
      const verified = await this.session.verify(copy); this.check();
      if (copy.channel !== 'file') throw new Error('unexpected file channel');
      const bytes = new Uint8Array(copy.payload), object = decodeFileObject(bytes);
      if (Object.keys(object).includes('type')) {
        if (this.requests === null) throw new Error('outgoing file replies unavailable');
        const status = await this.requests.receiveAuthenticated(this.session.peerId(), copy.messageId, bytes); this.check();
        if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence);
        return new CompanionFilePumpResult(status, null);
      }
      if (this.receiver === null) throw new Error('incoming file receiver unavailable');
      const request = decodeFileRequest(bytes), method = request.method;
      const digest = await this.crypto.sha256(bytes); this.check();
      if (digest.length !== 32) throw new Error('invalid file request digest');
      const value = await this.receiver.executeAuthenticated(this.session.peerId(), request); this.check();
      const reply = new CompanionFileReply(); reply.value = value;
      for (const byte of digest) reply.request_sha256 += byte.toString(16).padStart(2, '0');
      // Validate against an independent decode: a receiver's mutation of its
      // request object cannot weaken method-specific reply validation.
      const encoded = encodeFileReply(reply, decodeFileRequest(bytes));
      if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence); this.check();
      return new CompanionFilePumpResult(method, await this.session.send('file', copy.messageId, Array.from(encoded)));
    });
  }
}
