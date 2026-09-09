import { common } from '@kit.AbilityKit';
import { CompanionPairingInfo, CompanionBleCandidate, NativeCompanionBleDiscovery,
  NativeCompanionBleConnector, NativeCompanionBleAcceptor, NativeCompanionSyncCrypto,
  CompanionSyncAttempt, CompanionPairedAttempt, CompanionStreamTimer, CompanionSyncConnector,
  requestCompanionBluetoothPermission } from '@podjs/companion';
import { exampleClient, examplePairings, ExampleClient } from './ExampleClient';
import { onExampleBackground } from './ExampleForeground';

class ConnectionTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const id = setTimeout(expire, ms); return () => clearTimeout(id);
  }
}

@Component
export struct ConnectionPanel {
  @Prop @Watch('activeChanged') active: boolean = false;
  private generation: number = 0;
  private owner: ExampleClient | null = null;
  private discovery: NativeCompanionBleDiscovery | null = null;
  private expires: number = 0;
  private unsubscribe: () => void = () => {};
  private removeBackground: () => void = () => {};
  @State private peers: CompanionPairingInfo[] = [];
  @State private peer: string = '';
  @State private candidates: CompanionBleCandidate[] = [];
  @State private busy: boolean = false;
  @State private phase: string = 'idle';
  @State private status: string = '';
  aboutToAppear(): void {
    this.removeBackground = onExampleBackground(() => this.cancelPending());
    this.refresh();
  }
  aboutToDisappear(): void { this.cancelPending(); this.unsubscribe(); this.removeBackground(); }
  private activeChanged(): void { if (!this.active) this.cancelPending(); else this.refresh(); }
  private cancelPending(): void {
    this.generation++; this.discovery?.cancel(); this.discovery = null;
    this.candidates = []; this.expires = 0; this.busy = false;
    if (this.owner?.connection.status().phase === 'connecting') this.owner.connection.disconnect();
  }
  private async refresh(): Promise<void> {
    if (this.busy) return;
    const epoch = this.generation; this.busy = true;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const owner = await exampleClient(context);
      const pairings = await examplePairings(context);
      const peers = (await pairings.list()).filter((record: CompanionPairingInfo) => record.phase === 'approved');
      if (epoch !== this.generation) return;
      this.owner = owner; this.peers = peers;
      if (!peers.some((record: CompanionPairingInfo) => record.peer === this.peer)) this.peer = '';
      this.unsubscribe();
      this.unsubscribe = owner.connection.subscribe(value => {
        this.phase = value.phase;
        if (value.phase === 'closed' || value.phase === 'failed') this.status = '数据会话已结束，需要手动重新连接。';
      });
      this.status = peers.length === 0 ? '请先在邀请页完成双方配对。' : '';
    } catch (_) { if (epoch === this.generation) this.status = '读取配对失败，请到配对页恢复后重试。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private async discover(): Promise<void> {
    if (!this.active || this.busy || this.phase === 'connected' || this.peer === '') return;
    const epoch = this.generation; this.busy = true; this.candidates = []; this.expires = 0;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      if (!await requestCompanionBluetoothPermission(context, () => epoch !== this.generation)) {
        if (epoch === this.generation) this.status = '需要蓝牙权限才能发现设备。'; return;
      }
      if (epoch !== this.generation) return;
      const discovery = new NativeCompanionBleDiscovery(); this.discovery = discovery;
      this.status = '正在发现，请让对端先点击等待连接。';
      const candidates = await discovery.scan(5000);
      if (epoch !== this.generation) return;
      this.candidates = candidates; this.expires = Date.now() + 30000;
      this.status = candidates.length === 0 ? '未发现设备，请确认对端正在等待后重试。' : '选择附近设备；随后会核对已配对身份。';
    } catch (_) { if (epoch === this.generation) this.status = '发现失败，请检查蓝牙后重试。'; }
    finally { if (epoch === this.generation) { this.discovery = null; this.busy = false; } }
  }
  private fresh(candidate: CompanionBleCandidate): boolean {
    return Date.now() < this.expires && this.candidates.includes(candidate);
  }
  private async connect(candidate: CompanionBleCandidate | null): Promise<void> {
    if (!this.active || this.busy || this.owner === null || this.peer === '' || this.phase === 'connected') return;
    if (candidate !== null && !this.fresh(candidate)) { this.status = '发现结果已过期，请重新发现。'; return; }
    const epoch = this.generation, peer = this.peer, owner = this.owner; this.busy = true;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      if (!await requestCompanionBluetoothPermission(context, () => epoch !== this.generation)) {
        if (epoch === this.generation) this.status = '需要蓝牙权限才能连接。'; return;
      }
      const pairings = await examplePairings(context);
      if (epoch !== this.generation) return;
      if (candidate !== null && !this.fresh(candidate)) { this.status = '发现结果已过期，请重新发现。'; return; }
      const connector: CompanionSyncConnector = candidate === null ? new NativeCompanionBleAcceptor(null, 120000) :
        new NativeCompanionBleConnector(candidate.address, 120000);
      const attempt = new CompanionPairedAttempt(pairings,
        new CompanionSyncAttempt(connector, new NativeCompanionSyncCrypto(), new ConnectionTimer(), 120000), new ConnectionTimer(), 120000);
      this.status = candidate === null ? '等待所选设备认证连接；广播持续最多 30 秒。' : '正在核对已配对身份。';
      await owner.connection.connect(attempt, 'podjs.companion.demo', owner.deviceId, peer, candidate !== null);
      if (epoch === this.generation) { this.candidates = []; this.status = '已连接，可切换到状态页。前台会话最长两分钟，不会自动重连。'; }
    } catch (_) { if (epoch === this.generation) this.status = '连接未完成。请确认双方选择了正确的配对设备，然后重试。'; }
    finally { if (epoch === this.generation) this.busy = false; }
  }
  private disconnect(): void { this.cancelPending(); this.owner?.connection.disconnect(); this.status = '已断开。'; }
  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('连接已配对设备').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
        Text(this.phase === 'connected' ? '数据会话已连接' : this.phase === 'connecting' ? '等待认证连接' : '数据会话未连接')
          .fontSize(18).fontColor('#183149').width('100%')
        Text('一端等待，另一端发现并选择。附近名称不是身份凭据，认证失败不会同步数据。')
          .fontSize(14).fontColor('#42566A').width('100%')
        Button('刷新已配对设备').enabled(!this.busy && this.phase !== 'connected').width('100%').onClick(() => this.refresh())
        ForEach(this.peers, (record: CompanionPairingInfo) => {
          Button((this.peer === record.peer ? '已选择：' : '选择：') + record.peer)
            .enabled(!this.busy && this.phase !== 'connected').width('100%')
            .fontColor(this.peer === record.peer ? '#FFFFFF' : '#183149')
            .backgroundColor(this.peer === record.peer ? '#1767B2' : '#E8F1FA')
            .onClick(() => { this.peer = record.peer; this.candidates = []; this.expires = 0; })
        }, (record: CompanionPairingInfo) => record.peer)
        Button('等待所选设备连接').enabled(this.active && !this.busy && this.peer !== '' && this.phase !== 'connected')
          .width('100%').backgroundColor('#1767B2').onClick(() => this.connect(null))
        Button('发现附近设备').enabled(this.active && !this.busy && this.peer !== '' && this.phase !== 'connected')
          .width('100%').onClick(() => this.discover())
        ForEach(this.candidates, (candidate: CompanionBleCandidate) => {
          Button('连接 ' + (candidate.name || '未命名设备') + '\n' + candidate.address)
            .enabled(!this.busy && this.active).width('100%').onClick(() => this.connect(candidate))
        }, (candidate: CompanionBleCandidate) => candidate.address)
        if (this.busy || this.phase === 'connected') {
          Button('取消并断开').width('100%').fontColor('#183149').backgroundColor('#E8F1FA').onClick(() => this.disconnect())
        }
        if (this.status !== '') { Text(this.status).fontSize(14).fontColor('#42566A').width('100%') }
      }.alignItems(HorizontalAlign.Start).padding(12).width('100%')
    }.width('100%').height('100%')
  }
}
