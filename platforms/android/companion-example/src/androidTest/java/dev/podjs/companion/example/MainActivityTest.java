package dev.podjs.companion.example;

import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class MainActivityTest {
    @Test public void recreationPreservesTargetWithoutRequiringQueueLookup() throws Exception {
        String target="selected-"+UUID.randomUUID();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.choose_file).isEnabled());
            scenario.onActivity(a->((EditText)a.findViewById(R.id.peer)).setText(target));
            scenario.recreate(); await(scenario,a->a.findViewById(R.id.choose_file).isEnabled());
            scenario.onActivity(a->assertEquals(target,((EditText)a.findViewById(R.id.peer)).getText().toString()));
        }
    }
    @Test public void pairingRequiresApprovalRejectsReplacementAndCanBeRevoked() throws Exception {
        String target="pair-"+UUID.randomUUID(); java.util.concurrent.atomic.AtomicReference<String> code=new java.util.concurrent.atomic.AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.generate_key).isEnabled());
            scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.peer)).setText(target); a.findViewById(R.id.generate_key).performClick(); });
            await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled() && ((EditText)a.findViewById(R.id.pairing_key)).length()==64);
            scenario.onActivity(a->{
                assertTrue((a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);
                code.set(((EditText)a.findViewById(R.id.pairing_key)).getText().toString());
                a.findViewById(R.id.approve_pairing).performClick(); assertTrue(a.pairingDialog.isShowing());
                a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            });
            await(scenario,a->((EditText)a.findViewById(R.id.pairing_key)).length()==0);
            approve(scenario,code.get());
            await(scenario,a->result(a).startsWith("本机已保存批准的配对凭据") && a.findViewById(R.id.approve_pairing).isEnabled());
            scenario.onActivity(a->assertEquals(0,((EditText)a.findViewById(R.id.pairing_key)).length()));
            scenario.recreate(); await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled());
            scenario.onActivity(a->((EditText)a.findViewById(R.id.peer)).setText(target));
            approve(scenario,code.get());
            await(scenario,a->result(a).contains("Pairing already exists") && a.findViewById(R.id.revoke_pairing).isEnabled());
            scenario.onActivity(a->{ a.findViewById(R.id.revoke_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
            await(scenario,a->result(a).startsWith("本机已撤销配对") && a.findViewById(R.id.approve_pairing).isEnabled());
            approve(scenario,code.get());
            await(scenario,a->result(a).startsWith("本机已保存批准的配对凭据") && a.findViewById(R.id.revoke_pairing).isEnabled());
            scenario.onActivity(a->{ a.findViewById(R.id.revoke_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
            await(scenario,a->result(a).startsWith("本机已撤销配对"));
        } finally { code.set(null); }
    }
    private static String result(MainActivity activity) { return ((TextView)activity.findViewById(R.id.result)).getText().toString(); }
    private static void approve(ActivityScenario<MainActivity> scenario,String code) {
        scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.pairing_key)).setText(code); a.findViewById(R.id.approve_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
    }
    @Test public void pairingSecretAndApprovalDoNotSurviveLeavingForeground() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.generate_key).isEnabled());
            scenario.onActivity(a->{ ((EditText)a.findViewById(R.id.peer)).setText("pair-"+UUID.randomUUID()); a.findViewById(R.id.generate_key).performClick(); });
            await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled() && ((EditText)a.findViewById(R.id.pairing_key)).length()==64);
            scenario.onActivity(a->a.findViewById(R.id.approve_pairing).performClick());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(a->{ assertEquals(0,((EditText)a.findViewById(R.id.pairing_key)).length()); assertNull(a.pairingDialog); a.findViewById(R.id.generate_key).performClick(); });
            await(scenario,a->((EditText)a.findViewById(R.id.pairing_key)).length()==64);
            scenario.recreate(); await(scenario,a->a.findViewById(R.id.approve_pairing).isEnabled());
            scenario.onActivity(a->assertEquals(0,((EditText)a.findViewById(R.id.pairing_key)).length()));
        }
    }
    private interface Check { boolean ready(MainActivity activity); }
    private void await(ActivityScenario<MainActivity> activity,Check check) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(true) { AtomicBoolean ready=new AtomicBoolean(); activity.onActivity(view->ready.set(check.ready(view))); if(ready.get()) return;
            if(System.nanoTime()>end) fail("UI update timed out"); Thread.sleep(20); }
    }
    @Test public void nativeControlsPersistNoteAndQueueWithoutClaimingDelivery() throws Exception {
        String text="离线笔记 "+UUID.randomUUID(), peer="test-"+UUID.randomUUID();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,activity->((Button)activity.findViewById(R.id.save_note)).isEnabled());
            scenario.onActivity(activity->{ ((EditText)activity.findViewById(R.id.note)).setText(text); activity.findViewById(R.id.save_note).performClick(); });
            await(scenario,activity->((TextView)activity.findViewById(R.id.saved_note)).getText().toString().contains(text) && activity.findViewById(R.id.queue_message).isEnabled());
            scenario.onActivity(activity->{ ((EditText)activity.findViewById(R.id.peer)).setText(peer); ((EditText)activity.findViewById(R.id.message)).setText("测试离线队列"); activity.findViewById(R.id.queue_message).performClick(); });
            await(scenario,activity->((TextView)activity.findViewById(R.id.result)).getText().toString().equals("已加入离线队列，尚未送达。"));
            scenario.recreate(); await(scenario,activity->activity.findViewById(R.id.refresh).isEnabled() && ((TextView)activity.findViewById(R.id.queue)).getText().toString().contains(peer));
            scenario.onActivity(activity->{ assertEquals(text,((EditText)activity.findViewById(R.id.note)).getText().toString()); assertTrue(((TextView)activity.findViewById(R.id.queue)).getText().toString().contains("待发送 1 条"));
                ((EditText)activity.findViewById(R.id.message)).setText(""); activity.findViewById(R.id.queue_message).performClick(); assertEquals("先填写消息内容。",((TextView)activity.findViewById(R.id.result)).getText().toString()); });
        }
    }
}
