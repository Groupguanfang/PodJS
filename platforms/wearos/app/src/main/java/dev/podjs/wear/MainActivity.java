package dev.podjs.wear;
import android.app.Activity; import android.os.Bundle; import android.view.MotionEvent; import dev.podjs.runtime.PodRuntimeView;
public final class MainActivity extends Activity {
  private PodRuntimeView pod;
  @Override public void onCreate(Bundle b){super.onCreate(b);pod=new PodRuntimeView(this,"wearos-watch");setContentView(pod);}
  @Override protected void onResume(){super.onResume();pod.setLifecycle(0);pod.requestFocus();}
  @Override protected void onPause(){pod.setLifecycle(1);super.onPause();}
  @Override public boolean dispatchGenericMotionEvent(MotionEvent e){return pod!=null&&pod.handleScrollMotion(e)||super.dispatchGenericMotionEvent(e);}
}
