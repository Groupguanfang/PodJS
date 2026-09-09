package dev.podjs.companion.example;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit English API 31+ phone test, starting with Nearby permissions denied.
 * Uses real PermissionController UI, never pm grant/adoptShellPermissionIdentity.
 */
public class MainActivityBlePermissionTest {
    private static final String[] PERMISSIONS={Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE};
    private interface Condition { boolean ready() throws Exception; }
    private interface Check { boolean ready(MainActivity activity); }
    private static void until(Condition condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.ready()) { if(System.nanoTime()>end) fail("Permission UI condition timed out"); Thread.sleep(40); }
    }
    private static void await(ActivityScenario<MainActivity> scenario,Check check) throws Exception {
        until(()->{ AtomicBoolean result=new AtomicBoolean(); scenario.onActivity(a->result.set(check.ready(a))); return result.get(); });
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label) {
        if(node==null) return null;
        if(label.contentEquals(node.getText()==null?"":node.getText())) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo match=find(node.getChild(i),label); if(match!=null) return match; }
        return null;
    }
    private static boolean clickPermission(String label) {
        AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if(root==null || root.getPackageName()==null || !root.getPackageName().toString().contains("permissioncontroller")) return false;
        AccessibilityNodeInfo button=find(root,label);
        return button!=null && button.isClickable() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private static String status(MainActivity activity) { return ((TextView)activity.findViewById(R.id.ble_status)).getText().toString(); }
    @Test public void denyThenAllowRealNearbyDialogDoesNotStartRadioWork() throws Exception {
        assertTrue("Run only on an API 31+ phone",Build.VERSION.SDK_INT>=31);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String permission:PERMISSIONS) assertEquals("Start with Nearby permissions denied",PackageManager.PERMISSION_DENIED,context.checkSelfPermission(permission));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.ble_permissions).isEnabled());
            scenario.onActivity(a->{
                a.ble.sources=()->{ throw new AssertionError("Permission approval auto-started scan"); };
                assertFalse(a.findViewById(R.id.disconnect).isEnabled());
                a.findViewById(R.id.ble_permissions).performClick();
            });
            until(()->clickPermission("Don’t allow") || clickPermission("Don't allow"));
            await(scenario,a->status(a).contains("权限未全部允许") && a.findViewById(R.id.ble_permissions).isEnabled());
            for(String permission:PERMISSIONS) assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(permission));
            scenario.onActivity(a->a.findViewById(R.id.ble_permissions).performClick());
            until(()->clickPermission("Allow"));
            await(scenario,a->status(a).contains("权限已具备") && a.findViewById(R.id.ble_scan).isEnabled());
            for(String permission:PERMISSIONS) assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(permission));
            scenario.onActivity(a->{
                assertFalse(a.findViewById(R.id.disconnect).isEnabled());
                assertTrue(status(a).contains("重新选择操作"));
                assertEquals(0,((android.widget.LinearLayout)a.findViewById(R.id.ble_results)).getChildCount());
            });
        }
    }
}
