import { test, expect } from 'bun:test';

async function harness() {
  let dialog: any = null, stateListener: any = null, connectionListener: any = null;
  let reads: () => Promise<any> = async () => snapshot('saved');
  let write: (value: any) => Promise<void> = async value => { stateListener?.(snapshot(value.text)); };
  let removed = 0;
  const client = { state: { snapshot: () => reads(), subscribe(listener: any) { stateListener = listener; return () => { removed++; }; },
    set: (_key: string, value: any) => write(value) }, outgoingFiles: { recover: async () => {}, list: async () => [] } };
  const deps = { exampleClient: async () => ({ client, deviceId: 'phone', connection: {
    subscribe(listener: any) { connectionListener = listener; listener({ phase: 'idle' }); return () => { removed++; }; }
  } }), closeExampleConnection() {} };
  const original = await Bun.file('platforms/harmony/companion_example/src/main/ets/pages/Index.ets').text();
  const source = (original.slice(0, original.indexOf('  build() {')) + '}')
    .replace(/^import[\s\S]*?;\n/gm, '').replace('@Entry @Component struct ', 'class ').replace(/@State\s*/g, '');
  const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(source);
  const Page = new Function(...Object.keys(deps), js + '\nreturn Index;')(...Object.values(deps));
  const page = new Page(); page.getUIContext = () => ({ getHostContext: () => ({}), showAlertDialog(value: any) { dialog = value; } });
  return { page, client, dialog: () => dialog, emit: (value: any) => stateListener(value), removed: () => removed,
    connection: (phase: string) => connectionListener({ phase, peer: 'watch' }),
    read: (value: () => Promise<any>) => { reads = value; }, write: (value: (note: any) => Promise<void>) => { write = value; } };
}
function snapshot(text: string) { return { entries: [{ key: 'demo.note', value: { text }, deleted: false }] }; }
async function flush() { for (let n = 0; n < 8; n++) await Promise.resolve(); }

test('remote state updates clean editor but preserves dirty draft until explicit confirmation', async () => {
  const h = await harness(); await h.page.initialize(); expect(h.page.note).toBe('saved');
  h.emit(snapshot('remote')); expect(h.page.note).toBe('remote');
  h.page.editNote('draft'); h.emit(snapshot('new remote'));
  expect(h.page.note).toBe('draft'); expect(h.page.draft).toBe(true);
  h.page.loadSavedNote(); expect(h.page.note).toBe('draft'); h.dialog().secondaryButton.action();
  expect(h.page.note).toBe('new remote'); expect(h.page.draft).toBe(false);
  h.connection('connected'); expect(h.page.connectionStatus).toContain('已连接');
  h.connection('closed'); expect(h.page.connectionStatus).toContain('未连接');
});
test('stale discard confirmation and invalid note payload cannot erase newer editing', async () => {
  const h = await harness(); await h.page.initialize(); h.page.editNote('draft'); h.page.loadSavedNote();
  const confirm = h.dialog().secondaryButton.action; h.page.editNote('new draft'); confirm();
  expect(h.page.note).toBe('new draft');
  h.emit({ entries: [{ key: 'demo.note', value: { text: 42 } }] }); expect(h.page.note).toBe('new draft');
  expect(h.page.noteStatus).toContain('格式');
});
test('subscription observation wins over delayed initialization snapshot and late callbacks are ignored', async () => {
  const h = await harness(); let release!: (value: any) => void;
  h.read(() => new Promise(r => { release = r; }));
  const pending = h.page.initialize(); await flush(); h.emit(snapshot('new')); release(snapshot('old')); await pending;
  expect(h.page.note).toBe('new'); h.page.aboutToDisappear(); h.emit(snapshot('late'));
  expect(h.page.note).toBe('new'); expect(h.removed()).toBe(2);
});
test('save persists captured text, retains edits made while saving and avoids remote receipt claim', async () => {
  const h = await harness(); await h.page.initialize(); let release!: () => void;
  h.write(value => new Promise(r => { release = () => { h.emit(snapshot(value.text)); r(); }; }));
  h.page.editNote('one'); const pending = h.page.save(); h.page.editNote('two'); release(); await pending;
  expect(h.page.note).toBe('two'); expect(h.page.draft).toBe(true); expect(h.page.noteStatus).toContain('不代表对端已收到');
});
