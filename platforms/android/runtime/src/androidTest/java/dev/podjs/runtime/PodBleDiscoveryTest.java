package dev.podjs.runtime;

import android.bluetooth.BluetoothAdapter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodBleDiscoveryTest {
    private static class Fake implements PodBleDiscovery.Backend {
        PodBleDiscovery.Events events; int closed;
        final CountDownLatch started=new CountDownLatch(1);
        public void start(PodBleDiscovery.Events events) { this.events=events; started.countDown(); }
        public void close() { closed++; }
    }
    private static FutureTask<List<PodBleDiscovery.Entry>> run(PodBleDiscovery discovery,int duration) {
        FutureTask<List<PodBleDiscovery.Entry>> future=new FutureTask<>(() -> discovery.scan(duration));
        new Thread(future,"ble-discovery-test").start(); return future;
    }
    private static void failed(FutureTask<?> future) throws Exception {
        try { future.get(2,TimeUnit.SECONDS); fail("Unexpected scan success"); }
        catch(java.util.concurrent.ExecutionException error) { assertTrue(error.getCause() instanceof IOException); }
    }
    @Test public void boundedDeduplicatedSnapshotStopsAndIgnoresLateResults() throws Exception {
        Fake fake=new Fake();
        try(PodBleDiscovery discovery=new PodBleDiscovery(fake)) {
            FutureTask<List<PodBleDiscovery.Entry>> pending=run(discovery,250); assertTrue(fake.started.await(1,TimeUnit.SECONDS));
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter(); assertNotNull(adapter);
            for(int n=0;n<40;n++) fake.events.found(adapter.getRemoteDevice(String.format(java.util.Locale.ROOT,"02:50:4F:44:00:%02X",n)),-70);
            fake.events.found(adapter.getRemoteDevice("02:50:4F:44:00:00"),-30);
            List<PodBleDiscovery.Entry> snapshot=pending.get(1,TimeUnit.SECONDS);
            assertEquals(32,snapshot.size()); assertEquals(-30,snapshot.get(0).rssi); assertEquals(1,fake.closed);
            fake.events.found(adapter.getRemoteDevice("02:50:4F:44:00:00"),-90); assertEquals(-30,snapshot.get(0).rssi);
            try { snapshot.clear(); fail("Mutable scan snapshot"); } catch(UnsupportedOperationException expected) { }
            assertTrue(discovery.isClosed());
        }
    }
    @Test public void emptyDeadlineIsNotAnError() throws Exception {
        Fake fake=new Fake();
        try(PodBleDiscovery discovery=new PodBleDiscovery(fake)) {
            assertTrue(discovery.scan(100).isEmpty()); assertTrue(discovery.isClosed()); assertEquals(1,fake.closed);
        }
    }
    @Test public void cancellationAndPlatformFailureDoNotReturnEmptySuccess() throws Exception {
        for(boolean cancel:new boolean[]{false,true}) {
            Fake fake=new Fake();
            try(PodBleDiscovery discovery=new PodBleDiscovery(fake)) {
                FutureTask<?> pending=run(discovery,3000); assertTrue(fake.started.await(1,TimeUnit.SECONDS));
                if(cancel) discovery.close(); else fake.events.failed(2);
                failed(pending); assertEquals(1,fake.closed); assertTrue(discovery.isClosed());
            }
        }
    }
    @Test public void permissionFailureClosesAndInvalidDurationDoesNotStart() throws Exception {
        Fake fake=new Fake() { @Override public void start(PodBleDiscovery.Events events) { throw new SecurityException("denied"); } };
        try(PodBleDiscovery discovery=new PodBleDiscovery(fake)) {
            try { discovery.scan(99); fail("Invalid duration accepted"); } catch(IllegalArgumentException expected) { }
            assertEquals(0,fake.closed);
            failed(run(discovery,100)); assertEquals(1,fake.closed);
        }
    }
}
