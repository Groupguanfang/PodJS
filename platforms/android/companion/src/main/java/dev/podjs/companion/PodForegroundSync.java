package dev.podjs.companion;

import dev.podjs.runtime.PodSyncForeground;
import java.util.concurrent.Executor;

/** Source-compatible phone facade for the same bounded watch/phone driver. */
public final class PodForegroundSync extends PodSyncForeground {
    public PodForegroundSync(PodCompanion.Session session, long durationMillis, Executor events, Listener listener) {
        super(session, durationMillis, events, listener);
    }
}
