package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PodHttpServicesTest {
    private final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private Thread server(ServerSocket socket, String response, CountDownLatch accepted, AtomicReference<String> request) {
        Thread thread = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); byte[] b = new byte[4096]; int n = client.getInputStream().read(b); if (request != null && n > 0) request.set(new String(b, 0, n, StandardCharsets.US_ASCII)); OutputStream out = client.getOutputStream(); out.write(response.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); } catch (Exception ignored) { } });
        thread.start(); return thread;
    }

    private String url(ServerSocket socket) { return "http://127.0.0.1:" + socket.getLocalPort(); }
    private File file(String name) { File root = new File(context.getFilesDir(), "podjs/files"); assertTrue(root.exists() || root.mkdirs()); return new File(root, name); }

    @Test public void streamsPayloadLargerThanLegacyChunkLimit() throws Exception {
        byte[] payload = new byte[320 * 1024]; for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        final ServerSocket socket = new ServerSocket(0); final CountDownLatch accepted = new CountDownLatch(1);
        Thread binaryServer = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); client.getInputStream().read(new byte[1024]); OutputStream out = client.getOutputStream(); out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(payload); out.flush(); } catch (Exception ignored) { } }); binaryServer.start();
        PodHttpServices services = new PodHttpServices(context); File target = file("http-large.bin");
        services.execute("http.download", new JSONObject().put("url", url(socket)).put("path", "http-large.bin"));
        assertArrayEquals(payload, Files.readAllBytes(target.toPath())); services.close(); socket.close();
    }

    @Test public void failedResponseLeavesExistingFile() throws Exception {
        File target = file("http-existing.bin"); byte[] old = "old-content".getBytes(StandardCharsets.UTF_8); Files.write(target.toPath(), old);
        ServerSocket socket = new ServerSocket(0); Thread thread = server(socket, "HTTP/1.1 500 Server Error\r\nContent-Length: 0\r\n\r\n", new CountDownLatch(1), null);
        try { new PodHttpServices(context).execute("http.download", new JSONObject().put("url", url(socket)).put("path", "http-existing.bin")); fail("500 accepted"); } catch (Exception expected) { }
        assertArrayEquals(old, Files.readAllBytes(target.toPath())); socket.close(); thread.join(1000);
    }

    @Test public void crossOriginRedirectDropsAuthorization() throws Exception {
        ServerSocket destination = new ServerSocket(0); CountDownLatch accepted = new CountDownLatch(1); AtomicReference<String> request = new AtomicReference<>();
        Thread destinationThread = server(destination, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", accepted, request);
        ServerSocket redirect = new ServerSocket(0); Thread redirectThread = server(redirect, "HTTP/1.1 302 Found\r\nLocation: " + url(destination) + "\r\nContent-Length: 0\r\n\r\n", new CountDownLatch(1), null);
        new PodHttpServices(context).execute("http.download", new JSONObject().put("url", url(redirect)).put("path", "http-redirect.bin").put("headers", new JSONObject().put("Authorization", "Bearer secret")));
        assertNotNull(request.get()); assertFalse(request.get().toLowerCase().contains("authorization:")); redirect.close(); destination.close(); redirectThread.join(1000); destinationThread.join(1000);
    }

    @Test public void cancelDisconnectsBlockedRequest() throws Exception {
        ServerSocket socket = new ServerSocket(0); CountDownLatch accepted = new CountDownLatch(1);
        Thread server = new Thread(() -> { try (Socket client = socket.accept()) { accepted.countDown(); Thread.sleep(30_000); } catch (Exception ignored) { } }); server.start();
        PodHttpServices services = new PodHttpServices(context); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread request = new Thread(() -> { try { services.execute("http.download", new JSONObject().put("requestId", 41).put("url", url(socket)).put("path", "http-cancel.bin").put("timeoutMs", 120_000)); } catch (Throwable t) { error.set(t); } }); request.start();
        assertTrue(accepted.await(3, TimeUnit.SECONDS)); assertTrue(services.cancel(41)); request.join(3000); assertFalse(request.isAlive()); assertNotNull(error.get()); assertFalse(file("http-cancel.bin").exists()); services.close(); socket.close();
    }
}
