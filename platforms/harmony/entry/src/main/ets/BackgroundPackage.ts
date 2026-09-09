export interface BackgroundAssets {
  read(path: string): Promise<Uint8Array>;
  decode(bytes: Uint8Array): string; // Strict UTF-8; malformed input must throw.
  sha256(bytes: Uint8Array): Promise<string>;
}
class Artifact { file: string = ''; sha256: string = ''; bytes: number = 0; }
class Manifest {
  schema: number = 0;
  target: string = '';
  background?: Record<string, Artifact>;
  backgroundServices?: string[];
}
export class ApprovedBackgroundSource {
  appId: string = '';
  handlerId: string = '';
  source: string = '';
  sha256: string = '';
  grants: string[] = [];
}
function validId(value: string): boolean {
  return typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value);
}
/** Host-only installed-package boundary. OS installation authenticates assets;
 * hashes check integrity, not publisher trust. Never construct from guest JSON. */
export class BackgroundPackage {
  private handlers: Map<string, Artifact> = new Map();
  private grants: string[] = [];
  private constructor(private appId: string, private assets: BackgroundAssets) {}
  static async installed(appId: string, assets: BackgroundAssets): Promise<BackgroundPackage> {
    if (!validId(appId)) throw new Error('Invalid installed application identity');
    const bytes = await assets.read('pod.manifest.json');
    if (bytes.byteLength < 1 || bytes.byteLength > 128 * 1024) throw new Error('Invalid manifest size');
    const manifest = JSON.parse(assets.decode(bytes)) as Manifest;
    if (!manifest || manifest.schema !== 1 || manifest.target !== 'harmonyos-watch')
      throw new Error('Background manifest target or schema mismatch');
    const result = new BackgroundPackage(appId, assets);
    if (manifest.backgroundServices !== undefined) {
      if (!Array.isArray(manifest.backgroundServices) || manifest.backgroundServices.length > 4)
        throw new Error('Invalid background grants');
      for (const method of manifest.backgroundServices) {
        if (!['kv.get', 'kv.set', 'kv.delete', 'kv.keys'].includes(method) || result.grants.includes(method))
          throw new Error('Invalid background grant');
        result.grants.push(method);
      }
    }
    if (manifest.background !== undefined) {
      if (!manifest.background || typeof manifest.background !== 'object' || Array.isArray(manifest.background))
        throw new Error('Invalid background handlers');
      const names = Object.keys(manifest.background);
      if (names.length > 32) throw new Error('Too many background handlers');
      for (const name of names) {
        const item = manifest.background[name];
        if (!validId(name) || !item || typeof item.file !== 'string' ||
            !/^background\/[0-9a-f]{64}\.js$/.test(item.file) || typeof item.sha256 !== 'string' ||
            !/^[0-9a-f]{64}$/.test(item.sha256) || !Number.isInteger(item.bytes) || item.bytes < 1 || item.bytes > 1024 * 1024)
          throw new Error('Invalid background artifact');
        result.handlers.set(name, item);
      }
    }
    return result;
  }
  async resolve(handler: string): Promise<ApprovedBackgroundSource> {
    if (!validId(handler)) throw new Error('Invalid background handler');
    const artifact = this.handlers.get(handler);
    if (!artifact) throw new Error('Undeclared background handler');
    const bytes = await this.assets.read(artifact.file);
    if (bytes.byteLength !== artifact.bytes || await this.assets.sha256(bytes) !== artifact.sha256)
      throw new Error('Background artifact integrity mismatch');
    const result = new ApprovedBackgroundSource();
    result.appId = this.appId; result.handlerId = handler;
    result.source = this.assets.decode(bytes); result.sha256 = artifact.sha256;
    result.grants = this.grants.slice(); return result;
  }
  permits(hash: string, grants: string[]): boolean {
    if (grants.some(method => !this.grants.includes(method))) return false;
    for (const item of this.handlers.values()) if (item.sha256 === hash) return true;
    return false;
  }
}
