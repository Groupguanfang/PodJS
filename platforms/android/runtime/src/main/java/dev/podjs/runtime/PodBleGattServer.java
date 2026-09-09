package dev.podjs.runtime;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Single selected central, foreground-only GATT peripheral. The selected radio
 * identity is NOT application authentication; authenticate framed() afterwards.
 * accept and framed IO belong on workers. No scan, pairing or permission prompt.
 */
public final class PodBleGattServer implements Closeable {
    interface Events {
        default void advertising() { }
        void subscribed(int mtu);
        void received(byte[] value);
        void sent(int status);
        void failed();
    }
    interface Backend extends Closeable {
        void start(Events events) throws IOException;
        boolean indicate(byte[] value);
    }
    private final Object gate=new Object(),writer=new Object();
    private final Backend backend;
    private final int timeoutMillis;
    private boolean started,closed,pending,sent,advertising;
    private PodBleStream stream;
    public PodBleGattServer(Context context,BluetoothDevice selectedCentral,int timeoutMillis) {
        this(new AndroidBackend(java.util.Objects.requireNonNull(context).getApplicationContext(),
            java.util.Objects.requireNonNull(selectedCentral)),timeoutMillis);
    }
    /** Bind the first connected radio only. A trusted host MUST still authenticate
     * its pre-approved application peer; this grants no pairing or app access. */
    public PodBleGattServer(Context context,int timeoutMillis) {
        this(new AndroidBackend(java.util.Objects.requireNonNull(context).getApplicationContext(),null),timeoutMillis);
    }
    static final class RadioSelection {
        private String selected;
        RadioSelection(String expected) { selected=expected; }
        synchronized boolean offer(String address) {
            if(address==null) return false;
            if(selected==null) selected=address;
            return selected.equals(address);
        }
        synchronized boolean matches(String address) { return selected!=null && selected.equals(address); }
    }
    PodBleGattServer(Backend backend,int timeoutMillis) {
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("GATT timeout out of range");
        this.backend=java.util.Objects.requireNonNull(backend); this.timeoutMillis=timeoutMillis;
    }
    private final Events events=new Events() {
        public void advertising() { synchronized(gate) { if(!closed && stream==null) advertising=true; } }
        public void subscribed(int mtu) {
            boolean bad;
            synchronized(gate) {
                if(closed) return;
                bad=stream!=null || mtu<23 || mtu>517;
                if(!bad) { advertising=false; stream=new PodBleStream(mtu,PodBleGattServer.this::send,PodBleGattServer.this::close); gate.notifyAll(); }
            }
            if(bad) close();
        }
        public void received(byte[] value) {
            PodBleStream target; synchronized(gate) { if(closed) return; target=stream; }
            if(target==null) { close(); return; }
            try { target.receive(value); } catch(IOException error) { close(); }
        }
        public void sent(int status) {
            boolean bad;
            synchronized(gate) { if(closed) return; bad=!pending || sent || status!=BluetoothGatt.GATT_SUCCESS; if(!bad) { sent=true; gate.notifyAll(); } }
            if(bad) close();
        }
        public void failed() { close(); }
    };
    private void check() throws IOException { if(closed) throw new IOException("GATT server closed"); }
    private long deadline() { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis); }
    private void waitUntil(long end) throws IOException {
        long remaining=end-System.nanoTime(); if(remaining<=0) throw new IOException("GATT server deadline exceeded");
        try { TimeUnit.NANOSECONDS.timedWait(gate,remaining); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("GATT server interrupted"); }
    }
    public PodBleStream accept() throws IOException {
        synchronized(gate) { check(); if(started) throw new IOException("GATT server already used"); started=true; }
        long end=deadline();
        try {
            backend.start(events);
            synchronized(gate) {
                while(stream==null && !closed) waitUntil(end);
                check(); if(System.nanoTime()>=end) throw new IOException("GATT server deadline exceeded"); return stream;
            }
        } catch(IOException error) { close(); throw error; }
        catch(RuntimeException error) { close(); throw new IOException("GATT server setup failed",error); }
    }
    private void send(byte[] value) throws IOException {
        synchronized(writer) {
            try {
                synchronized(gate) { check(); pending=true; sent=false; }
                long end=deadline(); if(!backend.indicate(value)) throw new IOException("GATT indication rejected");
                synchronized(gate) {
                    while(!sent && !closed) waitUntil(end);
                    check(); if(System.nanoTime()>=end) throw new IOException("GATT indication deadline exceeded"); pending=false;
                }
            } catch(IOException error) { close(); throw error; }
            catch(RuntimeException error) { close(); throw new IOException("GATT indication failed",error); }
        }
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    /** True only after Android reports advertising start success; not peer discovery. */
    public boolean isAdvertising() { synchronized(gate) { return advertising && !closed; } }
    @Override public void close() {
        PodBleStream current;
        synchronized(gate) { if(closed) return; closed=true; current=stream; gate.notifyAll(); }
        try { backend.close(); } catch(Exception ignored) { }
        if(current!=null) try { current.close(); } catch(IOException ignored) { }
    }
    @SuppressWarnings("deprecation")
    private static final class AndroidBackend implements Backend {
        private final Object lock=new Object();
        private final Context context;
        private BluetoothDevice expected;
        private final RadioSelection selection;
        private BluetoothGattServer server;
        private BluetoothLeAdvertiser advertiser;
        private Events events;
        private boolean closed,connected,subscribed;
        private int mtu=23;
        private final BluetoothGattService service=new BluetoothGattService(PodBleGattClient.SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        private final BluetoothGattCharacteristic tx=new BluetoothGattCharacteristic(PodBleGattClient.FROM_SERVER,BluetoothGattCharacteristic.PROPERTY_INDICATE,0);
        private final BluetoothGattCharacteristic rx=new BluetoothGattCharacteristic(PodBleGattClient.TO_SERVER,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE);
        private final BluetoothGattDescriptor ccc=new BluetoothGattDescriptor(PodBleGattClient.CCC,BluetoothGattDescriptor.PERMISSION_WRITE);
        AndroidBackend(Context context,BluetoothDevice expected) {
            this.context=context; this.expected=expected; selection=new RadioSelection(expected==null?null:expected.getAddress());
            tx.addDescriptor(ccc); service.addCharacteristic(rx); service.addCharacteristic(tx);
        }
        private BluetoothGattServer active() { synchronized(lock) { return closed?null:server; } }
        private boolean selected(BluetoothDevice device) { return device!=null && selection.matches(device.getAddress()); }
        private void fail() { Events target; synchronized(lock) { if(closed) return; target=events; } target.failed(); }
        private final AdvertiseCallback advertising=new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settings) { if(active()!=null) events.advertising(); }
            @Override public void onStartFailure(int code) { fail(); }
        };
        private void respond(BluetoothDevice device,int request,int status) {
            BluetoothGattServer g=active(); if(g==null) return;
            try { if(!g.sendResponse(device,request,status,0,null)) fail(); } catch(RuntimeException error) { fail(); }
        }
        private final BluetoothGattServerCallback callback=new BluetoothGattServerCallback() {
            @Override public void onServiceAdded(int status,BluetoothGattService added) {
                if(active()==null) return;
                if(status!=BluetoothGatt.GATT_SUCCESS || added!=service) { fail(); return; }
                try {
                    synchronized(lock) {
                        if(closed) return;
                        advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true)
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(30000).build(),
                            new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(PodBleGattClient.SERVICE)).build(),advertising);
                    }
                } catch(RuntimeException error) { fail(); }
            }
            @Override public void onConnectionStateChange(BluetoothDevice device,int status,int state) {
                BluetoothGattServer g=active(); if(g==null) return;
                synchronized(lock) {
                    if(closed) return;
                    if(status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED && selection.offer(device.getAddress())) expected=device;
                }
                if(!selected(device)) { try { g.cancelConnection(device); } catch(RuntimeException ignored) { } return; }
                boolean bad;
                synchronized(lock) { if(closed) return; bad=status!=BluetoothGatt.GATT_SUCCESS || state!=BluetoothProfile.STATE_CONNECTED || connected; if(!bad) connected=true; }
                if(bad) fail();
            }
            @Override public void onMtuChanged(BluetoothDevice device,int value) {
                if(!selected(device)) return;
                boolean bad; synchronized(lock) { if(closed) return; bad=!connected || subscribed || value<23 || value>517; if(!bad) mtu=value; }
                if(bad) fail();
            }
            @Override public void onDescriptorWriteRequest(BluetoothDevice device,int request,BluetoothGattDescriptor descriptor,
                    boolean prepared,boolean responseNeeded,int offset,byte[] value) {
                boolean valid;
                synchronized(lock) {
                    if(closed) return;
                    valid=selected(device) && connected && !subscribed && descriptor==ccc && !prepared && responseNeeded && offset==0
                        && Arrays.equals(value,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    if(valid) subscribed=true;
                }
                if(!valid) { if(responseNeeded) respond(device,request,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED); if(selected(device)) fail(); return; }
                events.subscribed(mtu); // Allocate receive stream before central can write after CCC response.
                respond(device,request,BluetoothGatt.GATT_SUCCESS);
                try { advertiser.stopAdvertising(advertising); } catch(RuntimeException error) { fail(); }
            }
            @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int request,BluetoothGattCharacteristic characteristic,
                    boolean prepared,boolean responseNeeded,int offset,byte[] value) {
                boolean valid;
                synchronized(lock) { if(closed) return; valid=selected(device) && connected && subscribed && characteristic==rx && !prepared && responseNeeded && offset==0; }
                if(!valid) { if(responseNeeded) respond(device,request,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED); if(selected(device)) fail(); return; }
                events.received(value);
                if(active()!=null) respond(device,request,BluetoothGatt.GATT_SUCCESS);
            }
            @Override public void onExecuteWrite(BluetoothDevice device,int request,boolean execute) { respond(device,request,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED); if(selected(device)) fail(); }
            @Override public void onCharacteristicReadRequest(BluetoothDevice device,int request,int offset,BluetoothGattCharacteristic characteristic) { respond(device,request,BluetoothGatt.GATT_READ_NOT_PERMITTED); }
            @Override public void onDescriptorReadRequest(BluetoothDevice device,int request,int offset,BluetoothGattDescriptor descriptor) { respond(device,request,BluetoothGatt.GATT_READ_NOT_PERMITTED); }
            @Override public void onNotificationSent(BluetoothDevice device,int status) { if(selected(device) && active()!=null) events.sent(status); }
        };
        @Override public void start(Events events) throws IOException {
            synchronized(lock) { if(closed) throw new IOException("GATT server closed"); this.events=events; }
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            BluetoothLeAdvertiser available=adapter==null?null:adapter.getBluetoothLeAdvertiser();
            if(available==null) throw new IOException("BLE advertising unavailable");
            BluetoothGattServer opened=manager.openGattServer(context,callback);
            synchronized(lock) {
                if(closed || opened==null) { if(opened!=null) opened.close(); throw new IOException("GATT server open cancelled or failed"); }
                server=opened; advertiser=available;
            }
            if(!opened.addService(service)) throw new IOException("GATT service registration rejected");
        }
        @Override public boolean indicate(byte[] value) {
            BluetoothGattServer g=active(); if(g==null) return false;
            BluetoothDevice target; synchronized(lock) { target=expected; }
            if(target==null) return false;
            if(Build.VERSION.SDK_INT>=33) return g.notifyCharacteristicChanged(target,tx,true,value)==BluetoothStatusCodes.SUCCESS;
            tx.setValue(value); return g.notifyCharacteristicChanged(target,tx,true);
        }
        @Override public void close() {
            BluetoothGattServer old; BluetoothLeAdvertiser ads;
            synchronized(lock) { if(closed) return; closed=true; old=server; ads=advertiser; server=null; advertiser=null; }
            try { if(ads!=null) ads.stopAdvertising(advertising); }
            finally {
                if(old!=null) {
                    try { if(expected!=null) old.cancelConnection(expected); }
                    finally { try { old.clearServices(); } finally { old.close(); } }
                }
            }
        }
    }
}
