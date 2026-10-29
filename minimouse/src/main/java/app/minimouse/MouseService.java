package app.minimouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A D-pad mouse cursor for any app.
 *
 *   Hold *       cursor on/off (not while the keyboard is open: TT9 uses it)
 *   D-pad        short press: jump to the nearest button/link in that direction
 *                (a plain step where there's nothing to jump to, e.g. over text);
 *                hold: glide freely. Pushing against the top/bottom edge scrolls.
 *   OK           finger down/up: short = tap, hold = long press.
 *                Taps the middle of the outlined element.
 *   5            long press
 *   0            double tap
 *   1            drag mode: finger down / finger up (select text, sliders, maps)
 *   7            paste into the focused text field
 *   2 / 8        previous / next post
 *   3 / 9        zoom in / out
 *   4 / 6        scroll left / right; hold = swipe in from the left / right edge
 *   Back         back in the app as normal (ends a drag)
 *
 * The cursor pauses itself while the keyboard is open and in apps picked in the app
 * screen, hides after a few idle seconds, and remembers its position per app.
 */
public class MouseService extends AccessibilityService {

    private static final long HOLD_DELAY_MS = 150;
    private static final long HOLD_INTERVAL_MS = 30;
    private static final float STEP_DP = 8f;           // one press without a jump target
    private static final float HOLD_START_DP = 6f;     // per 30 ms when held
    private static final float HOLD_RAMP_DP = 1.5f;
    private static final float HOLD_MAX_DP = 30f;
    private static final long STAR_HOLD_MS = 500;
    private static final long SIDE_HOLD_MS = 400;      // hold 4/6 = edge swipe
    private static final long EDGE_SCROLL_EVERY_MS = 260;
    private static final long IDLE_HIDE_MS = 4000;
    private static final long TARGETS_MAX_AGE_MS = 400;

    private WindowManager wm;
    private Pointer pointer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    boolean active = false;      // the user has turned the cursor on
    boolean suspended = false;   // paused: keyboard open or a paused app in front
    boolean idleHidden = false;  // hidden after a while without movement
    float x, y;
    float dp;
    private int screenW, screenH;
    private int touchSlop;

    // Foreground app, paused apps and remembered positions.
    private String currentPkg = "";
    private Set<String> excluded;
    private final Map<String, PointF> positions = new HashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> {
                if (Prefs.KEY_EXCLUDED.equals(key)) {
                    excluded = Prefs.excluded(this);
                    updateSuspended();
                }
            };

    // * long-press detection (timer based, works without key repeats).
    private boolean starDown = false;
    private boolean starConsumed = false;
    private final Runnable starHeld = () -> {
        if (!starDown || starConsumed) return;
        if (keyboardOpen()) return; // TT9 uses holding * for its own things
        starConsumed = true;
        setActive(!active);
    };

    // 4/6: short = scroll sideways, hold = swipe in from the edge.
    private int sideKey = -1;
    private boolean sideConsumed = false;
    private final Runnable sideHeld = () -> {
        if (sideKey < 0 || sideConsumed) return;
        sideConsumed = true;
        edgeSwipe(sideKey == KeyEvent.KEYCODE_4);
    };

    // Held D-pad direction.
    private int holdKey = -1;
    private int holdDx, holdDy;
    private int holdSteps = 0;
    private final Runnable holdStep = new Runnable() {
        @Override
        public void run() {
            if (holdKey < 0) return;
            holdSteps++;
            float speed = Math.min(HOLD_MAX_DP, HOLD_START_DP + holdSteps * HOLD_RAMP_DP) * dp;
            moveBy(holdDx * speed, holdDy * speed, holdDy);
            noteActivity();
            handler.postDelayed(this, HOLD_INTERVAL_MS);
        }
    };
    private long lastEdgeScroll = 0;

    // Clickable elements on screen (cached briefly).
    private static final class Target {
        final Rect r = new Rect();
        boolean editable;
    }
    private final List<Target> targets = new ArrayList<>();
    private final List<Rect> textBlocks = new ArrayList<>();
    private long targetsTime = 0;
    Rect outline = null;          // element under the cursor, drawn as an outline
    private boolean outlineEditable = false;

    // Drag mode: the "finger" stays down between gestures.
    boolean dragging = false;
    private GestureDescription.StrokeDescription dragStroke;
    private float dragX, dragY;

    // Finger held down by the OK key.
    private GestureDescription.StrokeDescription okStroke;
    private float okX, okY;
    private boolean okRevealOnly = false;

    private final Runnable idleHide = () -> {
        if (active && !dragging && okStroke == null && holdKey < 0) {
            idleHidden = true;
            updateVisibility();
        }
    };

    String toast = null;
    private final Runnable hideToast = () -> {
        toast = null;
        updateVisibility();
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dp = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        x = screenW / 2f;
        y = screenH / 2f;

        prefs = Prefs.get(this);
        excluded = Prefs.excluded(this);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        pointer = new Pointer(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(pointer, lp);
        } catch (Exception e) {
            pointer = null;
        }
        updateVisibility();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = foregroundPackage();
            if (pkg != null && !pkg.equals(currentPkg)) appChanged(pkg);
        }
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            targetsTime = 0; // screen changed: re-read clickables next time
            updateSuspended();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        stopHold();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (pointer != null) {
            try {
                wm.removeView(pointer);
            } catch (Exception ignored) {
            }
            pointer = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- apps

    private String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return null;
            String pkg = root.getPackageName().toString();
            if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return null;
            return pkg;
        } catch (Exception e) {
            return null;
        }
    }

    /** Remembers the cursor position for the old app and restores it for the new one. */
    private void appChanged(String pkg) {
        if (!currentPkg.isEmpty()) positions.put(currentPkg, new PointF(x, y));
        currentPkg = pkg;
        PointF p = positions.get(pkg);
        if (p != null) {
            x = p.x;
            y = p.y;
        } else {
            x = screenW / 2f;
            y = screenH / 2f;
        }
        outline = null;
        if (pointer != null) pointer.invalidate();
    }

    /** Pauses the cursor while typing or in a paused app; brings it back afterwards. */
    private boolean updateSuspended() {
        boolean should = active && (keyboardOpen()
                || (excluded != null && excluded.contains(currentPkg)));
        if (should != suspended) {
            suspended = should;
            if (suspended) {
                stopHold();
                if (dragging) endDrag();
            } else {
                noteActivity();
            }
            updateVisibility();
        }
        return suspended;
    }

    private boolean keyboardOpen() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------- keys

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean firstDown = down && ev.getRepeatCount() == 0;

        // Hold * = toggle. A short press still reaches the app as normal.
        if (code == KeyEvent.KEYCODE_STAR) {
            if (firstDown) {
                starDown = true;
                starConsumed = false;
                handler.removeCallbacks(starHeld);
                handler.postDelayed(starHeld, STAR_HOLD_MS);
                return false;
            }
            if (down) return starConsumed;
            starDown = false;
            handler.removeCallbacks(starHeld);
            if (starConsumed) {
                starConsumed = false;
                return true;
            }
            return false;
        }

        if (!active) return false;
        if (updateSuspended()) return false; // typing or a paused app: keys work as normal

        // Any of our keys brings a hidden cursor back.
        boolean wasHidden = idleHidden;
        if (firstDown && isMouseKey(code)) {
            noteActivity();
        }

        int dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:    dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  dy = 1;  break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1;  break;
            default: break;
        }
        if (dx != 0 || dy != 0) {
            if (firstDown) {
                // A hidden cursor first just reappears where it was.
                if (!wasHidden) shortMove(dx, dy);
                startHold(code, dx, dy);
            } else if (!down && code == holdKey) {
                stopHold();
                noteActivity();
            }
            return true;
        }

        // OK works like a finger: down while the key is down, up when it is released.
        boolean isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (isOk) {
            if (firstDown) {
                stopHold();
                okRevealOnly = wasHidden;
                if (okRevealOnly) return true; // don't tap where you can't see the cursor
                if (dragging) endDrag();
                else fingerDown();
            } else if (!down) {
                if (!okRevealOnly) fingerUp();
                okRevealOnly = false;
                noteActivity();
            }
            return true;
        }

        // 4/6: decide on release (short = scroll) or after a while (hold = edge swipe).
        if (code == KeyEvent.KEYCODE_4 || code == KeyEvent.KEYCODE_6) {
            if (firstDown) {
                sideKey = code;
                sideConsumed = false;
                handler.removeCallbacks(sideHeld);
                handler.postDelayed(sideHeld, SIDE_HOLD_MS);
            } else if (!down && code == sideKey) {
                handler.removeCallbacks(sideHeld);
                if (!sideConsumed) {
                    if (code == KeyEvent.KEYCODE_4) {
                        swipe(x, y, Math.min(screenW - 2, x + screenW * 0.35f), y, 250);
                    } else {
                        swipe(x, y, Math.max(1, x - screenW * 0.35f), y, 250);
                    }
                    targetsTime = 0;
                }
                sideKey = -1;
            }
            return true;
        }

        if (!firstDown) {
            // Swallow repeats and key-ups of the keys we handle below.
            return isMouseKey(code);
        }

        switch (code) {
            case KeyEvent.KEYCODE_5: {
                PointF p = tapPoint();
                tapAt(p.x, p.y, 700);
                return true;
            }
            case KeyEvent.KEYCODE_1:
                if (dragging) endDrag();
                else startDrag();
                return true;
            case KeyEvent.KEYCODE_2:
                smartScroll(-1);
                return true;
            case KeyEvent.KEYCODE_8:
                smartScroll(1);
                return true;
            case KeyEvent.KEYCODE_3:
                pinch(true);
                return true;
            case KeyEvent.KEYCODE_9:
                pinch(false);
                return true;
            case KeyEvent.KEYCODE_0:
                doubleTap();
                return true;
            case KeyEvent.KEYCODE_7:
                paste();
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Back works as normal in the app; it only ends a drag.
                if (dragging) {
                    endDrag();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean isMouseKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                return true;
            default:
                return false;
        }
    }

    private void setActive(boolean on) {
        if (!on && dragging) endDrag();
        active = on;
        suspended = false;
        stopHold();
        if (on) {
            noteActivity();
            updateSuspended();
        }
        if (!on) showToast("Mus av");
        else if (suspended) showToast("Mus på, pausad i den här appen");
        else showToast("Mus på  (håll * = av)");
    }

    /** Keeps the cursor visible and restarts the idle timer. */
    private void noteActivity() {
        handler.removeCallbacks(idleHide);
        if (idleHidden) {
            idleHidden = false;
            updateVisibility();
        }
        handler.postDelayed(idleHide, IDLE_HIDE_MS);
    }

    private void showToast(String msg) {
        toast = msg;
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1300);
        updateVisibility();
    }

    private void updateVisibility() {
        if (pointer == null) return;
        boolean show = cursorVisible() || toast != null;
        pointer.setVisibility(show ? View.VISIBLE : View.GONE);
        pointer.invalidate();
    }

    boolean cursorVisible() {
        return active && !suspended && !idleHidden;
    }

    private void startHold(int key, int dx, int dy) {
        stopHold();
        holdKey = key;
        holdDx = dx;
        holdDy = dy;
        holdSteps = 0;
        handler.postDelayed(holdStep, HOLD_DELAY_MS);
    }

    private void stopHold() {
        holdKey = -1;
        handler.removeCallbacks(holdStep);
    }

    // ---------------------------------------------------------------- moving

    /** Short press: jump to the nearest clickable in that direction, or take a plain step. */
    private void shortMove(int dx, int dy) {
        if (!dragging) {
            refreshTargets(false);
            if (smartHere()) {
                Rect best = null;
                float bestScore = Float.MAX_VALUE;
                float maxAlong = (dy != 0 ? screenH : screenW) * 0.45f;
                float minAlong = 4 * dp;
                Rect current = outline;
                for (Target t : targets) {
                    if (current != null && t.r.equals(current)) continue;
                    if (t.r.contains((int) x, (int) y)) continue; // the one we're on
                    float vx = t.r.exactCenterX() - x, vy = t.r.exactCenterY() - y;
                    float along = vx * dx + vy * dy;
                    if (along < minAlong || along > maxAlong) continue;
                    float across = Math.abs(vx * dy - vy * dx);
                    float score = along + 2f * across;
                    if (score < bestScore) {
                        bestScore = score;
                        best = t.r;
                    }
                }
                if (best != null) {
                    x = clampX(best.exactCenterX());
                    y = clampY(best.exactCenterY());
                    updateOutline();
                    if (pointer != null) pointer.invalidate();
                    return;
                }
            }
        }
        moveBy(dx * STEP_DP * dp, dy * STEP_DP * dp, dy);
    }

    /** Moves the cursor; pushing against the top or bottom edge scrolls the page instead. */
    private void moveBy(float ddx, float ddy, int dirY) {
        float margin = 2 * dp;
        boolean atBottom = y >= screenH - 1 - margin;
        boolean atTop = y <= margin;
        if (!dragging && ((dirY > 0 && atBottom) || (dirY < 0 && atTop))) {
            edgeScroll(dirY);
            return;
        }
        x = Math.max(0, Math.min(screenW - 1, x + ddx));
        y = Math.max(0, Math.min(screenH - 1, y + ddy));
        if (dragging) dragTo(x, y);
        else updateOutline();
        if (pointer != null) pointer.invalidate();
    }

    private void edgeScroll(int dir) {
        long now = SystemClock.uptimeMillis();
        if (now - lastEdgeScroll < EDGE_SCROLL_EVERY_MS) return;
        lastEdgeScroll = now;
        float dist = screenH * 0.18f + touchSlop;
        float cx = x;
        if (dir > 0) {
            float sy = screenH * 0.75f;
            stillSwipe(cx, sy, cx, sy - dist, 220);
        } else {
            float sy = screenH * 0.25f;
            stillSwipe(cx, sy, cx, sy + dist, 220);
        }
        targetsTime = 0;
    }

    // ---------------------------------------------------------------- clickables

    /** Smart jumping only where it helps: enough buttons around, not over a block of text. */
    private boolean smartHere() {
        if (targets.size() < 3) return false;
        for (Rect r : textBlocks) {
            if (r.contains((int) x, (int) y)) return false;
        }
        return true;
    }

    private void refreshTargets(boolean ifStaleOnly) {
        long now = SystemClock.uptimeMillis();
        if (ifStaleOnly && now - targetsTime < TARGETS_MAX_AGE_MS) return;
        if (!ifStaleOnly && now - targetsTime < 120) return;
        targetsTime = now;
        targets.clear();
        textBlocks.clear();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                int type = w.getType();
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) collect(r, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void collect(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 60 || targets.size() > 300) return;
        if (!n.isVisibleToUser()) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return;
        boolean clickable = n.isClickable() || n.isLongClickable() || n.isEditable();
        float area = (float) r.width() * r.height();
        boolean small = area < screenW * screenH * 0.5f;
        if (clickable && small && r.width() > 6 * dp && r.height() > 6 * dp) {
            Target t = new Target();
            t.r.set(r);
            t.editable = n.isEditable();
            targets.add(t);
        }
        CharSequence text = n.getText();
        if (!clickable && text != null && text.length() > 120) {
            textBlocks.add(r);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), depth + 1);
        }
    }

    /** The smallest clickable element under the cursor becomes the outlined target. */
    private void updateOutline() {
        refreshTargets(true);
        Rect best = null;
        boolean editable = false;
        if (smartHere()) {
            long bestArea = Long.MAX_VALUE;
            for (Target t : targets) {
                if (!t.r.contains((int) x, (int) y)) continue;
                long area = (long) t.r.width() * t.r.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = t.r;
                    editable = t.editable;
                }
            }
        }
        outline = best;
        outlineEditable = editable;
    }

    /** Where a tap lands: the middle of the outlined element, or the cursor itself. */
    private PointF tapPoint() {
        updateOutline();
        if (outline != null && !outlineEditable
                && (long) outline.width() * outline.height() < (long) screenW * screenH / 3) {
            return new PointF(outline.exactCenterX(), outline.exactCenterY());
        }
        return new PointF(x, y);
    }

    // ---------------------------------------------------------------- smart scroll

    /**
     * Scrolls the list under the cursor so the next post starts at the top (dir = 1),
     * or back to the start of the current / previous post (dir = -1).
     */
    private void smartScroll(int dir) {
        Rect box = new Rect();
        List<Rect> kids = new ArrayList<>();
        boolean found = findScrollable(box, kids);

        if (!found) {
            box.set(0, 0, screenW, screenH);
        }
        float top = box.top, h = box.height();
        float maxDist = h - 24 * dp;
        float dist = 0;

        if (found && !kids.isEmpty()) {
            float gap = 6 * dp;
            if (dir > 0) {
                for (Rect k : kids) {
                    if (k.top > top + gap) {
                        dist = k.top - top;
                        break;
                    }
                }
            } else {
                for (Rect k : kids) {
                    if (k.top < top - gap && k.bottom > top + gap) {
                        dist = top - k.top;
                    }
                }
            }
        }
        if (dist <= 0 || dist > maxDist) dist = h * 0.6f;

        float cx = box.exactCenterX();
        dist += touchSlop;
        if (dir > 0) {
            float sy = box.bottom - 10 * dp;
            stillSwipe(cx, sy, cx, Math.max(box.top + 2, sy - dist), 380);
        } else {
            float sy = box.top + 10 * dp;
            stillSwipe(cx, sy, cx, Math.min(box.bottom - 2, sy + dist), 380);
        }
        targetsTime = 0;
    }

    /** Finds the deepest vertically scrollable view under the cursor and its children's bounds. */
    private boolean findScrollable(Rect outBox, List<Rect> outKids) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                if (!wb.contains((int) x, (int) y)) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (Exception ignored) {
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo n = deepestScrollable(r, 0);
            if (n != null) {
                best = n;
                break;
            }
        }
        if (best == null) return false;
        best.getBoundsInScreen(outBox);
        for (int i = 0; i < best.getChildCount(); i++) {
            AccessibilityNodeInfo c = best.getChild(i);
            if (c == null) continue;
            Rect r = new Rect();
            c.getBoundsInScreen(r);
            if (r.height() > 4 * dp) outKids.add(r);
        }
        outKids.sort((a, b) -> Integer.compare(a.top, b.top));
        return true;
    }

    private AccessibilityNodeInfo deepestScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 45) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains((int) x, (int) y)) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = deepestScrollable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        if (node.isScrollable() && r.height() > 80 * dp && r.height() >= r.width() * 0.5f) {
            return node;
        }
        return null;
    }

    // ---------------------------------------------------------------- gestures

    private void tapAt(float tx, float ty, long durationMs) {
        Path p = new Path();
        p.moveTo(tx, ty);
        dispatch(new GestureDescription.StrokeDescription(p, 0, durationMs));
        targetsTime = 0;
    }

    private void fingerDown() {
        PointF p = tapPoint();
        if (Build.VERSION.SDK_INT < 26) {
            tapAt(p.x, p.y, 60); // no "keep finger down" on old Android: plain tap
            return;
        }
        Path path = new Path();
        path.moveTo(p.x, p.y);
        okX = p.x;
        okY = p.y;
        okStroke = new GestureDescription.StrokeDescription(path, 0, 40, true);
        dispatch(okStroke);
    }

    private void fingerUp() {
        if (okStroke == null || Build.VERSION.SDK_INT < 26) return;
        Path p = new Path();
        p.moveTo(okX, okY);
        try {
            dispatch(okStroke.continueStroke(p, 0, 20, false));
        } catch (Exception ignored) {
        }
        okStroke = null;
        targetsTime = 0;
    }

    private void doubleTap() {
        PointF p = tapPoint();
        Path p1 = new Path();
        p1.moveTo(p.x, p.y);
        Path p2 = new Path();
        p2.moveTo(p.x, p.y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p1, 0, 40));
        b.addStroke(new GestureDescription.StrokeDescription(p2, 120, 40));
        dispatchGesture(b.build(), null, null);
        targetsTime = 0;
    }

    private void swipe(float sx, float sy, float ex, float ey, long durationMs) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        dispatch(new GestureDescription.StrokeDescription(p, 0, durationMs));
    }

    /** Swipe in from the screen edge (opens side menus in many apps). */
    private void edgeSwipe(boolean fromLeft) {
        float sy = y;
        float sx = fromLeft ? 1 : screenW - 2;
        float ex = fromLeft ? screenW * 0.6f : screenW * 0.4f;
        swipe(sx, sy, ex, sy, 300);
        targetsTime = 0;
    }

    /** A swipe that stops and rests before lifting, so the page doesn't keep gliding. */
    private void stillSwipe(float sx, float sy, float ex, float ey, long durationMs) {
        if (Build.VERSION.SDK_INT < 26) {
            swipe(sx, sy, ex, ey, durationMs);
            return;
        }
        Path move = new Path();
        move.moveTo(sx, sy);
        move.lineTo(ex, ey);
        final GestureDescription.StrokeDescription first =
                new GestureDescription.StrokeDescription(move, 0, durationMs, true);
        dispatchGesture(new GestureDescription.Builder().addStroke(first).build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Path rest = new Path();
                        rest.moveTo(ex, ey);
                        dispatch(first.continueStroke(rest, 0, 120, false));
                    }
                }, null);
    }

    /** Two fingers moving apart (zoom in) or together (zoom out) around the cursor. */
    private void pinch(boolean zoomIn) {
        float near = 20 * dp, far = Math.min(screenW, screenH) * 0.3f;
        float a = zoomIn ? near : far, b = zoomIn ? far : near;
        float lx = x, ly = y;
        Path p1 = new Path();
        p1.moveTo(clampX(lx - a), clampY(ly - a));
        p1.lineTo(clampX(lx - b), clampY(ly - b));
        Path p2 = new Path();
        p2.moveTo(clampX(lx + a), clampY(ly + a));
        p2.lineTo(clampX(lx + b), clampY(ly + b));
        GestureDescription.Builder g = new GestureDescription.Builder();
        g.addStroke(new GestureDescription.StrokeDescription(p1, 0, 350));
        g.addStroke(new GestureDescription.StrokeDescription(p2, 0, 350));
        dispatchGesture(g.build(), null, null);
        targetsTime = 0;
    }

    private float clampX(float v) {
        return Math.max(1, Math.min(screenW - 2, v));
    }

    private float clampY(float v) {
        return Math.max(1, Math.min(screenH - 2, v));
    }

    // ---------------------------------------------------------------- drag

    private void startDrag() {
        if (Build.VERSION.SDK_INT < 26) {
            showToast("Dra kräver Android 8+");
            return;
        }
        Path p = new Path();
        p.moveTo(x, y);
        // Long press so text gets selected and things become draggable.
        dragStroke = new GestureDescription.StrokeDescription(p, 0, 600, true);
        dragX = x;
        dragY = y;
        dragging = true;
        outline = null;
        dispatch(dragStroke);
        showToast("Dra: flytta och tryck 1 för att släppa");
    }

    private void dragTo(float nx, float ny) {
        if (dragStroke == null || Build.VERSION.SDK_INT < 26) return;
        if (Math.abs(nx - dragX) < 0.5f && Math.abs(ny - dragY) < 0.5f) return;
        Path p = new Path();
        p.moveTo(dragX, dragY);
        p.lineTo(nx, ny);
        try {
            dragStroke = dragStroke.continueStroke(p, 0, HOLD_INTERVAL_MS, true);
            dispatch(dragStroke);
        } catch (Exception e) {
            dragging = false;
            dragStroke = null;
        }
        dragX = nx;
        dragY = ny;
    }

    private void endDrag() {
        if (dragStroke != null && Build.VERSION.SDK_INT >= 26) {
            Path p = new Path();
            p.moveTo(dragX, dragY);
            try {
                dispatch(dragStroke.continueStroke(p, 0, 40, false));
            } catch (Exception ignored) {
            }
        }
        dragStroke = null;
        dragging = false;
        targetsTime = 0;
        if (pointer != null) pointer.invalidate();
    }

    private void dispatch(GestureDescription.StrokeDescription stroke) {
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    // ---------------------------------------------------------------- paste

    private void paste() {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field != null && field.isEditable()) {
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            showToast("Inklistrat");
        } else {
            showToast("Tryck först i ett textfält");
        }
    }

    // ---------------------------------------------------------------- overlay

    private static class Pointer extends View {
        private final MouseService s;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dragFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxInner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint();
        private final int[] loc = new int[2];

        Pointer(MouseService service) {
            super(service);
            s = service;
            fill.setColor(Color.argb(170, 0, 229, 255));
            dragFill.setColor(Color.argb(190, 255, 140, 0));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * s.dp);
            ring.setColor(Color.BLACK);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(2.5f * s.dp);
            box.setColor(Color.rgb(0, 229, 255));
            boxInner.setStyle(Paint.Style.STROKE);
            boxInner.setStrokeWidth(1f * s.dp);
            boxInner.setColor(Color.BLACK);
            text.setColor(Color.WHITE);
            text.setTextSize(14 * s.dp);
            bg.setColor(Color.argb(210, 0, 0, 0));
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            c.save();
            c.translate(-loc[0], -loc[1]);
            if (s.cursorVisible()) {
                if (s.outline != null && !s.dragging) {
                    float in = 2 * s.dp;
                    c.drawRect(s.outline.left + 1, s.outline.top + 1,
                            s.outline.right - 1, s.outline.bottom - 1, box);
                    c.drawRect(s.outline.left + in, s.outline.top + in,
                            s.outline.right - in, s.outline.bottom - in, boxInner);
                }
                float r = 7 * s.dp;
                c.drawCircle(s.x, s.y, r, s.dragging ? dragFill : fill);
                c.drawCircle(s.x, s.y, r, ring);
                c.drawCircle(s.x, s.y, 1.5f * s.dp, ring);
            }
            if (s.toast != null) {
                float tw = text.measureText(s.toast);
                float th = text.getTextSize();
                float tx = 10 * s.dp, ty = 10 * s.dp;
                c.drawRect(tx - 6 * s.dp, ty, tx + tw + 6 * s.dp, ty + th + 10 * s.dp, bg);
                c.drawText(s.toast, tx, ty + th + 3 * s.dp, text);
            }
            c.restore();
        }
    }
}
