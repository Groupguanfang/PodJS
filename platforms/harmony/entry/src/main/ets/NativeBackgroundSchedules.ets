import { common, bundleManager } from '@kit.AbilityKit';
import * as podjs from 'libpodjs_harmony.so';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { BackgroundJournal } from './BackgroundJournal';
import { BackgroundSchedules, BackgroundCoordinationLease } from './BackgroundSchedules';
import { NativeBackgroundStore } from './NativeBackgroundStore';
import { installedBackgroundPackage } from './NativeBackgroundPackage';
import { nativeSystemWorkScheduler } from './NativeSystemWorkScheduler';
import { BackgroundExecution } from './BackgroundExecution';
import { nativeBackgroundExecution } from './NativeBackgroundExecution';
import { RecoverableBackground } from './BackgroundRecovery';

class NativeMaintenance implements RecoverableBackground {
  private execution: BackgroundExecution;
  private schedules: BackgroundSchedules;
  constructor(execution: BackgroundExecution, schedules: BackgroundSchedules) { this.execution = execution; this.schedules = schedules; }
  async reconcile(current: () => boolean): Promise<void> {
    await this.execution.recoverAbandoned(current);
    await this.schedules.reconcile(current);
  }
}
export async function nativeBackgroundMaintenance(context: common.Context): Promise<RecoverableBackground> {
  const schedules = await nativeBackgroundSchedules(context);
  return new NativeMaintenance(await nativeBackgroundExecution(context), schedules);
}

async function runToken(): Promise<string> {
  const random = await cryptoFramework.createRandom().generateRandom(16);
  let token = ''; for (const byte of random.data) token += byte.toString(16).padStart(2, '0'); return token;
}
class SchedulerLease implements BackgroundCoordinationLease {
  private handle: number;
  constructor(handle: number) { this.handle = handle; }
  close(): void { if (this.handle >= 0) { const handle = this.handle; this.handle = -1; podjs.backgroundClose(handle); } }
}
let schedules: Promise<BackgroundSchedules> | null = null;
let filesDirectory: string = '';
export function nativeBackgroundSchedules(context: common.Context): Promise<BackgroundSchedules> {
  if (schedules !== null) {
    if (filesDirectory !== context.filesDir) return Promise.reject(new Error('Background context mismatch'));
    return schedules;
  }
  filesDirectory = context.filesDir;
  const pending = createSchedules(context); schedules = pending;
  // Factory IO is read-only; no lease or OS task has been acquired on failure.
  pending.catch(() => { if (schedules === pending) schedules = null; });
  return pending;
}
async function createSchedules(context: common.Context): Promise<BackgroundSchedules> {
  const bundle = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
  const journal = new BackgroundJournal(bundle.name, new NativeBackgroundStore(context),
    () => new Promise<void>(resolve => { setTimeout(resolve, 5); }));
  const system = await nativeSystemWorkScheduler();
  return new BackgroundSchedules(journal, system, async (handler: string) =>
    (await installedBackgroundPackage(context)).resolve(handler), runToken, () => Date.now(),
    async () => new SchedulerLease(await podjs.backgroundOpenSchedulerLease(context.filesDir)));
}
