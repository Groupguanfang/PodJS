import { expect, test } from "bun:test";
import { MessageOutbox, MessageInbox, type InboxSnapshot, type MessageSnapshot, type QueuedMessage } from "../src/sync-messages.ts";
function disk() {
  let saved: MessageSnapshot | undefined;
  return { load: () => saved, commit: (value: MessageSnapshot) => { saved = value; } };
}
const message = (messageId = "m1"): QueuedMessage => ({ peerId: "watch", messageId, payload: { hello: "世界" }, expiresAt: 10000, priority: "normal" });
function inboxDisk() {
  let saved: InboxSnapshot | undefined;
  return { load: () => saved, commit: (value: InboxSnapshot) => { saved = value; } };
}
test("lost applied ACK survives restart without redelivering application work", () => {
  const storage = inboxDisk(); let box = new MessageInbox(storage);
  expect(box.receive(message(), 0)).toBe("pending");
  expect(box.receive(message(), 1)).toBe("pending"); expect(box.pending(1)).toHaveLength(1);
  expect(box.markApplied("watch", "m1")).toEqual({ peerId: "watch", messageId: "m1" });
  box = new MessageInbox(storage);
  expect(box.receive(message(), 2)).toBe("applied"); expect(box.pending(2)).toEqual([]);
});
test("inbox disk failure cannot grant an applied ACK", () => {
  const storage = inboxDisk(), box = new MessageInbox(storage);
  box.receive(message(), 0);
  storage.commit = () => { throw new Error("disk full"); };
  expect(() => box.markApplied("watch", "m1")).toThrow("disk full");
  expect(new MessageInbox(storage).pending(1)).toEqual([message()]);
});
test("inbox rejects expired messages and conflicting duplicate identities", () => {
  const box = new MessageInbox(inboxDisk());
  expect(box.receive(message(), 10000)).toBe("expired"); expect(box.pending(10000)).toEqual([]);
  box.receive(message(), 1);
  expect(() => box.receive({ ...message(), payload: "forged" }, 2)).toThrow("identity reused");
  expect(() => box.markApplied("other", "m1")).toThrow("Unknown");
  box.expire(10000); expect(box.pending(10000)).toEqual([]);
});
test("outbox survives restart and resends until recipient ACK", () => {
  const storage = disk(); let box = new MessageOutbox(storage);
  box.enqueue(message(), 0); expect(box.pending("watch", 1)).toEqual([message()]);
  box = new MessageOutbox(storage); expect(box.pending("watch", 2)).toEqual([message()]);
  box.acknowledge("other", "m1"); expect(box.pending("watch", 3)).toHaveLength(1);
  box.acknowledge("watch", "m1"); expect(new MessageOutbox(storage).pending("watch", 4)).toEqual([]);
});
test("enqueue and ACK storage failures preserve retry state", () => {
  const storage = disk(), commit = storage.commit, box = new MessageOutbox(storage);
  storage.commit = () => { throw new Error("disk full"); };
  expect(() => box.enqueue(message(), 0)).toThrow(); expect(box.pending("watch", 0)).toEqual([]);
  storage.commit = commit; box.enqueue(message(), 0);
  storage.commit = () => { throw new Error("disk full"); };
  expect(() => box.acknowledge("watch", "m1")).toThrow(); expect(box.pending("watch", 0)).toHaveLength(1);
});
test("TTL and priority preserve unacknowledged messages", () => {
  const box = new MessageOutbox(disk()); box.enqueue(message(), 0);
  box.enqueue({ ...message("m2"), priority: "high", expiresAt: 5 }, 0);
  expect(box.pending("watch", 1).map(item => item.messageId)).toEqual(["m2", "m1"]);
  box.expire(5); expect(box.pending("watch", 5).map(item => item.messageId)).toEqual(["m1"]);
});
test("identities are idempotent but cannot silently replace content", () => {
  const box = new MessageOutbox(disk()); box.enqueue(message(), 0); box.enqueue(message(), 1);
  expect(box.pending("watch", 1)).toHaveLength(1);
  expect(() => box.enqueue({ ...message(), payload: "changed" }, 2)).toThrow("identity reused");
});
test("UTF8 payload quota counts bytes and rejects non JSON values", () => {
  const box = new MessageOutbox(disk());
  expect(() => box.enqueue({ ...message(), payload: "界".repeat(90000) }, 0)).toThrow("quota");
  expect(() => box.enqueue({ ...message(), payload: NaN }, 0)).toThrow("finite JSON");
  expect(box.pending("watch", 0)).toEqual([]);
});
