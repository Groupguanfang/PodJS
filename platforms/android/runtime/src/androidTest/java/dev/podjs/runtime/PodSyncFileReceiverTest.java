package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodSyncFileReceiverTest {
    private String offer(String id, long size) throws Exception {
        String hash=new String(new char[64]).replace('\0','0'); org.json.JSONArray chunks=new org.json.JSONArray();
        for(long index=0;index<(size+65535)/65536;index++) chunks.put(hash);
        return new JSONObject().put("method","offer").put("manifest",new JSONObject().put("transfer_id",id).put("size",size)
            .put("sha256",hash).put("chunk_hashes",chunks).put("mime","application/octet-stream")).toString();
    }
    @Test public void applicationQuotaIsSharedAcrossPeersAndReleasedByCancel() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncFileReceiver first=new PodSyncFileReceiver(context,app,"first"); PodSyncFileReceiver second=new PodSyncFileReceiver(context,app,"second")) {
            assertTrue(new JSONObject(first.command(offer("large",16L*1024*1024))).getBoolean("ok"));
            assertTrue(new JSONObject(first.command(offer("large",16L*1024*1024))).getBoolean("ok"));
            try { second.command(offer("tiny",1)); fail("Per-peer quota bypass accepted"); } catch(IOException expected) { }
            assertTrue(new JSONObject(first.command("{\"method\":\"cancel\",\"transfer_id\":\"large\"}")).getBoolean("ok"));
            assertTrue(new JSONObject(second.command(offer("large",16L*1024*1024))).getBoolean("ok"));
            assertTrue(new JSONObject(second.command("{\"method\":\"cancel\",\"transfer_id\":\"large\"}")).getBoolean("ok"));
        }
    }
    @Test public void corruptCompleteMarkerCannotLowerReservationForAnotherPeer() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncFileReceiver first=new PodSyncFileReceiver(context,app,"first"); PodSyncFileReceiver second=new PodSyncFileReceiver(context,app,"second")) {
            assertTrue(new JSONObject(first.command(offer("large",16L*1024*1024))).getBoolean("ok"));
            java.io.File complete=new java.io.File(context.getNoBackupFilesDir(),"podjs-sync/"+app+"/first/large/complete");
            try(java.io.RandomAccessFile wrong=new java.io.RandomAccessFile(complete,"rw")) { wrong.setLength(16L*1024*1024); }
            try { second.command(offer("tiny",1)); fail("Unverified complete file lowered quota"); } catch(IOException expected) { }
            assertTrue(new JSONObject(first.command("{\"method\":\"cancel\",\"transfer_id\":\"large\"}")).getBoolean("ok"));
        }
    }
    @Test public void busyApplicationLockRejectsBeforeIoAndFailedOpenReleasesPeerLock() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncFileReceiver first=new PodSyncFileReceiver(context,app,"first")) {
            java.io.File global=new java.io.File(context.getNoBackupFilesDir(),"podjs-sync/"+app+"/@quota.lock");
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(global,"rw"); java.nio.channels.FileLock lock=file.getChannel().lock()) {
                try { first.command(offer("test",0)); fail("Concurrent application writer admitted"); } catch(IOException expected) { }
                try(PodSyncFileReceiver second=new PodSyncFileReceiver(context,app,"second")) { fail("Open bypassed application lock"); } catch(IOException expected) { }
            }
            try(PodSyncFileReceiver second=new PodSyncFileReceiver(context,app,"second")) {
                assertTrue(new JSONObject(second.command(offer("test",0))).getBoolean("ok"));
                assertTrue(new JSONObject(second.command("{\"method\":\"cancel\",\"transfer_id\":\"test\"}")).getBoolean("ok"));
            }
        }
    }
    @Test public void chunkSurvivesReopenAndPublishesExactBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String peer = UUID.randomUUID().toString();
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        try (PodSyncFileReceiver receiver = new PodSyncFileReceiver(context, "test", peer)) {
            String offer = "{\"method\":\"offer\",\"manifest\":{\"transfer_id\":\"data\",\"size\":3,\"sha256\":\""
                + hash + "\",\"chunk_hashes\":[\"" + hash + "\"],\"mime\":\"text/plain\"}}";
            assertTrue(new JSONObject(receiver.command(offer)).getBoolean("ok"));
            assertFalse(new JSONObject(receiver.command("{\"method\":\"chunk\",\"transfer_id\":\"data\",\"index\":0,\"data_base64\":\"YmFk\"}")).getBoolean("ok"));
            assertTrue(new JSONObject(receiver.command("{\"method\":\"chunk\",\"transfer_id\":\"data\",\"index\":0,\"data_base64\":\"YWJj\"}")).getBoolean("ok"));
        }
        try (PodSyncFileReceiver receiver = new PodSyncFileReceiver(context, "test", peer)) {
            JSONObject missing = new JSONObject(receiver.command("{\"method\":\"missing\",\"transfer_id\":\"data\"}"));
            assertEquals(0, missing.getJSONObject("value").getJSONArray("missing").length());
            JSONObject finish = new JSONObject(receiver.command("{\"method\":\"finish\",\"transfer_id\":\"data\"}"));
            assertTrue(finish.getBoolean("ok"));
            assertArrayEquals(new byte[]{97, 98, 99}, java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(finish.getJSONObject("value").getString("path"))));
            assertTrue(new JSONObject(receiver.command("{\"method\":\"cancel\",\"transfer_id\":\"data\"}")).getBoolean("ok"));
        }
    }
    @Test public void nativeReceiverPersistsAndLocksWithoutUiRuntime() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String peer = UUID.randomUUID().toString();
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        PodSyncFileReceiver receiver = new PodSyncFileReceiver(context, "test", peer);
        try {
            try (PodSyncFileReceiver duplicate = new PodSyncFileReceiver(context, "test", peer)) {
                fail("Concurrent receiver accepted");
            } catch (IOException expected) { }
            String offer = "{\"method\":\"offer\",\"manifest\":{\"transfer_id\":\"empty\",\"size\":0,\"sha256\":\""
                + emptyHash + "\",\"chunk_hashes\":[],\"mime\":\"text/plain\"}}";
            assertTrue(new JSONObject(receiver.command(offer)).getBoolean("ok"));
        } finally { receiver.close(); }
        receiver.close();
        try { receiver.command("{}"); fail("Closed receiver accepted command"); }
        catch (IllegalStateException expected) { }
        try (PodSyncFileReceiver reopened = new PodSyncFileReceiver(context, "test", peer)) {
            assertTrue(new JSONObject(reopened.command("{\"method\":\"finish\",\"transfer_id\":\"empty\"}")).getBoolean("ok"));
            assertFalse(new JSONObject(reopened.command("{\"method\":\"missing\",\"transfer_id\":\"../escape\"}")).getBoolean("ok"));
            assertTrue(new JSONObject(reopened.command("{\"method\":\"cancel\",\"transfer_id\":\"empty\"}")).getBoolean("ok"));
        }
    }
}
