package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncFileSnapshotsTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File source(byte[] bytes) throws Exception { File file=File.createTempFile("sync-source-",".bin",context().getCacheDir()); Files.write(file.toPath(),bytes); return file; }
    @Test public void frozenSourceSurvivesMutationDeletionAndReopenWithVerifiedChunks() throws Exception {
        String app=UUID.randomUUID().toString(); byte[] bytes=new byte[65539]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*31);
        File source=source(bytes); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app);
        JSONObject manifest=snapshots.create(source,"application/octet-stream"); String id=manifest.getString("transfer_id");
        Files.write(source.toPath(),new byte[]{9}); Files.delete(source.toPath());
        snapshots=new PodSyncFileSnapshots(context(),app);
        assertEquals(Arrays.asList(id),snapshots.inventory()); assertEquals(manifest.toString(),snapshots.get(id).toString());
        assertArrayEquals(Arrays.copyOfRange(bytes,0,65536),snapshots.chunk(id,0)); assertArrayEquals(Arrays.copyOfRange(bytes,65536,bytes.length),snapshots.chunk(id,1));
        try { snapshots.chunk(id,2); fail("Out of range chunk"); } catch(java.io.IOException expected) { }
        assertTrue(new PodSyncFileSnapshots(context(),UUID.randomUUID().toString()).inventory().isEmpty());
        snapshots.discard(id); snapshots.discard(id); assertTrue(snapshots.inventory().isEmpty());
    }
    @Test public void snapshotsAndIncomingReservationsShareOneAppQuota() throws Exception {
        String app=UUID.randomUUID().toString(), hash=new String(new char[64]).replace('\0','0'); JSONArray chunks=new JSONArray(); for(int n=0;n<256;n++) chunks.put(hash);
        JSONObject manifest=new JSONObject().put("transfer_id","large").put("size",16*1024*1024).put("sha256",hash).put("chunk_hashes",chunks).put("mime","application/octet-stream");
        File source=source(new byte[]{1}); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app);
        try(PodSyncIncomingFiles incoming=new PodSyncIncomingFiles(context(),app)) {
            incoming.offer("phone",manifest); incoming.accept("phone","large");
            try { snapshots.create(source,"application/octet-stream"); fail("Outgoing bypassed incoming quota"); } catch(java.io.IOException expected) { }
            assertTrue(snapshots.inventory().isEmpty()); incoming.cancel("phone","large");
            String id=snapshots.create(source,"application/octet-stream").getString("transfer_id");
            JSONObject next=new JSONObject(manifest.toString()).put("transfer_id","next"); incoming.offer("phone",next);
            try { incoming.accept("phone","next"); fail("Incoming bypassed outgoing quota"); } catch(java.io.IOException expected) { }
            snapshots.discard(id); incoming.recover(); assertEquals("accepted",incoming.status("phone","next").phase); incoming.cancel("phone","next");
        } finally { Files.deleteIfExists(source.toPath()); }
    }
    @Test public void emptyOversizeSymlinkAndCorruptionBoundaries() throws Exception {
        String app=UUID.randomUUID().toString(); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app); File source=source(new byte[0]);
        String empty=snapshots.create(source,"text/plain").getString("transfer_id"); assertEquals(0,snapshots.get(empty).getJSONArray("chunk_hashes").length()); snapshots.discard(empty);
        try(RandomAccessFile file=new RandomAccessFile(source,"rw")) { file.setLength(16L*1024*1024+1); }
        try { snapshots.create(source,"text/plain"); fail("Oversize accepted"); } catch(java.io.IOException expected) { }
        Files.write(source.toPath(),new byte[]{1,2,3}); File link=new File(source.getParentFile(),source.getName()+"-link"); Files.createSymbolicLink(link.toPath(),source.toPath());
        try { snapshots.create(link,"text/plain"); fail("Symlink accepted"); } catch(java.io.IOException expected) { } finally { Files.delete(link.toPath()); }
        String id=snapshots.create(source,"text/plain").getString("transfer_id");
        File complete=new File(context().getNoBackupFilesDir(),"podjs-sync/"+PodSyncIncomingFiles.storageAppId(app)+"/outgoing-snapshots/"+id+"/complete");
        Files.write(complete.toPath(),new byte[]{1,2,4});
        try { snapshots.chunk(id,0); fail("Corrupt snapshot sent"); } catch(java.io.IOException expected) { }
        snapshots.discard(id); Files.delete(source.toPath());
    }
}
