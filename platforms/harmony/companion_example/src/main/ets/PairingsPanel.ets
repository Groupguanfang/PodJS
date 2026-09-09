import { common } from '@kit.AbilityKit';
import { CompanionPairings, CompanionPairingInfo } from '@podjs/companion';
import { examplePairings } from './ExampleClient';

@Component
export struct PairingsPanel {
  private owner: CompanionPairings | null = null;
  private generation: number = 0;
  @State private records: CompanionPairingInfo[] = [];
  @State private busy: boolean = false;
  @State private status: string = '';
  @State private failed: boolean = false;
  @State private loaded: boolean = false;
  aboutToAppear(): void { this.generation++; this.busy = false; this.refresh(); }
  aboutToDisappear(): void { this.generation++; }
  private async refresh(): Promise<void> {
    if (this.busy) return;
    const epoch = this.generation; this.busy = true; this.status = ''; this.failed = false;
    try {
      const owner = await examplePairings(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (epoch !== this.generation) return;
      this.owner = owner;
      await owner.recover();
      const records = await owner.list();
      if (epoch !== this.generation) return;
      this.records = records; this.loaded = true;
    } catch (_) {
      if (epoch === this.generation) { this.failed = true; this.status = '配对记录暂不可用，或上次清理未完成。请关闭其他实例后重试恢复。'; }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private phase(value: string): string {
    if (value === 'approved') return '已配对，连接状态另行确认';
    if (value === 'importing') return '配对未完成，需要恢复清理';
    return '撤销未完成，需要恢复清理';
  }
  private confirmRevoke(record: CompanionPairingInfo): void {
    if (this.busy || this.owner === null) return;
    const epoch = this.generation, peer = record.peer;
    this.getUIContext().showAlertDialog({ title: '撤销这台设备的配对？',
      message: peer + '\n立即断开使用此配对的连接，并删除本机配对凭据。不会删除笔记或文件副本；重新连接需要再次配对。',
      primaryButton: { value: '保留配对', action: () => {} },
      secondaryButton: { value: '撤销配对', action: () => { if (epoch === this.generation) this.revoke(peer); } }
    });
  }
  private async revoke(peer: string): Promise<void> {
    if (this.busy || this.owner === null) return;
    const epoch = this.generation; this.busy = true; this.status = ''; this.failed = false;
    try {
      await this.owner.revoke(peer);
      const records = await this.owner.list();
      if (epoch !== this.generation) return;
      this.records = records; this.status = '已撤销配对。重新连接需要再次配对。';
    } catch (_) {
      if (epoch === this.generation) {
        this.failed = true;
        this.status = '撤销未完成。连接已停用，请点击恢复并刷新重试清理。';
        try {
          const records = await this.owner.list();
          if (epoch === this.generation) this.records = records;
        } catch (_) { /* Keep the last visible list and recovery action. */ }
      }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  build() {
    Column({ space: 12 }) {
      Text('配对设备').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
      Text('这里管理本机已批准的设备。密钥不会显示或复制到剪贴板。').fontSize(14).fontColor('#42566A').width('100%')
      Button(this.busy ? '正在处理配对记录' : '恢复并刷新').enabled(!this.busy).width('100%')
        .backgroundColor('#1767B2').onClick(() => this.refresh())
      if (this.status.length > 0) { Text(this.status).fontSize(14).fontColor(this.failed ? '#A52332' : '#183149').width('100%') }
      List({ space: 16 }) {
        if (this.loaded && this.records.length === 0) {
          ListItem() { Text('暂无配对设备。请在邀请页生成或扫描邀请，并由双方明确确认。').fontSize(14).fontColor('#42566A').width('100%') }
        }
        ForEach(this.records, (record: CompanionPairingInfo) => {
          ListItem() {
            Column({ space: 8 }) {
              Text(record.peer).fontSize(18).fontWeight(FontWeight.Medium).fontColor('#183149').width('100%')
              Text(this.phase(record.phase)).fontSize(14).fontColor('#42566A').width('100%')
              Button('撤销配对').enabled(!this.busy).width('100%').backgroundColor('#FFFFFF').fontColor('#A52332')
                .accessibilityText('撤销与 ' + record.peer + ' 的配对').onClick(() => this.confirmRevoke(record))
            }.alignItems(HorizontalAlign.Start).width('100%')
          }
        }, (record: CompanionPairingInfo) => record.peer)
      }.layoutWeight(1).width('100%')
    }.alignItems(HorizontalAlign.Start).padding(12).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
