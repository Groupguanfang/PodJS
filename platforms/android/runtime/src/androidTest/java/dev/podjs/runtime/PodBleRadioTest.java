package dev.podjs.runtime;

import android.app.UiAutomation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit opt-in: emits the PodJS service UUID for at most a few seconds.
 * Never enables Bluetooth, pairs, connects to a device or imports a key.
 */
public class PodBleRadioTest {
    private static String nativeState(UiAutomation automation) throws IOException {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys bluetooth_manager"))) {
            byte[] buffer=new byte[4096]; int read;
            while((read=input.read(buffer))!=-1) {
                if(output.size()+read>1024*1024) throw new IOException("Bluetooth dump exceeds test budget"); output.write(buffer,0,read);
            }
        }
        return new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test public void nativeServiceAdvertisesAndAcceptDeadlineClosesIt() throws Exception {
        org.junit.Assume.assumeTrue("Requires explicit podjsBleRadio=true",
            "true".equals(InstrumentationRegistry.getArguments().getString("podjsBleRadio")));
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        if(Build.VERSION.SDK_INT>=31) automation.adoptShellPermissionIdentity("android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_ADVERTISE");
        try {
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            assertNotNull("No Bluetooth manager",manager);
            BluetoothAdapter adapter=manager.getAdapter(); assertNotNull("No adapter",adapter);
            assertTrue("Bluetooth must already be enabled; test never toggles it",adapter.isEnabled());
            assertNotNull("No BLE advertiser",adapter.getBluetoothLeAdvertiser());
            String registered=context.getPackageName()+" (Registered)";
            assertFalse("Test server already registered",nativeState(automation).contains(registered));
            // Locally administered synthetic route. The test never initiates a connection.
            try(PodBleGattServer server=new PodBleGattServer(context,adapter.getRemoteDevice("02:50:4F:44:4A:53"),4000)) {
                FutureTask<Boolean> accepting=new FutureTask<>(() -> {
                    try { server.accept(); return false; } catch(IOException expected) { return true; }
                });
                long start=SystemClock.elapsedRealtime();
                new Thread(accepting,"ble-radio-test-accept").start();
                while(!server.isAdvertising() && !server.isClosed() && SystemClock.elapsedRealtime()-start<3000) SystemClock.sleep(20);
                assertTrue("Native advertising callback not observed",server.isAdvertising());
                String active=nativeState(automation);
                assertTrue("Native server registration must be visible",active.contains(registered));
                assertTrue("Native service must be visible",active.contains("Service "+PodBleGattClient.SERVICE));
                assertTrue(accepting.get(5,TimeUnit.SECONDS));
                assertTrue("Accept ended before deadline",SystemClock.elapsedRealtime()-start>=3500);
                assertTrue(server.isClosed()); assertFalse(server.isAdvertising());
            }
            long cleanupEnd=SystemClock.elapsedRealtime()+1500;
            String after;
            do { after=nativeState(automation); if(!after.contains(registered)) break; SystemClock.sleep(20); }
            while(SystemClock.elapsedRealtime()<cleanupEnd);
            // OWW242 retains recycled handle-map records, including stale started
            // flags. Registration is the meaningful native lifecycle assertion;
            // this test does not claim an empty diagnostic map or over-air silence.
            assertFalse("Native server still registered after close",after.contains(registered));
            assertTrue("Test must leave Bluetooth enabled",adapter.isEnabled());
        } finally { if(Build.VERSION.SDK_INT>=31) automation.dropShellPermissionIdentity(); }
    }
}
