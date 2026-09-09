import { common } from '@kit.AbilityKit';
import image from '@ohos.multimedia.image';
import { CompanionPairingInvitation, CompanionPairingInvitationLease, CompanionStreamTimer,
  CompanionInitialPairing, CompanionPairingApprovalGate, CompanionPairingApprovalRequest,
  CompanionSyncAttempt, CompanionSyncConnector, NativeCompanionDistributedConnector,
  NativeCompanionDistributedAcceptor, requestCompanionDistributedPermission,
  NativeCompanionBleDiscovery, CompanionBleCandidate, NativeCompanionBleConnector, NativeCompanionBleAcceptor,
  requestCompanionBluetoothPermission,
  NativeCompanionSyncCrypto, renderCompanionPairingQr, scanCompanionPairingInvitation } from '@podjs/companion';
import { exampleClient, examplePairings } from './ExampleClient';
import { onExampleBackground } from './ExampleForeground';

class InvitationTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const timer = setTimeout(expire, ms); return () => clearTimeout(timer);
  }
}

@Component
export struct PairingInvitationPanel {
  @Prop @Watch('onActiveChanged') active: boolean = false;
  private generation: number = 0;
  private invitation: CompanionPairingInvitation | null = null;
  private lease: CompanionPairingInvitationLease | null = null;
  private pairing: CompanionInitialPairing | null = null;
  private approval: CompanionPairingApprovalGate | null = null;
  private answer: ((approved: boolean) => void) | null = null;
  private initiator: boolean = false;
  private discovery: NativeCompanionBleDiscovery | null = null;
  private candidatesExpireAt: number = 0;
  private removeBackground: () => void = () => {};
  @State private useBle: boolean = true;
  @State private candidates: CompanionBleCandidate[] = [];
  @State private peer: string = '';
  @State private address: string = '';
  @State private confirmation: CompanionPairingApprovalRequest | null = null;
  @State private bitmap: image.PixelMap | null = null;
  @State private busy: boolean = false;
  @State private device: string = '';
  @State private status: string = '';
  @State private failed: boolean = false;
  @State private hasInvitation: boolean = false;

  aboutToAppear(): void { this.removeBackground(); this.removeBackground = onExampleBackground(() => this.cancel()); }
  aboutToDisappear(): void { this.removeBackground(); this.clear(); }
  private onActiveChanged(): void {
    if (!this.active) { this.clear(); this.failed = false; this.status = '临时邀请已清除，请重新生成或扫描。'; }
  }
  private releaseImage(): void {
    const bitmap = this.bitmap; this.bitmap = null;
    if (bitmap !== null) bitmap.release().catch(() => {});
  }
  private clear(): void {
    this.generation++;
    if (this.discovery !== null) this.discovery.cancel(); this.discovery = null;
    this.candidates = []; this.candidatesExpireAt = 0; this.address = '';
    if (this.pairing !== null) this.pairing.cancel(); this.pairing = null;
    if (this.approval !== null) this.approval.dismiss(); this.approval = null;
    this.answer = null; this.confirmation = null;
    const lease = this.lease; this.lease = null;
    if (lease !== null) lease.close();
    if (this.invitation !== null) this.invitation.close(); this.invitation = null;
    this.releaseImage(); this.device = ''; this.hasInvitation = false; this.busy = false;
  }
  private cancel(): void {
    const mayExist = this.pairing !== null && this.pairing.localCredentialMayExist();
    this.clear(); this.failed = false;
    this.status = mayExist ? '已取消。凭据可能已保存，请到配对管理检查双方记录。' : '已取消邀请。未保存本机配对凭据。';
  }
  private retain(invitation: CompanionPairingInvitation, epoch: number): CompanionPairingInvitationLease {
    const lease = new CompanionPairingInvitationLease(invitation, { now: () => Date.now() }, new InvitationTimer(), () => {
      if (epoch !== this.generation) return;
      this.releaseImage(); this.hasInvitation = false;
      if (this.pairing === null) { this.clear(); this.status = '邀请已结束，请重新生成或扫描。'; }
    });
    lease.assertActive(); this.invitation = invitation; this.lease = lease; this.hasInvitation = true; return lease;
  }
  private async createInvitation(): Promise<void> {
    if (!this.active || this.busy) return;
    this.clear(); const epoch = this.generation; this.busy = true; this.failed = false; this.status = '';
    let invitation: CompanionPairingInvitation | null = null;
    try {
      const client = await exampleClient(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (epoch !== this.generation) return;
      invitation = await CompanionPairingInvitation.create('podjs.companion.demo', client.deviceId, Date.now(), new NativeCompanionSyncCrypto());
      if (epoch !== this.generation) { invitation.close(); return; }
      const lease = this.retain(invitation, epoch);
      const bitmap = await renderCompanionPairingQr(lease);
      if (epoch !== this.generation) { await bitmap.release(); return; }
      try { lease.assertActive(); } catch (error) { await bitmap.release(); throw error as Error; }
      this.bitmap = bitmap; this.device = client.deviceId; this.initiator = false; this.peer = ''; this.address = '';
      this.status = '先让对端扫码，再填写对端显示的本机标识并开始等待。双方选择同一种链路。';
    } catch (_) {
      if (invitation !== null) invitation.close();
      if (epoch === this.generation) { this.clear(); this.failed = true; this.status = '邀请二维码生成失败或已过期，请重试。'; }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private async scan(): Promise<void> {
    if (!this.active || this.busy) return;
    this.clear(); const epoch = this.generation; this.busy = true; this.failed = false; this.status = '';
    let invitation: CompanionPairingInvitation | null = null;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const client = await exampleClient(context);
      if (epoch !== this.generation) return;
      invitation = await scanCompanionPairingInvitation(context, 'podjs.companion.demo', client.deviceId, () => epoch !== this.generation);
      if (epoch !== this.generation) { if (invitation !== null) invitation.close(); return; }
      if (invitation === null) { this.status = '未扫描邀请。'; return; }
      this.retain(invitation, epoch); this.device = client.deviceId; this.initiator = true;
      this.peer = invitation.deviceId; this.address = '';
      this.status = this.useBle ? '请将本机标识告诉对端。对端开始等待后，再发现设备并连接；双方仍需确认。' :
        '请将本机标识告诉对端。双方选择系统分布式链路，并分别填写该链路的对端地址。';
    } catch (_) {
      if (invitation !== null) invitation.close();
      if (epoch === this.generation) { this.clear(); this.failed = true; this.status = '扫码不可用，或邀请无效、已过期。请在对端重新生成后重试。'; }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private selectTransport(useBle: boolean): void {
    if (!this.active || this.busy || this.useBle === useBle) return;
    this.useBle = useBle; this.address = ''; this.candidates = []; this.candidatesExpireAt = 0;
    this.failed = false;
    this.status = useBle ? '已选择独立蓝牙。双方需选择相同链路。' : '已选择系统分布式连接，请手动核对该链路的对端地址。不会使用扫描得到的虚拟地址。';
  }
  private freshCandidates(): boolean {
    const remaining = this.candidatesExpireAt - Date.now();
    return remaining > 0 && remaining <= 30000;
  }
  private selectCandidate(candidate: CompanionBleCandidate): void {
    if (!this.active || this.busy || !this.useBle || !this.initiator || !this.candidates.includes(candidate)) return;
    if (!this.freshCandidates()) { this.expireCandidates(); return; }
    this.address = candidate.address; this.failed = false;
    this.status = '已选择候选设备，尚未连接或信任。请点击连接，并在验证邀请后核对双方身份。';
  }
  private expireCandidates(): void {
    this.candidates = []; this.address = ''; this.candidatesExpireAt = 0;
    this.failed = true; this.status = '设备候选已过期，请让对端保持等待并重新发现。';
  }
  private async discover(): Promise<void> {
    if (!this.active || this.busy || !this.useBle || !this.initiator || this.lease === null) return;
    const epoch = this.generation, lease = this.lease;
    this.busy = true; this.failed = false; this.candidates = []; this.address = ''; this.candidatesExpireAt = 0;
    this.status = '正在检查蓝牙权限…';
    let discovery: NativeCompanionBleDiscovery | null = null;
    try {
      lease.assertActive();
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const granted = await requestCompanionBluetoothPermission(context, () => epoch !== this.generation);
      if (epoch !== this.generation) return;
      if (!granted) { this.status = '未获得蓝牙权限，尚未扫描。可重试或取消邀请。'; return; }
      lease.assertActive();
      discovery = new NativeCompanionBleDiscovery(); this.discovery = discovery;
      this.status = '正在发现 PodJS 蓝牙设备（五秒）…';
      const candidates = await discovery.scan(5000);
      if (epoch !== this.generation) return;
      lease.assertActive();
      this.candidates = candidates; this.candidatesExpireAt = Date.now() + 30000;
      this.status = candidates.length === 0 ? '未发现设备。请让对端在独立蓝牙模式开始等待，然后重试。' :
        '请选择你正在操作的设备。列表仅供连接选址，不代表已配对；候选三十秒后失效。';
    } catch (_) {
      if (epoch === this.generation) { this.failed = true; this.status = '发现未完成。请检查蓝牙开关、权限、设备支持情况及邀请有效期。'; }
    } finally {
      if (discovery !== null) discovery.cancel();
      if (this.discovery === discovery) this.discovery = null;
      if (epoch === this.generation) this.busy = false;
    }
  }
  private async connect(): Promise<void> {
    if (!this.active || this.busy || this.invitation === null || this.lease === null) return;
    const invitation = this.invitation, lease = this.lease, epoch = this.generation;
    const peer = this.peer.trim(), address = this.address.trim(), useBle = this.useBle;
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(peer)) {
      this.failed = true; this.status = '请填写对端显示的应用设备标识。'; return;
    }
    if (useBle && this.initiator) {
      if (!this.freshCandidates()) { this.expireCandidates(); return; }
      if (!this.candidates.some((candidate: CompanionBleCandidate) => candidate.address === address)) {
        this.failed = true; this.status = '请先从发现结果中选择设备。'; return;
      }
    } else if (!useBle && !/^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(address)) {
      this.failed = true; this.status = '请填写分布式链路的对端蓝牙地址（如 AA:BB:CC:DD:EE:FF）。'; return;
    }
    this.busy = true; this.failed = false; this.status = '正在检查连接权限…';
    let pairing: CompanionInitialPairing | null = null;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const client = await exampleClient(context);
      if (epoch !== this.generation) return;
      if (peer === client.deviceId || (this.initiator && peer !== invitation.deviceId)) throw new Error('invalid peer');
      lease.assertActive();
      const granted = useBle ? await requestCompanionBluetoothPermission(context, () => epoch !== this.generation) :
        await requestCompanionDistributedPermission(context, () => epoch !== this.generation);
      if (epoch !== this.generation) return;
      if (!granted) { this.status = '未获得所选链路的权限，尚未连接。可重新尝试或取消邀请。'; return; }
      const pairings = await examplePairings(context);
      if (epoch !== this.generation) return;
      lease.assertActive();
      if (useBle && this.initiator && !this.freshCandidates()) { this.expireCandidates(); return; }
      const crypto = new NativeCompanionSyncCrypto();
      let connector: CompanionSyncConnector;
      if (useBle) {
        connector = this.initiator ? new NativeCompanionBleConnector(address, 120000) : new NativeCompanionBleAcceptor(null, 120000);
      } else {
        connector = this.initiator ? new NativeCompanionDistributedConnector(address, 'podjs-pairing-v1', 120000) :
          new NativeCompanionDistributedAcceptor(address, 'podjs-pairing-v1', 120000);
      }
      const gate = new CompanionPairingApprovalGate({
        show: (request: CompanionPairingApprovalRequest, answer: (approved: boolean) => void) => {
          if (epoch !== this.generation || !this.active) { answer(false); return; }
          this.confirmation = request; this.answer = answer; this.status = '已验证邀请持有者，请核对双方身份后确认。';
        },
        hide: () => { if (epoch === this.generation) { this.confirmation = null; this.answer = null; } }
      });
      this.approval = gate;
      pairing = new CompanionInitialPairing(invitation, lease, pairings,
        new CompanionSyncAttempt(connector, crypto, new InvitationTimer(), 120000), crypto, gate);
      this.pairing = pairing;
      this.status = this.initiator ? '正在连接对端并验证邀请…' :
        (useBle ? '正在广播并等待对端验证邀请。请让对端现在发现设备；广播最多持续三十秒。' : '正在等待指定对端连接并验证邀请…');
      await pairing.start(client.deviceId, peer, this.initiator);
      if (epoch !== this.generation) return;
      this.clear(); this.failed = false; this.status = '双方已确认并保存配对凭据。可到配对管理查看；数据同步连接仍需单独建立。';
    } catch (_) {
      if (epoch !== this.generation) return;
      const mayExist = pairing !== null && pairing.localCredentialMayExist();
      this.clear(); this.failed = true;
      this.status = mayExist ? '配对未完整结束，凭据可能已保存。请检查双方配对记录，不要直接覆盖。' :
        '未完成配对。请检查双方所选链路、设备支持情况、地址和标识、权限及邀请有效期，然后重新生成邀请。';
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private respond(approved: boolean): void {
    const answer = this.answer; this.answer = null;
    if (answer !== null) { answer(approved); if (approved) this.status = '本机已确认，正在等待对端确认并保存凭据…'; }
  }
  private respondFor(request: CompanionPairingApprovalRequest, approved: boolean): void {
    if (this.confirmation !== request) return;
    this.respond(approved);
  }
  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('配对邀请').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
        Text('二维码含临时凭据，请勿截图转发。最多五分钟有效，切换标签页或离开页面会清除。')
          .fontSize(14).fontColor('#42566A').width('100%')
        Button('生成本机邀请').enabled(this.active && !this.busy).width('100%').backgroundColor('#1767B2').onClick(() => this.createInvitation())
        Button('扫描对端邀请').enabled(this.active && !this.busy).width('100%').backgroundColor('#1767B2').onClick(() => this.scan())
        if (this.bitmap !== null) {
          Row() {
            Image(this.bitmap).width('100%').constraintSize({ maxWidth: 256 }).aspectRatio(1)
              .objectFit(ImageFit.Contain).interpolation(ImageInterpolation.None)
              .accessibilityText('临时配对二维码，仅供你信任的设备扫描')
          }.justifyContent(FlexAlign.Center).padding(32).backgroundColor('#FFFFFF').width('100%')
        }
        if (this.device.length > 0) { Text('本机标识：' + this.device).fontSize(14).fontColor('#183149').width('100%') }
        if (this.hasInvitation && !this.busy) {
          Text('连接指定设备').fontSize(18).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
          Row({ space: 8 }) {
            Button(this.useBle ? '独立蓝牙（已选）' : '独立蓝牙').layoutWeight(1)
              .backgroundColor(this.useBle ? '#1767B2' : '#E9F0F6').fontColor(this.useBle ? '#FFFFFF' : '#183149').onClick(() => this.selectTransport(true))
            Button(this.useBle ? '系统分布式' : '系统分布式（已选）').layoutWeight(1)
              .backgroundColor(this.useBle ? '#E9F0F6' : '#1767B2').fontColor(this.useBle ? '#183149' : '#FFFFFF').onClick(() => this.selectTransport(false))
          }.width('100%')
          Text(this.useBle ? '独立蓝牙不依赖厂商账号。双方需选择相同链路，发现结果不代表设备身份。' :
            '可选系统链路需设备支持，并手动核对对端地址。不会自动回退到另一种链路。').fontSize(14).fontColor('#42566A').width('100%')
          TextInput({ placeholder: '对端应用设备标识', text: this.peer }).enabled(!this.initiator)
            .accessibilityText('对端应用设备标识').onChange((value: string) => this.peer = value)
          if (this.useBle && this.initiator) {
            Button('发现附近 PodJS 设备').width('100%').backgroundColor('#1767B2').onClick(() => this.discover())
            ForEach(this.candidates, (candidate: CompanionBleCandidate) => {
              Button() {
                Column({ space: 4 }) {
                  Text(candidate.name.length > 0 ? candidate.name : '未命名 PodJS 候选').fontSize(16).width('100%')
                    .fontColor(this.address === candidate.address ? '#FFFFFF' : '#183149')
                  Text(candidate.address + ' · ' + candidate.rssi + ' dBm' + (this.address === candidate.address ? ' · 已选择' : ''))
                    .fontSize(14).width('100%').fontColor(this.address === candidate.address ? '#FFFFFF' : '#183149')
                }.alignItems(HorizontalAlign.Start).padding(12).width('100%')
              }.width('100%').constraintSize({ minHeight: 64 }).backgroundColor(this.address === candidate.address ? '#1767B2' : '#E9F0F6')
                .fontColor(this.address === candidate.address ? '#FFFFFF' : '#183149').onClick(() => this.selectCandidate(candidate))
            }, (candidate: CompanionBleCandidate) => candidate.address)
          } else if (!this.useBle) {
            TextInput({ placeholder: '分布式链路的对端蓝牙地址', text: this.address }).maxLength(17)
              .accessibilityText('分布式链路的对端蓝牙地址').onChange((value: string) => this.address = value)
          } else {
            Text('请确认对端已扫描二维码，再开始等待。开始后二维码会隐藏，并广播最多三十秒。').fontSize(14).fontColor('#42566A').width('100%')
          }
          Button(this.initiator ? '连接并验证邀请' : '等待指定设备连接').width('100%').backgroundColor('#1767B2').onClick(() => this.connect())
        }
        if (this.confirmation !== null) {
          this.approvalControls(this.confirmation)
        }
        if (this.status.length > 0) { Text(this.status).fontSize(14).fontColor(this.failed ? '#A52332' : '#42566A').width('100%') }
        if (this.busy || this.hasInvitation) {
          Button(this.busy ? '取消当前操作' : '取消邀请').width('100%').backgroundColor('#FFFFFF').fontColor('#A52332').onClick(() => this.cancel())
        }
      }.alignItems(HorizontalAlign.Start).padding(12).width('100%')
    }.width('100%').height('100%').backgroundColor('#FFFFFF')
  }
  @Builder
  private approvalControls(request: CompanionPairingApprovalRequest) {
    Column({ space: 12 }) {
      Text('确认配对身份').fontSize(18).fontWeight(FontWeight.Bold).fontColor('#183149').width('100%')
      Text('应用：' + request.app + '\n本机：' + request.local + '\n对端：' + request.peer)
        .fontSize(14).fontColor('#183149').width('100%')
      Text('确认后允许双方保存长期配对凭据。若设备不认识，请拒绝。').fontSize(14).fontColor('#42566A').width('100%')
      Button('确认此设备').width('100%').backgroundColor('#1767B2').onClick(() => this.respondFor(request, true))
      Button('拒绝配对').width('100%').backgroundColor('#FFFFFF').fontColor('#A52332').onClick(() => this.respondFor(request, false))
    }.alignItems(HorizontalAlign.Start).width('100%')
  }
}
