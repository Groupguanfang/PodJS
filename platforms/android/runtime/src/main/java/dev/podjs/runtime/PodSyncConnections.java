package dev.podjs.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Host-owned authenticated connection registry for one app/local device.
 * Owns the pairing store passed to its constructor. Route all opens/revocations
 * through this registry, not directly through the store, to revoke live access.
 */
public final class PodSyncConnections implements Closeable {
    private final PodSyncPairingStore store;
    private final String localId;
    private final Map<String, Connection> connections = new HashMap<>();
    private boolean closed;

    public PodSyncConnections(PodSyncPairingStore store, String localId) {
        this.store = store; this.localId = localId;
    }
    /** Blocking handshake on caller's IO worker. Takes ownership of link immediately. */
    public Connection open(String remoteId, PodSyncStream link, boolean initiator, String[] grants) throws IOException {
        Connection candidate = new Connection(link,remoteId);
        synchronized (this) {
            connections.entrySet().removeIf(entry -> entry.getValue().isClosed());
            if (closed || connections.containsKey(remoteId) || connections.size() >= 8) {
                link.close(); throw new IOException("Connection unavailable or already open");
            }
            connections.put(remoteId,candidate);
        }
        try {
            PodSyncSession session = PodSyncHandshake.establishPaired(link,store,localId,remoteId,initiator,grants);
            candidate.attach(session);
            return candidate;
        } catch (IOException error) {
            synchronized (this) { connections.remove(remoteId,candidate); }
            try { candidate.close(); } catch (IOException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }
    public synchronized void disconnect(String remoteId) throws IOException {
        Connection connection = connections.remove(remoteId);
        if (connection != null) connection.close();
    }
    /** Close pending/active access first, then invalidate the stored credential. */
    public synchronized void revoke(String remoteId) throws Exception {
        IOException closing = null;
        try { disconnect(remoteId); } catch (IOException error) { closing = error; }
        try { store.revoke(localId,remoteId); }
        catch (Exception error) { if (closing != null) error.addSuppressed(closing); throw error; }
        if (closing != null) throw closing;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        IOException failure = null;
        for (Connection connection : connections.values()) {
            try { connection.close(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        connections.clear();
        try { store.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }

    public static final class Connection implements Closeable {
        private final PodSyncStream link;
        private final String peerId;
        private final Object outgoing = new Object();
        private final Object incoming = new Object();
        private boolean applying;
        private PodSyncSession session;
        private boolean closed;
        private Connection(PodSyncStream link, String peerId) { this.link = link; this.peerId = peerId; }
        public String peerId() { return peerId; }
        private synchronized boolean isClosed() { return closed; }
        private synchronized void attach(PodSyncSession session) throws IOException {
            if (closed) { session.close(); throw new IOException("Connection revoked during handshake"); }
            this.session = session;
        }
        private synchronized JSONObject command(JSONObject command) throws Exception {
            if (closed || session == null) throw new IOException("Connection not authenticated");
            JSONObject result = session.command(command);
            if (!result.getBoolean("ok")) throw new IOException("Sync session rejected command");
            return result.getJSONObject("value");
        }
        /** Returns the exact signed frame; host retains it until acknowledged. */
        public JSONObject send(String channel, String messageId, byte[] payload) throws IOException {
            synchronized (outgoing) {
                try {
                    if (payload == null || payload.length > 256 * 1024 + 1024) throw new IOException("Invalid payload size");
                    org.json.JSONArray bytes = new org.json.JSONArray(); for (byte b : payload) bytes.put(b & 255);
                    JSONObject frame = command(new JSONObject().put("method","send").put("channel",channel)
                        .put("message_id",messageId).put("payload",bytes)).getJSONObject("frame");
                    link.write(frame.toString().getBytes(StandardCharsets.UTF_8)); return frame;
                } catch (Exception error) { throw abort(error); }
            }
        }
        /** Authentication precedes exposure. Do not apply duplicate deliveries.
         * Commit only after durable application storage succeeds. This method does
         * not send an ACK itself or advance durable reconnect cursors.
         */
        public JSONObject receive() throws IOException {
            synchronized (incoming) {
                if (applying) throw abort(new IOException("Reentrant sync receive"));
                return readVerified();
            }
        }
        interface VerifiedReceiver<T> { T apply(JSONObject received) throws Exception; }
        /** Keep read, application persistence and commit in one receive critical
         * section. Another channel/worker cannot verify the next frame early. */
        <T> T consume(VerifiedReceiver<T> receiver) throws IOException {
            synchronized (incoming) {
                if (applying) throw abort(new IOException("Reentrant sync receive"));
                applying = true;
                try { return receiver.apply(readVerified()); }
                catch (Exception error) { throw abort(error); }
                finally { applying = false; }
            }
        }
        private JSONObject readVerified() throws IOException {
            try {
                byte[] bytes = link.read(); if (bytes == null) throw new IOException("Peer disconnected");
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                JSONObject frame = new JSONObject(text);
                JSONObject verified = command(new JSONObject().put("method","verify").put("frame",frame));
                return new JSONObject().put("frame",frame).put("delivery",verified.getString("delivery"))
                    .put("acknowledged",verified.getLong("acknowledged"));
            } catch (Exception error) { throw abort(error); }
        }
        public long commit(long sequence) throws IOException {
            try { return command(new JSONObject().put("method","commit").put("sequence",sequence)).getLong("acknowledged"); }
            catch (Exception error) { throw abort(error); }
        }
        private IOException abort(Exception error) {
            try { close(); } catch (IOException closing) { error.addSuppressed(closing); }
            return new IOException("Sync connection failed",error);
        }
        @Override public void close() throws IOException {
            synchronized (this) {
                if (closed) return; closed = true;
                if (session != null) { session.close(); session = null; }
            }
            link.close();
        }
    }
}
