import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionIncomingFiles } from './CompanionIncomingFiles';
import { CompanionIncomingNativePort } from './CompanionIncomingNativePort';
import { CompanionMessageDigest } from './CompanionMessageOutbox';

class IncomingDigest implements CompanionMessageDigest {
  async sha256(bytes: Uint8Array): Promise<Uint8Array> {
    const digest = cryptoFramework.createMd('SHA256'); await digest.update({ data: bytes }); return (await digest.digest()).data;
  }
}
/** Offers persist without allocation; only acceptLocal permits receiving bytes. */
export function createCompanionIncomingFiles(context: common.UIAbilityContext, appId: string, localId: string): CompanionIncomingFiles {
  return new CompanionIncomingFiles(appId, localId, new CompanionIncomingNativePort(native, context.filesDir, appId), new IncomingDigest());
}
