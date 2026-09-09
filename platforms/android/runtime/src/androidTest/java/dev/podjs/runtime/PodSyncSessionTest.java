package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodSyncSessionTest {
    private JSONArray bytes(byte[] input) {
        JSONArray values = new JSONArray();
        for (byte value : input) values.put(value & 255);
        return values;
    }
    private JSONObject config(JSONObject binding, byte[] key, boolean initiator) throws Exception {
        return new JSONObject().put("key",bytes(key)).put("binding",binding)
            .put("local_is_initiator",initiator).put("allowed_channels",new JSONArray().put("file"));
    }
    private JSONObject value(PodSyncSession session, JSONObject command) throws Exception {
        JSONObject result = session.command(command);
        assertTrue(result.toString(), result.getBoolean("ok"));
        return result.getJSONObject("value");
    }
    private JSONObject transfer(PodSyncStream from, PodSyncStream to, JSONObject data) throws Exception {
        from.write(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new JSONObject(new String(to.read(),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void authenticatedPayloadReachesDurableFileReceiverBeforeAck() throws Exception {
        byte[] key = PodSyncSession.newChallenge();
        JSONObject binding = new JSONObject().put("version",1).put("app_id","test")
            .put("initiator","phone").put("responder","watch")
            .put("initiator_nonce",bytes(PodSyncSession.newChallenge()))
            .put("responder_nonce",bytes(PodSyncSession.newChallenge()));
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
             java.net.Socket client = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort());
             java.net.Socket server = listener.accept();
             PodSyncStream phoneLink = new PodSyncStream(client);
             PodSyncStream watchLink = new PodSyncStream(server);
             PodSyncSession phone = new PodSyncSession(config(binding,key,true));
             PodSyncSession watch = new PodSyncSession(config(binding,key,false));
             PodSyncFileReceiver receiver = new PodSyncFileReceiver(
                 InstrumentationRegistry.getInstrumentation().getTargetContext(),"test",UUID.randomUUID().toString())) {
            client.setSoTimeout(3000); server.setSoTimeout(3000);
            JSONObject offer = new JSONObject().put("method","offer").put("manifest",new JSONObject()
                .put("transfer_id","empty").put("size",0).put("chunk_hashes",new JSONArray())
                .put("mime","text/plain").put("sha256","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
            JSONObject send = new JSONObject().put("method","send").put("channel","file")
                .put("message_id","offer-1").put("payload",bytes(offer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertFalse(phone.command(send).getBoolean("ok"));
            JSONObject phoneProof = transfer(phoneLink,watchLink,value(phone,new JSONObject().put("method","proof")));
            JSONObject watchProof = transfer(watchLink,phoneLink,value(watch,new JSONObject().put("method","proof")));
            JSONObject phoneAuth = value(phone,new JSONObject().put("method","authenticate").put("proof",watchProof.getJSONArray("proof")));
            JSONObject watchAuth = value(watch,new JSONObject().put("method","authenticate").put("proof",phoneProof.getJSONArray("proof")));
            assertEquals(phoneAuth.getString("sessionId"),watchAuth.getString("sessionId"));
            JSONObject frame = transfer(phoneLink,watchLink,value(phone,send).getJSONObject("frame"));
            JSONObject verify = new JSONObject().put("method","verify").put("frame",frame);
            JSONObject pending = value(watch,verify);
            assertEquals("pending",pending.getString("delivery"));
            assertEquals(0,pending.getLong("acknowledged"));
            JSONArray data = frame.getJSONArray("payload"); byte[] decoded = new byte[data.length()];
            for (int i=0;i<decoded.length;i++) decoded[i]=(byte)data.getInt(i);
            assertTrue(new JSONObject(receiver.command(new String(decoded,java.nio.charset.StandardCharsets.UTF_8))).getBoolean("ok"));
            assertEquals(1,value(watch,new JSONObject().put("method","commit").put("sequence",1)).getLong("acknowledged"));
            assertEquals("duplicate",value(watch,verify).getString("delivery"));
            assertTrue(new JSONObject(receiver.command("{\"method\":\"cancel\",\"transfer_id\":\"empty\"}")).getBoolean("ok"));
            frame.put("messageId","tampered");
            assertFalse(watch.command(verify).getBoolean("ok"));
            assertFalse(watch.command(new JSONObject().put("method","proof")).getBoolean("ok"));
        }
    }
}
