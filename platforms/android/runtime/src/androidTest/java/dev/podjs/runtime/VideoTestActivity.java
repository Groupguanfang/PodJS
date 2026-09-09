package dev.podjs.runtime;
import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
/** Attached same-process surface host for video instrumentation. */
public final class VideoTestActivity extends Activity {
 private FrameLayout host;
 @Override protected void onCreate(Bundle state){super.onCreate(state);host=new FrameLayout(this);host.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));setContentView(host);}
 FrameLayout videoHost(){return host;}
}
