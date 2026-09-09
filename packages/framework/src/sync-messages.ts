import type { SyncValue } from "./sync-state.ts";

export interface QueuedMessage {
  peerId: string;
  messageId: string;
  payload: SyncValue;
  expiresAt: number;
  priority: "normal" | "high";
}
export interface MessageSnapshot { version: 1; outgoing: QueuedMessage[] }
export interface MessageStorage {
  load(): MessageSnapshot | undefined;
  /** Atomic replacement, scoped to a single application's identity. */
  commit(value: MessageSnapshot): void;
}
export const MESSAGE_LIMITS = Object.freeze({ count: 1000, bytes: 8 * 1024 * 1024, payloadBytes: 256 * 1024 });
function json(value: unknown, depth = 0): string {
  if (depth > 32) throw new Error("Message nesting limit");
  if (value === null || typeof value === "boolean" || typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" && Number.isFinite(value)) return JSON.stringify(value);
  if (Array.isArray(value)) return "[" + Array.from(value, item => json(item, depth + 1)).join(",") + "]";
  if (value && typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
    return "{" + Object.keys(value).sort().map(key => JSON.stringify(key) + ":" + json((value as Record<string, unknown>)[key], depth + 1)).join(",") + "}";
  }
  throw new Error("Message payload must be finite JSON");
}
/** Avoid TextEncoder: QuickJS guests do not require browser globals. */
function byteLength(value: string): number {
  let bytes = 0;
  for (const char of value) { const cp = char.codePointAt(0)!; bytes += cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4; }
  return bytes;
}
function checkId(value: string): void {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error("Invalid message identity");
}
function timestamp(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error("Invalid message timestamp");
}
function validate(message: QueuedMessage): void {
  checkId(message.peerId); checkId(message.messageId); timestamp(message.expiresAt);
  if (!["normal", "high"].includes(message.priority)) throw new Error("Invalid message priority");
  if (byteLength(json(message.payload)) > MESSAGE_LIMITS.payloadBytes) throw new Error("Message payload quota exceeded");
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value));
export class MessageOutbox {
  private snapshot: MessageSnapshot;
  constructor(private readonly storage: MessageStorage) {
    this.snapshot = copy(storage.load() ?? { version: 1, outgoing: [] });
    if (this.snapshot.version !== 1 || !Array.isArray(this.snapshot.outgoing)) throw new Error("Invalid message snapshot");
    const seen = new Set<string>();
    for (const message of this.snapshot.outgoing) {
      validate(message);
      const key = JSON.stringify([message.peerId, message.messageId]);
      if (seen.has(key)) throw new Error("Duplicate outbox identity");
      seen.add(key);
    }
    this.quota(this.snapshot);
  }
  enqueue(message: QueuedMessage, now: number): void {
    validate(message); timestamp(now);
    if (message.expiresAt <= now) throw new Error("Message already expired");
    const existing = this.snapshot.outgoing.find(item => item.peerId === message.peerId && item.messageId === message.messageId);
    if (existing) {
      if (json(existing) !== json(message)) throw new Error("Message identity reused with different content");
      return;
    }
    const next = { version: 1 as const, outgoing: [...this.snapshot.outgoing.filter(item => item.expiresAt > now), copy(message)] };
    this.quota(next); this.commit(next);
  }
  /** Read does not mark sent: loss before remote ACK must retransmit. */
  pending(peerId: string, now: number): QueuedMessage[] {
    checkId(peerId); timestamp(now);
    return copy(this.snapshot.outgoing.filter(item => item.peerId === peerId && item.expiresAt > now)
      .sort((a, b) => a.priority === b.priority ? 0 : a.priority === "high" ? -1 : 1));
  }
  /** Only call with ACK from the authenticated recipient. */
  acknowledge(peerId: string, messageId: string): void {
    checkId(peerId); checkId(messageId);
    const outgoing = this.snapshot.outgoing.filter(item => item.peerId !== peerId || item.messageId !== messageId);
    if (outgoing.length !== this.snapshot.outgoing.length) this.commit({ version: 1, outgoing });
  }
  expire(now: number): void {
    timestamp(now);
    const outgoing = this.snapshot.outgoing.filter(item => item.expiresAt > now);
    if (outgoing.length !== this.snapshot.outgoing.length) this.commit({ version: 1, outgoing });
  }
  private quota(snapshot: MessageSnapshot): void {
    if (snapshot.outgoing.length > MESSAGE_LIMITS.count || byteLength(json(snapshot)) > MESSAGE_LIMITS.bytes) throw new Error("Message outbox full");
  }
  private commit(snapshot: MessageSnapshot): void {
    this.storage.commit(copy(snapshot)); this.snapshot = snapshot;
  }
}

export interface InboxRecord { message: QueuedMessage; applied: boolean }
export interface InboxSnapshot { version: 1; records: InboxRecord[] }
export interface InboxStorage {
  load(): InboxSnapshot | undefined;
  commit(snapshot: InboxSnapshot): void;
}
/** peerId is the authenticated sender in this store (recipient in MessageOutbox).
 * Applied receipts remain until expiry so a lost ACK cannot replay application work.
 * Application side effects must use messageId as their own idempotency key: a crash
 * between the side effect and markApplied can still cause redelivery.
 */
export class MessageInbox {
  private snapshot: InboxSnapshot;
  constructor(private readonly storage: InboxStorage) {
    this.snapshot = copy(storage.load() ?? { version: 1, records: [] });
    if (this.snapshot.version !== 1 || !Array.isArray(this.snapshot.records)) throw new Error("Invalid inbox snapshot");
    const identities = new Set<string>();
    for (const record of this.snapshot.records) {
      validate(record.message);
      if (typeof record.applied !== "boolean") throw new Error("Invalid inbox receipt");
      const key = JSON.stringify([record.message.peerId, record.message.messageId]);
      if (identities.has(key)) throw new Error("Duplicate inbox identity");
      identities.add(key);
    }
    this.quota(this.snapshot);
  }
  receive(message: QueuedMessage, now: number): "pending" | "applied" | "expired" {
    validate(message); timestamp(now);
    if (message.expiresAt <= now) return "expired";
    const prior = this.snapshot.records.find(record => record.message.peerId === message.peerId && record.message.messageId === message.messageId);
    if (prior) {
      if (json(prior.message) !== json(message)) throw new Error("Message identity reused with different content");
      return prior.applied ? "applied" : "pending";
    }
    const next: InboxSnapshot = { version: 1, records: [...this.snapshot.records.filter(record => record.message.expiresAt > now), { message: copy(message), applied: false }] };
    this.quota(next); this.commit(next);
    return "pending";
  }
  pending(now: number): QueuedMessage[] {
    timestamp(now);
    return copy(this.snapshot.records.filter(record => !record.applied && record.message.expiresAt > now).map(record => record.message));
  }
  /** Return value authorizes an applied ACK only after commit succeeds. */
  markApplied(peerId: string, messageId: string): { peerId: string; messageId: string } {
    checkId(peerId); checkId(messageId);
    const next = copy(this.snapshot);
    const record = next.records.find(record => record.message.peerId === peerId && record.message.messageId === messageId);
    if (!record) throw new Error("Unknown inbox message");
    if (!record.applied) { record.applied = true; this.commit(next); }
    return { peerId, messageId };
  }
  expire(now: number): void {
    timestamp(now);
    const records = this.snapshot.records.filter(record => record.message.expiresAt > now);
    if (records.length !== this.snapshot.records.length) this.commit({ version: 1, records });
  }
  private quota(snapshot: InboxSnapshot): void {
    if (snapshot.records.length > MESSAGE_LIMITS.count || byteLength(json(snapshot)) > MESSAGE_LIMITS.bytes) throw new Error("Message inbox full");
  }
  private commit(snapshot: InboxSnapshot): void {
    this.storage.commit(copy(snapshot)); this.snapshot = snapshot;
  }
}
