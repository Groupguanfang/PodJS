package dev.podjs.runtime;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class PodAccessibilityTest {
    @Test public void valueDoesNotMaskMixedExpandedOrBusyAndStateTextIsLocalized() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.content.Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());
            configuration.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            View view=new View(base.createConfigurationContext(configuration));
            PodAccessibility provider=new PodAccessibility(view,(id,hash,action)->false);
            String snapshot=new String(tree(64|8|128|16|32,"Choice"),StandardCharsets.UTF_8).replace("\"label\":\"Choice\"","\"label\":\"Choice\",\"value\":\"2/3\"");
            provider.update(snapshot.getBytes(StandardCharsets.UTF_8),new long[]{1,200,100});
            AccessibilityNodeInfo info=provider.createAccessibilityNodeInfo(2);
            assertTrue(info.isCheckable());assertFalse(info.isChecked());
            assertEquals("2/3, 部分选中, 已展开, 正忙",info.getStateDescription());
            provider.update(tree(128,"Choice"),new long[]{2,200,100});
            assertEquals("已收起",provider.createAccessibilityNodeInfo(2).getStateDescription());
            provider.update(tree(64|4,"Choice"),new long[]{3,200,100});
            assertEquals("已选中",provider.createAccessibilityNodeInfo(2).getStateDescription());
            provider.update(tree(64,"Choice"),new long[]{4,200,100});
            assertEquals("未选中",provider.createAccessibilityNodeInfo(2).getStateDescription());
            provider.update(tree(0,"Choice"),new long[]{3,200,100});
            assertNull(provider.createAccessibilityNodeInfo(2).getStateDescription());
        });
    }
    private static Object nativeCall(String name, Class<?>[] types, Object... arguments) throws Exception {
        java.lang.reflect.Method method=PodRuntimeView.class.getDeclaredMethod(name,types);
        method.setAccessible(true);return method.invoke(null,arguments);
    }
    private static String hash(byte[] bytes) {
        long result=0xcbf29ce484222325L;
        for(byte value:bytes){result^=value&255;result*=0x100000001b3L;}
        return String.format(java.util.Locale.ROOT,"%016x",result);
    }
    @Test public void nativeSemanticActionCrossesJniAndGuestFrameBeforeNewSnapshot() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getContext();
        org.json.JSONObject manifest;
        try(java.io.InputStream input=context.getAssets().open("semantic-host.json")) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
            manifest=new org.json.JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        byte[] pak="pak".getBytes(StandardCharsets.UTF_8);
        byte[] js=("globalThis.n=ui.createNode(0);ui.setProp(n,1,80);ui.setProp(n,2,40);ui.setAccessibility(n,'批准😀',1,null,null,512,0);ui.insertBefore(1,n,0);"+
            "globalThis.frame=()=>{for(const e of JSON.parse(pod.takeEvents()||'[]'))if(e.t==='accessibility.action'&&e.nodeId===n&&e.action==='activate')ui.setAccessibility(n,'完成😀',1,null,null,513,0);};").getBytes(StandardCharsets.UTF_8);
        manifest.put("target","android-watch").put("hostAbi",2).put("pakHash",hash(pak)).put("bundleHash",hash(js));
        byte[] manifestBytes=manifest.toString().getBytes(StandardCharsets.UTF_8);
        final Throwable[] failure={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            android.graphics.SurfaceTexture texture=new android.graphics.SurfaceTexture(false);
            texture.setDefaultBufferSize(240,240);android.view.Surface surface=new android.view.Surface(texture);
            long runtime=0;
            try {
                java.io.File directory=new java.io.File(context.getCacheDir(),"semantic-jni-"+java.util.UUID.randomUUID());assertTrue(directory.mkdir());
                runtime=(Long)nativeCall("nativeCreate",new Class<?>[]{android.view.Surface.class,String.class,int.class,int.class,float.class,String.class},surface,"android-watch",240,240,1f,directory.getAbsolutePath());
                assertNotEquals(0,runtime);
                // Snapshot/action verification does not need presentation buffers.
                nativeCall("nativeDetachSurface",new Class<?>[]{long.class},runtime);
                nativeCall("nativeBoot",new Class<?>[]{long.class,byte[].class,byte[].class,byte[].class},runtime,pak,js,manifestBytes);
                nativeCall("nativeAccessibilityEnabled",new Class<?>[]{long.class,boolean.class},runtime,true);
                nativeCall("nativeFrame",new Class<?>[]{long.class},runtime);
                long[] metadata=new long[3];
                byte[] first=(byte[])nativeCall("nativeAccessibilitySnapshot",new Class<?>[]{long.class,long[].class},runtime,metadata);
                assertNotNull(first);org.json.JSONObject node=new org.json.JSONObject(new String(first,StandardCharsets.UTF_8)).getJSONArray("nodes").getJSONObject(0);
                assertEquals("批准😀",node.getString("label"));int id=node.getInt("id");long originalHash=metadata[0];
                final long actionRuntime=runtime;
                View view=new View(context);view.layout(0,0,240,240);
                PodAccessibility provider=new PodAccessibility(view,(nodeId,contentHash,action)-> {
                    try {return (Boolean)nativeCall("nativeAccessibilityAction",new Class<?>[]{long.class,int.class,long.class,int.class},actionRuntime,nodeId,contentHash,action);}
                    catch(Exception error){throw new AssertionError(error);}
                });
                provider.update(first,metadata);
                assertFalse((Boolean)nativeCall("nativeAccessibilityAction",new Class<?>[]{long.class,int.class,long.class,int.class},runtime,id,originalHash^1,1));
                assertTrue(provider.performAction(id,AccessibilityNodeInfo.ACTION_CLICK,null));
                assertNull(nativeCall("nativeAccessibilitySnapshot",new Class<?>[]{long.class,long[].class},runtime,metadata));
                nativeCall("nativeFrame",new Class<?>[]{long.class},runtime);
                byte[] second=(byte[])nativeCall("nativeAccessibilitySnapshot",new Class<?>[]{long.class,long[].class},runtime,metadata);
                assertNotNull(second);node=new org.json.JSONObject(new String(second,StandardCharsets.UTF_8)).getJSONArray("nodes").getJSONObject(0);
                assertEquals("完成😀",node.getString("label"));assertEquals(0,node.getInt("actions"));assertNotEquals(originalHash,metadata[0]);
                provider.update(second,metadata);
                assertEquals("完成😀",provider.createAccessibilityNodeInfo(id).getContentDescription());
                assertFalse(provider.performAction(id,AccessibilityNodeInfo.ACTION_CLICK,null));
                assertFalse((Boolean)nativeCall("nativeAccessibilityAction",new Class<?>[]{long.class,int.class,long.class,int.class},runtime,id,metadata[0],1));
            } catch(Throwable error) {failure[0]=error;}
            finally {
                try {if(runtime!=0)nativeCall("nativeDestroy",new Class<?>[]{long.class},runtime);}catch(Throwable error){failure[0]=error;}
                surface.release();texture.release();
            }
        });
        if(failure[0]!=null)throw new AssertionError("JNI semantic round trip failed",failure[0]);
    }
    private static byte[] tree(int state, String label) {
        return ("{\"schema\":1,\"nodes\":[{\"id\":2,\"parentId\":0,\"role\":\"button\",\"label\":\""+label+"\",\"state\":"+state+",\"actions\":1,\"bounds\":{\"left\":10,\"top\":20,\"right\":70,\"bottom\":60}},{\"id\":3,\"parentId\":2,\"role\":\"adjustable\",\"label\":\"Volume\",\"value\":\"50%\",\"state\":2,\"actions\":6,\"bounds\":{\"left\":20,\"top\":30,\"right\":40,\"bottom\":50}}]}").getBytes(StandardCharsets.UTF_8);
    }
    @Test public void projectsBoundsUnicodeStateAndExactActionHash() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View view=new View(InstrumentationRegistry.getInstrumentation().getTargetContext());view.layout(0,0,400,200);
            long[] sent=new long[3];
            PodAccessibility provider=new PodAccessibility(view,(id,hash,action)->{sent[0]=id;sent[1]=hash;sent[2]=action;return true;});
            provider.update(tree(0,"批准😀"),new long[]{Long.MIN_VALUE+7,200,100});
            AccessibilityNodeInfo host=provider.createAccessibilityNodeInfo(-1);assertEquals(1,host.getChildCount());
            AccessibilityNodeInfo button=provider.createAccessibilityNodeInfo(2);assertEquals("批准😀",button.getContentDescription());
            assertEquals("android.widget.Button",button.getClassName());assertTrue(button.isClickable());assertEquals(1,button.getChildCount());
            Rect bounds=new Rect();button.getBoundsInParent(bounds);assertEquals(new Rect(20,40,140,120),bounds);
            AccessibilityNodeInfo adjustable=provider.createAccessibilityNodeInfo(3);adjustable.getBoundsInParent(bounds);assertEquals(new Rect(20,20,60,60),bounds);
            assertTrue(adjustable.isSelected());assertEquals("50%",adjustable.getStateDescription());
            assertTrue(provider.performAction(3,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));assertArrayEquals(new long[]{3,Long.MIN_VALUE+7,2},sent);
            assertFalse(provider.performAction(3,AccessibilityNodeInfo.ACTION_CLICK,null));
            assertTrue(provider.performAction(2,AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null));
            provider.update(tree(1,"Changed"),new long[]{8,200,100});
            assertTrue(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY).isAccessibilityFocused());
            assertFalse(provider.performAction(2,AccessibilityNodeInfo.ACTION_CLICK,null));
            provider.update("{\"schema\":1,\"nodes\":[]}".getBytes(StandardCharsets.UTF_8),new long[]{9,200,100});
            assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY));assertNull(provider.createAccessibilityNodeInfo(2));
            assertFalse(provider.performAction(2,AccessibilityNodeInfo.ACTION_CLICK,null));
        });
    }
}
