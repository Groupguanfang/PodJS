import { expect, test } from "bun:test";
import { CompanionState, type CompanionStateDatabase } from "../src/state";
import type { StateSnapshot } from "../../framework/src/sync-state";

class Database implements CompanionStateDatabase {
  rows = new Map<string, StateSnapshot>();
  tail: Promise<void> = Promise.resolve();
  failure: "before" | "after" | null = null;
  transaction(app: string, update: (saved: StateSnapshot | undefined) => StateSnapshot): Promise<void> {
    const task = this.tail.then(() => {
      const saved = this.rows.get(app);
      const next = update(saved ? structuredClone(saved) : undefined);
      const failure = this.failure; this.failure = null;
      if (failure === "before") throw Error("before commit");
      this.rows.set(app, structuredClone(next));
      if (failure === "after") throw Error("after commit");
    });
    this.tail = task.catch(() => {});
    return task;
  }
}
test("two SDK handles serialize counters, preserve concurrent keys and isolate apps", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db), b = new CompanionState("app", "phone", db);
  const [first, second] = await Promise.all([a.set("a", 1), b.set("b", 2)]);
  expect([first.counter, second.counter]).toEqual([1, 2]);
  expect(await a.get("b")).toBe(2);
  expect(await new CompanionState("other", "phone", db).get("a")).toBeUndefined();
});
test("failed and uncertain writes reload authoritative storage and never notify success", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db);
  let notifications = 0; a.subscribe(() => { notifications++; });
  db.failure = "before"; await expect(a.set("key", 1)).rejects.toThrow("before commit");
  expect(await a.get("key")).toBeUndefined();
  db.failure = "after"; await expect(a.set("key", 2)).rejects.toThrow("after commit");
  expect(await a.get("key")).toBe(2); expect(notifications).toBe(0);
  expect((await a.set("key", 3)).counter).toBe(2); expect(notifications).toBe(1);
});
test("receive commits state and cursor together; retry after lost receipt is idempotent", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db);
  const entries = [{ key: "remote", value: 7, counter: 5, deviceId: "watch", deleted: false }];
  db.failure = "before";
  await expect(a.receiveAuthenticated("watch", 0, 1, entries)).rejects.toThrow();
  expect((await a.snapshot()).cursors).toEqual({});
  db.failure = "after";
  await expect(a.receiveAuthenticated("watch", 0, 1, entries)).rejects.toThrow();
  const reopened = new CompanionState("app", "phone", db);
  expect(await reopened.receiveAuthenticated("watch", 0, 1, entries)).toBe(1);
  expect(await reopened.get("remote")).toBe(7);
  expect((await reopened.set("local", true)).counter).toBe(6);
  await expect(reopened.receiveAuthenticated("watch", 2, 3, entries)).rejects.toThrow("gap");
});
test("callers and throwing subscribers cannot mutate committed or queued values", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db);
  const value = { nested: [1] }; const pending = a.set("key", value); value.nested[0] = 99;
  let seen = 0; let observed: unknown;
  a.subscribe(snapshot => { snapshot.entries[0].value = 999; throw Error("subscriber"); });
  const unsubscribe = a.subscribe(snapshot => { seen++; observed = snapshot.entries[0].value; });
  await pending; expect(await a.get("key")).toEqual({ nested: [1] }); expect(seen).toBe(1);
  expect(observed).toEqual({ nested: [1] });
  unsubscribe(); await a.delete("key"); expect(seen).toBe(1);
  expect(await a.get("key")).toBeUndefined();
  expect((await a.snapshot()).entries[0].deleted).toBe(true);
});
test("authenticated ingress snapshots caller entries before asynchronous storage", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db);
  const entries = [{ key: "remote", value: { n: 1 }, counter: 1, deviceId: "watch", deleted: false }];
  const pending = a.receiveAuthenticated("watch", 0, 1, entries);
  entries[0].value.n = 99; entries[0].counter = 100;
  expect(await pending).toBe(1); expect(await a.get("remote")).toEqual({ n: 1 });
  expect((await a.snapshot()).clock).toBe(1);
});
test("non-JSON input and corrupt snapshots fail closed without replacement", async () => {
  const db = new Database(), a = new CompanionState("app", "phone", db);
  await expect(a.set("key", NaN)).rejects.toThrow("finite JSON");
  expect(db.rows.size).toBe(0);
  db.rows.set("app", { version: 1, clock: -1, entries: [], cursors: {} });
  await expect(a.set("key", 1)).rejects.toThrow("counter");
  expect(db.rows.get("app")!.clock).toBe(-1);
});
