package dev.podjs.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-worker incoming file RPC. Remote requests cannot grant local consent.
 * Replies contain only public transfer state, never native paths/errors.
 * Mutations are replayable through the durable transfer journal and chunk hashes;
 * a reply is an observation, not a durable sender receipt. Sender must bind it to
 * the request ID and SHA-256 and persist progress before committing its frame.
 */
public final class PodSyncFilePump {
    private final PodSyncConnections.Connection connection;
    private final PodSyncIncomingFiles files;
    private final PodSyncFileRequests requests;
    public PodSyncFilePump(PodSyncConnections.Connection connection, PodSyncIncomingFiles files) {
        this(connection,files,null);
    }
    public PodSyncFilePump(PodSyncConnections.Connection connection, PodSyncIncomingFiles files, PodSyncFileRequests requests) {
        this.connection=connection; this.files=files; this.requests=requests;
    }
    public boolean sendNext() throws IOException {
        try {
            if(requests==null) throw new IOException("Outgoing file requests unavailable");
            PodSyncFileRequests.Request request=requests.next(connection.peerId());
            if(request==null) return false;
            connection.send("file",request.messageId,request.payload); return true;
        } catch(Exception error) {
            try { connection.close(); } catch(IOException closing) { error.addSuppressed(closing); }
            throw new IOException("File request send failed",error);
        }
    }
    public String receiveOne() throws IOException { return connection.consume(this::receiveVerified); }
    String receiveVerified(JSONObject received) throws Exception {
        JSONObject frame=received.getJSONObject("frame");
        if(!"file".equals(frame.getString("channel"))) throw new IOException("Unexpected file channel");
        JSONArray array=frame.getJSONArray("payload");
        if(array.length()==0 || array.length()>96*1024) throw new IOException("Invalid file request size");
        byte[] payload=new byte[array.length()]; for(int n=0;n<payload.length;n++) payload[n]=(byte)array.getInt(n);
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
        JSONObject request=new JSONObject(text);
        if(request.has("type")) {
            if(requests==null) throw new IOException("Unexpected file reply");
            String result=requests.receive(connection.peerId(),frame.getString("messageId"),request);
            if(!"duplicate".equals(received.getString("delivery"))) connection.commit(frame.getLong("sequence"));
            return result;
        }
        if(files==null) throw new IOException("Incoming file requests unavailable");
        if(!(request.get("version") instanceof Integer) || request.getInt("version")!=1 ||
            !(request.get("method") instanceof String)) throw new IOException("Invalid file request");
        String method=request.getString("method"), peer=connection.peerId();
        JSONObject value=new JSONObject(); String phase;
        if("offer".equals(method)) {
            exact(request,"version","method","manifest");
            phase=files.offer(peer,request.getJSONObject("manifest")).phase;
        } else {
            if(!(request.get("transfer_id") instanceof String)) throw new IOException("Invalid transfer identity");
            String id=request.getString("transfer_id");
            if("chunk".equals(method)) {
                exact(request,"version","method","transfer_id","index","data_base64");
                if(!(request.get("index") instanceof Integer) || !(request.get("data_base64") instanceof String)) throw new IOException("Invalid chunk");
                String encoded=request.getString("data_base64");
                byte[] chunk=Base64.decode(encoded,Base64.NO_WRAP);
                if(chunk.length>65536 || !Base64.encodeToString(chunk,Base64.NO_WRAP).equals(encoded)) throw new IOException("Invalid chunk encoding");
                if(cancelled(peer,id)) phase="cancelled";
                else { files.chunk(peer,id,request.getInt("index"),chunk); phase=files.status(peer,id).phase; }
            } else {
                exact(request,"version","method","transfer_id");
                switch(method) {
                    case "status": phase=files.status(peer,id).phase; break;
                    case "missing":
                        if(cancelled(peer,id)) { value.put("missing",new JSONArray()); phase="cancelled"; }
                        else { value.put("missing",files.missing(peer,id)); phase=files.status(peer,id).phase; } break;
                    case "finish": phase=cancelled(peer,id)?"cancelled":files.finish(peer,id).phase; break;
                    case "cancel": phase=files.cancel(peer,id).phase; break;
                    default: throw new IOException("Unsupported file request");
                }
            }
        }
        value.put("phase",phase);
        JSONObject reply=new JSONObject().put("version",1).put("type","reply")
            .put("request_sha256",hex(MessageDigest.getInstance("SHA-256").digest(payload))).put("value",value);
        if(!"duplicate".equals(received.getString("delivery"))) connection.commit(frame.getLong("sequence"));
        connection.send("file",frame.getString("messageId"),reply.toString().getBytes(StandardCharsets.UTF_8));
        return method;
    }
    private boolean cancelled(String peer,String id) throws Exception {
        String phase=files.status(peer,id).phase;
        if(!phase.equals("cancelled") && !phase.equals("cancelling")) return false;
        files.cancel(peer,id); return true;
    }
    private static void exact(JSONObject value,String... fields) throws IOException {
        if(value.length()!=fields.length) throw new IOException("Invalid file request fields");
        for(String field:fields) if(!value.has(field)) throw new IOException("Missing file request field");
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return out.toString();
    }
}
