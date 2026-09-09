import { test, expect } from 'bun:test';
import { encodeStateUtf8, decodeStateUtf8 } from '../platforms/harmony/companion/src/main/ets/CompanionStatePump';
import { CompanionMessageEnvelope } from '../platforms/harmony/companion/src/main/ets/CompanionMessageWire';

async function codec() {
  const source = (await Bun.file('platforms/harmony/companion_example/src/main/ets/ExampleMessageText.ets').text())
    .replace(/^import.*;\n/gm, '').replace(/export /g, '');
  return new Function('encodeStateUtf8', 'decodeStateUtf8', new Bun.Transpiler({ loader: 'ts' }).transformSync(source) +
    '\nreturn { encodeExampleMessage, decodeExampleMessage };')(encodeStateUtf8, decodeStateUtf8);
}
async function harness() {
  const textCodec = await codec(); let fail = false, driveFail = false, randoms = 0, drives = 0, acks = 0;
  let records: any[] = [], inbox: any[] = [], dialog: any = null;
  let list: () => Promise<any[]> = async () => [{ peer: 'watch', phase: 'approved' }];
  const enqueues: any[][] = [];
  const transport = { driveOutgoing() { drives++; if (driveFail) throw Error('closed'); }, async acknowledge(message: any) {
    acks++; inbox = inbox.filter(value => value !== message);
  } };
  let active: any = transport;
  const owner = { deviceId: 'phone', client: {
    outbox: { pending: async () => records, async enqueue(...args: any[]) { enqueues.push(args); if (fail) throw Error('CAS uncertain'); records = [{ peer: args[0], messageId: args[1], envelope: args[2] }]; } },
    inbox: { pending: async () => inbox }
  }, connection: { activeTransport: () => active, status: () => ({ peer: 'watch' }), subscribe(listener: any) {
    listener({ phase: active === null ? 'closed' : 'connected', peer: 'watch' }); return () => {};
  } } };
  const deps = { ...textCodec, CompanionMessageEnvelope, exampleClient: async () => owner,
    examplePairings: async () => ({ list: () => list() }), onExampleBackground: () => () => {},
    cryptoFramework: { createRandom: () => ({ generateRandom: async () => ({ data: new Uint8Array(16).fill(++randoms) }) }) } };
  const original = await Bun.file('platforms/harmony/companion_example/src/main/ets/MessagesPanel.ets').text();
  const source = (original.slice(0, original.indexOf('  build() {')) + '}')
    .replace(/^import[\s\S]*?;\n/gm, '').replace('@Component', '').replace('export struct ', 'class ')
    .replace(/@Prop\s+@Watch\('[^']+'\)\s*/g, '').replace(/@State\s*/g, '');
  const Panel = new Function(...Object.keys(deps), new Bun.Transpiler({ loader: 'ts' }).transformSync(source) + '\nreturn MessagesPanel;')(...Object.values(deps));
  const panel = new Panel(); panel.getUIContext = () => ({ getHostContext: () => ({}), showAlertDialog(value: any) { dialog = value; } });
  panel.active = true; await panel.refresh(); panel.peer = 'watch'; panel.text = 'hello';
  return { panel, enqueues, counts: () => [randoms, drives, acks], fail: (value: boolean) => { fail = value; },
    driveFail: () => { driveFail = true; }, active: (value: any) => { active = value; }, dialog: () => dialog,
    list: (value: () => Promise<any[]>) => { list = value; },
    incoming: (payload = textCodec.encodeExampleMessage('received')) => {
      const message = { peer: 'watch', messageId: 'received-id', envelope: new CompanionMessageEnvelope(Date.now() + 10000, false, payload), digest: new Uint8Array(32), status: 'pending' };
      inbox = [message]; panel.incoming = inbox; return message;
    } };
}
async function flush() { for (let i = 0; i < 16; i++) await Promise.resolve(); }
test('example text format roundtrips Unicode and rejects unknown, oversized, invalid UTF8 and control payloads', async () => {
  const c = await codec(); const text = '你好 😀\nsecond line';
  expect(c.decodeExampleMessage(c.encodeExampleMessage(text))).toBe(text);
  expect(c.decodeExampleMessage(new Uint8Array([255]))).toBeNull();
  expect(c.decodeExampleMessage(new TextEncoder().encode('unknown'))).toBeNull();
  expect(() => c.encodeExampleMessage('a'.repeat(4097))).toThrow();
  expect(() => c.encodeExampleMessage('spoof\u202e')).toThrow();
});
test('uncertain enqueue retries exact ID, payload and expiry while retaining newer draft', async () => {
  const h = await harness(); h.fail(true); await h.panel.send(); expect(h.panel.retry).toBe(true);
  const first = h.enqueues[0]; h.panel.text = 'new draft'; h.fail(false); await h.panel.send();
  expect(h.enqueues[1].slice(0, 3)).toEqual(first.slice(0, 3));
  expect(h.counts()).toEqual([1, 1, 0]); expect(h.panel.text).toBe('new draft'); expect(h.panel.retry).toBe(false);
  expect(first[2].highPriority).toBe(false); expect(first[2].expiresAt - first[3]).toBeLessThanOrEqual(86400000);
});
test('optional outgoing wake failure cannot turn durable save into failed enqueue', async () => {
  const h = await harness(); h.driveFail(); await h.panel.send();
  expect(h.panel.retry).toBe(false); expect(h.panel.text).toBe(''); expect(h.panel.status).toContain('已保存');
});
test('messages require explicit current read confirmation, connected peer and supported text', async () => {
  const h = await harness(); const message = h.incoming();
  h.panel.confirmRead(message); expect(h.counts()[2]).toBe(0); h.dialog().secondaryButton.action(); await flush();
  expect(h.counts()[2]).toBe(1); expect(h.panel.incoming).toEqual([]);
  const unknown = h.incoming(new Uint8Array([1])); await h.panel.markRead(unknown); expect(h.counts()[2]).toBe(1);
  const fresh = h.incoming(); h.panel.confirmRead(fresh); h.panel.cancelPending(); h.dialog().secondaryButton.action(); await flush();
  expect(h.counts()[2]).toBe(1);
});
test('background during pairing lookup starts no enqueue and abandoning a retry never deletes queued data', async () => {
  const h = await harness(); let release!: (value: any[]) => void;
  h.list(() => new Promise(r => { release = r; })); const pending = h.panel.send(); await flush();
  h.panel.cancelPending(); release([{ peer: 'watch', phase: 'approved' }]); await pending;
  expect(h.enqueues).toEqual([]);
  const retry = await harness(); retry.fail(true); await retry.panel.send(); retry.panel.abandonRetry();
  expect(retry.panel.retry).toBe(true); retry.dialog().secondaryButton.action(); expect(retry.panel.retry).toBe(false);
  expect(retry.enqueues.length).toBe(1); expect(retry.panel.text).toBe('hello');
});
