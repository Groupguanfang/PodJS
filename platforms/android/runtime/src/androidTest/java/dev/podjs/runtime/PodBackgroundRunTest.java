package dev.podjs.runtime;

import org.junit.Test;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class PodBackgroundRunTest {
    @Test public void workerRunsWithoutUiAndReturnsStructuredStatus() throws Exception {
        try (PodBackgroundRun job = new PodBackgroundRun(UUID.randomUUID().toString(),"refresh",
                "globalThis.backgroundHandler=async ctx=>{if(typeof ui!=='undefined'||typeof fetch!=='undefined')throw Error('UI or network leaked'); await Promise.resolve(); return ctx.taskId==='refresh'?'success':'failure'}",1000,8*1024*1024)) {
            JSONObject result = job.run();
            assertEquals("success",result.getString("status")); assertEquals("completed",result.getString("code"));
            assertFalse(job.isRunning());
            try { job.run(); fail("One-shot handle reused"); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void deadlineInterruptsInfiniteHandler() throws Exception {
        try (PodBackgroundRun job = new PodBackgroundRun(UUID.randomUUID().toString(),"refresh","globalThis.backgroundHandler=()=>{while(true){}}",100,8*1024*1024)) {
            JSONObject result = job.run(); assertEquals("failure",result.getString("status"));
            assertEquals("deadline_exceeded",result.getString("code")); assertTrue(result.getLong("elapsedMs")<2000);
        }
    }
    @Test public void closeWhileRunningCancelsWithoutFreeingActiveNativeHandle() throws Exception {
        for (int i=0;i<10;i++) {
            PodBackgroundRun job = new PodBackgroundRun(UUID.randomUUID().toString(),"refresh","globalThis.backgroundHandler=()=>{while(true){}}",2000,8*1024*1024);
            FutureTask<JSONObject> work = new FutureTask<>(job::run);
            new Thread(work,"headless-close-test").start();
            long waitUntil = android.os.SystemClock.elapsedRealtime()+1000;
            while (!job.isRunning()) { if (android.os.SystemClock.elapsedRealtime()>waitUntil) fail("Worker did not start"); Thread.yield(); }
            job.close(); job.cancel(); job.close();
            assertEquals("cancelled",work.get(2,TimeUnit.SECONDS).getString("code"));
            assertFalse(job.isRunning());
            try { job.run(); fail("Closed task reused"); } catch (IllegalStateException expected) { }
        }
    }
}
