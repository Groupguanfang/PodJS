import { ReminderJournal } from './ReminderJournal';

/** Foreground driver supplies wakeups and a monotonic millisecond clock.
 * This controller itself owns no timer. */
export class NotificationDelivery {
  private active: boolean = false;
  private epoch: number = 0;
  private busy: boolean = false;
  private nextRead: number = 0;
  private delivered: Map<string, number> = new Map();
  private acknowledging: Map<string, Promise<boolean>> = new Map();
  constructor(private journal: () => Promise<ReminderJournal>, private authorized: () => boolean,
    private post: (json: string) => boolean, private now: () => number) {}
  setActive(value: boolean): void {
    if (value === this.active) return;
    this.active = value; this.epoch++; this.nextRead = 0;
  }
  async pump(): Promise<void> {
    if (!this.active || !this.authorized() || this.busy || this.now() < this.nextRead) return;
    const epoch = this.epoch; this.busy = true;
    this.nextRead = this.now() + 1000;
    try {
      const journal = await this.journal();
      const events = await journal.pendingOpens();
      if (!this.active || this.epoch !== epoch || !this.authorized()) return;
      events.sort((a, b) => (this.delivered.get(a.eventId) ?? -1) - (this.delivered.get(b.eventId) ?? -1));
      let count = 0;
      for (const event of events) {
        if (count >= 16) break;
        const sent = this.delivered.get(event.eventId);
        if (sent !== undefined && this.now() - sent < 1000) continue;
        if (!this.post(JSON.stringify(event))) break;
        this.delivered.set(event.eventId, this.now()); count++;
      }
      const pending = new Set(events.map(event => event.eventId));
      for (const id of this.delivered.keys()) if (!pending.has(id)) this.delivered.delete(id);
    } finally { this.busy = false; }
  }
  acknowledge(eventId: string): Promise<boolean> {
    if (!this.authorized() || !this.delivered.has(eventId)) return Promise.resolve(false);
    const pending = this.acknowledging.get(eventId); if (pending) return pending;
    const work = this.journal().then(journal => {
      // Loading storage may cross grant revocation. Recheck before mutation.
      if (!this.authorized()) return false;
      return journal.acknowledgeOpen(eventId);
    }).then(accepted => {
      if (accepted) this.delivered.delete(eventId); return accepted;
    });
    this.acknowledging.set(eventId, work);
    work.then(() => this.acknowledging.delete(eventId), () => this.acknowledging.delete(eventId));
    return work;
  }
}
