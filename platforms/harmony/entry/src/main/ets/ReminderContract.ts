export class ReminderNotification {
  id: string = '';
  title: string = '';
  body: string = '';
  at?: number;
  category?: string;
  actions?: Object[];
  payload?: Object | null;
}

export class ReminderHandle {
  reminderId: number = 0;
  notificationId: number = 0;
  token: string = '';
}

export class ReminderError extends Error {
  constructor(public code: string) { super(code); }
}

export interface ReminderApi {
  publish(value: ReminderNotification, notificationId: number, token: string): Promise<ReminderHandle>;
  cancel(handle: ReminderHandle): Promise<void>;
  list(): Promise<ReminderHandle[]>;
}

/** Validate before any native mutation. IDs/tokens are host-journal identities,
 * never guest-selected OS identifiers. The journal retains payload/category. */
export function reminderDelay(value: ReminderNotification, notificationId: number, token: string, now: number): number {
  if (!value || typeof value.id !== 'string' || value.id.length < 1 || value.id.length > 128 ||
      value.id.includes('\0') || typeof value.title !== 'string' || value.title.length > 256 ||
      value.title.includes('\0') || typeof value.body !== 'string' || value.body.length > 4096 ||
      value.body.includes('\0') || !Number.isInteger(notificationId) || notificationId < 1 ||
      (value.category !== undefined && (typeof value.category !== 'string' || value.category.length > 128 || value.category.includes('\0'))) ||
      notificationId > 2147483647 || !/^[0-9a-f]{32}$/.test(token) || !Number.isSafeInteger(now) || now < 0 ||
      (value.at !== undefined && (!Number.isSafeInteger(value.at) || value.at < 0))) {
    throw new ReminderError('invalid_argument');
  }
  if (value.actions !== undefined) {
    if (!Array.isArray(value.actions)) throw new ReminderError('invalid_argument');
    // Reminder API 23 has close/snooze buttons, not arbitrary application actions.
    if (value.actions.length !== 0) throw new ReminderError('unsupported');
  }
  // System countdowns avoid local-time/DST reinterpretation. Round up, never
  // schedule earlier than requested. Immediate/past requests use one second.
  return Math.max(1, Math.ceil(((value.at ?? now) - now) / 1000));
}
