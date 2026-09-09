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
let listenerBusy: boolean = false;
class AcceptorTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const timer = setTimeout(expire, ms); return () => clearTimeout(timer);
  }
}
function serviceDefinition(): ble.GattService {
  const descriptor: ble.BLEDescriptor = { serviceUuid: COMPANION_BLE_SERVICE, characteristicUuid: FROM_SERVER,
    descriptorUuid: CCC, descriptorValue: new ArrayBuffer(2), permissions: { read: false, write: true } };
  const incoming: ble.BLECharacteristic = { serviceUuid: COMPANION_BLE_SERVICE, characteristicUuid: TO_SERVER,
    characteristicValue: new ArrayBuffer(0), descriptors: [], properties: { write: true }, permissions: { read: false, write: true } };
  const outgoing: ble.BLECharacteristic = { serviceUuid: COMPANION_BLE_SERVICE, characteristicUuid: FROM_SERVER,
    characteristicValue: new ArrayBuffer(0), descriptors: [descriptor], properties: { indicate: true }, permissions: { read: false, write: false } };
  return { serviceUuid: COMPANION_BLE_SERVICE, isPrimary: true, characteristics: [incoming, outgoing] };
}

/** One-shot foreground PodJS GATT server. Explicit null selects the first
 * central for bootstrap only; it is NOT approval or trust. An application
 * challenge/confirmation must follow before any durable credential or data.
 * Otherwise the address must be host-approved in the same OS namespace.
 * One central only: the SDK's server MTU callback has no peer identity, so a
 * second connection terminates the attempt instead of mixing peer MTUs.
 * Requires ACCESS_BLUETOOTH; no global stop-advertising API or radio enabling. */
export class NativeCompanionBleAcceptor implements CompanionSyncConnector {
  private approved: string | null;
  private lifetime: number;
  private timer: CompanionStreamTimer;
  private server: ble.GattServer | null = null;
  private stream: CompanionBlePacketStream | null = null;
  private peer: string | null = null;
  private mtu: number = 23;
  private subscribed: boolean = false;
  private used: boolean = false;
  private closed: boolean = false;
  private ownsListener: boolean = false;
  private advertisingId: number | null = null;
  private stoppingAdvertising: boolean = false;
  private resolve: ((stream: CompanionSyncPacketStream) => void) | null = null;
  private reject: ((error: Error) => void) | null = null;
  private cancelTimer: () => void = () => {};
  constructor(approvedAddress: string | null, lifetimeMs: number = 120000, timer: CompanionStreamTimer = new AcceptorTimer()) {
    if (approvedAddress !== null && (typeof approvedAddress !== 'string' || !/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(approvedAddress)))
      throw new Error('approved BLE address or explicit bootstrap selection required');
    if (!Number.isInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 120000) throw new Error('invalid BLE lifetime');
    this.approved = approvedAddress === null ? null : approvedAddress.toUpperCase(); this.lifetime = lifetimeMs; this.timer = timer;
  }
  private check(): void { if (this.closed) throw new Error('BLE listener closed'); }
  private selected(address: string): boolean { return this.peer !== null && address.toUpperCase() === this.peer; }
  cancel(): void { this.fail(new Error('BLE listener cancelled')); }
  private stopLateAdvertisement(id: number): void { try { ble.stopAdvertising(id).catch(() => {}); } catch (_) {} }
  private fail(error: Error): void {
    if (this.closed) return; this.closed = true;
    const reject = this.reject; this.reject = null; this.resolve = null;
    if (reject !== null) reject(error);
    try { this.cancelTimer(); } catch (_) {}
    if (this.advertisingId !== null) this.stopLateAdvertisement(this.advertisingId);
    this.advertisingId = null;
    if (this.stream !== null) { try { this.stream.fail(error); } catch (_) {} }
    const server = this.server; this.server = null;
    if (server !== null) {
      try { server.off('connectionStateChange', this.onState); } catch (_) {}
      try { server.off('BLEMtuChange', this.onMtu); } catch (_) {}
      try { server.off('characteristicWrite', this.onWrite); } catch (_) {}
      try { server.off('descriptorWrite', this.onDescriptor); } catch (_) {}
      try { server.off('characteristicRead', this.onRead); } catch (_) {}
      try { server.off('descriptorRead', this.onDescriptorRead); } catch (_) {}
      // Explicit disconnect is API 26; older systems may only provide close.
      if (this.peer !== null) { try { server.disconnect(this.peer); } catch (_) {} }
      try { server.close(); } catch (_) {}
    }
    if (this.ownsListener) { this.ownsListener = false; listenerBusy = false; }
  }
  private respond(address: string, transId: number, status: number): void {
    if (this.closed || this.server === null) return;
    try { this.server.sendResponse({ deviceId: address, transId, status, offset: 0, value: new ArrayBuffer(0) }); }
    catch (_) { this.fail(new Error('BLE response failed')); }
  }
  private onState = (event: ble.BLEConnectionChangeState): void => {
    if (this.closed) return;
    if (!/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(event.deviceId)) { this.fail(new Error('invalid BLE central')); return; }
    const address = event.deviceId.toUpperCase();
    if (event.state === constant.ProfileConnectionState.STATE_CONNECTING && this.peer === null) return;
    if (event.state === constant.ProfileConnectionState.STATE_CONNECTED) {
      if (this.peer !== null || (this.approved !== null && this.approved !== address)) {
        if (this.server !== null) { try { this.server.disconnect(address); } catch (_) {} }
        this.fail(new Error('unexpected BLE central')); return;
      }
      this.peer = address; return;
    }
    if (this.selected(address)) this.fail(new Error('BLE central disconnected'));
  };
  private onMtu = (mtu: number): void => {
    if (this.closed || this.peer === null) return;
    if (!Number.isInteger(mtu) || mtu < 23 || mtu > 517 || (this.subscribed && this.mtu !== mtu)) {
      this.fail(new Error('invalid BLE server MTU')); return;
    }
    this.mtu = mtu;
  };
  private onRead = (request: ble.CharacteristicReadRequest): void => { this.respond(request.deviceId, request.transId, 2); };
  private onDescriptorRead = (request: ble.DescriptorReadRequest): void => { this.respond(request.deviceId, request.transId, 2); };
  private onWrite = (request: ble.CharacteristicWriteRequest): void => {
    if (this.closed) return;
    const valid = this.selected(request.deviceId) && this.subscribed && this.stream !== null && !request.isPrepared && request.needRsp &&
      request.offset === 0 && request.serviceUuid.toLowerCase() === COMPANION_BLE_SERVICE && request.characteristicUuid.toLowerCase() === TO_SERVER;
    if (!valid) {
      if (request.needRsp) this.respond(request.deviceId, request.transId, 6);
      if (this.selected(request.deviceId)) this.fail(new Error('invalid BLE write')); return;
    }
    this.stream!.receive(new Uint8Array(request.value));
    // A framing failure synchronously closes this owner through the attribute port.
    if (!this.closed) this.respond(request.deviceId, request.transId, 0);
  };
  private onDescriptor = (request: ble.DescriptorWriteRequest): void => {
    if (this.closed) return;
    const value = new Uint8Array(request.value);
    const valid = this.selected(request.deviceId) && !this.subscribed && !request.isPrepared && request.needRsp && request.offset === 0 &&
      request.serviceUuid.toLowerCase() === COMPANION_BLE_SERVICE && request.characteristicUuid.toLowerCase() === FROM_SERVER &&
      request.descriptorUuid.toLowerCase() === CCC && value.length === 2 && value[0] === 2 && value[1] === 0;
    if (!valid) {
      if (request.needRsp) this.respond(request.deviceId, request.transId, 6);
      if (this.selected(request.deviceId)) this.fail(new Error('invalid BLE subscription')); return;
    }
    try {
      const server = this.server, peer = this.peer;
      if (server === null || peer === null) throw new Error('BLE server missing');
      const port: CompanionBleAttributePort = {
        write: async (bytes: Uint8Array) => {
          this.check(); const buffer = new ArrayBuffer(bytes.length); new Uint8Array(buffer).set(bytes);
          const notification: ble.NotifyCharacteristic = { serviceUuid: COMPANION_BLE_SERVICE, characteristicUuid: FROM_SERVER,
            characteristicValue: buffer, confirm: true };
          await server.notifyCharacteristicChanged(peer, notification); this.check();
        }, close: () => this.cancel()
      };
      this.stream = new CompanionBlePacketStream(this.mtu, port, this.timer, this.lifetime);
      this.check(); this.subscribed = true;
      // Receive queue exists before the central receives its CCC response.
      this.respond(request.deviceId, request.transId, 0);
      this.finishSubscription();
    } catch (_) { this.fail(new Error('BLE subscription setup failed')); }
  };
  private finishSubscription(): void {
    if (this.closed || !this.subscribed || this.advertisingId === null || this.stoppingAdvertising) return;
    this.stoppingAdvertising = true;
    const id = this.advertisingId;
    try {
      ble.stopAdvertising(id).then(() => {
        if (this.closed) return;
        this.advertisingId = null;
        const resolve = this.resolve; this.resolve = null; this.reject = null;
        if (resolve !== null && this.stream !== null) resolve(this.stream);
      }).catch(() => this.fail(new Error('BLE advertisement stop failed')));
    } catch (_) { this.fail(new Error('BLE advertisement stop failed')); }
  }
  connect(): Promise<CompanionSyncPacketStream> {
    if (this.used || this.closed) return Promise.reject(new Error('BLE listener already used'));
    if (listenerBusy) return Promise.reject(new Error('PodJS BLE listener already active'));
    this.used = true;
    listenerBusy = true; this.ownsListener = true;
    return new Promise<CompanionSyncPacketStream>((resolve, reject) => {
      this.resolve = resolve; this.reject = reject;
      try {
        if (!canIUse('SystemCapability.Communication.Bluetooth.Core')) throw new Error('BLE unsupported');
        this.cancelTimer = this.timer.schedule(this.lifetime, () => this.fail(new Error('BLE listener deadline')));
        if (this.closed) { this.cancelTimer(); return; }
        const server = ble.createGattServer(); this.server = server;
        server.on('connectionStateChange', this.onState); server.on('BLEMtuChange', this.onMtu);
        server.on('characteristicWrite', this.onWrite); server.on('descriptorWrite', this.onDescriptor);
        server.on('characteristicRead', this.onRead); server.on('descriptorRead', this.onDescriptorRead);
        server.addService(serviceDefinition());
        const advertising: ble.AdvertisingParams = {
          advertisingSettings: { connectable: true, interval: 160 },
          advertisingData: { serviceUuids: [COMPANION_BLE_SERVICE], manufactureData: [], serviceData: [], includeDeviceName: false },
          duration: Math.max(1, Math.ceil(Math.min(this.lifetime, 30000) / 10))
        };
        ble.startAdvertising(advertising).then((id: number) => {
          if (!Number.isInteger(id) || id < 0) { this.fail(new Error('invalid BLE advertising ID')); return; }
          if (this.closed) { this.stopLateAdvertisement(id); return; }
          this.advertisingId = id; this.finishSubscription();
        }).catch(() => this.fail(new Error('BLE advertisement unavailable')));
      } catch (_) { this.fail(new Error('BLE listener unavailable')); }
    });
  }
}
