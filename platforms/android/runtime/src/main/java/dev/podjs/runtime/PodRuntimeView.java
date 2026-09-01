package dev.podjs.runtime;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.Choreographer;
import android.util.Base64;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Native full-screen PodJS surface. Production assets are read only from the APK. */
public final class PodRuntimeView extends TextureView implements TextureView.SurfaceTextureListener {
    static { System.loadLibrary("podjs_android"); }
    private long host;
    private final String targetId;
    private float rotaryRemainder;
    private boolean active = true;
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final Map<Integer, HttpURLConnection> requests = new ConcurrentHashMap<>();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTime) {
            if (host != 0 && active) {
                nativeFrame(host);
                String effect;
                while ((effect = nativePollEffect(host)) != null) {
                    if (effect.contains("\"t\":\"haptic\"")) performHapticFeedback(6);
                }
                String command;
                while ((command = nativePollNet(host)) != null) handleNetwork(command);
            }
            if (active) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PodRuntimeView(Context context, String targetId) {
        super(context);
        this.targetId = targetId;
        setSurfaceTextureListener(this);
        setOpaque(true);
        setFocusable(true);
    }

    private static byte[] asset(AssetManager assets, String path) throws IOException {
        try (InputStream stream = assets.open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = stream.read(chunk)) >= 0) output.write(chunk, 0, count);
            return output.toByteArray();
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (host != 0) {
            nativeResize(host, new Surface(texture), width, height);
            nativeTheme(host, currentTheme());
            return;
        }
        File data = new File(getContext().getFilesDir(), "podjs");
        if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("PodJS data directory");
        host = nativeCreate(new Surface(texture), targetId, width, height,
            getResources().getDisplayMetrics().density, data.getAbsolutePath());
        try {
            nativeBoot(host, asset(getContext().getAssets(), "main.pak"),
                asset(getContext().getAssets(), "main.js"),
                asset(getContext().getAssets(), "pod.manifest.json"));
            nativeTheme(host, currentTheme());
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } catch (IOException error) {
            nativeDestroy(host); host = 0;
            throw new IllegalStateException("Signed PodJS assets are missing", error);
        }
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (host != 0) nativeDetachSurface(host);
        return true;
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
        if (host != 0) nativeResize(host, new Surface(t), w, h);
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (host != 0) { nativeDestroy(host); host = 0; }
        network.shutdownNow();
        super.onDetachedFromWindow();
    }

    private String currentTheme() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (host != 0) nativeTheme(host, currentTheme());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (host == 0) return false;
        int count = Math.min(event.getPointerCount(), 8);
        int[] ids = new int[count]; float[] xy = new float[count * 2];
        if (event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < count; i++) {
                ids[i] = event.getPointerId(i);
                xy[i * 2] = event.getX(i) * 240f / getWidth();
                xy[i * 2 + 1] = event.getY(i) * 240f / getHeight();
            }
        } else count = 0;
        nativeInput(host, ids, xy, count, 0);
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            addRotaryDegrees(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * 15f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void handleNetwork(String line) {
        try {
            JSONObject command = new JSONObject(line);
            int handle = command.getInt("handle");
            if ("cancel".equals(command.getString("t"))) {
                HttpURLConnection connection = requests.remove(handle);
                if (connection != null) connection.disconnect();
                return;
            }
            long requestHost = host;
            network.execute(() -> executeRequest(requestHost, handle, command));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid PodJS network command", error);
        }
    }

    private void executeRequest(long requestHost, int handle, JSONObject command) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(command.getString("url")).openConnection();
            requests.put(handle, connection);
            connection.setRequestMethod(command.getString("method"));
            int timeout = command.getInt("timeoutMs");
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            JSONObject headers = command.getJSONObject("headers");
            for (Iterator<String> it = headers.keys(); it.hasNext();) { String key = it.next(); connection.setRequestProperty(key, headers.getString(key)); }
            byte[] requestBody = Base64.decode(command.getString("bodyBase64"), Base64.DEFAULT);
            if (requestBody.length > 0) { connection.setDoOutput(true); connection.getOutputStream().write(requestBody); }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            int limit = command.getInt("maxBytes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int total = 0, count;
            while (input != null && (count = input.read(chunk)) >= 0) { total += count; if (total > limit) throw new IOException("response_too_large"); output.write(chunk, 0, count); }
            JSONObject responseHeaders = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) if (entry.getKey() != null && !entry.getValue().isEmpty()) responseHeaders.put(entry.getKey(), entry.getValue().get(0));
            byte[] body = output.toByteArray(); String finalUrl = connection.getURL().toString(); String encodedHeaders = responseHeaders.toString();
            post(() -> { if (host == requestHost) nativeCompleteHttp(requestHost, handle, status, finalUrl, encodedHeaders, body); });
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            post(() -> { if (host == requestHost) nativeFailHttp(requestHost, handle, "network_error", message); });
        } finally {
            requests.remove(handle); if (connection != null) connection.disconnect();
        }
    }

    /** Wear OS sends fractional scroll units; only integral millidegrees cross the ABI. */
    public void addRotaryDegrees(float degrees) {
        rotaryRemainder += degrees * 1000f;
        int whole = (int) rotaryRemainder;
        rotaryRemainder -= whole;
        if (whole != 0 && host != 0) nativeInput(host, new int[0], new float[0], 0, whole);
    }

    public void setLifecycle(int state) {
        active = state != 2;
        if (host != 0) nativeLifecycle(host, state);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (active) Choreographer.getInstance().postFrameCallback(frameCallback);
    }
    public boolean sendBack() { return host != 0 && nativeBack(host); }

    private static native long nativeCreate(Surface surface, String target, int width, int height, float density, String dataDir);
    private static native void nativeBoot(long host, byte[] pak, byte[] js, byte[] manifest);
    private static native void nativeResize(long host, Surface surface, int width, int height);
    private static native void nativeDetachSurface(long host);
    private static native void nativeInput(long host, int[] ids, float[] xy, int count, int rotaryMilliDegrees);
    private static native void nativeFrame(long host);
    private static native String nativePollEffect(long host);
    private static native String nativePollNet(long host);
    private static native void nativeCompleteHttp(long host, int handle, int status, String url, String headers, byte[] body);
    private static native void nativeFailHttp(long host, int handle, String code, String message);
    private static native void nativeLifecycle(long host, int state);
    private static native void nativeTheme(long host, String theme);
    private static native boolean nativeBack(long host);
    private static native void nativeDestroy(long host);
}
