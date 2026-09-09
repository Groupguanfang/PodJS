import { ApprovedBackgroundSource } from './BackgroundPackage';
import { BackgroundJournal, BackgroundJournalError, BackgroundRecord, BackgroundTask } from './BackgroundJournal';
import { WorkIdentity } from './SystemWorkScheduler';

export interface BackgroundSystem {
  schedule(identity: WorkIdentity, earliestAt: number, requiresNetwork: boolean): Promise<void>;
  cancel(identity: WorkIdentity): Promise<void>;
}
export interface BackgroundCoordinationLease { close(): void; }
/** Recoverable task intent -> OS effect coordinator. Native IDs are never
 * recycled, so an old cancellation cannot address a newer retry generation. */
export class BackgroundSchedules {
  private tail: Promise<void> = Promise.resolve();
  constructor(private journal: BackgroundJournal, private system: BackgroundSystem,
    private approve: (handler: string) => Promise<ApprovedBackgroundSource>, private token: () => Promise<string>,
    private now: () => number,
    private lease: (() => Promise<BackgroundCoordinationLease>) | null = null) {}
  register(task: BackgroundTask, current: () => boolean = () => true): Promise<BackgroundRecord> {
    const frozen = JSON.parse(JSON.stringify(task)) as BackgroundTask;
    return this.serial(async () => {
      if (!current()) throw new BackgroundJournalError('cancelled');
      await this.cleanup();
      if (!current()) throw new BackgroundJournalError('cancelled');
      const approved = await this.approve(frozen.handler);
      const token = await this.token();
      if (!current()) throw new BackgroundJournalError('cancelled');
      const record = await this.journal.register(frozen, approved, token);
      await this.recover();
      return (await this.journal.records()).find(r => r.identity.runId === record.identity.runId) as BackgroundRecord;
    });
  }
  cancel(taskId: string, current: () => boolean = () => true): Promise<void> {
    return this.serial(async () => { if (!current()) return; await this.journal.cancelTask(taskId); await this.recover(); });
  }
  status(taskId: string): Promise<BackgroundRecord | null> {
    return this.serial(async () => {
      const records = (await this.journal.records()).filter(r => r.task.id === taskId);
      records.sort((a, b) => b.identity.workId - a.identity.workId); return records[0] ?? null;
    });
  }
  reconcile(current: () => boolean = () => true): Promise<void> {
    return this.serial(async () => { if (current()) await this.recover(); });
  }
  private async recover(): Promise<void> {
    await this.cleanup();
    for (const record of await this.journal.records()) {
      if (record.phase === 'completed' && record.result?.status === 'retry' && !record.retrySuppressed)
        await this.journal.retry(record.identity, await this.token(), this.now());
    }
    for (const record of await this.journal.records()) {
      if (record.phase === 'pending' || record.phase === 'scheduled') {
        await this.system.schedule(record.identity, record.task.earliestAt, record.task.requiresNetwork);
        await this.journal.scheduled(record.identity);
      }
    }
  }
  private async cleanup(): Promise<void> {
    const absent: WorkIdentity[] = [];
    // Cancel first: a retry-capacity failure must not bypass requested stops.
    for (const record of await this.journal.records()) {
      if (['cancelling', 'cancelled', 'completed', 'failed'].includes(record.phase)) {
        await this.system.cancel(record.identity);
        if (record.phase === 'cancelling') await this.journal.confirmCancelled(record.identity);
        absent.push(record.identity);
      }
    }
    if (absent.length) await this.journal.prune(absent);
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const work = this.tail.then(async () => {
      const lease = this.lease ? await this.lease() : null;
      try { return await operation(); } finally { lease?.close(); }
    });
    this.tail = work.then(() => {}, () => {}); return work;
  }
}
