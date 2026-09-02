export interface Preflight { ok: boolean; error?: string }
export const preflight: (target: string, abi: number) => Preflight;
export const boot: (js: Uint8Array, pak: Uint8Array, manifest: Uint8Array) => boolean;
export const rotary: (millidegrees: number) => boolean;
