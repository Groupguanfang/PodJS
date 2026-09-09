import { ApprovedBackgroundSource } from './BackgroundPackage';
import { WorkIdentity } from './SystemWorkScheduler';

export interface BackgroundStore {
  read(): Promise<string | null>;
  compareExchange(expected: string | null, desired: string): Promise<boolean>;
}
export class BackgroundTask {
  id: string = '';
  handler: string = '';
  earliestAt: number = 0;
  requiresNetwork: boolean = false;
  payload: Object | null = null;
  intervalMs?: number;
}
export class BackgroundOutcome {
  status: string = '';
  code: string = '';
  elapsedMs: number = 0;
}
export class BackgroundRecord {
  identity: WorkIdentity = new WorkIdentity();
  task: BackgroundTask = new BackgroundTask();
  sourceHash: string = '';
  grants: string[] = [];
  budgetMs: number = 10000;
  phase: string = 'pending';
  claimId: string = '';
  executionActive: boolean = false;
  result: BackgroundOutcome | null = null;
  retryOf: string = '';
  retryCount: number = 0;
  retrySuppressed: boolean = false;
}
class State {
  version: number = 1;
  appId: string = '';
  revision: number = 0;
  nextWorkId: number = 0x60000000;
  records: BackgroundRecord[] = [];
}
class StoreError { code: string = ''; }
export class BackgroundJournalError extends Error {
  constructor(public code: string) { super(code); }
}
function id(value: string): boolean { return typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value); }
function token(value: string): boolean { return typeof value === 'string' && /^[0-9a-f]{32}$/.test(value); }
function bytes(value: string): number {
  let count = 0;
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c < 0x80) count++;
    else if (c < 0x800) count += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < value.length &&
        value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) { count += 4; i++; }
    else count += 3;
  }
  return count;
}
function validTask(task: BackgroundTask): boolean {
  return !!task && id(task.id) && id(task.handler) && Number.isSafeInteger(task.earliestAt) && task.earliestAt >= 0 &&
    typeof task.requiresNetwork === 'boolean' && task.intervalMs === undefined &&
    task.payload !== undefined && bytes(JSON.stringify(task.payload)) <= 65536;
}
function validGrants(grants: string[]): boolean {
  return Array.isArray(grants) && grants.length <= 4 && new Set(grants).size === grants.length &&
    grants.every(method => ['kv.get', 'kv.set', 'kv.delete', 'kv.keys'].includes(method));
}
function terminal(record: BackgroundRecord): boolean { return ['completed', 'failed', 'cancelled'].includes(record.phase); }
function outcome(value: BackgroundOutcome): boolean {
  return !!value && ['success', 'retry', 'failure'].includes(value.status) && id(value.code) &&
    Number.isSafeInteger(value.elapsedMs) && value.elapsedMs >= 0;
}

/** Durable state only; OS effects occur outside CAS and must be reconciled.
 * An execution claim is never expired by wall-clock time. The host must hold an
 * exclusive process-independent execution lease before claim/abandon and until
 * native execution returns. This journal does not implement that lease. */
export class BackgroundJournal {
  constructor(private appId: string, private store: BackgroundStore, private pause: () => Promise<void>) {
    if (!id(appId)) throw new BackgroundJournalError('invalid_argument');
  }
  async records(): Promise<BackgroundRecord[]> { return this.load(await this.store.read()).records; }
  /** Caller holds the OS coordination lease and has confirmed these exact
   * system generations absent. Preserve latest status and unfinished retries.
   * The allocation cursor is deliberately independent of retained history. */
  prune(confirmedAbsent: WorkIdentity[]): Promise<number> {
    const keys = confirmedAbsent.map(value => this.key(value));
    return this.mutate(state => {
      const latest = new Map<string, number>();
      for (const record of state.records) latest.set(record.task.id, Math.max(latest.get(record.task.id) ?? 0, record.identity.workId));
      const before = state.records.length;
      state.records = state.records.filter(record => !(
        terminal(record) && !record.executionActive && record.identity.workId !== latest.get(record.task.id) &&
        !(record.phase === 'completed' && record.result?.status === 'retry' && !record.retrySuppressed) &&
        keys.some(key => key.workId === record.identity.workId && key.runId === record.identity.runId)));
      return before - state.records.length;
    });
  }
  register(task: BackgroundTask, approved: ApprovedBackgroundSource, runId: string): Promise<BackgroundRecord> {
    // Normalize optional public fields without allowing a mutable caller object
    // to affect a later CAS retry. Source itself is never persisted as authority.
    const frozen = JSON.parse(JSON.stringify(task)) as BackgroundTask;
    if (frozen.requiresNetwork === undefined) frozen.requiresNetwork = false;
    if (frozen.payload === undefined) frozen.payload = null;
    const hash = approved.sha256; const grants = approved.grants.slice();
    if (!validTask(frozen) || !token(runId) || approved.appId !== this.appId || approved.handlerId !== frozen.handler ||
        !/^[0-9a-f]{64}$/.test(hash) || !validGrants(grants))
      return Promise.reject(new BackgroundJournalError('invalid_argument'));
    return this.mutate(state => {
      const sameRun = state.records.find(r => r.identity.runId === runId);
      if (sameRun) {
        if (JSON.stringify(sameRun.task) !== JSON.stringify(frozen) || sameRun.sourceHash !== hash ||
            JSON.stringify(sameRun.grants) !== JSON.stringify(grants)) throw new BackgroundJournalError('conflict');
        return sameRun;
      }
      const previous = state.records.filter(r => r.task.id === frozen.id && !terminal(r));
      const duplicate = previous.find(r => r.phase !== 'cancelling' && JSON.stringify(r.task) === JSON.stringify(frozen) &&
        r.sourceHash === hash && JSON.stringify(r.grants) === JSON.stringify(grants));
      if (duplicate) return duplicate;
      if (state.records.length >= 128 || state.nextWorkId > 2147483647) throw new BackgroundJournalError('resource_exhausted');
      for (const record of state.records) if (record.task.id === frozen.id) {
        record.retrySuppressed = true;
        if (!terminal(record)) record.phase = 'cancelling';
      }
      const record = new BackgroundRecord(); record.identity.workId = state.nextWorkId++;
      record.identity.runId = runId; record.task = frozen; record.sourceHash = hash; record.grants = grants;
      state.records.push(record); return record;
    });
  }
  cancelTask(taskId: string): Promise<void> {
    if (!id(taskId)) return Promise.reject(new BackgroundJournalError('invalid_argument'));
    return this.mutate(state => {
      for (const record of state.records) if (record.task.id === taskId) {
        record.retrySuppressed = true;
        if (!terminal(record) || (record.phase === 'completed' && record.result?.status === 'retry')) record.phase = 'cancelling';
      }
    });
  }
  /** Commit a fresh OS identity before registering a retry. The parent remains
   * available for OS cancellation; old callbacks cannot address the new work. */
  retry(identity: WorkIdentity, runId: string, now: number): Promise<BackgroundRecord | null> {
    const key = this.key(identity);
    if (!token(runId) || !Number.isSafeInteger(now) || now < 0) return Promise.reject(new BackgroundJournalError('invalid_argument'));
    return this.mutate(state => {
      const child = state.records.find(r => r.retryOf === key.runId);
      if (child) return child;
      const parent = this.find(state, key);
      if (!parent || parent.phase !== 'completed' || parent.result?.status !== 'retry' || parent.executionActive ||
          parent.retrySuppressed || state.records.some(r => r.task.id === parent.task.id && r.identity.workId > parent.identity.workId)) return null;
      if (state.records.some(r => r.identity.runId === runId)) throw new BackgroundJournalError('conflict');
      if (state.records.length >= 128 || state.nextWorkId > 2147483647 || parent.retryCount >= Number.MAX_SAFE_INTEGER)
        throw new BackgroundJournalError('resource_exhausted');
      const earliest = now + Math.min(86400000, 30000 * Math.pow(2, Math.min(parent.retryCount, 12)));
      if (!Number.isSafeInteger(earliest)) throw new BackgroundJournalError('invalid_argument');
      const record = new BackgroundRecord(); record.identity.workId = state.nextWorkId++; record.identity.runId = runId;
      record.task = JSON.parse(JSON.stringify(parent.task)) as BackgroundTask;
      record.task.earliestAt = Math.max(parent.task.earliestAt, earliest);
      record.sourceHash = parent.sourceHash; record.grants = parent.grants.slice(); record.budgetMs = parent.budgetMs;
      record.retryOf = parent.identity.runId; record.retryCount = parent.retryCount + 1;
      parent.retrySuppressed = true; state.records.push(record); return record;
    });
  }
  scheduled(identity: WorkIdentity): Promise<void> {
    const key = this.key(identity);
    return this.mutate(state => { const record = this.find(state, key); if (record?.phase === 'pending') record.phase = 'scheduled'; });
  }
  confirmCancelled(identity: WorkIdentity): Promise<boolean> {
    const key = this.key(identity);
    return this.mutate(state => {
      const record = this.find(state, key);
      if (!record || record.executionActive || record.phase !== 'cancelling') return false;
      record.phase = 'cancelled'; return true;
    });
  }
  claim(identity: WorkIdentity, claimId: string, now: number): Promise<BackgroundRecord | null> {
    const key = this.key(identity);
    if (!token(claimId) || !Number.isSafeInteger(now) || now < 0) return Promise.reject(new BackgroundJournalError('invalid_argument'));
    return this.mutate(state => {
      const record = this.find(state, key);
      if (!record) return null;
      if (record.phase === 'running' && record.executionActive && record.claimId === claimId) return record;
      if (!['pending', 'scheduled'].includes(record.phase) || now < record.task.earliestAt ||
          state.records.some(r => r.executionActive)) return null;
      record.phase = 'running'; record.executionActive = true; record.claimId = claimId; return record;
    });
  }
  finish(identity: WorkIdentity, claimId: string, result: BackgroundOutcome): Promise<boolean> {
    const key = this.key(identity); const frozen = JSON.parse(JSON.stringify(result)) as BackgroundOutcome;
    if (!token(claimId) || !outcome(frozen)) return Promise.reject(new BackgroundJournalError('invalid_argument'));
    return this.mutate(state => {
      const record = this.find(state, key);
      if (!record || record.claimId !== claimId) return false;
      if (!record.executionActive) return JSON.stringify(record.result) === JSON.stringify(frozen);
      record.executionActive = false; record.result = frozen;
      record.phase = record.phase === 'cancelling' ? 'cancelled' : frozen.status === 'failure' ? 'failed' : 'completed';
      return true;
    });
  }
  /** Only after obtaining the exclusive execution lease and proving no native
   * run for this claim survives. Never call this merely because time elapsed. */
  abandon(identity: WorkIdentity, claimId: string): Promise<boolean> {
    const result = new BackgroundOutcome(); result.status = 'failure'; result.code = 'execution_abandoned';
    return this.finish(identity, claimId, result);
  }
  private key(value: WorkIdentity): WorkIdentity {
    if (!value || !Number.isInteger(value.workId) || value.workId < 0x60000000 || value.workId > 2147483647 || !token(value.runId))
      throw new BackgroundJournalError('invalid_argument');
    const key = new WorkIdentity(); key.workId = value.workId; key.runId = value.runId; return key;
  }
  private find(state: State, key: WorkIdentity): BackgroundRecord | undefined {
    return state.records.find(r => r.identity.workId === key.workId && r.identity.runId === key.runId);
  }
  private load(raw: string | null): State {
    if (raw === null) { const state = new State(); state.appId = this.appId; return state; }
    try {
      if (bytes(raw) > 16 * 1024 * 1024) throw new Error('large');
      const state = JSON.parse(raw) as State;
      if (!state || state.version !== 1 || state.appId !== this.appId || !Number.isSafeInteger(state.revision) || state.revision < 0 ||
          !Number.isInteger(state.nextWorkId) || state.nextWorkId < 0x60000000 || state.nextWorkId > 2147483648 ||
          !Array.isArray(state.records) || state.records.length > 128) throw new Error('header');
      const ids = new Set<number>(); const tokens = new Set<string>(); const liveTasks = new Set<string>(); let active = 0;
      for (const record of state.records) {
        // Backward-compatible v1 records predate retry metadata. A future write
        // persists these defaults together with a new revision.
        if (record.retryOf === undefined) record.retryOf = '';
        if (record.retryCount === undefined) record.retryCount = 0;
        if (record.retrySuppressed === undefined) record.retrySuppressed = false;
        this.key(record.identity);
        if (ids.has(record.identity.workId) || tokens.has(record.identity.runId) || record.identity.workId >= state.nextWorkId ||
            !validTask(record.task) || !/^[0-9a-f]{64}$/.test(record.sourceHash) || !validGrants(record.grants) ||
            record.budgetMs !== 10000 || !['pending', 'scheduled', 'running', 'cancelling', 'completed', 'cancelled', 'failed'].includes(record.phase) ||
            typeof record.executionActive !== 'boolean' || (record.claimId !== '' && !token(record.claimId)) ||
            typeof record.retrySuppressed !== 'boolean' || !Number.isSafeInteger(record.retryCount) || record.retryCount < 0 ||
            (record.retryOf !== '' && (!token(record.retryOf) || record.retryOf === record.identity.runId)) ||
            ((record.retryOf === '') !== (record.retryCount === 0)) ||
            (record.result !== null && !outcome(record.result))) throw new Error('record');
        if (record.executionActive) {
          active++; if (!token(record.claimId) || !['running', 'cancelling'].includes(record.phase) || record.result !== null) throw new Error('active');
        } else if (record.phase === 'running') throw new Error('running');
        if (['pending', 'scheduled'].includes(record.phase) && (record.claimId !== '' || record.result !== null)) throw new Error('pending');
        if (record.phase === 'completed' && (!record.result || !['success', 'retry'].includes(record.result.status))) throw new Error('completed');
        if (record.phase === 'failed' && record.result?.status !== 'failure') throw new Error('failed');
        if (!terminal(record) && record.phase !== 'cancelling') {
          if (liveTasks.has(record.task.id)) throw new Error('duplicate task');
          liveTasks.add(record.task.id);
        }
        ids.add(record.identity.workId); tokens.add(record.identity.runId);
      }
      if (active > 1) throw new Error('concurrent');
      return state;
    } catch (_) { throw new BackgroundJournalError('corrupt_storage'); }
  }
  private async mutate<T>(operation: (state: State) => T): Promise<T> {
    for (let attempt = 0; attempt < 32; attempt++) {
      try {
        const raw = await this.store.read(); const state = this.load(raw);
        const before = JSON.stringify(state); const result = operation(state);
        if (before === JSON.stringify(state)) return result;
        if (state.revision >= Number.MAX_SAFE_INTEGER) throw new BackgroundJournalError('resource_exhausted');
        state.revision++;
        const desired = JSON.stringify(state);
        if (bytes(desired) > 16 * 1024 * 1024) throw new BackgroundJournalError('resource_exhausted');
        if (await this.store.compareExchange(raw, desired)) return result;
      } catch (error) { if ((error as StoreError)?.code !== 'busy') throw error; }
      await this.pause();
    }
    throw new BackgroundJournalError('busy');
  }
}
