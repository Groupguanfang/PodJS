import { __onHostEvent, hasCapability } from "./watch.ts";
import type { PodCapabilityId } from "./targets.ts";
import { platformMethods } from "./platform-contract.ts";

export type SqlValue = string | number | null;
export interface SqlStatement { sql: string; params?: readonly SqlValue[] }
export class HostServiceError extends Error {
  constructor(readonly code: string, message: string) { super(message); this.name = "HostServiceError"; }
}
export interface HostOperation<T> { result: Promise<T>; cancel(): void }
const pending = new Map<number, { resolve(value: unknown): void; reject(error: Error): void }>();
let nextId = 1;
const methods: Record<string, PodCapabilityId> = {
  ...platformMethods,
  "runtime.delay": "runtime.timer",
  "http.download": "net.download",
  "browser.open": "system.browser",
  "audio.start": "media.audio", "audio.pause": "media.audio", "audio.resume": "media.audio", "audio.stop": "media.audio", "audio.state": "media.audio",
  "tts.init": "media.tts", "tts.speak": "media.tts", "tts.stop": "media.tts", "tts.state": "media.tts",
  "video.prepare": "media.video", "video.play": "media.video", "video.pause": "media.video", "video.seek": "media.video", "video.stop": "media.video", "video.setBounds": "media.video", "video.state": "media.video",
  "video.captureBackdrop": "media.video", "video.setTransform": "media.video", "video.setVolume": "media.video",
  "image.decode": "data.images", "browser.authorize": "system.browser.auth",
  "sql.query": "data.sqlite", "sql.execute": "data.sqlite", "sql.transaction": "data.sqlite",
  "input.text": "input.text", "secure.get": "data.secure", "secure.set": "data.secure", "secure.delete": "data.secure",
  "crypto.rsaOaepSha256": "crypto.basic", "crypto.random": "crypto.basic", "crypto.hash": "crypto.basic", "crypto.encrypt": "crypto.basic", "crypto.decrypt": "crypto.basic",
  "file.read": "data.files.chunks", "file.write": "data.files.chunks", "file.stat": "data.files.chunks", "file.delete": "data.files.chunks",
};
function emit(value: unknown): void {
  const host = (globalThis as unknown as { pod?: { emit(line: string): void } }).pod;
  if (!host) throw new HostServiceError("unavailable", "PodJS host is unavailable");
  host.emit(JSON.stringify(value));
}
__onHostEvent(event => {
  if (event.t !== "service.result" || typeof event.id !== "number") return;
  const request = pending.get(event.id);
  if (!request) return;
  pending.delete(event.id);
  if (event.ok === true) request.resolve(event.value);
  else request.reject(new HostServiceError(String(event.code ?? "host_error"), String(event.message ?? "Host operation failed")));
});
/** Cancellation stops delivery; a mutation already committed by the host is not undone. */
export function hostOperation<T>(method: string, args: Record<string, unknown>): HostOperation<T> {
  const id = nextId++;
  const result = new Promise<T>((resolve, reject) => {
    const capability = methods[method];
    if (!capability || !hasCapability(capability)) { reject(new HostServiceError("unsupported", `Unsupported host service: ${method}`)); return; }
    if (pending.size >= 64) { reject(new HostServiceError("busy", "Too many pending host operations")); return; }
    pending.set(id, { resolve: value => resolve(value as T), reject });
    try { emit({ t: "service.request", version: 1, id, method, args }); }
    catch (error) { pending.delete(id); reject(error); }
  });
  return { result, cancel() {
    const request = pending.get(id);
    if (!request) return;
    pending.delete(id);
    request.reject(new HostServiceError("cancelled", "Operation cancelled"));
    emit({ t: "service.cancel", version: 1, id });
  } };
}
export const sqlite = {
  query(sql: string, params: readonly SqlValue[] = []): Promise<Record<string, unknown>[]> {
    return hostOperation<Record<string, unknown>[]>("sql.query", { sql, params }).result;
  },
  async execute(sql: string, params: readonly SqlValue[] = []): Promise<void> {
    await hostOperation("sql.execute", { sql, params }).result;
  },
  async transaction(statements: readonly SqlStatement[]): Promise<void> {
    await hostOperation("sql.transaction", { statements }).result;
  },
};
export const textInput = {
  prompt(title: string, value = ""): HostOperation<string | null> {
    return hostOperation("input.text", { title, value });
  },
};

export const secureStorage = {
  async get(key: string): Promise<string | undefined> { const value = await hostOperation<{exists:boolean;value?:string}>("secure.get",{key}).result; return value.exists ? value.value : undefined; },
  async set(key: string,value: string): Promise<void> { await hostOperation("secure.set",{key,value}).result; },
  async delete(key: string): Promise<void> { await hostOperation("secure.delete",{key}).result; },
};
export const audio = {
  play(url: string): HostOperation<unknown> { return hostOperation("audio.start",{url}); },
  pause(): Promise<unknown> { return hostOperation("audio.pause",{}).result; },
  resume(): Promise<unknown> { return hostOperation("audio.resume",{}).result; },
  stop(): Promise<unknown> { return hostOperation("audio.stop",{}).result; },
  state(): Promise<{state:string;positionMs:number;durationMs:number}> { return hostOperation<{state:string;positionMs:number;durationMs:number}>("audio.state",{}).result; },
};
export const speech = {
  speak(text: string,language="zh-CN"): HostOperation<unknown> { return hostOperation("tts.speak",{text,language}); },
  stop(): Promise<unknown> { return hostOperation("tts.stop",{}).result; },
};
export interface VideoBounds { x:number; y:number; width:number; height:number; translateX?:number; visible?:boolean; roundClip?:{cx:number;cy:number;radius:number} }
export interface VideoPrepare { url?:string; videoUrl?:string; audioUrl?:string; type?:string; headers?:Record<string,string>; bounds:VideoBounds; autoplay?:boolean; muted?:boolean; loop?:boolean; backgroundPlayback?:boolean }
export interface VideoState { ok:boolean; state:string; positionMs:number; durationMs:number; width:number; height:number; volume:number; volumeMax:number }
export const video = {
  prepare(options: VideoPrepare): HostOperation<VideoState> { return hostOperation<VideoState>("video.prepare", options as unknown as Record<string,unknown>); },
  play(): Promise<VideoState> { return hostOperation<VideoState>("video.play", {}).result; },
  pause(): Promise<VideoState> { return hostOperation<VideoState>("video.pause", {}).result; },
  seek(positionMs:number): Promise<VideoState> { return hostOperation<VideoState>("video.seek", {positionMs}).result; },
  stop(): Promise<VideoState> { return hostOperation<VideoState>("video.stop", {}).result; },
  setBounds(bounds:VideoBounds): Promise<VideoState> { return hostOperation<VideoState>("video.setBounds", bounds as unknown as Record<string,unknown>).result; },
  state(): Promise<VideoState> { return hostOperation<VideoState>("video.state", {}).result; },
  captureBackdrop(): Promise<{ok:boolean}> { return hostOperation<{ok:boolean}>("video.captureBackdrop", {}).result; },
  setTransform(transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}): Promise<VideoState> { return hostOperation<VideoState>("video.setTransform", transform as Record<string,unknown>).result; },
  setVolume(volume:number): Promise<VideoState> { return hostOperation<VideoState>("video.setVolume", {volume}).result; },
};

export interface DownloadResult { status:number; headers:Record<string,string>; url:string; path:string; size:number }
export const http = {
  download(url:string,path:string,maxBytes=16*1024*1024): HostOperation<DownloadResult> { return hostOperation("http.download",{url,path,maxBytes}); },
};
function browserUrl(url: string): string {
  if (typeof url !== "string" || url.length === 0 || url.length > 8192) throw new HostServiceError("invalid_argument", "Browser URL must be a non-empty HTTP or HTTPS URL");
  const match = /^(https?):\/\/([^/\\?#:]+)(?::\d{1,5})?(?:[/?#].*)?$/i.exec(url);
  if (!match || /[\u0000-\u0020]/.test(url)) throw new HostServiceError("invalid_argument", "Browser URL must be a valid HTTP or HTTPS URL");
  return url;
}
export const browser = {
  open(url: string): HostOperation<void> { return hostOperation<void>("browser.open", { url: browserUrl(url) }); },
};
export const browserAuth = {
  authorize(args: {url:string; cookieOrigin:string; requiredCookieNames:readonly string[]; userAgent?:string}): HostOperation<{cookie:string;origin:string}> {
    return hostOperation("browser.authorize", args as unknown as Record<string,unknown>);
  },
};
export const images = {
  decode(path:string, maxDimension=512): Promise<{path:string;width:number;height:number}> {
    return hostOperation<{path:string;width:number;height:number}>("image.decode", {path, maxDimension}).result;
  },
};
export const fileChunks = {
  read(path:string,offset=0,maxBytes=65536): Promise<{dataBase64:string;offset:number;size:number;eof:boolean}> {
    return hostOperation<{dataBase64:string;offset:number;size:number;eof:boolean}>("file.read",{path,offset,maxBytes}).result;
  },
  async remove(path:string): Promise<void> { await hostOperation("file.delete",{path}).result; },
};
export { bytesToBase64, base64ToBytes, utf8ToString } from "../../../vendor/pocketjs/framework/src/bytes.ts";

/** Wall-clock delay driven by the host, independent of UI frame cadence. */
export function delay(milliseconds:number):HostOperation<void> { return hostOperation<void>("runtime.delay",{milliseconds}); }

/** Public-key RSA OAEP with SHA-256, MGF1 SHA-256 and an empty label. */
export function rsaOaepSha256(publicKeyPem:string,inputBase64:string):HostOperation<{ciphertextBase64:string}>{return hostOperation("crypto.rsaOaepSha256",{publicKeyPem,inputBase64});}
