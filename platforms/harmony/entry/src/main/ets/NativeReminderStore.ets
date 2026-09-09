import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';
import { ReminderJournal, ReminderStore } from './ReminderJournal';
import { NativeReminders } from './NativeReminders';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { NotificationSchedules } from './NotificationSchedules';

class NativeReminderStore implements ReminderStore {
  read(): Promise<string | null> { return podjs.journalRead(); }
  write(json: string): Promise<void> { return podjs.journalWrite(json); }
}

let coordinator: Promise<ReminderJournal> | null = null;
let filesDirectory: string = '';
let schedules: Promise<NotificationSchedules> | null = null;

async function notificationToken(): Promise<string> {
  const random = await cryptoFramework.createRandom().generateRandom(16);
  let token = '';
  for (const byte of random.data) token += byte.toString(16).padStart(2, '0');
  return token;
}

export function notificationSchedules(context: common.UIAbilityContext): Promise<NotificationSchedules> {
  const journal = reminderJournal(context); // Validate context even when cached.
  if (schedules === null) schedules = journal.then(value => new NotificationSchedules(value, notificationToken));
  return journal.then(() => schedules as Promise<NotificationSchedules>);
}

/** One coordinator across page recreation; each record transaction and its OS
 * side effect share this actor. Initialization is lazy and does not publish. */
export function reminderJournal(context: common.UIAbilityContext): Promise<ReminderJournal> {
  if (coordinator !== null) {
    if (filesDirectory !== context.filesDir) return Promise.reject(new Error('Journal context mismatch'));
    return coordinator;
  }
  filesDirectory = context.filesDir;
  const pending = podjs.journalOpen(filesDirectory).then(() =>
    new ReminderJournal(new NativeReminderStore(), new NativeReminders(context)));
  coordinator = pending;
  // Keep a failed initialization rejected: opening may have acquired ownership
  // before delivery failed. Do not create competing coordinators automatically.
  return pending;
}
