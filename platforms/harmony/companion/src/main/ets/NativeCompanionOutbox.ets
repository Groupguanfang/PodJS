import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionMessageOutbox, CompanionMessageStore, CompanionMessageDigest } from './CompanionMessageOutbox';
import { CompanionMessageInbox } from './CompanionMessageInbox';

export class NativeCompanionOutboxStore implements CompanionMessageStore {
  private filesDir: string;
  private app: string;
  constructor(context: common.UIAbilityContext, appId: string) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId)) throw new Error('invalid message app identity');
    this.filesDir = context.filesDir; this.app = appId;
  }
  read(): Promise<string | null> { return native.companionOutboxRead(this.filesDir, this.app); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionOutboxCompareExchange(this.filesDir, this.app, expected, desired);
  }
}
class NativeMessageDigest implements CompanionMessageDigest {
  async sha256(bytes: Uint8Array): Promise<Uint8Array> {
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: bytes }); return (await digest.digest()).data;
  }
}
export class NativeCompanionInboxStore implements CompanionMessageStore {
  private filesDir: string;
  private app: string;
  constructor(context: common.UIAbilityContext, appId: string) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId)) throw new Error('invalid message app identity');
    this.filesDir = context.filesDir; this.app = appId;
  }
  read(): Promise<string | null> { return native.companionInboxRead(this.filesDir, this.app); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionInboxCompareExchange(this.filesDir, this.app, expected, desired);
  }
}
export function createCompanionMessageInbox(context: common.UIAbilityContext, appId: string, localId: string): CompanionMessageInbox {
  return new CompanionMessageInbox(appId, localId, new NativeCompanionInboxStore(context, appId), new NativeMessageDigest());
}
/** Construction is lazy; durable writes use a dedicated app-private journal. */
export function createCompanionMessageOutbox(context: common.UIAbilityContext, appId: string, localId: string): CompanionMessageOutbox {
  return new CompanionMessageOutbox(appId, localId, new NativeCompanionOutboxStore(context, appId), new NativeMessageDigest());
}
