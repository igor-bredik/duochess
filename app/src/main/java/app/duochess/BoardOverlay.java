package app.duochess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

import app.duochess.core.Vision;

/**
 * Transparent full-screen layer over Duolingo that draws board coordinates and move arrows.
 * It never takes touches, so Duolingo keeps working underneath.
 */
final class BoardOverlay {
    static final class Arrow {
        final int from;
        final int to;
        final int color;

        Arrow(int from, int to, int color) {
            this.from = from;
            this.to = to;
            this.color = color;
        }
    }

    static final int GREEN = Color.argb(215, 76, 214, 92);

    private final WindowManager wm;
    private final DrawView view;
    private final WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean shown;

    BoardOverlay(Context ctx) {
        wm = ctx.getSystemService(WindowManager.class);
        view = new DrawView(ctx);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Android lets touches pass through another app's overlay only if it is at most 80% opaque.
        lp.alpha = 0.8f;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    void show() {
        main.post(() -> {
            if (!shown) {
                wm.addView(view, lp);
                shown = true;
            }
        });
    }

    void hide() {
        main.post(() -> {
            if (shown) {
                wm.removeView(view);
                shown = false;
            }
        });
    }

    /** Board in screen pixels; arrows use chess squares (a1 = 0). */
    void update(float left, float top, float sq, boolean whiteBottom, List<Arrow> arrows) {
        final List<Arrow> copy = new ArrayList<>(arrows);
        main.post(() -> {
            view.has = true;
            view.left = left;
            view.top = top;
            view.sq = sq;
            view.whiteBottom = whiteBottom;
            view.arrows = copy;
            view.invalidate();
        });
    }

    void clear() {
        main.post(() -> {
            if (view.has) {
                view.has = false;
                view.invalidate();
            }
        });
    }

    private static final class DrawView extends View {
        boolean has;
        float left, top, sq;
        boolean whiteBottom;
        List<Arrow> arrows = new ArrayList<>();
        private final int[] loc = new int[2];
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        DrawView(Context ctx) {
            super(ctx);
            fill.setStyle(Paint.Style.FILL);
            text.setColor(Color.argb(200, 236, 242, 245));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setShadowLayer(3f, 0f, 0f, Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!has || sq <= 0) return;
            getLocationOnScreen(loc);
            float ox = left - loc[0], oy = top - loc[1];

            text.setTextSize(sq * 0.2f);
            for (int col = 0; col < 8; col++) {
                char file = (char) (whiteBottom ? 'a' + col : 'h' - col);
                float x = ox + (col + 1) * sq - sq * 0.06f - text.measureText(String.valueOf(file));
                c.drawText(String.valueOf(file), x, oy + 8 * sq - sq * 0.07f, text);
            }
            for (int row = 0; row < 8; row++) {
                int rank = whiteBottom ? 8 - row : row + 1;
                c.drawText(String.valueOf(rank), ox + sq * 0.06f, oy + row * sq + sq * 0.24f, text);
            }

            for (Arrow a : arrows) {
                int fi = Vision.imageIndex(a.from, whiteBottom), ti = Vision.imageIndex(a.to, whiteBottom);
                float fx = ox + ((fi & 7) + 0.5f) * sq, fy = oy + ((fi >> 3) + 0.5f) * sq;
                float tx = ox + ((ti & 7) + 0.5f) * sq, ty = oy + ((ti >> 3) + 0.5f) * sq;
                drawArrow(c, fx, fy, tx, ty, a.color);
            }
        }

        private void drawArrow(Canvas c, float fx, float fy, float tx, float ty, int color) {
            float dx = tx - fx, dy = ty - fy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1) return;
            float ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
            float shaft = sq * 0.17f, headW = sq * 0.5f, headL = sq * 0.42f;
            float sx = fx + ux * sq * 0.12f, sy = fy + uy * sq * 0.12f;
            float px = tx - ux * sq * 0.12f, py = ty - uy * sq * 0.12f;
            float bx = px - ux * headL, by = py - uy * headL;
            path.reset();
            path.moveTo(sx + nx * shaft / 2, sy + ny * shaft / 2);
            path.lineTo(bx + nx * shaft / 2, by + ny * shaft / 2);
            path.lineTo(bx + nx * headW / 2, by + ny * headW / 2);
            path.lineTo(px, py);
            path.lineTo(bx - nx * headW / 2, by - ny * headW / 2);
            path.lineTo(bx - nx * shaft / 2, by - ny * shaft / 2);
            path.lineTo(sx - nx * shaft / 2, sy - ny * shaft / 2);
            path.close();
            fill.setColor(color);
            c.drawPath(path, fill);
        }
    }
}
