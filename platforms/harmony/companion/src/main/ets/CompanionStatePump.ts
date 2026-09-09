import { CompanionState } from './CompanionState';
import { CompanionSyncSession, CompanionSyncFrame } from './CompanionSyncAuth';
import { encodeStateAck, decodeStateAck } from './CompanionStateAck';

/** Reject malformed UTF-8 instead of replacing bytes before durable hashing. */
export function decodeStateUtf8(bytes: number[]): string {
  return decodeSyncUtf8(bytes, 262144);
}
export function decodeSyncUtf8(bytes: number[], maximum: number): string {
  if (!Number.isInteger(maximum) || maximum < 1 || maximum > 2097152 || bytes.length > maximum) throw new Error('payload too large');
  let result = '';
  for (let i = 0; i < bytes.length;) {
    const first = bytes[i++]; let point = first; let count = 0; let minimum = 0;
    if (!Number.isInteger(first) || first < 0 || first > 255) throw new Error('invalid UTF-8');
    if (first >= 0xc2 && first <= 0xdf) { point = first & 31; count = 1; minimum = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { point = first & 15; count = 2; minimum = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { point = first & 7; count = 3; minimum = 0x10000; }
    else if (first >= 0x80) throw new Error('invalid UTF-8');
    for (let j = 0; j < count; j++) {
      const next = bytes[i++];
      if (!Number.isInteger(next) || next < 0x80 || next > 0xbf) throw new Error('invalid UTF-8');
      point = (point << 6) | (next & 63);
    }
    if (point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) throw new Error('invalid UTF-8');
    result += String.fromCodePoint(point);
  }
  return result;
}
export function encodeStateUtf8(text: string): number[] {
  return encodeSyncUtf8(text, 262144);
}
export function encodeSyncUtf8(text: string, maximum: number): number[] {
  if (!Number.isInteger(maximum) || maximum < 1 || maximum > 2097152) throw new Error('invalid payload limit');
  const result: number[] = [];
  for (let i = 0; i < text.length; i++) {
    let point = text.charCodeAt(i);
    if (point >= 0xd800 && point <= 0xdbff) {
      const low = text.charCodeAt(++i);
      if (!(low >= 0xdc00 && low <= 0xdfff)) throw new Error('invalid UTF-16');
      point = 0x10000 + ((point - 0xd800) << 10) + low - 0xdc00;
    } else if (point >= 0xdc00 && point <= 0xdfff) throw new Error('invalid UTF-16');
    if (point < 0x80) result.push(point);
    else if (point < 0x800) result.push(0xc0 | (point >> 6), 0x80 | (point & 63));
    else if (point < 0x10000) result.push(0xe0 | (point >> 12), 0x80 | ((point >> 6) & 63), 0x80 | (point & 63));
    else result.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 63), 0x80 | ((point >> 6) & 63), 0x80 | (point & 63));
    if (result.length > maximum) throw new Error('payload too large');
  }
  return result;
}

export class CompanionStatePumpResult {
  status: string;
  reply: CompanionSyncFrame | null;
  constructor(status: string, reply: CompanionSyncFrame | null) { this.status = status; this.reply = reply; }
}
/** Dedicated state/ACK dispatcher for one authenticated session. The caller owns
 * transport IO and must close on write failure. No polling or discovery here. */
export class CompanionStatePump {
  private session: CompanionSyncSession;
  private state: CompanionState;
  private tail: Promise<void> = Promise.resolve();
  private closed: boolean = false;
  constructor(session: CompanionSyncSession, state: CompanionState) {
    if (!state.matchesIdentity(session.appId(), session.localId())) throw new Error('state session identity mismatch');
    this.session = session; this.state = state;
  }
  close(): void { this.closed = true; this.session.close(); }
  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const result = this.tail.then(() => { if (this.closed) throw new Error('state pump closed'); return work(); })
      .catch((error: Error) => { this.close(); throw error; });
    this.tail = result.then(() => {}, () => {}); return result;
  }
  sendNext(): Promise<CompanionSyncFrame | null> {
    return this.enqueue(async () => {
      const batch = await this.state.prepare(this.session.peerId());
      if (batch === null) return null;
      return this.session.send('state', batch.messageId, encodeStateUtf8(batch.payload));
    });
  }
  async receive(frame: CompanionSyncFrame): Promise<CompanionStatePumpResult> {
    // Serialize application storage as well as session sequence changes. Own a
    // full wire copy before waiting behind an earlier storage transaction.
    let copy: CompanionSyncFrame;
    try {
      const fields = ['protocolVersion', 'sessionId', 'sequence', 'channel', 'messageId', 'payload', 'tag'];
      if (Object.keys(frame).length !== 7 || !Object.keys(frame).every((key: string) => fields.includes(key)))
        throw new Error('invalid frame fields');
      copy = new CompanionSyncFrame(frame.sessionId, frame.sequence, frame.channel, frame.messageId, frame.payload);
      copy.protocolVersion = frame.protocolVersion; copy.tag = frame.tag.slice();
    }
    catch (error) { this.close(); throw error; }
    return this.enqueue(async () => {
      const verified = await this.session.verify(copy);
      const peer = this.session.peerId();
      if (copy.channel === 'ack') {
        const ack = decodeStateAck(new Uint8Array(copy.payload));
        const matched = await this.state.acknowledgeAuthenticated(peer, copy.messageId, ack.cursor, ack.digest);
        if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence);
        return new CompanionStatePumpResult(matched ? 'ack' : 'stale_ack', null);
      }
      if (copy.channel !== 'state') throw new Error('unexpected state channel');
      const receipt = await this.state.receiveBatchAuthenticated(peer, copy.messageId, decodeStateUtf8(copy.payload));
      if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence);
      const reply = await this.session.send('ack', copy.messageId, Array.from(encodeStateAck(receipt.cursor, receipt.digest)));
      return new CompanionStatePumpResult(receipt.duplicate ? 'duplicate' : 'applied', reply);
    });
  }
}
