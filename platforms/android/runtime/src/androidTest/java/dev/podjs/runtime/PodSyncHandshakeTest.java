package dev.podjs.runtime;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class PodSyncHandshakeTest {
    @Test public void bothPeersNegotiateAndAuthenticateOverTcp() throws Exception { run(false,false); }
    @Test public void wrongPairingKeyClosesConnection() throws Exception { run(true,false); }
    @Test public void wrongPairedIdentityClosesConnection() throws Exception { run(false,true); }
    private void run(boolean wrongKey, boolean wrongIdentity) throws Exception {
        byte[] key = PodSyncSession.newChallenge();
        byte[] remoteKey = wrongKey ? PodSyncSession.newChallenge() : key;
        try (ServerSocket listener = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());
             Socket accepted = listener.accept();
             PodSyncStream phoneLink = new PodSyncStream(client);
             PodSyncStream watchLink = new PodSyncStream(accepted)) {
            client.setSoTimeout(2500); accepted.setSoTimeout(2500);
            FutureTask<PodSyncSession> server = new FutureTask<>(() -> {
                try { return PodSyncHandshake.establish(watchLink,"app","watch","phone",remoteKey,false,new String[]{"message"}); }
                catch (IOException expected) { if (!wrongKey && !wrongIdentity) throw expected; return null; }
            });
            new Thread(server,"sync-handshake-test").start();
            PodSyncSession phone = null;
            try {
                phone = PodSyncHandshake.establish(phoneLink,"app","phone",wrongIdentity ? "other" : "watch",key,true,new String[]{"message"});
                if (wrongKey || wrongIdentity) fail("Untrusted peer accepted");
            } catch (IOException expected) { if (!wrongKey && !wrongIdentity) throw expected; }
            try (PodSyncSession watch = server.get(4,TimeUnit.SECONDS)) {
                if (wrongKey || wrongIdentity) {
                    assertNull(watch);
                    assertTrue(client.isClosed()); assertTrue(accepted.isClosed());
                } else {
                    assertNotNull(phone); assertNotNull(watch);
                    JSONObject sent = phone.command(new JSONObject().put("method","send").put("channel","message")
                        .put("message_id","one").put("payload",new JSONArray().put(42)));
                    assertTrue(sent.getBoolean("ok"));
                    phoneLink.write(sent.getJSONObject("value").getJSONObject("frame").toString().getBytes(StandardCharsets.UTF_8));
                    JSONObject frame = new JSONObject(new String(watchLink.read(),StandardCharsets.UTF_8));
                    JSONObject verified = watch.command(new JSONObject().put("method","verify").put("frame",frame));
                    assertTrue(verified.getBoolean("ok"));
                    assertEquals("pending",verified.getJSONObject("value").getString("delivery"));
                }
            } finally { if (phone != null) phone.close(); }
        }
    }
}
