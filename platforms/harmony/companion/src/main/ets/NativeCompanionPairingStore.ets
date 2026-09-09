import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { CompanionMessageStore } from './CompanionMessageOutbox';

/** Private local pairing metadata only; never synchronized as application state.
 * Store HUKS key IDs, identities and lifecycle states, never raw key bytes.
 * CAS commits are durable, but do not lock a multi-step HUKS operation. */
export class NativeCompanionPairingStore implements CompanionMessageStore {
  private filesDir: string;
  private app: string;
  constructor(context: common.UIAbilityContext, appId: string) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId)) throw new Error('invalid pairing app identity');
    this.filesDir = context.filesDir; this.app = appId;
  }
  read(): Promise<string | null> { return native.companionPairingsRead(this.filesDir, this.app); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionPairingsCompareExchange(this.filesDir, this.app, expected, desired);
  }
}
