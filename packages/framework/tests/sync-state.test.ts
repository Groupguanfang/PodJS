import { describe, expect, test } from "bun:test";
import { SyncStateStore, type StateSnapshot, type StateStorage } from "../src/sync-state.ts";
function disk() {
  let snapshot: StateSnapshot | undefined;
  return { load: () => snapshot, commit: (next: StateSnapshot) => { snapshot = next; } } satisfies StateStorage;
}
describe("durable two-peer state replication", () => {
  test("concurrent updates converge regardless of direction", () => {
    const a = new SyncStateStore("phone", disk()), b = new SyncStateStore("watch", disk());
    const av = a.set("favorite", false), bv = b.set("favorite", true);
    a.receive("watch", 0, 1, [bv]); b.receive("phone", 0, 1, [av]);
    expect(a.get("favorite")).toBe(true); expect(b.get("favorite")).toBe(true);
    expect(a.export().entries).toEqual(b.export().entries);
  });
  test("failed storage never acknowledges or mutates state; replay survives restart", () => {
    const storage = disk(), store = new SyncStateStore("watch", storage);
    const entry = new SyncStateStore("phone", disk()).set("article", "body");
    const commit = storage.commit;
    storage.commit = () => { throw new Error("disk full"); };
    expect(() => store.receive("phone", 0, 1, [entry])).toThrow("disk full");
    expect(store.get("article")).toBeUndefined(); expect(store.export().cursors).toEqual({});
    storage.commit = commit;
    expect(store.receive("phone", 0, 1, [entry])).toBe(1);
    const restarted = new SyncStateStore("watch", storage);
    expect(restarted.receive("phone", 0, 1, [entry])).toBe(1);
    expect(restarted.get("article")).toBe("body");
  });
  test("deletion survives stale replay and clock advances past received versions", () => {
    const a = new SyncStateStore("phone", disk()), b = new SyncStateStore("watch", disk());
    const old = a.set("article", "body"); b.receive("phone", 0, 1, [old]);
    const deleted = b.delete("article"); a.receive("watch", 0, 1, [deleted]);
    a.receive("watch", 1, 2, [old]);
    expect(a.get("article")).toBeUndefined(); expect(a.set("article", "new").counter).toBe(3);
  });
  test("gaps and malformed batches leave cursors untouched", () => {
    const a = new SyncStateStore("watch", disk());
    expect(() => a.receive("phone", 1, 2, [])).toThrow("gap");
    expect(() => a.set("x", NaN)).toThrow();
    expect(() => a.receive("phone", 0, 1, [{ key: "x", value: null, deleted: true, deviceId: "phone", counter: -1 }])).toThrow();
    expect(a.export()).toEqual({ version: 1, clock: 0, entries: [], cursors: {} });
  });
  test("returned objects cannot mutate persisted state", () => {
    const a = new SyncStateStore("watch", disk());
    const value = { x: 1 }; a.set("x", value); value.x = 9;
    const snapshot = a.export(); snapshot.entries[0].value = 99;
    expect(a.get("x")).toEqual({ x: 1 });
  });
  test("same revision with different payload is rejected atomically", () => {
    const a = new SyncStateStore("watch", disk());
    const entry = { key: "x", value: 1, deleted: false, deviceId: "phone", counter: 1 };
    a.receive("phone", 0, 1, [entry]);
    expect(() => a.receive("phone", 1, 2, [{ ...entry, value: 2 }])).toThrow("Conflicting");
    expect(a.export().cursors.phone).toBe(1);
  });
});
