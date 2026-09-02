import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';

@Entry @Component struct Index {
  private xcomponentController: XComponentController = new XComponentController();
  aboutToAppear(): void {
    const facts = podjs.preflight('harmonyos-watch', 1);
    if (!facts.ok) throw new Error(`PodJS preflight: ${facts.error}`);
  }
  private bootRuntime(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const ok = podjs.boot(
      context.resourceManager.getRawFileContentSync('main.js'),
      context.resourceManager.getRawFileContentSync('main.pak'),
      context.resourceManager.getRawFileContentSync('pod.manifest.json'));
    if (!ok) throw new Error('PodJS native runtime did not start');
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
