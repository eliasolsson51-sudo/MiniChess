package app.minimouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
import java.util.List;

/**
 * A D-pad mouse cursor for any app.
 *
 *   Hold *       cursor on/off (not while the keyboard is open: TT9 uses it)
 *                The cursor pauses itself while you type and comes back afterwards.
 *   D-pad        move (hold to speed up); pushing against the top/bottom edge scrolls
 *   OK           finger down/up: short = tap, hold = long press
 *   5            long press
 *   1            drag mode: finger down / finger up (select text, move sliders and maps)
 *   2 / 8        smart scroll: previous / next post
 *   4 / 6        scroll left / right
 *   3 / 9        zoom in / out
 *   0            double tap
 *   7            paste into the focused text field
 *   Back         back in the app as normal (ends a drag)
 */
public class MouseService extends AccessibilityService {

    private static final long HOLD_DELAY_MS = 150;
    private static final long HOLD_INTERVAL_MS = 30;
    private static final float STEP_DP = 10f;          // one press
    private static final float HOLD_START_DP = 6f;     // per 30 ms when held
    private static final float HOLD_RAMP_DP = 1.5f;
    private static final float HOLD_MAX_DP = 30f;
    private static final long STAR_HOLD_MS = 500;
    private static final long EDGE_SCROLL_EVERY_MS = 260;

    private WindowManager wm;
    private Pointer pointer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    boolean active = false;      // the user has turned the cursor on
    boolean suspended = false;   // paused while the keyboard is open
    float x, y;
    float dp;
    private int screenW, screenH;
    private int touchSlop;

    // * long-press detection (timer based, works without key repeats).
    private boolean starDown = false;
    private boolean starConsumed = false;
    private final Runnable starHeld = () -> {
        if (!starDown || starConsumed) return;
        if (keyboardOpen()) return; // TT9 uses holding * for its own things
        starConsumed = true;
        setActive(!active);
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
            handler.postDelayed(this, HOLD_INTERVAL_MS);
        }
    };
    private long lastEdgeScroll = 0;

    // Drag mode: the "finger" stays down between gestures.
    boolean dragging = false;
    private GestureDescription.StrokeDescription dragStroke;
    private float dragX, dragY;

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
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkKeyboard();
        }
    }

    /** Pauses the cursor while the keyboard is open and brings it back when it closes. */
    private boolean checkKeyboard() {
        boolean kb = keyboardOpen();
        boolean shouldSuspend = active && kb;
        if (shouldSuspend != suspended) {
            suspended = shouldSuspend;
            if (suspended) {
                stopHold();
                if (dragging) endDrag();
            }
            updateVisibility();
        }
        return suspended;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        stopHold();
        if (pointer != null) {
            try {
                wm.removeView(pointer);
            } catch (Exception ignored) {
            }
            pointer = null;
        }
        super.onDestroy();
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
        if (checkKeyboard()) return false; // typing: all keys go to TT9

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
                moveBy(dx * STEP_DP * dp, dy * STEP_DP * dp, dy);
                startHold(code, dx, dy);
            } else if (!down && code == holdKey) {
                stopHold();
            }
            return true;
        }

        // OK works like a finger: down while the key is down, up when it is released.
        // A short press is a tap, holding it is a long press.
        boolean isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (isOk) {
            if (firstDown) {
                stopHold();
                if (dragging) {
                    endDrag();
                } else {
                    fingerDown();
                }
            } else if (!down) {
                fingerUp();
            }
            return true;
        }

        if (!firstDown) {
            // Swallow repeats and key-ups of the keys we handle below.
            return isMouseKey(code);
        }

        switch (code) {
            case KeyEvent.KEYCODE_5:
                tap(700);
                return true;
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
            case KeyEvent.KEYCODE_4:
                swipe(x, y, Math.min(screenW - 2, x + screenW * 0.35f), y, 250);
                return true;
            case KeyEvent.KEYCODE_6:
                swipe(x, y, Math.max(1, x - screenW * 0.35f), y, 250);
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

    private boolean keyboardOpen() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void setActive(boolean on) {
        if (!on && dragging) endDrag();
        active = on;
        suspended = false;
        stopHold();
        showToast(on ? "Mus på  (håll * = av)" : "Mus av");
    }

    private void showToast(String msg) {
        toast = msg;
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1300);
        updateVisibility();
    }

    private void updateVisibility() {
        if (pointer == null) return;
        boolean show = (active && !suspended) || toast != null;
        pointer.setVisibility(show ? View.VISIBLE : View.GONE);
        pointer.invalidate();
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
                // First item that starts below the top of the list.
                for (Rect k : kids) {
                    if (k.top > top + gap) {
                        dist = k.top - top;
                        break;
                    }
                }
            } else {
                // Start of the item cut off at the top, if any.
                for (Rect k : kids) {
                    if (k.top < top - gap && k.bottom > top + gap) {
                        dist = top - k.top;
                    }
                }
            }
        }
        // Long posts (or no item found): scroll most of a screen.
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
        // Skip horizontal-only scrollers (carousels): they are wider than tall.
        if (node.isScrollable() && r.height() > 80 * dp && r.height() >= r.width() * 0.5f) {
            return node;
        }
        return null;
    }

    // ---------------------------------------------------------------- gestures

    private void tap(long durationMs) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(new GestureDescription.StrokeDescription(p, 0, durationMs));
    }

    // Finger held down by the OK key.
    private GestureDescription.StrokeDescription okStroke;
    private float okX, okY;

    private void fingerDown() {
        if (Build.VERSION.SDK_INT < 26) {
            tap(60); // no "keep finger down" on old Android: plain tap
            return;
        }
        Path p = new Path();
        p.moveTo(x, y);
        okX = x;
        okY = y;
        okStroke = new GestureDescription.StrokeDescription(p, 0, 40, true);
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
    }

    private void doubleTap() {
        Path p1 = new Path();
        p1.moveTo(x, y);
        Path p2 = new Path();
        p2.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p1, 0, 40));
        b.addStroke(new GestureDescription.StrokeDescription(p2, 120, 40));
        dispatchGesture(b.build(), null, null);
    }

    private void swipe(float sx, float sy, float ex, float ey, long durationMs) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        dispatch(new GestureDescription.StrokeDescription(p, 0, durationMs));
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
            text.setColor(Color.WHITE);
            text.setTextSize(14 * s.dp);
            bg.setColor(Color.argb(210, 0, 0, 0));
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            c.save();
            c.translate(-loc[0], -loc[1]);
            if (s.active && !s.suspended) {
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
