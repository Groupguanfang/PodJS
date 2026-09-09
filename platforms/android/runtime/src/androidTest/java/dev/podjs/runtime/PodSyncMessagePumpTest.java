package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class PodSyncMessagePumpTest {
    private Context endpoint(String id) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) {
            @Override public File getNoBackupFilesDir() {
                File root = new File(super.getNoBackupFilesDir(),id);
                if (!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Test directory unavailable");
                return root;
            }
        };
    }
    @Test public void lostAckAndReopenedStoresDoNotRepeatBusinessWork() throws Exception { run(false); }
    @Test public void failedBusinessWriteRemainsPendingAfterReconnect() throws Exception { run(true); }
    private void run(boolean failFirstWrite) throws Exception {
        String app = UUID.randomUUID().toString();
        Context phoneContext = endpoint(app+"-phone"), watchContext = endpoint(app+"-watch");
        byte[] key = PodSyncSession.newChallenge();
        byte[] payload = new byte[PodSyncOutbox.MAX_PAYLOAD]; java.util.Arrays.fill(payload,(byte)42);
        int[] applied = {0};
        for (int cycle=0;cycle<2;cycle++) {
            PodSyncPairingStore phoneKeys = new PodSyncPairingStore(phoneContext,app);
            PodSyncPairingStore watchKeys = new PodSyncPairingStore(watchContext,app);
            if (cycle == 0) { phoneKeys.importAuthorized("phone","watch",key); watchKeys.importAuthorized("watch","phone",key); }
            try (PodSyncConnections phone = new PodSyncConnections(phoneKeys,"phone");
                 PodSyncConnections watch = new PodSyncConnections(watchKeys,"watch");
                 PodSyncOutbox phoneOut = new PodSyncOutbox(phoneContext,app);
                 PodSyncOutbox watchOut = new PodSyncOutbox(watchContext,app);
                 PodSyncInbox phoneIn = new PodSyncInbox(phoneContext,app);
                 PodSyncInbox watchIn = new PodSyncInbox(watchContext,app);
                 ServerSocket listener = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());
                 Socket server = listener.accept()) {
                client.setSoTimeout(10000); server.setSoTimeout(10000);
                if (cycle == 0) phoneOut.enqueue("watch","message-1",payload,100,false,1);
                assertEquals(1,phoneOut.pending("watch",1,10).size());
                FutureTask<PodSyncConnections.Connection> accept = new FutureTask<>(() -> watch.open("phone",new PodSyncStream(server),false,new String[]{"message","ack"}));
                new Thread(accept,"sync-message-accept").start();
                PodSyncConnections.Connection phoneConnection = phone.open("watch",new PodSyncStream(client),true,new String[]{"message","ack"});
                PodSyncConnections.Connection watchConnection = accept.get(10,TimeUnit.SECONDS);
                PodSyncMessagePump sender = new PodSyncMessagePump(phoneConnection,phoneOut,phoneIn);
                PodSyncMessagePump receiver = new PodSyncMessagePump(watchConnection,watchOut,watchIn);
                FutureTask<Boolean> sending = new FutureTask<>(() -> sender.sendNext(1));
                new Thread(sending,"sync-message-send").start();
                final boolean failWrite = failFirstWrite && cycle == 0;
                String result = null;
                try {
                    result = receiver.receiveOne(1,(peer,id,data) -> {
                        assertEquals("phone",peer); assertEquals("message-1",id); assertArrayEquals(payload,data);
                        if (failWrite) throw new java.io.IOException("Simulated business storage failure");
                        applied[0]++;
                    });
                    if (failWrite) fail("Storage failure was acknowledged");
                } catch (java.io.IOException expected) { if (!failWrite) throw expected; }
                assertTrue(sending.get(10,TimeUnit.SECONDS));
                if (failWrite) { assertNull(result); assertEquals(1,watchIn.pending(1,10).size()); }
                else assertEquals(cycle == 0 || failFirstWrite ? "applied" : "duplicate",result);
                assertEquals(failWrite ? 0 : 1,applied[0]);
                if (cycle == 0) {
                    // Drop the ACK without delivering it to the sender; all stores
                    // and both sessions close, then reopen in the next iteration.
                    assertEquals(1,phoneOut.pending("watch",1,10).size());
                } else {
                    assertEquals("ack",sender.receiveOne(1,(peer,id,data) -> fail("ACK invoked business handler")));
                    assertTrue(phoneOut.pending("watch",1,10).isEmpty());
                    assertFalse(sender.sendNext(1));
                    watchIn.expire(100); phone.revoke("watch"); watch.revoke("phone");
                }
            }
        }
    }
}
