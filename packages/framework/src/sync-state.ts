/** Transport-independent state replication. Native storage must commit atomically. */
export type SyncValue = null | boolean | number | string | SyncValue[] | { [key: string]: SyncValue };
export interface StateEntry {
  key: string;
  value: SyncValue;
  counter: number;
  deviceId: string;
  deleted: boolean;
}
export interface StateSnapshot {
  version: 1;
  clock: number;
  entries: StateEntry[];
  cursors: Record<string, number>;
}
export interface StateStorage {
  load(): StateSnapshot | undefined;
  /** Must replace the snapshot atomically, throwing on failure. */
  commit(snapshot: StateSnapshot): void;
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
function identity(value: string): void {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error("Invalid sync identity");
}
function integer(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error("Invalid sync counter");
}
function validateValue(value: unknown, depth = 0): void {
  if (depth > 32) throw new Error("Sync value nesting limit");
  if (value === null || typeof value === "string" || typeof value === "boolean") return;
  if (typeof value === "number" && Number.isFinite(value)) return;
  if (Array.isArray(value)) { value.forEach(item => validateValue(item, depth + 1)); return; }
  if (typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
    Object.values(value).forEach(item => validateValue(item, depth + 1)); return;
  }
  throw new Error("Sync values must be finite JSON data");
}
function validate(entry: StateEntry): void {
  identity(entry.key); identity(entry.deviceId); integer(entry.counter);
  if (typeof entry.deleted !== "boolean" || (entry.deleted && entry.value !== null)) throw new Error("Invalid tombstone");
  validateValue(entry.value);
  if (JSON.stringify(entry.value).length > 65536) throw new Error("State value too large; use file sync");
}
function compare(a: StateEntry, b: StateEntry): number {
  return a.counter - b.counter || (a.deviceId < b.deviceId ? -1 : a.deviceId > b.deviceId ? 1 : 0);
}
function canonical(value: SyncValue): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value !== null && typeof value === "object") return "{" + Object.keys(value).sort().map(key => JSON.stringify(key) + ":" + canonical(value[key])).join(",") + "}";
  return JSON.stringify(value);
}
export class SyncStateStore {
  private snapshot: StateSnapshot;
  constructor(readonly deviceId: string, private readonly storage: StateStorage) {
    identity(deviceId);
    const saved = storage.load();
    this.snapshot = clone(saved ?? { version: 1, clock: 0, entries: [], cursors: {} });
    if (this.snapshot.version !== 1) throw new Error("Unsupported state version");
    integer(this.snapshot.clock);
    const keys = new Set<string>();
    for (const entry of this.snapshot.entries) {
      validate(entry);
      if (keys.has(entry.key) || entry.counter > this.snapshot.clock) throw new Error("Corrupt state snapshot");
      keys.add(entry.key);
    }
    for (const [peer, cursor] of Object.entries(this.snapshot.cursors)) { identity(peer); integer(cursor); }
  }
  get(key: string): SyncValue | undefined {
    identity(key);
    const entry = this.snapshot.entries.find(item => item.key === key);
    return entry && !entry.deleted ? clone(entry.value) : undefined;
  }
  export(): StateSnapshot { return clone(this.snapshot); }
  set(key: string, value: SyncValue): StateEntry { return this.write(key, value, false); }
  delete(key: string): StateEntry { return this.write(key, null, true); }
  private write(key: string, value: SyncValue, deleted: boolean): StateEntry {
    const entry = { key, value, deleted, deviceId: this.deviceId, counter: this.snapshot.clock + 1 };
    validate(entry);
    this.apply([entry]);
    return clone(entry);
  }
  /** Call only after transport authentication. Cursor and changes share one commit. */
  receive(peer: string, from: number, to: number, entries: readonly StateEntry[]): number {
    identity(peer); integer(from); integer(to);
    if (to <= from) throw new Error("Invalid sequence range");
    const current = Object.prototype.hasOwnProperty.call(this.snapshot.cursors, peer) ? this.snapshot.cursors[peer] : 0;
    if (to <= current) return current;
    if (from !== current) throw new Error("Sync sequence gap; request replay");
    this.apply(entries, { peer, cursor: to });
    return to;
  }
  private apply(entries: readonly StateEntry[], ack?: { peer: string; cursor: number }): void {
    if (entries.length > 512) throw new Error("Sync batch too large");
    const next = clone(this.snapshot);
    const table = new Map(next.entries.map(entry => [entry.key, entry]));
    for (const incoming of entries) {
      validate(incoming);
      const previous = table.get(incoming.key);
      if (previous && compare(incoming, previous) === 0 &&
          (previous.deleted !== incoming.deleted || canonical(previous.value) !== canonical(incoming.value))) {
        throw new Error("Conflicting payload for same state revision");
      }
      if (!previous || compare(incoming, previous) > 0) table.set(incoming.key, clone(incoming));
      next.clock = Math.max(next.clock, incoming.counter);
    }
    if (table.size > 10000) throw new Error("State entry quota exceeded");
    next.entries = [...table.values()].sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
    if (ack) Object.defineProperty(next.cursors, ack.peer, { value: ack.cursor, writable: true, configurable: true, enumerable: true });
    this.storage.commit(clone(next));
    this.snapshot = next;
  }
}
