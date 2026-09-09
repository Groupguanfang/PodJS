import { generateBarcode, scanCore } from '@kit.ScanKit';
import image from '@ohos.multimedia.image';
import { CompanionPairingInvitationLease } from './CompanionPairingInvitationLease';

/** Caller owns the returned PixelMap and must remove/release it in the lease's
 * QR-dismiss callback and when leaving the page. Display square, unfiltered,
 * black-on-white with an additional white quiet zone (e.g. 32 vp at 256 vp).
 * Never save/share this credential image. Do not use QRCode(text): ArkUI silently
 * truncates beyond 512 characters, while valid long identities can exceed that. */
export async function renderCompanionPairingQr(lease: CompanionPairingInvitationLease): Promise<image.PixelMap> {
  lease.assertActive();
  if (!canIUse('SystemCapability.Multimedia.Scan.GenerateBarcode')) {
    lease.close(); throw new Error('pairing QR generation unsupported');
  }
  let bytes = new Uint8Array(0);
  let bitmap: image.PixelMap | null = null;
  try {
    const text = lease.qrText();
    if (text.length < 1 || text.length > 1024) throw new Error('invalid invitation size');
    bytes = new Uint8Array(text.length);
    for (let i = 0; i < text.length; i++) {
      if (text.charCodeAt(i) > 127) throw new Error('invalid invitation encoding');
      bytes[i] = text.charCodeAt(i);
    }
    bitmap = await generateBarcode.createBarcode(bytes.buffer, {
      scanType: scanCore.ScanType.QR_CODE, width: 512, height: 512,
      backgroundColor: 0xFFFFFF, pixelMapColor: 0x000000, margin: 10,
      level: generateBarcode.ErrorCorrectionLevel.LEVEL_M
    });
    lease.assertActive();
    // Also reject a concurrent claim: it must not redisplay a consumed QR.
    lease.qrText(); return bitmap;
  } catch (_) {
    lease.close();
    if (bitmap !== null) { try { await bitmap.release(); } catch (_) {} }
    throw new Error('pairing QR generation failed or invitation closed');
  } finally { bytes.fill(0); }
}
