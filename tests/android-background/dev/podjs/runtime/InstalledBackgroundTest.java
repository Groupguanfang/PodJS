package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run only with the background-apk fixture via podTestSourceDir. */
public class InstalledBackgroundTest {
    @Test public void verifyRevokedProbe() throws Exception {
        String expected=InstrumentationRegistry.getArguments().getString("revokedRunId");
        org.junit.Assume.assumeNotNull(expected);
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (PodBackgroundScheduler store=new PodBackgroundScheduler(context)) {
            JSONObject result=store.status(java.util.UUID.fromString(expected));
            assertEquals("failed",result.getString("state"));
            assertEquals("FAILED",result.getString("systemState"));
            assertEquals("background_permission_denied",result.getJSONObject("result").getString("code"));
        }
    }
    @Test public void productionViewGuestRegistersBackgroundHandler() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String previousRun=null;
        try (PodBackgroundScheduler previous=new PodBackgroundScheduler(context)) {
            try { previousRun=previous.status(context.getPackageName(),"refresh").getString("runId"); }
            catch (IllegalArgumentException absent) { }
        }
        try (androidx.test.core.app.ActivityScenario<dev.podjs.androidwatch.MainActivity> activity=
                androidx.test.core.app.ActivityScenario.launch(dev.podjs.androidwatch.MainActivity.class);
             PodBackgroundScheduler store=new PodBackgroundScheduler(context)) {
            long deadline=android.os.SystemClock.elapsedRealtime()+20000;
            while (true) {
                try {
                    JSONObject status=store.status(context.getPackageName(),"refresh");
                    if (!status.getString("runId").equals(previousRun) && "completed".equals(status.getString("state"))) {
                        android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(
                            new java.io.File(context.getNoBackupFilesDir(),"podjs-background/jobs.sqlite").getPath(),null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                        try (android.database.Cursor row=db.rawQuery("SELECT payload FROM jobs WHERE id=?",new String[]{status.getString("runId")})) {
                            assertTrue(row.moveToFirst());
                            if ("ui-guest".equals(new JSONObject(row.getString(0)).optString("origin"))) {
                                assertEquals("success",status.getJSONObject("result").getString("status"));
                                String saved=new String(java.nio.file.Files.readAllBytes(new java.io.File(context.getFilesDir(),"podjs/podjs-kv.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);
                                assertEquals("后台😀",new JSONObject(saved).getString("background-output"));
                                break;
                            }
                        } finally { db.close(); }
                    }
                } catch (IllegalArgumentException notScheduledYet) { }
                assertTrue("Guest registration did not complete",android.os.SystemClock.elapsedRealtime()<deadline);
                Thread.sleep(50);
            }
        }
    }
    @Test public void verifyColdWakeProbe() throws Exception {
        String expected=InstrumentationRegistry.getArguments().getString("coldWakeRunId");
        org.junit.Assume.assumeNotNull(expected);
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (PodBackgroundScheduler store=new PodBackgroundScheduler(context)) {
            JSONObject result=store.status(java.util.UUID.fromString(expected));
            assertEquals(context.getPackageName(),result.getString("appId"));
            assertEquals("completed",result.getString("state"));
            assertEquals("SUCCEEDED",result.getString("systemState"));
            assertEquals("success",result.getJSONObject("result").getString("status"));
        }
    }
    @Test public void scheduleColdWakeProbe() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("coldWakeProbe")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (PodBackgroundServices service=new PodBackgroundServices(context,PodBackgroundPackage.installed(context,"android-watch"))) {
            JSONObject task=new JSONObject().put("id","refresh").put("handler","refresh")
                .put("earliestAt",System.currentTimeMillis()+Long.parseLong(InstrumentationRegistry.getArguments().getString("coldWakeDelayMs","45000")));
            JSONObject result=(JSONObject)service.execute("background.register",new JSONObject().put("task",task));
            assertEquals("scheduled",result.getString("state"));
            try (PodBackgroundScheduler store=new PodBackgroundScheduler(context)) {
                android.util.Log.i("PodJSColdWake",store.status(context.getPackageName(),"refresh").toString());
            }
        }
    }
    @Test public void installedApkAssetsExecuteWithoutActivity() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("dev.podjs.backgroundtest",context.getPackageName());
        java.util.concurrent.LinkedBlockingQueue<String> events=new java.util.concurrent.LinkedBlockingQueue<>();
        try (PodServices services=new PodServices(context,events::offer)) {
            services.approveInstalledBackground("android-watch");
            JSONObject task=new JSONObject().put("id","refresh").put("handler","refresh").put("earliestAt",0);
            services.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",1)
                .put("method","background.register").put("args",new JSONObject().put("task",task)));
            String line=events.poll(15,java.util.concurrent.TimeUnit.SECONDS); assertNotNull(line);
            JSONObject event=new JSONObject(line); assertTrue(event.toString(),event.getBoolean("ok"));
            assertEquals("refresh",event.getJSONObject("value").getString("id"));
            try (PodBackgroundScheduler store=new PodBackgroundScheduler(context)) {
                long deadline=android.os.SystemClock.elapsedRealtime()+15000;
                while (true) {
                    JSONObject status=store.status(context.getPackageName(),"refresh");
                    if ("completed".equals(status.getString("state"))) {
                        assertEquals("success",status.getJSONObject("result").getString("status")); break;
                    }
                    assertTrue(status.toString(),android.os.SystemClock.elapsedRealtime()<deadline);
                    Thread.sleep(50);
                }
            }
        }
        try { PodBackgroundPackage.installed(context,"wearos-watch"); fail("Wrong target accepted"); }
        catch (SecurityException expected) { }
    }
}
