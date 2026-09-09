package dev.podjs.runtime;

import android.content.Context;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Regression: backgrounding while prepare is pending must suppress autoplay. */
public final class PodVideoServicesLifecycleTest {
    @Test public void pauseDuringPrepareDoesNotAutoplayWhenReady() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext(); File root=new File(c.getFilesDir(),"podjs/files"); assertTrue(root.mkdirs()||root.isDirectory()); File dst=new File(root,"lifecycle-tiny.mp4");
        try(InputStream in=c.getAssets().open("tiny.mp4");FileOutputStream out=new FileOutputStream(dst)){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new android.content.Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        PodVideoServices s=new PodVideoServices(c);
        instrumentation.runOnMainSync(()->s.attachToParent(activity.videoHost()));
        instrumentation.waitForIdleSync(); CountDownLatch ready=new CountDownLatch(1); final String[] prepareError={null};
        try { s.dispatch("video.prepare",new JSONObject().put("url","lifecycle-tiny.mp4").put("autoplay",true).put("loop",true).put("muted",true).put("bounds",new JSONObject().put("width",16).put("height",16)),new PodVideoServices.Callback(){public void complete(JSONObject v){ready.countDown();}public void fail(String x,String y){prepareError[0]=x+":"+y;ready.countDown();}}); s.pauseForLifecycle(); assertTrue(ready.await(8,TimeUnit.SECONDS)); assertNull(prepareError[0]); Thread.sleep(150); CountDownLatch stateDone=new CountDownLatch(1); final String[] state={""}; s.dispatch("video.state",new JSONObject(),new PodVideoServices.Callback(){public void complete(JSONObject v){state[0]=v.optString("state");stateDone.countDown();}public void fail(String x,String y){stateDone.countDown();}}); assertTrue(stateDone.await(3,TimeUnit.SECONDS)); assertEquals("paused",state[0]); }
        finally { s.close(); activity.finish(); instrumentation.waitForIdleSync(); assertTrue(dst.delete()||!dst.exists()); }
    }
}
