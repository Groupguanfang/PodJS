import { ReminderApi, ReminderError, ReminderHandle, ReminderNotification, reminderDelay } from './ReminderContract';

/** One exclusive owner per app. write must atomically replace and durably commit
 * the complete value before resolving; a rejected write may have committed. */
export interface ReminderStore {
  read(): Promise<string | null>;
  write(json: string): Promise<void>;
}
export class ReminderRecord {
  value: ReminderNotification = new ReminderNotification();
  handle: ReminderHandle = new ReminderHandle();
  phase: string = 'publishing';
  openState?: string = 'none';
}
export class ReminderOpenEvent {
  t: string = 'notification.open';
  eventId: string = '';
  notificationId: string = '';
  payload: Object | null = null;
}
class JournalState {
  version: number = 2;
  nextNotificationId: number = 0x50000000;
  records: ReminderRecord[] = [];
}

/** Write-ahead publication/cancellation ledger. Completed records retain payload
 * for later cold-start event verification. No automatic history eviction. */
export class ReminderJournal {
  private tail: Promise<void> = Promise.resolve();
  constructor(private store: ReminderStore, private api: ReminderApi) {}

  allocateNotificationId(): Promise<number> {
    return this.serial(async () => {
      const state = await this.load();
      if (state.nextNotificationId > 2147483647) throw new ReminderError('resource_exhausted');
      const id = state.nextNotificationId++; await this.save(state); return id;
    });
  }
  reclaimAcknowledged(): Promise<number> {
    return this.serial(async () => {
      const state = await this.load(); const handles = await this.api.list();
      const retained = state.records.filter(record =>
        record.openState !== 'acked' || (record.phase !== 'finished' && record.phase !== 'cancelled') ||
        this.match(record, handles) !== undefined);
      const removed = state.records.length - retained.length;
      if (removed) { state.records = retained; await this.save(state); }
      return removed;
    });
  }

  captureOpen(token: string, notificationId: string): Promise<boolean> {
    return this.serial(async () => {
      const state = await this.load();
      const record = state.records.find(r => r.handle.token === token && r.value.id === notificationId);
      if (!record) return false;
      if ((record.openState ?? 'none') !== 'none') return true;
      record.openState = 'pending'; await this.save(state); return true;
    });
  }
  pendingOpens(): Promise<ReminderOpenEvent[]> {
    return this.serial(async () => {
      const state = await this.load();
      return state.records.filter(r => r.openState === 'pending').map(record => {
        const event = new ReminderOpenEvent(); event.eventId = record.handle.token + '.open';
        event.notificationId = record.value.id; event.payload = record.value.payload ?? null;
        return event;
      });
    });
  }
  acknowledgeOpen(eventId: string): Promise<boolean> {
    return this.serial(async () => {
      const state = await this.load();
      const record = state.records.find(r => r.handle.token + '.open' === eventId);
      if (!record || (record.openState ?? 'none') === 'none') return false;
      if (record.openState === 'acked') return true;
      record.openState = 'acked'; await this.save(state); return true;
    });
  }

  publish(value: ReminderNotification, notificationId: number, token: string): Promise<void> {
    // Snapshot caller-owned data before entering the asynchronous queue.
    let frozen: ReminderNotification;
    try {
      const json = JSON.stringify(value);
      if (json.length > 32000) throw new Error('large');
      frozen = JSON.parse(json) as ReminderNotification;
      reminderDelay(frozen, notificationId, token, Date.now());
    } catch (error) {
      return Promise.reject(error instanceof ReminderError ? error : new ReminderError('invalid_argument'));
    }
    return this.serial(async () => {
      const state = await this.load();
      const previous = state.records.find(r => r.handle.token === token);
      if (previous) {
        if (previous.handle.notificationId !== notificationId || JSON.stringify(previous.value) !== JSON.stringify(frozen))
          throw new ReminderError('conflict');
        if (previous.phase === 'ready' || previous.phase === 'finished') return;
        if (previous.phase !== 'publishing' && previous.phase !== 'uncertain') throw new ReminderError('conflict');
        const handles = await this.api.list();
        const found = this.match(previous, handles);
        if (!found) throw new ReminderError('uncertain_delivery');
        previous.handle = found; previous.phase = 'ready'; await this.save(state); return;
      }
      if (state.records.length >= 128) throw new ReminderError('resource_exhausted');
      if (state.records.some(r => r.handle.notificationId === notificationId)) throw new ReminderError('conflict');
      const record = new ReminderRecord(); record.value = frozen;
      record.handle.notificationId = notificationId; record.handle.token = token;
      state.records.push(record);
      state.nextNotificationId = Math.max(state.nextNotificationId, notificationId + 1);
      await this.save(state); // No OS mutation before this succeeds.
      const handle = await this.api.publish(frozen, notificationId, token);
      if (handle.notificationId !== notificationId || handle.token !== token || !Number.isInteger(handle.reminderId) || handle.reminderId < 0)
        throw new ReminderError('host_error');
      record.handle = handle; record.phase = 'ready'; await this.save(state);
    });
  }

  cancel(token: string): Promise<void> {
    return this.serial(async () => {
      const state = await this.load();
      const record = state.records.find(r => r.handle.token === token);
      if (!record || record.phase === 'cancelled') return;
      record.phase = 'cancelling'; await this.save(state);
      const handle = this.match(record, await this.api.list());
      if (handle) await this.api.cancel(handle);
      record.phase = 'cancelled'; await this.save(state);
    });
  }

  recover(reclaim: boolean = false): Promise<ReminderRecord[]> {
    return this.serial(async () => {
      const state = await this.load(); const handles = await this.api.list();
      let changed = false;
      for (const record of state.records) {
        if (record.phase === 'cancelled' || record.phase === 'finished') continue;
        const found = this.match(record, handles);
        const previous = record.phase;
        if (record.phase === 'cancelling') {
          if (found) await this.api.cancel(found);
          record.phase = 'cancelled';
        } else if (found) {
          changed = changed || record.handle.reminderId !== found.reminderId;
          record.handle = found; record.phase = 'ready';
        }
        else if (record.phase === 'ready') record.phase = 'finished';
        else record.phase = 'uncertain'; // May already have fired; never republish blindly.
        changed = changed || previous !== record.phase;
      }
      if (reclaim) {
        const retained = state.records.filter(record => record.openState !== 'acked' ||
          (record.phase !== 'finished' && record.phase !== 'cancelled') || this.match(record, handles) !== undefined);
        changed = changed || retained.length !== state.records.length;
        state.records = retained;
      }
      if (changed) await this.save(state);
      return state.records;
    });
  }

  private match(record: ReminderRecord, handles: ReminderHandle[]): ReminderHandle | undefined {
    const matches = handles.filter(h => h.token === record.handle.token && h.notificationId === record.handle.notificationId);
    if (matches.length > 1) throw new ReminderError('conflict');
    return matches[0];
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation);
    this.tail = result.then(() => {}, () => {}); return result;
  }
  private async load(): Promise<JournalState> {
    const raw = await this.store.read(); if (raw === null) return new JournalState();
    try {
      if (raw.length > 4200000) throw new Error('large');
      const state = JSON.parse(raw) as JournalState;
      if (!state || (state.version !== 1 && state.version !== 2) || !Array.isArray(state.records) || state.records.length > 128) throw new Error('schema');
      const tokens = new Set<string>(); const ids = new Set<number>();
      let minimumNextId = 0x50000000;
      for (const record of state.records) {
        if (!record || !record.handle || !['publishing','ready','cancelling','cancelled','finished','uncertain'].includes(record.phase)) throw new Error('record');
        if (!['none', 'pending', 'acked'].includes(record.openState ?? 'none')) throw new Error('open state');
        reminderDelay(record.value, record.handle.notificationId, record.handle.token, Date.now());
        if (!Number.isInteger(record.handle.reminderId) || record.handle.reminderId < 0 ||
            JSON.stringify(record.value).length > 32000 || tokens.has(record.handle.token) || ids.has(record.handle.notificationId)) throw new Error('identity');
        tokens.add(record.handle.token); ids.add(record.handle.notificationId);
        minimumNextId = Math.max(minimumNextId, record.handle.notificationId + 1);
      }
      // Version 1 journals written before allocation tracking migrate from all
      // retained identities. New journals preserve the cursor after reclamation.
      if (state.version === 1 && state.nextNotificationId === undefined) state.nextNotificationId = minimumNextId;
      if (!Number.isInteger(state.nextNotificationId) || state.nextNotificationId < minimumNextId ||
          state.nextNotificationId > 2147483648) throw new Error('allocation cursor');
      return state;
    } catch (_) { throw new ReminderError('journal_corrupt'); }
  }
  private save(state: JournalState): Promise<void> { state.version = 2; return this.store.write(JSON.stringify(state)); }
}
