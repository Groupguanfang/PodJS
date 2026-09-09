import { decodeSyncObject } from './CompanionSyncExchange';
import { CompanionPairingRandom } from './CompanionPairings';

export const PAIRING_INVITATION_LIFETIME_MS = 300000;
class InvitationWire {
  type: string = 'podjs-pairing-invitation';
  version: number = 1;
  appId: string = '';
  deviceId: string = '';
  invitationId: string = '';
  expiresAt: number = 0;
  secret: string = '';
}
function identity(value: string): void {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new Error('invalid pairing invitation identity');
}
function time(now: number): void {
  if (!Number.isSafeInteger(now) || now < 0 || now > Number.MAX_SAFE_INTEGER - PAIRING_INVITATION_LIFETIME_MS)
    throw new Error('invalid pairing invitation clock');
}
function hex(bytes: Uint8Array): string {
  let result = ''; for (const byte of bytes) result += byte.toString(16).padStart(2, '0'); return result;
}
function unhex(value: string): Uint8Array {
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(value.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

/** Host-only QR bootstrap credential. Never log, persist, or synchronize the
 * encoded text. The host must dismiss its QR on close/expiry and authenticate
 * possession plus obtain explicit approval on both devices before importing a
 * permanent pairing. This object alone grants no application permissions. */
export class CompanionPairingInvitation {
  readonly appId: string;
  readonly deviceId: string;
  readonly invitationId: string;
  readonly expiresAt: number;
  private secret: Uint8Array;
  private closed: boolean = false;
  private constructor(wire: InvitationWire) {
    this.appId = wire.appId; this.deviceId = wire.deviceId; this.invitationId = wire.invitationId;
    this.expiresAt = wire.expiresAt; this.secret = unhex(wire.secret);
  }
  private check(now: number): void {
    time(now);
    if (this.closed) throw new Error('pairing invitation closed');
    if (now >= this.expiresAt || this.expiresAt - now > PAIRING_INVITATION_LIFETIME_MS) {
      this.close(); throw new Error('pairing invitation expired or clock changed');
    }
  }
  close(): void { this.closed = true; this.secret.fill(0); }
  /** Copy is owned by the handshake caller and must be wiped after use. */
  takeSecret(now: number): Uint8Array {
    this.check(now); const result = this.secret.slice(); this.close(); return result;
  }
  encodeForQr(now: number): string {
    this.check(now);
    const wire = new InvitationWire(); wire.appId = this.appId; wire.deviceId = this.deviceId;
    wire.invitationId = this.invitationId; wire.expiresAt = this.expiresAt; wire.secret = hex(this.secret);
    return JSON.stringify(wire);
  }
  static async create(appId: string, deviceId: string, now: number, random: CompanionPairingRandom): Promise<CompanionPairingInvitation> {
    identity(appId); identity(deviceId); time(now);
    const id = (await random.challenge()).slice();
    let secret: Uint8Array | null = null;
    try {
      secret = (await random.challenge()).slice();
      if (id.length !== 32 || secret.length !== 32 || !id.some((byte: number) => byte !== 0) ||
        !secret.some((byte: number) => byte !== 0) || hex(id) === hex(secret)) throw new Error('invalid pairing invitation randomness');
      const wire = new InvitationWire(); wire.appId = appId; wire.deviceId = deviceId;
      wire.invitationId = hex(id); wire.expiresAt = now + PAIRING_INVITATION_LIFETIME_MS; wire.secret = hex(secret);
      return new CompanionPairingInvitation(wire);
    } finally { id.fill(0); if (secret !== null) secret.fill(0); }
  }
  /** expectedApp/localDevice come from the host, never from the scanned QR. */
  static parseQr(text: string, expectedApp: string, localDevice: string, now: number): CompanionPairingInvitation {
    identity(expectedApp); identity(localDevice); time(now);
    if (typeof text !== 'string' || text.length < 1 || text.length > 1024) throw new Error('invalid pairing invitation size');
    const bytes = new Uint8Array(text.length);
    for (let i = 0; i < text.length; i++) {
      if (text.charCodeAt(i) > 127) throw new Error('invalid pairing invitation encoding');
      bytes[i] = text.charCodeAt(i);
    }
    const wire = decodeSyncObject(bytes, 1024) as InvitationWire;
    if (Object.keys(wire).sort().join(',') !== 'appId,deviceId,expiresAt,invitationId,secret,type,version' ||
      wire.type !== 'podjs-pairing-invitation' || wire.version !== 1 || wire.appId !== expectedApp ||
      wire.deviceId === localDevice || !Number.isSafeInteger(wire.expiresAt) || wire.expiresAt <= now ||
      wire.expiresAt - now > PAIRING_INVITATION_LIFETIME_MS || typeof wire.invitationId !== 'string' ||
      !/^[0-9a-f]{64}$/.test(wire.invitationId) || /^0+$/.test(wire.invitationId) || typeof wire.secret !== 'string' ||
      !/^[0-9a-f]{64}$/.test(wire.secret) || /^0+$/.test(wire.secret) || wire.secret === wire.invitationId)
      throw new Error('invalid pairing invitation');
    identity(wire.deviceId);
    return new CompanionPairingInvitation(wire);
  }
}
