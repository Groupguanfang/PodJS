import { CompanionSyncSigner } from './CompanionSyncAuth';

/** Must hold an exclusive OS lease for this app for the entire object's lifetime,
 * including asynchronous keystore work. A CAS alone is not such a lease. */
export interface CompanionPairingLease {
  assertOwned(): void;
  read(): Promise<string | null>;
  compareExchange(expected: string | null, desired: string): Promise<boolean>;
  close(): void;
}
export interface CompanionPairingKeys {
  exists(id: string): Promise<boolean>;
  importKey(id: string, key: Uint8Array): Promise<void>;
  remove(id: string): Promise<void>;
  sign(id: string, bytes: Uint8Array): Promise<Uint8Array>;
}
export interface CompanionPairingRandom { challenge(): Promise<Uint8Array>; }
export class CompanionPairingInfo {
  readonly peer: string;
  readonly phase: string;
  constructor(peer: string, phase: string) { this.peer = peer; this.phase = phase; }
}
export interface CompanionPairingSigner extends CompanionSyncSigner {
  /** Release this connection's handle without revoking the durable pairing. */
  close(): void;
}
class PairingRecord {
  peer: string = '';
  keyId: string = '';
  phase: string = 'importing';
}
class PairingSnapshot {
  schema: number = 1;
  app: string = '';
  local: string = '';
  records: PairingRecord[] = [];
}
class PairingHandle implements CompanionPairingSigner {
  valid: boolean = true;
  constructor(private owner: CompanionPairings, readonly peer: string, readonly keyId: string,
    private disconnect: () => void) {}
  matchesIdentity(app: string, local: string, peer: string): boolean {
    return this.valid && peer === this.peer && this.owner.matchesIdentity(app, local);
  }
  sign(bytes: Uint8Array): Promise<Uint8Array> { return this.owner.signWithHandle(this, bytes); }
  close(): void { this.invalidate(); }
  invalidate(): void {
    if (!this.valid) return;
    this.valid = false;
    try { this.disconnect(); } catch (_) { /* Invalid even if transport cleanup fails. */ }
  }
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid pairing identity');
}

/** Host-only approved credential lifecycle. This does not perform initial pairing
 * or authorize a received key: importApproved requires prior user approval and an
 * authenticated out-of-band exchange. No key material enters the journal. */
export class CompanionPairings {
  private closed: boolean = false;
  private tail: Promise<void> = Promise.resolve();
  private handles: PairingHandle[] = [];
  constructor(private app: string, private local: string, private lease: CompanionPairingLease,
    private keys: CompanionPairingKeys, private random: CompanionPairingRandom) {
    identity(app); identity(local); lease.assertOwned();
  }
  matchesIdentity(app: string, local: string): boolean { return !this.closed && app === this.app && local === this.local; }
  private check(): void { if (this.closed) throw new Error('pairing owner closed'); this.lease.assertOwned(); }
  private run<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(async () => { this.check(); return operation(); });
    this.tail = result.then(() => {}, () => {}); return result;
  }
  private load(raw: string | null): PairingSnapshot {
    if (raw === null) { const fresh = new PairingSnapshot(); fresh.app = this.app; fresh.local = this.local; return fresh; }
    if (raw.length > 65536) throw new Error('pairing snapshot too large');
    const snapshot = JSON.parse(raw) as PairingSnapshot;
    if (!snapshot || snapshot.schema !== 1 || snapshot.app !== this.app || snapshot.local !== this.local ||
      !Array.isArray(snapshot.records) || snapshot.records.length > 64) throw new Error('invalid pairing snapshot');
    const peers: string[] = [], ids: string[] = [];
    for (const record of snapshot.records) {
      if (!record) throw new Error('invalid pairing record');
      identity(record.peer);
      if (record.peer === this.local || typeof record.keyId !== 'string' || !/^[0-9a-f]{64}$/.test(record.keyId) ||
        !['importing', 'approved', 'revoking'].includes(record.phase) || peers.includes(record.peer) || ids.includes(record.keyId))
        throw new Error('invalid pairing record');
      peers.push(record.peer); ids.push(record.keyId);
    }
    return snapshot;
  }
  private async save(raw: string | null, snapshot: PairingSnapshot): Promise<void> {
    this.check();
    if (!await this.lease.compareExchange(raw, JSON.stringify(snapshot))) throw new Error('pairing storage conflict');
    this.check();
  }
  private invalidate(peer?: string): void {
    for (const handle of this.handles) if (peer === undefined || handle.peer === peer) handle.invalidate();
    this.handles = this.handles.filter((handle: PairingHandle) => handle.valid);
  }
  /** Detached host-facing metadata; HUKS aliases and key material stay private. */
  list(): Promise<CompanionPairingInfo[]> {
    return this.run(async () => {
      const snapshot = this.load(await this.lease.read()); this.check();
      return snapshot.records.map((record: PairingRecord) => new CompanionPairingInfo(record.peer, record.phase));
    });
  }
  /** Closing invalidates immediately, but releases the OS lease only after all
   * in-flight keystore operations settle, so a new owner cannot race them. */
  async close(): Promise<void> {
    this.closed = true; this.invalidate(); await this.tail; this.lease.close();
  }
  importApproved(peer: string, key: Uint8Array): Promise<void> {
    identity(peer);
    if (peer === this.local || key.length !== 32 || !key.some((byte: number) => byte !== 0)) throw new Error('invalid pairing key');
    const temporary = key.slice();
    return this.run(async () => {
      const raw = await this.lease.read(), snapshot = this.load(raw);
      if (snapshot.records.some((record: PairingRecord) => record.peer === peer)) throw new Error('pairing already exists');
      if (snapshot.records.length >= 64) throw new Error('pairing limit reached');
      const random = await this.random.challenge(); this.check();
      if (random.length !== 32 || !random.some((byte: number) => byte !== 0)) throw new Error('invalid pairing randomness');
      let id = ''; for (const byte of random) id += byte.toString(16).padStart(2, '0');
      if (snapshot.records.some((record: PairingRecord) => record.keyId === id) || await this.keys.exists(id)) throw new Error('pairing key ID collision');
      const record = new PairingRecord(); record.peer = peer; record.keyId = id;
      snapshot.records.push(record); await this.save(raw, snapshot);
      const pending = JSON.stringify(snapshot);
      await this.keys.importKey(id, temporary); this.check();
      record.phase = 'approved'; await this.save(pending, snapshot);
    }).finally(() => { temporary.fill(0); });
  }
  /** Interrupted imports are never promoted to approved on restart. */
  recover(): Promise<void> {
    return this.run(async () => {
      let raw = await this.lease.read(); const snapshot = this.load(raw);
      const pending = snapshot.records.filter((record: PairingRecord) => record.phase !== 'approved');
      for (const record of pending) {
        this.invalidate(record.peer); await this.keys.remove(record.keyId); this.check();
        snapshot.records = snapshot.records.filter((item: PairingRecord) => item !== record);
        await this.save(raw, snapshot); raw = JSON.stringify(snapshot);
      }
    });
  }
  revoke(peer: string): Promise<void> {
    identity(peer); this.invalidate(peer);
    return this.run(async () => {
      this.invalidate(peer);
      const raw = await this.lease.read(), snapshot = this.load(raw);
      const record = snapshot.records.find((item: PairingRecord) => item.peer === peer);
      if (!record) return;
      record.phase = 'revoking'; await this.save(raw, snapshot);
      const pending = JSON.stringify(snapshot);
      await this.keys.remove(record.keyId); this.check();
      snapshot.records = snapshot.records.filter((item: PairingRecord) => item !== record);
      await this.save(pending, snapshot);
    });
  }
  openSigner(peer: string, disconnect: () => void): Promise<CompanionPairingSigner> {
    identity(peer);
    return this.run(async () => {
      const snapshot = this.load(await this.lease.read());
      const record = snapshot.records.find((item: PairingRecord) => item.peer === peer && item.phase === 'approved');
      if (!record || !await this.keys.exists(record.keyId)) throw new Error('pairing not approved');
      this.check();
      this.handles = this.handles.filter((handle: PairingHandle) => handle.valid);
      if (this.handles.length >= 64) throw new Error('pairing signer limit reached');
      const handle = new PairingHandle(this, peer, record.keyId, disconnect); this.handles.push(handle); return handle;
    });
  }
  signWithHandle(handle: PairingHandle, bytes: Uint8Array): Promise<Uint8Array> {
    const input = bytes.slice();
    return this.run(async () => {
      const checkRecord = async (): Promise<void> => {
        this.check();
        if (!handle.valid || !this.handles.includes(handle)) throw new Error('pairing signer revoked');
        const snapshot = this.load(await this.lease.read());
        if (!snapshot.records.some((record: PairingRecord) => record.peer === handle.peer && record.keyId === handle.keyId && record.phase === 'approved'))
          throw new Error('pairing not approved');
        this.check(); if (!handle.valid) throw new Error('pairing signer revoked');
      };
      try {
        await checkRecord(); const result = await this.keys.sign(handle.keyId, input); await checkRecord();
        if (result.length !== 32) throw new Error('invalid pairing signature');
        return result.slice();
      } catch (error) { handle.invalidate(); throw error; }
    });
  }
}
