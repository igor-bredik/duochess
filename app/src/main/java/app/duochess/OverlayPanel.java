package app.duochess;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small draggable panel shown over Duolingo with the analysis text. */
final class OverlayPanel {
    interface Actions {
        void onLichess();

        void onSnapshot();

        void onClose();
    }

    static final int NEUTRAL = Color.rgb(230, 236, 240);
    static final int GOOD = Color.rgb(124, 219, 90);
    static final int INACCURACY = Color.rgb(255, 213, 79);
    static final int MISTAKE = Color.rgb(255, 152, 0);
    static final int BLUNDER = Color.rgb(255, 82, 82);
    /** Colour of the thin strip on top of the panel; if it shows up in the capture, overlays are being recorded. */
    static final int MARKER = Color.rgb(255, 0, 170);

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    private final TextView line1;
    private final TextView line2;
    private final TextView line3;
    private final TextView hint;
    private final View strip;
    private boolean shown;
    /** Centre of the marker strip on screen, or -1 if not laid out yet. */
    volatile int markerX = -1, markerY = -1;
    private final int[] loc = new int[2];
    private final Runnable locate = new Runnable() {
        @Override
        public void run() {
            if (shown && strip.getWidth() > 0) {
                strip.getLocationOnScreen(loc);
                markerX = loc[0] + strip.getWidth() / 2;
                markerY = loc[1] + strip.getHeight() / 2;
            } else {
                markerX = -1;
            }
            main.postDelayed(this, 1000);
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    OverlayPanel(Context ctx, Actions actions) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(10), pad, dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 18, 24, 28));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(255, 60, 74, 82));
        root.setBackground(bg);

        strip = new View(ctx);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(MARKER);
        sbg.setCornerRadius(dp(2));
        strip.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(48), dp(4));
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = dp(6);
        root.addView(strip, slp);

        line1 = text(15, true, NEUTRAL);
        line2 = text(13, false, Color.rgb(230, 236, 240));
        line3 = text(13, false, Color.rgb(176, 190, 197));
        hint = text(12, false, INACCURACY);
        root.addView(line1);
        root.addView(line2);
        root.addView(line3);
        root.addView(hint);
        hint.setVisibility(View.GONE);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(6), 0, 0);
        buttons.addView(button("Lichess", v -> actions.onLichess()));
        buttons.addView(button("Кадр", v -> actions.onSnapshot()));
        buttons.addView(button("✕", v -> actions.onClose()));
        root.addView(buttons);

        lp = new WindowManager.LayoutParams(
                (int) (screenW * 0.94f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (screenW * 0.03f);
        lp.y = (int) (screenH * 0.155f);

        root.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.y = Math.max(0, startY + (int) (e.getRawY() - downY));
                        if (shown) wm.updateViewLayout(root, lp);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private TextView text(float sp, boolean bold, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.rgb(230, 236, 240));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(44, 58, 66));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dp(8));
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    void show() {
        main.post(() -> {
            if (!shown) {
                wm.addView(root, lp);
                shown = true;
                main.removeCallbacks(locate);
                main.postDelayed(locate, 500);
            }
        });
    }

    void hide() {
        main.post(() -> {
            main.removeCallbacks(locate);
            if (shown) {
                wm.removeView(root);
                shown = false;
            }
        });
    }

    void setHint(String h) {
        main.post(() -> {
            hint.setText(h);
            hint.setVisibility(h == null || h.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    void setText(String a, String b, String c, int color1) {
        main.post(() -> {
            line1.setText(a);
            line1.setTextColor(color1);
            line2.setText(b);
            line2.setVisibility(b == null || b.isEmpty() ? View.GONE : View.VISIBLE);
            line3.setText(c);
            line3.setVisibility(c == null || c.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }
}
