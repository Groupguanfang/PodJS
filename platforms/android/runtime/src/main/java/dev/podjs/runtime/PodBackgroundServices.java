package dev.podjs.runtime;

import android.content.Context;
import java.io.Closeable;
import org.json.JSONObject;

/** Per-approved-app route. Authorization is represented by the host supplying
 * an approved package, never by any identity/path/hash in request arguments. */
public final class PodBackgroundServices implements Closeable {
    private final PodBackgroundPackage approved;
    private final PodBackgroundScheduler scheduler;
    public PodBackgroundServices(Context context, PodBackgroundPackage approved) throws Exception {
        if (approved==null) throw new SecurityException("Background package approval required");
        this.approved=approved;
        scheduler=new PodBackgroundScheduler(context);
    }
    public synchronized Object execute(String method, JSONObject args) throws Exception {
        if ("background.register".equals(method)) {
            JSONObject task=args.getJSONObject("task");
            String id=task.getString("id"), handler=task.getString("handler");
            if (task.has("intervalMs")) throw new UnsupportedOperationException("Periodic background tasks are not supported");
            Object earliest=task.get("earliestAt");
            if (!(earliest instanceof Number) || task.getDouble("earliestAt")!=task.getLong("earliestAt"))
                throw new IllegalArgumentException("Invalid earliest time");
            if (task.has("requiresNetwork") && !(task.get("requiresNetwork") instanceof Boolean))
                throw new IllegalArgumentException("Invalid network constraint");
            java.util.UUID run=approved.schedule(scheduler,id,handler,task.getLong("earliestAt"),10000,
                task.optBoolean("requiresNetwork",false),task.opt("payload"));
            return result(scheduler.status(run));
        }
        String id=args.getString("id"); PodBackgroundScheduler.id(id);
        if ("background.cancel".equals(method)) { scheduler.cancel(approved.appId(),id); return JSONObject.NULL; }
        if ("background.status".equals(method)) return result(scheduler.status(approved.appId(),id));
        throw new IllegalArgumentException("Unknown background method");
    }
    private JSONObject result(JSONObject stored) throws Exception {
        String state=stored.getString("state");
        JSONObject result=new JSONObject().put("id",stored.getString("taskId"))
            .put("state","retry".equals(state)?"scheduled":state);
        JSONObject outcome=stored.optJSONObject("result");
        if (outcome!=null) result.put("result",outcome.getString("status"));
        return result;
    }
    @Override public synchronized void close() { scheduler.close(); }
}
