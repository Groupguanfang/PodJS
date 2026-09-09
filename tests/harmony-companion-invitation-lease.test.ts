import { test, expect } from 'bun:test';
import { CompanionPairingInvitation } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitation';
import { CompanionPairingInvitationLease } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitationLease';
async function fixture() {
  let random = 0, now = 1000, dismissed = 0, timerCancelled = 0, expire = () => {};
  const invitation = await CompanionPairingInvitation.create('app', 'watch', now, { async challenge() { return new Uint8Array(32).fill(++random); } });
  const clock = { now: () => now };
  const timer = { schedule(ms: number, callback: () => void) { expect(ms).toBe(300000); expire = callback; return () => { timerCancelled++; }; } };
  return { invitation, clock, timer, dismiss: () => { dismissed++; }, setNow: (n: number) => { now = n; }, expire: () => expire(), dismissed: () => dismissed, cancelled: () => timerCancelled };
}
test('claim hides QR once, rejects reuse and expiry wipes in-flight key even after wall-clock rollback', async () => {
  const f = await fixture(), lease = new CompanionPairingInvitationLease(f.invitation, f.clock, f.timer, f.dismiss);
  expect(JSON.parse(lease.qrText()).deviceId).toBe('watch');
  let cancelled = 0; const key = lease.claim(() => { cancelled++; });
  expect(key).toEqual(new Uint8Array(32).fill(2)); expect(f.dismissed()).toBe(1);
  expect(() => lease.qrText()).toThrow('claimed'); expect(() => lease.claim(() => {})).toThrow('claimed');
  f.setNow(1500); f.expire(); // timer deadline is independent of supplied wall time
  expect(key).toEqual(new Uint8Array(32)); expect(cancelled).toBe(1); expect(f.dismissed()).toBe(1);
  lease.close(); expect(cancelled).toBe(1); expect(f.cancelled()).toBe(1);
  expect(() => lease.assertActive()).toThrow('closed');
});
test('cancel before claim consumes invitation and throwing callbacks cannot preserve usable credentials', async () => {
  const f = await fixture(), lease = new CompanionPairingInvitationLease(f.invitation, f.clock, f.timer, f.dismiss);
  lease.close(); expect(() => lease.claim(() => {})).toThrow('closed'); expect(() => f.invitation.takeSecret(1000)).toThrow('closed');
  const g = await fixture(), broken = new CompanionPairingInvitationLease(g.invitation, g.clock, g.timer, () => { throw Error('UI failed'); });
  let cancelled = 0;
  expect(() => broken.claim(() => { cancelled++; throw Error('cleanup failed'); })).toThrow('UI failed');
  expect(cancelled).toBe(1); expect(() => broken.assertActive()).toThrow('closed');
});
test('clock expiry and synchronous scheduler expiry cannot reopen the invitation', async () => {
  const f = await fixture(), lease = new CompanionPairingInvitationLease(f.invitation, f.clock, f.timer, f.dismiss);
  f.setNow(301000); expect(() => lease.qrText()).toThrow('expired'); f.setNow(1000); expect(() => lease.qrText()).toThrow('closed');
  const g = await fixture(); let cleared = false;
  const expired = new CompanionPairingInvitationLease(g.invitation, g.clock, { schedule(_ms, expire) { expire(); return () => { cleared = true; }; } }, g.dismiss);
  expect(cleared).toBe(true); expect(g.dismissed()).toBe(1); expect(() => expired.claim(() => {})).toThrow('closed');
});
