import { test, expect } from 'bun:test';
import { CompanionSyncFraming, encodeSyncPacket, MAX_SYNC_FRAME_BYTES } from '../platforms/harmony/companion/src/main/ets/CompanionSyncFraming';

test('Android big-endian prefix matches and all split boundaries reconstruct exact bytes', () => {
  const body = new Uint8Array([0, 128, 255, 1]);
  const packet = encodeSyncPacket(body);
  expect(Array.from(packet)).toEqual([0, 0, 0, 4, 0, 128, 255, 1]);
  for (let split = 0; split <= packet.length; split++) {
    const decoder = new CompanionSyncFraming(), output: Uint8Array[] = [];
    decoder.feed(packet.subarray(0, split), p => output.push(p));
    decoder.feed(packet.subarray(split), p => output.push(p)); decoder.finish();
    expect(output).toEqual([body]);
  }
});
test('coalesced packets and one-byte fragments preserve order and output ownership', () => {
  const packet = encodeSyncPacket(new Uint8Array([3]));
  const combined = new Uint8Array([...packet, ...packet, ...packet]);
  for (const size of [1, 2, 7, combined.length]) {
    const decoder = new CompanionSyncFraming(), output: Uint8Array[] = [];
    for (let i = 0; i < combined.length; i += size) decoder.feed(combined.slice(i, i + size), p => output.push(p));
    decoder.finish(); expect(output.map(p => p[0])).toEqual([3, 3, 3]);
    output[0][0] = 9; expect(output[1][0]).toBe(3);
  }
});
test('zero, oversized and unsigned-high lengths fail before payload allocation', () => {
  for (const size of [0, MAX_SYNC_FRAME_BYTES + 1, 0xffffffff, 0x80000000]) {
    const header = new Uint8Array(4); new DataView(header.buffer).setUint32(0, size);
    const decoder = new CompanionSyncFraming();
    expect(() => decoder.feed(header, () => {})).toThrow('length');
    expect(() => decoder.feed(new Uint8Array(), () => {})).toThrow('closed');
  }
});
test('partial EOF and callback failure close permanently; clean EOF closes too', () => {
  const packet = encodeSyncPacket(new Uint8Array([1, 2]));
  for (let length = 1; length < packet.length; length++) {
    const decoder = new CompanionSyncFraming(); decoder.feed(packet.slice(0, length), () => {});
    expect(() => decoder.finish()).toThrow('truncated');
  }
  const decoder = new CompanionSyncFraming();
  expect(() => decoder.feed(packet, () => { throw Error('storage'); })).toThrow('storage');
  expect(() => decoder.finish()).toThrow('closed');
  const empty = new CompanionSyncFraming(); empty.finish();
  expect(() => empty.feed(packet, () => {})).toThrow('closed');
});
test('maximum packet and reentrant consumer behavior remain bounded', () => {
  const body = new Uint8Array(MAX_SYNC_FRAME_BYTES).fill(255), decoder = new CompanionSyncFraming();
  let count = 0;
  decoder.feed(encodeSyncPacket(body), p => { expect(p).toEqual(body); count++; });
  expect(count).toBe(1); decoder.finish();
  const recursive = new CompanionSyncFraming(), packet = encodeSyncPacket(new Uint8Array([1]));
  expect(() => recursive.feed(packet, () => recursive.feed(packet, () => {}))).toThrow('reentrant');
  expect(() => encodeSyncPacket(new Uint8Array())).toThrow('size');
});
