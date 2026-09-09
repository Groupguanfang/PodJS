package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalNotificationTest {
    private static final class SimulatedProcessDeath extends Error {}
    @Test public void enqueueBeforeCommitPreservesOldGenerationAndOrphanCannotPublish() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        androidx.work.WorkManager work=androidx.work.WorkManager.getInstance(context);
        String id="atomic-"+java.util.UUID.randomUUID();
        try(PodNotifications notifications=new PodNotifications(context)) {
            try {
                notifications.schedule(new JSONObject().put("id",id).put("title","Previous").put("body","Still scheduled").put("at",System.currentTimeMillis()+60000));
                String previous=scheduledRun(context,id);
                try {
                    notifications.schedule(new JSONObject().put("id",id).put("title","Uncommitted").put("body","Must not publish").put("at",System.currentTimeMillis()+1000),()->{throw new SimulatedProcessDeath();});
                    fail("Fault boundary not reached");
                } catch(SimulatedProcessDeath expected) {}
                assertEquals(previous,scheduledRun(context,id));
                assertFalse(work.getWorkInfoById(java.util.UUID.fromString(previous)).get(10,java.util.concurrent.TimeUnit.SECONDS).getState().isFinished());
                java.util.UUID orphan=null;
                for(androidx.work.WorkInfo info:work.getWorkInfosByTag("podjs-notification:"+id).get(10,java.util.concurrent.TimeUnit.SECONDS))
                    if(!previous.equals(info.getId().toString()))orphan=info.getId();
                assertNotNull("New work must be durable before record commit",orphan);
                long deadline=android.os.SystemClock.elapsedRealtime()+15000;
                while(!work.getWorkInfoById(orphan).get(10,java.util.concurrent.TimeUnit.SECONDS).getState().isFinished()) {
                    assertTrue("Orphan did not run",android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(25);
                }
                assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,work.getWorkInfoById(orphan).get(10,java.util.concurrent.TimeUnit.SECONDS).getState());
                assertEquals(previous,scheduledRun(context,id));
                for(android.service.notification.StatusBarNotification item:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                    assertNotEquals("podjs:"+id,item.getTag());
                notifications.schedule(new JSONObject().put("id",id).put("title","Committed replacement").put("body","Durable").put("at",System.currentTimeMillis()+60000));
                String committed=scheduledRun(context,id);assertNotEquals(previous,committed);
                assertNotNull(work.getWorkInfoById(java.util.UUID.fromString(committed)).get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(notifications.deliverScheduled(id,previous));
                assertEquals(committed,scheduledRun(context,id));
            } finally {notifications.cancel(id);}
        }
    }
    @Test public void sdkCallbacksReceiveOpenAndActionAndAcknowledge() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity=instrumentation.startActivitySync(new android.content.Intent(context,dev.podjs.androidwatch.MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try (PodNotifications notifications=new PodNotifications(context)) {
            for(String kind:new String[]{"open","action"}) {
                String id="sdk-"+java.util.UUID.randomUUID();
                notifications.show(new JSONObject().put("id",id).put("title","SDK notification").put("body","回调测试")
                    .put("payload",91).put("actions",new org.json.JSONArray().put(new JSONObject().put("id","approve").put("title","Approve"))));
                android.app.PendingIntent click=null;
                long publishedBy=android.os.SystemClock.elapsedRealtime()+5000;
                while(click==null && android.os.SystemClock.elapsedRealtime()<publishedBy) {
                    for(android.service.notification.StatusBarNotification item:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                        if(("podjs:"+id).equals(item.getTag()))click="open".equals(kind)?item.getNotification().contentIntent:item.getNotification().actions[0].actionIntent;
                    if(click==null)Thread.sleep(25);
                }
                assertNotNull(click);click.send();
                long deadline=android.os.SystemClock.elapsedRealtime()+20000;
                while(true) {
                    java.io.File file=new java.io.File(context.getFilesDir(),"podjs/podjs-kv.json");
                    if(file.isFile()) {
                        JSONObject kv=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                        String saved=kv.optString("notification-"+kind,"");
                        if(!saved.isEmpty()) {
                            JSONObject event=new JSONObject(saved);
                            if(id.equals(event.optString("notificationId"))) {
                                assertEquals(91,event.getInt("payload"));assertEquals("granted",event.getString("permission"));
                                if("action".equals(kind))assertEquals("approve",event.getString("actionId"));
                                boolean pending=false;org.json.JSONArray inbox=notifications.pendingEvents();
                                for(int i=0;i<inbox.length();i++)if(id.equals(inbox.getJSONObject(i).getJSONObject("value").getString("notificationId")))pending=true;
                                if(!pending)break;
                            }
                        }
                    }
                    assertTrue("SDK callback/ACK did not complete: "+kind,android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(25);
                }
                notifications.cancel(id);
            }
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    private JSONObject service(PodServices services,java.util.concurrent.LinkedBlockingQueue<String> events,int id,String method,JSONObject args) throws Exception {
        services.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method).put("args",args));
        String line=events.poll(10,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(line);
        JSONObject result=new JSONObject(line);assertEquals(id,result.getInt("id"));assertTrue(result.toString(),result.getBoolean("ok"));return result;
    }
    @Test public void notificationServiceRoutesPermissionScheduleListAndCancel() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT<33); // No fake approval of a runtime prompt.
        java.util.concurrent.LinkedBlockingQueue<String> events=new java.util.concurrent.LinkedBlockingQueue<>();
        String id="service-"+java.util.UUID.randomUUID();
        try(PodServices services=new PodServices(context,events::offer)) {
            assertEquals("granted",service(services,events,1,"notifications.status",new JSONObject()).getString("value"));
            assertEquals("granted",service(services,events,2,"notifications.requestPermission",new JSONObject()).getString("value"));
            service(services,events,3,"notifications.schedule",new JSONObject().put("notification",new JSONObject().put("id",id).put("title","Service test").put("body","Scheduled").put("at",System.currentTimeMillis()+60000)));
            assertEquals(id,service(services,events,4,"notifications.listPending",new JSONObject()).getJSONArray("value").getJSONObject(0).getString("id"));
            service(services,events,5,"notifications.cancel",new JSONObject().put("id",id));
            assertEquals(0,service(services,events,6,"notifications.listPending",new JSONObject()).getJSONArray("value").length());
        }
    }
    private String scheduledRun(android.content.Context context,String id) {
        android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(new java.io.File(context.getNoBackupFilesDir(),"podjs-notifications/inbox.sqlite").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        try(android.database.Cursor row=db.rawQuery("SELECT run FROM scheduled WHERE id=?",new String[]{id})) {assertTrue(row.moveToFirst());return row.getString(0);}finally{db.close();}
    }
    @Test public void scheduledNotificationSurvivesReopenAndStaleRunsCannotPublish() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="scheduled-"+java.util.UUID.randomUUID();
        JSONObject value=new JSONObject().put("id",id).put("title","PodJS delayed test").put("body","延迟通知").put("at",System.currentTimeMillis()+60000);
        try(PodNotifications notifications=new PodNotifications(context)) {
            notifications.schedule(value);String old=scheduledRun(context,id);
            notifications.schedule(value);String replacement=scheduledRun(context,id);
            assertNotEquals(old,replacement);assertTrue(notifications.deliverScheduled(id,old));
            assertEquals(1,notifications.listPending().length());
            notifications.cancel(id);assertTrue(notifications.deliverScheduled(id,replacement));assertEquals(0,notifications.listPending().length());
            value.put("at",System.currentTimeMillis()+1000);notifications.schedule(value);value.put("body","mutated");
        }
        try(PodNotifications reopened=new PodNotifications(context)) {
            assertEquals("延迟通知",reopened.listPending().getJSONObject(0).getString("body"));
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;android.app.Notification found=null;
            while(found==null) {
                for(android.service.notification.StatusBarNotification item:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                    if(item.getTag().equals("podjs:"+id))found=item.getNotification();
                assertTrue("Delayed notification not delivered",android.os.SystemClock.elapsedRealtime()<deadline);
                if(found==null)Thread.sleep(25);
            }
            assertEquals("延迟通知",found.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString());
            assertEquals(0,reopened.listPending().length());reopened.cancel(id);
        }
    }
    @Test public void pendingIntentLaunchCapturesBeforeSubscriberIsReady() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="launch-"+java.util.UUID.randomUUID();
        try(PodNotifications notifications=new PodNotifications(context)) {
            notifications.show(new JSONObject().put("id",id).put("title","PodJS launch test").put("body","启动链路测试").put("payload",73));
            android.app.PendingIntent open=null;
            for(android.service.notification.StatusBarNotification item:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if(item.getTag().equals("podjs:"+id))open=item.getNotification().contentIntent;
            assertNotNull(open);open.send();
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;JSONObject captured=null;
            while(captured==null) {
                org.json.JSONArray pending=notifications.pendingEvents();
                for(int i=0;i<pending.length();i++)if(id.equals(pending.getJSONObject(i).getJSONObject("value").getString("notificationId")))captured=pending.getJSONObject(i).getJSONObject("value");
                assertTrue("Activity did not capture notification",android.os.SystemClock.elapsedRealtime()<deadline);
                if(captured==null)Thread.sleep(25);
            }
            assertEquals(73,captured.getInt("payload"));
            Thread.sleep(1100); // No guest subscriber: frames must not delete the inbox.
            boolean retained=false;
            org.json.JSONArray pending=notifications.pendingEvents();
            for(int i=0;i<pending.length();i++)if(captured.getString("eventId").equals(pending.getJSONObject(i).getJSONObject("value").getString("eventId")))retained=true;
            assertTrue(retained);notifications.acknowledge(captured.getString("eventId"));notifications.cancel(id);
        }
    }
    private String actionToken(android.content.Context context,String id) {
        android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(new java.io.File(context.getNoBackupFilesDir(),"podjs-notifications/inbox.sqlite").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        try(android.database.Cursor row=db.rawQuery("SELECT token FROM action_tokens WHERE notification_id=?",new String[]{id})) {assertTrue(row.moveToFirst());return row.getString(0);} finally {db.close();}
    }
    @Test public void actionIdentityIsPersistedAndOldButtonsAreRevoked() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="actions-"+java.util.UUID.randomUUID();
        JSONObject value=new JSONObject().put("id",id).put("title","PodJS action test").put("body","操作按钮测试")
            .put("actions",new org.json.JSONArray().put(new JSONObject().put("id","approve").put("title","确认")));
        try(PodNotifications notifications=new PodNotifications(context)) {
            notifications.show(value);String old=actionToken(context,id);
            notifications.show(value);String current=actionToken(context,id);assertNotEquals(old,current);
            assertFalse(notifications.captureOpen(new android.content.Intent().putExtra("dev.podjs.notification.token",old)));
            android.app.Notification found=null;
            for(android.service.notification.StatusBarNotification notification:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if (notification.getTag().equals("podjs:"+id)) found=notification.getNotification();
            assertNotNull(found);assertEquals(1,found.actions.length);assertEquals("确认",found.actions[0].title.toString());
            assertTrue(notifications.captureOpen(new android.content.Intent().putExtra("dev.podjs.notification.token",current).putExtra("actionId","forged")));
            JSONObject event=notifications.pendingEvents().getJSONObject(0);
            assertEquals("notification.action",event.getString("t"));assertEquals("approve",event.getJSONObject("value").getString("actionId"));
            assertFalse(notifications.captureOpen(new android.content.Intent().putExtra("dev.podjs.notification.token",current)));
            notifications.acknowledge(event.getJSONObject("value").getString("eventId"));notifications.cancel(id);
        }
    }
    @Test public void realNotificationAndPersistentDeduplicatedOpenInbox() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="notification-"+java.util.UUID.randomUUID();String eventId;
        android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        try (PodNotifications notifications=new PodNotifications(context)) {
            assertTrue("Notification permission unavailable",notifications.permitted());
            notifications.show(new JSONObject().put("id",id).put("title","PodJS test").put("body","通知测试😀").put("payload",new JSONObject().put("n",17)));
            android.app.Notification actual=null;
            for(android.service.notification.StatusBarNotification item:manager.getActiveNotifications()) if(item.getTag().equals("podjs:"+id)) actual=item.getNotification();
            assertNotNull(actual);assertNotNull(actual.contentIntent);
            assertEquals("通知测试😀",actual.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString());
            assertFalse(notifications.captureOpen(new android.content.Intent().putExtra("dev.podjs.notification.token","forged")));
            // Exercise the inbox boundary using the stored token, without
            // pretending this simulates a physical notification tap.
            android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(new java.io.File(context.getNoBackupFilesDir(),"podjs-notifications/inbox.sqlite").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            String token;
            try(android.database.Cursor row=db.rawQuery("SELECT token FROM notifications WHERE id=?",new String[]{id})) {assertTrue(row.moveToFirst());token=row.getString(0);} finally {db.close();}
            android.content.Intent open=new android.content.Intent().putExtra("dev.podjs.notification.token",token).putExtra("payload","forged payload");
            assertTrue(notifications.captureOpen(open));assertFalse(notifications.captureOpen(open));
            JSONObject event=notifications.pendingEvents().getJSONObject(0).getJSONObject("value");
            assertEquals(17,event.getJSONObject("payload").getInt("n"));eventId=event.getString("eventId");
        }
        try(PodNotifications reopened=new PodNotifications(context)) {
            assertEquals(eventId,reopened.pendingEvents().getJSONObject(0).getJSONObject("value").getString("eventId"));
            reopened.acknowledge(eventId);assertEquals(0,reopened.pendingEvents().length());reopened.cancel(id);
        }
    }
}
