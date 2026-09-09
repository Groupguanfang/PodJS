package dev.podjs.companion.example;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One user-selected SAF document. Blocking work belongs on the example IO worker.
 * Only regular-file descriptors are accepted; pipes/cloud streams are explicitly
 * unsupported, rather than allowing an unbounded streaming read. Provider open
 * cancellation is cooperative: the provider must honor Android's CancellationSignal.
 */
final class SelectedDocument implements AutoCloseable {
    static final long LIMIT=16L*1024*1024;
    private final ContentResolver resolver;
    private final Uri uri;
    private final CancellationSignal signal=new CancellationSignal();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final ScheduledExecutorService cancellation=Executors.newSingleThreadScheduledExecutor(task->{ Thread thread=new Thread(task,"podjs-example-document-cancel"); thread.setDaemon(true); return thread; });
    private final long deadline;
    private volatile java.util.concurrent.ScheduledFuture<?> timeout;
    SelectedDocument(ContentResolver resolver,Uri uri) {
        this(resolver,uri,30000);
    }
    SelectedDocument(ContentResolver resolver,Uri uri,long timeoutMillis) {
        if(uri==null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("请选择系统文档提供器中的文件");
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("Invalid import deadline");
        this.resolver=resolver; this.uri=uri;
        deadline=SystemClock.elapsedRealtime()+timeoutMillis;
        timeout=cancellation.schedule(this::close,timeoutMillis,TimeUnit.MILLISECONDS);
        if(cancelled.get()) timeout.cancel(false);
    }
    void checkActive() throws IOException {
        if(cancelled.get() || SystemClock.elapsedRealtime()>=deadline) throw new IOException("文件导入已取消或超时，请保持页面在前台后重试");
    }
    private static File staging(File cache) throws IOException {
        File directory=new File(cache,"podjs-document-staging");
        try { java.nio.file.Files.createDirectory(directory.toPath()); } catch(java.nio.file.FileAlreadyExistsException exists) { }
        if(!java.nio.file.Files.isDirectory(directory.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid document staging directory");
        return directory;
    }
    /** Call only after acquiring the SDK's exclusive application storage ownership,
     * before starting imports. Unknown entries and symlinks are never deleted. */
    static void recoverStaging(File cache) throws IOException {
        File[] entries=staging(cache).listFiles(); if(entries==null) throw new IOException("Document staging unavailable");
        for(File entry:entries) {
            if(!entry.getName().startsWith("podjs-selected-") || !entry.getName().endsWith(".bin") ||
                !java.nio.file.Files.isRegularFile(entry.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unknown document staging entry");
        }
        for(File entry:entries) java.nio.file.Files.delete(entry.toPath());
    }
    /** Returned spool belongs to the caller and must be deleted after snapshot creation. */
    File copyTo(File cache) throws Exception {
        checkActive(); File spool=null; boolean transferred=false;
        try {
            try(ParcelFileDescriptor descriptor=resolver.openFileDescriptor(uri,"r",signal)) {
                checkActive();
                if(descriptor==null || !OsConstants.S_ISREG(Os.fstat(descriptor.getFileDescriptor()).st_mode)) throw new IOException("此提供器返回流式文件，请先下载到本机再选择");
                if(descriptor.getStatSize()>LIMIT) throw new IOException("文件超过 16 MiB 上限");
                spool=File.createTempFile("podjs-selected-",".bin",staging(cache));
                try(FileInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor())); FileOutputStream output=new FileOutputStream(spool)) {
                    byte[] buffer=new byte[65536]; long total=0; int count;
                    while(true) {
                        checkActive(); count=input.read(buffer); if(count<0) break;
                        if(count==0) throw new IOException("文件读取没有进展");
                        total+=count; if(total>LIMIT) throw new IOException("文件超过 16 MiB 上限");
                        output.write(buffer,0,count);
                    }
                    checkActive(); output.getFD().sync();
                }
            }
            transferred=true; return spool;
        } finally { if(!transferred && spool!=null) java.nio.file.Files.deleteIfExists(spool.toPath()); }
    }
    /** Cancel outside the UI thread: a provider's cancellation callback may block. */
    @Override public synchronized void close() {
        if(!cancelled.compareAndSet(false,true)) return;
        java.util.concurrent.ScheduledFuture<?> scheduled=timeout; if(scheduled!=null) scheduled.cancel(false);
        cancellation.execute(signal::cancel); cancellation.shutdown();
    }
}
