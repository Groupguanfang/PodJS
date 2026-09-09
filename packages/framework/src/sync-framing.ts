/** Binary framing shared by RFCOMM, ordered BLE GATT writes and LAN streams.
 * This layer carries opaque authenticated protocol bytes; it is not authentication.
 * One decoder per connection/direction. Discard it on disconnect or invalid input.
 */
export const MAX_SYNC_FRAME_BYTES = 2 * 1024 * 1024;
export function encodeSyncFrame(payload: Uint8Array): Uint8Array {
  if (payload.byteLength < 1 || payload.byteLength > MAX_SYNC_FRAME_BYTES) throw new Error("Invalid sync frame length");
  const output = new Uint8Array(payload.byteLength + 4);
  new DataView(output.buffer).setUint32(0, payload.byteLength, false);
  output.set(payload, 4);
  return output;
}
/** BLE callers pass the negotiated ATT value budget (MTU minus ATT overhead).
 * Writes must be serialized with transport acknowledgement/backpressure.
 */
export function* fragmentSyncFrame(frame: Uint8Array, maxValueBytes: number): Generator<Uint8Array> {
  if (!Number.isSafeInteger(maxValueBytes) || maxValueBytes < 1 || maxValueBytes > MAX_SYNC_FRAME_BYTES + 4) throw new Error("Invalid transport fragment budget");
  for (let offset = 0; offset < frame.length; offset += maxValueBytes) yield frame.slice(offset, offset + maxValueBytes);
}
export class SyncFrameDecoder {
  private header = new Uint8Array(4);
  private headerUsed = 0;
  private payload?: Uint8Array;
  private used = 0;
  private failed = false;
  private finished = false;
  private decoding = false;
  /** Delivers completed frames synchronously; callers must bound their receive queue.
   * Callback errors poison this connection, since delivery may have partially applied.
   */
  push(input: Uint8Array, deliver: (frame: Uint8Array) => void): void {
    if (this.failed || this.finished) throw new Error("Sync decoder requires a new connection");
    if (this.decoding) {
      this.failed = true;
      throw new Error("Reentrant sync decoding requires a new connection");
    }
    this.decoding = true;
    try {
      let offset = 0;
      while (offset < input.length) {
        if (!this.payload) {
          const count = Math.min(4 - this.headerUsed, input.length - offset);
          this.header.set(input.subarray(offset, offset + count), this.headerUsed);
          this.headerUsed += count; offset += count;
          if (this.headerUsed < 4) continue;
          const size = new DataView(this.header.buffer).getUint32(0, false);
          if (size < 1 || size > MAX_SYNC_FRAME_BYTES) throw new Error("Invalid sync frame length");
          this.payload = new Uint8Array(size); this.used = 0;
        }
        const count = Math.min(this.payload.length - this.used, input.length - offset);
        this.payload.set(input.subarray(offset, offset + count), this.used);
        this.used += count; offset += count;
        if (this.used === this.payload.length) {
          const complete = this.payload;
          this.payload = undefined; this.used = 0; this.headerUsed = 0;
          deliver(complete);
          // A callback must not hide a rejected recursive push/finish and let
          // the outer invocation continue delivering bytes from this connection.
          if (this.failed) throw new Error("Sync decoder requires a new connection");
        }
      }
    } catch (error) {
      this.failed = true; this.payload = undefined;
      throw error;
    } finally {
      this.decoding = false;
    }
  }
  /** Ends this connection permanently. Repeated clean EOF is harmless. */
  finish(): void {
    if (this.decoding) {
      this.failed = true;
      throw new Error("Cannot finish sync stream during delivery");
    }
    if (this.failed || this.headerUsed !== 0 || this.payload) {
      this.failed = true; this.payload = undefined;
      throw new Error("Truncated or invalid sync stream");
    }
    this.finished = true;
  }
}
