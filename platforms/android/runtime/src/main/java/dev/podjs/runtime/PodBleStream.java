package dev.podjs.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;

/** MTU-bounded packet adapter for the SAME length-prefixed authenticated protocol.
 * Construct only after GATT negotiation/subscription is ready. A packet sender
 * must serialize and await each write/indication completion, and radio.close()
 * must unblock pending sends. This class is not discovery or a GATT connection.
 * Receive callbacks never block for buffer space: overflow fails the link closed.
 */
public final class PodBleStream implements Closeable {
    public static final int HEADER_BYTES=6;
    public static final int MAX_ATTRIBUTE_BYTES=512;
    public static final int DEFAULT_BUFFER_BYTES=PodSyncStream.MAX_FRAME_BYTES+4;
    public static final int MAX_BUFFER_BYTES=2*PodSyncStream.MAX_FRAME_BYTES+8;
    private static final long MAX_SEQUENCE=0xffffffffL;
    public interface PacketSender { void send(byte[] value) throws IOException; }
    private final Object gate=new Object();
    private final PacketSender sender;
    private final Closeable radio;
    private final int maxValueBytes;
    private final int negotiatedMtu;
    private final byte[] ring;
    private final PodSyncStream framed;
    private int head,size;
    private long expectedSequence,sendSequence;
    private byte[] lastReceived;
    private boolean closed;
    private IOException failure;

    public PodBleStream(int negotiatedMtu,PacketSender sender,Closeable radio) {
        this(negotiatedMtu,DEFAULT_BUFFER_BYTES,sender,radio);
    }
    public PodBleStream(int negotiatedMtu,int receiveBufferBytes,PacketSender sender,Closeable radio) {
        if(negotiatedMtu<23 || negotiatedMtu>517) throw new IllegalArgumentException("Invalid negotiated ATT MTU");
        this.negotiatedMtu=negotiatedMtu;
        maxValueBytes=Math.min(negotiatedMtu-3,MAX_ATTRIBUTE_BYTES);
        if(receiveBufferBytes<maxValueBytes-HEADER_BYTES || receiveBufferBytes>MAX_BUFFER_BYTES) throw new IllegalArgumentException("Invalid BLE receive budget");
        this.sender=java.util.Objects.requireNonNull(sender); this.radio=java.util.Objects.requireNonNull(radio);
        ring=new byte[receiveBufferBytes]; framed=new PodSyncStream(new Incoming(),new Outgoing(),this);
    }
    /** Hand this stream to PodSyncHandshake/PodSyncConnections, never directly to guest code. */
    public PodSyncStream framed() { return framed; }
    public int maximumValueBytes() { return maxValueBytes; }
    public int negotiatedMtu() { return negotiatedMtu; }
    public int bufferedBytes() { synchronized(gate) { return size; } }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    private void ensureOpen() throws IOException {
        if(closed) throw new IOException("BLE stream closed",failure);
    }
    /** One complete characteristic value. Only an identical immediately previous
     * packet may repeat; a gap, altered duplicate, unknown version or overflow
     * closes the link without exposing further bytes. No business ACK is emitted.
     */
    public void receive(byte[] value) throws IOException {
        try {
            synchronized(gate) {
                ensureOpen();
                if(value==null || value.length<=HEADER_BYTES || value.length>maxValueBytes || value[0]!=0x50 || value[1]!=1) throw new IOException("Invalid BLE stream packet");
                long sequence=((long)(value[2]&255)<<24)|((long)(value[3]&255)<<16)|((long)(value[4]&255)<<8)|(value[5]&255);
                if(expectedSequence>0 && sequence==expectedSequence-1 && Arrays.equals(value,lastReceived)) return;
                if(sequence!=expectedSequence || expectedSequence>MAX_SEQUENCE) throw new IOException("BLE packet sequence gap or replay");
                int count=value.length-HEADER_BYTES;
                if(count>ring.length-size) throw new IOException("BLE receive budget exceeded");
                int tail=(head+size)%ring.length, first=Math.min(count,ring.length-tail);
                System.arraycopy(value,HEADER_BYTES,ring,tail,first);
                if(first<count) System.arraycopy(value,HEADER_BYTES+first,ring,0,count-first);
                size+=count; expectedSequence++; lastReceived=value.clone(); gate.notifyAll();
            }
        } catch(IOException error) { abort(error); throw error; }
    }
    private final class Incoming extends InputStream {
        @Override public int read() throws IOException { byte[] one=new byte[1]; read(one,0,1); return one[0]&255; }
        @Override public int read(byte[] output,int offset,int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset,length,output.length);
            if(length==0) return 0;
            synchronized(gate) {
                while(size==0 && !closed) {
                    try { gate.wait(); }
                    catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("BLE receive interrupted"); }
                }
                ensureOpen(); int count=Math.min(length,size), first=Math.min(count,ring.length-head);
                System.arraycopy(ring,head,output,offset,first);
                if(first<count) System.arraycopy(ring,0,output,offset+first,count-first);
                head=(head+count)%ring.length; size-=count; return count;
            }
        }
    }
    /** Only PodSyncStream's whole-frame writer accesses this buffer. */
    private final class Outgoing extends OutputStream {
        private final byte[] pending=new byte[maxValueBytes-HEADER_BYTES];
        private int count;
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
        @Override public void write(byte[] input,int offset,int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset,length,input.length);
            while(length>0) {
                synchronized(gate) { ensureOpen(); }
                int copied=Math.min(length,pending.length-count);
                System.arraycopy(input,offset,pending,count,copied); count+=copied; offset+=copied; length-=copied;
                if(count==pending.length) flush();
            }
        }
        @Override public void flush() throws IOException {
            synchronized(gate) { ensureOpen(); }
            if(count==0) return;
            if(sendSequence>MAX_SEQUENCE) throw new IOException("BLE packet sequence exhausted");
            byte[] packet=new byte[HEADER_BYTES+count]; packet[0]=0x50; packet[1]=1;
            packet[2]=(byte)(sendSequence>>>24); packet[3]=(byte)(sendSequence>>>16); packet[4]=(byte)(sendSequence>>>8); packet[5]=(byte)sendSequence;
            System.arraycopy(pending,0,packet,HEADER_BYTES,count);
            // No receive/state lock across a GATT callback wait. Platform permission
            // or adapter failures must also abort the partially written frame.
            try { sender.send(packet); }
            catch(RuntimeException error) { throw new IOException("BLE packet send failed",error); }
            synchronized(gate) { ensureOpen(); }
            sendSequence++; count=0;
        }
    }
    private void abort(IOException error) {
        synchronized(gate) { if(failure==null) failure=error; }
        try { close(); } catch(IOException cleanup) { error.addSuppressed(cleanup); }
    }
    @Override public void close() throws IOException {
        synchronized(gate) {
            if(closed) return; closed=true; size=0; head=0; lastReceived=null; Arrays.fill(ring,(byte)0); gate.notifyAll();
        }
        radio.close();
    }
}
