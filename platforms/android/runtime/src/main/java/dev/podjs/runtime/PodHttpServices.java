package dev.podjs.runtime;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streaming HTTP download service. This helper is intentionally not wired into the bridge. */
public final class PodHttpServices implements AutoCloseable {
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private final File root;
    private final Map<Integer, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final Map<Integer, HttpURLConnection> connections = new ConcurrentHashMap<>();
    private final Object cancellationLock = new Object();
    private volatile boolean closed;

    public PodHttpServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        File base = new File(context.getFilesDir(), "podjs/files");
        if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = base.getCanonicalFile(); } catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
    }

    public JSONObject execute(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if (!"http.download".equals(method) || args == null) throw new IllegalArgumentException("unsupported_method");
        int requestId = args.optInt("requestId", 0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (requestId != 0 && cancellations.putIfAbsent(requestId, cancelled) != null) throw new IllegalArgumentException("duplicate_request_id");
        try { return download(args, cancelled); }
        finally { if (requestId != 0) { cancellations.remove(requestId, cancelled); connections.remove(requestId); } }
    }

    /** Cancels a currently running download with the matching request id. */
    public boolean cancel(int requestId) {
        AtomicBoolean flag = cancellations.get(requestId);
        if (flag == null) return false;
        boolean changed;
        synchronized (cancellationLock) {
            changed = flag.compareAndSet(false, true);
            HttpURLConnection connection = connections.get(requestId);
            if (connection != null) connection.disconnect();
        }
        return changed;
    }

    @Override public void close() { synchronized (cancellationLock) { closed = true; for (Map.Entry<Integer, AtomicBoolean> entry : cancellations.entrySet()) { entry.getValue().set(true); HttpURLConnection connection = connections.get(entry.getKey()); if (connection != null) connection.disconnect(); } } }

    private JSONObject download(JSONObject args, AtomicBoolean cancelled) throws Exception {
        String method = args.optString("method", "GET").toUpperCase(Locale.US);
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) throw new IllegalArgumentException("unsupported_download_method");
        String encodedBody=args.optString("bodyBase64", "");
        if(encodedBody.length()>1_400_000)throw new IllegalArgumentException("request_body_too_large");
        byte[] requestBody=android.util.Base64.decode(encodedBody,android.util.Base64.DEFAULT);
        if(requestBody.length>1024*1024)throw new IllegalArgumentException("request_body_too_large");
        String rawUrl = required(args, "url");
        File target = safeFile(required(args, "path"));
        long maxBytes = args.has("maxBytes") ? args.getLong("maxBytes") : DEFAULT_MAX_BYTES;
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("invalid_max_bytes");
        int timeout = args.optInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeout < 1 || timeout > 120_000) throw new IllegalArgumentException("invalid_timeout_ms");
        Map<String, String> headers = requestHeaders(args.optJSONObject("headers"));
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("parent_unavailable");
        File tmp = new File(parent, target.getName() + ".download-" + System.nanoTime());
        HttpURLConnection connection = null;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            URL current = checkedUrl(rawUrl);
            int redirects = 0;
            while (true) {
                if (cancelled.get()) throw new IOException("download_cancelled");
                connection = (HttpURLConnection) current.openConnection();
                int requestId = args.optInt("requestId", 0);
                if (requestId != 0) connections.put(requestId, connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if("POST".equals(method)){connection.setDoOutput(true);connection.setFixedLengthStreamingMode(requestBody.length);try(java.io.OutputStream request=connection.getOutputStream()){request.write(requestBody);}}
                int status = connection.getResponseCode();
                if (status < 300 || status > 399) {
                    if (status < 200 || status >= 300) throw new IOException("http_status:" + status);
                    long length = 0;
                    if (!"HEAD".equals(method)) {
                        try (java.io.InputStream in = connection.getInputStream()) {
                            byte[] buffer = new byte[BUFFER_SIZE]; int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (cancelled.get()) throw new IOException("download_cancelled");
                                length += n; if (length > maxBytes) throw new IOException("download_too_large");
                                out.write(buffer, 0, n);
                            }
                        }
                    }
                    synchronized (cancellationLock) {
                        if (cancelled.get()) throw new IOException("download_cancelled");
                        out.getFD().sync();
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    return result(status, connection, current, target, length);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || ++redirects > MAX_REDIRECTS) throw new IOException("redirect_limit");
                URL next = checkedUrl(current.toURI().resolve(location).toURL().toString());
                if (!origin(current).equals(origin(next))) {
                    if("POST".equals(method))throw new IOException("cross_origin_post_redirect");
                    headers.remove("authorization"); headers.remove("cookie"); headers.remove("proxy-authorization");
                }
                if("POST".equals(method)&&(status==301||status==302||status==303)){method="GET";requestBody=new byte[0];headers.remove("content-type");}
                current = next;
                connection.disconnect(); connection = null;
            }
        } finally { if (connection != null) connection.disconnect(); if (tmp.exists()) tmp.delete(); }
    }

    private JSONObject result(int status, HttpURLConnection c, URL url, File target, long size) throws Exception {
        JSONObject headers = new JSONObject();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey(), e.getValue() == null ? "" : String.join(", ", e.getValue()));
        }
        return new JSONObject().put("status", status).put("headers", headers).put("url", url.toString()).put("path", relative(target)).put("size", size);
    }

    private String relative(File file) throws IOException { return root.toPath().relativize(file.getCanonicalFile().toPath()).toString(); }
    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File f = new File(root, path).getCanonicalFile(); String base = root.getPath() + File.separator;
        if (!f.getPath().startsWith(base)) throw new SecurityException("path_outside_root"); return f;
    }
    private static String required(JSONObject a, String key) { String value = a.optString(key, null); if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing_" + key); return value; }
    private static URL checkedUrl(String value) throws Exception { URI uri = URI.create(value); String scheme = uri.getScheme(); if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getUserInfo() != null) throw new IllegalArgumentException("invalid_http_url"); return uri.toURL(); }
    private static String origin(URL url) { return url.getProtocol().toLowerCase(Locale.US) + "://" + url.getAuthority().toLowerCase(Locale.US); }
    private static Map<String, String> requestHeaders(JSONObject object) throws Exception {
        if (object == null) return new HashMap<>();
        Map<String, String> result = new HashMap<>();
        org.json.JSONArray names = object.names();
        if (names == null) return result;
        for (int i = 0; i < names.length(); i++) { String key = names.getString(i); if (object.isNull(key)) continue; result.put(key.toLowerCase(Locale.US), object.getString(key)); }
        return result;
    }
}
