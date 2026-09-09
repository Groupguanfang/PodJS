import { ReminderJournal } from './ReminderJournal';

class OpenIdentity {
  constructor(public token: string, public id: string) {}
}

/** Validate launch metadata before touching storage. The journal, not the Want,
 * supplies payload. Failed captures remain bounded in memory for foreground
 * retries; process death before the first durable write is not recoverable here. */
export class NotificationOpenReceiver {
  private inflight: Map<string, Promise<boolean>> = new Map();
  private failed: Map<string, OpenIdentity> = new Map();
  constructor(private journal: () => Promise<ReminderJournal>) {}

  capture(token: Object | undefined, id: Object | undefined): Promise<boolean> {
    if (typeof token !== 'string' || !/^[0-9a-f]{32}$/.test(token) ||
        typeof id !== 'string' || !id || id.length > 128 || id.includes('\0')) return Promise.resolve(false);
    const key = token + ':' + id;
    const prior = this.inflight.get(key); if (prior) return prior;
    if (!this.failed.has(key) && this.inflight.size + this.failed.size >= 64)
      return Promise.reject(new Error('Too many pending notification opens'));
    const identity = new OpenIdentity(token, id);
    const work = Promise.resolve().then(() => this.journal()).then(journal => journal.captureOpen(identity.token, identity.id)).then(accepted => {
      this.failed.delete(key); return accepted;
    }, error => { this.failed.set(key, identity); throw error; });
    this.inflight.set(key, work);
    work.then(() => this.inflight.delete(key), () => this.inflight.delete(key));
    return work;
  }

  async retry(): Promise<void> {
    const pending = Array.from(this.failed.values());
    for (const identity of pending) {
      try { await this.capture(identity.token, identity.id); } catch (_) { /* Keep for the next foreground retry. */ }
    }
  }
}
