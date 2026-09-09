import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { notificationManager } from '@kit.NotificationKit';
import reminderAgentManager from '@ohos.reminderAgentManager';
import { ReminderApi, ReminderError, ReminderHandle, ReminderNotification, reminderDelay } from './ReminderContract';

/** System scheduler adapter. A durable host journal must allocate and commit
 * identities before publish, and reconcile returned handles after restart. */
export class NativeReminders implements ReminderApi {
  private context: common.UIAbilityContext;
  constructor(context: common.UIAbilityContext) { this.context = context; }
  private check(): void {
    if (!canIUse('SystemCapability.Notification.ReminderAgent')) throw new ReminderError('unsupported');
  }
  private failure(error: BusinessError): ReminderError {
    if (error instanceof ReminderError) return error;
    if (error.code === 201 || error.code === 1700001) return new ReminderError('permission_denied');
    if (error.code === 1700002) return new ReminderError('resource_exhausted');
    if (error.code === 401) return new ReminderError('invalid_argument');
    if (error.code === 801) return new ReminderError('unsupported');
    return new ReminderError('host_error');
  }
  async list(): Promise<ReminderHandle[]> {
    this.check();
    try {
      const found = await reminderAgentManager.getAllValidReminders();
      const result: ReminderHandle[] = [];
      for (const item of found) {
        const want = item.reminderReq.wantAgent;
        const token = want?.parameters?.['podjsNotificationToken'];
        const notificationId = item.reminderReq.notificationId;
        if (want?.pkgName !== this.context.abilityInfo.bundleName || want?.abilityName !== this.context.abilityInfo.name ||
            typeof token !== 'string' || !/^[0-9a-f]{32}$/.test(token) ||
            notificationId === undefined || !Number.isInteger(notificationId) || notificationId < 1) continue;
        const handle = new ReminderHandle(); handle.reminderId = item.reminderId;
        handle.notificationId = notificationId; handle.token = token;
        result.push(handle);
      }
      return result;
    } catch (error) { throw this.failure(error as BusinessError); }
  }
  async publish(value: ReminderNotification, notificationId: number, token: string): Promise<ReminderHandle> {
    const delay = reminderDelay(value, notificationId, token, Date.now());
    this.check();
    try {
      if (!(await notificationManager.isNotificationEnabled())) throw new ReminderError('permission_denied');
      const existing = await reminderAgentManager.getAllValidReminders();
      for (const item of existing) {
        if (item.reminderReq.notificationId !== notificationId) continue;
        const want = item.reminderReq.wantAgent;
        if (want?.pkgName !== this.context.abilityInfo.bundleName || want?.abilityName !== this.context.abilityInfo.name ||
            want?.parameters?.['podjsNotificationToken'] !== token) throw new ReminderError('conflict');
      }
      const parameters: Record<string, Object> = { 'podjsNotificationToken': token, 'podjsNotificationId': value.id };
      const request: reminderAgentManager.ReminderRequestTimer = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_TIMER,
        triggerTimeInSeconds: delay, notificationId: notificationId,
        title: value.title, content: value.body, ringDuration: 0,
        tapDismissed: true, slotType: notificationManager.SlotType.SERVICE_INFORMATION,
        wantAgent: { pkgName: this.context.abilityInfo.bundleName, abilityName: this.context.abilityInfo.name, parameters: parameters }
      };
      const handle = new ReminderHandle();
      handle.reminderId = await reminderAgentManager.publishReminder(request);
      handle.notificationId = notificationId; handle.token = token;
      return handle;
    } catch (error) { throw this.failure(error as BusinessError); }
  }
  async cancel(handle: ReminderHandle): Promise<void> {
    this.check();
    try {
      const existing = await reminderAgentManager.getAllValidReminders();
      for (const item of existing) {
        if (item.reminderId !== handle.reminderId) continue;
        const want = item.reminderReq.wantAgent;
        if (item.reminderReq.notificationId !== handle.notificationId ||
            want?.pkgName !== this.context.abilityInfo.bundleName || want?.abilityName !== this.context.abilityInfo.name ||
            want?.parameters?.['podjsNotificationToken'] !== handle.token) throw new ReminderError('conflict');
        await reminderAgentManager.cancelReminder(handle.reminderId); return;
      }
    } catch (error) { throw this.failure(error as BusinessError); }
  }
}
