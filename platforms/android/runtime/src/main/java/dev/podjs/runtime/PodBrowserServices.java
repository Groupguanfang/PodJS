package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import org.json.JSONObject;

/** Opens user-requested HTTP(S) URLs in the system browser. */
final class PodBrowserServices {
    private final Context context;
    PodBrowserServices(Context context) { this.context = context; }

    void open(JSONObject args) {
        String value = args.optString("url", "");
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null)
            throw new IllegalArgumentException("Browser URL must use HTTP or HTTPS");
        if (!(context instanceof Activity)) throw new IllegalStateException("Browser opening requires an Activity");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager packages = context.getPackageManager();
        if (intent.resolveActivity(packages) == null) throw new IllegalStateException("no_browser_handler");
        context.startActivity(intent);
    }
}
