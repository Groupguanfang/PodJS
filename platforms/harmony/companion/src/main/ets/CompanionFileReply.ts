import { decodeFileObject, CompanionFileRequest } from './CompanionFileWire';
import { encodeSyncUtf8 } from './CompanionStatePump';

export class CompanionFileValue {
  phase: string = '';
  missing?: number[];
}
export class CompanionFileReply {
  version: number = 1;
  type: string = 'reply';
  request_sha256: string = '';
  value: CompanionFileValue = new CompanionFileValue();
}
function exact(value: Object, keys: string[]): void {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length ||
    !Object.keys(value).every((key: string) => keys.includes(key))) throw new Error('invalid file reply fields');
}
/** Peer and request ID must be checked against durable state by the caller.
 * expectedDigest is SHA-256 of the exact original bytes, not reserialized JSON. */
export function decodeFileReply(bytes: Uint8Array, request: CompanionFileRequest, expectedDigest: string): CompanionFileReply {
  if (bytes.length > 4096 || !/^[0-9a-f]{64}$/.test(expectedDigest)) throw new Error('invalid file reply bounds');
  const reply = decodeFileObject(bytes) as CompanionFileReply;
  exact(reply, ['version', 'type', 'request_sha256', 'value']);
  if (reply.version !== 1 || reply.type !== 'reply' || reply.request_sha256 !== expectedDigest) throw new Error('file reply request mismatch');
  const missing = request.method === 'missing'; exact(reply.value, missing ? ['phase', 'missing'] : ['phase']);
  const phase = reply.value.phase;
  if (!['offered', 'accepting', 'accepted', 'complete', 'cancelling', 'cancelled'].includes(phase)) throw new Error('invalid file reply phase');
  if (!['offer', 'status', 'missing', 'chunk', 'finish', 'cancel'].includes(request.method)) throw new Error('invalid file reply method');
  if ((request.method === 'finish' && phase !== 'complete' && phase !== 'cancelled') ||
    (request.method === 'cancel' && phase !== 'cancelled') ||
    ((request.method === 'chunk' || missing) && !['accepted', 'complete', 'cancelled'].includes(phase))) throw new Error('file reply phase incompatible with method');
  if (missing) {
    const indexes = reply.value.missing as number[];
    if (!Array.isArray(indexes) || indexes.length > 256) throw new Error('invalid file missing indexes');
    let prior = -1;
    for (const index of indexes) {
      if (!Number.isInteger(index) || index <= prior || index > 255) throw new Error('invalid file missing order'); prior = index;
    }
    if ((phase === 'complete' || phase === 'cancelled') && indexes.length > 0) throw new Error('terminal file has missing chunks');
  }
  return reply;
}
/** Header validation for replies to forgotten IDs, where the original method is
 * unavailable. Does not grant permission to advance another request. */
export function decodeFileReplyHeader(bytes: Uint8Array): CompanionFileReply {
  if (bytes.length > 4096) throw new Error('file reply too large');
  const reply = decodeFileObject(bytes) as CompanionFileReply;
  exact(reply, ['version', 'type', 'request_sha256', 'value']);
  if (reply.version !== 1 || reply.type !== 'reply' || typeof reply.request_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(reply.request_sha256) ||
    reply.value === null || typeof reply.value !== 'object' || !['offered', 'accepting', 'accepted', 'complete', 'cancelling', 'cancelled'].includes(reply.value.phase)) throw new Error('invalid file reply header');
  return reply;
}
export function encodeFileReply(reply: CompanionFileReply, request: CompanionFileRequest): Uint8Array {
  const bytes = new Uint8Array(encodeSyncUtf8(JSON.stringify(reply), 4096));
  decodeFileReply(bytes, request, reply.request_sha256); return bytes;
}
