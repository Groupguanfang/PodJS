package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PodBleGattClientTest {
    private static class Fake implements PodBleGattClient.Backend {
        PodBleGattClient.Events events;
        final ArrayList<PodBleGattClient.Step> calls=new ArrayList<>();
        final ArrayList<byte[]> packets=new ArrayList<>();
        PodBleGattClient.Step stall,bad;
        int actualMtu=23,closed;
        final CountDownLatch waiting=new CountDownLatch(1);
        boolean permission;
        private boolean run(PodBleGattClient.Step step) {
            calls.add(step);
            if(step==stall) { waiting.countDown(); return true; }
            events.done(step,step==bad?257:0,actualMtu); return true;
        }
        public void start(PodBleGattClient.Events events) { this.events=events; run(PodBleGattClient.Step.CONNECT); }
        public boolean discover() { return run(PodBleGattClient.Step.DISCOVER); }
        public boolean mtu(int requested) { assertEquals(517,requested); return run(PodBleGattClient.Step.MTU); }
        public boolean subscribe() { return run(PodBleGattClient.Step.SUBSCRIBE); }
        public boolean write(byte[] value) {
            if(permission) throw new SecurityException("revoked");
            packets.add(value.clone()); return run(PodBleGattClient.Step.WRITE);
        }
        public void close() { closed++; }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("Expected failure"); } catch(IOException expected) { }
    }
    @Test public void actualMtuAndConfirmedPacketsCarryExistingFrames() throws Exception {
        Fake fake=new Fake();
        try(PodBleGattClient client=new PodBleGattClient(fake,1000)) {
            PodBleStream stream=client.connect(); assertEquals(20,stream.maximumValueBytes());
            assertEquals(java.util.Arrays.asList(PodBleGattClient.Step.CONNECT,PodBleGattClient.Step.DISCOVER,
                PodBleGattClient.Step.MTU,PodBleGattClient.Step.SUBSCRIBE),fake.calls);
            byte[] body=new byte[131]; for(int i=0;i<body.length;i++) body[i]=(byte)i;
            stream.framed().write(body); assertEquals(10,fake.packets.size());
            for(byte[] packet:fake.packets) { assertTrue(packet.length<=20); fake.events.received(packet); }
            assertArrayEquals(body,stream.framed().read());
            rejected(client::connect); assertFalse(client.isClosed());
            stream.close(); assertTrue(client.isClosed()); assertEquals(1,fake.closed);
        }
    }
    @Test public void failedStagesInvalidMtuAndUnexpectedCallbacksFailClosed() throws Exception {
        for(PodBleGattClient.Step stage:PodBleGattClient.Step.values()) {
            Fake fake=new Fake(); fake.bad=stage;
            try(PodBleGattClient client=new PodBleGattClient(fake,1000)) {
                if(stage==PodBleGattClient.Step.WRITE) {
                    PodBleStream stream=client.connect(); rejected(() -> stream.framed().write(new byte[]{1}));
                } else rejected(client::connect);
                assertTrue(client.isClosed()); assertEquals(1,fake.closed);
            }
        }
        Fake invalid=new Fake(); invalid.actualMtu=518;
        try(PodBleGattClient client=new PodBleGattClient(invalid,1000)) { rejected(client::connect); assertTrue(client.isClosed()); }
        Fake late=new Fake();
        try(PodBleGattClient client=new PodBleGattClient(late,1000)) {
            PodBleStream stream=client.connect(); late.events.done(PodBleGattClient.Step.MTU,0,23);
            assertTrue(client.isClosed()); rejected(() -> stream.framed().read());
        }
    }
    @Test public void setupDeadlineAndWriteDeadlineCloseBackend() throws Exception {
        for(PodBleGattClient.Step stage:new PodBleGattClient.Step[]{PodBleGattClient.Step.CONNECT,PodBleGattClient.Step.SUBSCRIBE,PodBleGattClient.Step.WRITE}) {
            Fake fake=new Fake(); fake.stall=stage;
            try(PodBleGattClient client=new PodBleGattClient(fake,150)) {
                long before=System.nanoTime();
                if(stage==PodBleGattClient.Step.WRITE) { PodBleStream stream=client.connect(); rejected(() -> stream.framed().write(new byte[]{1})); }
                else rejected(client::connect);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)>=100);
                assertTrue(client.isClosed()); assertEquals(1,fake.closed);
            }
        }
    }
    @Test public void cancellationAndDisconnectReleasePendingProcedures() throws Exception {
        for(boolean disconnect:new boolean[]{false,true}) {
            Fake fake=new Fake(); fake.stall=PodBleGattClient.Step.WRITE;
            try(PodBleGattClient client=new PodBleGattClient(fake,3000)) {
                PodBleStream stream=client.connect();
                FutureTask<Boolean> write=new FutureTask<>(() -> { rejected(() -> stream.framed().write(new byte[40])); return true; });
                new Thread(write,"gatt-test-write").start(); assertTrue(fake.waiting.await(1,TimeUnit.SECONDS));
                if(disconnect) fake.events.disconnected(); else client.close();
                assertTrue(write.get(1,TimeUnit.SECONDS)); assertEquals(1,fake.closed);
                // Late binder callbacks must not resurrect a closed connection.
                fake.events.done(PodBleGattClient.Step.WRITE,0,23); assertTrue(client.isClosed());
            }
        }
    }
    @Test public void revokedPermissionAbortsPartialFrame() throws Exception {
        Fake fake=new Fake();
        try(PodBleGattClient client=new PodBleGattClient(fake,1000)) {
            PodBleStream stream=client.connect(); fake.permission=true;
            rejected(() -> stream.framed().write(new byte[55])); assertTrue(client.isClosed()); assertEquals(1,fake.closed);
        }
    }
}
