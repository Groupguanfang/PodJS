import { workScheduler } from '@kit.BackgroundTasksKit';
import { bundleManager } from '@kit.AbilityKit';
import deviceInfo from '@ohos.deviceInfo';
import { SystemWorkApi, SystemWorkScheduler, WorkPlan } from './SystemWorkScheduler';

class NativeSystemWorks implements SystemWorkApi {
  supported(): boolean {
    return deviceInfo.sdkApiVersion >= 22 && canIUse('SystemCapability.ResourceSchedule.WorkScheduler');
  }
  async list(): Promise<WorkPlan[]> { return (await workScheduler.obtainAllWorks()) as WorkPlan[]; }
  start(plan: WorkPlan): void { workScheduler.startWork(plan as workScheduler.WorkInfo); }
  stop(plan: WorkPlan): void { workScheduler.stopWork(plan as workScheduler.WorkInfo, true); }
}
export async function nativeSystemWorkScheduler(): Promise<SystemWorkScheduler> {
  const bundle = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
  return new SystemWorkScheduler(bundle.name, new NativeSystemWorks(), () => Date.now());
}
