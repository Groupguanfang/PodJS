package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PodSyncStreamTest {
    @Test public void tcpTransfersFramesBothWaysAndCloseUnblocksRead() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket listener = new ServerSocket(0,1,loopback);
             Socket client = new Socket(loopback,listener.getLocalPort());
             Socket server = listener.accept();
             PodSyncStream phone = new PodSyncStream(client);
             PodSyncStream watch = new PodSyncStream(server)) {
            server.setSoTimeout(3000); client.setSoTimeout(3000);
            phone.write(new byte[]{1,2,3}); phone.write(new byte[]{4});
            assertArrayEquals(new byte[]{1,2,3},watch.read());
            assertArrayEquals(new byte[]{4},watch.read());
            watch.write(new byte[]{5,6}); assertArrayEquals(new byte[]{5,6},phone.read());
            FutureTask<Boolean> blocked = new FutureTask<>(() -> {
                try { phone.read(); return false; } catch (IOException expected) { return true; }
            });
            new Thread(blocked,"sync-test-reader").start();
            phone.close(); assertTrue(blocked.get(2,TimeUnit.SECONDS));
            assertNull(watch.read());
        }
    }
    @Test public void fragmentReadsAndMalformedFramesFailClosed() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (PodSyncStream writer = new PodSyncStream(new ByteArrayInputStream(new byte[0]),wire,() -> {})) {
            writer.write(new byte[]{7,8,9});
        }
        assertArrayEquals(new byte[]{0,0,0,3,7,8,9},wire.toByteArray());
        ByteArrayInputStream fragmented = new ByteArrayInputStream(wire.toByteArray()) {
            @Override public synchronized int read(byte[] bytes,int offset,int size) { return super.read(bytes,offset,Math.min(1,size)); }
        };
        try (PodSyncStream reader = new PodSyncStream(fragmented,new ByteArrayOutputStream(),() -> {})) {
            assertArrayEquals(new byte[]{7,8,9},reader.read()); assertNull(reader.read());
        }
        for (byte[] bad : new byte[][]{{0},{0,0,0,0},{-1,-1,-1,-1},{0,0,0,2,1}}) {
            boolean[] closed = {false};
            try (PodSyncStream reader = new PodSyncStream(new ByteArrayInputStream(bad),new ByteArrayOutputStream(),() -> closed[0]=true)) {
                try { reader.read(); fail("Invalid frame accepted"); } catch (IOException expected) { }
                assertTrue(closed[0]);
                try { reader.read(); fail("Failed connection reused"); } catch (IOException expected) { }
            }
        }
    }
}
