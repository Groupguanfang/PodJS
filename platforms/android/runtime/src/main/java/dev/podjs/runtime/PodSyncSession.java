package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.json.JSONObject;

/** Host-only protocol session. Never expose its configuration to guest JavaScript.
 * Pairing keys must come from authenticated provisioning; callers retain exact
 * outbound frames for retry, and commit incoming frames only after durable storage.
 */
public final class PodSyncSession implements AutoCloseable {
    static { System.loadLibrary("podjs_android"); }
    private long handle;

    public static byte[] newChallenge() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    public PodSyncSession(JSONObject hostConfig) {
        byte[] bytes = hostConfig.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > 8192) throw new IllegalArgumentException("Session config too large");
            handle = nativeOpen(bytes);
            if (handle == 0) throw new IllegalStateException("Cannot create sync session");
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    public synchronized JSONObject command(JSONObject command) throws org.json.JSONException {
        if (handle == 0) throw new IllegalStateException("Session closed");
        byte[] bytes = command.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Session command too large");
        return new JSONObject(new String(nativeCommand(handle, bytes), StandardCharsets.UTF_8));
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        nativeClose(handle); handle = 0;
    }
    private static native long nativeOpen(byte[] config);
    private static native byte[] nativeCommand(long handle, byte[] command);
    private static native void nativeClose(long handle);
}
