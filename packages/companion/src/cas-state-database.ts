import type { CompanionStateDatabase } from "./state";
import type { StateSnapshot } from "../../framework/src/sync-state";

export interface CompanionStateCas {
  read(appId: string): Promise<string | null>;
  compareExchange(appId: string, expected: string | null, desired: string): Promise<boolean>;
}

/** Optimistic transaction over native, durable compare-and-swap. A concurrent
 * writer produces an explicit conflict rather than replaying the callback.
 * The native adapter owns interprocess locking and the durable commit boundary.
 */
export class CasStateDatabase implements CompanionStateDatabase {
  private tail: Promise<void> = Promise.resolve();
  constructor(private native: CompanionStateCas) {}
  transaction(appId: string, update: (saved: StateSnapshot | undefined) => StateSnapshot): Promise<void> {
    const task = this.tail.then(async () => {
      const old = await this.native.read(appId);
      const next = update(old === null ? undefined : JSON.parse(old));
      if (!await this.native.compareExchange(appId, old, JSON.stringify(next))) {
        throw new Error("Companion state transaction conflict; reload and retry");
      }
    });
    this.tail = task.then(() => {}, () => {});
    return task;
  }
}
