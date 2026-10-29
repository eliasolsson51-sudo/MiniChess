package app.minichess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Transparent full-screen view that draws the marker, the picked square and the calibration crosshair. */
class OverlayView extends View {

    private final ChessPadService s;
    private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picked = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint();
    private final int[] loc = new int[2];
    private final float dp;

    OverlayView(Context ctx, ChessPadService service) {
        super(ctx);
        s = service;
        dp = ctx.getResources().getDisplayMetrics().density;

        outer.setStyle(Paint.Style.STROKE);
        outer.setStrokeWidth(4 * dp);
        outer.setColor(Color.rgb(0, 229, 255));

        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(1.5f * dp);
        inner.setColor(Color.BLACK);

        picked.setStyle(Paint.Style.FILL);
        picked.setColor(Color.argb(110, 255, 214, 0));

        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeWidth(1.5f * dp);
        cross.setColor(Color.rgb(255, 0, 200));

        text.setColor(Color.WHITE);
        text.setTextSize(13 * dp);

        textBg.setColor(Color.argb(200, 0, 0, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Draw in screen coordinates, the same ones the taps use.
        getLocationOnScreen(loc);
        canvas.save();
        canvas.translate(-loc[0], -loc[1]);

        if (s.paused) {
            label(canvas, "MiniChess paused  (# = resume)", 8 * dp, 8 * dp);
        } else if (s.mode == ChessPadService.MODE_PLAY) {
            drawPlay(canvas);
        } else {
            drawCalibration(canvas);
        }
        canvas.restore();
    }

    private void drawPlay(Canvas canvas) {
        RectF b = s.board;
        float sw = b.width() / 8f;
        float sh = b.height() / 8f;

        if (s.pickedCol >= 0) {
            canvas.drawRect(b.left + s.pickedCol * sw, b.top + s.pickedRow * sh,
                    b.left + (s.pickedCol + 1) * sw, b.top + (s.pickedRow + 1) * sh, picked);
        }

        // Small cyan dots on the legal squares while a piece is picked.
        if (s.targetMode) {
            for (int[] tg : s.targets) {
                canvas.drawCircle(b.left + (tg[0] + 0.5f) * sw, b.top + (tg[1] + 0.5f) * sh,
                        3 * dp, outer);
            }
        }

        float l = b.left + s.col * sw, t = b.top + s.row * sh;
        float pad = 2 * dp;
        canvas.drawRect(l + pad, t + pad, l + sw - pad, t + sh - pad, outer);
        float in = 4.5f * dp;
        canvas.drawRect(l + in, t + in, l + sw - in, t + sh - in, inner);

        if (s.showToast != null) {
            label(canvas, s.showToast, 8 * dp, 8 * dp);
        } else if (!s.calibrated) {
            label(canvas, "Press * to calibrate the board", 8 * dp, 8 * dp);
        }
    }

    private void drawCalibration(Canvas canvas) {
        int w = getWidth() + loc[0];
        int h = getHeight() + loc[1];
        canvas.drawLine(0, s.calY, w, s.calY, cross);
        canvas.drawLine(s.calX, 0, s.calX, h, cross);

        if (s.mode == ChessPadService.MODE_CAL_BOTTOM_RIGHT) {
            canvas.drawRect(Math.min(s.calX1, s.calX), Math.min(s.calY1, s.calY),
                    Math.max(s.calX1, s.calX), Math.max(s.calY1, s.calY), outer);
        }

        String step = s.mode == ChessPadService.MODE_CAL_TOP_LEFT
                ? "1/2: TOP-LEFT corner of the board"
                : "2/2: BOTTOM-RIGHT corner of the board";
        float y = s.calY > h / 2f ? 8 * dp : h - 60 * dp;
        label(canvas, step, 8 * dp, y);
        label(canvas, "D-pad = fine, 2/4/6/8 = fast, OK = set", 8 * dp, y + 22 * dp);
    }

    private void label(Canvas canvas, String msg, float x, float y) {
        float tw = text.measureText(msg);
        float th = text.getTextSize();
        canvas.drawRect(x - 4 * dp, y, x + tw + 4 * dp, y + th + 8 * dp, textBg);
        canvas.drawText(msg, x, y + th + 2 * dp, text);
    }
}
