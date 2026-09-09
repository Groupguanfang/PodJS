import { expect, test } from 'bun:test';
import { AuthorizedServices } from '../platforms/harmony/entry/src/main/ets/AuthorizedServices';
import { ServiceReply, ServiceRequest } from '../platforms/harmony/entry/src/main/ets/ServicePump';
import { platformMethods } from '../packages/framework/src/platform-contract';

test('every planned method checks its exact native capability before IO', () => {
  for (const [method, capability] of Object.entries(platformMethods)) {
    const request = new ServiceRequest(); request.method = method; request.id = 1;
    // A guest cannot grant itself a capability in request arguments.
    request.args = { capabilities: [capability] };
    let calls = 0;
    const observed: string[] = [];
    let allowed = false;
    const host = new AuthorizedServices({ hasCapability(name) { observed.push(name); return allowed; } }, {
      handle(_, complete) { calls++; complete(new ServiceReply()); }, cancel() { throw Error('not active'); }
    });
    let reply: ServiceReply | undefined;
    host.handle(request, result => { reply = result; });
    expect(observed).toEqual([capability]); expect(calls).toBe(0); expect(reply?.code).toBe('unsupported');
    host.cancel(1);
    allowed = true; host.handle(request, result => { reply = result; });
    expect(calls).toBe(1);
  }
});

test('unknown methods fail closed even with an authority returning true', () => {
  const host = new AuthorizedServices({ hasCapability() { throw Error('unknown must not query'); } }, {
    handle() { throw Error('must not execute'); }, cancel() { throw Error('must not cancel'); }
  });
  for (const method of ['notifications.schedule.extra', 'constructor', '__proto__', '']) {
    const request = new ServiceRequest(); request.method = method;
    host.handle(request, reply => expect(reply.code).toBe('unsupported'));
  }
});

test('cancellation suppresses completions and reaches only authorized active IO', () => {
  let finish: (reply: ServiceReply) => void = () => {};
  let cancelled = 0; let completed = 0;
  const host = new AuthorizedServices({ hasCapability() { return true; } }, {
    handle(_, complete) { finish = complete; }, cancel() { cancelled++; }
  });
  const request = new ServiceRequest(); request.id = 7; request.method = 'notifications.schedule';
  host.handle(request, () => completed++);
  host.cancel(7); host.cancel(7); finish(new ServiceReply());
  expect(cancelled).toBe(1); expect(completed).toBe(0);
  const oldFinish = finish;
  const reused = new ServiceRequest(); reused.id = 7; reused.method = request.method;
  host.handle(reused, () => completed++);
  oldFinish(new ServiceReply()); expect(completed).toBe(0);
  finish(new ServiceReply()); expect(completed).toBe(1);
});
