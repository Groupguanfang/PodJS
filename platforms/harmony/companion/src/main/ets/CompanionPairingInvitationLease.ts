import { CompanionPairingInvitation, PAIRING_INVITATION_LIFETIME_MS } from './CompanionPairingInvitation';
import { CompanionStreamTimer } from './CompanionPacketStream';

export interface CompanionPairingClock { now(): number; }

/** One issuing/scanning host's live invitation. Own exactly one lease per
 * invitation; do not reconstruct consumed invitations from saved QR text.
 * The host callback must actually dismiss the QR. claim() is for a host-approved
 * candidate, not every unauthenticated packet received by a listener. */
export class CompanionPairingInvitationLease {
  private closed: boolean = false;
  private claimed: boolean = false;
  private dismissed: boolean = false;
  private activeKey: Uint8Array | null = null;
  private cancelAttempt: () => void = () => {};
  private cancelTimer: () => void = () => {};
  constructor(private invitation: CompanionPairingInvitation, private clock: CompanionPairingClock,
    timer: CompanionStreamTimer, private dismissQr: () => void) {
    try {
      const now = clock.now(), remaining = invitation.expiresAt - now;
      if (!Number.isSafeInteger(now) || now < 0 || remaining <= 0 || remaining > PAIRING_INVITATION_LIFETIME_MS)
        throw new Error('invalid invitation lifetime');
      // Validate that a previously consumed invitation is not reopened.
      invitation.encodeForQr(now);
      this.cancelTimer = timer.schedule(remaining, () => this.close());
      if (this.closed) { try { this.cancelTimer(); } catch (_) {} }
    } catch (error) { this.close(); throw error; }
  }
  private check(): void {
    if (this.closed) throw new Error('invitation lease closed');
    const now = this.clock.now(), remaining = this.invitation.expiresAt - now;
    if (!Number.isSafeInteger(now) || now < 0 || remaining <= 0 || remaining > PAIRING_INVITATION_LIFETIME_MS) {
      this.close(); throw new Error('invitation lease expired or clock changed');
    }
  }
  qrText(): string {
    this.check(); if (this.claimed) throw new Error('invitation already claimed');
    return this.invitation.encodeForQr(this.clock.now());
  }
  /** Caller may use the returned buffer only during this lease. Cancellation
   * wipes this exact buffer and invokes cancelAttempt even after it was claimed.
   * No permanent pairing is written here; mutual confirmation remains required. */
  claim(cancelAttempt: () => void): Uint8Array {
    this.check(); if (this.claimed) throw new Error('invitation already claimed');
    this.claimed = true; this.cancelAttempt = cancelAttempt;
    try {
      this.activeKey = this.invitation.takeSecret(this.clock.now());
      this.dismiss(); this.check(); return this.activeKey;
    } catch (error) { this.close(); throw error; }
  }
  private dismiss(): void {
    if (this.dismissed) return;
    this.dismissed = true; this.dismissQr();
  }
  assertActive(): void { this.check(); }
  owns(invitation: CompanionPairingInvitation): boolean { return this.invitation === invitation; }
  close(): void {
    if (this.closed) return;
    this.closed = true; this.invitation.close();
    if (this.activeKey !== null) this.activeKey.fill(0);
    this.activeKey = null;
    try { this.cancelTimer(); } catch (_) {}
    try { this.dismiss(); } catch (_) {}
    try { this.cancelAttempt(); } catch (_) {}
    this.cancelAttempt = () => {};
  }
}
