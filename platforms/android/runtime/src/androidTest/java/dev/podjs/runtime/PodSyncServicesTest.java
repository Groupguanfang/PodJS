package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void dispatchUsesApprovedOwnerAndReturnsExactDurableStateAndTombstone() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodSyncClient client=new PodSyncClient(context(),app,"watch")) {
            AtomicReference<String> result=new AtomicReference<>(); AtomicReference<CountDownLatch> waiting=new AtomicReference<>();
            PodServices services=new PodServices(context(),event->{result.set(event);waiting.get().countDown();});
            try {
                JSONObject missing=call(services,result,waiting,1,"sync.state.get",new JSONObject().put("key","note"));
                assertEquals("unsupported",missing.getString("code"));
                services.attachApprovedSync(new PodSyncServices(client,Collections.singleton("companion.sync.state"),null));
                JSONObject set=call(services,result,waiting,2,"sync.state.set",new JSONObject().put("key","note").put("value",JSONObject.NULL));
                assertTrue(set.toString(),set.getBoolean("ok")); JSONObject entry=set.getJSONObject("value");
                assertEquals("watch",entry.getString("deviceId")); assertEquals(1,entry.getLong("counter"));
                JSONObject get=call(services,result,waiting,3,"sync.state.get",new JSONObject().put("key","note")).getJSONObject("value");
                assertTrue(get.getBoolean("exists")); assertTrue(get.getJSONObject("entry").isNull("value"));
                JSONObject deleted=call(services,result,waiting,4,"sync.state.delete",new JSONObject().put("key","note")).getJSONObject("value");
                assertTrue(deleted.getBoolean("deleted")); assertEquals(2,deleted.getLong("counter"));
                JSONObject tombstone=call(services,result,waiting,5,"sync.state.get",new JSONObject().put("key","note")).getJSONObject("value");
                assertFalse(tombstone.getBoolean("exists")); assertTrue(tombstone.getJSONObject("entry").getBoolean("deleted"));
                JSONObject denied=call(services,result,waiting,6,"sync.messages.send",new JSONObject());
                assertEquals("permission_denied",denied.getString("code"));
                JSONObject noLink=call(services,result,waiting,7,"sync.state.synchronize",new JSONObject().put("peerId","phone"));
                assertEquals("unsupported",noLink.getString("code"));
            } finally { services.close(); }
        }
        try(PodSyncClient reopened=new PodSyncClient(context(),app,"watch")) {
            assertTrue(reopened.stateSnapshot().getJSONArray("entries").getJSONObject(0).getBoolean("deleted"));
        }
    }
    @Test public void immutableGrantsAndCancellationPreventUnauthorizedMutationOrConnectionCalls() throws Exception {
        try(PodSyncClient client=new PodSyncClient(context(),UUID.randomUUID().toString(),"watch")) {
            HashSet<String> grants=new HashSet<>(); grants.add("companion.sync.state"); final int[] calls={0};
            PodSyncServices services=new PodSyncServices(client,grants,(method,args,cancel)->{calls[0]++;return null;});
            grants.add("companion.sync.message");
            try { services.execute("sync.messages.send",new JSONObject(),new CancellationSignal()); fail("Mutable grant escaped"); }
            catch(SecurityException expected) { }
            CancellationSignal cancelled=new CancellationSignal(); cancelled.cancel();
            try { services.execute("sync.state.set",new JSONObject().put("key","key").put("value","bad"),cancelled); fail("Cancelled mutation"); }
            catch(OperationCanceledException expected) { }
            assertNull(client.getState("key")); assertEquals(0,calls[0]);
            try { services.execute("sync.state.get",new JSONObject().put("key",42),new CancellationSignal()); fail("Coerced key"); }
            catch(IllegalArgumentException expected) { }
            try { services.execute("sync.state.set",new JSONObject().put("key","missing"),new CancellationSignal()); fail("Missing value"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void messageServiceCanonicalizesJsonAndRetriesStableTtlOffline() throws Exception {
        try(PodSyncClient client=new PodSyncClient(context(),UUID.randomUUID().toString(),"watch")) {
            PodSyncServices services=new PodSyncServices(client,Collections.singleton("companion.sync.message"),null);
            JSONObject message=new JSONObject().put("messageId","stable").put("ttlMs",60000).put("priority","normal")
                .put("payload",new JSONObject().put("b",2).put("a",1));
            JSONObject args=new JSONObject().put("peerId","phone").put("message",message);
            JSONObject result=(JSONObject)services.execute("sync.messages.send",args,new CancellationSignal());
            assertEquals("stable",result.getString("messageId")); assertEquals("queued",result.getString("state"));
            long expiry=client.pendingMessages("phone",System.currentTimeMillis()).get(0).expiresAt;
            message.put("payload",new JSONObject().put("a",1).put("b",2));
            services.execute("sync.messages.send",args,new CancellationSignal());
            assertEquals(1,client.pendingMessages("phone",System.currentTimeMillis()).size());
            assertEquals(expiry,client.pendingMessages("phone",System.currentTimeMillis()).get(0).expiresAt);
            assertEquals("{\"a\":1,\"b\":2}",new String(client.pendingMessages("phone",System.currentTimeMillis()).get(0).payload,java.nio.charset.StandardCharsets.UTF_8));
            message.put("ttlMs",60000.5);
            try { services.execute("sync.messages.send",args,new CancellationSignal()); fail("Fractional TTL"); } catch(IllegalArgumentException expected) { }
        }
    }
    private JSONObject call(PodServices services,AtomicReference<String> result,AtomicReference<CountDownLatch> waiting,int id,String method,JSONObject args) throws Exception {
        CountDownLatch latch=new CountDownLatch(1);waiting.set(latch);
        JSONObject request=new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method",method).put("args",args);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try { services.dispatch(request); } catch(Exception error) { throw new RuntimeException(error); }
        });
        assertTrue("Service timed out",latch.await(5,TimeUnit.SECONDS));return new JSONObject(result.get());
    }
}
