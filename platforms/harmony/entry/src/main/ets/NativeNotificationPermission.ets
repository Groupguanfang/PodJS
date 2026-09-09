import { common } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { NotificationPermissionApi, NotificationPermissionError } from './NotificationPermissions';
import { NativeReminders } from './NativeReminders';
import { reminderJournal } from './NativeReminderStore';
import { ReminderJournal } from './ReminderJournal';

export class NativeNotificationPermission implements NotificationPermissionApi {
  private context: common.UIAbilityContext;
  readonly reminders: NativeReminders;
  constructor(context: common.UIAbilityContext) {
    this.context = context;
    this.reminders = new NativeReminders(context);
  }
  journal(): Promise<ReminderJournal> { return reminderJournal(this.context); }
  private check(): void {
    if (!canIUse('SystemCapability.Notification.Notification')) throw new NotificationPermissionError('unsupported');
  }
  private failure(error: BusinessError): NotificationPermissionError {
    if (error.code === 1600004) return new NotificationPermissionError('denied');
    if (error.code === 1600013) return new NotificationPermissionError('busy');
    if (error.code === 801) return new NotificationPermissionError('unsupported');
    return new NotificationPermissionError('host_error');
  }
  async isEnabled(): Promise<boolean> {
    this.check();
    try { return await notificationManager.isNotificationEnabled(); }
    catch (error) { throw this.failure(error as BusinessError); }
  }
  async requestEnable(): Promise<void> {
    this.check();
    try { await notificationManager.requestEnableNotification(this.context); }
    catch (error) { throw this.failure(error as BusinessError); }
  }
}
