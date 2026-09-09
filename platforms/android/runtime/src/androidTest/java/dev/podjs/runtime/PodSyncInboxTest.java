package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodSyncInboxTest {
    @Test public void appliedReceiptSurvivesRestartAndLostAck() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String app = UUID.randomUUID().toString();
        try (PodSyncInbox box = new PodSyncInbox(context,app)) {
            assertEquals(PodSyncInbox.Delivery.PENDING,box.receive("phone","one",new byte[]{1},100,false,1));
            assertEquals(PodSyncInbox.Delivery.PENDING,box.receive("phone","one",new byte[]{1},100,false,1));
            assertEquals(1,box.pending(1,10).size());
            box.markApplied("phone","one"); assertTrue(box.pending(1,10).isEmpty());
        }
        try (PodSyncInbox box = new PodSyncInbox(context,app)) {
            assertEquals(PodSyncInbox.Delivery.APPLIED,box.receive("phone","one",new byte[]{1},100,false,2));
            assertEquals(PodSyncInbox.Delivery.PENDING,box.receive("other","one",new byte[]{1},100,false,2));
            try { box.receive("phone","one",new byte[]{2},100,false,2); fail("Changed content accepted"); }
            catch (IllegalArgumentException expected) { }
            try { box.markApplied("phone","unknown"); fail("Unknown receipt acknowledged"); }
            catch (IllegalArgumentException expected) { }
            assertEquals(PodSyncInbox.Delivery.EXPIRED,box.receive("phone","expired",new byte[]{1},2,false,2));
            assertEquals(2,box.expire(100));
        }
    }
    @Test public void appliedReceiptsCannotBeEvictedToAdmitNewData() throws Exception {
        try (PodSyncInbox box = new PodSyncInbox(InstrumentationRegistry.getInstrumentation().getTargetContext(),UUID.randomUUID().toString())) {
            byte[] payload = new byte[PodSyncOutbox.MAX_PAYLOAD];
            for (int i=0;i<31;i++) { box.receive("phone","m"+i,payload,100,false,1); box.markApplied("phone","m"+i); }
            try { box.receive("phone","overflow",payload,100,true,1); fail("Receipt evicted"); }
            catch (IllegalStateException expected) { }
            assertEquals(PodSyncInbox.Delivery.APPLIED,box.receive("phone","m0",payload,100,false,1));
            assertEquals(31,box.expire(100));
        }
    }
}
