import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';

const bluetoothPermission: Permissions = 'ohos.permission.ACCESS_BLUETOOTH';
let requesting: boolean = false;

/** Foreground user-action gate for discovery, GATT connect/listen and advertising.
 * Does not enable Bluetooth, scan, pair, or request real peer MAC access. */
export async function requestCompanionBluetoothPermission(context: common.UIAbilityContext,
  cancelled: () => boolean = () => false): Promise<boolean> {
  if (cancelled()) return false;
  if (!canIUse('SystemCapability.Communication.Bluetooth.Core')) throw new Error('Bluetooth unsupported');
  const token = context.applicationInfo.accessTokenId;
  if (!Number.isSafeInteger(token) || token <= 0) throw new Error('invalid application access token');
  const manager = abilityAccessCtrl.createAtManager();
  if (manager.checkAccessTokenSync(token, bluetoothPermission) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) return true;
  if (requesting) throw new Error('Bluetooth permission request busy');
  requesting = true;
  try {
    const result = await manager.requestPermissionsFromUser(context, [bluetoothPermission]);
    if (cancelled()) return false;
    if (result.permissions.length !== 1 || result.permissions[0] !== bluetoothPermission ||
      result.authResults.length !== 1 || result.authResults[0] !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) return false;
    return manager.checkAccessTokenSync(token, bluetoothPermission) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } finally { requesting = false; }
}
