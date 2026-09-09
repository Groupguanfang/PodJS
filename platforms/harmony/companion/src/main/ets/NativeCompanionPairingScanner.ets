import { common } from '@kit.AbilityKit';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { CompanionPairingInvitation } from './CompanionPairingInvitation';

let scanning: boolean = false;

/** Invoke from a foreground page after a user action. Default system scan UI
 * owns camera access; do not request extra camera/gallery permissions here.
 * No connection or permanent key import occurs. System UI has no cancel API;
 * cancelled() suppresses a late result but does not claim to dismiss that UI. */
export async function scanCompanionPairingInvitation(context: common.UIAbilityContext, appId: string,
  localId: string, cancelled: () => boolean = () => false): Promise<CompanionPairingInvitation | null> {
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(appId) || !/^[A-Za-z0-9_.:-]{1,128}$/.test(localId)) throw new Error('invalid pairing identity');
  if (cancelled()) return null;
  if (!canIUse('SystemCapability.Multimedia.Scan.ScanBarcode')) throw new Error('pairing scanner unsupported');
  if (scanning) throw new Error('pairing scanner busy');
  scanning = true;
  let result: scanBarcode.ScanResult | null = null;
  try {
    result = await scanBarcode.startScanForResult(context, {
      scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false, enableAlbum: false
    });
    if (cancelled()) return null;
    if (result.scanType !== scanCore.ScanType.QR_CODE ||
      (result.source !== undefined && result.source !== scanCore.ScanSource.CAMERA)) throw new Error('invalid pairing scan source');
    try { return CompanionPairingInvitation.parseQr(result.originalValue, appId, localId, Date.now()); }
    catch (_) { throw new Error('invalid or expired pairing invitation'); }
  } catch (error) {
    if (cancelled() || (error as BusinessError)?.code === 1000500002) return null;
    // Do not forward scanner/parser errors that might include scanned credentials.
    throw new Error('pairing scan failed or invitation invalid');
  } finally {
    try { if (result !== null) result.originalValue = ''; } catch (_) { /* VM/system copies may be immutable. */ }
    scanning = false;
  }
}
