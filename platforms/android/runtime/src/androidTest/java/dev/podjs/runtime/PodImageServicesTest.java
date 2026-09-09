package dev.podjs.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

public final class PodImageServicesTest {
    @Test public void decodesSmallFixtureToRgbaAndCleansOnClose() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-test.png");
        Bitmap fixture = Bitmap.createBitmap(new int[] { 0xffff0000, 0x8000ff00 }, 2, 1, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }

        PodImageServices images = new PodImageServices(context);
        JSONObject result = images.execute("image.decode", new JSONObject().put("path", "image-test.png").put("maxDimension", 512));
        assertEquals(2, result.getInt("width"));
        assertEquals(1, result.getInt("height"));
        File rgba = new File(root, result.getString("path"));
        byte[] bytes = Files.readAllBytes(rgba.toPath());
        assertEquals(8, bytes.length);
        assertEquals(255, bytes[0] & 0xff); assertEquals(0, bytes[1] & 0xff); assertEquals(0, bytes[2] & 0xff); assertEquals(255, bytes[3] & 0xff);
        assertEquals(0, bytes[4] & 0xff); assertEquals(255, bytes[5] & 0xff); assertEquals(0, bytes[6] & 0xff); assertEquals(128, bytes[7] & 0xff);
        images.close();
        assertFalse(rgba.exists());
        source.delete();
    }

    @Test public void rejectsInvalidFilesAndBoundsWithoutLeavingOutput() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File invalid = new File(root, "image-invalid.bin");
        Files.write(invalid.toPath(), new byte[] { 1, 2, 3, 4 });
        PodImageServices images = new PodImageServices(context);
        try {
            expectFailure(images, new JSONObject().put("path", "image-invalid.bin"), "unsupported_image");
            expectFailure(images, new JSONObject().put("path", "image-invalid.bin").put("maxDimension", 513), "invalid_max_dimension");
            expectFailure(images, new JSONObject().put("path", "../outside.png"), "path_outside_root");
        } finally {
            images.close(); invalid.delete();
        }
    }

    @Test public void nonPowerOfTwoFixtureProducesUploadableDimensions() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-npot-test.png");
        Bitmap fixture = Bitmap.createBitmap(3, 5, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }
        PodImageServices images = new PodImageServices(context);
        try {
            JSONObject result = images.execute("image.decode", new JSONObject().put("path", source.getName()).put("maxDimension", 5));
            assertEquals(4, result.getInt("width"));
            assertEquals(4, result.getInt("height"));
            assertEquals(4L * 4 * 4, new File(root, result.getString("path")).length());
        } finally { images.close(); source.delete(); }
    }

    @Test public void interruptedDecodeLeavesNoOutputBehind() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-cancel-test.png");
        Bitmap fixture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }
        PodImageServices images = new PodImageServices(context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { images.execute("image.decode", new JSONObject().put("path", source.getName())); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start(); worker.join();
        assertTrue(String.valueOf(failure.get()).contains("decode_cancelled"));
        File directory = new File(root, "images");
        File[] outputs = directory.listFiles((parent, name) -> name.startsWith("decoded-") && name.endsWith(".rgba"));
        assertTrue(outputs == null || outputs.length == 0);
        images.close(); source.delete();
    }

    private static void expectFailure(PodImageServices images, JSONObject args, String message) throws Exception {
        try { images.execute("image.decode", args); fail("expected " + message); }
        catch (Exception error) { assertTrue(String.valueOf(error.getMessage()).contains(message)); }
    }
}
