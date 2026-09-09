import { CompanionByteWriter } from './CompanionPacketStream';

export interface CompanionChunkPort {
  sendChunk(bytes: Uint8Array): void;
  /** Must yield to the event loop so close/deadline events can run. */
  yieldTurn(): Promise<void>;
  close(): void;
}
/** Adapter for linkEnhance's 1024-byte sendData limit. The enclosing packet
 * stream serializes whole frames; yielding does not permit a second sender. */
export class CompanionChunkWriter implements CompanionByteWriter {
  private port: CompanionChunkPort;
  private closed: boolean = false;
  private sending: boolean = false;
  constructor(port: CompanionChunkPort) { this.port = port; }
  close(): void { if (this.closed) return; this.closed = true; this.port.close(); }
  async send(bytes: Uint8Array): Promise<void> {
    if (this.closed) throw new Error('chunk writer closed');
    if (this.sending) throw new Error('concurrent chunk send');
    if (bytes.length < 1 || bytes.length > 2097156) throw new Error('invalid chunked frame size');
    const copy = bytes.slice(); this.sending = true;
    try {
      for (let offset = 0; offset < copy.length; offset += 1024) {
        if (this.closed) throw new Error('chunk writer closed');
        this.port.sendChunk(copy.slice(offset, offset + 1024));
        // Limit each event-loop turn to 8 KiB of submissions, including for
        // synchronous OS APIs with no per-send Promise or backpressure callback.
        if ((offset / 1024 + 1) % 8 === 0 || offset + 1024 >= copy.length) {
          await this.port.yieldTurn();
          if (this.closed) throw new Error('chunk writer closed');
        }
      }
    } catch (error) { try { this.close(); } catch (_) {} throw error; }
    finally { this.sending = false; }
  }
}
