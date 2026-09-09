import { test, expect } from 'bun:test';
import { CompanionPairingApprovalGate } from '../platforms/harmony/companion/src/main/ets/CompanionPairingApprovalGate';
import { onExampleBackground, cancelExampleForeground } from '../platforms/harmony/companion_example/src/main/ets/ExampleForeground';

// Exercise actual component methods with native dependencies substituted. ArkUI
// build/layout is checked separately by the SDK, not by this method-only harness.
async function harness() {
  let permission: () => Promise<boolean> = async () => true;
  let connects = 0, cancelled = 0, mode = 'success';
  let discovered: () => Promise<any[]> = async () => [{ address: 'AA:BB:CC:DD:EE:FF', name: 'Watch', rssi: -40 }];
  let discoveryCancelled = 0, bluetoothRequests = 0, distributedRequests = 0;
  const transports: string[] = [];
  const dependencies = {
    CompanionPairingApprovalGate, onExampleBackground,
    exampleClient: async () => ({ deviceId: 'phone-local' }),
    examplePairings: async () => ({}),
    requestCompanionDistributedPermission: () => { distributedRequests++; return permission(); },
    requestCompanionBluetoothPermission: () => { bluetoothRequests++; return permission(); },
    NativeCompanionSyncCrypto: class {},
    CompanionSyncAttempt: class {},
    NativeCompanionDistributedConnector: class { constructor() { connects++; transports.push('distributed-client'); } },
    NativeCompanionDistributedAcceptor: class { constructor() { connects++; transports.push('distributed-server'); } },
    NativeCompanionBleConnector: class { constructor() { connects++; transports.push('ble-client'); } },
    NativeCompanionBleAcceptor: class { constructor(address: string | null) { expect(address).toBeNull(); connects++; transports.push('ble-server'); } },
    NativeCompanionBleDiscovery: class {
      closed = false;
      scan(ms: number) { expect(ms).toBe(5000); return discovered(); }
      cancel() { if (!this.closed) { this.closed = true; discoveryCancelled++; } }
    },
    CompanionInitialPairing: class {
      may = false;
      gate: CompanionPairingApprovalGate;
      constructor(...args: any[]) { this.gate = args[5]; }
      async start(local: string, peer: string) {
        if (!await this.gate.confirm('podjs.companion.demo', local, peer, '11'.repeat(32))) throw Error('declined');
        this.may = true;
        if (mode === 'partial') throw Error('lost stored ack');
      }
      cancel() { cancelled++; this.gate.dismiss(); }
      localCredentialMayExist() { return this.may; }
    }
  };
  const original = await Bun.file('platforms/harmony/companion_example/src/main/ets/PairingInvitationPanel.ets').text();
  const methods = original.slice(0, original.indexOf('  build() {')) + '}';
  const source = methods.replace(/^import[\s\S]*?;\n/gm, '')
    .replace('@Component', '').replace('export struct ', 'class ')
    .replace(/@Prop\s+@Watch\('[^']+'\)\s*/g, '').replace(/@State\s*/g, '');
  const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(source);
  const Panel = new Function(...Object.keys(dependencies), js + '\nreturn PairingInvitationPanel;')(...Object.values(dependencies));
  const panel = new Panel();
  panel.getUIContext = () => ({ getHostContext: () => ({}) });
  panel.active = true; panel.invitation = { deviceId: 'watch-peer', close() {} };
  panel.lease = { assertActive() {}, close() {} }; panel.hasInvitation = true;
  panel.peer = 'watch-peer'; panel.address = 'AA:BB:CC:DD:EE:FF'; panel.initiator = true;
  panel.candidates = [{ address: panel.address, name: 'Watch', rssi: -40 }]; panel.candidatesExpireAt = Date.now() + 30000;
  return { panel, connects: () => connects, cancelled: () => cancelled,
    transports, requests: () => [bluetoothRequests, distributedRequests], discoveryCancelled: () => discoveryCancelled,
    discover: (value: () => Promise<any[]>) => { discovered = value; },
    permission: (value: () => Promise<boolean>) => { permission = value; }, mode: (value: string) => { mode = value; } };
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }

test('invitation panel requires an explicit answer and reports partial save separately', async () => {
  const h = await harness(); const run = h.panel.connect(); await flush();
  expect(h.connects()).toBe(1); expect(h.panel.busy).toBe(true);
  expect(h.panel.confirmation.peer).toBe('watch-peer');
  h.panel.respond(true); await run;
  expect(h.panel.status).toContain('双方已确认'); expect(h.panel.confirmation).toBeNull();
  expect(h.panel.busy).toBe(false);
  const partial = await harness(); partial.mode('partial');
  const failed = partial.panel.connect(); await flush(); partial.panel.respond(true); await failed;
  expect(partial.panel.status).toContain('凭据可能已保存'); expect(partial.panel.failed).toBe(true);
});
test('cancel during permission and background during approval prevent late approval', async () => {
  const h = await harness(); let release!: (value: boolean) => void;
  h.permission(() => new Promise(resolve => { release = resolve; }));
  const run = h.panel.connect(); await flush(); h.panel.cancel(); release(true); await run;
  expect(h.connects()).toBe(0); expect(h.panel.status).toContain('已取消');
  const pending = await harness(); pending.panel.aboutToAppear();
  const attempt = pending.panel.connect(); await flush(); const stale = pending.panel.answer;
  cancelExampleForeground(); stale(true); await attempt;
  expect(pending.panel.confirmation).toBeNull(); expect(pending.panel.status).toContain('已取消');
  expect(pending.cancelled()).toBe(1); pending.panel.aboutToDisappear();
});
test('permission denial retains invitation for retry; bad addresses never connect', async () => {
  const h = await harness(); h.permission(async () => false); await h.panel.connect();
  expect(h.connects()).toBe(0); expect(h.panel.hasInvitation).toBe(true); expect(h.panel.busy).toBe(false);
  h.panel.address = 'invalid'; await h.panel.connect(); expect(h.connects()).toBe(0); expect(h.panel.failed).toBe(true);
});
test('BLE discovery requires fresh explicit candidate selection and never reuses it for distributed routing', async () => {
  const h = await harness(); await h.panel.discover();
  expect(h.connects()).toBe(0); expect(h.panel.address).toBe(''); expect(h.panel.candidates.length).toBe(1);
  await h.panel.connect(); expect(h.connects()).toBe(0);
  const candidate = h.panel.candidates[0]; h.panel.selectCandidate(candidate);
  const run = h.panel.connect(); await flush(); expect(h.transports).toEqual(['ble-client']);
  h.panel.respond(true); await run; expect(h.requests()).toEqual([2, 0]);
  const other = await harness(); const stale = other.panel.candidates[0];
  other.panel.selectTransport(false); expect(other.panel.candidates).toEqual([]); expect(other.panel.address).toBe('');
  other.panel.selectCandidate(stale); expect(other.panel.address).toBe('');
  other.panel.address = 'AA:BB:CC:DD:EE:01'; const distributed = other.panel.connect(); await flush();
  expect(other.transports).toEqual(['distributed-client']); expect(other.requests()).toEqual([0, 1]);
  other.panel.respond(false); await distributed;
});
test('BLE issuer needs peer application identity but not a guessed hardware address', async () => {
  const h = await harness(); h.panel.initiator = false; h.panel.address = ''; h.panel.candidates = [];
  const run = h.panel.connect(); await flush(); expect(h.transports).toEqual(['ble-server']);
  h.panel.respond(false); await run;
});
test('expired selection and a scan completing after background cannot open a radio connection', async () => {
  const expired = await harness(); expired.panel.candidatesExpireAt = Date.now() - 1;
  await expired.panel.connect(); expect(expired.connects()).toBe(0); expect(expired.panel.candidates).toEqual([]);
  const late = await harness(); let release!: (rows: any[]) => void;
  late.discover(() => new Promise(resolve => { release = resolve; })); late.panel.aboutToAppear();
  const scanning = late.panel.discover(); await flush(); cancelExampleForeground();
  release([{ address: 'AA:BB:CC:DD:EE:FF', name: 'Late', rssi: -30 }]); await scanning;
  expect(late.discoveryCancelled()).toBe(1); expect(late.panel.candidates).toEqual([]); expect(late.panel.hasInvitation).toBe(false);
  expect(late.connects()).toBe(0); late.panel.aboutToDisappear();
});
test('a button callback bound to an older approval view cannot answer a new request', async () => {
  const h = await harness(); let answered = 0;
  const old = { peer: 'old' }, current = { peer: 'new' };
  h.panel.confirmation = current; h.panel.answer = () => answered++;
  h.panel.respondFor(old, true); expect(answered).toBe(0);
  h.panel.respondFor(current, false); expect(answered).toBe(1);
});
test('candidate expiry while permission is pending requires rediscovery, not a late connection', async () => {
  const h = await harness(); let release!: (allowed: boolean) => void;
  h.permission(() => new Promise(resolve => { release = resolve; }));
  const run = h.panel.connect(); await flush(); h.panel.candidatesExpireAt = Date.now() - 1;
  release(true); await run;
  expect(h.connects()).toBe(0); expect(h.panel.hasInvitation).toBe(true); expect(h.panel.busy).toBe(false);
  expect(h.panel.status).toContain('过期');
});
