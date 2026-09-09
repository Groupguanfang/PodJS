package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodBackgroundSchedulerTest {
    @Test public void approvedPackageResolvesAndVerifiesHandler() throws Exception {
        String source="globalThis.backgroundHandler=ctx=>ctx.payload===42?'success':'failure'";
        byte[] bytes=source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String file="background/"+PodBackgroundScheduler.sha256("handler")+".js";
        JSONObject item=new JSONObject().put("file",file).put("sha256",PodBackgroundScheduler.sha256(source)).put("bytes",bytes.length);
        JSONObject manifest=new JSONObject().put("schema",1).put("background",new JSONObject().put("refresh",item));
        PodBackgroundPackage approved=new PodBackgroundPackage(UUID.randomUUID().toString(),manifest,path->{
            assertEquals(file,path); return new java.io.ByteArrayInputStream(bytes);
        });
        assertTrue(approved.permitsSource(PodBackgroundScheduler.sha256(source),new String[0]));
        assertFalse(approved.permitsSource(PodBackgroundScheduler.sha256(source),new String[]{"kv.get"}));
        assertFalse(approved.permitsSource("wrong-hash",new String[0]));
        item.put("file","../../untrusted.js"); // Caller mutation cannot change approval snapshot.
        try (PodBackgroundScheduler scheduler=new PodBackgroundScheduler(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            UUID run=approved.schedule(scheduler,"refresh",0,1000,false,42);
            assertEquals("success",await(scheduler,run,"completed").getJSONObject("result").getString("status"));
            try { approved.schedule(scheduler,"unknown",0,1000,false,null); fail("Undeclared handler accepted"); }
            catch (SecurityException expected) { }
            try { new PodBackgroundPackage("app",manifest,path->new java.io.ByteArrayInputStream(bytes)); fail("Traversal accepted"); }
            catch (IllegalArgumentException expected) { }
            item.put("file",file);
            PodBackgroundPackage tampered=new PodBackgroundPackage("app",manifest,path->{
                byte[] changed=bytes.clone(); changed[0]='x'; return new java.io.ByteArrayInputStream(changed);
            });
            try { tampered.schedule(scheduler,"refresh",0,1000,false,null); fail("Tampering accepted"); }
            catch (SecurityException expected) { }
            PodBackgroundPackage oversized=new PodBackgroundPackage("app",manifest,path->new java.io.ByteArrayInputStream(new byte[bytes.length+1]));
            try { oversized.schedule(scheduler,"refresh",0,1000,false,null); fail("Wrong size accepted"); }
            catch (SecurityException expected) { }
        }
    }
    @Test public void payloadPersistsAsAnIndependentSnapshot() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String app=UUID.randomUUID().toString(); UUID run;
        String source="globalThis.backgroundHandler=ctx=>ctx.payload.n===17 && ctx.payload.items[0]==='汉字' ? 'success':'failure'";
        JSONObject payload=new JSONObject().put("n",17).put("items",new org.json.JSONArray().put("汉字").put(JSONObject.NULL));
        try (PodBackgroundScheduler scheduler=new PodBackgroundScheduler(context)) {
            run=scheduler.scheduleVerified(app,"payload",source,PodBackgroundScheduler.sha256(source),System.currentTimeMillis()+500,1000,false,payload);
            payload.put("n",99);
            try { scheduler.scheduleVerified(app,"large",source,PodBackgroundScheduler.sha256(source),0,1000,false,new String(new char[65537]).replace('\0','x')); fail("Oversized payload accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try (PodBackgroundScheduler reopened=new PodBackgroundScheduler(context)) {
            assertEquals("success",await(reopened,run,"completed").getJSONObject("result").getString("status"));
        }
    }
    private JSONObject await(PodBackgroundScheduler scheduler, UUID id, String state) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;
        JSONObject result;
        do {
            result=scheduler.status(id);
            if (state.equals(result.getString("state"))) return result;
            if (android.os.SystemClock.elapsedRealtime()>deadline) fail("Timed out waiting for "+state+": "+result);
            Thread.sleep(25);
        } while (true);
    }
    @Test public void realWorkManagerRunsHeadlessAndResultSurvivesReopen() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String app=UUID.randomUUID().toString(); UUID id;
        String source="globalThis.backgroundHandler=async()=>{if(typeof ui!=='undefined')throw Error('renderer mounted');return 'success'}";
        try (PodBackgroundScheduler scheduler=new PodBackgroundScheduler(context)) {
            id=scheduler.scheduleVerified(app,"refresh",source,PodBackgroundScheduler.sha256(source),0,1000,false);
            JSONObject completed=await(scheduler,id,"completed");
            assertEquals("success",completed.getJSONObject("result").getString("status"));
            assertEquals("completed",completed.getJSONObject("result").getString("code"));
        }
        try (PodBackgroundScheduler reopened=new PodBackgroundScheduler(context)) {
            assertEquals("success",reopened.status(id).getJSONObject("result").getString("status"));
        }
    }
    @Test public void replacementAndCancellationArePersisted() throws Exception {
        String app=UUID.randomUUID().toString(); String source="globalThis.backgroundHandler=()=> 'success'";
        try (PodBackgroundScheduler scheduler=new PodBackgroundScheduler(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            UUID old=scheduler.scheduleVerified(app,"refresh",source,PodBackgroundScheduler.sha256(source),System.currentTimeMillis()+60000,1000,false);
            UUID replacement=scheduler.scheduleVerified(app,"refresh",source,PodBackgroundScheduler.sha256(source),System.currentTimeMillis()+60000,1000,false);
            assertEquals("cancelled",scheduler.status(old).getString("state"));
            scheduler.cancel(app,"refresh");
            assertEquals("cancelled",scheduler.status(replacement).getString("state"));
            assertEquals("CANCELLED",scheduler.status(replacement).getString("systemState"));
            try { scheduler.scheduleVerified(app,"bad",source,"wrong-hash",0,1000,false); fail("Unverified bundle scheduled"); }
            catch (SecurityException expected) { }
        }
    }
    @Test public void retryUsesWorkManagerBackoffAndRunningTaskCanBeCancelled() throws Exception {
        String app=UUID.randomUUID().toString(); String retry="globalThis.backgroundHandler=()=> 'retry'";
        String infinite="globalThis.backgroundHandler=()=>{while(true){}}";
        try (PodBackgroundScheduler scheduler=new PodBackgroundScheduler(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            UUID id=scheduler.scheduleVerified(app,"retry",retry,PodBackgroundScheduler.sha256(retry),0,1000,false);
            assertEquals("retry",await(scheduler,id,"retry").getJSONObject("result").getString("status"));
            scheduler.cancel(app,"retry");
            UUID running=scheduler.scheduleVerified(app,"running",infinite,PodBackgroundScheduler.sha256(infinite),0,10000,false);
            await(scheduler,running,"running"); scheduler.cancel(app,"running");
            assertEquals("cancelled",scheduler.status(running).getString("state"));
            assertEquals("CANCELLED",scheduler.status(running).getString("systemState"));
        }
    }
}
