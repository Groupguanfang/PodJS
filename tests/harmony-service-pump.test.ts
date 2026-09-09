import { describe, expect, test } from 'bun:test';
import { ServicePump, ServiceReply, ServiceRequest, UnsupportedServices,
  type ServiceHandler, type ServiceTransport } from '../platforms/harmony/entry/src/main/ets/ServicePump';

class Transport implements ServiceTransport {
  effects: string[] = [];
  output: ServiceReply[] = [];
  blocked = false;
  poll() { return this.effects.shift() ?? null; }
  post(json: string) {
    if (this.blocked) return false;
    this.output.push(JSON.parse(json)); return true;
  }
  request(id: number, version = 1) {
    this.effects.push(JSON.stringify({ t: 'service.request', version, id, method: 'notifications.status', args: {} }));
  }
  cancel(id: number) { this.effects.push(JSON.stringify({ t: 'service.cancel', version: 1, id })); }
}
class Deferred implements ServiceHandler {
  calls: Array<(reply: ServiceReply) => void> = [];
  cancelled: number[] = [];
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void) { this.calls.push(complete); }
  cancel(id: number) { this.cancelled.push(id); }
}

describe('Harmony foreground service pump', () => {
  test('notification ACK effects reach the auxiliary route without consuming service replies', () => {
    const t = new Transport(); const observed: string[] = [];
    const pump = new ServicePump(t, new UnsupportedServices(), raw => { observed.push(raw); });
    const ack = JSON.stringify({ t: 'notification.ack', eventId: '0'.repeat(32) + '.open' });
    t.effects.push(ack); t.request(1); pump.pump();
    expect(observed).toEqual([ack]); expect(t.output[0]).toMatchObject({ id: 1, code: 'unsupported' });
  });
  test('unsupported replies are exact, malformed effects ignored and batches bounded', () => {
    const t = new Transport(); const pump = new ServicePump(t, new UnsupportedServices());
    t.effects.push('broken', 'null', '{}');
    for (let id = 1; id <= 70; id++) t.request(id);
    pump.pump();
    expect(t.effects.length).toBe(9);
    expect(t.output.length).toBe(61);
    expect(t.output[0]).toMatchObject({ t: 'service.result', id: 1, ok: false, code: 'unsupported' });
    pump.pump(); expect(t.output.length).toBe(70);
  });
  test('native rejection retains results until a later wakeup', () => {
    const t = new Transport(); t.blocked = true;
    const pump = new ServicePump(t, new UnsupportedServices());
    for (let id = 1; id <= 70; id++) t.request(id);
    pump.pump(); expect(t.output).toHaveLength(0); expect(t.effects).toHaveLength(6);
    t.blocked = false; pump.pump();
    expect(t.output.map(r => r.id)).toEqual(Array.from({ length: 70 }, (_, i) => i + 1));
  });
  test('cancel and reused ids cannot receive old async completions', () => {
    const t = new Transport(); const handler = new Deferred(); const pump = new ServicePump(t, handler);
    t.request(1); t.request(1); pump.pump(); expect(handler.calls).toHaveLength(1);
    t.cancel(1); pump.pump(); expect(handler.cancelled).toEqual([1]);
    t.request(1); pump.pump(); expect(handler.calls).toHaveLength(2);
    handler.calls[0](new ServiceReply()); expect(t.output).toHaveLength(0);
    handler.calls[1](new ServiceReply()); expect(t.output).toHaveLength(1);
    handler.calls[1](new ServiceReply()); expect(t.output).toHaveLength(1);
  });
  test('full active set still processes cancellation and rejects overload', () => {
    const t = new Transport(); const handler = new Deferred(); const pump = new ServicePump(t, handler);
    for (let id = 1; id <= 64; id++) t.request(id);
    pump.pump(); t.request(65); t.cancel(1); pump.pump();
    expect(t.output[0]).toMatchObject({ id: 65, code: 'busy' });
    expect(handler.cancelled).toEqual([1]);
    t.request(66); pump.pump(); expect(handler.calls).toHaveLength(65);
  });
  test('unsupported versions never reach handler, close cancels and suppresses late results', () => {
    const t = new Transport(); const handler = new Deferred(); const pump = new ServicePump(t, handler);
    t.request(1, 2); t.request(2); pump.pump(); expect(handler.calls).toHaveLength(1);
    expect(t.output[0]).toMatchObject({ id: 1, code: 'unsupported' });
    pump.close(); pump.close(); expect(handler.cancelled).toEqual([2]);
    handler.calls[0](new ServiceReply()); t.request(3); pump.pump();
    expect(t.output).toHaveLength(1); expect(handler.calls).toHaveLength(1);
  });
  test('invalid args and oversized or nonserializable results fail without poisoning later requests', () => {
    const t = new Transport(); const handler = new Deferred(); const pump = new ServicePump(t, handler);
    t.effects.push(JSON.stringify({ t: 'service.request', version: 1, id: 1, method: 'x', args: [] }));
    t.request(2); t.request(3); t.request(4); pump.pump();
    expect(t.output[0]).toMatchObject({ id: 1, code: 'invalid_argument' });
    const large = new ServiceReply(); large.ok = true; large.value = 'x'.repeat(349001);
    handler.calls[0](large);
    expect(t.output[1]).toMatchObject({ id: 2, code: 'resource_exhausted' });
    const cyclic = new ServiceReply(); cyclic.value = cyclic;
    handler.calls[1](cyclic);
    expect(t.output[2]).toMatchObject({ id: 3, code: 'host_error' });
    const valid = new ServiceReply(); valid.ok = true; valid.value = '中文';
    handler.calls[2](valid);
    expect(t.output[3]).toMatchObject({ id: 4, ok: true, value: '中文' });
  });
});
