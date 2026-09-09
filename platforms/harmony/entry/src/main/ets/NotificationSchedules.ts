import { ReminderJournal } from './ReminderJournal';
import { ReminderError, ReminderNotification, reminderDelay } from './ReminderContract';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';

/** Shared app-level actor, including ID allocation and multi-record replacement. */
export class NotificationSchedules {
  private tail: Promise<void> = Promise.resolve();
  constructor(private journal: ReminderJournal, private token: () => Promise<string>) {}
  schedule(value: ReminderNotification, current: () => boolean): Promise<void> {
    let frozen: ReminderNotification;
    try {
      const raw = JSON.stringify(value);
      if (raw.length > 32000) throw new Error('large');
      frozen = JSON.parse(raw) as ReminderNotification;
      reminderDelay(frozen, 1, '00000000000000000000000000000000', Date.now());
    } catch (error) { return Promise.reject(error instanceof ReminderError ? error : new ReminderError('invalid_argument')); }
    return this.serial(async () => {
      if (!current()) return;
      const records = await this.journal.recover(true);
      const previous = records.filter(r => r.value.id === frozen.id && r.phase !== 'cancelled' && r.phase !== 'finished');
      if (previous.length === 1 && JSON.stringify(previous[0].value) === JSON.stringify(frozen)) {
        if (!current()) return;
        await this.journal.publish(frozen, previous[0].handle.notificationId, previous[0].handle.token); return;
      }
      if (records.length >= 128) throw new ReminderError('resource_exhausted');
      const nativeId = await this.journal.allocateNotificationId();
      const token = await this.token();
      reminderDelay(frozen, nativeId, token, Date.now());
      if (records.some(r => r.handle.token === token)) throw new ReminderError('conflict');
      if (!current()) return;
      // Once replacement starts, cancellation suppresses the result, not the
      // already-committed mutation. Failed replacement is explicit and retryable.
      for (const record of previous) await this.journal.cancel(record.handle.token);
      await this.journal.publish(frozen, nativeId, token);
    });
  }
  cancel(id: string, current: () => boolean): Promise<void> {
    if (typeof id !== 'string' || !id || id.length > 128 || id.includes('\0')) return Promise.reject(new ReminderError('invalid_argument'));
    return this.serial(async () => {
      if (!current()) return;
      const records = await this.journal.recover();
      if (!current()) return;
      for (const record of records) if (record.value.id === id && record.phase !== 'cancelled') await this.journal.cancel(record.handle.token);
    });
  }
  list(current: () => boolean): Promise<ReminderNotification[]> {
    return this.serial(async () => {
      if (!current()) return [];
      const records = await this.journal.recover();
      if (records.some(r => r.phase === 'uncertain')) throw new ReminderError('uncertain_delivery');
      return records.filter(r => r.phase === 'ready').map(r => r.value);
    });
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation); this.tail = result.then(() => {}, () => {}); return result;
  }
}

class ScheduleArgs { notification: ReminderNotification = new ReminderNotification(); }
class CancelArgs { id: string = ''; }
export class NotificationServices implements ServiceHandler {
  private active: Map<number, ServiceRequest> = new Map();
  constructor(private permissions: ServiceHandler, private schedules: () => Promise<NotificationSchedules>) {}
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (request.method === 'notifications.status' || request.method === 'notifications.requestPermission') {
      this.permissions.handle(request, complete); return;
    }
    if (!['notifications.schedule', 'notifications.cancel', 'notifications.listPending'].includes(request.method)) {
      const reply = new ServiceReply(); reply.code = 'unsupported'; reply.message = 'Unsupported notification service'; complete(reply); return;
    }
    this.active.set(request.id, request); this.run(request, complete);
  }
  cancel(id: number): void { this.active.delete(id); this.permissions.cancel(id); }
  private async run(request: ServiceRequest, complete: (reply: ServiceReply) => void): Promise<void> {
    const reply = new ServiceReply();
    const current = (): boolean => this.active.get(request.id) === request;
    try {
      const schedules = await this.schedules();
      if (!current()) return;
      if (request.method === 'notifications.schedule') await schedules.schedule((request.args as ScheduleArgs).notification, current);
      else if (request.method === 'notifications.cancel') await schedules.cancel((request.args as CancelArgs).id, current);
      else reply.value = await schedules.list(current);
      reply.ok = true;
    } catch (error) {
      reply.code = error instanceof ReminderError ? error.code : 'host_error'; reply.message = 'Notification operation failed';
    }
    if (!current()) return;
    this.active.delete(request.id); complete(reply);
  }
}
