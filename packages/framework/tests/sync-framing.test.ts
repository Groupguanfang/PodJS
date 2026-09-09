import { expect, test } from "bun:test";
import { encodeSyncFrame, fragmentSyncFrame, SyncFrameDecoder, MAX_SYNC_FRAME_BYTES } from "../src/sync-framing.ts";
test("BLE minimum ATT budget and arbitrary stream splits preserve binary bytes", () => {
  const source = Uint8Array.from({ length: 65536 }, (_, i) => i % 256);
  for (const budget of [1, 20, 182, 244, 4096]) {
    const decoder = new SyncFrameDecoder(), received: Uint8Array[] = [];
    for (const fragment of fragmentSyncFrame(encodeSyncFrame(source), budget)) decoder.push(fragment, frame => received.push(frame));
    decoder.finish(); expect(received).toEqual([source]);
  }
});
test("coalesced RFCOMM/LAN frames decode individually", () => {
  const a = encodeSyncFrame(new Uint8Array([0, 255])), b = encodeSyncFrame(new Uint8Array([42]));
  const bytes = new Uint8Array(a.length + b.length); bytes.set(a); bytes.set(b, a.length);
  const received: Uint8Array[] = [], decoder = new SyncFrameDecoder();
  decoder.push(bytes, frame => received.push(frame)); decoder.finish();
  expect(received).toEqual([new Uint8Array([0, 255]), new Uint8Array([42])]);
});
test("length attacks fail before payload allocation and poison connection", () => {
  for (const size of [0, MAX_SYNC_FRAME_BYTES + 1, 0xffffffff]) {
    const bytes = new Uint8Array(4); new DataView(bytes.buffer).setUint32(0, size, false);
    const decoder = new SyncFrameDecoder();
    expect(() => decoder.push(bytes, () => {})).toThrow("length");
    expect(() => decoder.push(encodeSyncFrame(new Uint8Array([1])), () => {})).toThrow("new connection");
  }
});
test("disconnect mid header or payload never delivers a partial frame", () => {
  for (const length of [1, 3, 4, 5]) {
    const decoder = new SyncFrameDecoder(); let count = 0;
    decoder.push(encodeSyncFrame(new Uint8Array([1, 2])).slice(0, length), () => count++);
    expect(() => decoder.finish()).toThrow("Truncated"); expect(count).toBe(0);
  }
});
test("clean EOF is terminal even for empty input and repeated EOF is safe", () => {
  const decoder = new SyncFrameDecoder();
  decoder.finish(); decoder.finish();
  expect(() => decoder.push(new Uint8Array(), () => {})).toThrow("new connection");
  expect(() => decoder.push(encodeSyncFrame(new Uint8Array([1])), () => {})).toThrow("new connection");
});
test("delivery exceptions poison the connection", () => {
  const decoder = new SyncFrameDecoder();
  expect(() => decoder.push(encodeSyncFrame(new Uint8Array([1])), () => { throw new Error("delivery failed"); })).toThrow("delivery failed");
  expect(() => decoder.push(new Uint8Array(), () => {})).toThrow("new connection");
  expect(() => decoder.finish()).toThrow("invalid");
});
test("caught reentrant push or finish still stops coalesced frame delivery", () => {
  for (const operation of ["push", "finish"]) {
    const decoder = new SyncFrameDecoder(), frame = encodeSyncFrame(new Uint8Array([1]));
    const joined = new Uint8Array(frame.length * 2); joined.set(frame); joined.set(frame, frame.length);
    let delivered = 0;
    expect(() => decoder.push(joined, () => {
      delivered++;
      try {
        if (operation === "push") decoder.push(frame, () => { delivered++; });
        else decoder.finish();
      } catch { /* Deliberately swallow the nested error. */ }
    })).toThrow("new connection");
    expect(delivered).toBe(1);
    expect(() => decoder.push(frame, () => {})).toThrow("new connection");
    expect(() => decoder.finish()).toThrow("invalid");
  }
});
