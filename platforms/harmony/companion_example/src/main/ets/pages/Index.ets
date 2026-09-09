import { common } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { NativeCompanionClient, CompanionIncomingFilesPanel, CompanionOutgoingFile, pickCompanionFile,
  CompanionStateSnapshot, CompanionFileSender, CompanionMessageTransport, CompanionFileTerminalReceipt,
  decodeFileRequest, CompanionFileManifest } from '@podjs/companion';
import { exampleClient, closeExampleConnection, ExampleClient } from '../ExampleClient';
import { PairingsPanel } from '../PairingsPanel';
import { PairingInvitationPanel } from '../PairingInvitationPanel';
import { ConnectionPanel } from '../ConnectionPanel';
import { MessagesPanel } from '../MessagesPanel';
class Note { text: string = ''; }

@Entry @Component struct Index {
  private client: NativeCompanionClient | null = null;
  private owner: ExampleClient | null = null;
  private fileSender: CompanionFileSender | null = null;
  private fileTransport: CompanionMessageTransport | null = null;
  private generation: number = 0;
  private removeState: () => void = () => {};
  private removeConnection: () => void = () => {};
  private savedNote: string = '';
  private editRevision: number = 0;
  @State private ready: boolean = false;
  @State private deviceId: string = '';
  @State private note: string = '';
  @State private draft: boolean = false;
  @State private noteStatus: string = '';
  @State private connectionStatus: string = '数据会话未连接';
  @State private connected: boolean = false;
  @State private sendingId: string = '';
  @State private fileStatus: string = '';
  @State private receipt: CompanionFileTerminalReceipt | null = null;
  @State private status: string = '';
  @State private busy: boolean = false;
  @State private files: CompanionOutgoingFile[] = [];
  @State private activeTab: number = 0;
  @State private pageActive: boolean = true;
  aboutToAppear(): void { this.pageActive = true; this.busy = false; this.ready = false; this.initialize(); }
  aboutToDisappear(): void {
    this.pageActive = false; this.generation++; this.removeState(); this.removeConnection(); closeExampleConnection();
  }
  private acceptState(snapshot: CompanionStateSnapshot): void {
    const entry = snapshot.entries.find(value => value.key === 'demo.note');
    let text: string = '';
    if (entry !== undefined && !entry.deleted) {
      const value = entry.value as Note | null;
      if (value === null || typeof value.text !== 'string' || value.text.length > 8192) {
        this.noteStatus = '已保存数据不是可显示的笔记格式，本地草稿未改动。'; return;
      }
      text = value.text;
    }
    const changed = text !== this.savedNote;
    this.savedNote = text;
    if (!this.draft) this.note = text;
    this.draft = this.note !== this.savedNote;
    if (changed) this.noteStatus = this.draft ? '已保存内容有更新；你的未保存草稿已保留。' : '已载入最新已保存内容。';
  }
  private editNote(value: string): void { this.note = value; this.editRevision++; this.draft = value !== this.savedNote; }
  private loadSavedNote(): void {
    if (this.busy || !this.draft) return;
    const epoch = this.generation, revision = this.editRevision;
    this.getUIContext().showAlertDialog({ title: '放弃未保存的草稿？', message: '载入最新已保存内容会替换当前编辑框。',
      primaryButton: { value: '保留草稿', action: () => {} },
      secondaryButton: { value: '载入已保存内容', action: () => {
        if (epoch !== this.generation || revision !== this.editRevision || this.busy) return;
        this.note = this.savedNote; this.draft = false; this.editRevision++; this.noteStatus = '已载入最新已保存内容。';
      } }
    });
  }
  private async initialize(): Promise<void> {
    const epoch = ++this.generation;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const owner = await exampleClient(context);
      if (epoch !== this.generation) return;
      this.removeState(); this.removeConnection();
      let observations: number = 0;
      this.removeState = owner.client.state.subscribe(snapshot => {
        if (epoch === this.generation) { observations++; this.acceptState(snapshot); }
      });
      this.removeConnection = owner.connection.subscribe(value => {
        if (epoch !== this.generation) return;
        this.connected = value.phase === 'connected';
        if (!this.connected) { this.fileSender = null; this.fileTransport = null; this.sendingId = ''; this.receipt = null; }
        this.connectionStatus = value.phase === 'connected' ? '数据会话已连接：' + value.peer :
          value.phase === 'connecting' ? '正在认证连接：' + value.peer : '数据会话未连接，请到连接页手动连接';
      });
      const saved = await owner.client.state.snapshot();
      if (epoch !== this.generation) return;
      if (observations === 0) this.acceptState(saved);
      await owner.client.outgoingFiles.recover();
      const files = await owner.client.outgoingFiles.list();
      if (epoch !== this.generation) return;
      this.owner = owner; this.client = owner.client; this.deviceId = owner.deviceId; this.files = files; this.ready = true;
    } catch (error) {
      if (epoch === this.generation) { this.removeState(); this.removeConnection(); this.status = '无法打开本地数据，请重新进入页面。'; }
    }
  }
  private async save(): Promise<void> {
    if (this.client === null || this.busy) return;
    const epoch = this.generation; this.busy = true;
    try {
      const note = new Note(); note.text = this.note; await this.client.state.set('demo.note', note);
      if (epoch === this.generation) this.noteStatus = '已持久保存到本机。连接时会发送；此提示不代表对端已收到。';
    }
    catch (error) { if (epoch === this.generation) this.noteStatus = '保存失败，草稿已保留，请重试。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private async choose(transferId: string = ''): Promise<void> {
    if (this.client === null || this.busy) return;
    const epoch = this.generation; this.busy = true; this.status = '';
    try {
      let id = transferId;
      if (id === '') {
        const random = await cryptoFramework.createRandom().generateRandom(16);
        for (const byte of random.data) id += byte.toString(16).padStart(2, '0');
      }
      const result = await pickCompanionFile(this.getUIContext().getHostContext() as common.UIAbilityContext,
        this.client, id, () => epoch !== this.generation);
      if (epoch !== this.generation) return;
      this.files = await this.client.outgoingFiles.list(); this.status = result === null ? '未选择文件。' : '文件已导入本机，尚未发送。';
    } catch (error) {
      if (epoch === this.generation) {
        this.status = '导入未完成。请选择原文件继续，或清理暂存。不同内容不能覆盖同一传输。';
        try { const files = await this.client.outgoingFiles.list(); if (epoch === this.generation) this.files = files; }
        catch (_) { if (epoch === this.generation) this.status += ' 暂存列表读取失败，请重新进入页面。'; }
      }
    }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private confirmRemove(file: CompanionOutgoingFile): void {
    if (this.busy || this.sendingId === file.manifest.transfer_id) return;
    const epoch = this.generation, id = file.manifest.transfer_id;
    this.getUIContext().showAlertDialog({ title: '清理本地发送源？',
      message: id + '\n只删除应用内副本，不删除系统中选择的原文件。该传输 ID 将不能再次导入。',
      primaryButton: { value: '保留', action: () => {} },
      secondaryButton: { value: '清理副本', action: () => { if (epoch === this.generation) this.removeSource(id); } }
    });
  }
  private async removeSource(id: string): Promise<void> {
    if (this.client === null || this.busy || this.sendingId === id) return;
    const epoch = this.generation; this.busy = true;
    try {
      await this.client.outgoingFiles.remove(id);
      const files = await this.client.outgoingFiles.list();
      if (epoch === this.generation) { this.files = files; this.status = '本地副本已清理，原文件未改动。'; }
    } catch (error) { if (epoch === this.generation) this.status = '清理未完成，请重试；删除意图已记录时会在下次进入恢复。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private progressText(phase: string): string {
    if (phase === 'waiting_consent') return '等待对端接收批准。对端批准后，请点击同一文件的继续发送。';
    if (phase === 'complete') return '对端报告文件接收完成；不代表已导出到系统文件夹。';
    if (phase === 'cancelled') return '对端报告传输已取消。';
    if (phase === 'idle') return '暂无本次连接的活动文件。可发送本地完整副本，或继续上次传输。';
    return '请求已排队或正在等待回执。可刷新查看进度，不代表文件已送达。';
  }
  private async refreshFile(): Promise<void> {
    if (this.owner === null || this.client === null || this.busy) return;
    const owner = this.owner, transport = owner.connection.activeTransport();
    if (transport === null) { this.fileStatus = '请先到连接页建立数据会话，再查看该对端回执。'; return; }
    const epoch = this.generation, peer = owner.connection.status().peer; this.busy = true;
    try {
      const receipt = await this.client.fileRequests.terminal(peer);
      if (epoch !== this.generation || owner.connection.activeTransport() !== transport) return;
      this.receipt = receipt;
      this.fileStatus = this.progressText(receipt === null ? transport.fileStatus() : receipt.phase);
    } catch (_) { if (epoch === this.generation) this.fileStatus = '读取传输回执失败，未清理任何记录，请重试。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private async sendFile(file: CompanionOutgoingFile): Promise<void> {
    if (this.owner === null || this.client === null || this.busy || file.phase !== 'complete') return;
    const owner = this.owner, client = this.client, transport = owner.connection.activeTransport();
    if (transport === null) { this.fileStatus = '请先到连接页连接接收设备。'; return; }
    const epoch = this.generation, peer = owner.connection.status().peer, id = file.manifest.transfer_id;
    this.busy = true;
    try {
      const receipt = await client.fileRequests.terminal(peer);
      if (epoch !== this.generation || owner.connection.activeTransport() !== transport) return;
      if (receipt !== null) { this.receipt = receipt; this.fileStatus = '请先查看并确认上次传输回执，再发送下一个文件。'; return; }
      const pending = await client.fileRequests.next(peer), completed = await client.fileRequests.completed(peer);
      if (epoch !== this.generation || owner.connection.activeTransport() !== transport) return;
      const records = pending === null ? completed : [pending].concat(completed);
      for (const record of records) {
        const request = decodeFileRequest(record.payload);
        const queuedId = request.method === 'offer' ? (request.manifest as CompanionFileManifest).transfer_id : request.transfer_id;
        if (queuedId !== id) { this.fileStatus = '请先继续原传输：' + queuedId + '。不会覆盖其队列。'; return; }
      }
      if (this.fileTransport !== transport) { this.fileSender = null; this.sendingId = ''; }
      if (this.fileSender !== null && this.sendingId !== id) { this.fileStatus = '请先完成当前传输并确认回执。'; return; }
      if (this.fileSender === null) {
        const sender = await client.createStoredFileSender(peer, id);
        if (epoch !== this.generation || owner.connection.activeTransport() !== transport) return;
        this.fileSender = sender; this.fileTransport = transport; this.sendingId = id;
      }
      const phase = await transport.driveFile(this.fileSender);
      if (epoch === this.generation && owner.connection.activeTransport() === transport) this.fileStatus = this.progressText(phase);
    } catch (_) { if (epoch === this.generation) this.fileStatus = '发送未完成。队列和本地副本保留；请检查连接后继续同一传输。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private confirmReceipt(): void {
    const receipt = this.receipt;
    if (receipt === null || this.busy) return;
    const epoch = this.generation;
    this.getUIContext().showAlertDialog({ title: '确认并清理本地回执？',
      message: receipt.peer + '\n' + receipt.transferId + '\n' + this.progressText(receipt.phase) +
        '\n清理后释放发送队列；不删除本地副本或对端文件。本机将不再保留这条完成回执。',
      primaryButton: { value: '保留回执', action: () => {} },
      secondaryButton: { value: '确认并清理回执', action: () => { if (epoch === this.generation && this.receipt === receipt) this.consumeReceipt(receipt); } }
    });
  }
  private async consumeReceipt(receipt: CompanionFileTerminalReceipt): Promise<void> {
    if (this.owner === null || this.busy) return;
    const owner = this.owner, transport = owner.connection.activeTransport();
    if (transport === null || owner.connection.status().peer !== receipt.peer) return;
    const epoch = this.generation; this.busy = true;
    try {
      const consumed = await transport.consumeFileTerminal(receipt);
      if (epoch !== this.generation || owner.connection.activeTransport() !== transport) return;
      if (consumed) {
        this.receipt = null; this.fileSender = null; this.fileTransport = null; this.sendingId = '';
        this.fileStatus = '本地回执已清理，发送队列已释放；文件副本未删除。';
      } else this.fileStatus = '回执或队列已变化，请刷新后重新确认。';
    } catch (_) { if (epoch === this.generation) this.fileStatus = '清理回执未完成，请重新连接后刷新确认；不会删除文件副本。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  build() {
    Column({ space: 12 }) {
      Text('PodJS Companion').fontSize(26).fontWeight(FontWeight.Bold).fontColor('#183149')
      Text(this.connectionStatus).fontSize(14).fontColor('#42566A').maxLines(3)
      Text(this.deviceId).fontSize(12).fontColor('#42566A').maxLines(2)
      if (this.status.length > 0) { Text(this.status).fontSize(14).width('100%') }
      if (this.ready && this.client !== null) {
        Tabs() {
          TabContent() { PairingsPanel() }.tabBar('配对')
          TabContent() { PairingInvitationPanel({ active: this.pageActive && this.activeTab === 1 }) }.tabBar('邀请')
          TabContent() { ConnectionPanel({ active: this.pageActive && this.activeTab === 2 }) }.tabBar('连接')
          TabContent() {
            Column({ space: 12 }) {
              Text('离线笔记').fontSize(20).width('100%')
              TextArea({ text: this.note, placeholder: '写下要同步的内容' }).height(180).maxLength(8192)
                .accessibilityText('离线笔记内容').onChange((value: string) => this.editNote(value))
              Text(this.draft ? '有未保存草稿' : '编辑框与本机已保存内容一致').fontSize(14).fontColor('#42566A').width('100%')
              if (this.noteStatus !== '') { Text(this.noteStatus).fontSize(14).fontColor('#42566A').width('100%') }
              Button('保存到本机').enabled(!this.busy).width('100%').backgroundColor('#1767B2').onClick(() => this.save())
              if (this.draft) {
                Button('载入最新已保存内容').enabled(!this.busy).width('100%').fontColor('#183149').backgroundColor('#E8F1FA')
                  .onClick(() => this.loadSavedNote())
              }
            }.padding(12).width('100%')
          }.tabBar('状态')
          TabContent() {
            Column({ space: 12 }) {
              Button(this.busy ? '正在导入' : '选择文件并导入').enabled(!this.busy).width('100%')
                .backgroundColor('#1767B2').onClick(() => this.choose())
              Button('刷新传输进度与回执').enabled(!this.busy && this.connected).width('100%').onClick(() => this.refreshFile())
              if (this.fileStatus !== '') { Text(this.fileStatus).fontSize(14).fontColor('#42566A').width('100%') }
              if (this.receipt !== null) {
                Text(this.receipt.transferId + '\n' + this.progressText(this.receipt.phase)).fontSize(14).width('100%')
                Button('查看并确认回执').enabled(!this.busy && this.connected).width('100%').onClick(() => this.confirmReceipt())
              }
              List({ space: 8 }) {
                ForEach(this.files, (file: CompanionOutgoingFile) => {
                  ListItem() {
                    Column({ space: 8 }) {
                      Text(file.manifest.transfer_id + '\n' + file.manifest.size.toString() + ' 字节 · ' + file.phase).fontSize(14).width('100%')
                      if (file.phase === 'staging') {
                        Button('选择原文件继续导入').enabled(!this.busy).width('100%').onClick(() => this.choose(file.manifest.transfer_id))
                      }
                      if (file.phase === 'complete') {
                        Button(this.sendingId === file.manifest.transfer_id ? '继续发送' : '发送或恢复此文件')
                          .enabled(!this.busy && this.connected).width('100%').backgroundColor('#1767B2').onClick(() => this.sendFile(file))
                      }
                      if (file.phase !== 'removed') {
                        Button('清理本地副本').enabled(!this.busy && this.sendingId !== file.manifest.transfer_id).width('100%').fontColor('#A52332').backgroundColor('#FFFFFF')
                          .onClick(() => this.confirmRemove(file))
                      }
                    }.width('100%').padding({ bottom: 12 })
                  }
                }, (file: CompanionOutgoingFile) => file.manifest.transfer_id)
              }.layoutWeight(1).width('100%')
            }.padding(12).width('100%').height('100%')
          }.tabBar('发送源')
          TabContent() { CompanionIncomingFilesPanel({ receiver: this.client.incomingFiles }) }.tabBar('接收')
          TabContent() { MessagesPanel({ active: this.pageActive && this.activeTab === 6 }) }.tabBar('消息')
        }.onChange((index: number) => { this.activeTab = index; }).layoutWeight(1).width('100%')
      }
    }.padding(16).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
