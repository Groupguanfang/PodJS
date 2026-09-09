package dev.podjs.companion;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.runtime.PodSyncSession;
import dev.podjs.runtime.PodSyncStream;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Injected connected streams test lifecycle, not RFCOMM radio or system bonding. */
public class PodRfcommAttemptTest {
    private static final String[] CHANNELS={"state","message","file","ack"};
    private static Context endpoint() {
        String id=UUID.randomUUID().toString();
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public File getNoBackupFilesDir() {
                File root=new File(super.getNoBackupFilesDir(),id); if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable"); return root;
            }
        };
    }
    private static final class Wire implements AutoCloseable {
        final Socket a,b;
        Wire() throws Exception {
            try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                a=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()); b=listener.accept();
            }
        }
        public void close() throws IOException { try { a.close(); } finally { b.close(); } }
    }
    private static PodRfcommAttempt.Endpoint stream(Socket socket) {
        return new PodRfcommAttempt.Endpoint() { public PodSyncStream open() throws IOException { return new PodSyncStream(socket); } public void close() throws IOException { socket.close(); } };
    }
    private static <T> FutureTask<T> run(Callable<T> operation) {
        FutureTask<T> future=new FutureTask<>(operation); new Thread(future,"rfcomm-attempt-test").start(); return future;
    }
    private static IOException failed(FutureTask<?> future) throws Exception {
        try { future.get(5,TimeUnit.SECONDS); fail("Unexpected success"); return null; }
        catch(ExecutionException error) { assertTrue(error.getCause() instanceof IOException); return (IOException)error.getCause(); }
    }
    @Test public void sharedAuthenticationHandoffSurvivesAttemptClose() throws Exception {
        String app=UUID.randomUUID().toString();
        try(Wire wire=new Wire(); PodCompanion phone=new PodCompanion(endpoint(),app,"phone"); PodCompanion watch=new PodCompanion(endpoint(),app,"watch");
            PodRfcommAttempt client=new PodRfcommAttempt(phone,"watch",CHANNELS,3000); PodRfcommAttempt server=new PodRfcommAttempt(watch,"phone",CHANNELS,3000)) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            FutureTask<PodCompanion.Session> accepting=run(()->server.open(stream(wire.b),false));
            try(PodCompanion.Session p=client.open(stream(wire.a),true); PodCompanion.Session w=accepting.get(4,TimeUnit.SECONDS)) {
                client.close(); server.close(); assertFalse(wire.a.isClosed()); assertFalse(wire.b.isClosed());
                phone.setState("transport","RFCOMM lifecycle fixture"); assertTrue(p.sendState());
                assertEquals("state.applied",w.receiveDeferred(1)); assertEquals("state.ack",p.receiveDeferred(1));
                assertEquals("RFCOMM lifecycle fixture",watch.getState("transport"));
            }
            assertTrue(wire.a.isClosed()); assertTrue(wire.b.isClosed());
        }
    }
    @Test public void deadlineClosesStalledHandshake() throws Exception {
        try(Wire wire=new Wire(); PodCompanion sdk=new PodCompanion(endpoint(),UUID.randomUUID().toString(),"phone");
            PodRfcommAttempt attempt=new PodRfcommAttempt(sdk,"watch",CHANNELS,200)) {
            sdk.authorizeAfterUserApproval("watch",PodSyncSession.newChallenge());
            assertTrue(failed(run(()->attempt.open(stream(wire.a),true))).getMessage().contains("deadline"));
            assertTrue(attempt.isClosed()); assertTrue(wire.a.isClosed());
        }
    }
    @Test public void cancellationClosesLateOpenedStream() throws Exception {
        CountDownLatch began=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        try(Wire wire=new Wire(); PodCompanion sdk=new PodCompanion(endpoint(),UUID.randomUUID().toString(),"phone");
            PodRfcommAttempt attempt=new PodRfcommAttempt(sdk,"watch",CHANNELS,3000)) {
            PodRfcommAttempt.Endpoint delayed=new PodRfcommAttempt.Endpoint() {
                public PodSyncStream open() throws IOException {
                    began.countDown(); try { if(!cancelled.await(3,TimeUnit.SECONDS)) throw new IOException("Not cancelled"); }
                    catch(InterruptedException error) { throw new IOException(error); } return new PodSyncStream(wire.a);
                }
                public void close() { cancelled.countDown(); }
            };
            FutureTask<?> pending=run(()->attempt.open(delayed,true)); assertTrue(began.await(1,TimeUnit.SECONDS));
            attempt.close(); failed(pending); assertTrue(wire.a.isClosed());
        }
    }
}
