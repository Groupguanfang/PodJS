import { CompanionIncomingFiles, CompanionIncomingFileOffer } from './CompanionIncomingFiles';

/** Embed with the SAME receiver supplied to CompanionFileChannels.
 * Explicit refresh avoids creating a background polling loop. */
@Component
export struct CompanionIncomingFilesPanel {
  receiver: CompanionIncomingFiles | null = null;
  @State private offers: CompanionIncomingFileOffer[] = [];
  @State private busy: boolean = false;
  @State private error: string = '';
  @State private preview: string = '';

  aboutToAppear(): void { this.refresh(); }
  private async refresh(): Promise<void> {
    if (this.busy || this.receiver === null) return;
    this.busy = true; this.error = '';
    try { await this.receiver.recover(); this.offers = await this.receiver.listLocal(); }
    catch (error) { this.error = '无法读取接收记录，请刷新重试。'; }
    finally { this.busy = false; }
  }
  private phase(value: string): string {
    if (value === 'offered') return '等待你同意';
    if (value === 'accepting') return '正在准备接收';
    if (value === 'accepted') return '等待发送端传输';
    if (value === 'complete') return '接收完成';
    if (value === 'cancelling') return '正在清理';
    return '已取消';
  }
  private async act(offer: CompanionIncomingFileOffer, action: string): Promise<void> {
    if (this.busy || this.receiver === null) return;
    this.busy = true; this.error = ''; this.preview = '';
    try {
      if (action === 'accept') await this.receiver.acceptLocal(offer.peer, offer.manifest.transfer_id);
      else if (action === 'cancel') await this.receiver.cancelLocal(offer.peer, offer.manifest.transfer_id);
      else {
        if (offer.manifest.size === 0) this.preview = '空文件（0 字节）';
        else {
          const bytes = await this.receiver.readCompleteChunk(offer.peer, offer.manifest.transfer_id, 0);
          let hex = '';
          for (let i = 0; i < Math.min(32, bytes.length); i++) hex += bytes[i].toString(16).padStart(2, '0') + ' ';
          this.preview = offer.manifest.transfer_id + '，前 ' + Math.min(32, bytes.length).toString() + ' 字节：' + hex.trim();
        }
      }
      this.offers = await this.receiver.listLocal();
    } catch (error) { this.error = '操作未完成，请刷新查看当前状态后重试。'; }
    finally { this.busy = false; }
  }
  build() {
    Column({ space: 12 }) {
      Text('接收文件').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#183149')
      Text('只有你同意后才接收文件。预览只读取字节，不会打开或执行文件。')
        .fontSize(14).fontColor('#42566A').width('100%')
      Button(this.busy ? '处理中' : '刷新记录').enabled(!this.busy && this.receiver !== null)
        .onClick(() => this.refresh()).backgroundColor('#1767B2').width('100%')
      if (this.receiver === null) { Text('请由应用连接文件接收器。').fontColor('#42566A') }
      if (this.error.length > 0) { Text(this.error).fontColor('#A52332').width('100%') }
      if (this.preview.length > 0) { Text(this.preview).fontSize(14).fontColor('#183149').width('100%') }
      List({ space: 16 }) {
        if (this.offers.length === 0) {
          ListItem() { Text('暂无文件。发送端发起传输后，刷新查看接收请求。').fontColor('#42566A') }
        }
        ForEach(this.offers, (offer: CompanionIncomingFileOffer) => {
          ListItem() {
            Column({ space: 8 }) {
              Text(offer.manifest.transfer_id).fontSize(18).fontWeight(FontWeight.Medium).width('100%')
              Text('来自 ' + offer.peer).fontSize(14).width('100%')
              Text(offer.manifest.size.toString() + ' 字节，' + this.phase(offer.phase)).fontSize(14).width('100%')
              if (offer.phase === 'offered') {
                Button('接收').enabled(!this.busy).width('100%').backgroundColor('#1767B2')
                  .onClick(() => this.act(offer, 'accept'))
              }
              if (offer.phase === 'complete') {
                Button('预览字节').enabled(!this.busy).width('100%').backgroundColor('#1767B2')
                  .onClick(() => this.act(offer, 'preview'))
              }
              if (offer.phase !== 'cancelled') {
                Button(offer.phase === 'offered' ? '拒绝' : '取消并删除本地文件')
                  .enabled(!this.busy).width('100%').backgroundColor('#FFFFFF').fontColor('#A52332')
                  .onClick(() => this.act(offer, 'cancel'))
              }
            }.alignItems(HorizontalAlign.Start).width('100%').padding({ bottom: 12 })
          }
        }, (offer: CompanionIncomingFileOffer) => offer.peer + '/' + offer.manifest.transfer_id)
      }.layoutWeight(1).width('100%')
    }.padding(16).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
