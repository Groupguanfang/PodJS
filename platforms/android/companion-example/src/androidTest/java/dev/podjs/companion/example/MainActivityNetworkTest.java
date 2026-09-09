package dev.podjs.companion.example;

import android.content.Context;
import android.content.ContextWrapper;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.companion.PodCompanion;
import dev.podjs.companion.PodForegroundSync;
import dev.podjs.companion.PodLanAttempt;
import dev.podjs.runtime.PodSyncSession;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class MainActivityNetworkTest {
    @Test public void fileUiApprovesVerifiesImportsAndResumesAfterOfflineConsent() throws Exception {
        String peer="file-"+UUID.randomUUID(); byte[] key=PodSyncSession.newChallenge();
        Context remoteContext=endpoint();
        try(PodCompanion remote=new PodCompanion(remoteContext,"dev.podjs.companion.example",peer);
            ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled());
            String local=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("identity",Context.MODE_PRIVATE).getString("localId",null);
            remote.authorizeAfterUserApproval(local,key); approve(scenario,peer,key);
            File source=File.createTempFile("file-ui-source-",".bin",remoteContext.getCacheDir()); byte[] bytes=new byte[65539]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*11);
            String incoming,hash;
            try { java.nio.file.Files.write(source.toPath(),bytes); org.json.JSONObject manifest=remote.snapshotFile(source,"application/octet-stream"); incoming=manifest.getString("transfer_id"); hash=manifest.getString("sha256"); }
            finally { java.nio.file.Files.delete(source.toPath()); }
            final String receivedId=incoming;
            try(PodForegroundSync driver=connect(scenario,remote,local)) {
                remote.offerFile(local,incoming); driver.requestFiles(false);
                await(scenario,a->a.findViewById(R.id.incoming_files).findViewWithTag("accept:"+receivedId)!=null);
                until(()->remote.outgoingFiles(local).get(0).phase.equals("waiting"));
                scenario.onActivity(a->a.findViewById(R.id.incoming_files).findViewWithTag("accept:"+receivedId).performClick());
                await(scenario,a->text(a,R.id.result).startsWith("已批准"));
                driver.requestFiles(true); until(()->remote.outgoingFiles(local).get(0).phase.equals("complete"));
                await(scenario,a->a.findViewById(R.id.incoming_files).findViewWithTag("verify:"+receivedId)!=null);
                scenario.onActivity(a->a.findViewById(R.id.incoming_files).findViewWithTag("verify:"+receivedId).performClick());
                await(scenario,a->text(a,R.id.result).contains(hash) && a.findViewById(R.id.choose_file).isEnabled());
                scenario.onActivity(a->a.importDocument(peer,SelectedDocumentTest.uri("/valid")));
                await(scenario,a->text(a,R.id.result).startsWith("已保存发送快照") && a.findViewById(R.id.choose_file).isEnabled());
                until(()->remote.pendingFileConsent().size()==1);
                scenario.onActivity(a->a.findViewById(R.id.disconnect).performClick()); until(driver::isStopped);
                await(scenario,a->a.findViewById(R.id.connect).isEnabled());
            }
            String outgoing=remote.pendingFileConsent().get(0).transferId; remote.acceptFile(local,outgoing);
            try(PodForegroundSync resumed=connect(scenario,remote,local)) {
                // The UI's explicit reconnection checks previously pending consent once.
                until(()->remote.incomingFile(local,outgoing).phase.equals("complete"));
                assertArrayEquals(DocumentFixtureProvider.bytes(),java.nio.file.Files.readAllBytes(remote.completedIncomingFile(local,outgoing).toPath()));
                scenario.onActivity(a->{ a.findViewById(R.id.incoming_files).findViewWithTag("cancel-in:"+receivedId).performClick(); a.fileDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->a.findViewById(R.id.incoming_files).findViewWithTag("cancel-in:"+receivedId)==null && a.findViewById(R.id.show_sources).isEnabled());
                scenario.onActivity(a->a.findViewById(R.id.show_sources).performClick());
                await(scenario,a->a.findViewById(R.id.sources).findViewWithTag("release:"+outgoing)!=null && a.findViewById(R.id.show_sources).isEnabled());
                scenario.onActivity(a->{ a.findViewById(R.id.sources).findViewWithTag("release:"+outgoing).performClick(); a.fileDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->a.findViewById(R.id.sources).findViewWithTag("release:"+outgoing)==null && a.findViewById(R.id.revoke_pairing).isEnabled());
                scenario.onActivity(a->{ a.findViewById(R.id.revoke_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->text(a,R.id.result).startsWith("本机已撤销配对"));
            }
            remote.cancelIncomingFile(local,outgoing); remote.releaseSource(incoming); remote.revoke(local);
        } finally { java.util.Arrays.fill(key,(byte)0); }
        workersStopped();
    }
    private interface Check { boolean ready(MainActivity activity); }
    private interface Condition { boolean ready() throws Exception; }
    private static void until(Condition condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.ready()) { if(System.nanoTime()>end) fail("Condition timed out"); Thread.sleep(20); }
    }
    private static void await(ActivityScenario<MainActivity> scenario,Check check) throws Exception {
        until(()->{ AtomicBoolean result=new AtomicBoolean(); scenario.onActivity(a->result.set(check.ready(a))); return result.get(); });
    }
    private static String text(MainActivity activity,int id) { return ((TextView)activity.findViewById(id)).getText().toString(); }
    private static void workersStopped() throws Exception {
        until(()->{ for(Thread thread:Thread.getAllStackTraces().keySet()) if(thread.isAlive() && thread.getName().startsWith("podjs-example-")) return false; return true; });
    }
    static Context endpoint() {
        String id="network-"+UUID.randomUUID();
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public File getNoBackupFilesDir() {
                File root=new File(super.getNoBackupFilesDir(),id); if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable"); return root;
            }
        };
    }
    static void approve(ActivityScenario<MainActivity> scenario,String peer,byte[] key) throws Exception {
        StringBuilder hex=new StringBuilder(); for(byte value:key) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.peer)).setText(peer); ((EditText)a.findViewById(R.id.pairing_key)).setText(hex.toString());
            a.findViewById(R.id.approve_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
        await(scenario,a->text(a,R.id.result).startsWith("本机已保存批准的配对凭据") && a.findViewById(R.id.connect).isEnabled());
    }
    static PodForegroundSync connect(ActivityScenario<MainActivity> scenario,PodCompanion remote,String local) throws Exception {
        try(PodLanAttempt accepting=new PodLanAttempt(remote,local,new String[]{"state","message","file","ack"},10000)) {
            InetSocketAddress address=accepting.listen(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));
            FutureTask<PodCompanion.Session> accepted=new FutureTask<>(accepting::accept); new Thread(accepted,"example-test-accept").start();
            scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.ip)).setText(address.getAddress().getHostAddress()); ((EditText)a.findViewById(R.id.port)).setText(Integer.toString(address.getPort())); a.findViewById(R.id.connect).performClick(); });
            PodForegroundSync driver=new PodForegroundSync(accepted.get(10,TimeUnit.SECONDS),30000,Runnable::run,reason->{});
            try { await(scenario,a->text(a,R.id.connection_status).startsWith("已认证连接")); return driver; }
            catch(Exception|AssertionError error) { driver.close(); throw error; }
        }
    }
    @Test public void uiPairsConnectsSyncsBothWaysExplicitlyAcksAndReconnectsAfterBackground() throws Exception {
        String peer="remote-"+UUID.randomUUID(), initial="initial-"+UUID.randomUUID(), updated="remote-note-"+UUID.randomUUID();
        byte[] key=PodSyncSession.newChallenge();
        try(PodCompanion remote=new PodCompanion(endpoint(),"dev.podjs.companion.example",peer);
            ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled());
            String local=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("identity",Context.MODE_PRIVATE).getString("localId",null);
            assertNotNull(local); remote.authorizeAfterUserApproval(local,key); approve(scenario,peer,key);
            scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.note)).setText(initial); a.findViewById(R.id.save_note).performClick(); });
            await(scenario,a->a.findViewById(R.id.connect).isEnabled() && text(a,R.id.saved_note).contains(initial));
            try(PodForegroundSync driver=connect(scenario,remote,local)) {
                until(()->initial.equals(remote.getState("note")));
                remote.setState("note",updated); driver.requestState();
                await(scenario,a->text(a,R.id.saved_note).contains(updated));
                scenario.onActivity(a->assertEquals(initial,text(a,R.id.note))); // Remote refresh preserves the editor draft.
                long now=System.currentTimeMillis(); remote.sendMessage(local,"UI ACK required".getBytes(java.nio.charset.StandardCharsets.UTF_8),now+60000,false,now); driver.requestMessages();
                await(scenario,a->a.findViewById(R.id.ack_first)!=null && a.findViewById(R.id.ack_first).isEnabled());
                assertEquals(1,remote.pendingMessages(local,System.currentTimeMillis()).size());
                scenario.onActivity(a->a.findViewById(R.id.ack_first).performClick());
                until(()->remote.pendingMessages(local,System.currentTimeMillis()).isEmpty());
                await(scenario,a->a.findViewById(R.id.queue_message).isEnabled());
                scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.message)).setText("Retain until remote ACK"); a.findViewById(R.id.queue_message).performClick(); });
                until(()->remote.receivedMessages(System.currentTimeMillis()).size()==1);
                await(scenario,a->text(a,R.id.queue).contains("待发送 1 条"));
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); until(driver::isStopped);
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                await(scenario,a->a.findViewById(R.id.connect).isEnabled() && text(a,R.id.connection_status).startsWith("已断开"));
            }
            try(PodForegroundSync resumed=connect(scenario,remote,local)) {
                until(()->remote.receivedMessages(System.currentTimeMillis()).size()==1);
                resumed.ackMessage(remote.receivedMessages(System.currentTimeMillis()).get(0));
                await(scenario,a->text(a,R.id.queue).equals("暂无待发送消息"));
                scenario.onActivity(a->a.findViewById(R.id.disconnect).performClick()); until(resumed::isStopped);
                await(scenario,a->a.findViewById(R.id.revoke_pairing).isEnabled());
                scenario.onActivity(a->{ a.findViewById(R.id.revoke_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->text(a,R.id.result).startsWith("本机已撤销配对"));
            }
            remote.revoke(local);
        } finally { java.util.Arrays.fill(key,(byte)0); }
        workersStopped();
    }
    @Test public void listenerCancelsOnBackgroundAndDoesNotAutoRestart() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.listen).isEnabled());
            scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.peer)).setText("pending-"+UUID.randomUUID()); ((EditText)a.findViewById(R.id.ip)).setText("127.0.0.1"); ((EditText)a.findViewById(R.id.port)).setText("0"); a.findViewById(R.id.listen).performClick(); });
            await(scenario,a->text(a,R.id.connection_status).contains("监听 127.0.0.1:"));
            java.util.concurrent.atomic.AtomicInteger port=new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(a->{ String status=text(a,R.id.connection_status); int start=status.indexOf("127.0.0.1:")+10; port.set(Integer.parseInt(status.substring(start,status.indexOf('（',start)))); });
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            until(()->{ try(ServerSocket rebound=new ServerSocket()) { rebound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port.get())); return true; } catch(java.net.BindException busy) { return false; } });
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await(scenario,a->a.findViewById(R.id.listen).isEnabled() && text(a,R.id.connection_status).startsWith("已断开"));
        }
        workersStopped();
    }
}
