import { test, expect } from 'bun:test';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { CompanionSyncBinding, CompanionSyncSession } from '../platforms/harmony/companion/src/main/ets/CompanionSyncAuth';
import { CompanionFilePump } from '../platforms/harmony/companion/src/main/ets/CompanionFilePump';
import { CompanionFileRequests } from '../platforms/harmony/companion/src/main/ets/CompanionFileRequests';
import { CompanionFileRequest } from '../platforms/harmony/companion/src/main/ets/CompanionFileWire';
class Store { raw: string | null = null; fail = false; async read() { return this.raw; } async compareExchange(old: string | null, next: string) { if (this.fail) throw new Error('disk failure'); if (old !== this.raw) return false; this.raw = next; return true; } }
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); },
  async messageId() { return randomUUID(); }
};
async function peers(store = new Store(), nonce = 2, execute = async (_peer: string, _request: CompanionFileRequest) => ({ phase: 'offered' })) {
  const binding = new CompanionSyncBinding('app', 'phone', 'watch', Array(32).fill(1), Array(32).fill(nonce));
  const a = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, true, ['file'], crypto), b = new CompanionSyncSession(new Uint8Array(32).fill(7), binding, false, ['file'], crypto);
  await a.authenticate(await b.proof()); await b.authenticate(await a.proof());
  const requests = new CompanionFileRequests('app', 'phone', store, crypto);
  return { store, requests, a, b, left: new CompanionFilePump(a, requests, null, crypto), right: new CompanionFilePump(b, null, { matchesIdentity: (app, local) => app === 'app' && local === 'watch', executeAuthenticated: execute }, crypto) };
}
function status() { const request = new CompanionFileRequest(); request.method = 'status'; request.transfer_id = 'file'; return request; }
test('authenticated file request survives lost reply and fresh-session replay; matching observation persists before commit', async () => {
  const p = await peers(), queued = await p.requests.enqueue('watch', status());
  const sent = (await p.left.sendNext())!; expect((await p.right.receive(sent)).status).toBe('status'); p.left.close(); p.right.close();
  const next = await peers(p.store, 3), replay = (await next.left.sendNext())!;
  expect(replay.payload).toEqual(sent.payload); expect(replay.messageId).toBe(queued.messageId); expect(replay.sessionId).not.toBe(sent.sessionId);
  const response = await next.right.receive(replay); expect((await next.left.receive(response.reply!)).status).toBe('reply');
  expect(await next.left.sendNext()).toBeNull(); expect((await next.requests.completed('watch')).length).toBe(1);
  next.left.close(); next.right.close();
});
test('file request receiver cannot authorize remote accept and storage failure prevents successful reply commit', async () => {
  let executed = 0; const p = await peers(new Store(), 2, async () => { executed++; return { phase: 'offered' }; });
  const bad = await p.a.send('file', 'bad', Array.from(new TextEncoder().encode('{"version":1,"method":"accept","transfer_id":"file"}')));
  await expect(p.right.receive(bad)).rejects.toThrow('unsupported'); expect(executed).toBe(0); p.left.close();
  const q = await peers(); await q.requests.enqueue('watch', status()); const response = await q.right.receive((await q.left.sendNext())!);
  q.store.fail = true; await expect(q.left.receive(response.reply!)).rejects.toThrow('disk failure');
  await expect(q.a.commit(1)).rejects.toThrow('closed'); q.store.fail = false; expect(await q.requests.next('watch')).not.toBeNull(); q.right.close();
});
test('file receiver failure or invalid returned fields closes without a successful reply', async () => {
  const p = await peers(new Store(), 2, async () => { throw new Error('receiver persistence failed'); }); await p.requests.enqueue('watch', status());
  await expect(p.right.receive((await p.left.sendNext())!)).rejects.toThrow('persistence failed'); await expect(p.b.commit(1)).rejects.toThrow('closed'); p.left.close();
  const q = await peers(new Store(), 3, async () => ({ phase: 'offered', path: '/private' })); await q.requests.enqueue('watch', status());
  await expect(q.right.receive((await q.left.sendNext())!)).rejects.toThrow('fields'); q.left.close();
});
