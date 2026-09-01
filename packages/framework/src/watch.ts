import { onFrame } from "@pocketjs/framework/lifecycle";
import type { PodCapabilityId } from "./targets.ts";

export const RelativeAxis = Object.freeze({ Primary: 0, Secondary: 1 } as const);
export const RelativeAxisUnits = Object.freeze({ PerDegree: 1_000, PerTurn: 360_000 } as const);
export type RelativeAxisId = (typeof RelativeAxis)[keyof typeof RelativeAxis];
export type LifecycleState = "active" | "inactive" | "background";
export type SystemTheme = "light" | "dark";
export type HapticKind = "click" | "success" | "warning" | "error";

export interface DisplayMetrics {
  readonly logicalWidth: 240;
  readonly logicalHeight: 240;
  readonly physicalWidth: number;
  readonly physicalHeight: number;
  readonly density: number;
  readonly shape: "round" | "rect";
  readonly safeInsets: Readonly<{ top: number; right: number; bottom: number; left: number }>;
}

interface PodHost {
  takeEvents(): string | undefined;
  emit(line: string): void;
  displayMetrics(): string;
  capabilities(): string;
  kvGet(key: string): string | undefined;
  kvSet(key: string, value: string): number;
  kvDelete(key: string): number;
  kvKeys(): string;
}

type AxisHandler = (delta: number) => void;
type LifecycleHandler = (state: LifecycleState) => void;
type ThemeHandler = (theme: SystemTheme) => void;
type BackHandler = () => boolean | void;

const axisHandlers = new Map<RelativeAxisId, Set<AxisHandler>>();
const lifecycleHandlers = new Set<LifecycleHandler>();
const themeHandlers = new Set<ThemeHandler>();
const backHandlers: BackHandler[] = [];
let lifecycleState: LifecycleState = "active";
let themeState: SystemTheme = "dark";

function host(): PodHost | null {
  const value = (globalThis as { pod?: unknown }).pod as Partial<PodHost> | undefined;
  return value && typeof value.takeEvents === "function" ? value as PodHost : null;
}

function subscribe<T>(set: Set<T>, handler: T): () => void {
  set.add(handler);
  return () => set.delete(handler);
}

export function onAxisDelta(axis: RelativeAxisId, handler: AxisHandler): () => void {
  if (axis !== RelativeAxis.Primary && axis !== RelativeAxis.Secondary) {
    throw new Error(`PodJS: unknown relative axis ${axis}`);
  }
  let handlers = axisHandlers.get(axis);
  if (!handlers) axisHandlers.set(axis, handlers = new Set());
  handlers.add(handler);
  return () => handlers?.delete(handler);
}

export function getDisplayMetrics(): DisplayMetrics {
  const raw = host()?.displayMetrics();
  if (!raw) {
    return {
      logicalWidth: 240,
      logicalHeight: 240,
      physicalWidth: 240,
      physicalHeight: 240,
      density: 1,
      shape: "round",
      safeInsets: { top: 0, right: 0, bottom: 0, left: 0 },
    };
  }
  return Object.freeze(JSON.parse(raw) as DisplayMetrics);
}

export function capabilities(): readonly PodCapabilityId[] {
  return Object.freeze(JSON.parse(host()?.capabilities?.() ?? "[]") as PodCapabilityId[]);
}

export function hasCapability(capability: PodCapabilityId): boolean {
  return capabilities().includes(capability);
}

export function lifecycle(): LifecycleState { return lifecycleState; }
export function onLifecycleChange(handler: LifecycleHandler): () => void {
  return subscribe(lifecycleHandlers, handler);
}
export function systemTheme(): SystemTheme { return themeState; }
export function onSystemThemeChange(handler: ThemeHandler): () => void {
  return subscribe(themeHandlers, handler);
}
export function onSystemBack(handler: BackHandler): () => void {
  backHandlers.push(handler);
  return () => {
    const index = backHandlers.lastIndexOf(handler);
    if (index >= 0) backHandlers.splice(index, 1);
  };
}

export const haptics = Object.freeze({
  perform(kind: HapticKind): void {
    if (!["click", "success", "warning", "error"].includes(kind)) {
      throw new Error(`PodJS: unknown haptic kind ${kind}`);
    }
    host()?.emit(JSON.stringify({ t: "haptic", kind }));
  },
});

function validateKvKey(key: string): void {
  let bytes = 0;
  for (let i = 0; i < key.length; i++) {
    const code = key.charCodeAt(i);
    if (code < 0x80) bytes += 1;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && i + 1 < key.length
      && key.charCodeAt(i + 1) >= 0xdc00 && key.charCodeAt(i + 1) <= 0xdfff) {
      bytes += 4; i++;
    } else bytes += 3;
  }
  if (!key || bytes > 128) {
    throw new Error("PodJS KV keys must contain 1..128 UTF-8 bytes");
  }
}

export const kv = Object.freeze({
  get<T = unknown>(key: string): T | undefined {
    validateKvKey(key);
    const value = host()?.kvGet(key);
    return value === undefined ? undefined : JSON.parse(value) as T;
  },
  set(key: string, value: unknown): void {
    validateKvKey(key);
    const encoded = JSON.stringify(value);
    if (encoded === undefined) throw new Error("PodJS KV values must be JSON serializable");
    if (host()?.kvSet(key, encoded) !== 0) throw new Error(`PodJS KV write failed for ${key}`);
  },
  delete(key: string): boolean {
    validateKvKey(key);
    return host()?.kvDelete(key) === 0;
  },
  keys(): string[] {
    return JSON.parse(host()?.kvKeys() ?? "[]") as string[];
  },
});

type PodEvent =
  | { t: "axis"; axis: RelativeAxisId; delta: number }
  | { t: "lifecycle"; state: LifecycleState }
  | { t: "theme"; theme: SystemTheme }
  | { t: "back" };

export function __pumpPodEvents(): void {
  const batch = host()?.takeEvents();
  if (!batch) return;
  const events = JSON.parse(batch) as PodEvent[];
  for (const event of events) {
    if (event.t === "axis" && Number.isInteger(event.delta) && event.delta !== 0) {
      for (const handler of axisHandlers.get(event.axis) ?? []) handler(event.delta);
    } else if (event.t === "lifecycle") {
      lifecycleState = event.state;
      for (const handler of lifecycleHandlers) handler(event.state);
    } else if (event.t === "theme") {
      themeState = event.theme;
      for (const handler of themeHandlers) handler(event.theme);
    } else if (event.t === "back") {
      for (let i = backHandlers.length - 1; i >= 0; i--) {
        if (backHandlers[i]() === true) break;
      }
    }
  }
}

onFrame(__pumpPodEvents);
