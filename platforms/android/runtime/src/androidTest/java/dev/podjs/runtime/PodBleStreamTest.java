package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PodBleStreamTest {
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("Expected IOException"); } catch(IOException expected) { }
    }
    private static byte[] packet(int seq, byte... body) {
        byte[] result=new byte[6+body.length]; result[0]=0x50; result[1]=1;
        result[2]=(byte)(seq>>>24); result[3]=(byte)(seq>>>16);
        result[4]=(byte)(seq>>>8); result[5]=(byte)seq;
        System.arraycopy(body,0,result,6,body.length); return result;
    }
    @Test public void negotiatedMtuRoundTripsAndRingWraps() throws Exception {
        for(int mtu:new int[]{23,64,247,517}) {
            PodBleStream[] pair=new PodBleStream[2]; int[] counts={0,0};
            try {
                for(int i=0;i<2;i++) {
                    final int direction=i;
                    pair[i]=new PodBleStream(mtu,1024,value -> {
                        assertTrue(value.length<=Math.min(mtu-3,512)); counts[direction]++;
                        pair[1-direction].receive(value);
                    },() -> {});
                }
                for(int n=0;n<20;n++) {
                    byte[] body=new byte[731+n]; Arrays.fill(body,(byte)n);
                    pair[0].framed().write(body); assertArrayEquals(body,pair[1].framed().read());
                    pair[1].framed().write(body); assertArrayEquals(body,pair[0].framed().read());
                }
                assertTrue(counts[0]>20); assertEquals(counts[0],counts[1]);
            } finally { for(PodBleStream stream:pair) if(stream!=null) stream.close(); }
        }
    }
    @Test public void maximumFrameAndDuplicateCopyIsolation() throws Exception {
        try(PodBleStream receiver=new PodBleStream(517,value -> {},() -> {});
            PodBleStream sender=new PodBleStream(517,value -> {
                receiver.receive(value); receiver.receive(value.clone());
                Arrays.fill(value,(byte)0);
            },() -> {})) {
            byte[] body=new byte[PodSyncStream.MAX_FRAME_BYTES];
            for(int i=0;i<body.length;i++) body[i]=(byte)i;
            sender.framed().write(body); assertArrayEquals(body,receiver.framed().read());
            assertEquals(0,receiver.bufferedBytes());
        }
    }
    @Test public void malformedSequenceAndOverflowDiscardQueuedBytes() throws Exception {
        for(int kind=0;kind<8;kind++) {
            boolean[] closed={false};
            try(PodBleStream stream=new PodBleStream(23,14,value -> {},() -> closed[0]=true)) {
                stream.receive(packet(0,(byte)7));
                byte[] bad;
                switch(kind) {
                    case 0: bad=packet(2,(byte)8); break;
                    case 1: bad=packet(0,(byte)8); break;
                    case 2: bad=packet(1,(byte)8); bad[0]=0; break;
                    case 3: bad=packet(1,(byte)8); bad[1]=2; break;
                    case 4: bad=packet(1); break;
                    case 5: bad=packet(1,new byte[15]); break;
                    case 6: bad=packet(1,new byte[14]); break;
                    default: stream.receive(packet(1,(byte)9)); bad=packet(0,(byte)7);
                }
                rejected(() -> stream.receive(bad));
                assertTrue(stream.isClosed()); assertTrue(closed[0]); assertEquals(0,stream.bufferedBytes());
                rejected(() -> stream.framed().read());
            }
        }
    }
    @Test public void closeUnblocksReaderAndPendingSender() throws Exception {
        CountDownLatch sending=new CountDownLatch(1),release=new CountDownLatch(1);
        try(PodBleStream stream=new PodBleStream(23,value -> {
            sending.countDown();
            try { if(!release.await(3,TimeUnit.SECONDS)) throw new IOException("send timeout"); }
            catch(InterruptedException error) { throw new IOException(error); }
        },release::countDown)) {
            FutureTask<Boolean> reader=new FutureTask<>(() -> { rejected(() -> stream.framed().read()); return true; });
            FutureTask<Boolean> writer=new FutureTask<>(() -> { rejected(() -> stream.framed().write(new byte[40])); return true; });
            new Thread(reader,"ble-test-reader").start(); new Thread(writer,"ble-test-writer").start();
            assertTrue(sending.await(2,TimeUnit.SECONDS)); stream.close();
            assertTrue(reader.get(2,TimeUnit.SECONDS)); assertTrue(writer.get(2,TimeUnit.SECONDS));
        }
    }
    @Test public void senderFailuresCloseTransport() throws Exception {
        for(boolean runtime:new boolean[]{false,true}) {
            boolean[] closed={false};
            try(PodBleStream stream=new PodBleStream(23,value -> {
                if(runtime) throw new SecurityException("permission revoked");
                throw new IOException("radio lost");
            },() -> closed[0]=true)) {
                rejected(() -> stream.framed().write(new byte[42]));
                assertTrue(closed[0]); assertTrue(stream.isClosed());
            }
        }
    }
}
