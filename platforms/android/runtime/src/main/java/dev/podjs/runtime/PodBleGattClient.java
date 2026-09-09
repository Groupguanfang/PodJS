package dev.podjs.runtime;

import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Single-use foreground GATT central. The host selects the device and grants
 * Bluetooth permissions; this does not scan, pair, authenticate or reconnect.
 * connect() and all framed IO must run on workers, never the Android main thread.
 */
public final class PodBleGattClient implements Closeable {
    public static final UUID SERVICE=UUID.fromString("deef0001-654d-4e33-9a27-1341d8c28fd1");
    public static final UUID TO_SERVER=UUID.fromString("deef0002-654d-4e33-9a27-1341d8c28fd1");
    public static final UUID FROM_SERVER=UUID.fromString("deef0003-654d-4e33-9a27-1341d8c28fd1");
    public static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    enum Step { CONNECT, DISCOVER, MTU, SUBSCRIBE, WRITE }
    interface Events {
        void done(Step step,int status,int mtu);
        void received(byte[] value);
        void disconnected();
    }
    /** Test seam models asynchronous platform procedures, not radio performance. */
    interface Backend extends Closeable {
        void start(Events events) throws IOException;
        boolean discover();
        boolean mtu(int requested);
        boolean subscribe();
        boolean write(byte[] value);
    }
    private final Object gate=new Object(),procedure=new Object();
    private final Backend backend;
    private final int timeoutMillis;
    private Step pending;
    private boolean started,closed,completed;
    private int actualMtu=23;
    private IOException failure;
    private PodBleStream stream;
    public PodBleGattClient(Context context,BluetoothDevice device,int timeoutMillis) {
        this(new AndroidBackend(java.util.Objects.requireNonNull(context).getApplicationContext(),
            java.util.Objects.requireNonNull(device)),timeoutMillis);
    }
    PodBleGattClient(Backend backend,int timeoutMillis) {
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("GATT timeout out of range");
        this.backend=java.util.Objects.requireNonNull(backend); this.timeoutMillis=timeoutMillis;
    }
    private final Events events=new Events() {
        @Override public void done(Step step,int status,int mtu) {
            boolean invalid=false;
            synchronized(gate) {
                if(closed) return;
                if(step!=pending || completed || status!=BluetoothGatt.GATT_SUCCESS) invalid=true;
                else if(step==Step.MTU && (mtu<23 || mtu>517)) invalid=true;
                else { if(step==Step.MTU) actualMtu=mtu; completed=true; gate.notifyAll(); }
            }
            if(invalid) fail(new IOException("Unexpected or failed GATT procedure: "+step));
        }
        @Override public void received(byte[] value) {
            PodBleStream target; synchronized(gate) { if(closed) return; target=stream; }
            if(target==null) { fail(new IOException("GATT data before subscription")); return; }
            try { target.receive(value); } catch(IOException error) { fail(error); }
        }
        @Override public void disconnected() { fail(new IOException("GATT disconnected")); }
    };
    private void check() throws IOException { if(closed) throw new IOException("GATT client closed",failure); }
    private void expect(Step step) throws IOException {
        synchronized(gate) { check(); if(pending!=null) throw new IOException("Concurrent GATT procedure"); pending=step; completed=false; }
    }
    private void await(long deadline) throws IOException {
        synchronized(gate) {
            while(!completed && !closed) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw new IOException("GATT procedure deadline exceeded");
                try { TimeUnit.NANOSECONDS.timedWait(gate,remaining); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("GATT procedure interrupted"); }
            }
            check();
            if(System.nanoTime()>=deadline) throw new IOException("GATT procedure deadline exceeded");
            pending=null; completed=false;
        }
    }
    private long deadline() { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis); }
    /** One absolute deadline covers connect, discovery, MTU and indication CCC.
     * Ownership remains here: closing this client or returned stream closes both.
     * Authentication still MUST follow before application data is accepted.
     */
    public PodBleStream connect() throws IOException {
        synchronized(procedure) {
            synchronized(gate) { check(); if(started) throw new IOException("GATT client already used"); started=true; }
            long end=deadline();
            try {
                expect(Step.CONNECT); backend.start(events); await(end);
                expect(Step.DISCOVER); if(!backend.discover()) throw new IOException("GATT discovery rejected"); await(end);
                expect(Step.MTU); if(!backend.mtu(517)) throw new IOException("GATT MTU request rejected"); await(end);
                synchronized(gate) { check(); stream=new PodBleStream(actualMtu,this::send,this::close); }
                expect(Step.SUBSCRIBE); if(!backend.subscribe()) throw new IOException("GATT indication subscription rejected"); await(end);
                synchronized(gate) { check(); return stream; }
            } catch(IOException error) { fail(error); throw error; }
            catch(RuntimeException error) { IOException wrapped=new IOException("GATT setup failed",error); fail(wrapped); throw wrapped; }
        }
    }
    private void send(byte[] value) throws IOException {
        synchronized(procedure) {
            try {
                expect(Step.WRITE); long end=deadline();
                if(!backend.write(value)) throw new IOException("GATT write rejected"); await(end);
            } catch(IOException error) { fail(error); throw error; }
            catch(RuntimeException error) { IOException wrapped=new IOException("GATT write failed",error); fail(wrapped); throw wrapped; }
        }
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    private void fail(IOException error) { synchronized(gate) { if(failure==null) failure=error; } close(); }
    @Override public void close() {
        PodBleStream current;
        synchronized(gate) { if(closed) return; closed=true; current=stream; gate.notifyAll(); }
        // Never wait for the serialized writer; closing must interrupt it.
        try { backend.close(); } catch(Exception ignored) { }
        if(current!=null) try { current.close(); } catch(IOException ignored) { }
    }
    @SuppressWarnings("deprecation")
    private static final class AndroidBackend implements Backend {
        private final Context context;
        private final BluetoothDevice device;
        private final Object lock=new Object();
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic tx,rx;
        private BluetoothGattDescriptor ccc;
        private Events events;
        private boolean closed;
        AndroidBackend(Context context,BluetoothDevice device) { this.context=context; this.device=device; }
        private boolean current(BluetoothGatt value) {
            synchronized(lock) {
                if(closed) return false;
                // A binder callback can race connectGatt's return/publication.
                if(gatt==null) gatt=value;
                return gatt==value;
            }
        }
        private final BluetoothGattCallback callback=new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
                if(!current(g)) return;
                if(status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) events.done(Step.CONNECT,status,0);
                else events.disconnected();
            }
            @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
                if(!current(g)) return;
                if(status==BluetoothGatt.GATT_SUCCESS) {
                    BluetoothGattService service=g.getService(SERVICE);
                    tx=service==null?null:service.getCharacteristic(TO_SERVER);
                    rx=service==null?null:service.getCharacteristic(FROM_SERVER);
                    ccc=rx==null?null:rx.getDescriptor(CCC);
                    if(tx==null || rx==null || ccc==null || (tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0
                        || (rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)==0) status=BluetoothGatt.GATT_FAILURE;
                }
                events.done(Step.DISCOVER,status,0);
            }
            @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status) { if(current(g)) events.done(Step.MTU,status,mtu); }
            @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status) {
                if(current(g)) { if(descriptor!=ccc) events.disconnected(); else events.done(Step.SUBSCRIBE,status,0); }
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic characteristic,int status) {
                if(current(g)) { if(characteristic!=tx) events.disconnected(); else events.done(Step.WRITE,status,0); }
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic) {
                // Pre-33 callback: consume a defensive copy before returning.
                byte[] value=characteristic.getValue(); changed(g,characteristic,value==null?null:value.clone());
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] value) { changed(g,characteristic,value); }
            private void changed(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] value) {
                if(current(g)) { if(characteristic!=rx) events.disconnected(); else events.received(value); }
            }
        };
        @Override public void start(Events events) throws IOException {
            synchronized(lock) { if(closed) throw new IOException("GATT backend closed"); this.events=events; }
            BluetoothGatt opened=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            boolean discard;
            synchronized(lock) { discard=closed || (gatt!=null && gatt!=opened); if(!discard) gatt=opened; }
            if(discard && opened!=null) opened.close();
            if(discard || opened==null) throw new IOException("GATT open failed or cancelled");
        }
        private BluetoothGatt active() { synchronized(lock) { if(closed || gatt==null) throw new IllegalStateException("GATT unavailable"); return gatt; } }
        @Override public boolean discover() { return active().discoverServices(); }
        @Override public boolean mtu(int requested) { return active().requestMtu(requested); }
        @Override public boolean subscribe() {
            BluetoothGatt g=active();
            if(!g.setCharacteristicNotification(rx,true)) return false;
            if(Build.VERSION.SDK_INT>=33) return g.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)==BluetoothStatusCodes.SUCCESS;
            ccc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); return g.writeDescriptor(ccc);
        }
        @Override public boolean write(byte[] value) {
            BluetoothGatt g=active();
            if(Build.VERSION.SDK_INT>=33) return g.writeCharacteristic(tx,value,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;
            tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); tx.setValue(value); return g.writeCharacteristic(tx);
        }
        @Override public void close() {
            BluetoothGatt old; synchronized(lock) { if(closed) return; closed=true; old=gatt; gatt=null; }
            if(old!=null) { try { old.disconnect(); } finally { old.close(); } }
        }
    }
}
