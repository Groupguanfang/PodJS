package dev.podjs.runtime;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.app.Dialog;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.EnumMap;
import java.util.Map;
import com.google.zxing.DecodeHintType;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import static org.junit.Assert.*;
public final class PodQrOverlayTest {
 @Test public void extractedQrIsEnlargedAndCancelReleasesOverlay() throws Exception {
  Instrumentation i=InstrumentationRegistry.getInstrumentation();VideoTestActivity activity=(VideoTestActivity)i.startActivitySync(new Intent(i.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=i.getContext().getAssets().open("qr-test.png")){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)bytes.write(b,0,n);}
  String data="data:image/png;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
  String expectedPayload=decode(BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size()));
  PodBrowserAuthServices service=new PodBrowserAuthServices(activity);CountDownLatch cancelled=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();AtomicReference<String> failureCode=new AtomicReference<>();
  try(ServerSocket socket=new ServerSocket(0)){
   Thread server=new Thread(()->{try(Socket client=socket.accept()){client.setSoTimeout(3000);client.getInputStream().read(new byte[4096]);byte[] body=("<html><body><img src='"+data+"'></body></html>").getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}catch(Exception ignored){}});server.start();
   String origin="http://127.0.0.1:"+socket.getLocalPort();service.authorize(new JSONObject().put("url",origin).put("cookieOrigin",origin).put("requiredCookieNames",new org.json.JSONArray().put("sessionid")).put("qrExtractionScript","(function(){var i=document.querySelector('img');return i?i.src:null;})()"),new PodBrowserAuthServices.Callback(){public void complete(JSONObject value){callbacks.incrementAndGet();cancelled.countDown();}public void fail(String code,String message){failureCode.set(code);callbacks.incrementAndGet();cancelled.countDown();}});
   Field field=PodBrowserAuthServices.class.getDeclaredField("qrOverlay");field.setAccessible(true);long end=System.currentTimeMillis()+10000;final boolean[] ready={false};
   while(!ready[0]&&System.currentTimeMillis()<end){i.runOnMainSync(()->{try{ImageView v=(ImageView)field.get(service);if(v!=null&&v.getVisibility()==View.VISIBLE&&v.getDrawable()!=null){float[] m=new float[9];v.getImageMatrix().getValues(m);float shown=v.getDrawable().getIntrinsicWidth()*m[Matrix.MSCALE_X];ready[0]=shown>=280&&shown<=321;}}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(60);}
   assertTrue("QR was not enlarged with an integer module scale inside the round safe square",ready[0]);
   i.runOnMainSync(()->{try{
    ImageView overlay=(ImageView)field.get(service);Bitmap shown=((BitmapDrawable)overlay.getDrawable()).getBitmap();assertEquals(expectedPayload,decode(shown));
    int[] location=new int[2];overlay.getLocationOnScreen(location);float[] imageMatrix=new float[9];overlay.getImageMatrix().getValues(imageMatrix);float left=location[0]+imageMatrix[Matrix.MTRANS_X],top=location[1]+imageMatrix[Matrix.MTRANS_Y];float right=left+shown.getWidth()*imageMatrix[Matrix.MSCALE_X],bottom=top+shown.getHeight()*imageMatrix[Matrix.MSCALE_Y];
    float centerX=233,centerY=233;assertTrue(Math.hypot(left-centerX,top-centerY)<=230);assertTrue(Math.hypot(right-centerX,top-centerY)<=230);assertTrue(Math.hypot(left-centerX,bottom-centerY)<=230);assertTrue(Math.hypot(right-centerX,bottom-centerY)<=230);
    Field dialogField=PodBrowserAuthServices.class.getDeclaredField("dialog");dialogField.setAccessible(true);Dialog dialog=(Dialog)dialogField.get(service);View decor=dialog.getWindow().getDecorView();
    TextView done=findText(decor,"完成"),cancel=findText(decor,"取消");assertNotNull(done);assertNotNull(cancel);assertEquals(40,done.getHeight());assertEquals(40,cancel.getHeight());assertEquals(0,done.getPaddingTop());assertTrue(done.getTextSize()<=20);
    Bitmap screenshot=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(screenshot));
    File output=new File(i.getTargetContext().getFilesDir(),"pod-browser-auth-qr-overlay.png");try(FileOutputStream out=new FileOutputStream(output)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();
   }catch(Exception e){throw new RuntimeException(e);}});
   service.cancel();assertTrue(cancelled.await(3,TimeUnit.SECONDS));i.waitForIdleSync();assertEquals("cancelled",failureCode.get());assertEquals(1,callbacks.get());assertNull(field.get(service));server.join(1000);
  }finally{service.close();i.runOnMainSync(activity::finish);}
 }
 private static String decode(Bitmap bitmap)throws Exception{int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.PURE_BARCODE,Boolean.TRUE);return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels))),hints).getText();}
 private static TextView findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return(TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){TextView found=findText(group.getChildAt(n),text);if(found!=null)return found;}}return null;}
}
