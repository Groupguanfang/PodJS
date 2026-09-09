import { test, expect } from 'bun:test';

async function harness() {
  let permission: () => Promise<boolean> = async () => true;
  const routes: string[] = [], connections: any[][] = [];
  let phase = 'idle', disconnects = 0;
  const owner = { deviceId: 'phone-local', connection: {
    status: () => ({ phase }), subscribe: () => () => {},
    disconnect() { disconnects++; phase = 'closed'; },
    async connect(...args: any[]) { connections.push(args); phase = 'connected'; }
  } };
  const deps = {
    exampleClient: async () => owner, examplePairings: async () => ({ list: async () => [{ peer: 'watch', phase: 'approved' }] }),
    onExampleBackground: () => () => {}, requestCompanionBluetoothPermission: () => permission(),
    NativeCompanionSyncCrypto: class {}, CompanionSyncAttempt: class {}, CompanionPairedAttempt: class {},
    NativeCompanionBleAcceptor: class { constructor(address: any) { expect(address).toBeNull(); routes.push('server'); } },
    NativeCompanionBleConnector: class { constructor(address: string) { routes.push(address); } },
    NativeCompanionBleDiscovery: class { async scan() { return [{ address: 'AA:BB:CC:DD:EE:FF', name: 'watch', rssi: -40 }]; } cancel() {} }
  };
  const original = await Bun.file('platforms/harmony/companion_example/src/main/ets/ConnectionPanel.ets').text();
  const source = (original.slice(0, original.indexOf('  build() {')) + '}')
    .replace(/^import[\s\S]*?;\n/gm, '').replace('@Component', '').replace('export struct ', 'class ')
    .replace(/@Prop\s+@Watch\('[^']+'\)\s*/g, '').replace(/@State\s*/g, '');
  const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(source);
  const Panel = new Function(...Object.keys(deps), js + '\nreturn ConnectionPanel;')(...Object.values(deps));
  const panel = new Panel(); panel.getUIContext = () => ({ getHostContext: () => ({}) });
  panel.active = true; await panel.refresh(); panel.peer = 'watch';
  return { panel, routes, connections, setPhase: (value: string) => { phase = value; }, disconnects: () => disconnects,
    permission: (value: () => Promise<boolean>) => { permission = value; } };
}
test('paired UI waits with pinned application peer and connects only explicitly selected fresh routes', async () => {
  const h = await harness(); await h.panel.connect(null);
  expect(h.routes).toEqual(['server']); expect(h.connections[0].slice(1)).toEqual(['podjs.companion.demo', 'phone-local', 'watch', false]);
  const c = await harness(); await c.panel.discover(); expect(c.routes).toEqual([]);
  const candidate = c.panel.candidates[0]; await c.panel.connect({ ...candidate }); expect(c.routes).toEqual([]);
  await c.panel.connect(candidate); expect(c.routes).toEqual([candidate.address]); expect(c.connections[0][4]).toBe(true);
});
test('pending permission cancellation and expired routes never start a connection', async () => {
  const h = await harness(); let release!: (value: boolean) => void;
  h.permission(() => new Promise(r => { release = r; }));
  const pending = h.panel.connect(null); h.panel.cancelPending(); release(true); await pending;
  expect(h.routes).toEqual([]);
  const c = await harness(); await c.panel.discover(); c.panel.expires = 0;
  await c.panel.connect(c.panel.candidates[0]); expect(c.routes).toEqual([]);
});
test('leaving tab cancels a handshake but preserves a handed-off data session', async () => {
  const h = await harness(); h.setPhase('connecting'); h.panel.cancelPending(); expect(h.disconnects()).toBe(1);
  h.setPhase('connected'); h.panel.cancelPending(); expect(h.disconnects()).toBe(1);
  h.panel.disconnect(); expect(h.disconnects()).toBe(2);
});
