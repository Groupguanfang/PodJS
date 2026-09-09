import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionMessageStore } from './CompanionMessageOutbox';
import { CompanionFileRequests, CompanionFileRequestCrypto } from './CompanionFileRequests';

export class NativeCompanionFileRequestsStore implements CompanionMessageStore {
  private filesDir: string;
  private app: string;
  constructor(context: common.UIAbilityContext, appId: string) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId)) throw new Error('invalid file app identity');
    this.filesDir = context.filesDir; this.app = appId;
  }
  read(): Promise<string | null> { return native.companionFileRequestsRead(this.filesDir, this.app); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionFileRequestsCompareExchange(this.filesDir, this.app, expected, desired);
  }
}
class NativeRequestCrypto implements CompanionFileRequestCrypto {
  async sha256(bytes: Uint8Array): Promise<Uint8Array> {
    const digest = cryptoFramework.createMd('SHA256'); await digest.update({ data: bytes }); return (await digest.digest()).data;
  }
  async messageId(): Promise<string> {
    const random = await cryptoFramework.createRandom().generateRandom(16);
    let id = ''; for (const byte of random.data) id += byte.toString(16).padStart(2, '0'); return id;
  }
}
export function createCompanionFileRequests(context: common.UIAbilityContext, appId: string, localId: string): CompanionFileRequests {
  return new CompanionFileRequests(appId, localId, new NativeCompanionFileRequestsStore(context, appId), new NativeRequestCrypto());
}
