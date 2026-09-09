package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PodMediaServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void stateAndUnsupportedMethodsAreAsync() throws Exception {
        PodMediaServices services = new PodMediaServices(context());
        Result state = dispatch(services, "audio.state", new JSONObject());
        assertNull(state.error);
        assertEquals("idle", state.value.getString("state"));
        Result unsupported = dispatch(services, "video.start", new JSONObject());
        assertEquals("unsupported_method", unsupported.error);
        services.close();
    }

    @Test public void relativeMediaPathIsPrivateAndTraversalRejected() throws Exception {
        File root = new File(context().getFilesDir(), "podjs/files");
        File sample = new File(root, "media-test.raw");
        try (FileOutputStream out = new FileOutputStream(sample)) { out.write(new byte[] { 0, 1, 2, 3 }); }
        PodMediaServices services = new PodMediaServices(context());
        Result absolute = dispatch(services, "audio.start", new JSONObject().put("url", sample.getAbsolutePath()));
        assertEquals("invalid_argument", absolute.error);
        Result traversal = dispatch(services, "audio.start", new JSONObject().put("url", "../media-test.raw"));
        assertNotNull(traversal.error);
        services.close();
        assertTrue(sample.delete());
    }

    private static Result dispatch(PodMediaServices services, String method, JSONObject args) throws Exception {
        Result result = new Result();
        CountDownLatch latch = new CountDownLatch(1);
        services.dispatch(method, args, new PodMediaServices.Callback() {
            @Override public void complete(JSONObject value) { result.value = value; latch.countDown(); }
            @Override public void fail(String code, String message) { result.error = code; result.message = message; latch.countDown(); }
        });
        assertTrue("callback timeout", latch.await(5, TimeUnit.SECONDS));
        return result;
    }
    private static final class Result { JSONObject value; String error; String message; }
}
