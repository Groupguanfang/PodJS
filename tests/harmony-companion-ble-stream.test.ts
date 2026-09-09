import { test, expect } from 'bun:test';
import { CompanionBlePacketStream } from '../platforms/harmony/companion/src/main/ets/CompanionBlePacketStream';
const timer = { schedule() { return () => {}; } };
test('BLE framing matches Android P/1 sequence header and MTU fragmentation', async () => {
  const packets: Uint8Array[] = [];
  const sender = new CompanionBlePacketStream(23, { async write(value) { packets.push(value.slice()); }, close() {} }, timer, 1000);
  await sender.write(new Uint8Array([1, 2, 3]));
  expect(Array.from(packets[0])).toEqual([0x50, 1, 0, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3]);
  await sender.write(new Uint8Array(31).fill(4));
  expect(packets.map(p => p.length)).toEqual([13, 20, 20, 13]);
  expect(packets.map(p => new DataView(p.buffer).getUint32(2))).toEqual([0, 1, 2, 3]);
  const receiver = new CompanionBlePacketStream(23, { async write() {}, close() {} }, timer, 1000);
  for (const packet of packets) { receiver.receive(packet); receiver.receive(packet.slice()); }
  expect(await receiver.read()).toEqual(new Uint8Array([1, 2, 3]));
  expect(await receiver.read()).toEqual(new Uint8Array(31).fill(4));
  sender.close(); receiver.close();
});
test('invalid packets fail closed and a deadline prevents more writes after a late GATT response', async () => {
  for (const value of [new Uint8Array([0x50, 1, 0, 0, 0, 1, 0]), new Uint8Array([0x50, 2, 0, 0, 0, 0, 0]), new Uint8Array(21)]) {
    let closed = 0;
    const stream = new CompanionBlePacketStream(23, { async write() {}, close() { closed++; } }, timer, 1000);
    stream.receive(value); await expect(stream.read()).rejects.toThrow(); expect(closed).toBe(1);
  }
  const replay = new CompanionBlePacketStream(23, { async write() {}, close() {} }, timer, 1000);
  const good = new Uint8Array([0x50, 1, 0, 0, 0, 0, 0, 0, 0, 1, 42]);
  replay.receive(good); expect(await replay.read()).toEqual(new Uint8Array([42]));
  const altered = good.slice(); altered[10] = 43; replay.receive(altered);
  await expect(replay.read()).rejects.toThrow('replay');
  let expire!: () => void, release!: () => void, writes = 0;
  const stream = new CompanionBlePacketStream(23, { write() { writes++; return new Promise(resolve => { release = resolve; }); }, close() {} },
    { schedule(_ms, callback) { expire = callback; return () => {}; } }, 1000);
  const sending = stream.write(new Uint8Array(100)); expire(); await expect(sending).rejects.toThrow('deadline');
  release(); await Promise.resolve(); await Promise.resolve(); expect(writes).toBe(1);
});
