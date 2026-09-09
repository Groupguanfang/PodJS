package dev.podjs.runtime;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.ParcelUuid;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded foreground discovery on an IO worker. Advertisements are
 * untrusted routing hints, not app identity or pairing authorization. */
public final class PodBleDiscovery implements Closeable {
    public static final int MAX_RESULTS=32;
    public static final class Entry {
        public final BluetoothDevice device;
        public final int rssi;
        Entry(BluetoothDevice device,int rssi) { this.device=device; this.rssi=rssi; }
    }
    interface Events { void found(BluetoothDevice device,int rssi); void failed(int code); }
    interface Backend extends Closeable { void start(Events events) throws IOException; }
    private final Object gate=new Object();
    private final Backend backend;
    private final LinkedHashMap<String,Entry> results=new LinkedHashMap<>();
    private boolean started,closed;
    private IOException failure;
    public PodBleDiscovery(Context context) { this(new AndroidBackend(java.util.Objects.requireNonNull(context).getApplicationContext())); }
    PodBleDiscovery(Backend backend) { this.backend=java.util.Objects.requireNonNull(backend); }
    private final Events events=new Events() {
        public void found(BluetoothDevice device,int rssi) {
            synchronized(gate) {
                if(closed || failure!=null || device==null) return;
                String address=device.getAddress();
                if(results.containsKey(address) || results.size()<MAX_RESULTS) results.put(address,new Entry(device,rssi));
            }
        }
        public void failed(int code) { synchronized(gate) { if(closed) return; failure=new IOException("BLE scan failed: "+code); gate.notifyAll(); } }
    };
    /** Returns an immutable snapshot after 100–10,000 ms, possibly empty.
     * Cancellation and scanner errors throw, rather than masquerading as no peers.
     * A second scan requires a new instance and explicit host action.
     */
    public List<Entry> scan(int durationMillis) throws IOException {
        if(durationMillis<100 || durationMillis>10000) throw new IllegalArgumentException("BLE scan duration out of range");
        synchronized(gate) { if(started || closed) throw new IOException("BLE discovery already used or closed"); started=true; }
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durationMillis);
        try {
            backend.start(events);
            synchronized(gate) {
                while(!closed && failure==null) {
                    long remaining=deadline-System.nanoTime(); if(remaining<=0) break;
                    try { TimeUnit.NANOSECONDS.timedWait(gate,remaining); }
                    catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("BLE scan interrupted"); }
                }
                if(failure!=null) throw failure;
                if(closed) throw new IOException("BLE discovery cancelled");
                return Collections.unmodifiableList(new ArrayList<>(results.values()));
            }
        } catch(RuntimeException error) { throw new IOException("BLE discovery failed",error); }
        finally { close(); }
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    @Override public void close() {
        synchronized(gate) { if(closed) return; closed=true; results.clear(); gate.notifyAll(); }
        try { backend.close(); } catch(Exception ignored) { }
    }
    private static final class AndroidBackend implements Backend {
        private final Context context;
        private final Object lock=new Object();
        private BluetoothLeScanner scanner;
        private Events events;
        private boolean closed;
        AndroidBackend(Context context) { this.context=context; }
        private final ScanCallback callback=new ScanCallback() {
            @Override public void onScanResult(int type,ScanResult result) { receive(result); }
            @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult result:results) receive(result); }
            @Override public void onScanFailed(int code) { Events target; synchronized(lock) { if(closed) return; target=events; } target.failed(code); }
            private void receive(ScanResult result) {
                Events target; synchronized(lock) { if(closed) return; target=events; }
                ScanRecord record=result==null?null:result.getScanRecord();
                if(record==null || record.getServiceUuids()==null || !record.getServiceUuids().contains(new ParcelUuid(PodBleGattClient.SERVICE))) return;
                try { target.found(result.getDevice(),result.getRssi()); }
                catch(RuntimeException error) { target.failed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR); }
            }
        };
        @Override public void start(Events events) throws IOException {
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            BluetoothLeScanner available=adapter==null?null:adapter.getBluetoothLeScanner();
            if(available==null) throw new IOException("BLE scanner unavailable");
            synchronized(lock) {
                if(closed) throw new IOException("BLE scanner closed"); this.events=events; scanner=available;
                // Serialize start/publication against stop; platform calls cannot
                // be forcibly interrupted if its Binder implementation stalls.
                scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(PodBleGattClient.SERVICE)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
            }
        }
        @Override public void close() {
            BluetoothLeScanner previous;
            synchronized(lock) { if(closed) return; closed=true; previous=scanner; scanner=null; }
            if(previous!=null) previous.stopScan(callback);
        }
    }
}
