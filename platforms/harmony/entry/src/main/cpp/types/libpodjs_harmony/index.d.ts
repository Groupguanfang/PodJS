export interface Preflight { ok: boolean; error?: string }
export const preflight: (target: string, abi: number) => Preflight;
export const boot: (xcomponentId: string) => boolean;
