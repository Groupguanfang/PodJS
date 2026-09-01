package dev.podjs.androidwatch;
import android.app.Activity; import android.os.Bundle; import dev.podjs.runtime.PodRuntimeView;
public final class MainActivity extends Activity {
  private PodRuntimeView pod;
  @Override public void onCreate(Bundle b){super.onCreate(b);pod=new PodRuntimeView(this,"android-watch");setContentView(pod);}
  @Override protected void onResume(){super.onResume();pod.setLifecycle(0);}
  @Override protected void onPause(){pod.setLifecycle(1);super.onPause();}
  @Override protected void onStop(){pod.setLifecycle(2);super.onStop();}
  @Override public void onBackPressed(){if(!pod.sendBack())super.onBackPressed();}
}
