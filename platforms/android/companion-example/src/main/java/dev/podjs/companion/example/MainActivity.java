package dev.podjs.companion.example;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import dev.podjs.companion.PodCompanion;
import dev.podjs.runtime.PodSyncOutbox;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Offline-first SDK example with explicit out-of-band pairing approval. */
public final class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private PodCompanion sdk;
    private EditText peer,note,message;
    private TextView saved,queue,result;
    private Button save,send,refresh;
    private EditText pairingKey;
    private Button generateKey,approvePairing,revokePairing;
    private String localId;
    private boolean foreground;
    private boolean workBusy,refreshQueued,refreshAgain;
    private int pendingWork;
    private int incomingPage,outgoingPage;
    private volatile ExampleConnection connection;
    BlePanel ble;
    private EditText ip,port;
    private Button connect,listen,disconnect;
    private TextView connectionStatus;
    private LinearLayout inbox;
    private Button chooseFile,refreshFiles,showSources;
    private LinearLayout incomingFiles,outgoingFiles,sources;
    private volatile SelectedDocument importing;
    private String fileTarget;
    private static final int SELECT_FILE=41;
    android.app.AlertDialog fileDialog;
    android.app.AlertDialog pairingDialog;
    private interface Work { void run() throws Exception; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Pairing material must not appear in screenshots or the task switcher.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.main);
        findViewById(R.id.page).setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        });
        peer=findViewById(R.id.peer); note=findViewById(R.id.note); message=findViewById(R.id.message);
        saved=findViewById(R.id.saved_note); queue=findViewById(R.id.queue); result=findViewById(R.id.result);
        save=findViewById(R.id.save_note); send=findViewById(R.id.queue_message); refresh=findViewById(R.id.refresh);
        pairingKey=findViewById(R.id.pairing_key); generateKey=findViewById(R.id.generate_key);
        approvePairing=findViewById(R.id.approve_pairing); revokePairing=findViewById(R.id.revoke_pairing);
        ip=findViewById(R.id.ip); port=findViewById(R.id.port);
        connect=findViewById(R.id.connect); listen=findViewById(R.id.listen); disconnect=findViewById(R.id.disconnect);
        connectionStatus=findViewById(R.id.connection_status); inbox=findViewById(R.id.inbox);
        ble=new BlePanel(this,()->connection,()->peer.getText().toString().trim());
        chooseFile=findViewById(R.id.choose_file); refreshFiles=findViewById(R.id.refresh_files); showSources=findViewById(R.id.show_sources);
        incomingFiles=findViewById(R.id.incoming_files); outgoingFiles=findViewById(R.id.outgoing_files); sources=findViewById(R.id.sources);
        fileTarget=state==null?null:state.getString("fileTarget");
        String restoredPeer=state==null?null:state.getString("selectedPeer");
        chooseFile.setOnClickListener(view->selectFile());
        refreshFiles.setOnClickListener(view->{ String target=peer.getText().toString().trim(); if(connection!=null) connection.requestFiles(target,true); refreshSnapshot(); });
        showSources.setOnClickListener(view->work(()->{ List<String> ids=sdk.sourceSnapshots(); ui(()->renderSources(ids)); }));
        connect.setOnClickListener(view->startConnection(false)); listen.setOnClickListener(view->startConnection(true));
        disconnect.setOnClickListener(view->{ if(connection!=null) connection.disconnect(); });
        pairingKey.setSaveEnabled(false); pairingKey.setSaveFromParentEnabled(false);
        pairingKey.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        pairingKey.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        generateKey.setOnClickListener(view->work(()->{
            byte[] bytes=dev.podjs.runtime.PodSyncSession.newChallenge(); StringBuilder hex=new StringBuilder(64);
            try { for(byte value:bytes) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255)); }
            finally { java.util.Arrays.fill(bytes,(byte)0); }
            String code=hex.toString(); ui(()->{ if(foreground) { pairingKey.setText(code); result.setText("仅在可信的线下渠道与目标设备共享此密钥，然后双方分别确认配对。不要发送到普通局域网或日志。"); } });
        }));
        approvePairing.setOnClickListener(view->confirmPairing(false));
        revokePairing.setOnClickListener(view->confirmPairing(true));
        peer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)}); message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        save.setOnClickListener(view->{ String text=note.getText().toString(); work(()->{ sdk.setState("note",text); if(connection!=null) connection.requestState(); ui(()->{ saved.setText("本机已保存："+text); result.setText("笔记已保存到本机。"); }); }); });
        refresh.setOnClickListener(view->{ String target=peer.getText().toString().trim(); work(()->renderQueue(target)); });
        send.setOnClickListener(view->{ String target=peer.getText().toString().trim(), text=message.getText().toString();
            if(text.trim().isEmpty()) { result.setText("先填写消息内容。"); return; }
            work(()->{ long now=System.currentTimeMillis(); sdk.sendMessage(target,new JSONObject().put("text",text).toString().getBytes(StandardCharsets.UTF_8),now+86400000L,false,now);
                if(connection!=null) connection.requestMessages(target);
                renderQueue(target); ui(()->{ message.setText(""); result.setText("已加入离线队列，尚未送达。"); }); });
        });
        work(()->{
            android.content.SharedPreferences preferences=getSharedPreferences("identity",MODE_PRIVATE);
            String local=preferences.getString("localId",null);
            if(local==null) { local="device-"+UUID.randomUUID(); if(!preferences.edit().putString("localId",local).commit()) throw new java.io.IOException("无法保存本机身份"); }
            PodCompanion created=new PodCompanion(getApplicationContext(),"dev.podjs.companion.example",local);
            try {
                SelectedDocument.recoverStaging(getCacheDir());
                connection=new ExampleConnection(created,getMainExecutor(),this::connectionChanged); sdk=created; localId=local;
            } catch(Exception error) { try { created.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); } throw error; }
            Object current=sdk.getState("note"); String text=current==null?"":current.toString(), device=local, target=restoredPeer==null?preferences.getString("peer","watch"):restoredPeer;
            ui(()->{ connection.setForeground(foreground); ((TextView)findViewById(R.id.device)).setText("本机 ID\n"+device); peer.setText(target); note.setText(text); saved.setText(text.isEmpty()?"尚未保存笔记":"本机已保存："+text); });
            renderQueue(target);
            sdk.subscribeState(getMainExecutor(),this::refreshSnapshot);
            sdk.subscribeMessages(getMainExecutor(),this::refreshSnapshot);
            sdk.subscribeFiles(getMainExecutor(),this::refreshSnapshot);
        });
    }
    private void startConnection(boolean accepting) {
        try {
            String target=peer.getText().toString().trim();
            java.net.InetAddress address=android.net.InetAddresses.parseNumericAddress(ip.getText().toString().trim());
            int number=Integer.parseInt(port.getText().toString().trim());
            if(number<0 || number>65535) throw new IllegalArgumentException("Port out of range");
            connection.start(target,new java.net.InetSocketAddress(address,number),accepting);
            enableActions(!workBusy && sdk!=null);
        } catch(Exception error) { result.setText("无法开始：填写已配对设备 ID、数字 IP 和 0–65535 端口；连接对方时端口不能为 0。"); }
    }
    private void connectionChanged(ExampleConnection.State state) {
        if(destroyed) return;
        if(ble!=null) ble.connectionChanged(state);
        String text;
        switch(state.phase) {
            case CONNECTING: text="正在建立连接："+state.peer+"\n"+state.detail; break;
            case LISTENING: text="等待已配对设备："+state.peer+"\n监听 "+state.detail+"（30 秒内）"; break;
            case CONNECTED: text="已认证连接："+state.peer+"\n"+state.detail; break;
            case FAILED: text=state.detail; break;
            case STOPPED: text="已断开，持久数据保留"+(state.detail.equals("deadline")?"（本轮时间已到）":""); break;
            default: text="尚未连接";
        }
        connectionStatus.setText(text); enableActions(!workBusy && sdk!=null); refreshSnapshot();
    }
    /** Notifications wake one coalesced read; editor drafts are never overwritten. */
    private void refreshSnapshot() {
        if(destroyed || sdk==null) return;
        if(refreshQueued) { refreshAgain=true; return; }
        refreshQueued=true; String target=peer.getText().toString().trim();
        io.execute(()->{
            try {
                Object current=sdk.getState("note"); String value=current==null?"尚未保存笔记":"本机已保存："+current;
                List<dev.podjs.runtime.PodSyncInbox.Message> deliveries=sdk.receivedMessages(System.currentTimeMillis());
                List<PodSyncOutbox.Message> pending=sdk.pendingMessages(target,System.currentTimeMillis());
                List<dev.podjs.runtime.PodSyncIncomingFiles.Offer> received=sdk.incomingFiles();
                List<dev.podjs.runtime.PodSyncOutgoingFiles.Status> sent=sdk.outgoingFiles(target);
                ui(()->{ saved.setText(value); queue.setText(queueText(target,pending)); renderInbox(deliveries); renderFiles(received,sent); });
            } catch(Exception error) { ui(()->result.setText("刷新未完成，本机持久数据保留。")); }
            finally { ui(()->{ refreshQueued=false; if(refreshAgain) { refreshAgain=false; refreshSnapshot(); } }); }
        });
    }
    private void selectFile() {
        String target=peer.getText().toString().trim();
        if(!target.matches("[A-Za-z0-9_.:-]{1,128}") || target.equals(localId)) { result.setText("先填写另一台设备的完整 ID。"); return; }
        fileTarget=target;
        try { startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),SELECT_FILE); }
        catch(android.content.ActivityNotFoundException error) { fileTarget=null; result.setText("此设备没有系统文件选择器；可在手机端选择后发送到此设备。"); }
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("fileTarget",fileTarget); state.putString("selectedPeer",peer.getText().toString()); super.onSaveInstanceState(state); }
    @Override protected void onActivityResult(int request,int code,android.content.Intent data) {
        super.onActivityResult(request,code,data);
        if(request!=SELECT_FILE) return;
        String target=fileTarget; fileTarget=null;
        if(code!=RESULT_OK || data==null || data.getData()==null) { result.setText("未选择文件。"); return; }
        if(target==null) { result.setText("选择目标已失效，请重新选择文件。"); return; }
        importDocument(target,data.getData());
    }
    /** Package-visible so instrumentation can exercise granted ContentResolver data. */
    void importDocument(String target,android.net.Uri uri) {
        if(destroyed) return;
        final SelectedDocument selected;
        try { selected=new SelectedDocument(getContentResolver(),uri); importing=selected; }
        catch(IllegalArgumentException error) { result.setText(error.getMessage()); return; }
        work(()->{
            java.io.File spool=null; String id=null; boolean offered=false;
            try {
                spool=selected.copyTo(getCacheDir()); selected.checkActive();
                String mime=getContentResolver().getType(uri); if(mime==null) mime="application/octet-stream";
                selected.checkActive();
                id=sdk.snapshotFile(spool,mime).getString("transfer_id"); selected.checkActive();
                sdk.offerFile(target,id); offered=true; if(connection!=null) connection.requestFiles(target,false);
                ui(()->result.setText("已保存发送快照，等待对方批准。若文件选择器使连接断开，请手动重连。"));
            } finally {
                selected.close(); if(importing==selected) importing=null;
                try { if(spool!=null) java.nio.file.Files.deleteIfExists(spool.toPath()); }
                finally {
                    // A failed offer leaves no invisible orphan consuming the shared quota.
                    try { if(id!=null && !offered) sdk.releaseSource(id); }
                    finally { ui(this::refreshSnapshot); }
                }
            }
        });
    }
    private TextView fileText(String text) {
        TextView label=new TextView(this); label.setText(text); label.setTextSize(14); label.setTextColor(android.graphics.Color.rgb(22,50,79)); return label;
    }
    private Button fileButton(String title,Runnable action) {
        Button button=new Button(this); button.setText(title); button.setAllCaps(false); button.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
        button.setTextColor(getColorStateList(R.color.action_text)); button.setBackgroundTintList(getColorStateList(R.color.action_background)); button.setEnabled(!workBusy);
        button.setOnClickListener(view->action.run()); return button;
    }
    private void confirmFile(String title,String details,Work action) {
        fileDialog=new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(details).setNegativeButton("保留",null)
            .setPositiveButton("确认",(dialog,which)->work(action)).create();
        fileDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); fileDialog.show();
    }
    private void renderFiles(List<dev.podjs.runtime.PodSyncIncomingFiles.Offer> received,List<dev.podjs.runtime.PodSyncOutgoingFiles.Status> sent) {
        incomingFiles.removeAllViews(); outgoingFiles.removeAllViews();
        incomingPage=Math.min(incomingPage,Math.max(0,(received.size()-1)/10)); outgoingPage=Math.min(outgoingPage,Math.max(0,(sent.size()-1)/10));
        if(received.isEmpty()) incomingFiles.addView(fileText("暂无接收文件"));
        for(dev.podjs.runtime.PodSyncIncomingFiles.Offer offer:received.subList(incomingPage*10,Math.min(received.size(),incomingPage*10+10))) {
            String details="来自 "+offer.peerId+"\n"+offer.transferId+"\n"+offer.manifest.optLong("size")+" 字节 · "+offer.manifest.optString("mime")+"\n状态："+filePhase(offer.phase);
            incomingFiles.addView(fileText(details));
            if(offer.phase.equals("complete")) {
                Button verify=fileButton("重新验证本机文件",()->work(()->{
                    java.io.File file=sdk.completedIncomingFile(offer.peerId,offer.transferId);
                    if(file.length()!=offer.manifest.getLong("size")) throw new java.io.IOException("文件大小校验失败");
                    ui(()->result.setText("文件已校验："+offer.transferId+"\nSHA-256："+offer.manifest.optString("sha256")));
                })); verify.setTag("verify:"+offer.transferId); incomingFiles.addView(verify);
            }
            if(offer.phase.equals("offered") || offer.phase.equals("accepting")) {
                Button accept=fileButton("批准接收此文件",()->work(()->{ sdk.acceptFile(offer.peerId,offer.transferId); ui(()->result.setText("已批准。请发送端点击“查询批准并继续”，或手动重连后继续。")); }));
                accept.setTag("accept:"+offer.transferId); incomingFiles.addView(accept);
            }
            if(!offer.phase.equals("cancelled")) {
                String title=offer.phase.equals("complete")?"删除已接收文件":offer.phase.equals("offered")?"拒绝此文件":"取消并删除接收数据";
                Button cancel=fileButton(title,()->confirmFile(title,details+"\n这会删除本机接收数据，无法撤销；不删除对方的源文件。",()->sdk.cancelIncomingFile(offer.peerId,offer.transferId)));
                cancel.setTag("cancel-in:"+offer.transferId); incomingFiles.addView(cancel);
            }
        }
        filePages(incomingFiles,incomingPage,received.size(),()->{ incomingPage--; refreshSnapshot(); },()->{ incomingPage++; refreshSnapshot(); });
        if(sent.isEmpty()) outgoingFiles.addView(fileText("此目标暂无发送文件"));
        for(dev.podjs.runtime.PodSyncOutgoingFiles.Status status:sent.subList(outgoingPage*10,Math.min(sent.size(),outgoingPage*10+10))) {
            outgoingFiles.addView(fileText(status.transferId+"\n状态："+filePhase(status.phase)+(status.cancelRequested?"（已请求取消）":"")));
            if(!status.phase.equals("complete") && !status.phase.equals("cancelled")) {
                Button cancel=fileButton("取消发送",()->confirmFile("取消发送",status.transferId+"\n对方要在连接恢复并收到取消请求后才会清理接收数据。",()->{
                    sdk.cancelOutgoingFile(status.peerId,status.transferId); if(connection!=null) connection.requestFiles(status.peerId,false);
                })); cancel.setTag("cancel-out:"+status.transferId); outgoingFiles.addView(cancel);
            }
        }
        filePages(outgoingFiles,outgoingPage,sent.size(),()->{ outgoingPage--; refreshSnapshot(); },()->{ outgoingPage++; refreshSnapshot(); });
    }
    private void filePages(LinearLayout panel,int page,int size,Runnable previous,Runnable next) {
        if(size<=10) return;
        panel.addView(fileText("第 "+(page+1)+" 页，共 "+((size+9)/10)+" 页"));
        if(page>0) panel.addView(fileButton("上一页",previous));
        if((page+1)*10<size) panel.addView(fileButton("下一页",next));
    }
    private void renderSources(List<String> ids) {
        sources.removeAllViews(); if(ids.isEmpty()) sources.addView(fileText("暂无本机发送快照"));
        for(String id:ids) {
            sources.addView(fileText(id));
            Button release=fileButton("释放此本机快照",()->confirmFile("释放本机快照",id+"\n仅释放本机快照，不删除原始文件。仍有未结束传输时会拒绝。",()->{
                sdk.releaseSource(id); List<String> current=sdk.sourceSnapshots(); ui(()->renderSources(current));
            })); release.setTag("release:"+id); sources.addView(release);
        }
    }
    private static String filePhase(String phase) {
        switch(phase) {
            case "offered": return "等待本机批准";
            case "accepting": return "批准恢复中";
            case "accepted": return "已批准，接收中或等待发送端继续";
            case "offer": return "等待发送文件请求";
            case "waiting": return "等待对方批准";
            case "missing": return "核对缺少的分块";
            case "chunks": return "传输分块中";
            case "finish": return "等待最终校验";
            case "cancelling": return "取消处理中";
            case "cancelled": return "已取消";
            case "complete": return "完成，整文件校验通过";
            default: return "状态未知";
        }
    }
    private void renderInbox(List<dev.podjs.runtime.PodSyncInbox.Message> deliveries) {
        inbox.removeAllViews();
        if(deliveries.isEmpty()) { TextView empty=new TextView(this); empty.setText("暂无待确认消息"); inbox.addView(empty); return; }
        int count=0;
        for(dev.podjs.runtime.PodSyncInbox.Message delivery:deliveries) {
            if(count++==20) { TextView more=new TextView(this); more.setText("仅显示前 20 条，确认后继续查看。"); inbox.addView(more); break; }
            TextView preview=new TextView(this); preview.setTextColor(android.graphics.Color.rgb(22,50,79)); preview.setTextSize(14);
            String text=new String(delivery.payload,0,Math.min(1024,delivery.payload.length),StandardCharsets.UTF_8);
            preview.setText("来自 "+delivery.peerId+"\n"+text+(delivery.payload.length>1024?"\n（预览已截断）":"")); inbox.addView(preview);
            Button ack=new Button(this); ack.setText("确认此消息已处理"); ack.setAllCaps(false); ack.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
            ack.setTextColor(getColorStateList(R.color.action_text)); ack.setBackgroundTintList(getColorStateList(R.color.action_background));
            ack.setEnabled(!workBusy); if(count==1) ack.setId(R.id.ack_first);
            ack.setOnClickListener(view->work(()->{
                boolean online=connection!=null && connection.acknowledge(delivery);
                if(!online) sdk.ackMessage(delivery,System.currentTimeMillis());
                ui(()->result.setText(online?"确认已排队；对方收到 ACK 后才会移出发送队列。":"已在本机确认；对方将在重连后获知。"));
                ui(this::refreshSnapshot);
            })); inbox.addView(ack);
        }
    }
    private void confirmPairing(boolean revoke) {
        String target=peer.getText().toString().trim();
        if(!target.matches("[A-Za-z0-9_.:-]{1,128}") || target.equals(localId)) { result.setText("请填写另一台设备的完整 ID。"); return; }
        if(!revoke && !pairingKey.getText().toString().matches("[0-9a-fA-F]{64}")) { result.setText("密钥须为 64 位十六进制字符；使用生成按钮，或输入另一台设备生成的密钥。"); return; }
        pairingDialog=new android.app.AlertDialog.Builder(this)
            .setTitle(revoke?"撤销此设备？":"确认目标设备与密钥来源")
            .setMessage("目标："+target+(revoke?"\n关闭连接并删除本机配对凭据。离线数据不会因此删除；对方凭据须在对方撤销。":"\n请当面核对双方设备 ID，并确认密钥来自可信的线下传递。双方必须使用同一密钥、分别批准；此操作不代表已连接。已有凭据不会被替换。"))
            .setNegativeButton("取消",(dialog,which)->pairingKey.setText(""))
            .setPositiveButton(revoke?"撤销配对":"我已核对，批准配对",(dialog,which)->{
                byte[] secret=null;
                if(!revoke) {
                    String code=pairingKey.getText().toString();
                    if(!code.matches("[0-9a-fA-F]{64}")) { result.setText("密钥已清除，请重新输入。"); return; }
                    secret=new byte[32]; for(int n=0;n<32;n++) secret[n]=(byte)Integer.parseInt(code.substring(n*2,n*2+2),16);
                }
                pairingKey.setText(""); byte[] approved=secret;
                if(revoke && connection!=null) connection.disconnect();
                work(()->{ try {
                    if(revoke) sdk.revoke(target); else sdk.authorizeAfterUserApproval(target,approved);
                    ui(()->result.setText(revoke?"本机已撤销配对；对方凭据须在对方撤销。":"本机已保存批准的配对凭据。请在对方完成批准；尚未连接。"));
                } finally { if(approved!=null) java.util.Arrays.fill(approved,(byte)0); } });
            }).create();
        pairingDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        pairingDialog.setOnCancelListener(dialog->pairingKey.setText("")); pairingDialog.show();
    }
    private void renderQueue(String target) throws Exception {
        List<PodSyncOutbox.Message> pending=sdk.pendingMessages(target,System.currentTimeMillis());
        if(!getSharedPreferences("identity",MODE_PRIVATE).edit().putString("peer",target).commit()) throw new java.io.IOException("无法保存目标设备");
        String status=queueText(target,pending);
        ui(()->queue.setText(status));
    }
    private static String queueText(String target,List<PodSyncOutbox.Message> pending) {
        return pending.isEmpty()?"暂无待发送消息":"待发送 "+pending.size()+" 条"+(pending.size()==100?"（仅展示前 100 条）":"")+"\n目标："+target;
    }
    private void work(Work work) {
        if(destroyed) return;
        pendingWork++; workBusy=true; enableActions(false);
        io.execute(()->{ try { work.run(); } catch(Exception error) { ui(()->result.setText("操作未完成："+(error.getMessage()==null?"请检查设备 ID 或本机存储。":error.getMessage()))); }
            finally { ui(()->{ pendingWork--; workBusy=pendingWork>0; enableActions(sdk!=null && !workBusy); }); } });
    }
    private void enableActions(boolean ready) {
        if(ble!=null) ble.setReady(ready,foreground);
        save.setEnabled(ready); send.setEnabled(ready); refresh.setEnabled(ready);
        boolean busy=connection!=null && connection.isBusy();
        generateKey.setEnabled(ready && !busy); approvePairing.setEnabled(ready && !busy); revokePairing.setEnabled(ready);
        peer.setEnabled(ready && !busy); ip.setEnabled(ready && !busy); port.setEnabled(ready && !busy);
        connect.setEnabled(ready && !busy && foreground); listen.setEnabled(ready && !busy && foreground); disconnect.setEnabled(busy);
        chooseFile.setEnabled(ready); refreshFiles.setEnabled(ready); showSources.setEnabled(ready);
        for(int n=0;n<inbox.getChildCount();n++) if(inbox.getChildAt(n) instanceof Button) inbox.getChildAt(n).setEnabled(ready);
        for(LinearLayout panel:new LinearLayout[]{incomingFiles,outgoingFiles,sources}) for(int n=0;n<panel.getChildCount();n++) if(panel.getChildAt(n) instanceof Button) panel.getChildAt(n).setEnabled(ready);
    }
    private void ui(Runnable action) { runOnUiThread(()->{ if(!destroyed) action.run(); }); }
    @Override protected void onStart() { super.onStart(); foreground=true; if(connection!=null) { connection.setForeground(true); enableActions(!workBusy && sdk!=null); refreshSnapshot(); } }
    @Override protected void onStop() {
        foreground=false; pairingKey.setText("");
        if(ble!=null) ble.background();
        if(importing!=null) importing.close();
        if(fileDialog!=null) { fileDialog.dismiss(); fileDialog=null; }
        if(connection!=null) connection.setForeground(false);
        if(pairingDialog!=null) { pairingDialog.dismiss(); pairingDialog=null; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed=true; if(connection!=null) connection.close();
        if(ble!=null) ble.close();
        io.execute(()->{ if(connection!=null) connection.close(); if(sdk!=null) try { sdk.close(); } catch(Exception ignored) { android.util.Log.w("PodCompanionExample","Storage close failed"); } }); io.shutdown(); super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==BlePanel.PERMISSIONS && ble!=null) ble.permissionResult();
    }
}
