package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncStateReceiptTest {
    @Test public void currentStateEvidenceRequiresFinalAckAndSurvivesReopenWithoutPreparingWork() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncStateStore sender=new PodSyncStateStore(context,app,"watch")) {
            assertFalse(sender.currentStateAcknowledged("phone"));
            File database=new File(new File(context.getNoBackupFilesDir(),"podjs-state"),app+".sqlite");
            try(SQLiteDatabase inspect=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(0,android.database.DatabaseUtils.longForQuery(inspect,"SELECT COUNT(*) FROM outgoing",null));
            }
            sender.set("key","one"); PodSyncStateStore.Batch first=sender.prepare("phone");
            assertFalse(sender.currentStateAcknowledged("phone"));
            assertFalse(sender.acknowledge("phone",first.messageId,first.to,new byte[32]));
            assertFalse(sender.currentStateAcknowledged("phone"));
            sender.set("key","two"); assertTrue(sender.acknowledge("phone",first.messageId,first.to,first.digest()));
            assertFalse(sender.currentStateAcknowledged("phone"));
            PodSyncStateStore.Batch second=sender.prepare("phone");
            assertTrue(sender.acknowledge("phone",second.messageId,second.to,second.digest()));
            assertTrue(sender.currentStateAcknowledged("phone")); assertFalse(sender.currentStateAcknowledged("other"));
        }
        try(PodSyncStateStore reopened=new PodSyncStateStore(context,app,"watch")) {
            assertTrue(reopened.currentStateAcknowledged("phone")); reopened.delete("key");
            assertFalse(reopened.currentStateAcknowledged("phone"));
        }
    }
    @Test public void durableDuplicateReceiptRejectsChangedBytesIdAndInvalidUtf8() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        PodSyncStateStore.Batch batch;
        try(PodSyncStateStore sender=new PodSyncStateStore(context,UUID.randomUUID().toString(),"phone"); PodSyncStateStore receiver=new PodSyncStateStore(context,app,"watch")) {
            sender.set("key","value"); batch=sender.prepare("watch");
            assertFalse(receiver.receiveBatch("phone",batch.messageId,batch.payload).duplicate);
        }
        try(PodSyncStateStore receiver=new PodSyncStateStore(context,app,"watch")) {
            assertTrue(receiver.receiveBatch("phone",batch.messageId,batch.payload).duplicate);
            try { receiver.receiveBatch("phone","changed-id",batch.payload); fail("Changed ID accepted"); } catch(IllegalArgumentException expected) { }
            JSONObject changed=new JSONObject(new String(batch.payload,StandardCharsets.UTF_8)); changed.getJSONArray("entries").getJSONObject(0).put("value","changed");
            try { receiver.receiveBatch("phone",batch.messageId,changed.toString().getBytes(StandardCharsets.UTF_8)); fail("Changed duplicate accepted"); } catch(IllegalArgumentException expected) { }
            try { receiver.receiveBatch("phone",batch.messageId,new byte[]{(byte)0xc3,0x28}); fail("Invalid UTF-8 accepted"); } catch(java.nio.charset.CharacterCodingException expected) { }
            assertEquals("value",receiver.get("key")); assertEquals(1,receiver.snapshot().getJSONObject("cursors").getLong("phone"));
        }
    }
    @Test public void receiptInsertFailureRollsBackAlreadyWrittenStateAndCursor() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncStateStore sender=new PodSyncStateStore(context,UUID.randomUUID().toString(),"phone"); PodSyncStateStore receiver=new PodSyncStateStore(context,app,"watch")) {
            sender.set("key",true); PodSyncStateStore.Batch batch=sender.prepare("watch");
            File database=new File(new File(context.getNoBackupFilesDir(),"podjs-state"),app+".sqlite");
            try(SQLiteDatabase fault=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON incoming_batches BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END");
                try { receiver.receiveBatch("phone",batch.messageId,batch.payload); fail("Unpersisted receipt accepted"); } catch(android.database.SQLException expected) { }
                assertNull(receiver.get("key")); assertFalse(receiver.snapshot().getJSONObject("cursors").has("phone"));
                fault.execSQL("DROP TRIGGER reject_receipt"); assertFalse(receiver.receiveBatch("phone",batch.messageId,batch.payload).duplicate);
            }
        }
    }
}
