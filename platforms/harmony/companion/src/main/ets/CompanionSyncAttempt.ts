import { CompanionSyncSession, CompanionSyncSigner, copyCompanionSyncKey, clearCompanionSyncKey } from './CompanionSyncAuth';
import { CompanionSyncExchange, CompanionSyncChallengeCrypto, CompanionSyncPacketStream } from './CompanionSyncExchange';
import { CompanionStreamTimer } from './CompanionPacketStream';

/** Host-approved connection, with cancellation. No discovery or trust-on-first-use.
 * cancel must request cancellation without blocking; late connections are closed. */
export interface CompanionSyncConnector {
  connect(): Promise<CompanionSyncPacketStream>;
  cancel(): void;
}
export class CompanionSyncConnection {
  readonly session: CompanionSyncSession;
  readonly stream: CompanionSyncPacketStream;
  private closed: boolean = false;
  constructor(session: CompanionSyncSession, stream: CompanionSyncPacketStream) { this.session = session; this.stream = stream; }
  close(): void {
    if (this.closed) return; this.closed = true;
    this.session.close(); this.stream.close();
  }
}

/** One deadline covers connection, random generation, hello, crypto and proof IO.
 * Expiry settles the caller immediately, even if an OS Promise never completes.
 * A successfully returned connection is owned by the caller, not by this attempt. */
export class CompanionSyncAttempt {
  private connector: CompanionSyncConnector;
  private crypto: CompanionSyncChallengeCrypto;
  private timer: CompanionStreamTimer;
  private timeout: number;
  private used: boolean = false;
  private finished: boolean = false;
  private key: Uint8Array | CompanionSyncSigner | null = null;
  private link: CompanionSyncPacketStream | null = null;
  private exchange: CompanionSyncExchange | null = null;
  private cancelTimer: () => void = () => {};
  private reject: ((error: Error) => void) | null = null;
  constructor(connector: CompanionSyncConnector, crypto: CompanionSyncChallengeCrypto,
    timer: CompanionStreamTimer, timeoutMs: number) {
    if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 120000) throw new Error('invalid attempt timeout');
    this.connector = connector; this.crypto = crypto; this.timer = timer; this.timeout = timeoutMs;
  }
  cancel(): void { this.fail(new Error('sync attempt cancelled')); }
  private fail(error: Error): void {
    if (this.finished) return; this.finished = true;
    clearCompanionSyncKey(this.key); this.key = null;
    if (this.reject !== null) this.reject(error); this.reject = null;
    // A secondary cleanup error must not prevent the remaining cleanup or leave
    // the public Promise waiting for an unresponsive OS call.
    try { this.cancelTimer(); } catch (_) {}
    try { this.connector.cancel(); } catch (_) {}
    try { if (this.exchange !== null) this.exchange.close(); } catch (_) {}
    try { if (this.link !== null) this.link.close(); } catch (_) {}
    this.exchange = null; this.link = null;
  }
  async start(app: string, local: string, remote: string, key: Uint8Array | CompanionSyncSigner,
    initiator: boolean, grantedChannels: string[]): Promise<CompanionSyncConnection> {
    if (this.used || this.finished) throw new Error('sync attempt already used'); this.used = true;
    for (const id of [app, local, remote]) if (typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) throw new Error('invalid identity');
    if (local === remote || typeof initiator !== 'boolean') throw new Error('invalid peer roles');
    const grants = grantedChannels.slice();
    if (grants.length < 1 || grants.length > 4 || !grants.every((g: string) => ['state', 'message', 'file', 'ack'].includes(g))) throw new Error('invalid channel grants');
    this.key = copyCompanionSyncKey(key, app, local, remote);
    return new Promise<CompanionSyncConnection>((resolve, reject) => {
      this.reject = reject;
      try { this.cancelTimer = this.timer.schedule(this.timeout, () => this.fail(new Error('sync attempt deadline'))); }
      catch (error) { this.fail(new Error('attempt timer unavailable')); return; }
      if (this.finished) { try { this.cancelTimer(); } catch (_) {} return; }
      this.run(app, local, remote, initiator, grants).then((connection: CompanionSyncConnection) => {
        if (this.finished) { try { connection.close(); } catch (_) {} return; }
        this.finished = true; try { this.cancelTimer(); } catch (_) {} this.reject = null;
        clearCompanionSyncKey(this.key); this.key = null;
        this.link = null; this.exchange = null; resolve(connection);
      }, (error: Error) => this.fail(error));
    });
  }
  private async run(app: string, local: string, remote: string, initiator: boolean,
    grants: string[]): Promise<CompanionSyncConnection> {
    const link = await this.connector.connect();
    if (this.finished) { link.close(); throw new Error('late sync connection'); }
    this.link = link;
    const exchange = new CompanionSyncExchange(link, this.crypto); this.exchange = exchange;
    if (this.key === null) throw new Error('sync attempt cancelled');
    const session = await exchange.establish(app, local, remote, this.key, initiator, grants);
    if (this.finished) { session.close(); link.close(); throw new Error('late sync session'); }
    return new CompanionSyncConnection(session, link);
  }
}
