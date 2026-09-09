/** State ACK kind 3; pass only payload bytes from an authenticated ACK frame.
 * Peer and messageId binding remain in the authenticated outer envelope.
 */
export class CompanionStateAck {
  cursor: number = 0;
  digest: string = '';
}
export function encodeStateAck(cursor: number, digest: string): Uint8Array {
  if (!Number.isSafeInteger(cursor) || cursor < 0 || !/^[0-9a-f]{64}$/.test(digest)) throw new Error('Invalid state ACK');
  const output = new Uint8Array(41), view = new DataView(output.buffer);
  output[0] = 3;
  view.setUint32(1, Math.floor(cursor / 0x100000000), false);
  view.setUint32(5, cursor % 0x100000000, false);
  for (let i = 0; i < 32; i++) output[9 + i] = parseInt(digest.slice(i * 2, i * 2 + 2), 16);
  return output;
}
export function decodeStateAck(payload: Uint8Array): CompanionStateAck {
  if (payload.byteLength !== 41 || payload[0] !== 3) throw new Error('Invalid state ACK');
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength), result = new CompanionStateAck();
  result.cursor = view.getUint32(1, false) * 0x100000000 + view.getUint32(5, false);
  if (!Number.isSafeInteger(result.cursor)) throw new Error('Unsafe state ACK cursor');
  for (let i = 9; i < 41; i++) result.digest += payload[i].toString(16).padStart(2, '0');
  return result;
}
