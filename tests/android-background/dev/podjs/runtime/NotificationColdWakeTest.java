package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Two separate invocations; the external harness must prove process absence
 * between scheduling and system delivery, before invoking verify. */
public class NotificationColdWakeTest {
    @Test public void schedule() throws Exception {
        String id=InstrumentationRegistry.getArguments().getString("notificationColdWakeId");
        org.junit.Assume.assumeNotNull(id);
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(PodNotifications notifications=new PodNotifications(context)) {
            notifications.schedule(new JSONObject().put("id",id).put("title","PodJS cold wake")
                .put("body",id).put("at",System.currentTimeMillis()+30000));
            android.util.Log.i("PodJSNotificationColdWake","Scheduled "+id);
        }
    }
    @Test public void verify() throws Exception {
        String id=InstrumentationRegistry.getArguments().getString("notificationColdWakeId");
        org.junit.Assume.assumeNotNull(id);
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(PodNotifications notifications=new PodNotifications(context)) {
            try {
                android.app.Notification found=null;
                for(android.service.notification.StatusBarNotification item:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                    if(("podjs:"+id).equals(item.getTag()))found=item.getNotification();
                assertNotNull("System must publish before this verification process starts",found);
                assertEquals(id,found.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString());
                org.json.JSONArray pending=notifications.listPending();
                for(int i=0;i<pending.length();i++)assertNotEquals(id,pending.getJSONObject(i).getString("id"));
            } finally {notifications.cancel(id);}
        }
    }
}
