package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class PodSyncConnectionsTest {
    @Test public void storedCredentialConnectsAndRevocationClosesLiveSession() throws Exception {
        String app = UUID.randomUUID().toString(); byte[] key = PodSyncSession.newChallenge();
        PodSyncPairingStore store = new PodSyncPairingStore(InstrumentationRegistry.getInstrumentation().getTargetContext(),app);
        store.importAuthorized("phone","watch",key);
        try (PodSyncConnections manager = new PodSyncConnections(store,"phone");
             ServerSocket listener = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());
             Socket server = listener.accept(); PodSyncStream remote = new PodSyncStream(server)) {
            client.setSoTimeout(2500); server.setSoTimeout(2500);
            FutureTask<PodSyncSession> handshake = new FutureTask<>(() -> PodSyncHandshake.establish(remote,app,"watch","phone",key,false,new String[]{"message"}));
            new Thread(handshake,"sync-peer-test").start();
            PodSyncConnections.Connection connection = manager.open("watch",new PodSyncStream(client),true,new String[]{"message"});
            try (PodSyncSession peer = handshake.get(4,TimeUnit.SECONDS)) {
                connection.send("message","one",new byte[]{7});
                JSONObject frame = new JSONObject(new String(remote.read(),StandardCharsets.UTF_8));
                assertTrue(peer.command(new JSONObject().put("method","verify").put("frame",frame)).getBoolean("ok"));
                JSONObject reply = peer.command(new JSONObject().put("method","send").put("channel","message")
                    .put("message_id","reply").put("payload",new org.json.JSONArray().put(8)));
                remote.write(reply.getJSONObject("value").getJSONObject("frame").toString().getBytes(StandardCharsets.UTF_8));
                assertEquals("pending",connection.receive().getString("delivery"));
                assertEquals(1,connection.commit(1));
                manager.revoke("watch"); assertNull(store.load("phone","watch"));
                assertTrue(client.isClosed()); assertNull(remote.read());
                try { connection.send("message","two",new byte[]{9}); fail("Revoked connection usable"); }
                catch (IOException expected) { }
            }
        }
    }
    @Test public void revokeInterruptsPendingHandshake() throws Exception {
        String app = UUID.randomUUID().toString();
        PodSyncPairingStore store = new PodSyncPairingStore(InstrumentationRegistry.getInstrumentation().getTargetContext(),app);
        store.importAuthorized("phone","watch",PodSyncSession.newChallenge());
        try (PodSyncConnections manager = new PodSyncConnections(store,"phone");
             ServerSocket listener = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());
             Socket server = listener.accept(); PodSyncStream remote = new PodSyncStream(server)) {
            client.setSoTimeout(3000); server.setSoTimeout(3000);
            FutureTask<Boolean> pending = new FutureTask<>(() -> {
                try { manager.open("watch",new PodSyncStream(client),true,new String[]{"message"}); return false; }
                catch (IOException expected) { return true; }
            });
            new Thread(pending,"sync-pending-test").start();
            assertNotNull(remote.read()); // Hello proves open is registered and waiting for its peer.
            manager.revoke("watch");
            assertTrue(pending.get(2,TimeUnit.SECONDS));
            assertTrue(client.isClosed()); assertNull(store.load("phone","watch"));
        }
    }
}
