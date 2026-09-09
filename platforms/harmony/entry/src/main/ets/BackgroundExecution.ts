import { BackgroundJournal, BackgroundOutcome, BackgroundRecord } from './BackgroundJournal';
import { WorkIdentity } from './SystemWorkScheduler';

export interface BackgroundRun {
  // Must revalidate the current installed package, not trust persisted grants.
  prepare(record: BackgroundRecord): Promise<void>;
  execute(): Promise<BackgroundOutcome>;
  cancel(): void;
  close(): void;
}
export interface BackgroundExecutionHost {
  // Resolves only while holding the cross-process lease through close().
  openLease(): Promise<BackgroundRun>;
  token(): Promise<string>;
  now(): number;
}
class Session {
  stopped: boolean = false;
  run: BackgroundRun | null = null;
  work: Promise<BackgroundOutcome | null> = Promise.resolve(null);
}
class Failure { code: string = ''; }
function result(status: string, code: string): BackgroundOutcome {
  const value = new BackgroundOutcome(); value.status = status; value.code = code; return value;
}
/** Cold execution controller; no renderer or frame loop. OS scheduling and
 * retries are deliberately a separate owner of persistent task intents. */
export class BackgroundExecution {
  private sessions: Map<string, Session> = new Map();
  constructor(private journal: BackgroundJournal, private host: BackgroundExecutionHost) {}
  start(identity: WorkIdentity): Promise<BackgroundOutcome | null> {
    const key = this.key(identity);
    const previous = this.sessions.get(key); if (previous) return previous.work;
    if (this.sessions.size >= 16) return Promise.reject(new Error('Background execution queue full'));
    const frozen = new WorkIdentity(); frozen.workId = identity.workId; frozen.runId = identity.runId;
    const session = new Session(); this.sessions.set(key, session);
    session.work = this.perform(frozen, session);
    session.work.then(() => this.sessions.delete(key), () => this.sessions.delete(key));
    return session.work;
  }
  stop(identity: WorkIdentity): void {
    const session = this.sessions.get(this.key(identity));
    if (session) { session.stopped = true; session.run?.cancel(); }
  }
  stopAll(): void {
    for (const session of this.sessions.values()) { session.stopped = true; session.run?.cancel(); }
  }
  async recoverAbandoned(current: () => boolean = () => true): Promise<void> {
    if (!current() || !(await this.journal.records()).some(record => record.executionActive) || !current()) return;
    let run: BackgroundRun;
    try { run = await this.host.openLease(); }
    catch (error) {
      // A live owner is normal, and must not block independent OS cancellation.
      if ((error as Failure)?.code === 'busy') return;
      throw error;
    }
    try { if (current()) await this.abandonUnderLease(); }
    finally { run.close(); }
  }
  private async abandonUnderLease(): Promise<void> {
    // Reload after acquiring the lease; the preflight snapshot is not evidence
    // that a particular claim is still active or safe to finish.
    for (const record of await this.journal.records()) {
      if (record.executionActive) await this.journal.abandon(record.identity, record.claimId);
    }
  }
  private key(value: WorkIdentity): string {
    if (!value || !Number.isInteger(value.workId) || value.workId < 0x60000000 || value.workId > 2147483647 ||
        typeof value.runId !== 'string' || !/^[0-9a-f]{32}$/.test(value.runId)) throw new Error('Invalid background identity');
    return `${value.workId}:${value.runId}`;
  }
  private async perform(identity: WorkIdentity, session: Session): Promise<BackgroundOutcome | null> {
    const run = await this.host.openLease(); session.run = run;
    try {
      if (session.stopped) return null;
      // Exclusive lease acquisition proves no previous leased native run is
      // alive. Never do this recovery before acquiring it or based on a timer.
      await this.abandonUnderLease();
      if (session.stopped) return null;
      const claim = await this.host.token();
      if (session.stopped) return null;
      const record = await this.journal.claim(identity, claim, this.host.now());
      if (!record) return null;
      let outcome: BackgroundOutcome;
      try {
        if (session.stopped) outcome = result('retry', 'cancelled');
        else {
          await run.prepare(record);
          outcome = session.stopped ? result('retry', 'cancelled') : await run.execute();
          if (session.stopped && outcome.code === 'cancelled') outcome.status = 'retry';
        }
      } catch (error) {
        if (session.stopped) outcome = result('retry', 'cancelled');
        else outcome = result('failure', (error as Failure)?.code === 'background_permission_denied' ? 'background_permission_denied' : 'execution_error');
      }
      if (!await this.journal.finish(identity, claim, outcome)) throw new Error('Background result claim mismatch');
      return outcome;
    } finally {
      // Result commit (including failure) precedes release. On uncertain IO
      // failure, leave the record for the next lease owner to reconcile.
      session.run = null; run.close();
    }
  }
}
