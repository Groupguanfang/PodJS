/** Android-compatible message payload. Application bytes are deliberately opaque;
 * app/peer/message identity and authentication belong to the enclosing session. */
export class CompanionMessageEnvelope {
  expiresAt: number;
  highPriority: boolean;
  payload: Uint8Array;
  constructor(expiresAt: number, highPriority: boolean, payload: Uint8Array) {
    if (!Number.isSafeInteger(expiresAt) || expiresAt < 0) throw new Error('invalid message expiry');
    if (typeof highPriority !== 'boolean') throw new Error('invalid message priority');
    if (payload.length > 262144) throw new Error('message payload too large');
    this.expiresAt = expiresAt; this.highPriority = highPriority; this.payload = payload.slice();
  }
}
export function encodeMessageEnvelope(message: CompanionMessageEnvelope): Uint8Array {
  // Validate again because public fields can change after construction.
  const valid = new CompanionMessageEnvelope(message.expiresAt, message.highPriority, message.payload);
  const bytes = new Uint8Array(9 + valid.payload.length), view = new DataView(bytes.buffer);
  view.setUint32(0, Math.floor(valid.expiresAt / 0x100000000), false);
  view.setUint32(4, valid.expiresAt % 0x100000000, false);
  bytes[8] = valid.highPriority ? 1 : 0; bytes.set(valid.payload, 9);
  return bytes;
}
export function decodeMessageEnvelope(bytes: Uint8Array): CompanionMessageEnvelope {
  if (bytes.length < 9 || bytes.length > 262153) throw new Error('invalid message envelope size');
  if (bytes[8] > 1) throw new Error('invalid message priority');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const expiry = view.getUint32(0, false) * 0x100000000 + view.getUint32(4, false);
  return new CompanionMessageEnvelope(expiry, bytes[8] === 1, bytes.subarray(9));
}
export class CompanionMessageAck {
  expired: boolean;
  digest: Uint8Array;
  constructor(expired: boolean, digest: Uint8Array) {
    if (typeof expired !== 'boolean' || digest.length !== 32) throw new Error('invalid message ACK');
    this.expired = expired; this.digest = digest.slice();
  }
}
export function encodeMessageAck(ack: CompanionMessageAck): Uint8Array {
  const valid = new CompanionMessageAck(ack.expired, ack.digest), bytes = new Uint8Array(33);
  bytes[0] = valid.expired ? 2 : 1; bytes.set(valid.digest, 1); return bytes;
}
export function decodeMessageAck(bytes: Uint8Array): CompanionMessageAck {
  if (bytes.length !== 33 || (bytes[0] !== 1 && bytes[0] !== 2)) throw new Error('invalid message ACK');
  return new CompanionMessageAck(bytes[0] === 2, bytes.subarray(1));
}
