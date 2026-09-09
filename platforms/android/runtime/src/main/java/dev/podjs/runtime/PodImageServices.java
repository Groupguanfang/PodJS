package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Bounded system image decoding into tightly packed RGBA8888 files. */
public final class PodImageServices implements Closeable {
    public static final int MAX_DIMENSION = 512;
    private static final int MAX_SOURCE_DIMENSION = 16_384;
    private static final long MAX_SOURCE_PIXELS = 64L * 1024 * 1024;
    private final File root;
    private volatile boolean closed;

    public PodImageServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        File files = new File(context.getFilesDir(), "podjs/files");
        if (!files.exists() && !files.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = files.getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
    }

    public JSONObject execute(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if (!"image.decode".equals(method) || args == null) throw new IllegalArgumentException("unsupported_method");
        return decode(args);
    }

    private JSONObject decode(JSONObject args) throws Exception {
        File source = safeFile(required(args, "path"));
        if (!source.isFile()) throw new IOException("file_not_found");
        int maximum = args.optInt("maxDimension", MAX_DIMENSION);
        if (maximum < 1 || maximum > MAX_DIMENSION) throw new IllegalArgumentException("invalid_max_dimension");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        checkInterrupted();
        int sourceWidth = bounds.outWidth, sourceHeight = bounds.outHeight;
        if (sourceWidth < 1 || sourceHeight < 1) throw new IOException("unsupported_image");
        if (sourceWidth > MAX_SOURCE_DIMENSION || sourceHeight > MAX_SOURCE_DIMENSION
                || (long) sourceWidth * sourceHeight > MAX_SOURCE_PIXELS) {
            throw new IOException("image_too_large");
        }

        int sample = 1;
        // Keep the sampled bitmap below roughly 2x the requested edge, then do
        // one high-quality exact resize. The decoder only receives powers of two.
        while (ceilDiv(Math.max(sourceWidth, sourceHeight), sample << 1) >= maximum) sample <<= 1;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath(), options);
        if (decoded == null) throw new IOException("unsupported_image");

        Bitmap bitmap = decoded;
        File output = null;
        try {
            checkInterrupted();
            int width = decoded.getWidth(), height = decoded.getHeight();
            double scale = Math.min(1.0, Math.min((double) maximum / width, (double) maximum / height));
            int scaledWidth = Math.max(1, (int) Math.floor(width * scale));
            int scaledHeight = Math.max(1, (int) Math.floor(height * scale));
            int textureWidth = nearestPowerOfTwo(scaledWidth, maximum);
            int textureHeight = nearestPowerOfTwo(scaledHeight, maximum);
            if (width != textureWidth || height != textureHeight) {
                bitmap = Bitmap.createScaledBitmap(decoded, textureWidth, textureHeight, true);
                if (bitmap != decoded) decoded.recycle();
            }
            checkInterrupted();
            int finalWidth = bitmap.getWidth(), finalHeight = bitmap.getHeight();
            int[] pixels = new int[finalWidth * finalHeight];
            bitmap.getPixels(pixels, 0, finalWidth, 0, 0, finalWidth, finalHeight);
            File directory = new File(root, "images");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("parent_unavailable");
            output = new File(directory, "decoded-" + Long.toUnsignedString(System.nanoTime()) + ".rgba").getCanonicalFile();
            byte[] row = new byte[finalWidth * 4];
            try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
                for (int y = 0; y < finalHeight; y++) {
                    checkInterrupted();
                    for (int x = 0; x < finalWidth; x++) {
                        int pixel = pixels[y * finalWidth + x], offset = x * 4;
                        row[offset] = (byte) ((pixel >>> 16) & 0xff);
                        row[offset + 1] = (byte) ((pixel >>> 8) & 0xff);
                        row[offset + 2] = (byte) (pixel & 0xff);
                        row[offset + 3] = (byte) ((pixel >>> 24) & 0xff);
                    }
                    stream.write(row);
                }
            }
            checkInterrupted();
            if (closed) throw new IOException("service_closed");
            return new JSONObject().put("path", relative(output)).put("width", finalWidth).put("height", finalHeight);
        } catch (Exception error) {
            if (output != null && output.exists()) output.delete();
            throw error;
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File file = new File(root, path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator)) throw new SecurityException("path_outside_root");
        return file;
    }

    private String relative(File file) throws IOException { return root.toPath().relativize(file.getCanonicalFile().toPath()).toString(); }
    private static String required(JSONObject args, String key) { String value = args.optString(key, null); if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing_" + key); return value; }
    private static int ceilDiv(int value, int divisor) { return (value + divisor - 1) / divisor; }
    private static int nearestPowerOfTwo(int value, int maximum) {
        int lower = 1;
        while (lower <= maximum / 2 && lower << 1 <= value) lower <<= 1;
        int upper = lower <= maximum / 2 ? lower << 1 : lower;
        return value - lower < upper - value ? lower : upper;
    }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("decode_cancelled");
    }

    @Override public void close() {
        closed = true;
        File directory = new File(root, "images");
        File[] temporary = directory.listFiles((parent, name) -> name.startsWith("decoded-") && name.endsWith(".rgba"));
        if (temporary != null) {
            for (File output : temporary) if (output.isFile()) output.delete();
        }
    }
}
