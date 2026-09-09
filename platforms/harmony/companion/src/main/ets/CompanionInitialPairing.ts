import { CompanionPairingInvitation } from './CompanionPairingInvitation';
import { CompanionPairingInvitationLease } from './CompanionPairingInvitationLease';
import { CompanionPairings } from './CompanionPairings';
import { CompanionSyncAttempt, CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionSyncCrypto, CompanionSyncFrame } from './CompanionSyncAuth';
import { decodeSyncObject } from './CompanionSyncExchange';

export interface CompanionPairingApproval {
  confirm(app: string, local: string, peer: string, invitationId: string): Promise<boolean>;
  dismiss(): void;
}
class Confirmation {
  type: string = 'podjs-pairing-confirmation';
  version: number = 1;
  invitationId: string;
  phase: string;
  constructor(invitationId: string, phase: string) { this.invitationId = invitationId; this.phase = phase; }
}
function ascii(text: string): Uint8Array {
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) > 127) throw new Error('invalid pairing control encoding');
    bytes[i] = text.charCodeAt(i);
  }
  return bytes;
}

/** Bootstrap-only connection: accepts exactly approval then durable-save
 * confirmation, never application messages. QR possession is authenticated by
 * the existing challenge/HMAC exchange; both hosts must separately approve.
 * Permanent keys are domain-separated from the QR key and bound to this session.
 * No distributed transaction is possible: an interrupted final acknowledgement
 * may leave one/both hosts paired. Inspect local records; do not auto-overwrite. */
export class CompanionInitialPairing {
  private used: boolean = false;
  private finished: boolean = false;
  private connection: CompanionSyncConnection | null = null;
  private reject: ((error: Error) => void) | null = null;
  private mayHaveCredential: boolean = false;
  constructor(private invitation: CompanionPairingInvitation, private lease: CompanionPairingInvitationLease,
    private pairings: CompanionPairings, private attempt: CompanionSyncAttempt,
    private crypto: CompanionSyncCrypto, private approval: CompanionPairingApproval) {}
  localCredentialMayExist(): boolean { return this.mayHaveCredential; }
  cancel(): void { this.fail(new Error('initial pairing cancelled or expired')); }
  private cleanup(): void {
    try { this.attempt.cancel(); } catch (_) {}
    try { if (this.connection !== null) this.connection.close(); } catch (_) {}
    this.connection = null;
    try { this.approval.dismiss(); } catch (_) {}
    this.lease.close();
  }
  private fail(error: Error): void {
    if (this.finished) return;
    this.finished = true;
    if (this.reject !== null) this.reject(error); this.reject = null; this.cleanup();
  }
  private check(): void {
    if (this.finished) throw new Error('initial pairing closed'); this.lease.assertActive();
  }
  private async send(connection: CompanionSyncConnection, phase: string): Promise<string> {
    this.check();
    const payload = ascii(JSON.stringify(new Confirmation(this.invitation.invitationId, phase)));
    const frame = await connection.session.send('message', 'pairing-' + phase, Array.from(payload));
    this.check(); await connection.stream.write(ascii(JSON.stringify(frame))); this.check(); return frame.sessionId;
  }
  private async receive(connection: CompanionSyncConnection, phase: string): Promise<void> {
    this.check(); const bytes = await connection.stream.read(); this.check();
    if (bytes === null) throw new Error('pairing peer disconnected');
    const frame = decodeSyncObject(bytes, 4096) as CompanionSyncFrame;
    const result = await connection.session.verify(frame); this.check();
    if (result.delivery !== 'pending' || frame.channel !== 'message' || frame.messageId !== 'pairing-' + phase)
      throw new Error('unexpected pairing control frame');
    const control = decodeSyncObject(new Uint8Array(frame.payload), 512) as Confirmation;
    if (Object.keys(control).sort().join(',') !== 'invitationId,phase,type,version' ||
      control.type !== 'podjs-pairing-confirmation' || control.version !== 1 ||
      control.invitationId !== this.invitation.invitationId || control.phase !== phase)
      throw new Error('invalid pairing confirmation');
    await connection.session.commit(frame.sequence); this.check();
  }
  private async exchange(local: string, peer: string, initiator: boolean, key: Uint8Array): Promise<void> {
    const existing = await this.pairings.list(); this.check();
    if (existing.some((record) => record.peer === peer)) throw new Error('pairing already exists; inspect or revoke it first');
    const connection = await this.attempt.start(this.invitation.appId, local, peer, key, initiator, ['message']);
    if (this.finished) { connection.close(); throw new Error('late initial pairing connection'); }
    this.connection = connection; this.check();
    const approved = await this.approval.confirm(this.invitation.appId, local, peer, this.invitation.invitationId);
    this.check(); if (approved !== true) throw new Error('pairing approval declined');
    const sessionId = await this.send(connection, 'approved');
    await this.receive(connection, 'approved');
    const permanent = await this.crypto.hmacSha256(key, ascii('PodJS-pairing-key-v1:' + sessionId + ':' + this.invitation.invitationId));
    try {
      this.check();
      if (permanent.length !== 32 || !permanent.some((byte: number) => byte !== 0)) throw new Error('invalid derived pairing key');
      this.mayHaveCredential = true;
      await this.pairings.importApproved(peer, permanent); this.check();
      await this.send(connection, 'stored'); await this.receive(connection, 'stored');
    } finally { permanent.fill(0); }
  }
  async start(local: string, peer: string, initiator: boolean): Promise<void> {
    if (this.used || this.finished) throw new Error('initial pairing already used'); this.used = true;
    if (!this.lease.owns(this.invitation) || !this.pairings.matchesIdentity(this.invitation.appId, local) ||
      this.invitation.deviceId !== (initiator ? peer : local)) {
      this.cancel(); throw new Error('initial pairing identity mismatch');
    }
    return new Promise<void>((resolve, reject) => {
      this.reject = reject;
      let key: Uint8Array;
      try { key = this.lease.claim(() => this.cancel()); }
      catch (error) { this.fail(error as Error); return; }
      this.exchange(local, peer, initiator, key).then(() => {
        if (this.finished) return;
        this.finished = true; this.reject = null; this.cleanup(); resolve();
      }).catch((error: Error) => this.fail(error));
    });
  }
}
