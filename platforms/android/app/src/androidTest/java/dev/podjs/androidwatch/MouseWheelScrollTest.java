package dev.podjs.androidwatch;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class MouseWheelScrollTest {
    @Rule public final ActivityScenarioRule<MainActivity> activityRule =
        new ActivityScenarioRule<>(MainActivity.class);

    private static MotionEvent mouseWheel(float vertical) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        return MotionEvent.obtain(
            1L, 1L, MotionEvent.ACTION_SCROLL, 1,
            new MotionEvent.PointerProperties[] { properties },
            new MotionEvent.PointerCoords[] { coords },
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        );
    }

    private static long pixelHash(Bitmap bitmap) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < bitmap.getHeight(); y += 4) {
            for (int x = 0; x < bitmap.getWidth(); x += 4) {
                hash = (hash ^ bitmap.getPixel(x, y)) * 0x100000001b3L;
            }
        }
        return hash;
    }

    @Test public void simulatedMouseWheelScrollsRenderedContent() {
        SystemClock.sleep(250);
        Bitmap before = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        ActivityScenario<MainActivity> scenario = activityRule.getScenario();
        scenario.onActivity(activity -> {
            // Forty-eight simulated detents become one unscaled detent after
            // the Android/Wear OS mouse-only 1/48 conversion.
            MotionEvent event = mouseWheel(48f);
            try {
                assertTrue(activity.dispatchGenericMotionEvent(event));
            } finally {
                event.recycle();
            }
        });
        // Allow the runtime-frame handoff and the short ease-out to settle.
        SystemClock.sleep(250);
        Bitmap after = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotEquals(pixelHash(before), pixelHash(after));
        before.recycle();
        after.recycle();
    }
}
