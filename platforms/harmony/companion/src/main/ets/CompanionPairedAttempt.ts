import { CompanionPairings, CompanionPairingSigner } from './CompanionPairings';
import { CompanionSyncAttempt, CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionStreamTimer } from './CompanionPacketStream';

class PairedConnection extends CompanionSyncConnection {
  constructor(connection: CompanionSyncConnection, private signer: CompanionPairingSigner) {
    super(connection.session, connection.stream);
  }
  close(): void {
    try { super.close(); } finally { this.signer.close(); }
  }
}

/** Deadline covers both keystore lookup and the existing authenticated attempt.
 * No raw pairing key is returned to the caller. The connector and channel grants
 * remain explicitly host-approved; pairing does not authorize an arbitrary URL. */
export class CompanionPairedAttempt {
  private used: boolean = false;
  private finished: boolean = false;
  private signer: CompanionPairingSigner | null = null;
  private connection: CompanionSyncConnection | null = null;
  private reject: ((error: Error) => void) | null = null;
  private clearTimer: () => void = () => {};
  constructor(private pairings: CompanionPairings, private attempt: CompanionSyncAttempt,
    private timer: CompanionStreamTimer, private timeout: number) {
    if (!Number.isInteger(timeout) || timeout < 1 || timeout > 120000) throw new Error('invalid paired attempt timeout');
  }
  cancel(): void { this.fail(new Error('paired attempt cancelled')); }
  private fail(error: Error): void {
    if (this.finished) return;
    this.finished = true;
    if (this.reject !== null) this.reject(error); this.reject = null;
    try { this.clearTimer(); } catch (_) {}
    try { this.attempt.cancel(); } catch (_) {}
    try { if (this.connection !== null) this.connection.close(); } catch (_) {}
    try { if (this.signer !== null) this.signer.close(); } catch (_) {}
    this.connection = null; this.signer = null;
  }
  private revoked(): void {
    // Revocation after handoff must still close the caller-owned connection.
    try { if (this.connection !== null) this.connection.close(); } catch (_) {}
    this.fail(new Error('pairing revoked during connection'));
  }
  private async establish(app: string, local: string, peer: string, initiator: boolean,
    grants: string[]): Promise<CompanionSyncConnection> {
    const signer = await this.pairings.openSigner(peer, () => this.revoked());
    if (this.finished) { signer.close(); throw new Error('paired attempt cancelled'); }
    this.signer = signer;
    const raw = await this.attempt.start(app, local, peer, signer, initiator, grants);
    if (this.finished || !signer.matchesIdentity(app, local, peer)) {
      try { raw.close(); } finally { signer.close(); }
      throw new Error('pairing revoked during connection');
    }
    const connection = new PairedConnection(raw, signer); this.connection = connection; return connection;
  }
  async start(app: string, local: string, peer: string, initiator: boolean,
    grants: string[]): Promise<CompanionSyncConnection> {
    if (this.used || this.finished) throw new Error('paired attempt already used'); this.used = true;
    if (!this.pairings.matchesIdentity(app, local)) { this.cancel(); throw new Error('pairing owner identity mismatch'); }
    const stableGrants = grants.slice();
    return new Promise<CompanionSyncConnection>((resolve, reject) => {
      this.reject = reject;
      try { this.clearTimer = this.timer.schedule(this.timeout, () => this.fail(new Error('paired attempt deadline'))); }
      catch (_) { this.fail(new Error('paired attempt timer unavailable')); return; }
      if (this.finished) { try { this.clearTimer(); } catch (_) {} return; }
      this.establish(app, local, peer, initiator, stableGrants).then((connection: CompanionSyncConnection) => {
        if (this.finished) { connection.close(); return; }
        this.finished = true; this.reject = null;
        try { this.clearTimer(); } catch (_) {}
        resolve(connection);
      }).catch((error: Error) => this.fail(error));
    });
  }
}
