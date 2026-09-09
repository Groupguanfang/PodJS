import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { NotificationDelivery } from './NotificationDelivery';
import { reminderJournal } from './NativeReminderStore';

let abilityVisible: boolean = false;
let pageVisible: boolean = false;
let timer: number = -1;
let delivery: NotificationDelivery | null = null;
const encoder: util.TextEncoder = new util.TextEncoder();

function tick(): void {
  delivery?.pump().catch(() => { console.error('PodJS notification delivery failed; journal remains pending'); });
}
export function refreshNotificationDelivery(): void {
  const active = abilityVisible && pageVisible && delivery !== null && podjs.hasCapability('notification.local');
  delivery?.setActive(active);
  if (!active && timer !== -1) { clearInterval(timer); timer = -1; }
  if (active && timer === -1) { tick(); timer = setInterval(tick, 1000); }
}
export function attachNotificationDelivery(context: common.UIAbilityContext): void {
  detachNotificationDelivery();
  delivery = new NotificationDelivery(() => reminderJournal(context),
    () => podjs.hasCapability('notification.local'),
    (json: string) => podjs.postEvent(encoder.encodeInto(json)), () => podjs.monotonicMillis());
  refreshNotificationDelivery();
}
export function detachNotificationDelivery(): void {
  delivery?.setActive(false); delivery = null;
  if (timer !== -1) { clearInterval(timer); timer = -1; }
}
export function notificationAbilityVisible(value: boolean): void { abilityVisible = value; refreshNotificationDelivery(); }
export function notificationPageVisible(value: boolean): void { pageVisible = value; refreshNotificationDelivery(); }

class AckEffect { t: string = ''; eventId: string = ''; }
export function notificationEffect(raw: string): void {
  if (!delivery || !podjs.hasCapability('notification.local')) return;
  let effect: AckEffect;
  try { effect = JSON.parse(raw) as AckEffect; } catch (_) { return; }
  if (!effect || effect.t !== 'notification.ack' || typeof effect.eventId !== 'string' ||
      !/^[0-9a-f]{32}\.open$/.test(effect.eventId)) return;
  delivery.acknowledge(effect.eventId).catch(() => {
    console.error('PodJS notification ACK was not persisted; event remains pending');
  });
}
