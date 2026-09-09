import { CompanionSyncConnector } from './CompanionSyncAttempt';
import { CompanionSyncCrypto } from './CompanionSyncAuth';
import { CompanionSyncPacketStream } from './CompanionSyncExchange';
import { CompanionByteWriter, CompanionPacketStream, CompanionStreamTimer } from './CompanionPacketStream';
import { decodeSyncUtf8 } from './CompanionStatePump';

/** Implementations must retain CA verification and TLS >=1.2. No disable flag. */
export interface CompanionTlsSocket extends CompanionByteWriter {
  listen(receive: (bytes: Uint8Array) => void, end: () => void, error: (error: Error) => void): void;
  connect(host: string, port: number, ca: string, timeoutMs: number): Promise<void>;
  certificateDer(): Promise<Uint8Array>;
}
export interface CompanionTlsSocketFactory { create(): CompanionTlsSocket; }

/** Normalize one TLS leaf certificate for a standard SHA256 DER fingerprint. */
export function companionCertificateDer(data: Uint8Array, format: string): Uint8Array {
  if (data.length < 1 || data.length > 65536) throw new Error('invalid TLS certificate size');
  if (format === 'der') return data.slice();
  if (format !== 'pem') throw new Error('unsupported TLS certificate encoding');
  const text = decodeSyncUtf8(Array.from(data), 65536).trim();
  const match = /^-----BEGIN CERTIFICATE-----\s+([A-Za-z0-9+/=\r\n\t ]+)\s+-----END CERTIFICATE-----$/.exec(text);
  if (match === null) throw new Error('invalid TLS certificate PEM');
  const body = match[1].replace(/\s/g, '');
  if (body.length === 0 || body.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(body)) throw new Error('invalid certificate base64');
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const result: number[] = [];
  for (let i = 0; i < body.length; i += 4) {
    const a = alphabet.indexOf(body[i]), b = alphabet.indexOf(body[i + 1]);
    const c = body[i + 2] === '=' ? 0 : alphabet.indexOf(body[i + 2]);
    const d = body[i + 3] === '=' ? 0 : alphabet.indexOf(body[i + 3]);
    if (body[i + 2] === '=' && (b & 15) !== 0 || body[i + 3] === '=' && body[i + 2] !== '=' && (c & 3) !== 0)
      throw new Error('noncanonical certificate base64');
    result.push((a << 2) | (b >> 4));
    if (body[i + 2] !== '=') result.push(((b & 15) << 4) | (c >> 2));
    if (body[i + 3] !== '=') result.push(((c & 3) << 6) | d);
  }
  return new Uint8Array(result);
}

/** Explicit endpoint plus CA and previously approved leaf fingerprint. Never
 * learn a pin from this connection. Use within CompanionSyncAttempt to bound
 * native connect/certificate/hash calls and perform the application HMAC. */
export class CompanionTlsConnector implements CompanionSyncConnector {
  private factory: CompanionTlsSocketFactory;
  private crypto: CompanionSyncCrypto;
  private timer: CompanionStreamTimer;
  private host: string;
  private port: number;
  private ca: string;
  private pin: string;
  private lifetime: number;
  private socket: CompanionTlsSocket | null = null;
  private stream: CompanionPacketStream | null = null;
  private used: boolean = false;
  private closed: boolean = false;
  constructor(factory: CompanionTlsSocketFactory, crypto: CompanionSyncCrypto, timer: CompanionStreamTimer,
    host: string, port: number, ca: string, leafSha256: string, lifetimeMs: number) {
    if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid TLS endpoint');
    if (ca.length > 65536 || !ca.includes('-----BEGIN CERTIFICATE-----') || !ca.includes('-----END CERTIFICATE-----')) throw new Error('approved TLS CA required');
    if (!/^[0-9a-f]{64}$/.test(leafSha256)) throw new Error('approved TLS leaf fingerprint required');
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid TLS lifetime');
    this.factory = factory; this.crypto = crypto; this.timer = timer; this.host = host; this.port = port;
    this.ca = ca; this.pin = leafSha256; this.lifetime = lifetimeMs;
  }
  cancel(): void {
    this.closed = true;
    try { if (this.stream !== null) this.stream.close(); } catch (_) {}
    try { if (this.socket !== null) this.socket.close(); } catch (_) {}
  }
  private check(): void { if (this.closed) throw new Error('TLS connector closed'); }
  async connect(): Promise<CompanionSyncPacketStream> {
    this.check(); if (this.used) throw new Error('TLS connector already used'); this.used = true;
    try {
      const socket = this.factory.create(); this.socket = socket;
      const writer: CompanionByteWriter = {
        send: async (bytes: Uint8Array) => { this.check(); await socket.send(bytes); this.check(); },
        close: () => { this.closed = true; socket.close(); }
      };
      const stream = new CompanionPacketStream(writer, this.timer, this.lifetime); this.stream = stream;
      socket.listen((bytes: Uint8Array) => stream.receive(bytes), () => stream.end(), (error: Error) => stream.fail(error));
      await socket.connect(this.host, this.port, this.ca, this.lifetime); this.check();
      const certificate = await socket.certificateDer(); this.check();
      if (certificate.length < 1 || certificate.length > 65536) throw new Error('invalid TLS leaf certificate');
      const digest = await this.crypto.sha256(certificate.slice()); this.check();
      if (digest.length !== 32) throw new Error('invalid TLS fingerprint digest');
      let actual = ''; for (const byte of digest) actual += byte.toString(16).padStart(2, '0');
      if (actual !== this.pin) throw new Error('TLS leaf fingerprint mismatch');
      return stream;
    } catch (error) { this.cancel(); throw error; }
  }
}
