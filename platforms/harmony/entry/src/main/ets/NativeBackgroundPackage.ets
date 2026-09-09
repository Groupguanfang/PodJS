import { common, bundleManager } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { BackgroundAssets, BackgroundPackage } from './BackgroundPackage';

class InstalledAssets implements BackgroundAssets {
  private context: common.Context;
  constructor(context: common.Context) { this.context = context; }
  read(path: string): Promise<Uint8Array> { return this.context.resourceManager.getRawFileContent(path); }
  decode(bytes: Uint8Array): string {
    return util.TextDecoder.create('utf-8', { fatal: true, ignoreBOM: true }).decodeWithStream(bytes, { stream: false });
  }
  async sha256(bytes: Uint8Array): Promise<string> {
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: bytes });
    const result = await digest.digest();
    let hex = '';
    for (const byte of result.data) hex += byte.toString(16).padStart(2, '0');
    return hex;
  }
}
/** Reread the current OS-installed package on each cold execution. Never use
 * a task's persisted source/grants as authority after an application upgrade. */
export async function installedBackgroundPackage(context: common.Context): Promise<BackgroundPackage> {
  const bundle = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
  return BackgroundPackage.installed(bundle.name, new InstalledAssets(context));
}
