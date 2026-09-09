package dev.podjs.runtime;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** RFCOMM server/client primitives. This class never performs discovery or BLE operations. */
public final class PodBluetoothServices implements AutoCloseable {
    public static final int MAX_HANDLES = 8;
    public static final int MAX_READ_BYTES = 65536;

    private final BluetoothAdapter adapter;
    private final Context context;
    private final Object lock = new Object();
    private final Map<String, BluetoothServerSocket> servers = new HashMap<>();
    private final Map<String, BluetoothSocket> connections = new HashMap<>();
    private final Map<String, BluetoothSocket> pending = new HashMap<>();
    private boolean closed;
    private long nextId;

    public PodBluetoothServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    /**
     * Supported methods and exact results: listen(serviceName,uuid) returns
     * {@code {serverId}}; accept(serverId) returns {@code {connectionId,remoteAddress}};
     * connect(address,uuid) returns {@code {connectionId}}; read(connectionId,maxBytes)
     * returns {@code {dataBase64,eof}}; write(connectionId,dataBase64) returns
     * {@code {bytes}}; close(id) returns {@code {closed}}. UUIDs must be supplied by
     * the application. At most eight server/connection handles are held at once.
     */
    public JSONObject execute(String method, JSONObject args) throws Exception {
        if (method == null || args == null) throw new IllegalArgumentException("method_and_args_required");
        synchronized (lock) { if (closed) throw new IllegalStateException("service_closed"); }
        switch (method) {
            case "bluetooth.listen": return listen(args);
            case "bluetooth.accept": return accept(args);
            case "bluetooth.connect": return connect(args);
            case "bluetooth.read": return read(args);
            case "bluetooth.write": return write(args);
            case "bluetooth.close": return closeHandle(args);
            default: throw new IllegalArgumentException("unsupported_method:" + method);
        }
    }

    private JSONObject listen(JSONObject args) throws Exception {
        requireAdapter(); requirePermission(Manifest.permission.BLUETOOTH_CONNECT);
        String name = required(args, "serviceName");
        if (name.isEmpty()) throw new IllegalArgumentException("empty_service_name");
        UUID uuid = uuid(args);
        synchronized (lock) { ensureCapacity(); }
        BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(name, uuid);
        String id;
        synchronized (lock) {
            if (closed) { closeQuietly(server); throw new IllegalStateException("service_closed"); }
            try { ensureCapacity(); } catch (RuntimeException e) { closeQuietly(server); throw e; }
            id = newId("s"); servers.put(id, server);
        }
        return new JSONObject().put("serverId", id);
    }

    private JSONObject accept(JSONObject args) throws Exception {
        requireAdapter(); requirePermission(Manifest.permission.BLUETOOTH_CONNECT);
        String id = required(args, "serverId");
        BluetoothServerSocket server;
        synchronized (lock) { server = servers.get(id); }
        if (server == null) throw new IllegalArgumentException("unknown_server");
        BluetoothSocket socket = server.accept();
        String connectionId;
        synchronized (lock) {
            if (closed || !servers.containsKey(id)) { closeQuietly(socket); throw new IOException("server_closed"); }
            try { ensureCapacity(); } catch (RuntimeException e) { closeQuietly(socket); throw e; }
            connectionId = newId("c"); connections.put(connectionId, socket);
        }
        return new JSONObject().put("connectionId", connectionId).put("remoteAddress", socket.getRemoteDevice().getAddress());
    }

    private JSONObject connect(JSONObject args) throws Exception {
        requireAdapter(); requirePermission(Manifest.permission.BLUETOOTH_CONNECT);
        String address = required(args, "address"); UUID uuid = uuid(args);
        BluetoothSocket socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(uuid);
        String id = args.optString("operationId", null);
        synchronized (lock) {
            if (id == null || id.isEmpty()) id = newId("c");
            if (servers.containsKey(id) || connections.containsKey(id) || pending.containsKey(id)) throw new IllegalArgumentException("duplicate_handle");
            pending.put(id, socket);
        }
        try {
            socket.connect();
            synchronized (lock) {
                boolean wasPending = pending.remove(id) != null;
                if (closed || !wasPending) { closeQuietly(socket); throw new IOException("connection_closed"); }
                try { ensureCapacity(); } catch (RuntimeException e) { closeQuietly(socket); throw e; }
                connections.put(id, socket);
            }
            return new JSONObject().put("connectionId", id);
        } catch (Exception e) { synchronized (lock) { pending.remove(id); } closeQuietly(socket); throw e; }
    }

    private JSONObject read(JSONObject args) throws Exception {
        requireAdapter(); requirePermission(Manifest.permission.BLUETOOTH_CONNECT);
        String id = required(args, "connectionId"); int max = args.optInt("maxBytes", MAX_READ_BYTES);
        if (max <= 0 || max > MAX_READ_BYTES) throw new IllegalArgumentException("invalid_max_bytes");
        BluetoothSocket socket = connection(id); byte[] data = new byte[max]; int n = socket.getInputStream().read(data); boolean eof = n < 0;
        if (n < 0) n = 0;
        return new JSONObject().put("dataBase64", Base64.encodeToString(data, 0, n, Base64.NO_WRAP)).put("eof", eof);
    }

    private JSONObject write(JSONObject args) throws Exception {
        requireAdapter(); requirePermission(Manifest.permission.BLUETOOTH_CONNECT);
        byte[] data;
        try { data = Base64.decode(required(args, "dataBase64"), Base64.DEFAULT); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid_base64", e); }
        if (data.length > MAX_READ_BYTES) throw new IllegalArgumentException("chunk_too_large");
        BluetoothSocket socket = connection(required(args, "connectionId")); OutputStream out = socket.getOutputStream(); out.write(data); out.flush();
        return new JSONObject().put("bytes", data.length);
    }

    private JSONObject closeHandle(JSONObject args) throws Exception {
        String id = required(args, "id"); BluetoothServerSocket server; BluetoothSocket socket;
        synchronized (lock) { server = servers.remove(id); socket = connections.remove(id); if (socket == null) socket = pending.remove(id); }
        if (server != null) closeQuietly(server); if (socket != null) closeQuietly(socket);
        return new JSONObject().put("closed", server != null || socket != null);
    }

    private BluetoothSocket connection(String id) {
        synchronized (lock) { BluetoothSocket s = connections.get(id); if (s == null) throw new IllegalArgumentException("unknown_connection"); return s; }
    }
    private void requireAdapter() { if (adapter == null) throw new IllegalStateException("bluetooth_unavailable"); if (!adapter.isEnabled()) throw new IllegalStateException("bluetooth_disabled"); }
    private void requirePermission(String permission) { if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("missing_permission:" + permission); }
    private static UUID uuid(JSONObject args) { try { return UUID.fromString(required(args, "uuid")); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid_uuid", e); } }
    private static String required(JSONObject args, String key) { String value = args.optString(key, null); if (value == null) throw new IllegalArgumentException("missing_" + key); return value; }
    private void ensureCapacity() { synchronized (lock) { if (servers.size() + connections.size() >= MAX_HANDLES) throw new IllegalStateException("handle_limit"); } }
    private String newId(String prefix) { return prefix + (++nextId); }
    private static void closeQuietly(BluetoothServerSocket s) { try { if (s != null) s.close(); } catch (IOException ignored) {} }
    private static void closeQuietly(BluetoothSocket s) { try { if (s != null) s.close(); } catch (IOException ignored) {} }

    @Override public void close() {
        synchronized (lock) { if (closed) return; closed = true; for (BluetoothServerSocket s : servers.values()) closeQuietly(s); for (BluetoothSocket s : connections.values()) closeQuietly(s); for (BluetoothSocket s : pending.values()) closeQuietly(s); servers.clear(); connections.clear(); pending.clear(); }
    }
}
