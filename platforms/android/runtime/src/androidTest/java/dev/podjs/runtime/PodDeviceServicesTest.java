package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

import static org.junit.Assert.*;

public class PodDeviceServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void secureStoreSurvivesRecreationAndDoesNotContainPlaintext() throws Exception {
        String value = "你好，PodJS 🔐";
        PodDeviceServices first = new PodDeviceServices(context());
        first.execute("secure.set", new JSONObject().put("key", "unicode").put("value", value));
        first.close();
        File store = new File(new File(context().getFilesDir(), "podjs"), "secure.store");
        assertTrue(store.isFile());
        byte[] bytes = java.nio.file.Files.readAllBytes(store.toPath());
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains(value));
        PodDeviceServices second = new PodDeviceServices(context());
        JSONObject result = second.execute("secure.get", new JSONObject().put("key", "unicode"));
        assertTrue(result.getBoolean("exists"));
        assertEquals(value, result.getString("value"));
        second.execute("secure.delete", new JSONObject().put("key", "unicode"));
        second.close();
    }

    @Test public void fileChunksAndTraversalAreBounded() throws Exception {
        PodDeviceServices services = new PodDeviceServices(context());
        String path = "test/chunk.bin";
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        services.execute("file.write", new JSONObject().put("path", path).put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP)));
        JSONObject read = services.execute("file.read", new JSONObject().put("path", path).put("maxBytes", 3));
        assertEquals("hel", new String(Base64.decode(read.getString("dataBase64"), Base64.DEFAULT), StandardCharsets.UTF_8));
        try { services.execute("file.stat", new JSONObject().put("path", "../escape")); fail("traversal accepted"); }
        catch (SecurityException expected) { }
        services.execute("file.delete", new JSONObject().put("path", path));
        services.close();
    }
}
