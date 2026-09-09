package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncStatePumpTest {
    private Context endpoint(String id) {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) { @Override public File getNoBackupFilesDir() {
            File root=new File(super.getNoBackupFilesDir(),id);
            if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Test directory unavailable"); return root;
        } };
    }
    @Test public void authenticatedStateReplaysLostAckAfterStoreAndSessionRestart() throws Exception { run(false); }
    @Test public void failedStateWriteClosesSessionWithoutAckAndRetriesAfterReconnect() throws Exception { run(true); }
    private void run(boolean failWrite) throws Exception {
        String app=UUID.randomUUID().toString(); Context pc=endpoint(app+"-phone"), wc=endpoint(app+"-watch"); byte[] key=PodSyncSession.newChallenge();
        for(int cycle=0;cycle<2;cycle++) {
            PodSyncPairingStore pk=new PodSyncPairingStore(pc,app), wk=new PodSyncPairingStore(wc,app);
            if(cycle==0) { pk.importAuthorized("phone","watch",key); wk.importAuthorized("watch","phone",key); }
            try(PodSyncConnections phone=new PodSyncConnections(pk,"phone"); PodSyncConnections watch=new PodSyncConnections(wk,"watch");
                PodSyncStateStore ps=new PodSyncStateStore(pc,app,"phone"); PodSyncStateStore ws=new PodSyncStateStore(wc,app,"watch");
                ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress()); Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()); Socket server=listener.accept()) {
                client.setSoTimeout(10000); server.setSoTimeout(10000);
                if(cycle==0) {
                    JSONArray seed=new JSONArray();
                    for(int n=0;n<600;n++) {
                        seed.put(new JSONObject().put("key","k"+n).put("deviceId","phone").put("counter",n+1).put("deleted",false).put("value","正文😀"+n));
                        if(seed.length()==512) { ps.receive("seed",0,1,seed); seed=new JSONArray(); }
                    }
                    ps.receive("seed",1,2,seed);
                }
                FutureTask<PodSyncConnections.Connection> accepting=new FutureTask<>(() -> watch.open("phone",new PodSyncStream(server),false,new String[]{"state","ack"}));
                new Thread(accepting,"state-accept").start();
                PodSyncConnections.Connection p=phone.open("watch",new PodSyncStream(client),true,new String[]{"state","ack"}), w=accepting.get(10,TimeUnit.SECONDS);
                PodSyncStatePump sender=new PodSyncStatePump(p,ps), receiver=new PodSyncStatePump(w,ws);
                boolean reject=failWrite && cycle==0;
                File database=new File(new File(wc.getNoBackupFilesDir(),"podjs-state"),app+".sqlite");
                try(SQLiteDatabase fault=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                    if(reject) fault.execSQL("CREATE TRIGGER reject_state BEFORE UPDATE ON snapshot BEGIN SELECT RAISE(ABORT,'injected state failure'); END");
                    FutureTask<Boolean> sending=new FutureTask<>(sender::sendNext); new Thread(sending,"state-send").start();
                    try { String result=receiver.receiveOne(); if(reject) fail("Write failure acknowledged"); assertEquals(cycle==0 || failWrite ? "applied" : "duplicate",result); }
                    catch(java.io.IOException error) { if(!reject) throw error; }
                    assertTrue(sending.get(10,TimeUnit.SECONDS));
                    if(reject) { assertFalse(ws.snapshot().getJSONObject("cursors").has("phone")); fault.execSQL("DROP TRIGGER reject_state"); }
                }
                if(cycle==0) { assertNotNull(ps.prepare("watch")); /* Drop ACK and close all stores/sessions. */ }
                else {
                    assertEquals("ack",sender.receiveOne());
                    FutureTask<Boolean> next=new FutureTask<>(sender::sendNext); new Thread(next,"state-next").start();
                    assertEquals("applied",receiver.receiveOne()); assertTrue(next.get(10,TimeUnit.SECONDS)); assertEquals("ack",sender.receiveOne()); assertFalse(sender.sendNext());
                    assertEquals(600,ws.snapshot().getJSONArray("entries").length());
                    assertEquals(ps.snapshot().getJSONArray("entries").toString(),ws.snapshot().getJSONArray("entries").toString());
                    // Exercise the other direction on this same authenticated link.
                    ws.set("watch-local","双向😀");
                    for(int batch=0;batch<2;batch++) {
                        FutureTask<Boolean> reverse=new FutureTask<>(receiver::sendNext); new Thread(reverse,"state-reverse").start();
                        assertEquals("applied",sender.receiveOne()); assertTrue(reverse.get(10,TimeUnit.SECONDS)); assertEquals("ack",receiver.receiveOne());
                    }
                    assertEquals("双向😀",ps.get("watch-local")); assertFalse(receiver.sendNext());
                    phone.revoke("watch"); watch.revoke("phone");
                }
            }
        }
    }
}
