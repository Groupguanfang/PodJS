package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PodBleGattServerTest {
    @Test public void firstRadioSelectionCannotSwitchAndStrictSelectionRemainsStrict() {
        String first="02:50:4F:44:00:01",other="02:50:4F:44:00:02";
        PodBleGattServer.RadioSelection open=new PodBleGattServer.RadioSelection(null);
        assertFalse(open.matches(first)); assertFalse(open.offer(null)); assertTrue(open.offer(first));
        assertTrue(open.matches(first)); assertFalse(open.offer(other)); assertFalse(open.matches(other)); assertTrue(open.offer(first));
        PodBleGattServer.RadioSelection strict=new PodBleGattServer.RadioSelection(first);
        assertFalse(strict.offer(other)); assertTrue(strict.matches(first)); assertTrue(strict.offer(first));
    }
    @Test public void clientAndServerControllersExchangeFramesBothWays() throws Exception {
        PodBleGattServer.Events[] serverEvents=new PodBleGattServer.Events[1];
        PodBleGattClient.Events[] clientEvents=new PodBleGattClient.Events[1];
        CountDownLatch listening=new CountDownLatch(1);
        PodBleGattServer.Backend peripheral=new PodBleGattServer.Backend() {
            public void start(PodBleGattServer.Events events) { serverEvents[0]=events; listening.countDown(); }
            public boolean indicate(byte[] value) { clientEvents[0].received(value.clone()); serverEvents[0].sent(0); return true; }
            public void close() { }
        };
        PodBleGattClient.Backend central=new PodBleGattClient.Backend() {
            public void start(PodBleGattClient.Events events) { clientEvents[0]=events; events.done(PodBleGattClient.Step.CONNECT,0,0); }
            public boolean discover() { clientEvents[0].done(PodBleGattClient.Step.DISCOVER,0,0); return true; }
            public boolean mtu(int requested) { clientEvents[0].done(PodBleGattClient.Step.MTU,0,23); return true; }
            public boolean subscribe() { serverEvents[0].subscribed(23); clientEvents[0].done(PodBleGattClient.Step.SUBSCRIBE,0,0); return true; }
            public boolean write(byte[] value) { serverEvents[0].received(value.clone()); clientEvents[0].done(PodBleGattClient.Step.WRITE,0,0); return true; }
            public void close() { }
        };
        try(PodBleGattServer server=new PodBleGattServer(peripheral,2000);
            PodBleGattClient client=new PodBleGattClient(central,2000)) {
            FutureTask<PodBleStream> accepting=new FutureTask<>(server::accept);
            new Thread(accepting,"gatt-controller-pair").start(); assertTrue(listening.await(1,TimeUnit.SECONDS));
            PodBleStream p=client.connect(),w=accepting.get(1,TimeUnit.SECONDS);
            byte[] body=new byte[65539]; for(int i=0;i<body.length;i++) body[i]=(byte)(i*19);
            p.framed().write(body); assertArrayEquals(body,w.framed().read());
            w.framed().write(body); assertArrayEquals(body,p.framed().read());
        }
    }
    private static class Fake implements PodBleGattServer.Backend {
        PodBleGattServer.Events events;
        int mtu=23,closed,count;
        boolean waitAccept,waitSend,reject,permission;
        final CountDownLatch waiting=new CountDownLatch(1);
        public void start(PodBleGattServer.Events events) {
            this.events=events; if(waitAccept) waiting.countDown(); else events.subscribed(mtu);
        }
        public boolean indicate(byte[] value) {
            if(permission) throw new SecurityException("revoked");
            assertTrue(value.length<=Math.min(mtu-3,512)); count++;
            if(reject) return false;
            if(waitSend) { waiting.countDown(); return true; }
            events.received(value.clone()); events.sent(0); return true;
        }
        public void close() { closed++; }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("Expected IOException"); } catch(IOException expected) { }
    }
    @Test public void subscriptionMtuAndConfirmedIndicationsRoundTrip() throws Exception {
        for(int mtu:new int[]{23,247,517}) {
            Fake fake=new Fake(); fake.mtu=mtu;
            try(PodBleGattServer server=new PodBleGattServer(fake,1000)) {
                PodBleStream stream=server.accept(); byte[] body=new byte[4099];
                for(int i=0;i<body.length;i++) body[i]=(byte)(i*7);
                stream.framed().write(body); assertArrayEquals(body,stream.framed().read()); assertTrue(fake.count>1);
                rejected(server::accept); assertFalse(server.isClosed());
                stream.close(); assertTrue(server.isClosed()); assertEquals(1,fake.closed);
            }
        }
    }
    @Test public void invalidSubscriptionsAndUnsolicitedCallbacksClose() throws Exception {
        Fake invalid=new Fake(); invalid.mtu=22;
        try(PodBleGattServer server=new PodBleGattServer(invalid,1000)) { rejected(server::accept); assertTrue(server.isClosed()); }
        for(int kind=0;kind<3;kind++) {
            Fake fake=new Fake();
            try(PodBleGattServer server=new PodBleGattServer(fake,1000)) {
                PodBleStream stream=server.accept();
                if(kind==0) fake.events.subscribed(23);
                else if(kind==1) fake.events.sent(0);
                else fake.events.received(new byte[]{1});
                assertTrue(server.isClosed()); rejected(() -> stream.framed().read()); assertEquals(1,fake.closed);
            }
        }
    }
    @Test public void deadlinesCloseUnsubscribedServerAndUnconfirmedIndication() throws Exception {
        for(boolean accepting:new boolean[]{false,true}) {
            Fake fake=new Fake(); fake.waitAccept=accepting; fake.waitSend=!accepting;
            try(PodBleGattServer server=new PodBleGattServer(fake,150)) {
                long before=System.nanoTime();
                if(accepting) rejected(server::accept);
                else { PodBleStream stream=server.accept(); rejected(() -> stream.framed().write(new byte[]{1})); }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)>=100);
                assertTrue(server.isClosed()); assertEquals(1,fake.closed);
            }
        }
    }
    @Test public void closeOrDisconnectInterruptsAcceptAndIndication() throws Exception {
        for(boolean accepting:new boolean[]{false,true}) for(boolean disconnect:new boolean[]{false,true}) {
            Fake fake=new Fake(); fake.waitAccept=accepting; fake.waitSend=!accepting;
            try(PodBleGattServer server=new PodBleGattServer(fake,3000)) {
                PodBleStream stream=accepting?null:server.accept();
                FutureTask<Boolean> operation=new FutureTask<>(() -> {
                    if(accepting) rejected(server::accept); else rejected(() -> stream.framed().write(new byte[]{1})); return true;
                });
                new Thread(operation,"gatt-server-test").start(); assertTrue(fake.waiting.await(1,TimeUnit.SECONDS));
                if(disconnect) fake.events.failed(); else server.close();
                assertTrue(operation.get(1,TimeUnit.SECONDS)); assertEquals(1,fake.closed);
                fake.events.subscribed(23); fake.events.sent(0); assertTrue(server.isClosed());
            }
        }
    }
    @Test public void rejectionAndPermissionLossCloseTransport() throws Exception {
        for(boolean permission:new boolean[]{false,true}) {
            Fake fake=new Fake(); fake.permission=permission; fake.reject=!permission;
            try(PodBleGattServer server=new PodBleGattServer(fake,1000)) {
                PodBleStream stream=server.accept(); rejected(() -> stream.framed().write(new byte[]{1}));
                assertTrue(server.isClosed()); assertEquals(1,fake.closed);
            }
        }
    }
}
