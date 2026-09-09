package dev.podjs.runtime;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
public class PodTimerServicesTest {
 @Test public void delayUsesWallClockAndCancellationSuppressesCompletion() throws Exception {
  LinkedBlockingQueue<String> events=new LinkedBlockingQueue<>();
  PodServices services=new PodServices(InstrumentationRegistry.getInstrumentation().getTargetContext(),events::offer);
  try {
   long start=SystemClock.elapsedRealtime();
   services.dispatch(command(1,180));
   assertNull(events.poll(70,TimeUnit.MILLISECONDS));
   String event=events.poll(2,TimeUnit.SECONDS);assertNotNull(event);
   assertTrue(SystemClock.elapsedRealtime()-start>=150);assertTrue(new JSONObject(event).getBoolean("ok"));
   services.dispatch(command(2,160));
   services.dispatch(new JSONObject().put("t","service.cancel").put("id",2));
   assertNull(events.poll(250,TimeUnit.MILLISECONDS));
  } finally {services.close();}
 }
 private JSONObject command(int id,int ms)throws Exception{return new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method","runtime.delay").put("args",new JSONObject().put("milliseconds",ms));}
}
