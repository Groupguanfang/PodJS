import { CompanionSyncSession, CompanionSyncFrame } from './CompanionSyncAuth';
import { CompanionMessageOutbox } from './CompanionMessageOutbox';
import { CompanionMessageInbox, CompanionIncomingMessage } from './CompanionMessageInbox';
import { encodeMessageEnvelope, encodeMessageAck, decodeMessageAck, CompanionMessageAck } from './CompanionMessageWire';

export interface CompanionMessageHandler { apply(message: CompanionIncomingMessage): Promise<void>; }
export class CompanionMessagePumpResult {
  status: string;
  reply: CompanionSyncFrame | null;
  delivery: CompanionIncomingMessage | null;
  constructor(status: string, reply: CompanionSyncFrame | null, delivery: CompanionIncomingMessage | null = null) {
    this.status = status; this.reply = reply; this.delivery = delivery;
  }
}
/** Dedicated message/ACK dispatcher. Host owns bounded transport IO and must
 * write returned frames in order, close on write failure, and never use another
 * dispatcher concurrently on this session. No plaintext/encryption assumptions. */
export class CompanionMessagePump {
  private session: CompanionSyncSession;
  private outbox: CompanionMessageOutbox;
  private inbox: CompanionMessageInbox;
  private busy: boolean = false;
  private closed: boolean = false;
  constructor(session: CompanionSyncSession, outbox: CompanionMessageOutbox, inbox: CompanionMessageInbox) {
    if (!outbox.matchesIdentity(session.appId(), session.localId()) || !inbox.matchesIdentity(session.appId(), session.localId()))
      throw new Error('message session identity mismatch');
    this.session = session; this.outbox = outbox; this.inbox = inbox;
  }
  close(): void { this.closed = true; this.session.close(); }
  private check(): void { if (this.closed) throw new Error('message pump closed'); }
  private async run<T>(work: () => Promise<T>): Promise<T> {
    this.check();
    if (this.busy) { this.close(); throw new Error('message pump operation already active'); }
    this.busy = true;
    try { const value = await work(); this.check(); return value; }
    catch (error) { this.close(); throw error; }
    finally { this.busy = false; }
  }
  sendNext(now: number): Promise<CompanionSyncFrame | null> {
    return this.run(async () => {
      const rows = await this.outbox.pending(this.session.peerId(), now, 1); this.check();
      if (rows.length === 0) return null;
      return this.session.send('message', rows[0].messageId, Array.from(encodeMessageEnvelope(rows[0].envelope)));
    });
  }
  acknowledge(delivery: CompanionIncomingMessage, now: number): Promise<CompanionSyncFrame> {
    const peer = delivery.peer, id = delivery.messageId, digest = delivery.digest.slice();
    return this.run(async () => {
      if (peer !== this.session.peerId()) throw new Error('message acknowledgement peer mismatch');
      const applied = await this.inbox.acknowledge(peer, id, digest, now); this.check();
      return this.session.send('ack', id, Array.from(encodeMessageAck(new CompanionMessageAck(false, applied.digest))));
    });
  }
  /** Null handler defers business completion: durable pending permits sequence
   * commit, but sends no ACK that could remove the sender's outgoing message. */
  async receive(frame: CompanionSyncFrame, now: number, handler: CompanionMessageHandler | null = null): Promise<CompanionMessagePumpResult> {
    let copy: CompanionSyncFrame;
    try {
      const fields = ['protocolVersion', 'sessionId', 'sequence', 'channel', 'messageId', 'payload', 'tag'];
      if (Object.keys(frame).length !== 7 || !Object.keys(frame).every((key: string) => fields.includes(key))) throw new Error('invalid message frame fields');
      copy = new CompanionSyncFrame(frame.sessionId, frame.sequence, frame.channel, frame.messageId, frame.payload);
      copy.protocolVersion = frame.protocolVersion; copy.tag = frame.tag.slice();
    } catch (error) { this.close(); throw error; }
    return this.run(async () => {
      const verified = await this.session.verify(copy); this.check();
      if (copy.channel === 'ack') {
        const ack = decodeMessageAck(new Uint8Array(copy.payload));
        const matched = await this.outbox.acknowledgeAuthenticated(this.session.peerId(), copy.messageId, ack.digest); this.check();
        if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence);
        return new CompanionMessagePumpResult(matched ? 'ack' : 'stale_ack', null);
      }
      if (copy.channel !== 'message') throw new Error('unexpected message channel');
      const delivery = await this.inbox.receiveAuthenticated(this.session.peerId(), copy.messageId, new Uint8Array(copy.payload), now); this.check();
      const token = delivery.digest.slice();
      let status = delivery.status;
      if (status === 'pending' && handler !== null) {
        // Keep the content token private: application mutation of its delivery
        // must not retarget or change the receipt we commit after business work.
        await handler.apply(delivery); this.check();
        await this.inbox.acknowledge(this.session.peerId(), copy.messageId, token, now); this.check();
        status = 'applied';
      }
      if (verified.delivery !== 'duplicate') await this.session.commit(copy.sequence); this.check();
      if (status === 'pending') return new CompanionMessagePumpResult('pending', null, delivery);
      const reply = await this.session.send('ack', copy.messageId, Array.from(encodeMessageAck(new CompanionMessageAck(status === 'expired', token))));
      return new CompanionMessagePumpResult(status, reply);
    });
  }
}
