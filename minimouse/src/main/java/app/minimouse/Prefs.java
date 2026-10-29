package app.minimouse;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Settings shared by the service and the app screen. */
final class Prefs {

    static final String FILE = "minimouse";
    static final String KEY_EXCLUDED = "excluded";

    /** Apps that already work with the D-pad, paused by default. */
    private static final Set<String> DEFAULT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "app.inkos",
            "com.android.launcher3",
            "com.chess"            // MiniChess handles chess.com
    ));
    private static final String[] DEFAULT_NAME_HINTS = {
            "podlp", "minispot", "clock", "klocka", "contacts", "kontakter"
    };

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Apps where the cursor pauses itself. Filled with sensible defaults the first time. */
    static Set<String> excluded(Context c) {
        SharedPreferences p = get(c);
        if (!p.contains(KEY_EXCLUDED)) {
            Set<String> d = defaults(c);
            p.edit().putStringSet(KEY_EXCLUDED, d).apply();
            return new HashSet<>(d);
        }
        return new HashSet<>(p.getStringSet(KEY_EXCLUDED, new HashSet<>()));
    }

    static void setExcluded(Context c, Set<String> set) {
        get(c).edit().putStringSet(KEY_EXCLUDED, new HashSet<>(set)).apply();
    }

    static List<ResolveInfo> launcherApps(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        return c.getPackageManager().queryIntentActivities(i, 0);
    }

    private static Set<String> defaults(Context c) {
        Set<String> d = new HashSet<>(DEFAULT_PACKAGES);
        PackageManager pm = c.getPackageManager();
        try {
            for (ResolveInfo ri : launcherApps(c)) {
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                String lp = pkg.toLowerCase(Locale.ROOT);
                for (String hint : DEFAULT_NAME_HINTS) {
                    if (label.contains(hint) || lp.contains(hint)) {
                        d.add(pkg);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return d;
    }
}
