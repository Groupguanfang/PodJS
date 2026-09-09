import { CompanionSyncBinding, CompanionSyncCrypto, CompanionSyncSession, CompanionSyncSigner, copyCompanionSyncKey, clearCompanionSyncKey } from './CompanionSyncAuth';
import { decodeSyncUtf8, encodeSyncUtf8 } from './CompanionStatePump';

/** Packet payloads without the length prefix. Implementations must impose a
 * handshake deadline and make close interrupt pending reads/writes. */
export interface CompanionSyncPacketStream {
  read(): Promise<Uint8Array | null>;
  write(packet: Uint8Array): Promise<void>;
  close(): void;
}
export interface CompanionSyncChallengeCrypto extends CompanionSyncCrypto {
  challenge(): Promise<Uint8Array>;
}
class Hello {
  type: string = 'hello';
  protocolVersion: number = 1;
  appId: string;
  sender: string;
  recipient: string;
  initiator: boolean;
  nonce: number[];
  constructor(app: string, sender: string, recipient: string, initiator: boolean, nonce: number[]) {
    this.appId = app; this.sender = sender; this.recipient = recipient;
    this.initiator = initiator; this.nonce = nonce.slice();
  }
}
class Proof {
  type: string = 'proof';
  proof: number[];
  constructor(proof: Uint8Array) { this.proof = Array.from(proof); }
}
function identity(id: string): void {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) throw new Error('invalid identity');
}
function bytes32(bytes: number[]): void {
  if (!Array.isArray(bytes) || bytes.length !== 32) throw new Error('invalid challenge or proof');
  for (let i = 0; i < 32; i++) if (!Number.isInteger(bytes[i]) || bytes[i] < 0 || bytes[i] > 255)
    throw new Error('invalid challenge or proof');
}
/** JSON grammar is validated first. Count decoded root keys separately so JSON
 * duplicate keys (including escaped aliases) cannot silently become last-wins. */
export function decodeSyncObject(packet: Uint8Array, maximum: number): Object {
  const text = decodeSyncUtf8(Array.from(packet), maximum);
  const result = JSON.parse(text) as Object;
  if (result === null || typeof result !== 'object' || Array.isArray(result)) throw new Error('expected sync object');
  const keys: string[] = []; let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const character = text[i];
    if (character === '"') {
      const start = i++;
      for (; i < text.length; i++) {
        if (text[i] === '\\') { i++; continue; }
        if (text[i] === '"') break;
      }
      let next = i + 1; while (next < text.length && /\s/.test(text[next])) next++;
      if (depth === 1 && text[next] === ':') {
        const key = JSON.parse(text.slice(start, i + 1)) as string;
        if (keys.includes(key)) throw new Error('duplicate sync field'); keys.push(key);
      }
    } else if (character === '{' || character === '[') depth++;
    else if (character === '}' || character === ']') depth--;
  }
  return result;
}
function exact(value: Object, fields: string[]): void {
  const keys = Object.keys(value);
  if (keys.length !== fields.length || !keys.every((key: string) => fields.includes(key))) throw new Error('invalid handshake fields');
}

/** One-shot Android-compatible hello/proof exchange, not pairing. The supplied
 * key and remote identity must already have host approval. Session ownership is
 * handed to caller only after mutual proof verification and final write. */
export class CompanionSyncExchange {
  private link: CompanionSyncPacketStream;
  private crypto: CompanionSyncChallengeCrypto;
  private used: boolean = false;
  private closed: boolean = false;
  private session: CompanionSyncSession | null = null;
  private key: Uint8Array | CompanionSyncSigner | null = null;
  constructor(link: CompanionSyncPacketStream, crypto: CompanionSyncChallengeCrypto) { this.link = link; this.crypto = crypto; }
  close(): void {
    this.closed = true;
    clearCompanionSyncKey(this.key);
    if (this.session !== null) this.session.close();
    this.link.close();
  }
  private check(): void { if (this.closed) throw new Error('sync exchange closed'); }
  private async read(): Promise<Object> {
    this.check(); const packet = await this.link.read(); this.check();
    if (packet === null) throw new Error('missing handshake frame');
    return decodeSyncObject(packet, 8192);
  }
  private async write(value: Object): Promise<void> {
    this.check(); await this.link.write(new Uint8Array(encodeSyncUtf8(JSON.stringify(value), 8192))); this.check();
  }
  private hello(value: Object, app: string, remote: string, local: string, initiator: boolean): Hello {
    exact(value, ['type', 'protocolVersion', 'appId', 'sender', 'recipient', 'initiator', 'nonce']);
    const hello = value as Hello;
    if (hello.type !== 'hello' || hello.protocolVersion !== 1 || hello.appId !== app ||
      hello.sender !== remote || hello.recipient !== local || hello.initiator !== initiator) throw new Error('peer identity or version mismatch');
    bytes32(hello.nonce); return hello;
  }
  private async authenticate(session: CompanionSyncSession, value: Object): Promise<void> {
    exact(value, ['type', 'proof']); const proof = value as Proof;
    if (proof.type !== 'proof') throw new Error('invalid proof envelope');
    bytes32(proof.proof); await session.authenticate(new Uint8Array(proof.proof)); this.check();
  }
  async establish(app: string, local: string, remote: string, pairingKey: Uint8Array | CompanionSyncSigner,
    initiator: boolean, grantedChannels: string[]): Promise<CompanionSyncSession> {
    this.check(); if (this.used) throw new Error('exchange already used'); this.used = true;
    const grants = grantedChannels.slice();
    try {
      identity(app); identity(local); identity(remote);
      if (local === remote || typeof initiator !== 'boolean') throw new Error('invalid peer roles');
      this.key = copyCompanionSyncKey(pairingKey, app, local, remote);
      if (grants.length < 1 || grants.length > 4 || !grants.every((g: string) => ['state', 'message', 'file', 'ack'].includes(g))) throw new Error('invalid channel grants');
      const nonce = Array.from(await this.crypto.challenge()); this.check(); bytes32(nonce);
      if (!nonce.some((b: number) => b !== 0)) throw new Error('invalid challenge');
      const hello = new Hello(app, local, remote, initiator, nonce);
      let peer: Hello;
      if (initiator) { await this.write(hello); peer = this.hello(await this.read(), app, remote, local, false); }
      else { peer = this.hello(await this.read(), app, remote, local, true); await this.write(hello); }
      const binding = new CompanionSyncBinding(app, initiator ? local : remote, initiator ? remote : local,
        initiator ? nonce : peer.nonce, initiator ? peer.nonce : nonce);
      const session = new CompanionSyncSession(this.key, binding, initiator, grants, this.crypto); this.session = session;
      const proof = new Proof(await session.proof()); this.check();
      if (initiator) { await this.write(proof); await this.authenticate(session, await this.read()); }
      else { await this.authenticate(session, await this.read()); await this.write(proof); }
      this.session = null; return session;
    } catch (error) { this.close(); throw error; }
    finally { clearCompanionSyncKey(this.key); this.key = null; }
  }
}
