export const backgroundAbilityName = 'PodBackgroundAbility';
export class WorkIdentity {
  workId: number = 0;
  runId: string = '';
}
export class WorkPlan {
  workId: number = 0;
  bundleName: string = '';
  abilityName: string = backgroundAbilityName;
  isPersisted: boolean = true;
  isRepeat: boolean = false;
  earliestStartTime: number = 0;
  // LOW_OR_OKAY is an always-eligible battery condition. Work Scheduler requires
  // at least one condition even for jobs without a network requirement.
  batteryStatus: number = 2;
  networkType?: number;
  parameters: Record<string, number | string | boolean> = {};
}
export interface SystemWorkApi {
  supported(): boolean;
  list(): Promise<WorkPlan[]>;
  start(plan: WorkPlan): void;
  stop(plan: WorkPlan): void; // Stop and remove only this precise owned work.
}
export class WorkSchedulerError extends Error {
  constructor(public code: string) { super(code); }
}
function valid(identity: WorkIdentity): boolean {
  return !!identity && Number.isInteger(identity.workId) && identity.workId >= 1 && identity.workId <= 2147483647 &&
    typeof identity.runId === 'string' && /^[0-9a-f]{32}$/.test(identity.runId);
}
/** Host-only system adapter, not task persistence. The journal must allocate
 * workId/runId durably and record intent before calling start/stop. */
export class SystemWorkScheduler {
  constructor(private bundleName: string, private api: SystemWorkApi, private now: () => number) {
    if (typeof bundleName !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(bundleName))
      throw new WorkSchedulerError('invalid_argument');
  }
  private check(identity: WorkIdentity): void {
    if (!valid(identity)) throw new WorkSchedulerError('invalid_argument');
    if (!this.api.supported()) throw new WorkSchedulerError('unsupported');
  }
  private owned(plan: WorkPlan, identity: WorkIdentity): boolean {
    return plan.workId === identity.workId && plan.bundleName === this.bundleName &&
      plan.abilityName === backgroundAbilityName && !!plan.parameters &&
      plan.parameters['podjsWorkVersion'] === 1 && plan.parameters['runId'] === identity.runId;
  }
  async schedule(identity: WorkIdentity, earliestAt: number, requiresNetwork: boolean): Promise<void> {
    this.check(identity);
    if (!Number.isSafeInteger(earliestAt) || earliestAt < 0 || typeof requiresNetwork !== 'boolean')
      throw new WorkSchedulerError('invalid_argument');
    // Snapshot before awaiting OS IO; caller mutation cannot redirect work.
    const key = new WorkIdentity(); key.workId = identity.workId; key.runId = identity.runId;
    const existing = (await this.api.list()).filter(plan => plan.workId === key.workId);
    if (existing.length) {
      if (existing.length !== 1 || !this.owned(existing[0], key) ||
          existing[0].parameters['earliestAt'] !== earliestAt ||
          existing[0].parameters['requiresNetwork'] !== requiresNetwork)
        throw new WorkSchedulerError('conflict');
      return;
    }
    const current = this.now();
    if (!Number.isSafeInteger(current) || current < 0) throw new WorkSchedulerError('invalid_clock');
    if (!this.api.supported()) throw new WorkSchedulerError('unsupported');
    const plan = new WorkPlan(); plan.workId = key.workId; plan.bundleName = this.bundleName;
    plan.earliestStartTime = Math.max(0, earliestAt - current);
    if (requiresNetwork) plan.networkType = 0; // NETWORK_TYPE_ANY
    plan.parameters = { podjsWorkVersion: 1, runId: key.runId, earliestAt, requiresNetwork };
    this.api.start(plan);
  }
  async cancel(identity: WorkIdentity): Promise<void> {
    this.check(identity);
    const key = new WorkIdentity(); key.workId = identity.workId; key.runId = identity.runId;
    const existing = (await this.api.list()).filter(plan => plan.workId === key.workId);
    if (existing.length === 0) return;
    if (existing.length !== 1 || !this.owned(existing[0], key)) throw new WorkSchedulerError('conflict');
    this.api.stop(existing[0]);
    // Success authorizes journal pruning, so require absence rather than just
    // acceptance of stopWork. A delayed removal is retried by reconciliation.
    if ((await this.api.list()).some(plan => plan.workId === key.workId)) throw new WorkSchedulerError('busy');
  }
  async list(): Promise<WorkIdentity[]> {
    if (!this.api.supported()) throw new WorkSchedulerError('unsupported');
    const result: WorkIdentity[] = [];
    for (const plan of await this.api.list()) {
      const key = new WorkIdentity(); key.workId = plan.workId;
      const runId = plan.parameters?.['runId']; key.runId = typeof runId === 'string' ? runId : '';
      if (valid(key) && this.owned(plan, key)) result.push(key);
    }
    return result;
  }
}
