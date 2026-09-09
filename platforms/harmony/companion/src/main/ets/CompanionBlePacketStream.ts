import { CompanionByteWriter, CompanionPacketStream, CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionSyncPacketStream } from './CompanionSyncExchange';

export interface CompanionBleAttributePort {
  /** Resolves only after the acknowledged GATT write/indication completes. */
  write(value: Uint8Array): Promise<void>;
  close(): void;
}
class BleWriter implements CompanionByteWriter {
  private sequence: number = 0;
  private closed: boolean = false;
  private sending: boolean = false;
  constructor(private port: CompanionBleAttributePort, private maximumValue: number) {}
  close(): void { if (this.closed) return; this.closed = true; this.port.close(); }
  async send(bytes: Uint8Array): Promise<void> {
    if (this.closed || this.sending) throw new Error('BLE writer unavailable');
    this.sending = true;
    try {
      const copy = bytes.slice(), payload = this.maximumValue - 6;
      for (let offset = 0; offset < copy.length; offset += payload) {
        if (this.closed || this.sequence > 0xffffffff) throw new Error('BLE send closed or sequence exhausted');
        const count = Math.min(payload, copy.length - offset), packet = new Uint8Array(6 + count);
        packet[0] = 0x50; packet[1] = 1;
        new DataView(packet.buffer).setUint32(2, this.sequence, false);
        packet.set(copy.subarray(offset, offset + count), 6);
        await this.port.write(packet);
        if (this.closed) throw new Error('BLE send closed');
        this.sequence++;
      }
    } catch (error) { try { this.close(); } catch (_) {} throw error; }
    finally { this.sending = false; }
  }
}

/** Wire-compatible with Android PodBleStream: P/1 + big-endian u32 sequence
 * per characteristic value, carrying the existing length-prefixed byte stream.
 * Only the exact immediately previous packet may repeat. Gaps, altered replays,
 * invalid MTU-sized values and framed queue overflow terminate the stream.
 * This is framing, not encryption or application authentication. */
export class CompanionBlePacketStream implements CompanionSyncPacketStream {
  private stream: CompanionPacketStream;
  private expected: number = 0;
  private previous: Uint8Array | null = null;
  private closed: boolean = false;
  readonly maximumValueBytes: number;
  constructor(mtu: number, port: CompanionBleAttributePort, timer: CompanionStreamTimer, lifetimeMs: number) {
    if (!Number.isInteger(mtu) || mtu < 23 || mtu > 517) throw new Error('invalid BLE MTU');
    this.maximumValueBytes = Math.min(mtu - 3, 512);
    this.stream = new CompanionPacketStream(new BleWriter(port, this.maximumValueBytes), timer, lifetimeMs);
  }
  read(): Promise<Uint8Array | null> { return this.stream.read(); }
  write(bytes: Uint8Array): Promise<void> { return this.stream.write(bytes); }
  close(): void { this.fail(new Error('BLE stream closed')); }
  fail(error: Error): void {
    if (this.closed) return; this.closed = true; this.previous = null; this.stream.fail(error);
  }
  receive(value: Uint8Array): void {
    if (this.closed) return;
    try {
      if (value.length <= 6 || value.length > this.maximumValueBytes || value[0] !== 0x50 || value[1] !== 1)
        throw new Error('invalid BLE packet');
      const sequence = new DataView(value.buffer, value.byteOffset, value.byteLength).getUint32(2, false);
      if (this.expected > 0 && sequence === this.expected - 1 && this.previous !== null &&
        value.length === this.previous.length && value.every((byte, i) => byte === this.previous![i])) return;
      if (sequence !== this.expected || this.expected > 0xffffffff) throw new Error('BLE packet gap or replay');
      this.previous = value.slice(); this.expected++;
      this.stream.receive(value.slice(6));
    } catch (error) { this.fail(error as Error); }
  }
}
