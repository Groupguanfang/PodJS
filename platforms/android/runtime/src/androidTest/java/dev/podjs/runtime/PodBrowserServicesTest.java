package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.fail;

public class PodBrowserServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void rejectsNonHttpSchemes() throws Exception {
        PodBrowserServices services = new PodBrowserServices(context());
        try { services.open(new JSONObject().put("url", "file:///tmp/about.html")); fail("file URL accepted"); }
        catch (IllegalArgumentException expected) { }
        try { services.open(new JSONObject().put("url", "javascript:alert(1)")); fail("javascript URL accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsMalformedWebUrls() throws Exception {
        PodBrowserServices services = new PodBrowserServices(context());
        try { services.open(new JSONObject().put("url", "https:///missing-host")); fail("malformed URL accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
