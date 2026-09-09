import * as native from 'libpodjs_companion.so';
import { common } from '@kit.AbilityKit';
import { CompanionPairings, CompanionPairingLease } from './CompanionPairings';
import { NativeCompanionHuks } from './NativeCompanionHuks';
import { NativeCompanionSyncCrypto } from './NativeCompanionSync';

class NativePairingLease implements CompanionPairingLease {
  private handle: Object;
  constructor(handle: Object) { this.handle = handle; }
  assertOwned(): void { native.companionPairingLeaseAssert(this.handle); }
  read(): Promise<string | null> { return native.companionPairingLeaseRead(this.handle); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return native.companionPairingLeaseCompareExchange(this.handle, expected, desired);
  }
  close(): void { native.companionPairingLeaseClose(this.handle); }
}

/** One owner per app across processes. Always await owner.close() when done.
 * Recovery removes interrupted imports/revocations before exposing signers. */
export async function openCompanionPairings(context: common.UIAbilityContext, appId: string, localId: string): Promise<CompanionPairings> {
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId) || !/^[A-Za-z0-9_.:-]{1,128}$/.test(localId)) throw new Error('invalid pairing identity');
  const lease = new NativePairingLease(await native.companionPairingLeaseOpen(context.filesDir, appId));
  let owner: CompanionPairings | null = null;
  try {
    owner = new CompanionPairings(appId, localId, lease, new NativeCompanionHuks(), new NativeCompanionSyncCrypto());
    await owner.recover(); return owner;
  } catch (error) {
    if (owner !== null) await owner.close(); else lease.close();
    throw error as Error;
  }
}
