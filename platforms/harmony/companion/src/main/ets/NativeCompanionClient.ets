import { common } from '@kit.AbilityKit';
import { CompanionState } from './CompanionState';
import { createCompanionState } from './NativeCompanionState';
import { CompanionMessageOutbox } from './CompanionMessageOutbox';
import { CompanionMessageInbox } from './CompanionMessageInbox';
import { createCompanionMessageOutbox, createCompanionMessageInbox } from './NativeCompanionOutbox';
import { CompanionFileRequests } from './CompanionFileRequests';
import { createCompanionFileRequests } from './NativeCompanionFileRequests';
import { CompanionIncomingFiles } from './CompanionIncomingFiles';
import { createCompanionIncomingFiles } from './NativeCompanionIncomingFiles';
import { CompanionFileChannels } from './CompanionFilePump';
import { CompanionFileSender, CompanionFileSource } from './CompanionFileSender';
import { CompanionFileManifest } from './CompanionFileWire';
import { NativeCompanionSyncCrypto } from './NativeCompanionSync';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionMessageTransport } from './CompanionMessageTransport';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionOutgoingFiles, CompanionOutgoingFile, CompanionStoredFileSource } from './CompanionOutgoingFiles';
import { createCompanionOutgoingFiles, NativeCompanionImportCrypto } from './NativeCompanionOutgoingFiles';
import { importCompanionFile } from './CompanionFileImporter';
import { CompanionConnectionClient } from './CompanionConnectionOwner';

class ClientTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const id = setTimeout(expire, milliseconds); return () => clearTimeout(id);
  }
}
/** App-scoped durable channels. Construction grants no permissions and starts
 * no network work. The app obtains an authenticated connection separately. */
export class NativeCompanionClient implements CompanionConnectionClient {
  readonly state: CompanionState;
  readonly outbox: CompanionMessageOutbox;
  readonly inbox: CompanionMessageInbox;
  readonly fileRequests: CompanionFileRequests;
  readonly incomingFiles: CompanionIncomingFiles;
  readonly outgoingFiles: CompanionOutgoingFiles;
  private app: string;
  private local: string;
  private crypto: NativeCompanionSyncCrypto = new NativeCompanionSyncCrypto();
  private transport: CompanionMessageTransport | null = null;
  constructor(context: common.UIAbilityContext, appId: string, localId: string) {
    this.app = appId; this.local = localId;
    this.state = createCompanionState(context, appId, localId);
    this.outbox = createCompanionMessageOutbox(context, appId, localId);
    this.inbox = createCompanionMessageInbox(context, appId, localId);
    this.fileRequests = createCompanionFileRequests(context, appId, localId);
    this.incomingFiles = createCompanionIncomingFiles(context, appId, localId);
    this.outgoingFiles = createCompanionOutgoingFiles(context, appId);
  }
  createFileSender(peer: string, manifest: CompanionFileManifest, source: CompanionFileSource): CompanionFileSender {
    return new CompanionFileSender(this.fileRequests, peer, manifest, source, this.crypto);
  }
  async createStoredFileSender(peer: string, transferId: string): Promise<CompanionFileSender> {
    const files = await this.outgoingFiles.list();
    const file = files.find((item: CompanionOutgoingFile) => item.manifest.transfer_id === transferId);
    if (file === undefined || file.phase !== 'complete') throw new Error('outgoing source not complete');
    return this.createFileSender(peer, file.manifest, new CompanionStoredFileSource(this.outgoingFiles, transferId));
  }
  importFile(transferId: string, size: number, mime: string, source: CompanionFileSource,
    cancelled: () => boolean = () => false): Promise<CompanionFileManifest> {
    return importCompanionFile(this.outgoingFiles, source, new NativeCompanionImportCrypto(), transferId, size, mime, cancelled);
  }
  /** One foreground owner at a time. close before replacing a connection. */
  attach(connection: CompanionSyncConnection, durationMs: number): CompanionMessageTransport {
    if (this.transport !== null) throw new Error('companion client already attached');
    if (connection.session.appId() !== this.app || connection.session.localId() !== this.local) throw new Error('companion connection identity mismatch');
    const files = new CompanionFileChannels(this.fileRequests, this.incomingFiles, this.crypto);
    const transport = new CompanionMessageTransport(connection, this.outbox, this.inbox,
      { now: () => Date.now() }, new ClientTimer(), durationMs, this.state, files);
    this.transport = transport;
    transport.stopped.then(() => { if (this.transport === transport) this.transport = null; });
    return transport;
  }
  close(): void {
    const transport = this.transport; this.transport = null;
    if (transport !== null) transport.close();
  }
}
