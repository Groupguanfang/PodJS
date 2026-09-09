package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import static org.junit.Assert.*;

public final class PodMediaCompatibilityTest {
 @Test public void rsaOaepUsesSha256ForBothDigestAndMgf() throws Exception {
  KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(1024);KeyPair pair=generator.generateKeyPair();
  String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP)+"\n-----END PUBLIC KEY-----";
  byte[] text="refresh_1700000000123".getBytes(StandardCharsets.UTF_8);
  try(PodDeviceServices service=new PodDeviceServices(InstrumentationRegistry.getInstrumentation().getTargetContext())){
   JSONObject result=service.execute("crypto.rsaOaepSha256",new JSONObject().put("publicKeyPem",pem).put("inputBase64",Base64.encodeToString(text,Base64.NO_WRAP)));
   byte[] encrypted=Base64.decode(result.getString("ciphertextBase64"),Base64.DEFAULT);assertEquals(128,encrypted.length);
   Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");cipher.init(Cipher.DECRYPT_MODE,pair.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));assertArrayEquals(text,cipher.doFinal(encrypted));
  }
 }
 @Test public void postResponseStreamsBeyondLegacyLimit() throws Exception {
  android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  byte[] body="query=RSS".getBytes(StandardCharsets.US_ASCII),payload=new byte[320*1024];java.util.Arrays.fill(payload,(byte)'x');
  AtomicReference<Throwable> error=new AtomicReference<>();AtomicReference<String> request=new AtomicReference<>();
  try(ServerSocket server=new ServerSocket(0);PodHttpServices service=new PodHttpServices(context)){
   Thread thread=new Thread(()->{try(Socket client=server.accept()){client.setSoTimeout(3000);InputStream in=client.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int n;while((n=in.read())!=-1){header.write(n);if(header.toString("US-ASCII").endsWith("\r\n\r\n"))break;if(header.size()>8192)throw new IOException("oversized headers");}request.set(header.toString("US-ASCII"));byte[] received=new byte[body.length];new DataInputStream(in).readFully(received);assertArrayEquals(body,received);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+payload.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(payload);out.flush();}catch(Throwable e){error.set(e);}});thread.start();
   File file=new File(context.getFilesDir(),"podjs/files/post-large-test.bin");
   try {JSONObject result=service.execute("http.download",new JSONObject().put("url","http://127.0.0.1:"+server.getLocalPort()).put("method","POST").put("path","post-large-test.bin").put("bodyBase64",Base64.encodeToString(body,Base64.NO_WRAP)));thread.join(3500);assertNull(error.get());assertTrue(request.get().startsWith("POST "));assertEquals(payload.length,result.getLong("size"));assertArrayEquals(payload,Files.readAllBytes(file.toPath()));}finally{file.delete();}
  }
 }
}
