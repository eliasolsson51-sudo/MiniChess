package app.minimouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * A D-pad mouse cursor for any app.
 *
 *   Hold *       cursor on/off (not while the keyboard is open, so TT9 works as normal)
 *   D-pad        move (hold to speed up)
 *   OK           tap
 *   5            long press
 *   2 / 8        scroll up / down
 *   4 / 6        scroll left / right
 *   Back         cursor off
 */
public class MouseService extends AccessibilityService {

    private static final long HOLD_DELAY_MS = 180;
    private static final long HOLD_INTERVAL_MS = 30;

    private WindowManager wm;
    private Pointer pointer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean active = false;
    float x, y;
    private float dp;
    private int screenW, screenH;

    // * long-press detection: a timer, so it works even if the phone sends no key repeats.
    private static final long STAR_HOLD_MS = 500;
    private boolean starDown = false;
    private boolean starConsumed = false;
    private final Runnable starHeld = () -> {
        if (!starDown || starConsumed) return;
        boolean kb = keyboardOpen();
        Log.d("MiniMouse", "* held, keyboard open: " + kb);
        if (!kb) {
            starConsumed = true;
            setActive(!active);
        }
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
            // Speed ramps up from 4 dp to 22 dp per step.
            float speed = Math.min(22f, 4f + holdSteps * 0.6f) * dp;
            moveBy(holdDx * speed, holdDy * speed);
            handler.postDelayed(this, HOLD_INTERVAL_MS);
        }
    };

    String toast = null;
    private final Runnable hideToast = () -> {
        toast = null;
        if (pointer != null) pointer.invalidate();
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

        Log.d("MiniMouse", "key " + code + " " + KeyEvent.keyCodeToString(code)
                + (down ? " down" : " up") + " repeat " + ev.getRepeatCount());

        // Hold * = toggle. A short press still reaches the app as normal.
        if (code == KeyEvent.KEYCODE_STAR) {
            if (firstDown) {
                starDown = true;
                starConsumed = false;
                handler.removeCallbacks(starHeld);
                handler.postDelayed(starHeld, STAR_HOLD_MS);
                return false;
            }
            if (down) {
                return starConsumed; // repeats: swallow once the toggle happened
            }
            starDown = false;
            handler.removeCallbacks(starHeld);
            if (starConsumed) {
                starConsumed = false;
                return true;
            }
            return false;
        }

        if (!active) return false;

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
                moveBy(dx * 4 * dp, dy * 4 * dp);
                startHold(code, dx, dy);
            } else if (!down && code == holdKey) {
                stopHold();
            }
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (firstDown) {
                    stopHold();
                    tap(60);
                }
                return true;
            case KeyEvent.KEYCODE_5:
                if (firstDown) tap(700);
                return true;
            case KeyEvent.KEYCODE_2:
                if (firstDown) swipe(0, 1);   // finger down = content scrolls up
                return true;
            case KeyEvent.KEYCODE_8:
                if (firstDown) swipe(0, -1);
                return true;
            case KeyEvent.KEYCODE_4:
                if (firstDown) swipe(1, 0);
                return true;
            case KeyEvent.KEYCODE_6:
                if (firstDown) swipe(-1, 0);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (firstDown) setActive(false);
                return true;
            default:
                return false;
        }
    }

    private boolean keyboardOpen() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void setActive(boolean on) {
        active = on;
        stopHold();
        showToast(on ? "Mus på  (håll * = av)" : "Mus av");
        updateVisibility();
    }

    private void showToast(String msg) {
        toast = msg;
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1300);
        if (pointer != null) pointer.invalidate();
    }

    private void updateVisibility() {
        if (pointer == null) return;
        pointer.setVisibility(active || toast != null ? View.VISIBLE : View.GONE);
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

    private void moveBy(float ddx, float ddy) {
        x = Math.max(0, Math.min(screenW - 1, x + ddx));
        y = Math.max(0, Math.min(screenH - 1, y + ddy));
        if (pointer != null) pointer.invalidate();
    }

    // ---------------------------------------------------------------- gestures

    private void tap(long durationMs) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(p, durationMs);
    }

    /** Swipes from the cursor in the given direction (a finger movement). */
    private void swipe(int dirX, int dirY) {
        float dist = (dirY != 0 ? screenH : screenW) * 0.35f;
        float sx = x, sy = y;
        float ex = Math.max(1, Math.min(screenW - 2, sx + dirX * dist));
        float ey = Math.max(1, Math.min(screenH - 2, sy + dirY * dist));
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        dispatch(p, 250);
    }

    private void dispatch(Path path, long durationMs) {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    // ---------------------------------------------------------------- overlay

    private static class Pointer extends View {
        private final MouseService s;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint();
        private final int[] loc = new int[2];

        Pointer(MouseService service) {
            super(service);
            s = service;
            fill.setColor(Color.argb(170, 0, 229, 255));
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
            if (s.active) {
                float r = 7 * s.dp;
                c.drawCircle(s.x, s.y, r, fill);
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
