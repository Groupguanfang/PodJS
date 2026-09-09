package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodSyncOutboxTest {
    @Test public void ttlRetrySurvivesAckAndRestartWithoutExtendingExpiryOrRequeueing() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncOutbox box=new PodSyncOutbox(context,app)) {
            assertEquals(110,box.enqueueWithTtl("phone","stable",new byte[]{1},100,false,10));
            assertEquals(110,box.enqueueWithTtl("phone","stable",new byte[]{1},100,false,20));
            assertEquals(110,box.pending("phone",20,1).get(0).expiresAt);
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.ByteBuffer.allocate(10).putLong(110).put((byte)0).put((byte)1).array());
            assertTrue(box.acknowledgeVerified("phone","stable",digest));
        }
        try(PodSyncOutbox box=new PodSyncOutbox(context,app)) {
            assertEquals(110,box.enqueueWithTtl("phone","stable",new byte[]{1},100,false,30));
            assertTrue(box.pending("phone",30,100).isEmpty());
            box.enqueue("phone","stable",new byte[]{1},110,false,30); assertTrue(box.pending("phone",30,100).isEmpty());
            try { box.enqueueWithTtl("phone","stable",new byte[]{2},100,false,30); fail("Changed payload"); } catch(IllegalArgumentException expected) { }
            try { box.enqueueWithTtl("phone","stable",new byte[]{1},101,false,30); fail("Changed TTL"); } catch(IllegalArgumentException expected) { }
            try { box.enqueue("phone","stable",new byte[]{1},111,false,30); fail("Bypassed intent"); } catch(IllegalArgumentException expected) { }
            try { box.enqueueWithTtl("phone","stable",new byte[]{1},100,false,110); fail("Expired intent extended"); } catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void ttlIntentFailureRollsBackFirstQueueRowAndAbsoluteRequestsCannotBeAdopted() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncOutbox box=new PodSyncOutbox(context,app)) {
            java.io.File file=new java.io.File(new java.io.File(context.getNoBackupFilesDir(),"podjs-outbox"),app+".sqlite");
            try(android.database.sqlite.SQLiteDatabase fault=android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_ttl BEFORE INSERT ON ttl_intents BEGIN SELECT RAISE(ABORT,'injected'); END");
                try { box.enqueueWithTtl("phone","new",new byte[]{1},100,false,10); fail("Intent failure ignored"); } catch(android.database.SQLException expected) { }
                assertTrue(box.pending("phone",10,100).isEmpty());
                fault.execSQL("DROP TRIGGER reject_ttl"); assertEquals(110,box.enqueueWithTtl("phone","new",new byte[]{1},100,false,10));
                box.enqueue("phone","absolute",new byte[]{1},110,false,10);
                try { box.enqueueWithTtl("phone","absolute",new byte[]{1},100,false,10); fail("Adopted unknown TTL"); } catch(IllegalArgumentException expected) { }
            }
        }
    }
    @Test public void staleContentAckCannotEraseReusedMessageId() throws Exception {
        try (PodSyncOutbox box = new PodSyncOutbox(InstrumentationRegistry.getInstrumentation().getTargetContext(),UUID.randomUUID().toString())) {
            box.enqueue("watch","one",new byte[]{1},100,false,1);
            byte[] oldDigest = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.ByteBuffer.allocate(10).putLong(100).put((byte)0).put((byte)1).array());
            assertTrue(box.acknowledgeVerified("watch","one",oldDigest));
            box.enqueue("watch","one",new byte[]{2},100,false,1);
            try { box.acknowledgeVerified("watch","one",oldDigest); fail("Stale ACK erased new data"); }
            catch (IllegalArgumentException expected) { }
            assertArrayEquals(new byte[]{2},box.pending("watch",1,1).get(0).payload);
            box.expire(100);
        }
    }
    @Test public void countQuotaIsIndependentOfPayloadBytes() throws Exception {
        try (PodSyncOutbox box = new PodSyncOutbox(InstrumentationRegistry.getInstrumentation().getTargetContext(),UUID.randomUUID().toString())) {
            for (int i=0;i<1000;i++) box.enqueue("watch","small"+i,new byte[]{0},100,false,1);
            try { box.enqueue("watch","extra",new byte[]{0},100,false,1); fail("Count limit exceeded"); }
            catch (IllegalStateException expected) { }
            assertEquals(1000,box.expire(100));
        }
    }
    @Test public void restartKeepsUnackedMessagesAndPeerAckIsScoped() throws Exception {
        String app = UUID.randomUUID().toString();
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (PodSyncOutbox box = new PodSyncOutbox(context,app)) {
            box.enqueue("watch","normal",new byte[]{1},100,false,1);
            box.enqueue("watch","urgent",new byte[]{2},100,true,1);
            box.enqueue("other","normal",new byte[]{3},100,false,1);
            box.enqueue("watch","normal",new byte[]{1},100,false,1);
            assertEquals(2,box.pending("watch",2,10).size());
            assertEquals("urgent",box.pending("watch",2,1).get(0).messageId);
            try { box.enqueue("watch","normal",new byte[]{4},100,false,1); fail("Conflicting identity accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try (PodSyncOutbox box = new PodSyncOutbox(context,app)) {
            assertEquals(2,box.pending("watch",2,10).size());
            assertTrue(box.acknowledge("other","normal"));
            assertEquals(2,box.pending("watch",2,10).size());
            assertTrue(box.acknowledge("watch","normal"));
            assertFalse(box.acknowledge("watch","normal"));
            assertEquals(1,box.pending("watch",2,10).size());
            assertEquals(0,box.pending("watch",100,10).size());
            assertEquals(1,box.expire(100));
        }
    }
    @Test public void byteQuotaRejectsNewMessageWithoutEvictingUnackedData() throws Exception {
        String app = UUID.randomUUID().toString();
        try (PodSyncOutbox box = new PodSyncOutbox(InstrumentationRegistry.getInstrumentation().getTargetContext(),app)) {
            byte[] payload = new byte[PodSyncOutbox.MAX_PAYLOAD];
            for (int i=0;i<31;i++) box.enqueue("watch","m"+i,payload,100,false,1);
            for (boolean high : new boolean[]{false,true}) {
                try { box.enqueue("watch","overflow",payload,100,high,1); fail("Quota exceeded"); }
                catch (IllegalStateException expected) { }
            }
            assertEquals(31,box.pending("watch",1,100).size());
            assertTrue(box.acknowledge("watch","m0"));
            box.enqueue("watch","after-ack",payload,100,false,1);
            assertEquals(31,box.pending("watch",1,100).size());
            assertEquals(31,box.expire(100));
        }
    }
}
