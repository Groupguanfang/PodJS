import { CompanionPairingApproval } from './CompanionInitialPairing';

export class CompanionPairingApprovalRequest {
  readonly app: string;
  readonly local: string;
  readonly peer: string;
  readonly invitationId: string;
  constructor(app: string, local: string, peer: string, invitationId: string) {
    this.app = app; this.local = local; this.peer = peer; this.invitationId = invitationId;
  }
}
export interface CompanionPairingApprovalView {
  /** Show identities, never QR secret. answer(true) must require a user action. */
  show(request: CompanionPairingApprovalRequest, answer: (approved: boolean) => void): void;
  hide(): void;
}
class PendingApproval {
  resolve: (approved: boolean) => void;
  constructor(resolve: (approved: boolean) => void) { this.resolve = resolve; }
}

/** One prompt at a time; bind one gate to one initial-pairing attempt. Dismiss
 * permanently disables it, resolving any prompt as declined. Old callbacks are
 * scoped to the exact request and cannot approve a later prompt. */
export class CompanionPairingApprovalGate implements CompanionPairingApproval {
  private pending: PendingApproval | null = null;
  private closed: boolean = false;
  constructor(private view: CompanionPairingApprovalView) {}
  private settle(pending: PendingApproval, approved: boolean): void {
    if (this.pending !== pending) return;
    this.pending = null;
    let result = approved === true && !this.closed;
    try { this.view.hide(); } catch (_) { result = false; }
    pending.resolve(result);
  }
  confirm(app: string, local: string, peer: string, invitationId: string): Promise<boolean> {
    if (this.closed) return Promise.resolve(false);
    if (this.pending !== null) return Promise.reject(new Error('pairing approval already pending'));
    for (const value of [app, local, peer]) {
      if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) return Promise.reject(new Error('invalid pairing approval identity'));
    }
    if (local === peer || typeof invitationId !== 'string' || !/^[0-9a-f]{64}$/.test(invitationId)) return Promise.reject(new Error('invalid pairing approval request'));
    const request = new CompanionPairingApprovalRequest(app, local, peer, invitationId);
    return new Promise<boolean>((resolve) => {
      const pending = new PendingApproval(resolve); this.pending = pending;
      let showing = true;
      let answered = false;
      let answer = false;
      try {
        this.view.show(request, (approved: boolean) => {
          if (answered) return;
          answered = true;
          answer = approved === true;
          if (!showing) this.settle(pending, answer);
        });
        showing = false;
        if (answered) this.settle(pending, answer);
      }
      catch (_) { this.settle(pending, false); }
    });
  }
  dismiss(): void {
    if (this.closed) return; this.closed = true;
    if (this.pending !== null) this.settle(this.pending, false);
    else { try { this.view.hide(); } catch (_) {} }
  }
}
