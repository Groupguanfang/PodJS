package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** API 33+ actual system dialogs. Run with a dedicated test application; revoke
 * POST_NOTIFICATIONS and clear user-set/user-fixed flags between invocations. */
public class NotificationPermissionTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private static final class Reply implements PodNotificationPermission.Callback {
        final java.util.concurrent.LinkedBlockingQueue<String> events=new java.util.concurrent.LinkedBlockingQueue<>();
        public void complete(String state){events.offer(state);}
        public void fail(String code,String message){events.offer("error:"+code);}
        String take() throws Exception {String result=events.poll(10,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(result);return result;}
    }
    private android.view.accessibility.AccessibilityNodeInfo button(android.view.accessibility.AccessibilityNodeInfo node,String suffix) {
        if(node==null)return null;
        String id=node.getViewIdResourceName();
        if(id!=null && id.endsWith("/"+suffix))return node;
        for(int i=0;i<node.getChildCount();i++) {
            android.view.accessibility.AccessibilityNodeInfo found=button(node.getChild(i),suffix);
            if(found!=null)return found;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo systemButton(String name) throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        while(true) {
            android.view.accessibility.AccessibilityNodeInfo node=button(instrumentation.getUiAutomation().getRootInActiveWindow(),name);
            if(node!=null) {
                assertTrue("Expected system permission controller",String.valueOf(node.getPackageName()).endsWith("permissioncontroller"));
                return node;
            }
            if(android.os.SystemClock.elapsedRealtime()>=deadline)fail("Missing system permission button "+name+": "+tree(instrumentation.getUiAutomation().getRootInActiveWindow()));
            Thread.sleep(25);
        }
    }
    private String tree(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null)return "null";
        StringBuilder result=new StringBuilder().append(node.getViewIdResourceName()).append('=').append(node.getText()).append(';');
        for(int i=0;i<node.getChildCount();i++)result.append(tree(node.getChild(i)));
        return result.toString();
    }
    private void clickSystemButton(String name) throws Exception {
        assertTrue(systemButton(name).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    }
    private void awaitActivityFocus(NotificationPermissionTestActivity activity) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        while(!activity.hasWindowFocus()) {
            assertTrue("Activity did not regain focus",android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(25);
        }
        instrumentation.waitForIdleSync();
    }
    private NotificationPermissionTestActivity launch() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=33);
        android.content.Context context=instrumentation.getTargetContext();
        org.junit.Assume.assumeTrue("dev.podjs.permissiontest".equals(context.getPackageName()));
        return (NotificationPermissionTestActivity)instrumentation.startActivitySync(new android.content.Intent(context,NotificationPermissionTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Test public void realAllowAfterCallerCancellationDoesNotDeliverCancelledCallback() throws Exception {
        NotificationPermissionTestActivity activity=launch();
        try {
            assertEquals("notDetermined",activity.permission.status());
            Reply first=new Reply(),second=new Reply(),third=new Reply();
            instrumentation.runOnMainSync(()->{
                activity.permission.request(first);
                activity.permission.request(second);
                activity.permission.cancel();
                activity.permission.request(third);
            });
            assertEquals("error:busy",second.take());assertEquals("error:busy",third.take());
            clickSystemButton("permission_allow_button");
            long deadline=android.os.SystemClock.elapsedRealtime()+10000;
            while(activity.permissionResults==0){assertTrue(android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(25);}
            assertTrue(first.events.isEmpty());assertEquals("granted",activity.permission.status());
            Reply already=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(already));
            assertEquals("granted",already.take());
        } finally {instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void realDenialCompletesWithDenied() throws Exception {
        NotificationPermissionTestActivity activity=launch();
        try {
            Reply reply=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(reply));
            clickSystemButton("permission_deny_button");
            assertEquals("denied",reply.take());assertEquals("denied",activity.permission.status());
            awaitActivityFocus(activity);
            Reply dismissed=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(dismissed));
            systemButton("permission_allow_button");
            assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            assertEquals("denied",dismissed.take());
            awaitActivityFocus(activity);
            Reply fixed=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(fixed));
            clickSystemButton("permission_deny_and_dont_ask_again_button");assertEquals("denied",fixed.take());awaitActivityFocus(activity);
            Reply again=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(again));
            assertEquals("denied",again.take());
        } finally {instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void dismissWithoutChoiceKeepsNotDetermined() throws Exception {
        NotificationPermissionTestActivity activity=launch();
        try {
            assertEquals("notDetermined",activity.permission.status());
            Reply reply=new Reply();instrumentation.runOnMainSync(()->activity.permission.request(reply));
            systemButton("permission_allow_button");
            assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            assertEquals("notDetermined",reply.take());assertEquals("notDetermined",activity.permission.status());
        } finally {instrumentation.runOnMainSync(activity::finish);}
    }
}
