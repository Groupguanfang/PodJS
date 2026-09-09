package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncStateStoreTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String app() { return UUID.randomUUID().toString(); }
    @Test public void offlineConflictsTombstonesAndRestartConverge() throws Exception {
        String leftApp=app(), rightApp=app();
        try (PodSyncStateStore left=new PodSyncStateStore(context(),leftApp,"a"); PodSyncStateStore right=new PodSyncStateStore(context(),rightApp,"z")) {
            JSONObject a=left.set("title","手表😀"), z=right.set("title","手机😀");
            assertEquals(1,left.receive("z",0,1,new JSONArray().put(z)));
            assertEquals(1,right.receive("a",0,1,new JSONArray().put(a)));
            assertEquals("手机😀",left.get("title")); assertEquals(left.get("title"),right.get("title"));
            JSONObject deleted=left.delete("title"); right.receive("a",1,2,new JSONArray().put(deleted));
            assertNull(right.get("title")); left.receive("z",1,2,new JSONArray().put(z)); assertNull(left.get("title"));
        }
        try (PodSyncStateStore reopened=new PodSyncStateStore(context(),rightApp,"z")) {
            assertNull(reopened.get("title")); assertTrue(reopened.snapshot().getJSONArray("entries").getJSONObject(0).getBoolean("deleted"));
            assertEquals(2,reopened.snapshot().getJSONObject("cursors").getLong("a"));
        }
    }
    @Test public void storageFailureCannotAdvanceStateOrCursor() throws Exception {
        String app=app();
        try (PodSyncStateStore store=new PodSyncStateStore(context(),app,"watch"); PodSyncStateStore peer=new PodSyncStateStore(context(),app(),"phone")) {
            JSONArray batch=new JSONArray().put(peer.set("key",new JSONObject().put("body","真实事务😀")));
            File database=new File(new File(context().getNoBackupFilesDir(),"podjs-state"),app+".sqlite");
            try (SQLiteDatabase fault=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_write BEFORE UPDATE ON snapshot BEGIN SELECT RAISE(ABORT,'injected IO failure'); END");
                try { store.receive("phone",0,1,batch); fail("Failed commit acknowledged"); } catch (android.database.SQLException expected) { }
                assertNull(store.get("key")); assertFalse(store.snapshot().getJSONObject("cursors").has("phone"));
                fault.execSQL("DROP TRIGGER reject_write");
            }
            assertEquals(1,store.receive("phone",0,1,batch));
            assertEquals(1,store.receive("phone",0,1,batch));
            assertEquals("真实事务😀",((JSONObject)store.get("key")).getString("body"));
        }
    }
    @Test public void sequenceGapsAndConflictingRevisionRollbackWholeBatch() throws Exception {
        try (PodSyncStateStore store=new PodSyncStateStore(context(),app(),"watch"); PodSyncStateStore peer=new PodSyncStateStore(context(),app(),"phone")) {
            JSONObject original=peer.set("x",1); store.receive("phone",0,1,new JSONArray().put(original));
            JSONObject changed=new JSONObject(original.toString()).put("value",2), fresh=peer.set("y",true);
            try { store.receive("phone",1,2,new JSONArray().put(fresh).put(changed)); fail("Conflicting revision accepted"); }
            catch (IllegalArgumentException expected) { }
            assertNull(store.get("y")); assertEquals(1,store.snapshot().getJSONObject("cursors").getLong("phone"));
            try { store.receive("phone",2,3,new JSONArray().put(fresh)); fail("Gap accepted"); } catch (IllegalArgumentException expected) { }
            assertEquals(2,store.receive("phone",1,2,new JSONArray().put(fresh)));
        }
    }
    @Test public void independentHandlesSerializeClockAndDeviceBindingSurvivesReopen() throws Exception {
        String app=app(); ExecutorService workers=Executors.newFixedThreadPool(2);
        try (PodSyncStateStore one=new PodSyncStateStore(context(),app,"watch"); PodSyncStateStore two=new PodSyncStateStore(context(),app,"watch")) {
            Future<?> first=workers.submit(() -> { try { for(int i=0;i<20;i++) one.set("a"+i,i); } catch(Exception error) { throw new RuntimeException(error); } });
            Future<?> second=workers.submit(() -> { try { for(int i=0;i<20;i++) two.set("b"+i,i); } catch(Exception error) { throw new RuntimeException(error); } });
            first.get(); second.get(); assertEquals(40,one.snapshot().getLong("clock")); assertEquals(40,two.snapshot().getJSONArray("entries").length());
            try (PodSyncStateStore wrong=new PodSyncStateStore(context(),app,"other")) { fail("Device rebound"); }
            catch (java.io.IOException expected) { }
        } finally { workers.shutdownNow(); }
    }
    @Test public void jsonNullDetachedValuesBoundsAndCanonicalRevisionEquality() throws Exception {
        try (PodSyncStateStore store=new PodSyncStateStore(context(),app(),"watch")) {
            JSONObject value=new JSONObject().put("b",1).put("a",-0.0); JSONObject entry=store.set("object",value); value.put("b",9);
            assertEquals(1,((JSONObject)store.get("object")).getInt("b"));
            entry.put("value",new JSONObject().put("a",0).put("b",1.0)); store.receive("relay",0,1,new JSONArray().put(entry));
            store.set("null",JSONObject.NULL); assertSame(JSONObject.NULL,store.get("null")); assertNull(store.get("missing"));
            char[] huge=new char[65537]; java.util.Arrays.fill(huge,'x');
            try { store.set("huge",new String(huge)); fail("Oversize accepted"); } catch (IllegalArgumentException expected) { }
            assertNull(store.get("huge"));
        }
    }
}
