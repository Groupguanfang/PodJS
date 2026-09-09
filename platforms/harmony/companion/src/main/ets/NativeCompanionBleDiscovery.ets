import ble from '@ohos.bluetooth.ble';
import { CompanionStreamTimer } from './CompanionPacketStream';

export const COMPANION_BLE_SERVICE: string = 'deef0001-654d-4e33-9a27-1341d8c28fd1';

/** Untrusted, ephemeral routing hint. SDK 26 may return a virtual address;
 * never interpret this as an application identity or a real hardware MAC. */
export class CompanionBleCandidate {
  readonly address: string;
  readonly name: string;
  readonly rssi: number;
  constructor(address: string, name: string, rssi: number) {
    this.address = address; this.name = name; this.rssi = rssi;
  }
}
class DiscoveryTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const timer = setTimeout(expire, ms); return () => clearTimeout(timer);
  }
}

/** Foreground, one-shot discovery of the independent PodJS GATT service.
 * Host must first obtain ACCESS_BLUETOOTH via explicit user action. This does
 * not enable radios, pair, connect, scan arbitrary services, or use global scan
 * stop APIs. Each owner has a dedicated SDK-15 scanner. Results expire with the
 * host's selection UI; GATT + app authentication must validate the chosen peer. */
export class NativeCompanionBleDiscovery {
  private scanner: ble.BleScanner | null = null;
  private used: boolean = false;
  private closed: boolean = false;
  private started: boolean = false;
  private entries: Map<string, CompanionBleCandidate> = new Map();
  private reject: ((error: Error) => void) | null = null;
  private cancelTimer: () => void = () => {};
  private timer: CompanionStreamTimer;
  constructor(timer: CompanionStreamTimer = new DiscoveryTimer()) { this.timer = timer; }
  private receive = (report: ble.ScanReport): void => {
    if (this.closed || !Array.isArray(report.scanResult)) return;
    if (report.reportType !== ble.ScanReportType.ON_FOUND && report.reportType !== ble.ScanReportType.ON_LOST &&
      report.reportType !== ble.ScanReportType.ON_BATCH) return;
    // Bound callback work even if an OS batch is unexpectedly large.
    for (const result of report.scanResult.slice(0, 256)) {
      if (typeof result.deviceId !== 'string' || !/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(result.deviceId)) continue;
      const address = result.deviceId.toUpperCase();
      if (report.reportType === ble.ScanReportType.ON_LOST) { this.entries.delete(address); continue; }
      if (result.connectable !== true || !Number.isInteger(result.rssi) || result.rssi < -127 || result.rssi > 20) continue;
      if (!this.entries.has(address) && this.entries.size >= 32) continue;
      const name = typeof result.deviceName === 'string' ? result.deviceName.slice(0, 80).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, '') : '';
      this.entries.set(address, new CompanionBleCandidate(address, name, result.rssi));
    }
  };
  private stop(scanner: ble.BleScanner): void {
    try { scanner.stopScan().catch(() => {}); } catch (_) {}
  }
  private cleanup(): void {
    try { this.cancelTimer(); } catch (_) {}
    if (this.scanner !== null) {
      try { this.scanner.off('BLEDeviceFind', this.receive); } catch (_) {}
      this.stop(this.scanner);
    }
    this.entries.clear();
  }
  cancel(): void { this.fail(new Error('BLE discovery cancelled')); }
  private fail(error: Error): void {
    if (this.closed) return;
    this.closed = true;
    const reject = this.reject; this.reject = null;
    this.cleanup(); if (reject !== null) reject(error);
  }
  scan(durationMs: number = 5000): Promise<CompanionBleCandidate[]> {
    if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 10000)
      return Promise.reject(new Error('invalid BLE discovery duration'));
    if (this.used || this.closed) return Promise.reject(new Error('BLE discovery already used'));
    this.used = true;
    return new Promise<CompanionBleCandidate[]>((resolve, reject) => {
      this.reject = reject;
      try {
        if (!canIUse('SystemCapability.Communication.Bluetooth.Core')) throw new Error('BLE unsupported');
        const scanner = ble.createBleScanner(); this.scanner = scanner;
        scanner.on('BLEDeviceFind', this.receive);
        this.cancelTimer = this.timer.schedule(durationMs, () => {
          if (this.closed) return;
          if (!this.started) { this.fail(new Error('BLE discovery start deadline')); return; }
          const snapshot = Array.from(this.entries.values());
          this.closed = true; this.reject = null; this.cleanup(); resolve(snapshot);
        });
        if (this.closed) { this.cancelTimer(); return; }
        scanner.startScan([{ serviceUuid: COMPANION_BLE_SERVICE }]).then(() => {
          if (this.closed) { this.stop(scanner); return; }
          this.started = true;
        }).catch(() => this.fail(new Error('BLE discovery unavailable')));
      } catch (_) { this.fail(new Error('BLE discovery unavailable')); }
    });
  }
}
