package dev.podjs.companion.example;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.companion.PodCompanion;
import dev.podjs.companion.PodForegroundSync;
import dev.podjs.runtime.PodSyncSession;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run explicitly on a phone with DocumentsUI, not the watch's picker-less shell. */
public class MainActivityPickerTest {
    private interface Condition { boolean ready() throws Exception; }
    private interface Check { boolean ready(MainActivity activity); }
    private static void until(Condition condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!condition.ready()) { if(System.nanoTime()>end) fail("Picker condition timed out"); Thread.sleep(40); }
    }
    private static void await(ActivityScenario<MainActivity> scenario,Check check) throws Exception {
        until(()->{ AtomicBoolean ready=new AtomicBoolean(); scenario.onActivity(a->ready.set(check.ready(a))); return ready.get(); });
    }
    private static String text(MainActivity activity,int id) { return ((TextView)activity.findViewById(id)).getText().toString(); }
    private static AccessibilityNodeInfo matching(AccessibilityNodeInfo node,String value) {
        if(node==null) return null;
        if(value.contentEquals(node.getText()==null?"":node.getText()) || value.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())) return node;
        for(int n=0;n<node.getChildCount();n++) { AccessibilityNodeInfo child=node.getChild(n), found=matching(child,value); if(found!=null) return found; }
        return null;
    }
    private static boolean click(String value) {
        AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        AccessibilityNodeInfo node=matching(root,value);
        while(node!=null) { if(node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node=node.getParent(); }
        return false;
    }
    private static boolean rowContains(android.view.View view,String id,String phase) {
        if(view instanceof TextView) { String value=((TextView)view).getText().toString(); if(value.contains(id) && value.contains(phase)) return true; }
        if(view instanceof android.view.ViewGroup) { android.view.ViewGroup group=(android.view.ViewGroup)view; for(int n=0;n<group.getChildCount();n++) if(rowContains(group.getChildAt(n),id,phase)) return true; }
        return false;
    }
    @Test public void realPickerGrantsPreviouslyUnreadableDownloadAndTransfersExactBytes() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String token=UUID.randomUUID().toString(), peer="picker-"+UUID.randomUUID();
        byte[] key=PodSyncSession.newChallenge(); boolean seeded=false;
        try(PodCompanion remote=new PodCompanion(MainActivityNetworkTest.endpoint(),"dev.podjs.companion.example",peer);
            ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(scenario,a->a.findViewById(R.id.choose_file).isEnabled());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            String local=context.getSharedPreferences("identity",Context.MODE_PRIVATE).getString("localId",null);
            assertNotNull(local); remote.authorizeAfterUserApproval(local,key); MainActivityNetworkTest.approve(scenario,peer,key);
            Bundle fixture=context.getContentResolver().call(DocumentFixtureProvider.AUTHORITY,"seedDownload",token,null); seeded=true;
            assertEquals("dev.podjs.companion.example.test",fixture.getString("owner"));
            Uri source=Uri.parse(fixture.getString("uri"));
            try(android.os.ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(source,"r")) { fail("Fixture readable before picker approval"); }
            catch(SecurityException|java.io.FileNotFoundException expected) { }
            scenario.onActivity(a->a.findViewById(R.id.choose_file).performClick());
            until(()->{ AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(); return root!=null && root.getPackageName()!=null && root.getPackageName().toString().contains("documentsui"); });
            // Navigate the actual DocumentsUI roots and choose our isolated fixture.
            until(()->click("Show roots")); until(()->click("Downloads"));
            until(()->click("PodJSFixture")); until(()->click(fixture.getString("name")));
            await(scenario,a->text(a,R.id.result).startsWith("已保存发送快照") && a.findViewById(R.id.connect).isEnabled());
            try(PodForegroundSync driver=MainActivityNetworkTest.connect(scenario,remote,local)) {
                until(()->remote.pendingFileConsent().size()==1); String transfer=remote.pendingFileConsent().get(0).transferId;
                remote.acceptFile(local,transfer); scenario.onActivity(a->a.findViewById(R.id.refresh_files).performClick());
                until(()->remote.incomingFile(local,transfer).phase.equals("complete"));
                assertArrayEquals(DocumentFixtureProvider.bytes(),java.nio.file.Files.readAllBytes(remote.completedIncomingFile(local,transfer).toPath()));
                await(scenario,a->rowContains(a.findViewById(R.id.outgoing_files),transfer,"完成"));
                scenario.onActivity(a->a.findViewById(R.id.show_sources).performClick());
                await(scenario,a->a.findViewById(R.id.sources).findViewWithTag("release:"+transfer)!=null && a.findViewById(R.id.show_sources).isEnabled());
                scenario.onActivity(a->{ a.findViewById(R.id.sources).findViewWithTag("release:"+transfer).performClick(); a.fileDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->a.findViewById(R.id.sources).findViewWithTag("release:"+transfer)==null && a.findViewById(R.id.revoke_pairing).isEnabled());
                scenario.onActivity(a->{ a.findViewById(R.id.revoke_pairing).performClick(); a.pairingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); });
                await(scenario,a->text(a,R.id.result).startsWith("本机已撤销配对")); remote.cancelIncomingFile(local,transfer);
            }
            remote.revoke(local);
        } finally {
            java.util.Arrays.fill(key,(byte)0);
            if(seeded) context.getContentResolver().call(DocumentFixtureProvider.AUTHORITY,"removeDownload",token,null);
        }
    }
}
