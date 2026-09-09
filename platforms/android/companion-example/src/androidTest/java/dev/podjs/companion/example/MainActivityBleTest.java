package dev.podjs.companion.example;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class MainActivityBleTest {
    private interface Check { boolean test(MainActivity a); }
    private static void await(ActivityScenario<MainActivity> scenario,Check check) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) { AtomicBoolean done=new AtomicBoolean(); scenario.onActivity(a->done.set(check.test(a))); if(done.get()) return; Thread.sleep(20); }
        fail("BLE UI did not reach expected state");
    }
    private static String text(MainActivity a,int id) { return ((TextView)a.findViewById(id)).getText().toString(); }
    @Test public void permissionGateAndResultNeverAutomaticallyScanOrConnect() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.ble_scan).isEnabled());
            scenario.onActivity(a->{
                a.ble.sources=()->{ throw new AssertionError("Permission flow started scanning"); };
                a.ble.missingPermissions=()->new String[]{"missing"};
                a.findViewById(R.id.ble_scan).performClick(); assertTrue(text(a,R.id.ble_status).startsWith("先点击"));
                a.ble.missingPermissions=()->new String[0]; a.ble.permissionResult();
                assertTrue(text(a,R.id.ble_status).contains("重新选择操作")); assertFalse(a.findViewById(R.id.disconnect).isEnabled());
                ((EditText)a.findViewById(R.id.ble_address)).setText("bad"); a.findViewById(R.id.ble_connect).performClick();
                assertTrue(text(a,R.id.ble_status).contains("完整蓝牙地址")); assertFalse(a.findViewById(R.id.disconnect).isEnabled());
            });
        }
    }
    @Test public void choosingScanResultOnlyFillsRadioAddressAndBackgroundClearsIt() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.ble_scan).isEnabled());
            scenario.onActivity(a->{
                a.ble.missingPermissions=()->new String[0];
                a.ble.sources=()->new BlePanel.ScanSource() {
                    public List<BlePanel.Row> scan() { return Collections.singletonList(new BlePanel.Row("02:50:4F:44:00:01",-42)); }
                    public void close() { }
                };
                ((EditText)a.findViewById(R.id.peer)).setText("application-peer"); a.findViewById(R.id.ble_scan).performClick();
            });
            await(scenario,a->((LinearLayout)a.findViewById(R.id.ble_results)).getChildCount()==1);
            scenario.onActivity(a->{
                ((LinearLayout)a.findViewById(R.id.ble_results)).getChildAt(0).performClick();
                assertEquals("02:50:4F:44:00:01",text(a,R.id.ble_address)); assertEquals("application-peer",text(a,R.id.peer));
                assertFalse(a.findViewById(R.id.disconnect).isEnabled()); assertTrue(text(a,R.id.ble_status).contains("尚未连接"));
            });
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED);
            await(scenario,a->a.findViewById(R.id.ble_scan).isEnabled());
            scenario.onActivity(a->{ assertEquals("",text(a,R.id.ble_address)); assertEquals(0,((LinearLayout)a.findViewById(R.id.ble_results)).getChildCount()); });
        }
    }
    @Test public void backgroundCancelsScanAndLateResultCannotRepopulateUi() throws Exception {
        CountDownLatch started=new CountDownLatch(1),closed=new CountDownLatch(1),returned=new CountDownLatch(1);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.ble_scan).isEnabled());
            scenario.onActivity(a->{
                a.ble.missingPermissions=()->new String[0]; a.ble.sources=()->new BlePanel.ScanSource() {
                    public List<BlePanel.Row> scan() throws Exception {
                        started.countDown(); if(!closed.await(3,TimeUnit.SECONDS)) throw new java.io.IOException("not cancelled");
                        returned.countDown(); return Collections.singletonList(new BlePanel.Row("02:50:4F:44:00:02",-30));
                    }
                    public void close() { closed.countDown(); }
                }; a.findViewById(R.id.ble_scan).performClick();
            });
            assertTrue(started.await(1,TimeUnit.SECONDS)); scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(closed.await(1,TimeUnit.SECONDS)); assertTrue(returned.await(1,TimeUnit.SECONDS));
            scenario.moveToState(Lifecycle.State.RESUMED); await(scenario,a->a.findViewById(R.id.ble_scan).isEnabled());
            scenario.onActivity(a->{ assertEquals(0,((LinearLayout)a.findViewById(R.id.ble_results)).getChildCount()); assertEquals("",text(a,R.id.ble_address)); });
        }
    }
}
