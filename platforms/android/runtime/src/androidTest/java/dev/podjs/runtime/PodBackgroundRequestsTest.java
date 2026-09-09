package dev.podjs.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodBackgroundRequestsTest {
    @Test public void nativeKvAdapterPersistsAcrossIndependentRuns() throws Exception {
        java.io.File root=new java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"kv-bg-"+java.util.UUID.randomUUID());
        assertTrue(root.mkdir());
        try {
            String write="globalThis.backgroundHandler=async c=>{await c.request('kv.set',{key:'shared',value:'汉字😀'});return 'success'}";
            try (PodBackgroundRun run=new PodBackgroundRun("kv-native","task",write,3000,16*1024*1024,null,new String[]{"kv.set"},root.getAbsolutePath())) {
                assertEquals("success",run.run().getString("status"));
                assertNull(run.pollRequest()); // Concrete native adapter consumes its own IO.
            }
            String read="globalThis.backgroundHandler=async c=>{const r=await c.request('kv.get',{key:'shared'});return r.exists&&r.value==='汉字😀'?'success':'failure'}";
            try (PodBackgroundRun run=new PodBackgroundRun("kv-native","task",read,3000,16*1024*1024,null,new String[]{"kv.get"},root.getAbsolutePath())) {
                assertEquals("success",run.run().getString("status"));
            }
        } finally {
            for (String name:new String[]{"podjs-kv.json","podjs-kv.tmp","podjs-kv.lock"}) {
                java.io.File file=new java.io.File(root,name);if(file.exists())assertTrue(file.delete());
            }
            assertTrue(root.delete());
        }
    }
    private JSONObject awaitRequest(PodBackgroundRun run) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        JSONObject request;
        while ((request=run.pollRequest())==null) {
            assertTrue("No native request",android.os.SystemClock.elapsedRealtime()<deadline);
            Thread.sleep(2);
        }
        return request;
    }
    @Test public void unicodeRequestsRoundTripAndRepliesAreOneShot() throws Exception {
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        String source="globalThis.backgroundHandler=async c=>{try{await c.request('ui.draw',{});return 'failure'}catch(e){if(e!=='permission_denied')throw e}const r=await c.request('kv.get',{key:'汉字😀'});return r==='回包😀'?'success':'failure'}";
        try (PodBackgroundRun run=new PodBackgroundRun("requests","task",source,3000,16*1024*1024,null,new String[]{"kv.get"})) {
            java.util.concurrent.Future<JSONObject> result=executor.submit(run::run);
            JSONObject request=awaitRequest(run);
            assertEquals("requests",request.getString("appId"));assertEquals("task",request.getString("taskId"));
            assertEquals("kv.get",request.getString("method"));assertEquals("汉字😀",request.getJSONObject("args").getString("key"));
            JSONObject reply=new JSONObject().put("id",request.getLong("id")).put("ok",true).put("value","回包😀");
            assertTrue(run.reply(reply));assertFalse(run.reply(reply));
            assertEquals("success",result.get(5,java.util.concurrent.TimeUnit.SECONDS).getString("status"));
            assertNull(run.pollRequest());
        } finally {executor.shutdownNow();}
    }
    @Test public void closeCancelsPendingRequestAndRejectsLateReply() throws Exception {
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (PodBackgroundRun run=new PodBackgroundRun("requests-close","task","globalThis.backgroundHandler=c=>c.request('get',{})",3000,16*1024*1024,null,new String[]{"get"})) {
            java.util.concurrent.Future<JSONObject> result=executor.submit(run::run);
            JSONObject request=awaitRequest(run);run.close();
            assertFalse(run.reply(new JSONObject().put("id",request.getLong("id")).put("ok",true).put("value",17)));
            assertNull(run.pollRequest());
            assertEquals("cancelled",result.get(5,java.util.concurrent.TimeUnit.SECONDS).getString("code"));
        } finally {executor.shutdownNow();}
    }
}
