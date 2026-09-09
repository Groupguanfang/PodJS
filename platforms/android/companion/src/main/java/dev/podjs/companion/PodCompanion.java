package dev.podjs.companion;

import android.content.Context;
import dev.podjs.runtime.PodSyncClient;

/** Phone SDK name retained as a source-compatible facade over the shared
 * phone/watch channel owner. No state, key store or session is duplicated. */
public final class PodCompanion extends PodSyncClient {
    public PodCompanion(Context context, String appId, String localId) throws Exception {
        super(context, appId, localId);
    }
}
