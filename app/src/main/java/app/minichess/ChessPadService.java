package app.minichess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Draws a square marker over the chess.com board and turns key presses into taps.
 *
 * Keys while chess.com is in front:
 *   D-pad        move the marker (after picking a piece: jump between its legal squares)
 *   OK / 5       tap the marked square (tap a piece, then tap where it should go)
 *   7            smart moves on/off
 *   0            after picking: free movement; otherwise clear the yellow highlight
 *   #            pause / resume (when paused, all keys go to chess.com as normal)
 *   *            calibrate: mark the board's top-left, then bottom-right corner
 *
 * Legal squares are found by comparing a screenshot taken just before the piece is
 * tapped with one taken just after: the squares where chess.com drew a move dot or a
 * capture ring are the ones that changed.
 */
public class ChessPadService extends AccessibilityService {

    static final String CHESS_PKG = "com.chess";
    static final String PREFS = "minichess";

    static final int MODE_PLAY = 0;
    static final int MODE_CAL_TOP_LEFT = 1;
    static final int MODE_CAL_BOTTOM_RIGHT = 2;

    // Points inside a square that are compared before/after (fractions of the square).
    // Centre catches move dots, the edge points catch capture rings.
    private static final float[][] SAMPLE_POINTS = {
            {0.5f, 0.5f}, {0.5f, 0.08f}, {0.5f, 0.92f}, {0.08f, 0.5f}, {0.92f, 0.5f}
    };
    private static final int DIFF_THRESHOLD = 45; // sum of |dR|+|dG|+|dB|, averaged 3x3
    private static final long AFTER_TAP_DELAY_MS = 450;

    private WindowManager wm;
    private OverlayView overlay;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    boolean inChess = false;
    boolean paused = false;
    int mode = MODE_PLAY;

    // Cursor on the board (0..7). Row 0 is the top of the screen.
    int col = 4;
    int row = 6;
    int pickedCol = -1;
    int pickedRow = -1;

    // Legal target squares of the picked piece, as {col, row}.
    final List<int[]> targets = new ArrayList<>();
    boolean targetMode = false;
    boolean busy = false;
    boolean smart = true; // 7 toggles jumping between legal squares

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
        smart = prefs.getBoolean("smart", true);
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
            if (!inChess) {
                mode = MODE_PLAY;
                clearPick();
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
                clearPick();
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
                if (firstDown && !busy) {
                    clearPick();
                    startCalibration();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && !busy) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && !busy) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && !busy) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && !busy) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_5:
                if (firstDown && !busy) press();
                return true;
            case KeyEvent.KEYCODE_7:
                if (firstDown && !busy) {
                    smart = !smart;
                    prefs.edit().putBoolean("smart", smart).apply();
                    if (!smart) targetMode = false;
                    showToast = smart ? "Smart moves ON (7)" : "Smart moves OFF (7)";
                    handler.removeCallbacks(hideToast);
                    handler.postDelayed(hideToast, 1500);
                    refresh();
                }
                return true;
            case KeyEvent.KEYCODE_0:
                if (firstDown && !busy) {
                    if (targetMode) {
                        targetMode = false; // keep the piece picked, move freely
                    } else {
                        clearPick();
                    }
                    refresh();
                }
                return true;
            default:
                return false; // Back, Menu, Call etc. go to chess.com as normal.
        }
    }

    private void move(int dx, int dy) {
        if (targetMode) {
            jumpToTarget(dx, dy);
        } else {
            col = Math.max(0, Math.min(7, col + dx));
            row = Math.max(0, Math.min(7, row + dy));
        }
        refresh();
    }

    /** Moves the cursor to the nearest legal square (or the picked square) in the given direction. */
    private void jumpToTarget(int dx, int dy) {
        List<int[]> candidates = new ArrayList<>(targets);
        candidates.add(new int[]{pickedCol, pickedRow});
        int[] best = null;
        float bestScore = Float.MAX_VALUE;
        for (int[] t : candidates) {
            int vx = t[0] - col, vy = t[1] - row;
            int along = vx * dx + vy * dy;
            if (along <= 0) continue;
            int across = Math.abs(vx * dy - vy * dx);
            float score = along + 2f * across;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best != null) {
            col = best[0];
            row = best[1];
        }
    }

    private void press() {
        if (pickedCol >= 0) {
            // Second tap: the destination (or the same square to cancel).
            tapSquare(col, row);
            clearPick();
            refresh();
            return;
        }
        // First tap: pick a piece.
        Log.d("MiniChess", "pick: smart=" + smart + " sdk=" + Build.VERSION.SDK_INT);
        if (smart && Build.VERSION.SDK_INT >= 30) {
            pickWithTargets(col, row);
        } else {
            tapSquare(col, row);
            pickedCol = col;
            pickedRow = row;
            refresh();
        }
    }

    private void clearPick() {
        pickedCol = -1;
        pickedRow = -1;
        targetMode = false;
        targets.clear();
    }

    // ---------------------------------------------------------------- legal squares

    @TargetApi(30)
    private void pickWithTargets(final int c, final int r) {
        busy = true;
        takeShot(2, before -> {
            tapSquare(c, r);
            pickedCol = c;
            pickedRow = r;
            refresh();
            if (before == null) {
                Log.d("MiniChess", "no 'before' screenshot, smart moves skipped");
                busy = false;
                return;
            }
            handler.postDelayed(() -> takeShot(2, after -> {
                targets.clear();
                if (after != null) {
                    findTargets(before, after, c, r);
                    after.recycle();
                }
                before.recycle();
                busy = false;
                if (pickedCol == c && pickedRow == r && !targets.isEmpty()) {
                    targetMode = true;
                    jumpToNearestTarget();
                }
                refresh();
            }), AFTER_TAP_DELAY_MS);
        });
    }

    private void jumpToNearestTarget() {
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int[] t : targets) {
            int d = Math.abs(t[0] - col) + Math.abs(t[1] - row);
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        if (best != null) {
            col = best[0];
            row = best[1];
        }
    }

    private void findTargets(Bitmap before, Bitmap after, int pc, int pr) {
        Log.d("MiniChess", "shots " + before.getWidth() + "x" + before.getHeight()
                + " board " + board.toShortString());
        StringBuilder grid = new StringBuilder();
        float sw = board.width() / 8f;
        float sh = board.height() / 8f;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (c == pc && r == pr) {
                    grid.append("   X");
                    continue;
                }
                int maxDiff = 0;
                for (float[] p : SAMPLE_POINTS) {
                    int x = (int) (board.left + (c + p[0]) * sw);
                    int y = (int) (board.top + (r + p[1]) * sh);
                    maxDiff = Math.max(maxDiff, pixelDiff(before, after, x, y));
                }
                if (maxDiff > DIFF_THRESHOLD) {
                    targets.add(new int[]{c, r});
                }
                grid.append(String.format(java.util.Locale.ROOT, "%4d", maxDiff));
            }
            Log.d("MiniChess", "row " + r + ":" + grid);
            grid.setLength(0);
        }
        Log.d("MiniChess", "targets found: " + targets.size());
    }

    private static int pixelDiff(Bitmap a, Bitmap b, int x, int y) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long sum = 0;
        int n = 0;
        for (int yy = y - 1; yy <= y + 1; yy++) {
            for (int xx = x - 1; xx <= x + 1; xx++) {
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int p = a.getPixel(xx, yy), q = b.getPixel(xx, yy);
                sum += Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff))
                        + Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff))
                        + Math.abs((p & 0xff) - (q & 0xff));
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    /** Takes a screenshot as a software bitmap, or null on failure. Retries when called too often. */
    @TargetApi(30)
    private void takeShot(final int retries, final Consumer<Bitmap> cb) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                Bitmap soft = null;
                HardwareBuffer hb = result.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (hw != null) {
                        soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                    }
                } catch (Exception e) {
                    Log.d("MiniChess", "screenshot copy failed: " + e);
                    soft = null;
                } finally {
                    hb.close();
                }
                cb.accept(soft);
            }

            @Override
            public void onFailure(int errorCode) {
                Log.d("MiniChess", "screenshot failed, code " + errorCode + ", retries left " + retries);
                if (retries > 0) {
                    handler.postDelayed(() -> takeShot(retries - 1, cb), 350);
                } else {
                    cb.accept(null);
                }
            }
        });
    }

    // ---------------------------------------------------------------- calibration

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

    private void tapSquare(int c, int r) {
        float x = board.left + (c + 0.5f) * board.width() / 8f;
        float y = board.top + (r + 0.5f) * board.height() / 8f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    // ---------------------------------------------------------------- overlay

    String showToast = null;
    private final Runnable hideToast = () -> {
        showToast = null;
        refresh();
    };

    void refresh() {
        if (overlay == null) {
            return;
        }
        overlay.setVisibility(inChess ? OverlayView.VISIBLE : OverlayView.GONE);
        overlay.invalidate();
    }
}
