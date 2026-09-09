import { test, expect } from 'bun:test';
import { CompanionPairingInvitation as Invitation } from '../platforms/harmony/companion/src/main/ets/CompanionPairingInvitation';

async function invitation() {
  let n = 0;
  return Invitation.create('app', 'watch', 1000, { async challenge() { return new Uint8Array(32).fill(++n); } });
}
test('host invitation round-trips with exact fields, five-minute expiry and single-use secret', async () => {
  const original = await invitation(), wire = original.encodeForQr(1000);
  expect(Object.keys(JSON.parse(wire))).toEqual(['type', 'version', 'appId', 'deviceId', 'invitationId', 'expiresAt', 'secret']);
  expect(JSON.parse(wire).expiresAt).toBe(301000);
  const scanned = Invitation.parseQr(wire, 'app', 'phone', 2000);
  expect(scanned.deviceId).toBe('watch'); expect(scanned.invitationId).toBe('01'.repeat(32));
  const secret = scanned.takeSecret(2000); expect(secret).toEqual(new Uint8Array(32).fill(2));
  expect(() => scanned.takeSecret(2000)).toThrow('closed'); expect(() => scanned.encodeForQr(2000)).toThrow('closed');
  expect(original.takeSecret(2000)).toEqual(secret); secret.fill(0);
});
test('scanning rejects wrong app, self, expiration, unknown/duplicate fields and malformed keys', async () => {
  const wire = (await invitation()).encodeForQr(1000);
  expect(() => Invitation.parseQr(wire, 'other', 'phone', 1000)).toThrow('invalid');
  expect(() => Invitation.parseQr(wire, 'app', 'watch', 1000)).toThrow('invalid');
  expect(() => Invitation.parseQr(wire, 'app', 'phone', 301000)).toThrow('invalid');
  for (const [field, value] of [['version', 2], ['secret', '00'.repeat(32)], ['secret', 'ff'], ['secret', '01'.repeat(32)],
    ['invitationId', '00'.repeat(32)], ['expiresAt', 301001], ['expiresAt', '301000'], ['deviceId', '../watch'], ['extra', true]]) {
    const changed = JSON.parse(wire); changed[field as string] = value;
    expect(() => Invitation.parseQr(JSON.stringify(changed), 'app', 'phone', 1000)).toThrow();
  }
  expect(() => Invitation.parseQr(wire.replace('"version":1', '"version":1,"vers\\u0069on":1'), 'app', 'phone', 1000)).toThrow();
  expect(() => Invitation.parseQr(' '.repeat(1025), 'app', 'phone', 1000)).toThrow('size');
});
test('expired/closed invitations cannot be revived and broken randomness never issues a credential', async () => {
  const expired = await invitation(); expect(() => expired.encodeForQr(301000)).toThrow('expired');
  expect(() => expired.takeSecret(2000)).toThrow('closed');
  const closed = await invitation(); closed.close(); expect(() => closed.takeSecret(2000)).toThrow('closed');
  const rollback = await invitation(); expect(() => rollback.encodeForQr(999)).toThrow('clock changed');
  for (const data of [new Uint8Array(31).fill(1), new Uint8Array(32), new Uint8Array(32).fill(1)]) {
    await expect(Invitation.create('app', 'watch', 1000, { async challenge() { return data; } })).rejects.toThrow('randomness');
  }
});
