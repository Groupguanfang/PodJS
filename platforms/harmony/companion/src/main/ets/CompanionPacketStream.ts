import { CompanionSyncPacketStream } from './CompanionSyncExchange';
import { CompanionSyncFraming, encodeSyncPacket } from './CompanionSyncFraming';

export interface CompanionByteWriter {
  send(bytes: Uint8Array): Promise<void>;
  close(): void;
}
export interface CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void;
}
class PendingWrite {
  bytes: Uint8Array;
  resolve: () => void;
  reject: (error: Error) => void;
  constructor(bytes: Uint8Array, resolve: () => void, reject: (error: Error) => void) {
    this.bytes = bytes; this.resolve = resolve; this.reject = reject;
  }
}
/** Event-driven adapter with bounded queues. One consumer and at most 8 writes;
 * each direction retains at most 4 MiB of complete packets. Overflow closes,
 * rather than dropping protocol bytes. The absolute lifetime includes handshake
 * and data transfer; build a fresh stream/session after expiry. */
export class CompanionPacketStream implements CompanionSyncPacketStream {
  private writer: CompanionByteWriter;
  private decoder: CompanionSyncFraming = new CompanionSyncFraming();
  private queue: Uint8Array[] = [];
  private queuedBytes: number = 0;
  private writes: PendingWrite[] = [];
  private writeBytes: number = 0;
  private resolveRead: ((packet: Uint8Array | null) => void) | null = null;
  private rejectRead: ((error: Error) => void) | null = null;
  private failure: Error | null = null;
  private ended: boolean = false;
  private cancelTimer: () => void = () => {};
  constructor(writer: CompanionByteWriter, timer: CompanionStreamTimer, lifetimeMs: number) {
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid stream lifetime');
    this.writer = writer;
    this.cancelTimer = timer.schedule(lifetimeMs, () => this.fail(new Error('sync stream deadline')));
  }
  close(): void { this.fail(new Error('sync stream closed')); }
  fail(error: Error): void {
    if (this.failure !== null) return;
    this.failure = error; this.cancelTimer(); this.decoder.close();
    this.queue = []; this.queuedBytes = 0;
    if (this.rejectRead !== null) this.rejectRead(error);
    this.resolveRead = null; this.rejectRead = null;
    for (const write of this.writes) write.reject(error);
    this.writes = []; this.writeBytes = 0;
    this.writer.close();
  }
  receive(bytes: Uint8Array): void {
    if (this.failure !== null || this.ended) return;
    try {
      this.decoder.feed(bytes, (packet: Uint8Array) => {
        if (this.resolveRead !== null) {
          const resolve = this.resolveRead; this.resolveRead = null; this.rejectRead = null; resolve(packet);
        } else {
          if (this.queue.length >= 32 || this.queuedBytes + packet.length > 4194304) throw new Error('sync receive queue overflow');
          this.queue.push(packet); this.queuedBytes += packet.length;
        }
      });
    } catch (error) { this.fail(error as Error); }
  }
  end(): void {
    if (this.failure !== null || this.ended) return;
    try { this.decoder.finish(); }
    catch (error) { this.fail(error as Error); return; }
    this.ended = true; this.cancelTimer();
    if (this.resolveRead !== null) this.resolveRead(null);
    this.resolveRead = null; this.rejectRead = null;
    const error = new Error('sync stream EOF');
    for (const write of this.writes) write.reject(error);
    this.writes = []; this.writeBytes = 0; this.writer.close();
  }
  async read(): Promise<Uint8Array | null> {
    if (this.failure !== null) throw this.failure;
    if (this.resolveRead !== null) throw new Error('sync reader already active');
    if (this.queue.length > 0) { const packet = this.queue.shift() as Uint8Array; this.queuedBytes -= packet.length; return packet; }
    if (this.ended) return null;
    return new Promise<Uint8Array | null>((resolve, reject) => { this.resolveRead = resolve; this.rejectRead = reject; });
  }
  async write(packet: Uint8Array): Promise<void> {
    if (this.failure !== null) throw this.failure;
    if (this.ended) throw new Error('sync stream EOF');
    const bytes = encodeSyncPacket(packet);
    if (this.writes.length >= 8 || this.writeBytes + bytes.length > 4194312) {
      const error = new Error('sync write queue overflow'); this.fail(error); throw error;
    }
    return new Promise<void>((resolve, reject) => {
      const write = new PendingWrite(bytes, resolve, reject); this.writes.push(write); this.writeBytes += bytes.length;
      if (this.writes.length === 1) this.flush(write);
    });
  }
  private async flush(write: PendingWrite): Promise<void> {
    try {
      await this.writer.send(write.bytes);
      if (this.failure !== null || this.ended) return;
      this.writes.shift(); this.writeBytes -= write.bytes.length; write.resolve();
      if (this.writes.length > 0) this.flush(this.writes[0]);
    } catch (error) { if (this.failure === null && !this.ended) this.fail(error as Error); }
  }
}
