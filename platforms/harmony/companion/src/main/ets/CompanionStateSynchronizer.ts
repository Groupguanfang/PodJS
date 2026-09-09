import { CompanionState } from './CompanionState';
import { CompanionStatePump, encodeSyncUtf8 } from './CompanionStatePump';
import { CompanionSyncFrame } from './CompanionSyncAuth';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { decodeSyncObject } from './CompanionSyncExchange';
import { CompanionStreamTimer } from './CompanionPacketStream';

export class CompanionStateSyncStop {
  reason: string;
  error: Error | null;
  constructor(reason: string, error: Error | null) { this.reason = reason; this.error = error; }
}

/** Explicit foreground run, taking exclusive ownership of the authenticated
 * connection, not the state store. No discovery, reconnect, background service,
 * message/file routing or implicit remote completion protocol is created. */
export class CompanionStateSynchronizer {
  readonly stopped: Promise<CompanionStateSyncStop>;
  private resolveStopped: (result: CompanionStateSyncStop) => void = () => {};
  private connection: CompanionSyncConnection;
  private pump: CompanionStatePump;
  private closed: boolean = false;
  private wanted: boolean = true;
  private inFlight: boolean = false;
  private writerScheduled: boolean = false;
  private tail: Promise<void> = Promise.resolve();
  private cancelTimer: () => void = () => {};
  private unsubscribe: () => void = () => {};
  private drained: Promise<void> | null = null;
  private resolveDrained: (() => void) | null = null;
  private rejectDrained: ((error: Error) => void) | null = null;
  constructor(connection: CompanionSyncConnection, state: CompanionState,
    timer: CompanionStreamTimer, durationMs: number) {
    if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 120000) throw new Error('invalid synchronization duration');
    this.pump = new CompanionStatePump(connection.session, state); this.connection = connection;
    this.stopped = new Promise<CompanionStateSyncStop>(resolve => { this.resolveStopped = resolve; });
    try {
      this.unsubscribe = state.subscribe(() => { if (!this.closed) this.requestState(); });
      this.cancelTimer = timer.schedule(durationMs, () => this.stop('deadline', new Error('state synchronization deadline')));
      if (this.closed) { this.cancelTimer(); this.unsubscribe(); return; }
      this.receiveLoop(); this.scheduleWriter();
    } catch (error) { this.stop('failed', error as Error); }
  }
  close(): void { this.stop('closed', new Error('state synchronization closed')); }
  /** Resolves when all current local outgoing cycles have matching durable ACKs
   * and a new prepare finds no changes. The run remains alive to receive edits;
   * this does not claim that a remote peer has no undisclosed pending changes. */
  synchronize(): Promise<void> {
    if (this.closed) return Promise.reject(new Error('state synchronization stopped'));
    if (this.drained === null) this.drained = new Promise<void>((resolve, reject) => {
      this.resolveDrained = resolve; this.rejectDrained = reject;
    });
    const result = this.drained; this.requestState(); return result;
  }
  requestState(): void {
    this.check(); this.wanted = true; this.scheduleWriter();
  }
  private check(): void { if (this.closed) throw new Error('state synchronization stopped'); }
  private stop(reason: string, error: Error | null): void {
    if (this.closed) return; this.closed = true;
    if (this.rejectDrained !== null) this.rejectDrained(error === null ? new Error('state synchronization ended') : error);
    this.drained = null; this.resolveDrained = null; this.rejectDrained = null;
    this.resolveStopped(new CompanionStateSyncStop(reason, error));
    try { this.cancelTimer(); } catch (_) {}
    try { this.unsubscribe(); } catch (_) {}
    try { this.pump.close(); } catch (_) {}
    try { this.connection.close(); } catch (_) {}
  }
  private enqueue(work: () => Promise<void>): Promise<void> {
    const result = this.tail.then(() => { this.check(); return work(); });
    this.tail = result.then(() => {}, () => {}); return result;
  }
  private async write(frame: CompanionSyncFrame): Promise<void> {
    this.check();
    await this.connection.stream.write(new Uint8Array(encodeSyncUtf8(JSON.stringify(frame), 2097152)));
    this.check();
  }
  private scheduleWriter(): void {
    if (this.closed || this.writerScheduled || !this.wanted || this.inFlight) return;
    this.writerScheduled = true;
    this.enqueue(async () => {
      this.wanted = false; this.inFlight = true;
      const frame = await this.pump.sendNext(); this.check();
      if (frame !== null) { await this.write(frame); return; }
      this.inFlight = false;
      if (!this.wanted && this.resolveDrained !== null) {
        this.resolveDrained(); this.drained = null; this.resolveDrained = null; this.rejectDrained = null;
      }
    }).then(() => { this.writerScheduled = false; this.scheduleWriter(); }, (error: Error) => this.stop('failed', error));
  }
  private async receiveLoop(): Promise<void> {
    try {
      while (!this.closed) {
        const packet = await this.connection.stream.read(); this.check();
        if (packet === null) { this.stop('eof', null); return; }
        const frame = decodeSyncObject(packet, 2097152) as CompanionSyncFrame;
        await this.enqueue(async () => {
          const result = await this.pump.receive(frame); this.check();
          // All signing and complete writes share this queue: an ACK cannot
          // overtake a previously signed outgoing state frame on the wire.
          if (result.reply !== null) await this.write(result.reply);
          if (result.status === 'ack') { this.inFlight = false; this.wanted = true; }
          else if (result.status === 'applied') this.wanted = true;
        });
        this.scheduleWriter();
      }
    } catch (error) { this.stop('failed', error as Error); }
  }
}
