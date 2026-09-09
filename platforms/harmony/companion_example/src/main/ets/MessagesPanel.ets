import { common } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionPairingInfo, CompanionMessageEnvelope, CompanionOutgoingMessage, CompanionIncomingMessage } from '@podjs/companion';
import { ExampleClient, exampleClient, examplePairings } from './ExampleClient';
import { onExampleBackground } from './ExampleForeground';
import { encodeExampleMessage, decodeExampleMessage } from './ExampleMessageText';

class PreparedMessage {
  readonly peer: string;
  readonly id: string;
  readonly text: string;
  readonly envelope: CompanionMessageEnvelope;
  constructor(peer: string, id: string, text: string, envelope: CompanionMessageEnvelope) {
    this.peer = peer; this.id = id; this.text = text; this.envelope = envelope;
  }
}

@Component
export struct MessagesPanel {
  @Prop @Watch('activeChanged') active: boolean = false;
  private generation: number = 0;
  private owner: ExampleClient | null = null;
  private prepared: PreparedMessage | null = null;
  private unsubscribe: () => void = () => {};
  private removeBackground: () => void = () => {};
  @State private peers: CompanionPairingInfo[] = [];
  @State private peer: string = '';
  @State private text: string = '';
  @State private busy: boolean = false;
  @State private retry: boolean = false;
  @State private connectedPeer: string = '';
  @State private status: string = '';
  @State private outgoing: CompanionOutgoingMessage[] = [];
  @State private incoming: CompanionIncomingMessage[] = [];
  aboutToAppear(): void { this.removeBackground = onExampleBackground(() => this.cancelPending()); this.refresh(); }
  aboutToDisappear(): void { this.cancelPending(); this.unsubscribe(); this.removeBackground(); }
  private activeChanged(): void { if (this.active) this.refresh(); else this.cancelPending(); }
  private cancelPending(): void { this.generation++; this.busy = false; }
  private async loadLists(owner: ExampleClient, epoch: number): Promise<void> {
    const peer = this.peer, now = Date.now();
    const outgoing = peer === '' ? [] : await owner.client.outbox.pending(peer, now, 50);
    const incoming = await owner.client.inbox.pending(now, 100);
    if (epoch !== this.generation || peer !== this.peer) return;
    this.outgoing = outgoing; this.incoming = incoming;
  }
  private async refresh(): Promise<void> {
    if (this.busy) return;
    const epoch = this.generation; this.busy = true;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const owner = await exampleClient(context), pairings = await examplePairings(context);
      const peers = (await pairings.list()).filter((record: CompanionPairingInfo) => record.phase === 'approved');
      if (epoch !== this.generation) return;
      this.owner = owner; this.peers = peers;
      if (!peers.some((record: CompanionPairingInfo) => record.peer === this.peer) && this.prepared === null) this.peer = '';
      this.unsubscribe(); this.unsubscribe = owner.connection.subscribe(value => {
        if (epoch === this.generation) this.connectedPeer = value.phase === 'connected' ? value.peer : '';
      });
      await this.loadLists(owner, epoch);
    } catch (_) { if (epoch === this.generation) this.status = '读取消息或配对记录失败，请重试。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private selectPeer(peer: string): void {
    if (this.busy || this.prepared !== null || !this.peers.some((record: CompanionPairingInfo) => record.peer === peer)) return;
    this.peer = peer; this.outgoing = []; this.refresh();
  }
  private async send(): Promise<void> {
    if (!this.active || this.busy || this.owner === null || this.peer === '') return;
    const epoch = this.generation, owner = this.owner; this.busy = true;
    let persisted: boolean = false;
    try {
      const pairings = await examplePairings(this.getUIContext().getHostContext() as common.UIAbilityContext);
      const records = await pairings.list();
      if (epoch !== this.generation) return;
      if (!records.some((record: CompanionPairingInfo) => record.peer === this.peer && record.phase === 'approved')) {
        this.status = '收件设备已不在批准列表中，请刷新配对。'; return;
      }
      if (this.prepared === null) {
        const text = this.text, peer = this.peer, payload = encodeExampleMessage(text);
        const random = await cryptoFramework.createRandom().generateRandom(16);
        if (epoch !== this.generation) return;
        if (random.data.length !== 16) throw new Error('message identity random size');
        let id: string = 'text-'; for (const byte of random.data) id += byte.toString(16).padStart(2, '0');
        this.prepared = new PreparedMessage(peer, id, text, new CompanionMessageEnvelope(Date.now() + 86400000, false, payload));
        this.retry = true;
      }
      const prepared = this.prepared;
      if (prepared.envelope.expiresAt <= Date.now()) {
        this.prepared = null; this.retry = false; this.status = '上次消息已过期。草稿保留，请再次保存以创建新消息。'; return;
      }
      await owner.client.outbox.enqueue(prepared.peer, prepared.id, prepared.envelope, Date.now()); persisted = true;
      if (epoch !== this.generation) return;
      this.prepared = null; this.retry = false;
      if (this.text === prepared.text) this.text = '';
      this.status = '已保存到待发队列，一天后过期；这不代表对端已读。';
      const transport = owner.connection.activeTransport();
      if (transport !== null && owner.connection.status().peer === prepared.peer) {
        try { transport.driveOutgoing(); } catch (_) { this.status += ' 当前连接不可用，请手动重新连接。'; }
      }
      await this.loadLists(owner, epoch);
    } catch (_) {
      if (epoch === this.generation) this.status = persisted ? '消息已入队，但列表刷新失败。请刷新，不要重复创建消息。' :
        this.prepared === null ? '消息未保存，请检查文本内容后重试。' : '保存结果未确认。重试会复用原消息 ID 和内容，不会改发当前新草稿。';
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private display(payload: Uint8Array): string {
    const text = decodeExampleMessage(payload); return text === null ? '未知格式，未自动处理' : text;
  }
  private abandonRetry(): void {
    const prepared = this.prepared;
    if (prepared === null || this.busy) return;
    const epoch = this.generation;
    this.getUIContext().showAlertDialog({ title: '放弃本次保存重试？',
      message: prepared.id + '\n消息可能已经入队。此操作只清除本页重试状态，不会撤回或删除队列中的消息。再次发送会创建新消息。',
      primaryButton: { value: '保留重试', action: () => {} },
      secondaryButton: { value: '放弃重试', action: () => {
        if (epoch !== this.generation || this.prepared !== prepared || this.busy) return;
        this.prepared = null; this.retry = false; this.status = '已放弃本页重试状态；请刷新检查队列，避免重复发送。';
      } }
    });
  }
  private confirmRead(message: CompanionIncomingMessage): void {
    if (!this.active || this.busy || !this.incoming.includes(message) || message.peer !== this.connectedPeer ||
      message.envelope.expiresAt <= Date.now() || decodeExampleMessage(message.envelope.payload) === null) return;
    const epoch = this.generation;
    this.getUIContext().showAlertDialog({ title: '标记已读并发送回执？',
      message: message.peer + '\n' + message.messageId + '\n确认后本机将持久标记为已读，并尝试通知对端。',
      primaryButton: { value: '暂不处理', action: () => {} },
      secondaryButton: { value: '标记已读', action: () => { if (epoch === this.generation && this.incoming.includes(message)) this.markRead(message); } }
    });
  }
  private async markRead(message: CompanionIncomingMessage): Promise<void> {
    if (!this.active || this.busy || this.owner === null || !this.incoming.includes(message) ||
      message.envelope.expiresAt <= Date.now() || decodeExampleMessage(message.envelope.payload) === null) return;
    const owner = this.owner, transport = owner.connection.activeTransport();
    if (transport === null || owner.connection.status().peer !== message.peer) return;
    const epoch = this.generation; this.busy = true;
    try {
      await transport.acknowledge(message);
      if (epoch !== this.generation) return;
      this.status = '本机已标记已读并写出回执；网络写入不等于对端已收到。';
      await this.loadLists(owner, epoch);
    } catch (_) {
      if (epoch === this.generation) {
        this.status = '回执未完成，本机可能已标记已读。重新连接后，对端重发会触发回执，不需要重复处理。';
        try { await this.loadLists(owner, epoch); } catch (_) {}
      }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('文本消息').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
        Text('离线保存，连接后发送。一天后过期；消息消失也可能是过期，不代表已读。').fontSize(14).fontColor('#42566A').width('100%')
        Button('刷新配对与消息').enabled(!this.busy).width('100%').onClick(() => this.refresh())
        ForEach(this.peers, (record: CompanionPairingInfo) => {
          Button((this.peer === record.peer ? '收件设备：' : '选择收件设备：') + record.peer)
            .enabled(!this.busy && !this.retry).width('100%').fontColor(this.peer === record.peer ? '#FFFFFF' : '#183149')
            .backgroundColor(this.peer === record.peer ? '#1767B2' : '#E8F1FA').onClick(() => this.selectPeer(record.peer))
        }, (record: CompanionPairingInfo) => record.peer)
        TextArea({ text: this.text, placeholder: '输入文本消息，最多 4096 字符' }).height(140).maxLength(4096)
          .accessibilityText('待发送文本消息').onChange((value: string) => { this.text = value; })
        Button(this.retry ? '重试上次消息保存' : '保存到待发队列').enabled(this.active && !this.busy && this.peer !== '')
          .width('100%').backgroundColor('#1767B2').onClick(() => this.send())
        if (this.retry) { Button('放弃本次保存重试').enabled(!this.busy).width('100%').onClick(() => this.abandonRetry()) }
        if (this.status !== '') { Text(this.status).fontSize(14).fontColor('#42566A').width('100%') }
        Text('所选设备待发消息（最多显示 50 条）').fontSize(18).fontColor('#183149').width('100%')
        ForEach(this.outgoing, (message: CompanionOutgoingMessage) => {
          Column({ space: 4 }) {
            Text(message.messageId).fontSize(14).fontColor('#42566A').width('100%')
            Text(this.display(message.envelope.payload)).fontSize(16).fontColor('#183149').width('100%')
          }.width('100%')
        }, (message: CompanionOutgoingMessage) => message.peer + '/' + message.messageId)
        Text('待处理消息（最多显示 100 条）').fontSize(18).fontColor('#183149').width('100%')
        ForEach(this.incoming, (message: CompanionIncomingMessage) => {
          Column({ space: 8 }) {
            Text(message.peer + '\n' + message.messageId).fontSize(14).fontColor('#42566A').width('100%')
            Text(this.display(message.envelope.payload)).fontSize(16).fontColor('#183149').width('100%')
            Button('标记已读并回执').enabled(this.active && !this.busy && this.connectedPeer === message.peer &&
              decodeExampleMessage(message.envelope.payload) !== null).width('100%').onClick(() => this.confirmRead(message))
          }.width('100%')
        }, (message: CompanionIncomingMessage) => message.peer + '/' + message.messageId)
      }.alignItems(HorizontalAlign.Start).padding(12).width('100%')
    }.width('100%').height('100%')
  }
}
