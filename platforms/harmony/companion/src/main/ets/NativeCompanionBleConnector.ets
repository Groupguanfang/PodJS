import ble from '@ohos.bluetooth.ble';
import constant from '@ohos.bluetooth.constant';
import { CompanionBleAttributePort, CompanionBlePacketStream } from './CompanionBlePacketStream';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionSyncConnector } from './CompanionSyncAttempt';
import { CompanionSyncPacketStream } from './CompanionSyncExchange';
import { COMPANION_BLE_SERVICE } from './NativeCompanionBleDiscovery';

const TO_SERVER: string = 'deef0002-654d-4e33-9a27-1341d8c28fd1';
const FROM_SERVER: string = 'deef0003-654d-4e33-9a27-1341d8c28fd1';
const CCC: string = '00002902-0000-1000-8000-00805f9b34fb';
class BleTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const timer = setTimeout(expire, ms); return () => clearTimeout(timer);
  }
}

/** One-shot independent GATT transport, compatible with Android PodBleStream.
 * The address must be selected by the host from the same OS Bluetooth namespace.
 * Requires foreground ACCESS_BLUETOOTH grant. Neither OS connection nor service
 * discovery authenticates an application; use CompanionSyncAttempt + approved
 * credentials next. This layer does not promise link encryption. */
export class NativeCompanionBleConnector implements CompanionSyncConnector {
  private address: string;
  private lifetime: number;
  private timer: CompanionStreamTimer;
  private used: boolean = false;
  private closed: boolean = false;
  private connected: boolean = false;
  private gatt: ble.GattClientDevice | null = null;
  private stream: CompanionBlePacketStream | null = null;
  private resolve: ((stream: CompanionSyncPacketStream) => void) | null = null;
  private reject: ((error: Error) => void) | null = null;
  private resolveMtu: ((mtu: number) => void) | null = null;
  private rejectMtu: ((error: Error) => void) | null = null;
  private mtu: number = 0;
  private cancelTimer: () => void = () => {};
  constructor(approvedAddress: string, lifetimeMs: number = 120000, timer: CompanionStreamTimer = new BleTimer()) {
    if (typeof approvedAddress !== 'string' || !/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(approvedAddress)) throw new Error('approved BLE address required');
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid BLE lifetime');
    this.address = approvedAddress.toUpperCase(); this.lifetime = lifetimeMs; this.timer = timer;
  }
  private check(): void { if (this.closed) throw new Error('BLE connection closed'); }
  cancel(): void { this.fail(new Error('BLE connection cancelled')); }
  private fail(error: Error): void {
    if (this.closed) return; this.closed = true;
    const reject = this.reject, rejectMtu = this.rejectMtu;
    this.reject = null; this.resolve = null; this.rejectMtu = null; this.resolveMtu = null;
    if (reject !== null) reject(error); if (rejectMtu !== null) rejectMtu(error);
    try { this.cancelTimer(); } catch (_) {}
    if (this.stream !== null) { try { this.stream.fail(error); } catch (_) {} }
    const gatt = this.gatt; this.gatt = null;
    if (gatt !== null) {
      try { gatt.off('BLEConnectionStateChange', this.onState); } catch (_) {}
      try { gatt.off('BLEMtuChange', this.onMtu); } catch (_) {}
      try { gatt.off('BLECharacteristicChange', this.onValue); } catch (_) {}
      try { gatt.disconnect(); } catch (_) {}
      try { gatt.close(); } catch (_) {}
    }
  }
  private onState = (event: ble.BLEConnectionChangeState): void => {
    if (this.closed) return;
    if (typeof event.deviceId !== 'string' || event.deviceId.toUpperCase() !== this.address) {
      this.fail(new Error('BLE peer mismatch')); return;
    }
    if (event.state === constant.ProfileConnectionState.STATE_CONNECTING && !this.connected) return;
    if (event.state !== constant.ProfileConnectionState.STATE_CONNECTED) { this.fail(new Error('BLE disconnected')); return; }
    if (this.connected) return;
    this.connected = true;
    this.prepare().catch(() => this.fail(new Error('BLE setup failed')));
  };
  private onMtu = (mtu: number): void => {
    if (this.closed) return;
    if (!Number.isInteger(mtu) || mtu < 23 || mtu > 517) { this.fail(new Error('invalid negotiated BLE MTU')); return; }
    if (this.resolveMtu !== null) {
      this.mtu = mtu;
      const resolve = this.resolveMtu; this.resolveMtu = null; this.rejectMtu = null; resolve(mtu);
    } else if (this.mtu !== 0 && this.mtu !== mtu) this.fail(new Error('BLE MTU changed during stream'));
  };
  private onValue = (value: ble.BLECharacteristic): void => {
    if (this.closed) return;
    if (this.stream === null || value.serviceUuid.toLowerCase() !== COMPANION_BLE_SERVICE ||
      value.characteristicUuid.toLowerCase() !== FROM_SERVER) { this.fail(new Error('unexpected BLE characteristic')); return; }
    this.stream.receive(new Uint8Array(value.characteristicValue));
  };
  private async prepare(): Promise<void> {
    this.check(); const gatt = this.gatt;
    if (gatt === null) throw new Error('BLE device missing');
    const services = await gatt.getServices(); this.check();
    if (services.length > 128) throw new Error('too many BLE services');
    const matches = services.filter((service: ble.GattService) => service.serviceUuid.toLowerCase() === COMPANION_BLE_SERVICE);
    if (matches.length !== 1 || !matches[0].isPrimary || matches[0].characteristics.length > 128) throw new Error('invalid PodJS BLE service');
    const txs = matches[0].characteristics.filter((value: ble.BLECharacteristic) => value.characteristicUuid.toLowerCase() === TO_SERVER);
    const rxs = matches[0].characteristics.filter((value: ble.BLECharacteristic) => value.characteristicUuid.toLowerCase() === FROM_SERVER);
    if (txs.length !== 1 || rxs.length !== 1) throw new Error('missing PodJS BLE characteristics');
    const tx = txs[0], rx = rxs[0];
    if (tx.serviceUuid.toLowerCase() !== COMPANION_BLE_SERVICE || rx.serviceUuid.toLowerCase() !== COMPANION_BLE_SERVICE ||
      tx.properties?.write !== true || rx.properties?.indicate !== true || rx.descriptors.length > 128)
      throw new Error('invalid PodJS BLE characteristic properties');
    const descriptors = rx.descriptors.filter((value: ble.BLEDescriptor) => value.descriptorUuid.toLowerCase() === CCC);
    if (descriptors.length !== 1 || descriptors[0].serviceUuid.toLowerCase() !== COMPANION_BLE_SERVICE ||
      descriptors[0].characteristicUuid.toLowerCase() !== FROM_SERVER) throw new Error('missing BLE indication descriptor');
    const mtu = await new Promise<number>((resolve, reject) => {
      this.resolveMtu = resolve; this.rejectMtu = reject;
      try { gatt.setBLEMtuSize(517); } catch (_) { this.resolveMtu = null; this.rejectMtu = null; reject(new Error('BLE MTU request failed')); }
    });
    this.check();
    const port: CompanionBleAttributePort = {
      write: async (value: Uint8Array) => {
        this.check();
        const buffer = new ArrayBuffer(value.length); new Uint8Array(buffer).set(value);
        tx.characteristicValue = buffer;
        await gatt.writeCharacteristicValue(tx, ble.GattWriteType.WRITE); this.check();
      }, close: () => this.cancel()
    };
    const stream = new CompanionBlePacketStream(mtu, port, this.timer, this.lifetime);
    this.stream = stream;
    this.check();
    await gatt.setCharacteristicChangeIndication(rx, true); this.check();
    const resolve = this.resolve; this.resolve = null; this.reject = null;
    if (resolve !== null) resolve(stream);
  }
  connect(): Promise<CompanionSyncPacketStream> {
    if (this.used || this.closed) return Promise.reject(new Error('BLE connector already used'));
    this.used = true;
    return new Promise<CompanionSyncPacketStream>((resolve, reject) => {
      this.resolve = resolve; this.reject = reject;
      try {
        if (!canIUse('SystemCapability.Communication.Bluetooth.Core')) throw new Error('BLE unsupported');
        this.cancelTimer = this.timer.schedule(this.lifetime, () => this.fail(new Error('BLE connection deadline')));
        if (this.closed) { this.cancelTimer(); return; }
        const gatt = ble.createGattClientDevice(this.address); this.gatt = gatt;
        gatt.on('BLEConnectionStateChange', this.onState); gatt.on('BLEMtuChange', this.onMtu); gatt.on('BLECharacteristicChange', this.onValue);
        gatt.connect();
      } catch (_) { this.fail(new Error('BLE connection unavailable')); }
    });
  }
}
