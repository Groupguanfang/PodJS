package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncStateSenderTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String app() { return UUID.randomUUID().toString(); }
    private JSONArray entries(PodSyncStateStore.Batch batch) throws Exception { return new JSONObject(new String(batch.payload,StandardCharsets.UTF_8)).getJSONArray("entries"); }
    @Test public void frozenBatchSurvivesRestartAndNewChangesWaitForExactAck() throws Exception {
        String app=app(); PodSyncStateStore.Batch first;
        try (PodSyncStateStore store=new PodSyncStateStore(context(),app,"watch")) {
            store.set("value","old"); first=store.prepare("phone"); store.set("value","new");
        }
        try (PodSyncStateStore store=new PodSyncStateStore(context(),app,"watch")) {
            PodSyncStateStore.Batch replay=store.prepare("phone");
            assertEquals(first.messageId,replay.messageId); assertArrayEquals(first.payload,replay.payload);
            assertFalse(store.acknowledge("other",first.messageId,first.to,first.digest()));
            assertFalse(store.acknowledge("phone",first.messageId,first.to+1,first.digest()));
            assertFalse(store.acknowledge("phone",first.messageId,first.to,new byte[32]));
            assertArrayEquals(first.payload,store.prepare("phone").payload);
            assertTrue(store.acknowledge("phone",first.messageId,first.to,first.digest()));
            PodSyncStateStore.Batch next=store.prepare("phone"); assertEquals(first.to,next.from); assertNotEquals(first.messageId,next.messageId);
            assertEquals("new",entries(next).getJSONObject(0).getString("value"));
            assertFalse(store.acknowledge("phone",first.messageId,first.to,first.digest()));
            assertTrue(store.acknowledge("phone",next.messageId,next.to,next.digest())); assertNull(store.prepare("phone"));
        }
    }
    @Test public void largeStateSplitsAt512AndLostAckNeverSkipsRemainder() throws Exception {
        String senderApp=app(),receiverApp=app(); PodSyncStateStore.Batch first;
        try (PodSyncStateStore sender=new PodSyncStateStore(context(),senderApp,"watch"); PodSyncStateStore receiver=new PodSyncStateStore(context(),receiverApp,"phone")) {
            JSONArray batch=new JSONArray();
            for(int n=0;n<600;n++) {
                batch.put(new JSONObject().put("key","k"+n).put("deviceId","source").put("counter",n+1).put("deleted",false).put("value","正文😀"+n));
                if(batch.length()==512) { sender.receive("source",0,1,batch); batch=new JSONArray(); }
            }
            sender.receive("source",1,2,batch); first=sender.prepare("phone"); assertEquals(512,entries(first).length());
            receiver.receive("watch",first.from,first.to,entries(first)); // Lose the network ACK here.
        }
        try (PodSyncStateStore sender=new PodSyncStateStore(context(),senderApp,"watch"); PodSyncStateStore receiver=new PodSyncStateStore(context(),receiverApp,"phone")) {
            assertArrayEquals(first.payload,sender.prepare("phone").payload);
            assertEquals(first.to,receiver.receive("watch",first.from,first.to,entries(first)));
            assertTrue(sender.acknowledge("phone",first.messageId,first.to,first.digest()));
            PodSyncStateStore.Batch last=sender.prepare("phone"); assertEquals(88,entries(last).length());
            receiver.receive("watch",last.from,last.to,entries(last)); assertTrue(sender.acknowledge("phone",last.messageId,last.to,last.digest()));
            assertNull(sender.prepare("phone")); assertEquals(600,receiver.snapshot().getJSONArray("entries").length());
            assertEquals(sender.snapshot().getJSONArray("entries").toString(),receiver.snapshot().getJSONArray("entries").toString());
        }
    }
    @Test public void byteLimitAppliesBeforeWireEncodingAndEmptyStateAcknowledgesOnce() throws Exception {
        try (PodSyncStateStore sender=new PodSyncStateStore(context(),app(),"watch")) {
            try { sender.set("bad","\ud800"); fail("Unicode replaced silently"); } catch(IllegalArgumentException expected) { }
            try { sender.set("bad",new JSONObject().put("\udc00",1)); fail("Unicode key replaced silently"); } catch(IllegalArgumentException expected) { }
            PodSyncStateStore.Batch empty=sender.prepare("phone"); assertEquals(0,entries(empty).length());
            assertTrue(sender.acknowledge("phone",empty.messageId,empty.to,empty.digest())); assertNull(sender.prepare("phone"));
            char[] chars=new char[60000]; Arrays.fill(chars,'中'); sender.set("a",new String(chars)); sender.set("b",new String(chars));
            PodSyncStateStore.Batch first=sender.prepare("phone"); assertEquals(1,entries(first).length()); assertTrue(first.payload.length<=PodSyncStateStore.MAX_BATCH_BYTES);
            assertTrue(sender.acknowledge("phone",first.messageId,first.to,first.digest()));
            PodSyncStateStore.Batch second=sender.prepare("phone"); assertEquals(1,entries(second).length()); assertEquals("b",entries(second).getJSONObject(0).getString("key"));
        }
    }
    @Test public void failedBatchAndAckWritesLeaveRetryablePersistentProgress() throws Exception {
        String app=app();
        try (PodSyncStateStore sender=new PodSyncStateStore(context(),app,"watch")) {
            sender.set("key",true); File database=new File(new File(context().getNoBackupFilesDir(),"podjs-state"),app+".sqlite");
            try (SQLiteDatabase fault=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_sender BEFORE UPDATE ON outgoing BEGIN SELECT RAISE(ABORT,'injected failure'); END");
                try { sender.prepare("phone"); fail("Unpersisted batch returned"); } catch(android.database.SQLException expected) { }
                fault.execSQL("DROP TRIGGER reject_sender"); PodSyncStateStore.Batch batch=sender.prepare("phone"); assertEquals(0,batch.from);
                fault.execSQL("CREATE TRIGGER reject_ack BEFORE UPDATE ON outgoing BEGIN SELECT RAISE(ABORT,'injected failure'); END");
                try { sender.acknowledge("phone",batch.messageId,batch.to,batch.digest()); fail("Unpersisted ACK returned"); } catch(android.database.SQLException expected) { }
                assertArrayEquals(batch.payload,sender.prepare("phone").payload);
                fault.execSQL("DROP TRIGGER reject_ack"); assertTrue(sender.acknowledge("phone",batch.messageId,batch.to,batch.digest())); assertNull(sender.prepare("phone"));
            }
        }
    }
}
