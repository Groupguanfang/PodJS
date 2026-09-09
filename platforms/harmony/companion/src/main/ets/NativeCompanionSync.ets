import cryptoFramework from '@ohos.security.cryptoFramework';
import { CompanionSyncCrypto, CompanionSyncBinding, CompanionSyncHandshake, CompanionSyncSession, CompanionSyncSigner } from './CompanionSyncAuth';
import { CompanionSyncExchange, CompanionSyncPacketStream, CompanionSyncChallengeCrypto } from './CompanionSyncExchange';
import { CompanionSyncAttempt, CompanionSyncConnector } from './CompanionSyncAttempt';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionState } from './CompanionState';
import { CompanionStateSynchronizer } from './CompanionStateSynchronizer';
import { CompanionPairings } from './CompanionPairings';
import { CompanionPairedAttempt } from './CompanionPairedAttempt';

class NativeAttemptTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const timer = setTimeout(expire, milliseconds); return () => { clearTimeout(timer); };
  }
}

export function createCompanionSyncAttempt(connector: CompanionSyncConnector, timeoutMs: number): CompanionSyncAttempt {
  return new CompanionSyncAttempt(connector, new NativeCompanionSyncCrypto(), new NativeAttemptTimer(), timeoutMs);
}
export function createCompanionPairedAttempt(pairings: CompanionPairings, connector: CompanionSyncConnector,
  timeoutMs: number): CompanionPairedAttempt {
  return new CompanionPairedAttempt(pairings, createCompanionSyncAttempt(connector, timeoutMs), new NativeAttemptTimer(), timeoutMs);
}

export function synchronizeCompanionState(connection: CompanionSyncConnection, state: CompanionState,
  durationMs: number): CompanionStateSynchronizer {
  return state.synchronize(connection, new NativeAttemptTimer(), durationMs);
}

export class NativeCompanionSyncCrypto implements CompanionSyncChallengeCrypto {
  async challenge(): Promise<Uint8Array> {
    return (await cryptoFramework.createRandom().generateRandom(32)).data;
  }
  async sha256(bytes: Uint8Array): Promise<Uint8Array> {
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: bytes });
    return (await digest.digest()).data;
  }
  async hmacSha256(key: Uint8Array, bytes: Uint8Array): Promise<Uint8Array> {
    const generator = cryptoFramework.createSymKeyGenerator('HMAC|SHA256');
    const symKey = await generator.convertKey({ data: key });
    const mac = cryptoFramework.createMac('SHA256');
    await mac.init(symKey);
    await mac.update({ data: bytes });
    return (await mac.doFinal()).data;
  }
}

export function createCompanionSyncExchange(link: CompanionSyncPacketStream): CompanionSyncExchange {
  return new CompanionSyncExchange(link, new NativeCompanionSyncCrypto());
}

/** Caller must authenticate pairing and supply fresh OS challenges. This does
 * not discover peers, exchange hello messages, or construct a frame session. */
export function createCompanionSyncHandshake(key: Uint8Array | CompanionSyncSigner, binding: CompanionSyncBinding,
  initiator: boolean): CompanionSyncHandshake {
  return new CompanionSyncHandshake(key, binding, initiator, new NativeCompanionSyncCrypto());
}

/** Grants must come from host-approved application permissions. No transport IO. */
export function createCompanionSyncSession(key: Uint8Array | CompanionSyncSigner, binding: CompanionSyncBinding,
  initiator: boolean, grants: string[]): CompanionSyncSession {
  return new CompanionSyncSession(key, binding, initiator, grants, new NativeCompanionSyncCrypto());
}
