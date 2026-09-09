import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionState, CompanionStatePort, CompanionStateCrypto } from './CompanionState';

class NativeStateCrypto implements CompanionStateCrypto {
  async sha256(text: string): Promise<string> {
    const md = cryptoFramework.createMd('SHA256');
    await md.update({ data: new util.TextEncoder().encodeInto(text) });
    const result = await md.digest();
    let hex = ''; for (const byte of result.data) hex += byte.toString(16).padStart(2, '0');
    return hex;
  }
  async messageId(): Promise<string> {
    const result = await cryptoFramework.createRandom().generateRandom(16);
    let hex = ''; for (const byte of result.data) hex += byte.toString(16).padStart(2, '0');
    return hex;
  }
}

export class NativeCompanionStateCas implements CompanionStatePort {
  private filesDir: string;
  constructor(context: common.UIAbilityContext) { this.filesDir = context.filesDir; }
  read(appId: string): Promise<string | null> { return native.companionStateRead(this.filesDir, appId); }
  compareExchange(appId: string, expected: string | null, desired: string): Promise<boolean> {
    return native.companionStateCompareExchange(this.filesDir, appId, expected, desired);
  }
}

/** Lazy construction: no IO, permissions, pairing or scanning. */
export function createCompanionState(context: common.UIAbilityContext, appId: string, deviceId: string): CompanionState {
  return new CompanionState(appId, deviceId, new NativeCompanionStateCas(context), new NativeStateCrypto());
}
