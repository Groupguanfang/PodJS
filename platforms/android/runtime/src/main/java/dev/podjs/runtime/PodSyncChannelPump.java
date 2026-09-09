package dev.podjs.runtime;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** One authenticated connection, durable state and message channels. Dispatch
 * happens after verification and inside the connection's receive/apply lock.
 * Unknown channels/ACK kinds close the connection without committing the frame.
 * Optional incoming file routing never grants remote consent. No thread/timer.
 */
public final class PodSyncChannelPump {
    private final PodSyncConnections.Connection connection;
    private final PodSyncStatePump state;
    private final PodSyncMessagePump messages;
    private final PodSyncFilePump files;
    public PodSyncChannelPump(PodSyncConnections.Connection connection, PodSyncStateStore state,
                              PodSyncOutbox outgoing, PodSyncInbox incoming) {
        this(connection,state,outgoing,incoming,null);
    }
    public PodSyncChannelPump(PodSyncConnections.Connection connection, PodSyncStateStore state,
                              PodSyncOutbox outgoing, PodSyncInbox incoming, PodSyncIncomingFiles files) {
        this(connection,state,outgoing,incoming,files,null);
    }
    public PodSyncChannelPump(PodSyncConnections.Connection connection, PodSyncStateStore state,
                              PodSyncOutbox outgoing, PodSyncInbox incoming, PodSyncIncomingFiles files, PodSyncFileRequests requests) {
        this.connection = connection;
        this.state = new PodSyncStatePump(connection,state);
        this.messages = new PodSyncMessagePump(connection,outgoing,incoming);
        this.files = files==null && requests==null?null:new PodSyncFilePump(connection,files,requests);
    }
    public boolean sendState() throws IOException { return state.sendNext(); }
    public boolean sendMessage(long now) throws IOException { return messages.sendNext(now); }
    public boolean sendFile() throws IOException { if(files==null) throw new IOException("File channel unavailable"); return files.sendNext(); }
    /** Returns a channel-qualified outcome such as state.applied or message.ack. */
    public String receiveOne(long now, PodSyncMessagePump.Handler handler) throws IOException {
        return receiveOne(now,handler,false);
    }
    public String receiveOneDeferred(long now) throws IOException { return receiveOne(now,null,true); }
    public void acknowledgeMessage(PodSyncInbox.Message delivery,long now) throws Exception { messages.acknowledge(delivery,now); }
    private String receiveOne(long now,PodSyncMessagePump.Handler handler,boolean deferred) throws IOException {
        return connection.consume(received -> {
            JSONObject frame = received.getJSONObject("frame"); String channel = frame.getString("channel");
            if ("state".equals(channel)) return "state." + state.receiveVerified(received);
            if ("message".equals(channel)) return "message." + (deferred?messages.receiveVerifiedDeferred(received,now):messages.receiveVerified(received,now,handler));
            if ("file".equals(channel) && files!=null) return "file." + files.receiveVerified(received);
            if ("ack".equals(channel)) {
                JSONArray bytes = frame.getJSONArray("payload");
                if (bytes.length() == 41 && bytes.getInt(0) == 3) return "state." + state.receiveVerified(received);
                if (bytes.length() == 33 && (bytes.getInt(0) == 1 || bytes.getInt(0) == 2)) return "message." + messages.receiveVerified(received,now,handler);
            }
            throw new IOException("Unsupported sync channel or ACK kind");
        });
    }
}
