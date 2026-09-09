package dev.podjs.runtime;

import android.content.Context;
import android.view.View;
import android.view.TextureView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Device regression for the explicit backgroundPlayback lifecycle contract. */
public final class PodVideoBackgroundTest {
    @Test public void backgroundFlagControlsLifecyclePauseAndCleanup() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext(); File root=new File(c.getFilesDir(),"podjs/files"); assertTrue(root.mkdirs()||root.isDirectory()); File file=new File(root,"background-tiny.mp4");
        try(InputStream in=c.getAssets().open("tiny.mp4"); FileOutputStream out=new FileOutputStream(file)){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new android.content.Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        PodVideoServices service=new PodVideoServices(c);
        instrumentation.runOnMainSync(()->service.attachToParent(activity.videoHost()));
        instrumentation.waitForIdleSync();
        try {
            Result bg=prepare(service,file,"background-tiny.mp4",true); assertNull(bg.error);
            Thread.sleep(180); long before=state(service).getLong("positionMs"); service.pauseForLifecycle(); Thread.sleep(220); long after=state(service).getLong("positionMs"); assertTrue("background playback did not progress",after>before+40);
            Result stopped=new Result(); CountDownLatch stopLatch=new CountDownLatch(1); service.dispatch("video.stop",new JSONObject(),new PodVideoServices.Callback(){public void complete(JSONObject v){stopped.value=v;stopLatch.countDown();}public void fail(String c,String m){stopped.error=c;stopLatch.countDown();}}); assertTrue(stopLatch.await(3,TimeUnit.SECONDS)); assertNull(stopped.error); assertEquals("stopped",stopped.value.getString("state"));
            Result fg=prepare(service,file,"background-tiny.mp4",false); assertNull(fg.error); Thread.sleep(150); service.pauseForLifecycle(); JSONObject paused=state(service); assertEquals("paused",paused.getString("state")); long p1=paused.getLong("positionMs"); Thread.sleep(220); long p2=state(service).getLong("positionMs"); assertTrue("foreground playback was not paused",Math.abs(p2-p1)<80);
        } finally { service.close(); activity.finish(); instrumentation.waitForIdleSync(); assertTrue(file.delete()||!file.exists()); }
    }
    private static Result prepare(PodVideoServices s,File file,String path,boolean background)throws Exception { Result r=new Result(); CountDownLatch l=new CountDownLatch(1); s.dispatch("video.prepare",new JSONObject().put("url",path).put("autoplay",true).put("loop",true).put("muted",true).put("backgroundPlayback",background).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",16).put("height",16)),new PodVideoServices.Callback(){public void complete(JSONObject v){r.value=v;l.countDown();}public void fail(String c,String m){r.error=c;r.message=m;l.countDown();}}); assertTrue(l.await(8,TimeUnit.SECONDS)); return r; }
    private static JSONObject state(PodVideoServices s)throws Exception { Result r=new Result(); CountDownLatch l=new CountDownLatch(1); s.dispatch("video.state",new JSONObject(),new PodVideoServices.Callback(){public void complete(JSONObject v){r.value=v;l.countDown();}public void fail(String c,String m){r.error=c;l.countDown();}}); assertTrue(l.await(3,TimeUnit.SECONDS)); assertNull(r.error); return r.value; }
    private static final class Result { JSONObject value; String error,message; }
}
