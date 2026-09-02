import { afterEach, describe, expect, test } from "bun:test";

const events: unknown[] = [];
const effects: string[] = [];
const values = new Map<string, string>();

(globalThis as { pod?: unknown }).pod = {
  takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  emit: (line: string) => effects.push(line),
  displayMetrics: () => JSON.stringify({
    logicalWidth: 240, logicalHeight: 240, physicalWidth: 466, physicalHeight: 466,
    density: 1.9416667, shape: "round", safeInsets: { top: 8, right: 8, bottom: 8, left: 8 },
  }),
  capabilities: () => JSON.stringify(["input.touch", "data.kv"]),
  kvGet: (key: string) => values.get(key),
  kvSet: (key: string, value: string) => (values.set(key, value), 0),
  kvDelete: (key: string) => values.delete(key) ? 0 : 1,
  kvKeys: () => JSON.stringify([...values.keys()].sort()),
};

const api = await import("../src/watch.ts");
const frame = await import("../../../vendor/pocketjs/framework/src/frame.ts");
const services = await import("@pocketjs/framework/services");

afterEach(() => { events.length = 0; effects.length = 0; values.clear(); });

describe("watch API", () => {
  test("normalizes display metrics", () => {
    expect(api.getDisplayMetrics()).toMatchObject({ physicalWidth: 466, shape: "round" });
  });

  test("exposes only host-declared capabilities", () => {
    expect(api.capabilities()).toEqual(["input.touch", "data.kv"]);
    expect(api.hasCapability("data.kv")).toBeTrue();
    expect(api.hasCapability("net.http")).toBeFalse();
  });

  test("delivers signed rotary motion at a frame boundary", () => {
    const seen: number[] = [];
    const stop = api.onAxisDelta(api.RelativeAxis.Primary, delta => seen.push(delta));
    events.push({ t: "axis", axis: 0, delta: -1250 });
    api.__pumpPodEvents();
    stop();
    expect(seen).toEqual([-1250]);
  });

  test("keeps the host event pump across application frame-hook reset", () => {
    const seen: number[] = [];
    const stop = api.onAxisDelta(api.RelativeAxis.Primary, delta => seen.push(delta));
    frame.resetFrameHooks();
    events.push({ t: "axis", axis: 0, delta: 12_000 });
    services.runServicePumps();
    stop();
    expect(seen).toEqual([12_000]);
  });

  test("emits typed haptics and persists JSON KV values", () => {
    api.haptics.perform("success");
    api.kv.set("counter", { n: 3 });
    expect(JSON.parse(effects[0])).toEqual({ t: "haptic", kind: "success" });
    expect(api.kv.get<{ n: number }>("counter")).toEqual({ n: 3 });
    expect(api.kv.keys()).toEqual(["counter"]);
    expect(api.kv.delete("counter")).toBeTrue();
  });

  test("validates UTF-8 KV key length without browser encoding globals", () => {
    const encoder = globalThis.TextEncoder;
    try {
      Object.defineProperty(globalThis, "TextEncoder", { value: undefined, configurable: true });
      expect(() => api.kv.set("😀".repeat(32), 1)).not.toThrow();
      expect(() => api.kv.set("中".repeat(43), 1)).toThrow("1..128 UTF-8 bytes");
    } finally {
      Object.defineProperty(globalThis, "TextEncoder", { value: encoder, configurable: true });
    }
  });
});
