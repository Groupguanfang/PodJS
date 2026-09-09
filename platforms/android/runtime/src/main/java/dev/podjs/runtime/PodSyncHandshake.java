package dev.podjs.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Establishes one fresh session over a connected stream using a previously
 * authenticated pairing key. This is not pairing or trust-on-first-use.
 * Call on an IO worker with a transport timeout/cancellation configured by host.
 */
public final class PodSyncHandshake {
    private PodSyncHandshake() {}

    /** Loads only a previously authorized pairing and erases the temporary key. */
    public static PodSyncSession establishPaired(PodSyncStream link, PodSyncPairingStore store,
            String localId, String remoteId, boolean initiator,
            String[] grantedChannels) throws IOException {
        byte[] key = null;
        try {
            key = store.load(localId,remoteId);
            if (key == null) throw new IOException("Pairing required");
            return establish(link,store.applicationId(),localId,remoteId,key,initiator,grantedChannels);
        } catch (Exception error) {
            try { link.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            throw new IOException("Paired session unavailable",error);
        } finally { if (key != null) Arrays.fill(key,(byte)0); }
    }

    public static PodSyncSession establish(PodSyncStream link, String applicationId,
            String localId, String pairedRemoteId, byte[] pairingKey, boolean initiator,
            String[] grantedChannels) throws IOException {
        PodSyncSession session = null;
        byte[] key = pairingKey == null ? null : pairingKey.clone();
        try {
            if (key == null || key.length != 32) throw new IOException("Invalid pairing key");
            validateId(applicationId); validateId(localId); validateId(pairedRemoteId);
            if (localId.equals(pairedRemoteId)) throw new IOException("Identical peer identities");
            JSONArray channels = new JSONArray();
            if (grantedChannels == null || grantedChannels.length == 0 || grantedChannels.length > 4)
                throw new IOException("Invalid channel grants");
            for (String channel : grantedChannels) {
                if (!Arrays.asList("state","message","file","ack").contains(channel)) throw new IOException("Invalid channel grant");
                channels.put(channel);
            }
            JSONArray nonce = bytes(PodSyncSession.newChallenge());
            JSONObject local = new JSONObject().put("type","hello").put("protocolVersion",1)
                .put("appId",applicationId).put("sender",localId).put("recipient",pairedRemoteId)
                .put("initiator",initiator).put("nonce",nonce);
            // Role ordering avoids simultaneous large writes and is shared by all transports.
            JSONObject remote;
            if (initiator) { send(link,local); remote = read(link); }
            else { remote = read(link); validateHello(remote,applicationId,pairedRemoteId,localId,true); send(link,local); }
            validateHello(remote,applicationId,pairedRemoteId,localId,!initiator);
            JSONObject binding = new JSONObject().put("version",1).put("app_id",applicationId)
                .put("initiator",initiator ? localId : pairedRemoteId).put("responder",initiator ? pairedRemoteId : localId)
                .put("initiator_nonce",initiator ? nonce : remote.getJSONArray("nonce"))
                .put("responder_nonce",initiator ? remote.getJSONArray("nonce") : nonce);
            session = new PodSyncSession(new JSONObject().put("key",bytes(key)).put("binding",binding)
                .put("local_is_initiator",initiator).put("allowed_channels",channels));
            JSONObject proof = success(session.command(new JSONObject().put("method","proof")));
            proof.put("type","proof");
            JSONObject remoteProof;
            if (initiator) { send(link,proof); remoteProof = read(link); }
            else {
                remoteProof = read(link);
                authenticate(session,remoteProof);
                send(link,proof);
                return session;
            }
            authenticate(session,remoteProof);
            return session;
        } catch (Exception error) {
            if (session != null) session.close();
            try { link.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            throw new IOException("Sync handshake failed",error);
        } finally { if (key != null) Arrays.fill(key,(byte)0); }
    }
    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IOException("Invalid identity");
    }
    private static JSONArray bytes(byte[] data) {
        JSONArray result = new JSONArray(); for (byte value : data) result.put(value & 255); return result;
    }
    private static void validateHello(JSONObject hello, String app, String remote, String local, boolean initiator) throws Exception {
        if (hello.length() != 7 || !"hello".equals(hello.get("type"))
                || !Integer.valueOf(1).equals(hello.get("protocolVersion")) || !app.equals(hello.get("appId"))
                || !remote.equals(hello.get("sender")) || !local.equals(hello.get("recipient"))
                || !Boolean.valueOf(initiator).equals(hello.get("initiator"))) throw new IOException("Peer identity or version mismatch");
        validateBytes(hello.getJSONArray("nonce"));
    }
    private static void validateBytes(JSONArray data) throws Exception {
        if (data.length() != 32) throw new IOException("Invalid challenge or proof");
        for (int i=0;i<32;i++) {
            Object value = data.get(i);
            if (!(value instanceof Integer) || (Integer)value < 0 || (Integer)value > 255) throw new IOException("Invalid byte");
        }
    }
    private static void authenticate(PodSyncSession session, JSONObject proof) throws Exception {
        if (proof.length() != 2 || !"proof".equals(proof.get("type"))) throw new IOException("Invalid proof envelope");
        validateBytes(proof.getJSONArray("proof"));
        success(session.command(new JSONObject().put("method","authenticate").put("proof",proof.getJSONArray("proof"))));
    }
    private static JSONObject success(JSONObject result) throws Exception {
        if (!result.getBoolean("ok")) throw new IOException("Session rejected handshake");
        return result.getJSONObject("value");
    }
    private static void send(PodSyncStream link, JSONObject value) throws IOException {
        link.write(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject read(PodSyncStream link) throws Exception {
        byte[] data = link.read();
        if (data == null || data.length > 8192) throw new IOException("Missing or oversized handshake frame");
        return new JSONObject(new String(data,StandardCharsets.UTF_8));
    }
}
