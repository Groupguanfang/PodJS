package dev.podjs.companion;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.runtime.PodSyncSession;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodLanAttemptTest {
    private static final String[] CHANNELS={"state","message","file","ack"};
    private static Context endpoint() {
        String id=UUID.randomUUID().toString();
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public File getNoBackupFilesDir() {
                File root=new File(super.getNoBackupFilesDir(),id);
                if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable");
                return root;
            }
        };
    }
    private static InetSocketAddress loopback() { return new InetSocketAddress(InetAddress.getLoopbackAddress(),0); }
    private static <T> FutureTask<T> run(java.util.concurrent.Callable<T> task) {
        FutureTask<T> future=new FutureTask<>(task); new Thread(future,"lan-attempt-test").start(); return future;
    }
    private static void failed(FutureTask<?> future) throws Exception {
        try { future.get(5,TimeUnit.SECONDS); fail("Attempt unexpectedly succeeded"); }
        catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
    }
    @Test public void authenticatedHandoffSurvivesAttemptClose() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodCompanion phone=new PodCompanion(endpoint(),app,"phone"); PodCompanion watch=new PodCompanion(endpoint(),app,"watch");
            PodLanAttempt server=new PodLanAttempt(watch,"phone",CHANNELS,5000); PodLanAttempt client=new PodLanAttempt(phone,"watch",CHANNELS,5000)) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            InetSocketAddress address=server.listen(loopback()); FutureTask<PodCompanion.Session> accepting=run(server::accept);
            try(PodCompanion.Session p=client.connect(address); PodCompanion.Session w=accepting.get(5,TimeUnit.SECONDS)) {
                client.close(); server.close(); phone.setState("lan","authenticated"); assertTrue(p.sendState());
                assertEquals("state.applied",w.receiveDeferred(1)); assertEquals("state.ack",p.receiveDeferred(1));
                assertEquals("authenticated",watch.getState("lan"));
            }
        }
    }
    @Test public void cancelUnblocksAcceptAndReleasesPort() throws Exception {
        try(PodCompanion sdk=new PodCompanion(endpoint(),UUID.randomUUID().toString(),"phone");
            PodLanAttempt attempt=new PodLanAttempt(sdk,"watch",CHANNELS,5000)) {
            InetSocketAddress address=attempt.listen(loopback()); FutureTask<PodCompanion.Session> accepting=run(attempt::accept);
            attempt.close(); failed(accepting);
            try(ServerSocket rebound=new ServerSocket()) { rebound.bind(address); }
        }
    }
    @Test public void deadlineClosesStalledHandshake() throws Exception {
        try(PodCompanion sdk=new PodCompanion(endpoint(),UUID.randomUUID().toString(),"phone");
            PodLanAttempt attempt=new PodLanAttempt(sdk,"watch",CHANNELS,500)) {
            sdk.authorizeAfterUserApproval("watch",PodSyncSession.newChallenge());
            InetSocketAddress address=attempt.listen(loopback()); FutureTask<PodCompanion.Session> accepting=run(attempt::accept);
            try(Socket silent=new Socket(address.getAddress(),address.getPort())) { failed(accepting); assertTrue(attempt.isClosed()); }
        }
    }
    @Test public void cancellationClosesConnectingHandshake() throws Exception {
        try(PodCompanion sdk=new PodCompanion(endpoint(),UUID.randomUUID().toString(),"phone");
            PodLanAttempt attempt=new PodLanAttempt(sdk,"watch",CHANNELS,5000);
            ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            sdk.authorizeAfterUserApproval("watch",PodSyncSession.newChallenge()); server.setSoTimeout(5000);
            FutureTask<PodCompanion.Session> connecting=run(()->attempt.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort())));
            try(Socket silent=server.accept()) {
                silent.setSoTimeout(5000); assertTrue(silent.getInputStream().read()>=0);
                attempt.close(); failed(connecting);
            }
        }
    }
}
