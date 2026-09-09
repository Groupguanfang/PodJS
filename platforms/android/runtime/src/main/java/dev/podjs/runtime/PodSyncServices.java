package dev.podjs.runtime;

import android.os.CancellationSignal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Guest service adapter over a host-owned, app-scoped channel owner. Construct
 * only after native package approval. The guest cannot choose the owner, grants,
 * private storage root or an authenticated connection through service arguments.
 * Does not own/close the shared client or advertise target capabilities. */
final class PodSyncServices {
    /** Host supplies actual connection/file operations, including durable
     * synchronize completion. Returning a queued wake is not a sync result. */
    interface LiveOperations {
        Object execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception;
    }
    private final PodSyncClient client;
    private final Set<String> grants;
    private final LiveOperations live;
    PodSyncServices(PodSyncClient client, Set<String> approvedGrants, LiveOperations live) {
        if (client == null || approvedGrants == null) throw new IllegalArgumentException("Missing approved sync owner");
        this.client = client; this.grants = Collections.unmodifiableSet(new HashSet<>(approvedGrants)); this.live = live;
    }
    private static String capability(String method) {
        switch (method) {
            case "sync.state.get": case "sync.state.set": case "sync.state.delete": case "sync.state.synchronize": return "companion.sync.state";
            case "sync.messages.send": case "sync.messages.ack": return "companion.sync.message";
            case "sync.files.offer": case "sync.files.accept": case "sync.files.cancel": case "sync.files.status": return "companion.sync.file";
            default: throw new UnsupportedOperationException("Unknown sync service");
        }
    }
    private static String key(JSONObject args) throws Exception {
        Object value = args.opt("key");
        if (!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid state key");
        return (String)value;
    }
    Object execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        String required = capability(method);
        if (!grants.contains(required)) throw new SecurityException("Sync capability not approved");
        cancellation.throwIfCanceled();
        switch (method) {
            case "sync.state.get": {
                String key = key(args); JSONArray entries = client.stateSnapshot().getJSONArray("entries");
                cancellation.throwIfCanceled();
                for (int n = 0; n < entries.length(); n++) {
                    JSONObject entry = entries.getJSONObject(n);
                    if (key.equals(entry.getString("key"))) return new JSONObject().put("exists", !entry.getBoolean("deleted")).put("entry", entry);
                }
                return new JSONObject().put("exists", false);
            }
            case "sync.state.set": {
                if (!args.has("value")) throw new IllegalArgumentException("Missing state value");
                String key = key(args); Object value = args.get("value");
                cancellation.throwIfCanceled(); return client.setStateEntry(key, value);
            }
            case "sync.state.delete": {
                String key = key(args); cancellation.throwIfCanceled(); return client.deleteStateEntry(key);
            }
            case "sync.messages.send": {
                Object peer=args.opt("peerId"), raw=args.opt("message");
                if (!(peer instanceof String) || !(raw instanceof JSONObject)) throw new IllegalArgumentException("Invalid message arguments");
                JSONObject message=(JSONObject)raw; Object id=message.opt("messageId"), ttlValue=message.opt("ttlMs"), priority=message.opt("priority");
                if (!(id instanceof String) || !(ttlValue instanceof Number) || !(priority instanceof String) || !message.has("payload"))
                    throw new IllegalArgumentException("Invalid message fields");
                Number number=(Number)ttlValue; long ttl=number.longValue();
                if (ttl<=0 || ttl>9007199254740991L || number.doubleValue()!=(double)ttl ||
                    !(priority.equals("normal") || priority.equals("high"))) throw new IllegalArgumentException("Invalid message TTL or priority");
                byte[] bytes=PodSyncStateStore.encodeServiceJson(message.get("payload")); cancellation.throwIfCanceled();
                client.queueMessageWithTtl((String)peer,(String)id,bytes,ttl,priority.equals("high"),System.currentTimeMillis());
                return new JSONObject().put("messageId",id).put("state","queued");
            }
            default:
                if (live == null) throw new UnsupportedOperationException("Approved sync connection operations unavailable");
                return live.execute(method, new JSONObject(args.toString()), cancellation);
        }
    }
}
