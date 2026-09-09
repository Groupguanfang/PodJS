package dev.podjs.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-worker state channel over an already authenticated connection. Owns no
 * thread or polling lifetime. Until a shared dispatcher is attached, this pump
 * requires a connection dedicated to state/ACK frames, not message/file frames.
 * Use PodSyncChannelPump for mixed state and message traffic.
 */
public final class PodSyncStatePump {
    private final PodSyncConnections.Connection connection;
    private final PodSyncStateStore state;
    public PodSyncStatePump(PodSyncConnections.Connection connection, PodSyncStateStore state) { this.connection = connection; this.state = state; }
    public boolean sendNext() throws IOException {
        try {
            PodSyncStateStore.Batch batch = state.prepare(connection.peerId());
            if (batch == null) return false;
            connection.send("state",batch.messageId,batch.payload); return true;
        } catch (Exception error) { throw abort(error); }
    }
    /** applied/duplicate/ack/stale_ack. State, receipt and sender progress must
     * commit before the transient authenticated frame sequence is committed.
     */
    public String receiveOne() throws IOException {
        return connection.consume(this::receiveVerified);
    }
    String receiveVerified(JSONObject received) throws IOException {
        try {
            JSONObject frame = received.getJSONObject("frame");
            String channel = frame.getString("channel"), id = frame.getString("messageId");
            long sequence = frame.getLong("sequence"); boolean duplicateFrame = "duplicate".equals(received.getString("delivery"));
            JSONArray array = frame.getJSONArray("payload");
            if (array.length() > PodSyncStateStore.MAX_BATCH_BYTES) throw new IOException("Invalid state payload size");
            byte[] payload = new byte[array.length()]; for (int n=0;n<payload.length;n++) payload[n]=(byte)array.getInt(n);
            if ("ack".equals(channel)) {
                if (payload.length != 41 || payload[0] != 3) throw new IOException("Invalid state ACK");
                ByteBuffer ack = ByteBuffer.wrap(payload); ack.get(); long cursor = ack.getLong(); byte[] digest = new byte[32]; ack.get(digest);
                boolean matched = state.acknowledge(connection.peerId(),id,cursor,digest);
                if (!duplicateFrame) connection.commit(sequence);
                return matched ? "ack" : "stale_ack";
            }
            if (!"state".equals(channel)) throw new IOException("Unexpected state channel");
            PodSyncStateStore.Receipt receipt = state.receiveBatch(connection.peerId(),id,payload);
            if (!duplicateFrame) connection.commit(sequence);
            // Kind 3 is disjoint from message ACK kinds 1/2. The frame message ID
            // binds this exact batch; the authenticated peer binds its recipient.
            connection.send("ack",id,ByteBuffer.allocate(41).put((byte)3).putLong(receipt.cursor).put(receipt.digest).array());
            return receipt.duplicate ? "duplicate" : "applied";
        } catch (Exception error) { throw abort(error); }
    }
    private IOException abort(Exception error) {
        try { connection.close(); } catch (IOException closing) { error.addSuppressed(closing); }
        return new IOException("State delivery failed",error);
    }
}
