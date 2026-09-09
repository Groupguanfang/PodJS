package dev.podjs.runtime;

import android.app.UiAutomation;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit two-radio framing probe, NOT authentication or application delivery. */
public class PodBleWirelessTest {
    @Test public void selectedLinuxCentralExchangesFramedBytes() throws Exception {
        String peer=InstrumentationRegistry.getArguments().getString("podjsBlePeer");
        org.junit.Assume.assumeTrue("Requires explicit podjsBlePeer adapter address",peer!=null);
        assertTrue("Invalid selected host address",BluetoothAdapter.checkBluetoothAddress(peer));
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        if(Build.VERSION.SDK_INT>=31) automation.adoptShellPermissionIdentity("android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_ADVERTISE");
        ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor();
        try {
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            BluetoothManager manager=context.getSystemService(BluetoothManager.class); assertNotNull(manager);
            BluetoothAdapter adapter=manager.getAdapter(); assertNotNull(adapter); assertTrue(adapter.isEnabled());
            try(PodBleGattServer server=new PodBleGattServer(context,adapter.getRemoteDevice(peer),30000)) {
                deadline.schedule(server::close,90,TimeUnit.SECONDS);
                PodBleStream stream=server.accept();
                stream.framed().write(("{\"mtu\":"+stream.negotiatedMtu()+"}").getBytes(StandardCharsets.UTF_8));
                byte[] expected=new byte[4099]; for(int i=0;i<expected.length;i++) expected[i]=(byte)(i*29);
                assertArrayEquals(expected,stream.framed().read());
                byte[] reversed=new byte[expected.length]; for(int i=0;i<expected.length;i++) reversed[i]=expected[expected.length-1-i];
                stream.framed().write(reversed);
                assertArrayEquals("verified".getBytes(StandardCharsets.UTF_8),stream.framed().read());
                stream.framed().write("done".getBytes(StandardCharsets.UTF_8));
                android.util.Log.i("PodBleWireless","verified mtu="+stream.negotiatedMtu()+" bytesEachWay="+expected.length);
            }
        } finally { deadline.shutdownNow(); if(Build.VERSION.SDK_INT>=31) automation.dropShellPermissionIdentity(); }
    }
}
