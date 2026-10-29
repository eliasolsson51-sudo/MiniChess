package app.autodark;

import android.accessibilityservice.AccessibilityService;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns colour inversion on while an app that ignores dark mode is in front (Settings),
 * and off again for everything else. Needs WRITE_SECURE_SETTINGS, granted with adb.
 */
public class AutoDarkService extends AccessibilityService {

    static final String KEY = "accessibility_display_inversion_enabled";

    /** Apps that stay white even in dark mode. */
    static final Set<String> WHITE_APPS = new HashSet<>(Arrays.asList(
            "com.android.settings"
    ));

    /** Windows that should not change the current state (shade, volume panel, keyboards). */
    private static final Set<String> IGNORE = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "io.github.sspanak.tt9"
    ));

    private Boolean current = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        current = null;
        update();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            update();
        }
    }

    private void update() {
        String pkg = null;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
            }
        } catch (Exception ignored) {
        }
        if (pkg == null || pkg.equals(getPackageName()) || IGNORE.contains(pkg)) {
            return;
        }
        Log.d("AutoDark", "foreground: " + pkg);
        setInverted(WHITE_APPS.contains(pkg));
    }

    private void setInverted(boolean on) {
        if (current != null && current == on) return;
        try {
            Settings.Secure.putInt(getContentResolver(), KEY, on ? 1 : 0);
            current = on;
        } catch (SecurityException e) {
            Log.d("AutoDark", "no permission: run the adb pm grant command");
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        // Never leave the phone inverted when the service stops.
        try {
            Settings.Secure.putInt(getContentResolver(), KEY, 0);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
