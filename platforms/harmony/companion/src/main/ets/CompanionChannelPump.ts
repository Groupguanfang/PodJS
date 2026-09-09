import { CompanionSyncSession, CompanionSyncFrame } from './CompanionSyncAuth';
import { CompanionState } from './CompanionState';
import { CompanionStatePump } from './CompanionStatePump';
import { CompanionMessageOutbox } from './CompanionMessageOutbox';
import { CompanionMessageInbox, CompanionIncomingMessage } from './CompanionMessageInbox';
import { CompanionMessagePump, CompanionMessagePumpResult, CompanionMessageHandler } from './CompanionMessagePump';
import { CompanionFileChannels, CompanionFilePump } from './CompanionFilePump';

export class CompanionChannelResult extends CompanionMessagePumpResult {
  channel: string;
  constructor(channel: string, status: string, reply: CompanionSyncFrame | null, delivery: CompanionIncomingMessage | null = null) {
    super(status, reply, delivery); this.channel = channel;
  }
}
/** Exclusive state/message/optional-file session owner. Dispatch hints are untrusted until
 * the selected pump verifies the complete signed frame; they never authorize IO.
 * File requests/receiver must be explicitly configured. Host must serialize returned frame writes
 * with these operations, and close this owner when transport fails. */
export class CompanionChannelPump {
  private state: CompanionStatePump;
  private message: CompanionMessagePump;
  private file: CompanionFilePump | null = null;
  private busy: boolean = false;
  private closed: boolean = false;
  constructor(session: CompanionSyncSession, state: CompanionState, outbox: CompanionMessageOutbox, inbox: CompanionMessageInbox, files: CompanionFileChannels | null = null) {
    this.state = new CompanionStatePump(session, state); this.message = new CompanionMessagePump(session, outbox, inbox);
    if (files !== null) this.file = new CompanionFilePump(session, files.requests, files.receiver, files.crypto);
  }
  close(): void { this.closed = true; this.state.close(); this.message.close(); if (this.file !== null) this.file.close(); }
  private async run<T>(work: () => Promise<T>): Promise<T> {
    if (this.closed) throw new Error('channel pump closed');
    if (this.busy) { this.close(); throw new Error('channel operation already active'); }
    this.busy = true;
    try { const result = await work(); if (this.closed) throw new Error('channel pump closed'); return result; }
    catch (error) { this.close(); throw error; }
    finally { this.busy = false; }
  }
  sendState(): Promise<CompanionSyncFrame | null> { return this.run(() => this.state.sendNext()); }
  sendMessage(now: number): Promise<CompanionSyncFrame | null> { return this.run(() => this.message.sendNext(now)); }
  sendFile(): Promise<CompanionSyncFrame | null> {
    return this.run(async () => { if (this.file === null) throw new Error('file channel not configured'); return this.file.sendNext(); });
  }
  acknowledgeMessage(delivery: CompanionIncomingMessage, now: number): Promise<CompanionSyncFrame> {
    return this.run(() => this.message.acknowledge(delivery, now));
  }
  receive(frame: CompanionSyncFrame, now: number, handler: CompanionMessageHandler | null = null): Promise<CompanionChannelResult> {
    return this.run(async () => {
      if (frame.channel === 'file') {
        if (this.file === null) throw new Error('file channel not configured');
        const result = await this.file.receive(frame); return new CompanionChannelResult('file', result.status, result.reply);
      }
      if (frame.channel === 'state' || (frame.channel === 'ack' && frame.payload[0] === 3)) {
        const result = await this.state.receive(frame);
        return new CompanionChannelResult('state', result.status, result.reply);
      }
      if (frame.channel === 'message' || (frame.channel === 'ack' && (frame.payload[0] === 1 || frame.payload[0] === 2))) {
        const result = await this.message.receive(frame, now, handler);
        return new CompanionChannelResult('message', result.status, result.reply, result.delivery);
      }
      throw new Error('unsupported channel or ACK kind');
    });
  }
}
