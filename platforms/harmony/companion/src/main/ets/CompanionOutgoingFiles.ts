import { CompanionIncomingFilePort } from './CompanionIncomingFiles';
import { CompanionFileManifest, validateFileManifest } from './CompanionFileWire';
import { CompanionFileSource } from './CompanionFileSender';

export class CompanionOutgoingFile {
  manifest: CompanionFileManifest = new CompanionFileManifest();
  phase: string = 'staging';
}
class SourceJournal {
  schema: number = 1;
  app: string = '';
  files: CompanionOutgoingFile[] = [];
}
function copy(manifest: CompanionFileManifest): CompanionFileManifest {
  validateFileManifest(manifest); const value = new CompanionFileManifest();
  value.transfer_id = manifest.transfer_id; value.size = manifest.size; value.sha256 = manifest.sha256;
  value.mime = manifest.mime; value.chunk_hashes = manifest.chunk_hashes.slice(); return value;
}
/** Dedicated outgoing namespace, not a receiver port shared with incoming files.
 * Immutable IDs survive removal. Quota reserves both chunks and assembly bytes. */
export class CompanionOutgoingFiles {
  private app: string;
  private port: CompanionIncomingFilePort;
  constructor(app: string, port: CompanionIncomingFilePort) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(app)) throw new Error('invalid outgoing app');
    this.app = app; this.port = port;
  }
  private async load(): Promise<SourceJournal> {
    const raw = await this.port.readJournal();
    if (raw === null) { const journal = new SourceJournal(); journal.app = this.app; return journal; }
    if (raw.length > 4194304) throw new Error('outgoing journal too large');
    const journal = JSON.parse(raw) as SourceJournal;
    if (!journal || journal.schema !== 1 || journal.app !== this.app || !Array.isArray(journal.files) || journal.files.length > 128) throw new Error('invalid outgoing journal');
    const ids: string[] = []; let bytes = 0;
    for (const file of journal.files) {
      if (!file || !['staging', 'complete', 'removing', 'removed'].includes(file.phase)) throw new Error('invalid outgoing phase');
      validateFileManifest(file.manifest);
      if (ids.includes(file.manifest.transfer_id)) throw new Error('duplicate outgoing identity'); ids.push(file.manifest.transfer_id);
      if (file.phase !== 'removed') bytes += file.manifest.size * 2;
    }
    if (bytes > 33554432) throw new Error('outgoing quota exceeded'); return journal;
  }
  private save(journal: SourceJournal): Promise<void> { return this.port.writeJournal(JSON.stringify(journal)); }
  private find(journal: SourceJournal, id: string): CompanionOutgoingFile {
    const file = journal.files.find((value: CompanionOutgoingFile) => value.manifest.transfer_id === id);
    if (file === undefined) throw new Error('unknown outgoing file'); return file;
  }
  private async recoverFile(journal: SourceJournal, file: CompanionOutgoingFile): Promise<void> {
    if (file.phase === 'removing') {
      await this.port.remove('source', copy(file.manifest)); file.phase = 'removed'; await this.save(journal);
    } else if (file.phase === 'staging') await this.port.reserve('source', copy(file.manifest));
  }
  recover(): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load();
      for (const file of journal.files) if (file.phase === 'removing') await this.recoverFile(journal, file);
      for (const file of journal.files) if (file.phase === 'staging') await this.recoverFile(journal, file);
    });
  }
  list(): Promise<CompanionOutgoingFile[]> { return this.port.exclusive(async () => (await this.load()).files); }
  prepare(manifest: CompanionFileManifest): Promise<void> {
    const stable = copy(manifest);
    return this.port.exclusive(async () => {
      const journal = await this.load();
      let file = journal.files.find((value: CompanionOutgoingFile) => value.manifest.transfer_id === stable.transfer_id);
      if (file !== undefined) {
        if (JSON.stringify(copy(file.manifest)) !== JSON.stringify(stable)) throw new Error('outgoing manifest changed');
        if (file.phase === 'removed' || file.phase === 'removing') throw new Error('outgoing file removed');
      } else {
        let bytes = stable.size * 2;
        for (const item of journal.files) if (item.phase !== 'removed') bytes += item.manifest.size * 2;
        if (bytes > 33554432 || journal.files.length >= 128) throw new Error('outgoing quota exceeded');
        file = new CompanionOutgoingFile(); file.manifest = stable; journal.files.push(file); await this.save(journal);
      }
      await this.recoverFile(journal, file);
    });
  }
  writeChunk(id: string, index: number, bytes: Uint8Array): Promise<void> {
    const stable = bytes.slice();
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging') throw new Error('outgoing file not staging');
      await this.recoverFile(journal, file); await this.port.writeChunk('source', copy(file.manifest), index, stable);
    });
  }
  missing(id: string): Promise<number[]> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging' && file.phase !== 'complete') throw new Error('outgoing file removed');
      await this.recoverFile(journal, file); return this.port.missing('source', copy(file.manifest));
    });
  }
  finish(id: string): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging' && file.phase !== 'complete') throw new Error('outgoing file removed');
      await this.recoverFile(journal, file); await this.port.finish('source', copy(file.manifest));
      file.phase = 'complete'; await this.save(journal);
    });
  }
  readChunk(id: string, index: number): Promise<Uint8Array> {
    return this.port.exclusive(async () => {
      const file = this.find(await this.load(), id);
      if (file.phase !== 'complete') throw new Error('outgoing file not complete');
      return this.port.readCompleteChunk('source', copy(file.manifest), index);
    });
  }
  remove(id: string): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase === 'removed') return;
      file.phase = 'removing'; await this.save(journal); await this.recoverFile(journal, file);
    });
  }
}
export class CompanionStoredFileSource implements CompanionFileSource {
  private files: CompanionOutgoingFiles;
  private id: string;
  constructor(files: CompanionOutgoingFiles, id: string) { this.files = files; this.id = id; }
  readChunk(index: number): Promise<Uint8Array> { return this.files.readChunk(this.id, index); }
}
