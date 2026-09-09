import socket from '@ohos.net.socket';
import { BusinessError } from '@kit.BasicServicesKit';
import { CompanionByteWriter, CompanionStreamTimer, CompanionPacketStream } from './CompanionPacketStream';

class NativeStreamTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const timer = setTimeout(expire, milliseconds);
    return () => { clearTimeout(timer); };
  }
}
class NativeTcpWriter implements CompanionByteWriter {
  private socket: socket.TCPSocket;
  private closed: boolean = false;
  constructor(connection: socket.TCPSocket) { this.socket = connection; }
  async send(bytes: Uint8Array): Promise<void> {
    if (this.closed) throw new Error('TCP stream closed');
    await this.socket.send({ data: bytes.slice().buffer });
  }
  close(): void {
    if (this.closed) return; this.closed = true;
    // The adapter has exclusive ownership; no other listeners may be installed.
    try { this.socket.off('message'); } catch (_) {}
    try { this.socket.off('close'); } catch (_) {}
    try { this.socket.off('error'); } catch (_) {}
    // Logical cancellation is synchronous even if OS cleanup rejects or stalls.
    try { this.socket.close().catch(() => {}); } catch (_) {}
  }
}

/** Takes exclusive ownership of a connected TCP socket. Host must declare
 * INTERNET permission and establish its approved connection first. TCP is not
 * encrypted: use only for development or inside a separately encrypted link.
 * No discovery, connect, pairing, retry or plaintext production fallback. */
export function createCompanionTcpPacketStream(connection: socket.TCPSocket,
  lifetimeMs: number): CompanionPacketStream {
  const writer = new NativeTcpWriter(connection);
  const stream = new CompanionPacketStream(writer, new NativeStreamTimer(), lifetimeMs);
  try {
    connection.on('message', (message: socket.SocketMessageInfo) => stream.receive(new Uint8Array(message.message)));
    connection.on('close', () => stream.end());
    connection.on('error', (error: BusinessError) => stream.fail(new Error('TCP stream error: ' + error.code)));
    return stream;
  } catch (error) { stream.close(); throw new Error('TCP listener registration failed'); }
}
