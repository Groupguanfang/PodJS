package dev.podjs.runtime;

/** Debug-only attached Activity for testing the production permission coordinator
 * independently from the watch renderer and its graphics requirements. */
public final class NotificationPermissionTestActivity extends android.app.Activity {
    PodNotificationPermission permission;
    volatile int permissionResults;
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        permission=new PodNotificationPermission(this);
        android.widget.TextView text=new android.widget.TextView(this);
        text.setText("PodJS notification permission test");setContentView(text);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        permission.result(code,results);permissionResults++;
    }
}
