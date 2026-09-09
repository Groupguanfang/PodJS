import { test, expect } from 'bun:test';
import { CompanionPairingApprovalGate } from '../platforms/harmony/companion/src/main/ets/CompanionPairingApprovalGate';
const id = '11'.repeat(32);
test('synchronous approval cannot bypass a subsequent show failure', async () => {
  const gate = new CompanionPairingApprovalGate({ show(_request, answer) {
    answer(true); throw Error('view failed after callback');
  }, hide() {} });
  expect(await gate.confirm('app', 'phone', 'watch', id)).toBe(false);
  const normal = new CompanionPairingApprovalGate({ show(_request, answer) { answer(false); answer(true); }, hide() {} });
  expect(await normal.confirm('app', 'phone', 'watch', id)).toBe(false);
});
test('explicit answer is single-use and stale callbacks cannot approve later prompts', async () => {
  const callbacks: ((value: boolean) => void)[] = []; let hidden = 0;
  const gate = new CompanionPairingApprovalGate({ show(request, answer) {
    expect(JSON.parse(JSON.stringify(request))).toEqual({ app: 'app', local: 'phone', peer: 'watch', invitationId: id }); callbacks.push(answer);
  }, hide() { hidden++; } });
  const first = gate.confirm('app', 'phone', 'watch', id);
  await expect(gate.confirm('app', 'phone', 'watch', id)).rejects.toThrow('pending');
  callbacks[0](false); expect(await first).toBe(false);
  const second = gate.confirm('app', 'phone', 'watch', id);
  callbacks[0](true); expect(hidden).toBe(1);
  callbacks[1](true); expect(await second).toBe(true); callbacks[1](true); expect(hidden).toBe(2);
});
test('dismiss settles a pending prompt, permanently disables its gate and ignores late approval', async () => {
  let answer!: (value: boolean) => void, shown = 0;
  const gate = new CompanionPairingApprovalGate({ show(_request, callback) { shown++; answer = callback; }, hide() {} });
  const pending = gate.confirm('app', 'phone', 'watch', id); gate.dismiss();
  expect(await pending).toBe(false); answer(true); gate.dismiss();
  expect(await gate.confirm('app', 'phone', 'watch', id)).toBe(false); expect(shown).toBe(1);
});
test('UI failure fails closed and invalid identities never reach the view', async () => {
  const brokenShow = new CompanionPairingApprovalGate({ show() { throw Error('view failed'); }, hide() {} });
  expect(await brokenShow.confirm('app', 'phone', 'watch', id)).toBe(false);
  let answer!: (value: boolean) => void;
  const brokenHide = new CompanionPairingApprovalGate({ show(_request, callback) { answer = callback; }, hide() { throw Error('view failed'); } });
  const pending = brokenHide.confirm('app', 'phone', 'watch', id); answer(true); expect(await pending).toBe(false);
  const invalid = new CompanionPairingApprovalGate({ show() { throw Error('must not show'); }, hide() {} });
  await expect(invalid.confirm('app', 'phone', 'phone', id)).rejects.toThrow('invalid');
  await expect(invalid.confirm('app', 'phone', 'bad/id', id)).rejects.toThrow('invalid');
});
