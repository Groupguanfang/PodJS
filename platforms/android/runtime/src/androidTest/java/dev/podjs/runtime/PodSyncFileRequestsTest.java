package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncFileRequestsTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private JSONObject request() throws Exception { return new JSONObject().put("version",1).put("method","status").put("transfer_id","transfer"); }
    private JSONObject reply(PodSyncFileRequests.Request request) throws Exception {
        StringBuilder hash=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(request.payload)) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new JSONObject().put("version",1).put("type","reply").put("request_sha256",hash.toString()).put("value",new JSONObject().put("phase","offered"));
    }
    @Test public void pendingBytesAndCompletedObservationsSurviveReopenAndCannotCrossPeer() throws Exception {
        String app=UUID.randomUUID().toString(); PodSyncFileRequests.Request pending;
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app)) {
            pending=queue.enqueue("watch",request());
            try { queue.enqueue("watch",request()); fail("Overtook pending request"); } catch(java.io.IOException expected) { }
            assertFalse(queue.forgetCompleted("watch",pending.messageId));
        }
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app)) {
            assertEquals(pending.messageId,queue.next("watch").messageId); assertArrayEquals(pending.payload,queue.next("watch").payload);
            assertEquals("stale_reply",queue.receive("other",pending.messageId,reply(pending)));
            assertNotNull(queue.next("watch"));
            JSONObject bad=reply(pending).put("request_sha256",new String(new char[64]).replace('\0','0'));
            try { queue.receive("watch",pending.messageId,bad); fail("Wrong digest accepted"); } catch(java.io.IOException expected) { }
            assertEquals("reply",queue.receive("watch",pending.messageId,reply(pending))); assertNull(queue.next("watch"));
        }
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app)) {
            assertEquals("offered",queue.get("watch",pending.messageId).reply.getJSONObject("value").getString("phase"));
            assertEquals(pending.messageId,queue.completed("watch").get(0).messageId); assertTrue(queue.completed("other").isEmpty());
            assertEquals("duplicate_reply",queue.receive("watch",pending.messageId,reply(pending).put("value",new JSONObject().put("phase","accepted"))));
            assertEquals("offered",queue.get("watch",pending.messageId).reply.getJSONObject("value").getString("phase"));
            assertTrue(queue.forgetCompleted("watch",pending.messageId));
            assertEquals("stale_reply",queue.receive("watch",pending.messageId,reply(pending)));
            assertNotEquals(pending.messageId,queue.enqueue("watch",request()).messageId);
        }
    }
    @Test public void sqliteReplyFailureKeepsPendingAndRetriesAtomically() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app)) {
            PodSyncFileRequests.Request pending=queue.enqueue("watch",request());
            try(SQLiteDatabase fault=SQLiteDatabase.openDatabase(new File(context().getNoBackupFilesDir(),"podjs-file-requests/"+app+".sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_reply BEFORE UPDATE ON requests BEGIN SELECT RAISE(ABORT,'injected'); END");
                try { queue.receive("watch",pending.messageId,reply(pending)); fail("Failed write acknowledged"); } catch(android.database.SQLException expected) { }
                assertNull(queue.get("watch",pending.messageId).reply); assertNotNull(queue.next("watch"));
                fault.execSQL("DROP TRIGGER reject_reply");
                assertEquals("reply",queue.receive("watch",pending.messageId,reply(pending)));
            }
        }
    }
}
