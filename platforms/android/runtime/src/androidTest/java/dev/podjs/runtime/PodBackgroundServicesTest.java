package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodBackgroundServicesTest {
    @Test public void asynchronousTransportDeniesUnapprovedBackgroundRequests() throws Exception {
        java.util.concurrent.LinkedBlockingQueue<String> events=new java.util.concurrent.LinkedBlockingQueue<>();
        try (PodServices services=new PodServices(InstrumentationRegistry.getInstrumentation().getTargetContext(),events::offer)) {
            services.dispatch(new JSONObject().put("t","service.request").put("id",1).put("version",1)
                .put("method","background.status").put("args",new JSONObject().put("id","task")));
            String event=events.poll(5,java.util.concurrent.TimeUnit.SECONDS); assertNotNull(event);
            JSONObject result=new JSONObject(event);
            assertFalse(result.getBoolean("ok")); assertEquals("permission_denied",result.getString("code"));
        }
    }
    private PodBackgroundPackage approved(String app) throws Exception {
        String source="globalThis.backgroundHandler=()=> 'success'";
        byte[] bytes=source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JSONObject item=new JSONObject().put("file","background/"+PodBackgroundScheduler.sha256("handler")+".js")
            .put("sha256",PodBackgroundScheduler.sha256(source)).put("bytes",bytes.length);
        return new PodBackgroundPackage(app,new JSONObject().put("schema",1).put("background",new JSONObject().put("handler",item)),
            path->new java.io.ByteArrayInputStream(bytes));
    }
    @Test public void serviceTaskIdentityIsIndependentAndScopedAcrossReopen() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String app=java.util.UUID.randomUUID().toString();
        JSONObject task=new JSONObject().put("id","task").put("handler","handler").put("earliestAt",System.currentTimeMillis()+60000);
        try (PodBackgroundServices service=new PodBackgroundServices(context,approved(app))) {
            JSONObject registered=(JSONObject)service.execute("background.register",new JSONObject().put("task",task));
            assertEquals("task",registered.getString("id"));
            assertEquals("scheduled",registered.getString("state"));
            task.put("intervalMs",1000);
            try { service.execute("background.register",new JSONObject().put("task",task)); fail("Periodic request accepted"); }
            catch (UnsupportedOperationException expected) { }
        }
        try (PodBackgroundServices other=new PodBackgroundServices(context,approved(java.util.UUID.randomUUID().toString()))) {
            try { other.execute("background.status",new JSONObject().put("id","task").put("appId",app)); fail("Cross-app read accepted"); }
            catch (IllegalArgumentException expected) { }
            other.execute("background.cancel",new JSONObject().put("id","task").put("appId",app));
        }
        try (PodBackgroundServices reopened=new PodBackgroundServices(context,approved(app))) {
            assertEquals("scheduled",((JSONObject)reopened.execute("background.status",new JSONObject().put("id","task"))).getString("state"));
            reopened.execute("background.cancel",new JSONObject().put("id","task"));
            assertEquals("cancelled",((JSONObject)reopened.execute("background.status",new JSONObject().put("id","task"))).getString("state"));
        }
    }
}
