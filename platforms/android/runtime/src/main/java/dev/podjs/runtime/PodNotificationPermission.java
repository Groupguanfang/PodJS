package dev.podjs.runtime;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Main-thread permission coordinator; one system prompt at a time per View. */
final class PodNotificationPermission {
    static final int REQUEST=0x504e;
    interface Callback {void complete(String state);void fail(String code,String message);}
    private final Context context;
    private Callback pending;
    private boolean inFlight;
    PodNotificationPermission(Context context){this.context=context;}
    String status() {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)
            return context.getSharedPreferences("podjs-notifications",Context.MODE_PRIVATE).getBoolean("permissionAsked",false)?"denied":"notDetermined";
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel("podjs.local.v1");
        return manager.areNotificationsEnabled() && (channel==null || channel.getImportance()!=NotificationManager.IMPORTANCE_NONE)?"granted":"denied";
    }
    void request(Callback callback) {
        if(inFlight){callback.fail("busy","Notification permission request already active");return;}
        if(Build.VERSION.SDK_INT<33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")==PackageManager.PERMISSION_GRANTED){callback.complete(status());return;}
        if(!(context instanceof Activity)){callback.fail("unavailable","Notification permission requires an Activity");return;}
        Activity activity=(Activity)context;
        if(activity.isFinishing() || activity.isDestroyed()){callback.fail("unavailable","Activity is unavailable");return;}
        pending=callback;inFlight=true;
        try {activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},REQUEST);}
        catch(RuntimeException error){pending=null;inFlight=false;callback.fail("unavailable","Cannot request notification permission");}
    }
    void result(int code,int[] grants) {
        if(code!=REQUEST)return;
        // Android can return DENIED even when Back dismisses the notification
        // dialog without a choice. A first actual denial sets rationale; later
        // fixed denials retain our already-persisted decision. Do not turn an
        // untouched permission into denied merely because the prompt closed.
        boolean granted=grants!=null && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED;
        boolean denied=context instanceof Activity && ((Activity)context).shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS");
        if(granted || denied)
            context.getSharedPreferences("podjs-notifications",Context.MODE_PRIVATE).edit().putBoolean("permissionAsked",true).apply();
        inFlight=false;if(pending==null)return;
        Callback callback=pending;pending=null;callback.complete(status());
    }
    void cancel(){pending=null;}
}
