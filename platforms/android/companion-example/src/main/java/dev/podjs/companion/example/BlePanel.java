package dev.podjs.companion.example;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.*;
import dev.podjs.runtime.PodBleDiscovery;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Example UI policy: permission never implies a pending action, and discovering
 * an address never approves an application peer. Owned work ends on foreground exit. */
final class BlePanel implements AutoCloseable {
    static final int PERMISSIONS=61;
    static final class Row {
        final String address; final int rssi;
        Row(String address,int rssi) { this.address=address; this.rssi=rssi; }
    }
    interface ScanSource extends AutoCloseable { List<Row> scan() throws Exception; }
    private final Activity activity;
    private final Supplier<ExampleConnection> connection;
    private final Supplier<String> peer;
    private final Button permissions,scan,cancel,connect,listen;
    private final EditText address;
    private final TextView status;
    private final LinearLayout results;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->new Thread(task,"podjs-example-ble-scan"));
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor(task->new Thread(task,"podjs-example-ble-stop"));
    private ScanSource scanning;
    private boolean ready,active,destroyed,requesting,bleAttempt;
    private long generation;
    Supplier<ScanSource> sources;
    Supplier<String[]> missingPermissions=this::missing;
    BlePanel(Activity activity,Supplier<ExampleConnection> connection,Supplier<String> peer) {
        this.activity=activity; this.connection=connection; this.peer=peer;
        permissions=activity.findViewById(R.id.ble_permissions); scan=activity.findViewById(R.id.ble_scan); cancel=activity.findViewById(R.id.ble_cancel);
        connect=activity.findViewById(R.id.ble_connect); listen=activity.findViewById(R.id.ble_listen);
        address=activity.findViewById(R.id.ble_address); status=activity.findViewById(R.id.ble_status); results=activity.findViewById(R.id.ble_results);
        address.setSaveEnabled(false); address.setSaveFromParentEnabled(false);
        sources=()->{
            PodBleDiscovery discovery=new PodBleDiscovery(activity.getApplicationContext());
            return new ScanSource() {
                public List<Row> scan() throws Exception {
                    List<Row> found=new ArrayList<>(); for(PodBleDiscovery.Entry entry:discovery.scan(5000)) found.add(new Row(entry.device.getAddress(),entry.rssi)); return found;
                }
                public void close() { discovery.close(); }
            };
        };
        permissions.setOnClickListener(view->{
            String[] missing=missingPermissions.get();
            if(missing.length==0) { status.setText("权限已具备。选择扫描、连接或等待；不会自动开始。"); return; }
            requesting=true; update(); activity.requestPermissions(missing,PERMISSIONS);
        });
        scan.setOnClickListener(view->scan()); cancel.setOnClickListener(view->{ stop(); status.setText("扫描已取消，未连接设备。"); });
        connect.setOnClickListener(view->connect(false)); listen.setOnClickListener(view->connect(true));
    }
    private String[] missing() {
        String[] needed=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE}
            :new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing=new ArrayList<>(); for(String permission:needed) if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) missing.add(permission);
        return missing.toArray(new String[0]);
    }
    void permissionResult() {
        requesting=false; if(destroyed) return;
        status.setText(missingPermissions.get().length==0?"权限已具备，请重新选择操作。":"权限未全部允许。可再次请求或在系统设置中允许；不会自动开始。"); update();
    }
    private boolean permitted() {
        if(missingPermissions.get().length==0) return true;
        status.setText("先点击“检查并请求蓝牙权限”。Android 11 扫描还需要系统定位服务开启。"); return false;
    }
    private void scan() {
        if(!ready || !active || destroyed || scanning!=null || !permitted()) return;
        results.removeAllViews(); address.setText(""); status.setText("正在扫描 PodJS 服务（5 秒），发现不代表已配对。");
        long token=++generation; ScanSource source=sources.get(); scanning=source; update();
        worker.execute(()->{
            List<Row> found=null; Exception failure=null;
            try { found=source.scan(); } catch(Exception error) { failure=error; }
            finally { try { source.close(); } catch(Exception ignored) { } }
            List<Row> snapshot=found; Exception error=failure;
            activity.runOnUiThread(()->{
                if(destroyed || !active || generation!=token) return;
                scanning=null;
                status.setText(error==null?(snapshot.isEmpty()?"未发现 PodJS 广播。检查另一端等待模式、距离与系统定位服务。":"选择蓝牙地址，再核对上方的应用设备 ID；尚未认证。"):
                    "扫描未完成：检查蓝牙开关、权限及系统定位服务。不会自动重试。");
                if(error==null) for(Row row:snapshot) {
                    Button choose=new Button(activity); choose.setText(row.address+"\n信号 "+row.rssi+" dBm"); choose.setAllCaps(false);
                    choose.setMinHeight((int)(48*activity.getResources().getDisplayMetrics().density));
                    choose.setTextColor(activity.getColorStateList(R.color.action_text)); choose.setBackgroundTintList(activity.getColorStateList(R.color.action_background));
                    choose.setOnClickListener(view->{ address.setText(row.address); status.setText("已选择蓝牙地址，尚未连接或批准配对。"); }); results.addView(choose);
                }
                update();
            });
        });
    }
    private void connect(boolean accepting) {
        if(!ready || !active || destroyed || scanning!=null || !permitted()) return;
        String radio=address.getText().toString().trim().toUpperCase(Locale.ROOT), target=peer.get();
        if(!accepting && !BluetoothAdapter.checkBluetoothAddress(radio)) { status.setText("选择或填写完整蓝牙地址，例如 AA:BB:CC:DD:EE:FF。"); return; }
        try {
            BluetoothManager manager=activity.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null || !adapter.isEnabled()) { status.setText("蓝牙不可用或尚未开启。请在系统设置中开启后重试。"); return; }
            connection.get().startBle(activity.getApplicationContext(),target,accepting?null:adapter.getRemoteDevice(radio),accepting);
            bleAttempt=true;
            status.setText(accepting?"等待首条无线连接并验证目标设备，最多 30 秒；认证失败即关闭。":"正在连接并验证应用配对，最多 30 秒。"); update();
        } catch(Exception error) { status.setText("BLE 未开始：检查设备 ID、权限和现有连接状态。"); }
    }
    void setReady(boolean ready,boolean active) { this.ready=ready; this.active=active; update(); }
    void connectionChanged(ExampleConnection.State state) {
        if(!bleAttempt || destroyed) return;
        if(state.phase==ExampleConnection.Phase.FAILED) { status.setText(state.detail); bleAttempt=false; }
        else if(state.phase==ExampleConnection.Phase.CONNECTED) status.setText(state.detail);
        else if(state.phase==ExampleConnection.Phase.STOPPED) { status.setText("BLE 已断开，持久数据保留；不会自动重连。"); bleAttempt=false; }
    }
    private void update() {
        if(destroyed) return;
        boolean available=ready && active && !requesting;
        ExampleConnection current=connection.get(); boolean idle=current!=null && !current.isBusy();
        permissions.setEnabled(available && scanning==null && idle); scan.setEnabled(available && scanning==null && idle);
        connect.setEnabled(available && scanning==null && idle); listen.setEnabled(available && scanning==null && idle);
        cancel.setEnabled(active && scanning!=null); address.setEnabled(available && scanning==null && idle);
        for(int i=0;i<results.getChildCount();i++) results.getChildAt(i).setEnabled(available && idle);
    }
    void stop() {
        ++generation; ScanSource previous=scanning; scanning=null; results.removeAllViews(); address.setText("");
        if(previous!=null) cleanup.execute(()->{ try { previous.close(); } catch(Exception ignored) { } }); update();
    }
    void background() { active=false; stop(); status.setText("离开前台已清除扫描结果；返回后手动开始。"); }
    @Override public void close() { if(destroyed) return; background(); destroyed=true; worker.shutdown(); cleanup.shutdown(); }
}
