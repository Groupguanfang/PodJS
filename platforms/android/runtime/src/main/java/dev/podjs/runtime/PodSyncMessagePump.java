package dev.podjs.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground/host-worker message channel, independent of transport and business schema.
 * No polling thread or background lifetime is created here. The host serializes
 * receiveOne and drives sendNext with backpressure, typically after each ACK.
 */
public final class PodSyncMessagePump {
    public interface Handler {
        /** Persist idempotent business work before returning; throwing leaves it unacknowledged. */
        void apply(String peerId, String messageId, byte[] payload) throws Exception;
    }
    private final PodSyncConnections.Connection connection;
    private final PodSyncOutbox outgoing;
    private final PodSyncInbox incoming;
    public PodSyncMessagePump(PodSyncConnections.Connection connection, PodSyncOutbox outgoing, PodSyncInbox incoming) {
        this.connection=connection; this.outgoing=outgoing; this.incoming=incoming;
    }
    /** Sends but does not remove the highest-priority live message. Reconnection
     * re-signs the same message identity in the fresh session, preserving deduplication.
     */
    public boolean sendNext(long now) throws IOException {
        java.util.List<PodSyncOutbox.Message> batch = outgoing.pending(connection.peerId(),now,1);
        if (batch.isEmpty()) return false;
        PodSyncOutbox.Message message = batch.get(0);
        ByteBuffer envelope = ByteBuffer.allocate(9 + message.payload.length);
        envelope.putLong(message.expiresAt).put((byte)(message.highPriority ? 1 : 0)).put(message.payload);
        connection.send("message",message.messageId,envelope.array()); return true;
    }
    /** Returns applied/duplicate/expired/ack. Receipt and queue writes happen before
     * committing the inbound session sequence. No ACK is generated for ACK frames.
     */
    public String receiveOne(long now, Handler handler) throws IOException {
        return connection.consume(received -> receiveVerified(received,now,handler));
    }
    String receiveVerified(JSONObject received, long now, Handler handler) throws IOException {
        return receiveVerified(received,now,handler,false);
    }
    String receiveVerifiedDeferred(JSONObject received,long now) throws IOException { return receiveVerified(received,now,null,true); }
    /** Explicit application completion, on the same authenticated peer. The
     * durable receipt commits before sending; a lost ACK is retried on replay. */
    public void acknowledge(PodSyncInbox.Message delivery,long now) throws Exception {
        if(delivery==null || !connection.peerId().equals(delivery.peerId)) throw new IOException("Wrong message acknowledgement peer");
        PodSyncInbox.Message message=incoming.acknowledge(delivery.peerId,delivery.messageId,delivery.acknowledgementToken(),now);
        connection.send("ack",message.messageId,ByteBuffer.allocate(33).put((byte)1).put(message.acknowledgementToken()).array());
    }
    private String receiveVerified(JSONObject received,long now,Handler handler,boolean deferred) throws IOException {
        try {
            JSONObject frame = received.getJSONObject("frame");
            String channel = frame.getString("channel"), id = frame.getString("messageId");
            long sequence = frame.getLong("sequence");
            JSONArray values = frame.getJSONArray("payload"); byte[] bytes = new byte[values.length()];
            for (int i=0;i<bytes.length;i++) bytes[i]=(byte)values.getInt(i);
            boolean duplicateFrame = "duplicate".equals(received.getString("delivery"));
            if ("ack".equals(channel)) {
                if (bytes.length != 33 || (bytes[0] != 1 && bytes[0] != 2)) throw new IOException("Invalid message ACK");
                // The connection fixes the authenticated sender; payload cannot select another peer.
                outgoing.acknowledgeVerified(connection.peerId(),id,java.util.Arrays.copyOfRange(bytes,1,33));
                if (!duplicateFrame) connection.commit(sequence);
                return "ack";
            }
            if (!"message".equals(channel) || bytes.length < 9 || bytes.length > PodSyncOutbox.MAX_PAYLOAD + 9)
                throw new IOException("Invalid message envelope");
            ByteBuffer envelope = ByteBuffer.wrap(bytes); long expires = envelope.getLong(); int priority = envelope.get() & 255;
            if (priority > 1) throw new IOException("Invalid message priority");
            byte[] payload = new byte[envelope.remaining()]; envelope.get(payload);
            PodSyncInbox.Delivery delivery = incoming.receive(connection.peerId(),id,payload,expires,priority == 1,now);
            String result;
            if (delivery == PodSyncInbox.Delivery.PENDING) {
                if(deferred) {
                    // Inbox durability permits transport sequence advancement,
                    // but not the business ACK that removes the peer's outbox.
                    if(!duplicateFrame) connection.commit(sequence);
                    return "pending";
                }
                handler.apply(connection.peerId(),id,payload);
                incoming.markApplied(connection.peerId(),id); result = "applied";
            } else result = delivery == PodSyncInbox.Delivery.APPLIED ? "duplicate" : "expired";
            if (!duplicateFrame) connection.commit(sequence);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            byte[] ack = ByteBuffer.allocate(33).put((byte)(delivery == PodSyncInbox.Delivery.EXPIRED ? 2 : 1)).put(digest).array();
            connection.send("ack",id,ack);
            return result;
        } catch (Exception error) {
            // A failed application write requires a fresh session and durable retry;
            // never permit subsequent frames to accidentally advance past it.
            try { connection.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            throw new IOException("Message delivery failed",error);
        }
    }
}
