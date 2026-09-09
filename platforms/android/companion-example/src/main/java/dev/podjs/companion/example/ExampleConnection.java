package dev.podjs.companion.example;

import dev.podjs.companion.PodCompanion;
import dev.podjs.companion.PodForegroundSync;
import dev.podjs.companion.PodLanAttempt;
import dev.podjs.runtime.PodSyncInbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Example-owned foreground lifecycle. No DNS, discovery, reconnect or background grant.
 * Storage and blocking network cleanup never run on the UI executor. Generation checks
 * prevent a completed handshake or queued notification resurrecting a stopped Activity.
 */
final class ExampleConnection implements AutoCloseable {
    enum Phase { IDLE, CONNECTING, LISTENING, CONNECTED, STOPPED, FAILED }
    static final class State {
        final Phase phase; final String peer,detail;
        State(Phase phase,String peer,String detail) { this.phase=phase; this.peer=peer; this.detail=detail; }
    }
    interface Listener { void changed(State state); }
    private final Object gate=new Object();
    private final PodCompanion sdk;
    private final Executor events;
    private final Listener listener;
    private final ExecutorService network=Executors.newSingleThreadExecutor(task->new Thread(task,"podjs-example-connect"));
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor(task->new Thread(task,"podjs-example-disconnect"));
    private AutoCloseable attempt;
    private PodForegroundSync driver;
    private boolean foreground,disposed,connecting,cleaning;
    private long generation;
    private State state=new State(Phase.IDLE,"","");
    ExampleConnection(PodCompanion sdk,Executor events,Listener listener) {
        this.sdk=sdk; this.events=events; this.listener=listener;
    }
    void setForeground(boolean active) {
        synchronized(gate) { foreground=active; if(!active) stopLocked("background"); }
    }
    boolean isBusy() { synchronized(gate) { return connecting || cleaning || attempt!=null || driver!=null; } }
    boolean isConnected() { synchronized(gate) { return driver!=null && !driver.isStopped(); } }
    void start(String peer,InetSocketAddress address,boolean listen) throws IOException {
        synchronized(gate) {
            if(disposed || !foreground) throw new IOException("页面不在前台");
            if(isBusy()) throw new IOException("先断开当前连接并等待关闭完成");
            if(address==null || address.isUnresolved()) throw new IOException("请填写数字 IP 地址");
            if(!listen && (address.getPort()==0 || address.getAddress().isAnyLocalAddress())) throw new IOException("请填写对方 IP 和非零端口");
            PodLanAttempt pending=new PodLanAttempt(sdk,peer,new String[]{"state","message","file","ack"},30000);
            long token=++generation; attempt=pending; connecting=true;
            state=new State(Phase.CONNECTING,peer,listen?"准备监听":"连接与认证中"); publishLocked();
            network.execute(()->establish(token,peer,address,listen,pending));
        }
    }
    private void establish(long token,String peer,InetSocketAddress address,boolean listen,PodLanAttempt pending) {
        PodCompanion.Session session=null;
        try {
            if(listen) {
                InetSocketAddress bound=pending.listen(address);
                synchronized(gate) { if(token==generation && !disposed) { state=new State(Phase.LISTENING,peer,bound.getAddress().getHostAddress()+":"+bound.getPort()); publishLocked(); } }
                session=pending.accept();
            } else session=pending.connect(address);
            synchronized(gate) {
                if(!disposed && foreground && token==generation) {
                    driver=new PodForegroundSync(session,120000,events,reason->ended(token,reason)); session=null;
                    // An explicit new connection checks retained consent once, not on a timer.
                    try { driver.requestFiles(true); } catch(IOException ignored) { }
                    attempt=null; state=new State(Phase.CONNECTED,peer,"认证成功；本轮最多 120 秒"); publishLocked();
                }
            }
        } catch(Exception error) {
            synchronized(gate) { if(token==generation && !disposed) { state=new State(Phase.FAILED,peer,"连接未完成：检查配对凭据、IP、端口或重试"); publishLocked(); } }
        } finally {
            if(session!=null) try { session.close(); } catch(Exception ignored) { }
            pending.close();
            synchronized(gate) { if(attempt==pending) attempt=null; connecting=false; publishLocked(); }
        }
    }
    void startBle(android.content.Context context,String peer,android.bluetooth.BluetoothDevice device,boolean listen) throws IOException {
        synchronized(gate) {
            if(disposed || !foreground) throw new IOException("页面不在前台");
            if(isBusy()) throw new IOException("先断开当前连接并等待关闭完成");
            dev.podjs.companion.PodBleAttempt pending=new dev.podjs.companion.PodBleAttempt(sdk,peer,new String[]{"state","message","file","ack"},30000);
            long token=++generation; attempt=pending; connecting=true;
            state=new State(Phase.CONNECTING,peer,listen?"BLE 广播与认证等待":"BLE 连接与认证"); publishLocked();
            network.execute(()->{
                PodCompanion.Session session=null;
                try {
                    session=listen?(device==null?pending.accept(context):pending.accept(context,device)):pending.connect(context,device);
                    synchronized(gate) {
                        if(!disposed && foreground && token==generation) {
                            driver=new PodForegroundSync(session,120000,events,reason->ended(token,reason)); session=null;
                            try { driver.requestFiles(true); } catch(IOException ignored) { }
                            attempt=null; state=new State(Phase.CONNECTED,peer,"BLE 认证成功；本轮最多 120 秒"); publishLocked();
                        }
                    }
                } catch(Exception error) {
                    synchronized(gate) { if(token==generation && !disposed) { state=new State(Phase.FAILED,peer,"BLE 未连接：检查权限、蓝牙地址、双方角色与配对凭据；不会自动重试"); publishLocked(); } }
                } finally {
                    if(session!=null) try { session.close(); } catch(Exception ignored) { }
                    pending.close();
                    synchronized(gate) { if(attempt==pending) attempt=null; connecting=false; publishLocked(); }
                }
            });
        }
    }
    private void ended(long token,String reason) {
        synchronized(gate) {
            if(disposed || token!=generation) return;
            driver=null; state=new State(Phase.STOPPED,state.peer,reason); publishLocked();
        }
    }
    void disconnect() { synchronized(gate) { stopLocked("closed"); } }
    private void stopLocked(String reason) {
        if(disposed) return;
        ++generation;
        AutoCloseable pending=attempt; PodForegroundSync active=driver; attempt=null; driver=null;
        state=new State(Phase.STOPPED,state.peer,reason);
        if(pending!=null || active!=null) {
            cleaning=true;
            cleanup.execute(()->{
                try { if(pending!=null) try { pending.close(); } catch(Exception ignored) { } if(active!=null) active.close(); }
                finally { synchronized(gate) { cleaning=false; publishLocked(); } }
            });
        }
        publishLocked();
    }
    private void publishLocked() {
        long token=generation; State snapshot=state;
        if(disposed) return;
        events.execute(()->{
            synchronized(gate) { if(disposed || generation!=token || state!=snapshot) return; }
            listener.changed(snapshot);
        });
    }
    void requestState() {
        synchronized(gate) { if(driver!=null) try { driver.requestState(); } catch(IOException ignored) { /* Durable state remains queued. */ } }
    }
    void requestMessages(String peer) {
        synchronized(gate) { if(driver!=null && state.peer.equals(peer)) try { driver.requestMessages(); } catch(IOException ignored) { /* Durable outbox remains queued. */ } }
    }
    void requestFiles(String peer,boolean pollConsent) {
        synchronized(gate) { if(driver!=null && state.peer.equals(peer)) try { driver.requestFiles(pollConsent); } catch(IOException ignored) { /* Persistent file intent remains available. */ } }
    }
    /** Returns false for offline/other-peer delivery: caller may explicitly ACK locally. */
    boolean acknowledge(PodSyncInbox.Message delivery) throws IOException {
        synchronized(gate) { if(driver==null || !state.peer.equals(delivery.peerId)) return false; driver.ackMessage(delivery); return true; }
    }
    @Override public void close() {
        synchronized(gate) {
            if(disposed) return; foreground=false; stopLocked("closed"); disposed=true;
            network.shutdown(); cleanup.shutdown();
        }
    }
}
