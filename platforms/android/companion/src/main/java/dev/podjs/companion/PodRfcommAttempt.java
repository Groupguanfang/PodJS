package dev.podjs.companion;

import android.bluetooth.*;
import dev.podjs.runtime.PodSyncStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One secure RFCOMM socket plus the SAME application HMAC handshake. Android
 * bonding is a prerequisite, never a substitute for SDK pairing approval. */
public final class PodRfcommAttempt implements AutoCloseable {
    public static final UUID SERVICE=UUID.fromString("deef0004-654d-4e33-9a27-1341d8c28fd1");
    interface Endpoint extends Closeable { PodSyncStream open() throws IOException; }
    private final Object gate=new Object();
    private final PodCompanion sdk;
    private final String peer;
    private final String[] channels;
    private final int timeoutMillis;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{ Thread t=new Thread(task,"podjs-rfcomm-deadline"); t.setDaemon(true); return t; });
    private Endpoint endpoint;
    private boolean started,closed,transferred,expired;
    private long deadline;
    public PodRfcommAttempt(PodCompanion sdk,String peer,String[] channels,int timeoutMillis) {
        this.sdk=java.util.Objects.requireNonNull(sdk);
        if(peer==null || !peer.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid peer identity");
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("RFCOMM timeout out of range");
        if(channels==null || channels.length==0 || channels.length>4) throw new IllegalArgumentException("Invalid channel grants");
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(String channel:channels) if(!java.util.Arrays.asList("state","message","file","ack").contains(channel) || !seen.add(channel)) throw new IllegalArgumentException("Invalid channel grant");
        this.peer=peer; this.channels=channels.clone(); this.timeoutMillis=timeoutMillis;
    }
    public PodCompanion.Session connect(BluetoothDevice device) throws IOException { return open(new NativeEndpoint(java.util.Objects.requireNonNull(device),null),true); }
    public PodCompanion.Session accept(BluetoothAdapter adapter) throws IOException { return open(new NativeEndpoint(null,java.util.Objects.requireNonNull(adapter)),false); }
    PodCompanion.Session open(Endpoint candidate,boolean initiator) throws IOException {
        boolean reject;
        synchronized(gate) {
            reject=started || closed;
            if(!reject) {
                started=true; endpoint=candidate; deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                timer.schedule(()->terminate(true),timeoutMillis,TimeUnit.MILLISECONDS);
            }
        }
        if(reject) { try { candidate.close(); } catch(Exception ignored) { } throw new IOException("RFCOMM attempt already used or closed"); }
        PodSyncStream stream=null; PodCompanion.Session session=null;
        try {
            stream=candidate.open();
            synchronized(gate) { if(closed) throw new IOException("RFCOMM attempt cancelled"); }
            session=sdk.open(peer,stream,initiator,channels);
            synchronized(gate) {
                if(System.nanoTime()>=deadline) { expired=true; throw new IOException("RFCOMM deadline before handoff"); }
                if(closed) throw new IOException("RFCOMM attempt cancelled before handoff");
                transferred=true; endpoint=null;
            }
            timer.shutdownNow(); return session;
        } catch(Exception error) {
            if(session!=null) try { session.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            else if(stream!=null) try { stream.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            close(); boolean timedOut; synchronized(gate) { timedOut=expired; }
            throw new IOException(timedOut?"RFCOMM connection/authentication deadline exceeded":"RFCOMM attempt failed",error);
        }
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    private void terminate(boolean timeout) {
        Endpoint pending;
        synchronized(gate) {
            if(closed || (timeout && transferred)) return;
            closed=true; expired|=timeout; pending=endpoint; endpoint=null;
        }
        timer.shutdownNow(); if(pending!=null) try { pending.close(); } catch(Exception ignored) { }
    }
    @Override public void close() { terminate(false); }
    private static final class NativeEndpoint implements Endpoint {
        private final Object gate=new Object();
        private final BluetoothDevice device;
        private final BluetoothAdapter adapter;
        private BluetoothSocket socket;
        private BluetoothServerSocket listener;
        private boolean closed;
        NativeEndpoint(BluetoothDevice device,BluetoothAdapter adapter) { this.device=device; this.adapter=adapter; }
        private void own(BluetoothSocket value) throws IOException {
            synchronized(gate) { if(!closed) { socket=value; return; } }
            value.close(); throw new IOException("RFCOMM socket arrived after cancellation");
        }
        @Override public PodSyncStream open() throws IOException {
            synchronized(gate) { if(closed) throw new IOException("RFCOMM endpoint already cancelled"); }
            if(device!=null) {
                if(device.getBondState()!=BluetoothDevice.BOND_BONDED) throw new IOException("System Bluetooth bonding required first");
                BluetoothSocket opened=device.createRfcommSocketToServiceRecord(SERVICE); own(opened); opened.connect();
                if(device.getBondState()!=BluetoothDevice.BOND_BONDED) throw new IOException("Bluetooth bond no longer available");
                return new PodSyncStream(opened);
            }
            java.util.Set<String> approved=new java.util.HashSet<>();
            for(BluetoothDevice bonded:adapter.getBondedDevices()) approved.add(bonded.getAddress());
            if(approved.isEmpty()) throw new IOException("No existing system Bluetooth bonds");
            BluetoothServerSocket opened=adapter.listenUsingRfcommWithServiceRecord("PodJS Sync",SERVICE);
            synchronized(gate) { if(!closed) listener=opened; }
            try {
                synchronized(gate) { if(closed) throw new IOException("RFCOMM listener cancelled"); }
                BluetoothSocket accepted=opened.accept(); own(accepted);
                BluetoothDevice remote=accepted.getRemoteDevice();
                if(!approved.contains(remote.getAddress()) || remote.getBondState()!=BluetoothDevice.BOND_BONDED) throw new IOException("Peer was not bonded when listening began");
                return new PodSyncStream(accepted);
            } finally { opened.close(); synchronized(gate) { if(listener==opened) listener=null; } }
        }
        @Override public void close() {
            BluetoothSocket pending; BluetoothServerSocket waiting;
            synchronized(gate) { if(closed) return; closed=true; pending=socket; waiting=listener; socket=null; listener=null; }
            try { if(waiting!=null) waiting.close(); } catch(IOException ignored) { }
            try { if(pending!=null) pending.close(); } catch(IOException ignored) { }
        }
    }
}
