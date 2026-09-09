export interface RecoverableBackground {
  reconcile(current: () => boolean): Promise<void>;
}
/** Foreground maintenance only: never executes guest code or substitutes a
 * timer for OS scheduling. Lifecycle owns the timer that invokes pump(). */
export class BackgroundRecovery {
  private active: boolean = false;
  private epoch: number = 0;
  private pending: Promise<void> | null = null;
  constructor(private load: () => Promise<RecoverableBackground>) {}
  setActive(value: boolean): void {
    if (value !== this.active) { this.active = value; this.epoch++; }
  }
  pump(): Promise<void> {
    if (!this.active) return Promise.resolve();
    if (this.pending) return this.pending;
    const epoch = this.epoch;
    const work = this.perform(epoch); this.pending = work;
    work.then(() => { this.pending = null; }, () => { this.pending = null; });
    return work;
  }
  private async perform(epoch: number): Promise<void> {
    const current = (): boolean => this.active && this.epoch === epoch;
    const schedules = await this.load();
    if (current()) await schedules.reconcile(current);
  }
}
