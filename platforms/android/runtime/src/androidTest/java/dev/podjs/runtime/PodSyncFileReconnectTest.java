package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Recreates stores/sessions over authenticated TCP. The explicitly gated
 * fileCrashProbe also SIGKILLs its own instrumentation process at a checkpoint. */
public class PodSyncFileReconnectTest {
    private Context endpoint(String id) {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) { @Override public File getNoBackupFilesDir() {
            File root=new File(super.getNoBackupFilesDir(),id); if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable"); return root;
        } };
    }
    @Test public void lostChunkAndFinishRepliesResumeAcrossThreeFreshSessions() throws Exception { run(false); }
    @Test public void senderReplyWriteFailureClosesSessionAndResumesExactChunk() throws Exception { run(true); }
    @Test public void fileCrashProbe() throws Exception {
        android.os.Bundle args=InstrumentationRegistry.getArguments(); String phase=args.getString("fileCrashPhase"), run=args.getString("fileCrashRun");
        org.junit.Assume.assumeTrue("Explicit crash probe arguments required",("seed".equals(phase) || "resume".equals(phase)) && run!=null);
        assertTrue(run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        run(false,run,phase);
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value=new StringBuilder(); for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return value.toString();
    }
    private static void checkpoint(File path,JSONObject value) throws Exception {
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(path)) { output.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.getFD().sync(); }
    }
    private static String method(PodSyncFileRequests.Request request) throws Exception { return new JSONObject(new String(request.payload,java.nio.charset.StandardCharsets.UTF_8)).getString("method"); }
    private JSONObject deliver(PodSyncFileRequests.Request request,PodSyncConnections.Connection connection,PodSyncFilePump receiver) throws Exception {
        FutureTask<JSONObject> sending=new FutureTask<>(()->connection.send("file",request.messageId,request.payload));
        new Thread(sending,"reconnect-file-send").start();
        assertEquals(method(request),receiver.receiveOne()); return sending.get(15,TimeUnit.SECONDS);
    }
    private void run(boolean failReplyWrite) throws Exception {
        run(failReplyWrite,null,null);
    }
    private void run(boolean failReplyWrite,String suppliedApp,String crashPhase) throws Exception {
        String app=suppliedApp==null?UUID.randomUUID().toString():suppliedApp; Context pc=endpoint(app+"-phone"), wc=endpoint(app+"-watch");
        byte[] key=PodSyncSession.newChallenge(), bytes=new byte[131079]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*43);
        String transferId=null; PodSyncFileRequests.Request lost=null; HashSet<String> sessions=new HashSet<>();
        File checkpointPath=new File(pc.getNoBackupFilesDir(),"file-crash-checkpoint.json"); JSONObject saved=null;
        if("seed".equals(crashPhase)) assertFalse("Use a fresh crash run ID",checkpointPath.exists());
        if("resume".equals(crashPhase)) {
            saved=new JSONObject(new String(Files.readAllBytes(checkpointPath.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(app,saved.getString("run")); assertFalse(saved.optBoolean("recovered",false));
            assertNotEquals(saved.getInt("pid"),android.os.Process.myPid()); transferId=saved.getString("transferId"); sessions.add(saved.getString("sessionId"));
        }
        for(int cycle="resume".equals(crashPhase)?1:0;cycle<3;cycle++) {
            PodSyncPairingStore pk=new PodSyncPairingStore(pc,app), wk=new PodSyncPairingStore(wc,app);
            if(cycle==0) { pk.importAuthorized("phone","watch",key); wk.importAuthorized("watch","phone",key); }
            PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(pc,app);
            try(PodSyncConnections phone=new PodSyncConnections(pk,"phone"); PodSyncConnections watch=new PodSyncConnections(wk,"watch");
                PodSyncFileRequests queue=new PodSyncFileRequests(pc,app); PodSyncIncomingFiles incoming=new PodSyncIncomingFiles(wc,app);
                PodSyncOutgoingFiles transfers=new PodSyncOutgoingFiles(queue,snapshots);
                ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress()); Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()); Socket server=listener.accept()) {
                client.setSoTimeout(10000); server.setSoTimeout(10000);
                FutureTask<PodSyncConnections.Connection> accepting=new FutureTask<>(()->watch.open("phone",new PodSyncStream(server),false,new String[]{"file"}));
                new Thread(accepting,"reconnect-file-accept").start();
                PodSyncConnections.Connection p=phone.open("watch",new PodSyncStream(client),true,new String[]{"file"}), w=accepting.get(10,TimeUnit.SECONDS);
                PodSyncFilePump sender=new PodSyncFilePump(p,null,queue), receiver=new PodSyncFilePump(w,incoming);
                if(cycle==0) {
                    File source=File.createTempFile("reconnect-source-",".bin",pc.getCacheDir()); Files.write(source.toPath(),bytes);
                    transferId=snapshots.create(source,"application/octet-stream").getString("transfer_id"); Files.delete(source.toPath());
                    transfers.start("watch",transferId);
                    JSONObject first=deliver(transfers.advance("watch",false),p,receiver); assertTrue(sessions.add(first.getString("sessionId"))); assertEquals(1,first.getLong("sequence"));
                    assertEquals("reply",sender.receiveOne()); assertNull(transfers.advance("watch",false));
                    incoming.accept("phone",transferId);
                    deliver(transfers.advance("watch",true),p,receiver); assertEquals("reply",sender.receiveOne());
                    deliver(transfers.advance("watch",false),p,receiver); assertEquals("reply",sender.receiveOne());
                    lost=transfers.advance("watch",false); assertEquals("chunk",method(lost));
                    deliver(lost,p,receiver);
                    assertEquals("[1,2]",incoming.missing("phone",transferId).toString());
                    if(failReplyWrite) {
                        File path=new File(pc.getNoBackupFilesDir(),"podjs-file-requests/"+app+".sqlite");
                        try(SQLiteDatabase fault=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                            fault.execSQL("CREATE TRIGGER fail_receipt BEFORE UPDATE ON requests BEGIN SELECT RAISE(ABORT,'injected file receipt'); END");
                            try { sender.receiveOne(); fail("Failed receipt acknowledged"); } catch(java.io.IOException expected) { }
                            assertNull(queue.get("watch",lost.messageId).reply);
                            try { p.send("file",lost.messageId,lost.payload); fail("Failed persistence kept connection alive"); } catch(java.io.IOException expected) { }
                            fault.execSQL("DROP TRIGGER fail_receipt");
                        }
                    }
                    // Either drop the unread reply or close the failed session.
                    assertEquals(lost.messageId,queue.next("watch").messageId);
                    if("seed".equals(crashPhase)) {
                        JSONObject ready=new JSONObject().put("run",app).put("pid",android.os.Process.myPid()).put("transferId",transferId)
                            .put("requestId",lost.messageId).put("requestSha256",hash(lost.payload)).put("sessionId",first.getString("sessionId"));
                        checkpoint(checkpointPath,ready);
                        android.os.Bundle status=new android.os.Bundle(); status.putString("fileCrashReady",app); status.putInt("fileCrashPid",android.os.Process.myPid());
                        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
                        // No close/finally/shutdown hook runs: pending SQL, native
                        // reader lease and both authenticated sockets remain open.
                        android.os.Process.killProcess(android.os.Process.myPid());
                        throw new AssertionError("SIGKILL unexpectedly returned");
                    }
                } else {
                    if("resume".equals(crashPhase) && cycle==1) {
                        lost=queue.next("watch"); assertNotNull(lost); assertEquals(saved.getString("requestId"),lost.messageId);
                        assertEquals(saved.getString("requestSha256"),hash(lost.payload));
                        assertEquals("[1,2]",incoming.missing("phone",transferId).toString());
                    }
                    assertEquals(transferId,transfers.list("watch").get(0).transferId);
                    PodSyncFileRequests.Request replay=transfers.advance("watch",false);
                    assertEquals(lost.messageId,replay.messageId); assertArrayEquals(lost.payload,replay.payload);
                    JSONObject first=deliver(replay,p,receiver); assertTrue(sessions.add(first.getString("sessionId"))); assertEquals(1,first.getLong("sequence"));
                    assertEquals("reply",sender.receiveOne());
                    if(cycle==1) {
                        for(int expectedIndex=1;expectedIndex<3;expectedIndex++) {
                            PodSyncFileRequests.Request chunk=transfers.advance("watch",false); JSONObject command=new JSONObject(new String(chunk.payload,java.nio.charset.StandardCharsets.UTF_8));
                            assertEquals("chunk",command.getString("method")); assertEquals(expectedIndex,command.getInt("index"));
                            deliver(chunk,p,receiver); assertEquals("reply",sender.receiveOne());
                        }
                        lost=transfers.advance("watch",false); assertEquals("finish",method(lost)); deliver(lost,p,receiver);
                        assertEquals("complete",incoming.status("phone",transferId).phase);
                        // Drop finish reply, retaining the same pending RPC for cycle 2.
                        assertNotNull(queue.next("watch"));
                    } else {
                        assertNull(transfers.advance("watch",false)); assertEquals("complete",transfers.status("watch",transferId).phase);
                        assertArrayEquals(bytes,Files.readAllBytes(incoming.completedFile("phone",transferId).toPath()));
                        assertTrue(queue.completed("watch").isEmpty()); transfers.releaseSnapshot(transferId); assertTrue(snapshots.inventory().isEmpty());
                        incoming.cancel("phone",transferId); phone.revoke("watch"); watch.revoke("phone");
                    }
                }
            }
        }
        assertEquals(3,sessions.size());
        if("resume".equals(crashPhase)) checkpoint(checkpointPath,saved.put("recovered",true).put("resumePid",android.os.Process.myPid()).put("sessions",sessions.size()).put("bytes",bytes.length));
    }
}
