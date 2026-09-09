package dev.podjs.runtime;

import android.net.Uri;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.DecodeHintType;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PodBrowserAuthServicesTest {
    @Test public void acceptsOnlyHttpOriginsAndSameOriginRedirects() {
        Uri start = PodBrowserAuthServices.requireWebUri("https://www.douyin.com/chat?isPopup=1", false);
        Uri origin = PodBrowserAuthServices.requireWebUri("https://www.douyin.com", true);
        assertTrue(PodBrowserAuthServices.sameOrigin(start, origin));
        assertFalse(PodBrowserAuthServices.sameOrigin(start, Uri.parse("https://sso.example.com/login")));
        assertFalse(PodBrowserAuthServices.sameOrigin(start, Uri.parse("http://www.douyin.com/")));
        try { PodBrowserAuthServices.requireWebUri("javascript:alert(1)", false); fail("javascript URL accepted"); }
        catch (IllegalArgumentException expected) { }
        try { PodBrowserAuthServices.requireWebUri("https://www.douyin.com/path", true); fail("origin path accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void requiresEveryNamedNonEmptyCookie() throws Exception {
        Set<String> required = PodBrowserAuthServices.requiredCookieNames(new JSONArray().put("sessionid").put("sid_guard"));
        assertTrue(PodBrowserAuthServices.containsRequiredCookies("theme=dark; sessionid=abc; sid_guard=def", required));
        assertFalse(PodBrowserAuthServices.containsRequiredCookies("sessionid=abc; sid_guard=", required));
        assertFalse(PodBrowserAuthServices.containsRequiredCookies("sessionid=abc", required));
    }

    @Test public void rejectsMissingOrInvalidCookieNames() throws Exception {
        assertTrue(PodBrowserAuthServices.requiredCookieNames(new JSONArray()).isEmpty());
        try { PodBrowserAuthServices.requiredCookieNames(new JSONArray().put("bad=name")); fail("invalid cookie name accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void roundWatchLayoutAssignsARealWebViewHeight() {
        PodBrowserAuthServices.LayoutSpec layout = PodBrowserAuthServices.layoutSpec(466, 466, 1f, true);
        assertEquals(18, layout.horizontalInsetPx);
        assertEquals(22, layout.verticalInsetPx);
        assertEquals(22, layout.statusHeightPx);
        assertEquals(40, layout.actionHeightPx);
        assertEquals(360, layout.webViewHeightPx);
        assertEquals(466, layout.verticalInsetPx * 2 + layout.statusHeightPx
                + layout.webViewHeightPx + layout.actionHeightPx);
        assertTrue(layout.webViewHeightPx > 0);
        PodBrowserAuthServices.LayoutSpec highDensity = PodBrowserAuthServices.layoutSpec(466, 466, 3f, true);
        assertTrue(highDensity.webViewHeightPx >= 360);
        assertTrue(layout.statusInsetPx >= 130);
        assertTrue(layout.actionInsetPx >= 130);
        assertTrue(layout.chromeTextPx <= 20);
        assertTrue(layout.qrSizePx >= 315);
        double browserCenterY = layout.verticalInsetPx + layout.statusHeightPx
                + layout.webViewHeightPx / 2.0;
        double half = layout.qrSizePx / 2.0;
        double cornerRadius = Math.hypot(half, half + Math.abs(browserCenterY - 233));
        assertTrue(cornerRadius <= 230);
    }

    @Test public void qrExtractionAcceptsOnlyBoundedPngAndJpegDataImages() {
        String png = "data:image/png;base64," + android.util.Base64.encodeToString(
                "qr".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        assertEquals("qr", new String(PodBrowserAuthServices.dataImageBytes(png), StandardCharsets.UTF_8));
        assertTrue(PodBrowserAuthServices.dataImageBytes("https://example.com/qr.png") == null);
        assertTrue(PodBrowserAuthServices.dataImageBytes("data:text/plain;base64,cXI=") == null);
        assertEquals(png, PodBrowserAuthServices.javascriptString(JSONObject.quote(png)));
        assertTrue(PodBrowserAuthServices.javascriptString("null") == null);
    }

    @Test public void qrIsDecodedAndReencodedWithExactlyOneModuleQuietZone() throws Exception {
        String payload = "https://example.test/dummy-login";
        BitMatrix sourceMatrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 317, 317);
        Bitmap source = Bitmap.createBitmap(317, 317, Bitmap.Config.ARGB_8888);
        for (int y=0;y<317;y++) for(int x=0;x<317;x++) source.setPixel(x,y,
                sourceMatrix.get(x,y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded);
        Bitmap normalized = PodBrowserAuthServices.decodeAndEncodeQr(encoded.toByteArray(), 321);
        assertTrue(normalized != null);
        assertTrue(normalized.getWidth() <= 321);
        for (int i = 0; i < normalized.getWidth(); i++) {
            assertEquals(android.graphics.Color.WHITE, normalized.getPixel(i, 0));
            assertEquals(android.graphics.Color.WHITE, normalized.getPixel(0, i));
            assertEquals(android.graphics.Color.WHITE, normalized.getPixel(i, normalized.getHeight() - 1));
            assertEquals(android.graphics.Color.WHITE, normalized.getPixel(normalized.getWidth() - 1, i));
        }
        BitMatrix natural = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.MARGIN, 1));
        int moduleScale = normalized.getWidth() / natural.getWidth();
        int firstDark = normalized.getWidth();
        for (int y=0;y<normalized.getHeight();y++) for(int x=0;x<normalized.getWidth();x++)
            if (normalized.getPixel(x,y) == android.graphics.Color.BLACK) firstDark = Math.min(firstDark, Math.min(x,y));
        assertEquals(moduleScale, firstDark);
        int[] pixels=new int[normalized.getWidth()*normalized.getHeight()];
        normalized.getPixels(pixels,0,normalized.getWidth(),0,0,normalized.getWidth(),normalized.getHeight());
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        assertEquals(payload,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(normalized.getWidth(),normalized.getHeight(),pixels))), hints).getText());
        source.recycle(); normalized.recycle();
    }
}
