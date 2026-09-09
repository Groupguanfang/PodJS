import { SyncStateStore, type StateEntry, type StateSnapshot, type SyncValue } from "../../framework/src/sync-state.ts";

/** Native backends must serialize transactions across all handles for this app,
 * atomically persist the returned snapshot, and resolve only after durable commit.
 * Invoke update exactly once. Throwing after commit is permitted: callers must
 * reload rather than roll back. The callback is synchronous and must not escape.
 * Never share a namespace between application identities.
 */
export interface CompanionStateDatabase {
  transaction(appId: string, update: (saved: StateSnapshot | undefined) => StateSnapshot): Promise<void>;
}

/** Async storage boundary for phone SDKs. This is not a transport or an ArkTS
 * native backend. A platform backend must implement the transaction contract.
 */
export class CompanionState {
  private tail: Promise<void> = Promise.resolve();
  private listeners = new Set<(snapshot: StateSnapshot) => void>();

  constructor(readonly appId: string, readonly deviceId: string, private database: CompanionStateDatabase) {
    for (const identity of [appId, deviceId]) {
      if (typeof identity !== "string" || !/^[A-Za-z0-9_.:-]{1,128}$/.test(identity)) throw new Error("Invalid companion identity");
    }
  }

  get(key: string): Promise<SyncValue | undefined> {
    return this.run(store => store.get(key), false);
  }
  snapshot(): Promise<StateSnapshot> { return this.run(store => store.export(), false); }
  set(key: string, value: SyncValue): Promise<StateEntry> {
    // Validate and freeze synchronously, before another task can mutate input.
    try {
      const checked = new SyncStateStore(this.deviceId, { load: () => undefined, commit: () => {} }).set(key, value);
      return this.run(store => store.set(key, checked.value), true);
    } catch (error) { return Promise.reject(error); }
  }
  delete(key: string): Promise<StateEntry> { return this.run(store => store.delete(key), true); }

  /** Only an authenticated session may invoke this ingress. The returned cursor
   * is safe to ACK only after the promise resolves. This method does not verify
   * peer authentication, nor manufacture a sender's confirmed outbound cursor.
   */
  receiveAuthenticated(peer: string, from: number, to: number, entries: readonly StateEntry[]): Promise<number> {
    let frozen: StateEntry[];
    try {
      // The reference engine validates finite JSON before cloning it.
      const validator = new SyncStateStore(this.deviceId, { load: () => undefined, commit: () => {} });
      validator.receive(peer, 0, 1, entries);
      frozen = JSON.parse(JSON.stringify(entries));
    } catch (error) { return Promise.reject(error); }
    return this.run(store => store.receive(peer, from, to, frozen), true);
  }

  /** Local successful transactions only; not a cross-process database observer.
   * Callback failures cannot turn an already committed operation into a failure.
   */
  subscribe(listener: (snapshot: StateSnapshot) => void): () => void {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }

  private run<T>(operation: (store: SyncStateStore) => T, notify: boolean): Promise<T> {
    const task = this.tail.then(async () => {
      let result!: T;
      let committed: StateSnapshot | undefined;
      await this.database.transaction(this.appId, saved => {
        let updated = saved;
        const store = new SyncStateStore(this.deviceId, {
          load: () => saved,
          commit: snapshot => { updated = snapshot; },
        });
        result = operation(store);
        committed = updated ?? store.export();
        return committed;
      });
      if (!committed) throw new Error("State backend did not execute transaction");
      if (notify) for (const listener of [...this.listeners]) {
        try { listener(JSON.parse(JSON.stringify(committed))); } catch { /* committed */ }
      }
      return result;
    });
    this.tail = task.then(() => {}, () => {});
    return task;
  }
}
