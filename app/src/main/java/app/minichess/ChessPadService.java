package app.minichess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a square marker over the chess.com board and turns key presses into taps.
 *
 * Keys while chess.com is in front:
 *   D-pad        move the marker; hold to move fast
 *                (after picking a piece: jump between its legal squares)
 *   OK / 5       tap the marked square (tap a piece, then tap where it should go)
 *   7            smart moves on/off
 *   0            after picking: free movement; otherwise clear the yellow highlight
 *   #            pause / resume (when paused, all keys go to chess.com as normal)
 *   *            calibrate by hand (only needed if the board can't be read automatically)
 *
 * The board is read from chess.com's accessibility descriptions of each square
 * ("Vit Springare på f3", "g1, tomt"), which gives both the exact square positions and the
 * position of every piece. Legal moves are then calculated by ChessLogic. No screenshots.
 */
public class ChessPadService extends AccessibilityService {

    static final String CHESS_PKG = "com.chess";
    static final String PREFS = "minichess";

    static final int MODE_PLAY = 0;
    static final int MODE_CAL_TOP_LEFT = 1;
    static final int MODE_CAL_BOTTOM_RIGHT = 2;

    // Fast scrolling: first repeat after HOLD_DELAY, then one step every HOLD_INTERVAL.
    private static final long HOLD_DELAY_MS = 260;
    private static final long HOLD_INTERVAL_MS = 85;

    private WindowManager wm;
    private OverlayView overlay;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    boolean inChess = false;
    boolean paused = false;
    int mode = MODE_PLAY;

    // Cursor on the board (0..7, screen order). Row 0 is the top of the screen.
    int col = 4;
    int row = 6;
    int pickedCol = -1;
    int pickedRow = -1;

    // Legal target squares of the picked piece, as screen cells {col, row}.
    final List<int[]> targets = new ArrayList<>();
    boolean targetMode = false;
    boolean smart = true;

    // Board rectangle in screen pixels (read automatically, or from calibration).
    final RectF board = new RectF();
    boolean calibrated = false;      // a board rectangle is known (auto or by hand)
    private final RectF manualBoard = new RectF();
    private boolean hasManualBoard = false;
    private BoardReader.Board lastBoard;

    // Calibration crosshair.
    float calX, calY;
    float calX1, calY1;

    // Held D-pad direction for fast scrolling.
    private int holdKey = -1;
    private int holdDx, holdDy;
    private final Runnable holdStep = new Runnable() {
        @Override
        public void run() {
            if (holdKey < 0) return;
            if (mode == MODE_PLAY) {
                move(holdDx, holdDy);
            } else {
                calX += holdDx * 6f;
                calY += holdDy * 6f;
                clampCrosshair();
                refresh();
            }
            handler.postDelayed(this, HOLD_INTERVAL_MS);
        }
    };

    String showToast = null;
    private final Runnable hideToast = () -> {
        showToast = null;
        refresh();
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        smart = prefs.getBoolean("smart", true);
        loadManualBoard();

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
            stopHold();
            if (!inChess) {
                mode = MODE_PLAY;
                clearPick();
            } else {
                readBoard();
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
        stopHold();
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

    // ---------------------------------------------------------------- board

    /** Reads the board from chess.com. Falls back to the hand-calibrated rectangle. */
    private BoardReader.Board readBoard() {
        BoardReader.Board b = null;
        try {
            b = BoardReader.read(getRootInActiveWindow());
        } catch (Exception ignored) {
        }
        lastBoard = b;
        if (b != null) {
            board.set(b.bounds);
            calibrated = true;
        } else if (hasManualBoard) {
            board.set(manualBoard);
            calibrated = true;
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float w = dm.widthPixels;
            float top = Math.max(0, (dm.heightPixels - w) / 2f);
            board.set(0, top, w, top + w);
            calibrated = false;
        }
        return b;
    }

    // ---------------------------------------------------------------- keys

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        if (!inChess) {
            stopHold();
            return false;
        }
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean firstDown = down && ev.getRepeatCount() == 0;

        // # always toggles pause.
        if (code == KeyEvent.KEYCODE_POUND) {
            if (firstDown) {
                stopHold();
                paused = !paused;
                mode = MODE_PLAY;
                clearPick();
                if (!paused) readBoard();
                refresh();
            }
            return true;
        }
        if (paused) {
            return false;
        }

        // D-pad: one step on press, then fast steps while held. System repeats are ignored.
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
                if (mode == MODE_PLAY) {
                    move(dx, dy);
                } else {
                    calX += dx * 3f;
                    calY += dy * 3f;
                    clampCrosshair();
                    refresh();
                }
                startHold(code, dx, dy);
            } else if (!down && code == holdKey) {
                stopHold();
            }
            return true;
        }

        if (mode != MODE_PLAY) {
            return handleCalibrationKey(code, down, firstDown);
        }

        switch (code) {
            case KeyEvent.KEYCODE_STAR:
                if (firstDown) {
                    stopHold();
                    clearPick();
                    startCalibration();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_5:
                if (firstDown) {
                    stopHold();
                    press();
                }
                return true;
            case KeyEvent.KEYCODE_7:
                if (firstDown) {
                    smart = !smart;
                    prefs.edit().putBoolean("smart", smart).apply();
                    if (!smart) targetMode = false;
                    toast(smart ? "Smart moves ON (7)" : "Smart moves OFF (7)");
                }
                return true;
            case KeyEvent.KEYCODE_0:
                if (firstDown) {
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

    private void startHold(int key, int dx, int dy) {
        stopHold();
        holdKey = key;
        holdDx = dx;
        holdDy = dy;
        handler.postDelayed(holdStep, HOLD_DELAY_MS);
    }

    private void stopHold() {
        holdKey = -1;
        handler.removeCallbacks(holdStep);
    }

    private void toast(String msg) {
        showToast = msg;
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1500);
        refresh();
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
        BoardReader.Board b = readBoard();
        if (pickedCol >= 0) {
            // Second tap: the destination (or the same square to cancel).
            tapCell(b, col, row);
            clearPick();
            refresh();
            return;
        }
        // First tap: pick a piece.
        tapCell(b, col, row);
        pickedCol = col;
        pickedRow = row;
        targets.clear();
        targetMode = false;
        if (smart && b != null) {
            BoardReader.Square sq = b.grid[col][row];
            if (sq != null && sq.piece != 0) {
                for (int[] to : ChessLogic.legalMoves(b.pos, sq.file, sq.rank)) {
                    int[] cell = b.cellOf(to[0], to[1]);
                    if (cell != null) targets.add(cell);
                }
            }
            if (!targets.isEmpty()) {
                targetMode = true;
                jumpToNearestTarget();
            }
        }
        refresh();
    }

    private void clearPick() {
        pickedCol = -1;
        pickedRow = -1;
        targetMode = false;
        targets.clear();
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

    // ---------------------------------------------------------------- calibration

    private boolean handleCalibrationKey(int code, boolean down, boolean firstDown) {
        float big = 40f;
        switch (code) {
            case KeyEvent.KEYCODE_2: if (down) calY -= big; break;
            case KeyEvent.KEYCODE_8: if (down) calY += big; break;
            case KeyEvent.KEYCODE_4: if (down) calX -= big; break;
            case KeyEvent.KEYCODE_6: if (down) calX += big; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_5:
                if (firstDown) {
                    stopHold();
                    confirmCalibrationPoint();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_STAR:
                if (firstDown) {
                    stopHold();
                    mode = MODE_PLAY; // cancel, keep the old board
                }
                break;
            default:
                return false;
        }
        clampCrosshair();
        refresh();
        return true;
    }

    private void clampCrosshair() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        calX = Math.max(0, Math.min(dm.widthPixels - 1, calX));
        calY = Math.max(0, Math.min(dm.heightPixels - 1, calY));
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
                manualBoard.set(l, t, r, b);
                hasManualBoard = true;
                prefs.edit()
                        .putFloat("l", l).putFloat("t", t)
                        .putFloat("r", r).putFloat("b", b)
                        .apply();
                if (lastBoard == null) {
                    board.set(manualBoard);
                    calibrated = true;
                }
            }
            mode = MODE_PLAY;
        }
    }

    private void loadManualBoard() {
        if (prefs.contains("l")) {
            manualBoard.set(prefs.getFloat("l", 0), prefs.getFloat("t", 0),
                    prefs.getFloat("r", 0), prefs.getFloat("b", 0));
            hasManualBoard = true;
            board.set(manualBoard);
            calibrated = true;
        }
    }

    // ---------------------------------------------------------------- taps

    /** Taps the middle of a screen cell: the real square if the board was read, else the grid. */
    private void tapCell(BoardReader.Board b, int c, int r) {
        float x, y;
        BoardReader.Square sq = b != null ? b.grid[c][r] : null;
        if (sq != null) {
            Rect rb = sq.bounds;
            x = rb.exactCenterX();
            y = rb.exactCenterY();
        } else {
            x = board.left + (c + 0.5f) * board.width() / 8f;
            y = board.top + (r + 0.5f) * board.height() / 8f;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
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
