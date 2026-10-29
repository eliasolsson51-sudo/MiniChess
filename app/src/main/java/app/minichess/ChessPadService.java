package app.minichess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Draws a square marker over the chess.com board and turns key presses into taps.
 *
 * Keys while chess.com is in front:
 *   D-pad        move the marker one square
 *   OK / 5       tap the marked square (tap a piece, then tap where it should go)
 *   0            clear the "picked piece" highlight
 *   #            pause / resume (when paused, all keys go to chess.com as normal)
 *   *            calibrate: mark the board's top-left, then bottom-right corner
 */
public class ChessPadService extends AccessibilityService {

    static final String CHESS_PKG = "com.chess";
    static final String PREFS = "minichess";

    static final int MODE_PLAY = 0;
    static final int MODE_CAL_TOP_LEFT = 1;
    static final int MODE_CAL_BOTTOM_RIGHT = 2;

    private WindowManager wm;
    private OverlayView overlay;
    private SharedPreferences prefs;

    boolean inChess = false;
    boolean paused = false;
    int mode = MODE_PLAY;

    // Cursor on the board (0..7). Row 0 is the top of the screen.
    int col = 4;
    int row = 6;
    int pickedCol = -1;
    int pickedRow = -1;

    // Board rectangle in screen pixels.
    final RectF board = new RectF();
    boolean calibrated = false;

    // Calibration crosshair.
    float calX, calY;
    float calX1, calY1;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadBoard();

        overlay = new OverlayView(this, this);
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
            wm.addView(overlay, lp);
        } catch (Exception e) {
            overlay = null;
        }
        refresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        String pkg = null;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
            }
        } catch (Exception ignored) {
        }
        if (pkg == null && event.getPackageName() != null) {
            pkg = event.getPackageName().toString();
        }
        if (pkg == null || pkg.equals(getPackageName())) {
            return;
        }
        boolean nowInChess = CHESS_PKG.equals(pkg);
        if (nowInChess != inChess) {
            inChess = nowInChess;
            if (!inChess && mode != MODE_PLAY) {
                mode = MODE_PLAY;
            }
            refresh();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        removeOverlay();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    private void removeOverlay() {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        if (!inChess) {
            return false;
        }
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean firstDown = down && ev.getRepeatCount() == 0;

        // # always toggles pause.
        if (code == KeyEvent.KEYCODE_POUND) {
            if (firstDown) {
                paused = !paused;
                mode = MODE_PLAY;
                refresh();
            }
            return true;
        }
        if (paused) {
            return false;
        }

        if (mode != MODE_PLAY) {
            return handleCalibrationKey(code, down, firstDown, ev.getRepeatCount());
        }

        switch (code) {
            case KeyEvent.KEYCODE_STAR:
                if (firstDown) {
                    startCalibration();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) row = Math.max(0, row - 1);
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) row = Math.min(7, row + 1);
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) col = Math.max(0, col - 1);
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) col = Math.min(7, col + 1);
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_5:
                if (firstDown) {
                    tapSquare(col, row);
                }
                return true;
            case KeyEvent.KEYCODE_0:
                if (firstDown) {
                    pickedCol = -1;
                    pickedRow = -1;
                    refresh();
                }
                return true;
            default:
                return false; // Back, Menu, Call etc. go to chess.com as normal.
        }
    }

    private boolean handleCalibrationKey(int code, boolean down, boolean firstDown, int repeat) {
        float small = repeat > 6 ? 12f : 3f;
        float big = 40f;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:    if (down) calY -= small; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  if (down) calY += small; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (down) calX -= small; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) calX += small; break;
            case KeyEvent.KEYCODE_2: if (down) calY -= big; break;
            case KeyEvent.KEYCODE_8: if (down) calY += big; break;
            case KeyEvent.KEYCODE_4: if (down) calX -= big; break;
            case KeyEvent.KEYCODE_6: if (down) calX += big; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_5:
                if (firstDown) confirmCalibrationPoint();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_STAR:
                if (firstDown) {
                    mode = MODE_PLAY; // cancel, keep the old board
                }
                break;
            default:
                return false;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        calX = Math.max(0, Math.min(dm.widthPixels - 1, calX));
        calY = Math.max(0, Math.min(dm.heightPixels - 1, calY));
        refresh();
        return true;
    }

    private void startCalibration() {
        mode = MODE_CAL_TOP_LEFT;
        calX = board.left;
        calY = board.top;
        refresh();
    }

    private void confirmCalibrationPoint() {
        if (mode == MODE_CAL_TOP_LEFT) {
            calX1 = calX;
            calY1 = calY;
            mode = MODE_CAL_BOTTOM_RIGHT;
            calX = board.right;
            calY = board.bottom;
        } else {
            float l = Math.min(calX1, calX), r = Math.max(calX1, calX);
            float t = Math.min(calY1, calY), b = Math.max(calY1, calY);
            if (r - l > 40 && b - t > 40) {
                board.set(l, t, r, b);
                calibrated = true;
                prefs.edit()
                        .putFloat("l", l).putFloat("t", t)
                        .putFloat("r", r).putFloat("b", b)
                        .apply();
            }
            mode = MODE_PLAY;
        }
    }

    private void loadBoard() {
        if (prefs.contains("l")) {
            board.set(prefs.getFloat("l", 0), prefs.getFloat("t", 0),
                    prefs.getFloat("r", 0), prefs.getFloat("b", 0));
            calibrated = true;
        } else {
            // Guess: a full-width square board in the middle of the screen.
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float w = dm.widthPixels;
            float top = Math.max(0, (dm.heightPixels - w) / 2f);
            board.set(0, top, w, top + w);
            calibrated = false;
        }
    }

    // ---------------------------------------------------------------- taps

    float squareCenterX(int c) {
        return board.left + (c + 0.5f) * board.width() / 8f;
    }

    float squareCenterY(int r) {
        return board.top + (r + 0.5f) * board.height() / 8f;
    }

    private void tapSquare(int c, int r) {
        float x = squareCenterX(c);
        float y = squareCenterY(r);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);

        if (pickedCol < 0) {
            pickedCol = c;
            pickedRow = r;
        } else {
            pickedCol = -1;
            pickedRow = -1;
        }
        refresh();
    }

    // ---------------------------------------------------------------- overlay

    void refresh() {
        if (overlay == null) {
            return;
        }
        overlay.setVisibility(inChess ? OverlayView.VISIBLE : OverlayView.GONE);
        overlay.invalidate();
    }
}
