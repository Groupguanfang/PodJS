package dev.podjs.companion.example;

import android.content.Context;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

public class SelectedDocumentTest {
    @Test public void stagingRecoveryPreservesUnknownEntriesAndSymlinkTargets() throws Exception {
        Context context=context(); File directory=new File(context.getCacheDir(),"podjs-document-staging");
        File known=File.createTempFile("podjs-selected-",".bin",directory), unknown=new File(directory,"unknown-test-entry");
        java.nio.file.Files.write(unknown.toPath(),new byte[]{7});
        try {
            try { SelectedDocument.recoverStaging(context.getCacheDir()); fail("Unknown entry was ignored"); } catch(java.io.IOException expected) { }
            assertTrue(known.exists()); assertArrayEquals(new byte[]{7},java.nio.file.Files.readAllBytes(unknown.toPath()));
        } finally { java.nio.file.Files.delete(unknown.toPath()); }
        File outside=File.createTempFile("kept-document-test-",".bin",context.getCacheDir());
        java.nio.file.Path link=new File(directory,"podjs-selected-link.bin").toPath();
        try {
            java.nio.file.Files.write(outside.toPath(),new byte[]{9}); java.nio.file.Files.createSymbolicLink(link,outside.toPath());
            try { SelectedDocument.recoverStaging(context.getCacheDir()); fail("Symlink accepted"); } catch(java.io.IOException expected) { }
            assertArrayEquals(new byte[]{9},java.nio.file.Files.readAllBytes(outside.toPath())); assertTrue(known.exists());
        } finally { java.nio.file.Files.deleteIfExists(link); java.nio.file.Files.delete(outside.toPath()); java.nio.file.Files.deleteIfExists(known.toPath()); }
    }
    private androidx.test.core.app.ActivityScenario<MainActivity> activity;
    @Before public void foregroundDocumentHost() throws Exception {
        activity=androidx.test.core.app.ActivityScenario.launch(MainActivity.class);
        // OWW242 forbids cold-starting another app from a provider request. A
        // foreground document-picker round trip starts the test APK normally.
        activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(true) {
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean(); activity.onActivity(a->ready.set(a.findViewById(R.id.choose_file).isEnabled()));
            if(ready.get()) break; if(System.nanoTime()>end) fail("Document host did not initialize"); Thread.sleep(20);
        }
    }
    @After public void closeHost() { if(activity!=null) activity.close(); }
    @Test public void deadlineCancelsCooperativeProviderWithoutUiAction() throws Exception {
        Context context=context();
        long started=android.os.SystemClock.elapsedRealtime();
        try(SelectedDocument selected=new SelectedDocument(context.getContentResolver(),uri("/wait"),1000)) {
            FutureTask<File> copying=new FutureTask<>(()->selected.copyTo(context.getCacheDir())); Thread thread=new Thread(copying,"test-document-deadline"); thread.start();
            try { copying.get(5,TimeUnit.SECONDS); fail("Expired open returned a file"); }
            catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof android.os.OperationCanceledException || expected.getCause() instanceof java.io.IOException); }
            assertTrue("Failed before the deadline instead of timing out",android.os.SystemClock.elapsedRealtime()-started>=750);
            thread.join(1000); assertFalse(thread.isAlive());
        }
    }
    static Uri uri(String path) { return Uri.parse("content://"+DocumentFixtureProvider.AUTHORITY+path); }
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void selectedRegularDocumentCopiesExactlyAndRejectsOversizeAndPipe() throws Exception {
        Context context=context(); File spool;
        try(SelectedDocument selected=new SelectedDocument(context.getContentResolver(),uri("/valid"))) {
            spool=selected.copyTo(context.getCacheDir());
            try { assertArrayEquals(DocumentFixtureProvider.bytes(),java.nio.file.Files.readAllBytes(spool.toPath())); }
            finally { java.nio.file.Files.delete(spool.toPath()); }
        }
        for(String path:new String[]{"/oversize","/pipe"}) {
            try(SelectedDocument selected=new SelectedDocument(context.getContentResolver(),uri(path))) {
                try { selected.copyTo(context.getCacheDir()); fail("Unsupported source accepted"); } catch(java.io.IOException expected) { }
            }
        }
        try { new SelectedDocument(context.getContentResolver(),Uri.parse("file:///not-a-document")); fail("File URI accepted"); } catch(IllegalArgumentException expected) { }
        File[] leftovers=new File(context.getCacheDir(),"podjs-document-staging").listFiles(); assertNotNull(leftovers); assertEquals(0,leftovers.length);
        // A completed spool stranded before snapshot publication is reclaimed on reopen.
        try(SelectedDocument selected=new SelectedDocument(context.getContentResolver(),uri("/valid"))) { spool=selected.copyTo(context.getCacheDir()); }
        SelectedDocument.recoverStaging(context.getCacheDir()); assertFalse(spool.exists());
    }
    @Test public void cancellationReachesCooperativeProviderAndStopsWorker() throws Exception {
        Context context=context();
        try(SelectedDocument selected=new SelectedDocument(context.getContentResolver(),uri("/wait"))) {
            FutureTask<File> copying=new FutureTask<>(()->selected.copyTo(context.getCacheDir())); Thread thread=new Thread(copying,"test-document-copy"); thread.start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!context.getContentResolver().call(DocumentFixtureProvider.AUTHORITY,"waiting",null,null).getBoolean("waiting")) {
                if(System.nanoTime()>end) fail("Provider did not begin open"); Thread.sleep(20);
            }
            selected.close();
            try { copying.get(5,TimeUnit.SECONDS); fail("Cancelled open returned a file"); }
            catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof android.os.OperationCanceledException || expected.getCause() instanceof java.io.IOException); }
            thread.join(1000); assertFalse(thread.isAlive());
        }
    }
}
