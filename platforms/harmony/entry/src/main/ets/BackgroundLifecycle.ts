import { BackgroundOutcome } from './BackgroundJournal';
import { WorkIdentity } from './SystemWorkScheduler';

export interface BackgroundLifecycleDriver {
  start(identity: WorkIdentity): Promise<BackgroundOutcome | null>;
  stop(identity: WorkIdentity): void;
}
class PendingWork {
  identity: WorkIdentity = new WorkIdentity();
  stopped: boolean = false;
  driver: BackgroundLifecycleDriver | null = null;
  promise: Promise<void> = Promise.resolve();
}
/** Owns callbacks while the OS extension's asynchronous native factory loads.
 * A stop cannot be lost in that gap. Never removes an OS job on a null result
 * or error: the durable coordinator alone decides what may be removed. */
export class BackgroundLifecycle {
  private pending: Map<string, PendingWork> = new Map();
  private destroyed: boolean = false;
  constructor(private load: () => Promise<BackgroundLifecycleDriver>, private reconcile: () => Promise<void>) {}
  start(identity: WorkIdentity): Promise<void> {
    const key = this.key(identity);
    if (this.destroyed) return Promise.resolve();
    const existing = this.pending.get(key); if (existing) return existing.promise;
    if (this.pending.size >= 16) return Promise.reject(new Error('Background lifecycle queue full'));
    const item = new PendingWork(); item.identity.workId = identity.workId; item.identity.runId = identity.runId;
    this.pending.set(key, item);
    item.promise = this.perform(item);
    item.promise.then(() => this.pending.delete(key), () => this.pending.delete(key));
    return item.promise;
  }
  stop(identity: WorkIdentity): void {
    const item = this.pending.get(this.key(identity));
    if (item) { item.stopped = true; item.driver?.stop(item.identity); }
  }
  destroy(): void {
    this.destroyed = true;
    for (const item of this.pending.values()) { item.stopped = true; item.driver?.stop(item.identity); }
  }
  private key(identity: WorkIdentity): string {
    if (!identity || !Number.isInteger(identity.workId) || identity.workId < 0x60000000 ||
        identity.workId > 2147483647 || typeof identity.runId !== 'string' || !/^[0-9a-f]{32}$/.test(identity.runId))
      throw new Error('Invalid background identity');
    return `${identity.workId}:${identity.runId}`;
  }
  private async perform(item: PendingWork): Promise<void> {
    const driver = await this.load(); item.driver = driver;
    if (item.stopped || this.destroyed) return;
    try { await driver.start(item.identity); }
    finally { if (!this.destroyed) await this.reconcile(); }
  }
}
