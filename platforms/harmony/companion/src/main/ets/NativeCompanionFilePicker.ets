import fs from '@ohos.file.fs';
import picker from '@ohos.file.picker';
import { common } from '@kit.AbilityKit';
import { NativeCompanionClient } from './NativeCompanionClient';
import { CompanionFileSource } from './CompanionFileSender';
import { CompanionFileManifest } from './CompanionFileWire';

class PickedSource implements CompanionFileSource {
  private fd: number;
  private size: number;
  private busy: boolean = false;
  constructor(fd: number, size: number) { this.fd = fd; this.size = size; }
  async readChunk(index: number): Promise<Uint8Array> {
    if (this.busy) throw new Error('picked source already reading');
    if (!Number.isInteger(index) || index < 0 || index >= Math.ceil(this.size / 65536)) throw new Error('invalid picked chunk');
    this.busy = true;
    try {
      const stat = await fs.stat(this.fd);
      if (!stat.isFile() || stat.size !== this.size) throw new Error('picked file size changed');
      const start = index * 65536, length = Math.min(65536, this.size - start);
      if (fs.lseek(this.fd, start, fs.WhenceType.SEEK_SET) !== start) throw new Error('cannot seek picked file');
      const bytes = new Uint8Array(length); let offset = 0;
      while (offset < length) {
        const part = new ArrayBuffer(length - offset);
        const count = await fs.read(this.fd, part, { length: part.byteLength });
        if (!Number.isInteger(count) || count <= 0 || count > part.byteLength) throw new Error('picked file truncated');
        bytes.set(new Uint8Array(part, 0, count), offset); offset += count;
      }
      return bytes;
    } finally { this.busy = false; }
  }
}
/** Call from an explicit foreground user action. The selected URI is opened
 * read-only and never retained; successful import lives in app-private storage.
 * Picker cancellation returns null; permission/IO errors remain failures. */
export async function pickCompanionFile(context: common.UIAbilityContext, client: NativeCompanionClient,
  transferId: string, cancelled: () => boolean = () => false): Promise<CompanionFileManifest | null> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(transferId)) throw new Error('invalid import identity');
  if (cancelled()) throw new Error('file import cancelled');
  const selected = await new picker.DocumentViewPicker(context).select({ maxSelectNumber: 1 });
  if (cancelled()) throw new Error('file import cancelled');
  if (selected.length === 0) return null;
  if (selected.length !== 1 || !selected[0]) throw new Error('invalid picker result');
  const file = await fs.open(selected[0], fs.OpenMode.READ_ONLY);
  try {
    if (cancelled()) throw new Error('file import cancelled');
    const stat = await fs.stat(file.fd);
    if (!stat.isFile() || !Number.isSafeInteger(stat.size) || stat.size < 0 || stat.size > 16777216) throw new Error('unsupported picked file');
    return await client.importFile(transferId, stat.size, 'application/octet-stream', new PickedSource(file.fd, stat.size), cancelled);
  } finally { await fs.close(file); }
}
