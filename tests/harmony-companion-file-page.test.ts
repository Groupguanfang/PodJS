import { test, expect } from 'bun:test';
import { CompanionFileTerminalReceipt } from '../platforms/harmony/companion/src/main/ets/CompanionFileRequests';
import { decodeFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';

async function harness() {
  let receipt: CompanionFileTerminalReceipt | null = null, pending: any = null, dialog: any = null;
  let creates = 0, drives = 0, consumes = 0;
  let create: () => Promise<any> = async () => ({ sender: true });
  const transport = { async driveFile() { drives++; return 'waiting_consent'; }, fileStatus: () => 'waiting_consent',
    async consumeFileTerminal(value: any) { expect(value).toBe(receipt); consumes++; receipt = null; return true; } };
  let active: any = transport;
  const client = { fileRequests: { terminal: async () => receipt, next: async () => pending, completed: async () => [] },
    createStoredFileSender: async (peer: string, id: string) => { expect(peer).toBe('watch'); expect(id).toBe('file'); creates++; return create(); } };
  const owner = { client, connection: { activeTransport: () => active, status: () => ({ peer: 'watch' }) } };
  const original = await Bun.file('platforms/harmony/companion_example/src/main/ets/pages/Index.ets').text();
  const source = (original.slice(0, original.indexOf('  build() {')) + '}')
    .replace(/^import[\s\S]*?;\n/gm, '').replace('@Entry @Component struct ', 'class ').replace(/@State\s*/g, '');
  const js = new Bun.Transpiler({ loader: 'ts' }).transformSync(source);
  const Page = new Function('decodeFileRequest', js + '\nreturn Index;')(decodeFileRequest);
  const page = new Page(); page.client = client; page.owner = owner;
  page.getUIContext = () => ({ showAlertDialog(value: any) { dialog = value; } });
  const file = { phase: 'complete', manifest: { transfer_id: 'file' } };
  return { page, file, transport, counts: () => [creates, drives, consumes],
    active: (value: any) => { active = value; }, create: (value: () => Promise<any>) => { create = value; },
    pending: (id: string) => { pending = { payload: new TextEncoder().encode(JSON.stringify({ version: 1, method: 'status', transfer_id: id })) }; },
    receipt: () => { receipt = new CompanionFileTerminalReceipt('watch', 'message', 'file', 'a'.repeat(64), 'complete'); return receipt; },
    dialog: () => dialog };
}
async function flush() { for (let i = 0; i < 16; i++) await Promise.resolve(); }
test('file page reuses one driver for consent resume and never changes an occupied peer queue', async () => {
  const h = await harness(); await h.page.sendFile(h.file); await h.page.sendFile(h.file);
  expect(h.counts()).toEqual([1, 2, 0]); expect(h.page.fileStatus).toContain('对端批准');
  const other = await harness(); other.pending('other-file'); await other.page.sendFile(other.file);
  expect(other.counts()).toEqual([0, 0, 0]); expect(other.page.fileStatus).toContain('other-file');
});
test('terminal receipt is displayed before explicit confirmation and consumption releases UI driver', async () => {
  const h = await harness(); await h.page.sendFile(h.file); const receipt = h.receipt();
  await h.page.refreshFile(); expect(h.page.receipt).toBe(receipt);
  await h.page.sendFile(h.file); expect(h.counts()).toEqual([1, 1, 0]);
  h.page.confirmReceipt(); expect(h.counts()[2]).toBe(0);
  h.dialog().secondaryButton.action(); await flush();
  expect(h.counts()[2]).toBe(1); expect(h.page.sendingId).toBe(''); expect(h.page.receipt).toBeNull();
});
test('late sender construction after disconnect cannot drive a stale connection', async () => {
  const h = await harness(); let release!: (value: any) => void;
  h.create(() => new Promise(r => { release = r; }));
  const pending = h.page.sendFile(h.file); await flush(); h.active(null); release({}); await pending;
  expect(h.counts()).toEqual([1, 0, 0]); expect(h.page.sendingId).toBe('');
});
test('stale receipt confirmation does not consume a newer displayed receipt', async () => {
  const h = await harness(); h.receipt(); await h.page.refreshFile(); h.page.confirmReceipt();
  const confirm = h.dialog().secondaryButton.action; h.page.receipt = h.receipt(); confirm(); await flush();
  expect(h.counts()[2]).toBe(0);
});
