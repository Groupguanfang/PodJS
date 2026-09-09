import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { ServicePump, ServiceTransport, UnsupportedServices } from '../ServicePump';
import { AuthorizedServices, ServiceAuthority } from '../AuthorizedServices';
import { NotificationPermissions } from '../NotificationPermissions';
import { NativeNotificationPermission } from '../NativeNotificationPermission';
import { NotificationServices } from '../NotificationSchedules';
import { notificationSchedules } from '../NativeReminderStore';
import { BackgroundServices } from '../BackgroundServices';
import { nativeBackgroundSchedules } from '../NativeBackgroundSchedules';
import { attachNotificationDelivery, detachNotificationDelivery, notificationPageVisible,
  refreshNotificationDelivery, notificationEffect } from '../NativeNotificationDelivery';

class NativeAuthority implements ServiceAuthority {
  hasCapability(name: string): boolean { return podjs.hasCapability(name); }
}

class NativeServices implements ServiceTransport {
  private encoder: util.TextEncoder = new util.TextEncoder();
  poll(): string | null { return podjs.pollEffect(); }
  post(json: string): boolean { return podjs.postEvent(this.encoder.encodeInto(json)); }
}

@Entry @Component struct Index {
  private xcomponentController: XComponentController = new XComponentController();
  private services: ServicePump = new ServicePump(new NativeServices(),
    new AuthorizedServices(new NativeAuthority(), new UnsupportedServices()));
  aboutToAppear(): void {
    const facts = podjs.preflight('harmonyos-watch', 2);
    if (!facts.ok) throw new Error(`PodJS preflight: ${facts.error}`);
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    attachNotificationDelivery(context);
    this.services = new ServicePump(new NativeServices(),
      new AuthorizedServices(new NativeAuthority(),
        new BackgroundServices(new NotificationServices(new NotificationPermissions(new NativeNotificationPermission(context)),
          () => notificationSchedules(context)), () => nativeBackgroundSchedules(context))), notificationEffect);
    podjs.setEffectListener(() => this.services.pump());
  }
  aboutToDisappear(): void {
    notificationPageVisible(false);
    detachNotificationDelivery();
    podjs.setEffectListener(null);
    this.services.close();
  }
  onPageShow(): void { notificationPageVisible(true); }
  onPageHide(): void { notificationPageVisible(false); }
  private bootRuntime(): void {
    const context = getContext(this) as common.UIAbilityContext;
    podjs.accessibilityStateLabels([
      context.resourceManager.getStringSync($r('app.string.pod_a11y_checked').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_unchecked').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_mixed').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_expanded').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_collapsed').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_busy').id)
    ]);
    const ok = podjs.boot(
      context.resourceManager.getRawFileContentSync('main.js'),
      context.resourceManager.getRawFileContentSync('main.pak'),
      context.resourceManager.getRawFileContentSync('pod.manifest.json'));
    if (!ok) throw new Error('PodJS native runtime did not start');
    refreshNotificationDelivery();
  }
  private handleCrown(event: CrownEvent): void {
    podjs.rotary(Math.round(event.degree * 1000));
    event.stopPropagation();
  }
  build() {
    Stack() {
      XComponent({ id: 'podjs', type: XComponentType.SURFACE, libraryname: 'podjs_harmony',
        controller: this.xcomponentController })
        .width('100%').height('100%')
        .focusable(false)
        .onLoad(() => this.bootRuntime())
    }
      .id('podjs-crown-target')
      .width('100%').height('100%').backgroundColor('#070A12')
      .focusable(true).defaultFocus(true).focusOnTouch(true)
      .onDigitalCrown((event: CrownEvent) => this.handleCrown(event))
      .onAppear(() => {
        this.getUIContext().getFocusController().requestFocus('podjs-crown-target');
      })
  }
}
