import { WorkSchedulerExtensionAbility, workScheduler } from '@kit.BackgroundTasksKit';
import { BackgroundLifecycle } from './BackgroundLifecycle';
import { nativeBackgroundExecution } from './NativeBackgroundExecution';
import { nativeBackgroundSchedules } from './NativeBackgroundSchedules';
import { backgroundAbilityName, WorkIdentity } from './SystemWorkScheduler';

export default class PodBackgroundAbility extends WorkSchedulerExtensionAbility {
  private lifecycle: BackgroundLifecycle | null = null;
  private controller(): BackgroundLifecycle {
    if (!this.lifecycle) {
      this.lifecycle = new BackgroundLifecycle(() => nativeBackgroundExecution(this.context),
        async () => { await (await nativeBackgroundSchedules(this.context)).reconcile(); });
    }
    return this.lifecycle;
  }
  private identity(work: workScheduler.WorkInfo): WorkIdentity | null {
    const runId = work.parameters?.['runId'];
    if (work.abilityName !== backgroundAbilityName || work.parameters?.['podjsWorkVersion'] !== 1 ||
        !Number.isInteger(work.workId) || work.workId < 0x60000000 || work.workId > 2147483647 ||
        typeof runId !== 'string' || !/^[0-9a-f]{32}$/.test(runId)) return null;
    const identity = new WorkIdentity(); identity.workId = work.workId; identity.runId = runId; return identity;
  }
  onWorkStart(work: workScheduler.WorkInfo): void {
    const identity = this.identity(work); if (!identity) return;
    // Never acknowledge an OS job just because startup failed or another
    // process owns the lease. Durable reconciliation decides removal.
    this.controller().start(identity).catch(() => { console.error('PodJS background callback failed'); });
  }
  onWorkStop(work: workScheduler.WorkInfo): void {
    const identity = this.identity(work); if (identity) this.lifecycle?.stop(identity);
  }
}
