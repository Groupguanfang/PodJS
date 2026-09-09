import linkEnhance from '@ohos.distributedsched.linkEnhance';
import { CompanionChunkPort, CompanionChunkWriter } from './CompanionChunkWriter';
import { CompanionPacketStream, CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionSyncConnector } from './CompanionSyncAttempt';
import { CompanionSyncPacketStream } from './CompanionSyncExchange';

class DistributedTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const timer = setTimeout(expire, milliseconds); return () => { clearTimeout(timer); };
  }
}
class DistributedChunkPort implements CompanionChunkPort {
  private connection: linkEnhance.Connection;
  private closed: boolean = false;
  private onClose: () => void;
  constructor(connection: linkEnhance.Connection, onClose: () => void) { this.connection = connection; this.onClose = onClose; }
  sendChunk(bytes: Uint8Array): void {
    if (this.closed) throw new Error('distributed connection closed');
    if (bytes.length < 1 || bytes.length > 1024) throw new Error('invalid distributed chunk');
    this.connection.sendData(bytes.slice().buffer);
  }
  async yieldTurn(): Promise<void> { await new Promise<void>(resolve => { setTimeout(resolve, 0); }); }
  close(): void {
    if (this.closed) return; this.closed = true;
    try { this.connection.off('dataReceived'); } catch (_) {}
    try { this.connection.off('disconnected'); } catch (_) {}
    try { this.connection.close(); } catch (_) {}
    try { this.onClose(); } catch (_) {}
  }
}

/** Optional API-20 system adapter. Takes exclusive ownership of a connected
 * linkEnhance channel after matching the host-approved BLE device address.
 * Requires DISTRIBUTED_DATASYNC and AppCollaboration capability on the device.
 * OS peer routing is not app authentication: run the normal HMAC exchange next.
 * No encryption guarantee is inferred from these API declarations. */
export function createCompanionDistributedPacketStream(connection: linkEnhance.Connection,
  approvedDeviceId: string, lifetimeMs: number, onClose: () => void = () => {}): CompanionPacketStream {
  if (!/^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(approvedDeviceId)) throw new Error('approved BLE peer address required');
  if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid distributed lifetime');
  const port = new DistributedChunkPort(connection, onClose);
  try {
    const peer = connection.getPeerDeviceId();
    if (peer.toUpperCase() !== approvedDeviceId.toUpperCase()) throw new Error('distributed peer mismatch');
    const stream = new CompanionPacketStream(new CompanionChunkWriter(port), new DistributedTimer(), lifetimeMs);
    try {
      connection.on('dataReceived', (bytes: ArrayBuffer) => stream.receive(new Uint8Array(bytes)));
      connection.on('disconnected', (reason: number) => stream.fail(new Error('distributed connection ended: ' + reason)));
      return stream;
    } catch (_) { stream.close(); throw new Error('distributed listener registration failed'); }
  } catch (error) { port.close(); throw new Error('distributed stream unavailable: ' + (error as Error).message); }
}

/** One-shot client. Permission/capability failures are errors, not a signal to
 * auto-enable a different radio or silently fall back to another transport. */
export class NativeCompanionDistributedConnector implements CompanionSyncConnector {
  private deviceId: string;
  private serverName: string;
  private lifetime: number;
  private connection: linkEnhance.Connection | null = null;
  private stream: CompanionPacketStream | null = null;
  private reject: ((error: Error) => void) | null = null;
  private used: boolean = false;
  private cancelled: boolean = false;
  constructor(approvedDeviceId: string, serverName: string, lifetimeMs: number) {
    if (!/^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(approvedDeviceId)) throw new Error('approved BLE peer address required');
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(serverName)) throw new Error('invalid distributed service name');
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid distributed lifetime');
    this.deviceId = approvedDeviceId.toUpperCase(); this.serverName = serverName; this.lifetime = lifetimeMs;
  }
  cancel(): void {
    if (this.cancelled) return; this.cancelled = true;
    if (this.reject !== null) this.reject(new Error('distributed connect cancelled')); this.reject = null;
    try { if (this.stream !== null) this.stream.close(); } catch (_) {}
    if (this.connection !== null) {
      try { this.connection.off('connectResult'); } catch (_) {}
      try { this.connection.off('disconnected'); } catch (_) {}
      try { this.connection.close(); } catch (_) {}
    }
  }
  connect(): Promise<CompanionSyncPacketStream> {
    if (this.used || this.cancelled) return Promise.reject(new Error('distributed connector already used'));
    this.used = true;
    return new Promise<CompanionSyncPacketStream>((resolve, reject) => {
      this.reject = reject;
      try {
        const connection = linkEnhance.createConnection(this.deviceId, this.serverName); this.connection = connection;
        connection.on('disconnected', (reason: number) => {
          if (this.reject !== null) this.reject(new Error('distributed connect ended: ' + reason));
          this.reject = null; this.cancel();
        });
        connection.on('connectResult', (result: linkEnhance.ConnectResult) => {
          if (this.cancelled) { try { connection.close(); } catch (_) {} return; }
          if (this.stream !== null) return;
          try {
            if (!result.success || result.reason !== 0 || result.deviceId.toUpperCase() !== this.deviceId)
              throw new Error('distributed connect rejected: ' + result.reason);
            connection.off('connectResult'); connection.off('disconnected');
            const stream = createCompanionDistributedPacketStream(connection, this.deviceId, this.lifetime);
            this.stream = stream; this.reject = null; resolve(stream);
          } catch (error) { reject(new Error('distributed connect failed: ' + (error as Error).message)); this.reject = null; this.cancel(); }
        });
        connection.connect();
      } catch (error) { reject(new Error('distributed connect unavailable: ' + (error as Error).message)); this.reject = null; this.cancel(); }
    });
  }
}

/** One-shot approved-peer listener. Use through CompanionSyncAttempt for the
 * listen/handshake deadline and with local_is_initiator=false for authentication. */
export class NativeCompanionDistributedAcceptor implements CompanionSyncConnector {
  private deviceId: string;
  private serverName: string;
  private lifetime: number;
  private server: linkEnhance.Server | null = null;
  private stream: CompanionPacketStream | null = null;
  private reject: ((error: Error) => void) | null = null;
  private used: boolean = false;
  private cancelled: boolean = false;
  constructor(approvedDeviceId: string, serverName: string, lifetimeMs: number) {
    if (!/^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(approvedDeviceId)) throw new Error('approved BLE peer address required');
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(serverName)) throw new Error('invalid distributed service name');
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid distributed lifetime');
    this.deviceId = approvedDeviceId.toUpperCase(); this.serverName = serverName; this.lifetime = lifetimeMs;
  }
  cancel(): void {
    if (this.cancelled) return;
    this.cancelled = true;
    const reject = this.reject; this.reject = null;
    if (reject !== null) reject(new Error('distributed accept cancelled'));
    if (this.stream !== null) this.stream.close();
    if (this.server !== null) {
      try { this.server.off('connectionAccepted'); } catch (_) {}
      try { this.server.off('serverStopped'); } catch (_) {}
      try { this.server.stop(); } catch (_) {}
      try { this.server.close(); } catch (_) {}
    }
  }
  connect(): Promise<CompanionSyncPacketStream> {
    if (this.used || this.cancelled) return Promise.reject(new Error('distributed acceptor already used'));
    this.used = true;
    return new Promise<CompanionSyncPacketStream>((resolve, reject) => {
      this.reject = reject;
      try {
        const server = linkEnhance.createServer(this.serverName); this.server = server;
        server.on('serverStopped', (reason: number) => {
          if (this.stream !== null || this.cancelled) return;
          reject(new Error('distributed listener stopped: ' + reason)); this.reject = null; this.cancel();
        });
        server.on('connectionAccepted', (connection: linkEnhance.Connection) => {
          if (this.cancelled || this.stream !== null) { try { connection.close(); } catch (_) {} return; }
          try {
            // An unrelated nearby peer must not consume the approved attempt.
            if (connection.getPeerDeviceId().toUpperCase() !== this.deviceId) { connection.close(); return; }
            const stream = createCompanionDistributedPacketStream(connection, this.deviceId, this.lifetime, () => this.cancel());
            this.stream = stream;
            server.stop();
            this.reject = null;
            resolve({
              read: () => stream.read(),
              write: (bytes: Uint8Array) => stream.write(bytes),
              close: () => this.cancel()
            });
          } catch (error) {
            try { connection.close(); } catch (_) {}
            reject(new Error('distributed accept failed: ' + (error as Error).message)); this.reject = null; this.cancel();
          }
        });
        server.start();
      } catch (error) {
        reject(new Error('distributed listener unavailable: ' + (error as Error).message)); this.reject = null; this.cancel();
      }
    });
  }
}
