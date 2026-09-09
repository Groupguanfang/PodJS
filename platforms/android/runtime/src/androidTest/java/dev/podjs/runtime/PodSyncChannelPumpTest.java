package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncChannelPumpTest {
    private static Context endpoint(String id) {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) { @Override public File getNoBackupFilesDir() {
            File root=new File(super.getNoBackupFilesDir(),id); if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Test directory unavailable"); return root;
        } };
    }
    private static final class Pair implements AutoCloseable {
        final PodSyncConnections phone,watch;
        final PodSyncConnections.Connection pc,wc;
        final PodSyncStateStore ps,ws;
        final PodSyncOutbox po,wo;
        final PodSyncInbox pi,wi;
        final PodSyncIncomingFiles wf;
        final PodSyncFileRequests pf;
        final Context phoneContext;
        final String applicationId;
        final PodSyncChannelPump sender,receiver;
        Pair() throws Exception {
            String app=UUID.randomUUID().toString(); Context p=endpoint(app+"-phone"), w=endpoint(app+"-watch");
            phoneContext=p; applicationId=app;
            PodSyncPairingStore pk=new PodSyncPairingStore(p,app), wk=new PodSyncPairingStore(w,app); byte[] key=PodSyncSession.newChallenge();
            pk.importAuthorized("phone","watch",key); wk.importAuthorized("watch","phone",key);
            phone=new PodSyncConnections(pk,"phone"); watch=new PodSyncConnections(wk,"watch");
            ps=new PodSyncStateStore(p,app,"phone"); ws=new PodSyncStateStore(w,app,"watch");
            po=new PodSyncOutbox(p,app); wo=new PodSyncOutbox(w,app); pi=new PodSyncInbox(p,app); wi=new PodSyncInbox(w,app);
            wf=new PodSyncIncomingFiles(w,app);
            pf=new PodSyncFileRequests(p,app);
            try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()), server=listener.accept();
                client.setSoTimeout(10000); server.setSoTimeout(10000);
                FutureTask<PodSyncConnections.Connection> accepting=new FutureTask<>(() -> watch.open("phone",new PodSyncStream(server),false,new String[]{"state","message","file","ack"}));
                new Thread(accepting,"channel-accept").start();
                pc=phone.open("watch",new PodSyncStream(client),true,new String[]{"state","message","file","ack"}); wc=accepting.get(10,TimeUnit.SECONDS);
            }
            sender=new PodSyncChannelPump(pc,ps,po,pi,null,pf); receiver=new PodSyncChannelPump(wc,ws,wo,wi,wf);
        }
        @Override public void close() throws Exception { try { phone.close(); } finally { watch.close(); ps.close(); ws.close(); po.close(); wo.close(); pi.close(); wi.close(); wf.close(); pf.close(); } }
    }
    @Test public void outgoingStateMachineTransfersFrozenFileAndConsumesRepliesDurably() throws Exception {
        try(Pair pair=new Pair()) {
            byte[] bytes=new byte[65539]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*19);
            File source=File.createTempFile("sender-source-",".bin",pair.phoneContext.getCacheDir()); java.nio.file.Files.write(source.toPath(),bytes);
            PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(pair.phoneContext,pair.applicationId);
            String id=snapshots.create(source,"application/octet-stream").getString("transfer_id"); java.nio.file.Files.delete(source.toPath());
            try(PodSyncOutgoingFiles transfers=new PodSyncOutgoingFiles(pair.pf,snapshots)) {
                assertEquals("offer",transfers.start("watch",id).phase);
                assertNotNull(transfers.advance("watch",false));
                pumpFile(pair);
                assertNull(transfers.advance("watch",false)); assertEquals("waiting",transfers.status("watch",id).phase);
                pair.wf.accept("phone",id);
                int rounds=0;
                while(transfers.advance("watch",true)!=null) { pumpFile(pair); if(++rounds>10) fail("Transfer never completed"); }
                assertEquals("complete",transfers.status("watch",id).phase); assertTrue(pair.pf.completed("watch").isEmpty());
                assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(pair.wf.completedFile("phone",id).toPath()));
                transfers.releaseSnapshot(id); assertTrue(snapshots.inventory().isEmpty());
                transfers.cancel("watch",id); assertNotNull(transfers.advance("watch",false)); pumpFile(pair);
                assertNull(transfers.advance("watch",false)); assertEquals("cancelled",transfers.status("watch",id).phase);
                assertEquals("cancelled",pair.wf.status("phone",id).phase);
            }
        }
    }
    private static void pumpFile(Pair pair) throws Exception {
        FutureTask<String> receive=new FutureTask<>(()->pair.receiver.receiveOne(1,(p,i,b)->fail("Unexpected message")));
        new Thread(receive,"state-machine-file-receive").start(); assertTrue(pair.sender.sendFile()); assertTrue(receive.get(15,TimeUnit.SECONDS).startsWith("file."));
        assertEquals("file.reply",pair.sender.receiveOne(1,(p,i,b)->fail("Unexpected message")));
    }
    @Test public void receiverCancellationTerminatesAlreadyQueuedChunk() throws Exception {
        try(Pair pair=new Pair()) {
            File source=File.createTempFile("cancel-source-",".bin",pair.phoneContext.getCacheDir()); java.nio.file.Files.write(source.toPath(),new byte[]{1,2,3});
            PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(pair.phoneContext,pair.applicationId); String id=snapshots.create(source,"text/plain").getString("transfer_id"); java.nio.file.Files.delete(source.toPath());
            try(PodSyncOutgoingFiles transfers=new PodSyncOutgoingFiles(pair.pf,snapshots)) {
                transfers.start("watch",id); transfers.advance("watch",false); pumpFile(pair);
                pair.wf.accept("phone",id); transfers.advance("watch",true); pumpFile(pair);
                transfers.advance("watch",false); pumpFile(pair);
                PodSyncFileRequests.Request chunk=transfers.advance("watch",false);
                assertEquals("chunk",new org.json.JSONObject(new String(chunk.payload,java.nio.charset.StandardCharsets.UTF_8)).getString("method"));
                pair.wf.cancel("phone",id); pumpFile(pair);
                assertNull(transfers.advance("watch",false)); assertEquals("cancelled",transfers.status("watch",id).phase);
                transfers.releaseSnapshot(id); assertTrue(snapshots.inventory().isEmpty());
            }
        }
    }
    @Test public void durableFileRequestRepliesUseSharedDispatcherAndFirstObservationWins() throws Exception {
        try(Pair pair=new Pair()) {
            org.json.JSONObject manifest=new org.json.JSONObject().put("transfer_id","transfer").put("size",0)
                .put("sha256",digest(new byte[0])).put("chunk_hashes",new org.json.JSONArray()).put("mime","text/plain");
            PodSyncFileRequests.Request pending=pair.pf.enqueue("watch",new org.json.JSONObject().put("version",1).put("method","offer").put("manifest",manifest));
            assertTrue(pair.sender.sendFile()); assertEquals("file.offer",pair.receiver.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            pair.wf.accept("phone","transfer");
            assertTrue(pair.sender.sendFile()); assertEquals("file.offer",pair.receiver.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("file.reply",pair.sender.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("file.duplicate_reply",pair.sender.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("offered",pair.pf.get("watch",pending.messageId).reply.getJSONObject("value").getString("phase"));
            assertFalse(pair.sender.sendFile()); assertTrue(pair.pf.forgetCompleted("watch",pending.messageId));
            PodSyncFileRequests.Request status=pair.pf.enqueue("watch",fileRequest("status"));
            assertTrue(pair.sender.sendFile()); assertEquals("file.status",pair.receiver.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("file.reply",pair.sender.receiveOne(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("accepted",pair.pf.get("watch",status.messageId).reply.getJSONObject("value").getString("phase"));
            pair.wf.cancel("phone","transfer");
        }
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder(); for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return result.toString();
    }
    private static org.json.JSONObject fileRequest(String method) throws Exception {
        return new org.json.JSONObject().put("version",1).put("method",method).put("transfer_id","transfer");
    }
    private static org.json.JSONObject exchange(Pair pair,org.json.JSONObject request) throws Exception {
        byte[] bytes=request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); String id=UUID.randomUUID().toString();
        // Read concurrently: a full chunk's JSON byte envelope can exceed the
        // socket send buffer, so send-then-receive on one thread deadlocks.
        FutureTask<String> receiving=new FutureTask<>(()->pair.receiver.receiveOne(1,(peer,messageId,payload)->fail("File reached message handler")));
        new Thread(receiving,"file-receive").start();
        pair.pc.send("file",id,bytes);
        assertEquals("file."+request.getString("method"),receiving.get(15,TimeUnit.SECONDS));
        org.json.JSONObject received=pair.pc.receive(), frame=received.getJSONObject("frame");
        assertEquals("file",frame.getString("channel")); assertEquals(id,frame.getString("messageId"));
        org.json.JSONArray payload=frame.getJSONArray("payload"); byte[] response=new byte[payload.length()]; for(int i=0;i<response.length;i++) response[i]=(byte)payload.getInt(i);
        org.json.JSONObject reply=new org.json.JSONObject(new String(response,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(4,reply.length()); assertEquals("reply",reply.getString("type")); assertEquals(digest(bytes),reply.getString("request_sha256"));
        assertFalse(reply.toString().contains("path")); pair.pc.commit(frame.getLong("sequence")); return reply.getJSONObject("value");
    }
    @Test public void fileRequestsShareConnectionButRequireLocalConsentAndReturnNoPaths() throws Exception {
        try(Pair pair=new Pair()) {
            byte[] data=new byte[65539]; for(int n=0;n<data.length;n++) data[n]=(byte)(n*37);
            byte[] first=java.util.Arrays.copyOfRange(data,0,65536), last=java.util.Arrays.copyOfRange(data,65536,data.length);
            org.json.JSONObject manifest=new org.json.JSONObject().put("transfer_id","transfer").put("size",data.length)
                .put("sha256",digest(data)).put("chunk_hashes",new org.json.JSONArray().put(digest(first)).put(digest(last))).put("mime","application/octet-stream");
            org.json.JSONObject offer=new org.json.JSONObject().put("version",1).put("method","offer").put("manifest",manifest);
            assertEquals("offered",exchange(pair,offer).getString("phase"));
            assertEquals("offered",exchange(pair,offer).getString("phase")); assertEquals(1,pair.wf.pendingConsent().size());
            pair.ps.set("interleave",true); assertTrue(pair.sender.sendState());
            assertEquals("state.applied",pair.receiver.receiveOne(1,(p,i,b)->fail("State routed to message")));
            assertEquals("state.ack",pair.sender.receiveOne(1,(p,i,b)->fail("ACK routed to message")));
            pair.wf.accept("phone","transfer");
            assertEquals(2,exchange(pair,fileRequest("missing")).getJSONArray("missing").length());
            org.json.JSONObject chunk=fileRequest("chunk").put("index",0).put("data_base64",android.util.Base64.encodeToString(first,android.util.Base64.NO_WRAP));
            exchange(pair,chunk); exchange(pair,chunk);
            assertEquals(1,exchange(pair,fileRequest("missing")).getJSONArray("missing").length());
            exchange(pair,fileRequest("chunk").put("index",1).put("data_base64",android.util.Base64.encodeToString(last,android.util.Base64.NO_WRAP)));
            assertEquals(0,exchange(pair,fileRequest("missing")).getJSONArray("missing").length());
            assertEquals("complete",exchange(pair,fileRequest("finish")).getString("phase"));
            assertEquals("complete",exchange(pair,fileRequest("finish")).getString("phase"));
            assertArrayEquals(data,java.nio.file.Files.readAllBytes(pair.wf.completedFile("phone","transfer").toPath()));
            assertEquals("cancelled",exchange(pair,fileRequest("cancel")).getString("phase"));
            assertEquals("cancelled",exchange(pair,fileRequest("cancel")).getString("phase"));
        }
    }
    @Test public void remoteAcceptAndIdentityInjectionFailClosed() throws Exception {
        for(boolean injection:new boolean[]{false,true}) try(Pair pair=new Pair()) {
            byte[] empty=new byte[0];
            pair.wf.offer("phone",new org.json.JSONObject().put("transfer_id","transfer").put("size",0).put("sha256",digest(empty)).put("chunk_hashes",new org.json.JSONArray()).put("mime","text/plain"));
            org.json.JSONObject request=fileRequest(injection?"status":"accept"); if(injection) request.put("peer","another");
            pair.pc.send("file","bad",request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { pair.receiver.receiveOne(1,(p,i,b)->fail("Unexpected message")); fail("Remote authority accepted"); } catch(java.io.IOException expected) { }
            assertEquals("offered",pair.wf.status("phone","transfer").phase);
            try { pair.wc.send("file","alive",empty); fail("Connection not closed"); } catch(java.io.IOException expected) { }
        }
    }
    @Test public void stateAndMessageAckNamespacesStayIndependentOnOneConnection() throws Exception {
        try(Pair pair=new Pair()) {
            pair.ps.set("title","共享连接😀"); PodSyncStateStore.Batch batch=pair.ps.prepare("watch");
            pair.po.enqueue("watch",batch.messageId,new byte[]{1,2,3},100,false,1);
            assertTrue(pair.sender.sendState());
            assertEquals("state.applied",pair.receiver.receiveOne(1,(peer,id,bytes) -> fail("State reached message handler")));
            assertTrue(pair.sender.sendMessage(1));
            assertEquals("message.applied",pair.receiver.receiveOne(1,(peer,id,bytes) -> { assertEquals(batch.messageId,id); assertArrayEquals(new byte[]{1,2,3},bytes); }));
            assertEquals("state.ack",pair.sender.receiveOne(1,(peer,id,bytes) -> fail("ACK reached message handler")));
            assertNull(pair.ps.prepare("watch")); assertEquals(1,pair.po.pending("watch",1,10).size());
            assertEquals("message.ack",pair.sender.receiveOne(1,(peer,id,bytes) -> fail("ACK reached message handler")));
            assertTrue(pair.po.pending("watch",1,10).isEmpty()); assertEquals("共享连接😀",pair.ws.get("title"));
        }
    }
    @Test public void concurrentReceiversWaitForPreviousDurableApplicationCommit() throws Exception { concurrent(false); }
    @Test public void failedFirstApplicationCannotBeSkippedByAnotherReceiver() throws Exception { concurrent(true); }
    private void concurrent(boolean failFirst) throws Exception {
        try(Pair pair=new Pair()) {
            byte[] envelope=ByteBuffer.allocate(10).putLong(100).put((byte)0).put((byte)7).array();
            pair.pc.send("message","first",envelope); pair.pc.send("message","second",envelope);
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), attempted=new CountDownLatch(1);
            FutureTask<String> first=new FutureTask<>(() -> pair.receiver.receiveOne(1,(peer,id,bytes) -> {
                assertEquals("first",id); entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new java.io.IOException("Test release timeout");
                if(failFirst) throw new java.io.IOException("Injected application failure");
            }));
            new Thread(first,"channel-first").start(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            FutureTask<String> second=new FutureTask<>(() -> { attempted.countDown(); return pair.receiver.receiveOne(1,(peer,id,bytes) -> { assertFalse(failFirst); assertEquals("second",id); }); });
            new Thread(second,"channel-second").start(); assertTrue(attempted.await(5,TimeUnit.SECONDS));
            try { second.get(150,TimeUnit.MILLISECONDS); fail("Later frame passed unfinished application"); }
            catch(TimeoutException expected) { } finally { release.countDown(); }
            if(failFirst) {
                try { first.get(5,TimeUnit.SECONDS); fail("Application failure ignored"); } catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
                try { second.get(5,TimeUnit.SECONDS); fail("Next frame applied after failure"); } catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
                assertEquals(1,pair.wi.pending(1,10).size()); assertEquals("first",pair.wi.pending(1,10).get(0).messageId);
            } else {
                assertEquals("message.applied",first.get(5,TimeUnit.SECONDS)); assertEquals("message.applied",second.get(5,TimeUnit.SECONDS));
                assertTrue(pair.wi.pending(1,10).isEmpty());
            }
        }
    }
    @Test public void unknownAckKindClosesConnectionWithoutTouchingPendingState() throws Exception {
        try(Pair pair=new Pair()) {
            pair.ps.set("key",true); PodSyncStateStore.Batch pending=pair.ps.prepare("watch");
            pair.wc.send("ack",pending.messageId,new byte[]{99});
            try { pair.sender.receiveOne(1,(peer,id,bytes) -> fail("Unknown ACK delivered")); fail("Unknown ACK accepted"); }
            catch(java.io.IOException expected) { }
            assertArrayEquals(pending.payload,pair.ps.prepare("watch").payload);
            try { pair.pc.send("state",pending.messageId,pending.payload); fail("Bad channel connection survived"); } catch(java.io.IOException expected) { }
        }
    }
}
