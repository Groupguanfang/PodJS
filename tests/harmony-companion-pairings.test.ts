import { test, expect } from 'bun:test';
import { createHmac } from 'node:crypto';
import { CompanionPairings } from '../platforms/harmony/companion/src/main/ets/CompanionPairings';

function fixture() {
  let raw: string | null = null, owned = true, counter = 0, failSave = 0, saves = 0, failDelete = false;
  const material = new Map<string, Uint8Array>();
  const lease = {
    assertOwned() { if (!owned) throw Error('lease closed'); },
    async read() { return raw; },
    async compareExchange(old: string | null, next: string) { if (++saves === failSave) throw Error('disk failed'); if (old !== raw) return false; raw = next; return true; },
    close() { owned = false; }
  };
  const keys = {
    async exists(id: string) { return material.has(id); },
    async importKey(id: string, key: Uint8Array) { material.set(id, key.slice()); },
    async remove(id: string) { if (failDelete) throw Error('delete failed'); material.delete(id); },
    async sign(id: string, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', material.get(id)!).update(bytes).digest()); }
  };
  const random = { async challenge() { return new Uint8Array(32).fill(++counter); } };
  return { lease, keys, material, owner: () => new CompanionPairings('app', 'phone', lease, keys, random),
    raw: () => raw, owned: () => owned, reopen: () => { owned = true; }, failSave: (n: number) => { failSave = n; }, failDelete: (value: boolean) => { failDelete = value; } };
}
test('approved metadata reopens identity-bound signers without persisting key material', async () => {
  const f = fixture(), owner = f.owner(), key = new Uint8Array(32).fill(77);
  await owner.importApproved('watch', key);
  expect(f.raw()).not.toContain('77');
  await owner.close(); f.reopen(); const reopened = f.owner();
  let disconnected = 0;
  const signer = await reopened.openSigner('watch', () => { disconnected++; });
  expect(signer.matchesIdentity('app', 'phone', 'watch')).toBe(true);
  expect(signer.matchesIdentity('app', 'watch', 'phone')).toBe(false);
  expect(await signer.sign(new Uint8Array([1]))).toEqual(new Uint8Array(createHmac('sha256', key).update(new Uint8Array([1])).digest()));
  await expect(reopened.importApproved('watch', key)).rejects.toThrow('already exists');
  await reopened.revoke('watch');
  await expect(signer.sign(new Uint8Array([1]))).rejects.toThrow('revoked');
  expect(disconnected).toBe(1); expect(f.material.size).toBe(0);
});
test('interrupted import stays unapproved and recovery deletes orphan key before forgetting intent', async () => {
  const f = fixture(), owner = f.owner(); f.failSave(2);
  await expect(owner.importApproved('watch', new Uint8Array(32).fill(7))).rejects.toThrow('disk failed');
  expect(f.raw()).toContain('importing'); expect(f.material.size).toBe(1);
  await expect(owner.openSigner('watch', () => {})).rejects.toThrow('not approved');
  f.failDelete(true); await expect(owner.recover()).rejects.toThrow('delete failed'); expect(f.raw()).toContain('importing');
  f.failDelete(false); await owner.recover(); expect(f.material.size).toBe(0); expect(JSON.parse(f.raw()!).records).toEqual([]);
  await owner.importApproved('watch', new Uint8Array(32).fill(8));
});
test('revoke immediately closes handles and suppresses an in-flight HMAC before durable deletion', async () => {
  const f = fixture(), owner = f.owner(); await owner.importApproved('watch', new Uint8Array(32).fill(7));
  let release!: () => void, started!: () => void, disconnected = 0;
  const ready = new Promise<void>(resolve => { started = resolve; });
  f.keys.sign = async () => { started(); await new Promise<void>(resolve => { release = resolve; }); return new Uint8Array(32).fill(1); };
  const signer = await owner.openSigner('watch', () => { disconnected++; });
  const signing = signer.sign(new Uint8Array([1])); await ready;
  const revoked = owner.revoke('watch'); expect(disconnected).toBe(1); expect(signer.matchesIdentity('app', 'phone', 'watch')).toBe(false);
  release(); await expect(signing).rejects.toThrow('revoked'); await revoked; expect(f.material.size).toBe(0);
});
test('failed deletion remains durably revoking and close retains lease until keystore work settles', async () => {
  const f = fixture(), owner = f.owner(); await owner.importApproved('watch', new Uint8Array(32).fill(7));
  f.failDelete(true); await expect(owner.revoke('watch')).rejects.toThrow('delete failed'); expect(f.raw()).toContain('revoking');
  await expect(owner.openSigner('watch', () => {})).rejects.toThrow('not approved'); f.failDelete(false); await owner.recover();
  let release!: () => void, started!: () => void;
  const ready = new Promise<void>(resolve => { started = resolve; });
  f.keys.importKey = async () => { started(); await new Promise<void>(resolve => { release = resolve; }); };
  const importing = owner.importApproved('watch', new Uint8Array(32).fill(8)); await ready;
  const closing = owner.close(); expect(f.owned()).toBe(true);
  release(); await expect(importing).rejects.toThrow('closed'); await closing; expect(f.owned()).toBe(false);
  expect(f.raw()).toContain('importing');
});
test('public listing hides key IDs and releasing handles does not revoke pairing or exhaust capacity', async () => {
  const f = fixture(), owner = f.owner(); await owner.importApproved('watch', new Uint8Array(32).fill(7));
  const listed = await owner.list();
  expect(JSON.parse(JSON.stringify(listed))).toEqual([{ peer: 'watch', phase: 'approved' }]);
  (listed[0] as any).peer = 'changed'; listed.pop();
  expect((await owner.list())[0].peer).toBe('watch');
  let disconnected = 0;
  for (let i = 0; i < 100; i++) {
    const signer = await owner.openSigner('watch', () => { disconnected++; });
    signer.close(); signer.close();
    await expect(signer.sign(new Uint8Array(0))).rejects.toThrow('revoked');
  }
  expect(disconnected).toBe(100); expect((await owner.list())[0].phase).toBe('approved'); expect(f.material.size).toBe(1);
  const final = await owner.openSigner('watch', () => {}); expect((await final.sign(new Uint8Array(0))).length).toBe(32);
  await owner.close(); await expect(owner.list()).rejects.toThrow('closed');
});
