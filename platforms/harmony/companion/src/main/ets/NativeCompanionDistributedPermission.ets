import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';

const distributedPermission: Permissions = 'ohos.permission.DISTRIBUTED_DATASYNC';
let requesting: boolean = false;

/** Call only from a foreground user action; this does not connect, enable radios,
 * open Settings, or select another transport. A granted permission does not prove
 * the particular device implements linkEnhance (some builds trim the API). */
export async function requestCompanionDistributedPermission(context: common.UIAbilityContext,
  cancelled: () => boolean = () => false): Promise<boolean> {
  if (cancelled()) return false;
  if (!canIUse('SystemCapability.DistributedSched.AppCollaboration')) throw new Error('distributed transport unsupported');
  const token = context.applicationInfo.accessTokenId;
  if (!Number.isSafeInteger(token) || token <= 0) throw new Error('invalid application access token');
  const manager = abilityAccessCtrl.createAtManager();
  if (manager.checkAccessTokenSync(token, distributedPermission) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) return true;
  if (requesting) throw new Error('distributed permission request busy');
  requesting = true;
  try {
    const result = await manager.requestPermissionsFromUser(context, [distributedPermission]);
    if (cancelled()) return false;
    if (result.permissions.length !== 1 || result.permissions[0] !== distributedPermission ||
      result.authResults.length !== 1 || result.authResults[0] !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) return false;
    return manager.checkAccessTokenSync(token, distributedPermission) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } finally { requesting = false; }
}
