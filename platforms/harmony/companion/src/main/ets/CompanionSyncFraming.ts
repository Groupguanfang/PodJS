/** Android PodSyncStream wire format: unsigned big-endian u32 length followed
 * by 1..2 MiB opaque bytes. This layer provides no authentication/encryption. */
export const MAX_SYNC_FRAME_BYTES: number = 2 * 1024 * 1024;

export function encodeSyncPacket(payload: Uint8Array): Uint8Array {
  if (payload.length < 1 || payload.length > MAX_SYNC_FRAME_BYTES) throw new Error('invalid sync frame size');
  const packet = new Uint8Array(payload.length + 4);
  new DataView(packet.buffer).setUint32(0, payload.length, false);
  packet.set(payload, 4); return packet;
}

/** Incremental decoder with one bounded in-progress allocation. The synchronous
 * callback owns completed payloads. Hosts must apply backpressure outside this
 * codec, not queue unlimited async work in the callback. Callback errors close
 * the decoder. Reentrant feed/finish calls are rejected to preserve wire order. */
export class CompanionSyncFraming {
  private header: Uint8Array = new Uint8Array(4);
  private headerUsed: number = 0;
  private payload: Uint8Array | null = null;
  private payloadUsed: number = 0;
  private closed: boolean = false;
  private feeding: boolean = false;
  close(): void {
    this.closed = true; this.payload = null; this.payloadUsed = 0; this.headerUsed = 0;
  }
  feed(chunk: Uint8Array, accept: (payload: Uint8Array) => void): void {
    if (this.closed) throw new Error('sync framing closed');
    if (this.feeding) { this.close(); throw new Error('reentrant sync framing'); }
    this.feeding = true;
    try {
      let offset = 0;
      while (offset < chunk.length) {
        if (this.payload === null) {
          const take = Math.min(4 - this.headerUsed, chunk.length - offset);
          this.header.set(chunk.subarray(offset, offset + take), this.headerUsed);
          this.headerUsed += take; offset += take;
          if (this.headerUsed < 4) continue;
          const length = new DataView(this.header.buffer).getUint32(0, false);
          if (length < 1 || length > MAX_SYNC_FRAME_BYTES) throw new Error('invalid sync frame length');
          this.payload = new Uint8Array(length); this.payloadUsed = 0;
        }
        const payload = this.payload;
        const take = Math.min(payload.length - this.payloadUsed, chunk.length - offset);
        payload.set(chunk.subarray(offset, offset + take), this.payloadUsed);
        this.payloadUsed += take; offset += take;
        if (this.payloadUsed === payload.length) {
          this.payload = null; this.headerUsed = 0; this.payloadUsed = 0;
          accept(payload);
          if (this.closed) throw new Error('sync framing closed');
        }
      }
    } catch (error) { this.close(); throw error; }
    finally { this.feeding = false; }
  }
  /** Clean EOF only at a packet boundary; either EOF closes the decoder. */
  finish(): void {
    if (this.closed) throw new Error('sync framing closed');
    const truncated = this.headerUsed !== 0 || this.payload !== null;
    const reentrant = this.feeding;
    this.close();
    if (reentrant) throw new Error('reentrant sync framing');
    if (truncated) throw new Error('truncated sync frame');
  }
}
