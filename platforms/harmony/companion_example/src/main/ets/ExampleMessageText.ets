import { encodeStateUtf8, decodeStateUtf8 } from '@podjs/companion';

const prefix: string = 'podjs-text-v1\n';
function validate(text: string): void {
  if (typeof text !== 'string' || text.length < 1 || text.length > 4096 ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(text))
    throw new Error('unsupported example text');
}
export function encodeExampleMessage(text: string): Uint8Array {
  validate(text); return new Uint8Array(encodeStateUtf8(prefix + text));
}
export function decodeExampleMessage(bytes: Uint8Array): string | null {
  if (bytes.length > 16400) return null;
  try {
    const wire = decodeStateUtf8(Array.from(bytes));
    if (!wire.startsWith(prefix)) return null;
    const text = wire.slice(prefix.length); validate(text); return text;
  } catch (_) { return null; }
}
