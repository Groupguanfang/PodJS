import socket from '@ohos.net.socket';
import cert from '@ohos.security.cert';
import { BusinessError } from '@kit.BasicServicesKit';
import { CompanionTlsConnector, CompanionTlsSocket, CompanionTlsSocketFactory, companionCertificateDer } from './CompanionTlsConnector';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { NativeCompanionSyncCrypto } from './NativeCompanionSync';

class NativeTlsTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const timer = setTimeout(expire, milliseconds); return () => { clearTimeout(timer); };
  }
}
class NativeTlsSocket implements CompanionTlsSocket {
  private socket: socket.TLSSocket = socket.constructTLSSocketInstance();
  private closed: boolean = false;
  private check(): void { if (this.closed) throw new Error('TLS socket closed'); }
  listen(receive: (bytes: Uint8Array) => void, end: () => void, error: (error: Error) => void): void {
    this.socket.on('message', (message: socket.SocketMessageInfo) => receive(new Uint8Array(message.message)));
    this.socket.on('close', end);
    this.socket.on('error', (failure: BusinessError) => error(new Error('TLS socket error: ' + failure.code)));
  }
  async connect(host: string, port: number, ca: string, timeoutMs: number): Promise<void> {
    this.check(); const family = host.includes(':') ? 2 : 1;
    await this.socket.bind({ address: family === 2 ? '::' : '0.0.0.0', port: 0, family: family }); this.check();
    await this.socket.connect({ address: { address: host, port: port, family: family },
      secureOptions: { ca: ca, protocols: [socket.Protocol.TLSv12, socket.Protocol.TLSv13] },
      skipRemoteValidation: false, timeout: timeoutMs }); this.check();
    const protocol = await this.socket.getProtocol(); this.check();
    if (protocol !== 'TLSv1.2' && protocol !== 'TLSv1.3') throw new Error('unsupported negotiated TLS version');
  }
  async certificateDer(): Promise<Uint8Array> {
    this.check(); const leaf = await this.socket.getRemoteCertificate(); this.check();
    if (leaf.encodingFormat === cert.EncodingFormat.FORMAT_DER) return companionCertificateDer(leaf.data, 'der');
    if (leaf.encodingFormat === cert.EncodingFormat.FORMAT_PEM) return companionCertificateDer(leaf.data, 'pem');
    throw new Error('unsupported TLS leaf encoding');
  }
  async send(bytes: Uint8Array): Promise<void> { this.check(); await this.socket.send(bytes.slice().buffer); this.check(); }
  close(): void {
    if (this.closed) return; this.closed = true;
    try { this.socket.off('message'); } catch (_) {}
    try { this.socket.off('close'); } catch (_) {}
    try { this.socket.off('error'); } catch (_) {}
    try { this.socket.close().catch(() => {}); } catch (_) {}
  }
}
class NativeTlsFactory implements CompanionTlsSocketFactory {
  create(): CompanionTlsSocket { return new NativeTlsSocket(); }
}

/** Explicit approved endpoint/CA/DER fingerprint. Use with createCompanionSyncAttempt;
 * INTERNET permission and prior pairing approval are required. No pin learning,
 * insecure fallback, discovery or account-based trust is performed. */
export function createCompanionTlsConnector(host: string, port: number, ca: string,
  leafSha256: string, lifetimeMs: number): CompanionTlsConnector {
  return new CompanionTlsConnector(new NativeTlsFactory(), new NativeCompanionSyncCrypto(), new NativeTlsTimer(),
    host, port, ca, leafSha256, lifetimeMs);
}
