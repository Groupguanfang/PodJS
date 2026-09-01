import * as podjs from 'libpodjs_harmony.so';

@Entry @Component struct Index {
  private xcomponentController: XComponentController = new XComponentController();
  aboutToAppear(): void {
    const facts = podjs.preflight('harmonyos-watch', 1);
    if (!facts.ok) throw new Error(`PodJS preflight: ${facts.error}`);
  }
  build() {
    Stack() {
      XComponent({ id: 'podjs', type: XComponentType.SURFACE, controller: this.xcomponentController })
        .width('100%').height('100%').onLoad(() => podjs.boot('podjs'))
    }.width('100%').height('100%').backgroundColor('#070A12')
  }
}
