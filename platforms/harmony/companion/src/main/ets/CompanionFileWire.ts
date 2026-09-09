import { decodeSyncObject } from './CompanionSyncExchange';
import { decodeSyncUtf8, encodeSyncUtf8 } from './CompanionStatePump';

export class CompanionFileManifest {
  transfer_id: string = '';
  size: number = 0;
  sha256: string = '';
  chunk_hashes: string[] = [];
  mime: string = '';
}
export class CompanionFileRequest {
  version: number = 1;
  method: string = '';
  manifest: CompanionFileManifest | null = null;
  transfer_id: string = '';
  index: number = 0;
  data: Uint8Array = new Uint8Array();
}
interface FileWireRequest {
  version: number;
  method: string;
  manifest?: CompanionFileManifest;
  transfer_id?: string;
  index?: number;
  data_base64?: string;
}
function exact(value: Object, fields: string[]): void {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== fields.length ||
    !Object.keys(value).every((key: string) => fields.includes(key))) throw new Error('invalid file fields');
}
function transfer(id: string): void {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) throw new Error('invalid file transfer identity');
}
export function validateFileManifest(manifest: CompanionFileManifest): void {
  exact(manifest, ['transfer_id', 'size', 'sha256', 'chunk_hashes', 'mime']); transfer(manifest.transfer_id);
  if (!Number.isSafeInteger(manifest.size) || manifest.size < 0 || manifest.size > 16777216) throw new Error('file quota exceeded');
  if (typeof manifest.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(manifest.sha256)) throw new Error('invalid file hash');
  if (!Array.isArray(manifest.chunk_hashes) || manifest.chunk_hashes.length !== Math.ceil(manifest.size / 65536) ||
    !manifest.chunk_hashes.every((hash: string) => typeof hash === 'string' && /^[0-9a-f]{64}$/.test(hash))) throw new Error('invalid file chunks');
  if (typeof manifest.mime !== 'string' || /[\u0000-\u001f\u007f-\u009f]/.test(manifest.mime)) throw new Error('invalid file MIME');
  encodeSyncUtf8(manifest.mime, 128);
}
const alphabet: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function encodeFileChunk(bytes: Uint8Array): string {
  if (bytes.length > 65536) throw new Error('file chunk too large');
  let text = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i], b = i + 1 < bytes.length ? bytes[i + 1] : 0, c = i + 2 < bytes.length ? bytes[i + 2] : 0;
    text += alphabet[a >> 2] + alphabet[((a & 3) << 4) | (b >> 4)] +
      (i + 1 < bytes.length ? alphabet[((b & 15) << 2) | (c >> 6)] : '=') + (i + 2 < bytes.length ? alphabet[c & 63] : '=');
  }
  return text;
}
export function decodeFileChunk(text: string): Uint8Array {
  if (typeof text !== 'string' || text.length > 87384 || text.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text)) throw new Error('invalid file base64');
  const padding = text.endsWith('==') ? 2 : text.endsWith('=') ? 1 : 0;
  const size = text.length / 4 * 3 - padding;
  if (size < 0 || size > 65536) throw new Error('file chunk too large');
  const bytes = new Uint8Array(size); let offset = 0;
  for (let i = 0; i < text.length; i += 4) {
    const bits = (alphabet.indexOf(text[i]) << 18) | (alphabet.indexOf(text[i + 1]) << 12) |
      ((text[i + 2] === '=' ? 0 : alphabet.indexOf(text[i + 2])) << 6) | (text[i + 3] === '=' ? 0 : alphabet.indexOf(text[i + 3]));
    for (let shift = 16; shift >= 0 && offset < size; shift -= 8) bytes[offset++] = (bits >> shift) & 255;
  }
  if (encodeFileChunk(bytes) !== text) throw new Error('noncanonical file base64'); return bytes;
}
/** Strict JSON at every nesting level, including duplicate manifest keys. */
export function decodeFileObject(bytes: Uint8Array): Object {
  const object = decodeSyncObject(bytes, 98304), text = decodeSyncUtf8(Array.from(bytes), 98304), stack: string[][] = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') {
      const start = i++;
      for (; i < text.length; i++) { if (text[i] === '\\') { i++; continue; } if (text[i] === '"') break; }
      let next = i + 1; while (next < text.length && /\s/.test(text[next])) next++;
      if (text[next] === ':') {
        const key = JSON.parse(text.slice(start, i + 1)) as string, keys = stack[stack.length - 1];
        if (keys.includes(key)) throw new Error('duplicate file field'); keys.push(key);
      }
    } else if (text[i] === '{' || text[i] === '[') { if (stack.length >= 8) throw new Error('file nesting limit'); stack.push([]); }
    else if (text[i] === '}' || text[i] === ']') stack.pop();
  }
  return object;
}
export function decodeFileRequest(bytes: Uint8Array): CompanionFileRequest {
  const wire = decodeFileObject(bytes) as FileWireRequest, result = new CompanionFileRequest();
  if (wire.version !== 1 || typeof wire.method !== 'string') throw new Error('invalid file request'); result.method = wire.method;
  if (wire.method === 'offer') {
    exact(wire, ['version', 'method', 'manifest']);
    const manifest = wire.manifest as CompanionFileManifest; validateFileManifest(manifest); result.manifest = manifest;
  } else {
    result.transfer_id = wire.transfer_id as string; transfer(result.transfer_id);
    if (wire.method === 'chunk') {
      exact(wire, ['version', 'method', 'transfer_id', 'index', 'data_base64']); result.index = wire.index as number;
      if (!Number.isInteger(result.index) || result.index < 0 || result.index >= 256) throw new Error('invalid file chunk index');
      result.data = decodeFileChunk(wire.data_base64 as string);
    } else {
      exact(wire, ['version', 'method', 'transfer_id']);
      if (!['status', 'missing', 'finish', 'cancel'].includes(wire.method)) throw new Error('unsupported file method');
    }
  }
  return result;
}
export function encodeFileRequest(request: CompanionFileRequest): Uint8Array {
  let wire: FileWireRequest;
  if (request.method === 'offer') wire = { version: request.version, method: request.method, manifest: request.manifest as CompanionFileManifest };
  else if (request.method === 'chunk') wire = { version: request.version, method: request.method, transfer_id: request.transfer_id, index: request.index, data_base64: encodeFileChunk(request.data) };
  else wire = { version: request.version, method: request.method, transfer_id: request.transfer_id };
  const bytes = new Uint8Array(encodeSyncUtf8(JSON.stringify(wire), 98304)); decodeFileRequest(bytes); return bytes;
}
