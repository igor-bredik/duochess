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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Draggable panel shown over Duolingo with the analysis text. It can be collapsed into a small
 * bubble (also draggable) that a tap opens again.
 */
final class OverlayPanel {
    interface Actions {
        void onLichess();

        void onSnapshot();

        void onClose();

        void onArrows();

        /** "Позиция": the user wants to start from the position now on the board. */
        void onAskSide();

        void onSide(boolean whiteToMove);
    }

    static final int NEUTRAL = Color.rgb(230, 236, 240);
    static final int GOOD = Color.rgb(124, 219, 90);
    static final int INACCURACY = Color.rgb(255, 213, 79);
    static final int MISTAKE = Color.rgb(255, 152, 0);
    static final int BLUNDER = Color.rgb(255, 82, 82);
    /** Colour of the thin strip on top of the panel; if it shows up in the capture, overlays are being recorded. */
    static final int MARKER = Color.rgb(255, 0, 170);

    private static final int BUTTON_BG = Color.rgb(44, 58, 66);

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;
    private final WindowManager.LayoutParams blp;
    private final LinearLayout root;
    private final LinearLayout bubble;
    private final TextView line1;
    private final TextView line2;
    private final TextView line3;
    private final TextView hint;
    private final TextView bubbleText;
    private final TextView arrowsButton;
    private final TextView cancelButton;
    private final LinearLayout chooser;
    private final View strip;
    private final View bubbleStrip;
    private final int screenW;
    /** The panel or the bubble is on screen. */
    private boolean shown;
    private boolean collapsed;
    private boolean bubblePlaced;
    /** Side chooser shown because no game is followed yet / because the user pressed "Позиция". */
    private boolean chooserAuto, chooserAsked;
    /** Centre of the marker strip on screen, or -1 if not laid out yet. */
    volatile int markerX = -1, markerY = -1;
    private final int[] loc = new int[2];
    private final Runnable locate = new Runnable() {
        @Override
        public void run() {
            View s = collapsed ? bubbleStrip : strip;
            if (shown && s.getWidth() > 0) {
                s.getLocationOnScreen(loc);
                markerX = loc[0] + s.getWidth() / 2;
                markerY = loc[1] + s.getHeight() / 2;
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
        screenW = ctx.getResources().getDisplayMetrics().widthPixels;

        // ---------------------------------------------------------------- full panel
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(10), pad, dp(8));
        root.setBackground(panelBackground(16));

        strip = markerStrip();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(48), dp(4));
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = dp(6);
        root.addView(strip, slp);

        line1 = text(15, true, NEUTRAL);
        line2 = text(13, false, Color.rgb(230, 236, 240));
        line3 = text(13, false, Color.rgb(176, 190, 197));
        hint = text(12, false, INACCURACY);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(line1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(smallButton("–", v -> collapse()));
        head.addView(smallButton("✕", v -> actions.onClose()));
        root.addView(head);
        root.addView(line2);
        root.addView(line3);
        root.addView(hint);
        hint.setVisibility(View.GONE);

        chooser = new LinearLayout(ctx);
        chooser.setOrientation(LinearLayout.VERTICAL);
        chooser.setPadding(0, dp(8), 0, 0);
        TextView q = text(13, false, INACCURACY);
        q.setText("Начать с позиции, которая сейчас на доске. Кто сейчас ходит?");
        chooser.addView(q);
        LinearLayout sides = new LinearLayout(ctx);
        sides.setOrientation(LinearLayout.HORIZONTAL);
        sides.setPadding(0, dp(6), 0, 0);
        sides.addView(button("Белые", Color.rgb(236, 240, 242), Color.rgb(19, 31, 36), v -> side(actions, true)), weighted(1f, false));
        sides.addView(button("Чёрные", Color.rgb(8, 10, 12), Color.rgb(236, 240, 242), v -> side(actions, false)), weighted(1f, true));
        cancelButton = button("Отмена", BUTTON_BG, NEUTRAL, v -> {
            chooserAsked = false;
            updateChooser();
        });
        sides.addView(cancelButton, weighted(1f, true));
        chooser.addView(sides);
        chooser.setVisibility(View.GONE);
        root.addView(chooser);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button("Lichess", BUTTON_BG, NEUTRAL, v -> actions.onLichess()), weighted(1f, false));
        buttons.addView(button("Кадр", BUTTON_BG, NEUTRAL, v -> actions.onSnapshot()), weighted(0.8f, true));
        buttons.addView(button("Позиция", BUTTON_BG, NEUTRAL, v -> actions.onAskSide()), weighted(1.1f, true));
        arrowsButton = button("Стрелки вкл", BUTTON_BG, NEUTRAL, v -> actions.onArrows());
        buttons.addView(arrowsButton, weighted(1.4f, true));
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
                        if (shown && !collapsed) wm.updateViewLayout(root, lp);
                        return true;
                    default:
                        return false;
                }
            }
        });

        // ---------------------------------------------------------------- collapsed bubble
        bubble = new LinearLayout(ctx);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(6), dp(12), dp(7));
        bubble.setBackground(panelBackground(18));
        bubbleStrip = markerStrip();
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(dp(22), dp(4));
        bslp.gravity = Gravity.CENTER_HORIZONTAL;
        bslp.bottomMargin = dp(4);
        bubble.addView(bubbleStrip, bslp);
        bubbleText = text(15, true, NEUTRAL);
        bubbleText.setText("♞");
        bubbleText.setGravity(Gravity.CENTER);
        bubble.addView(bubbleText);

        blp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Anchored to the right edge, so a longer text grows to the left and stays on screen.
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.x = dp(6);

        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = blp.x;
                        startY = blp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                        if (moved) {
                            blp.x = Math.max(0, Math.min(screenW - v.getWidth(), startX - (int) dx));
                            blp.y = Math.max(0, startY + (int) dy);
                            if (shown && collapsed) wm.updateViewLayout(bubble, blp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) expand();
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

    private GradientDrawable panelBackground(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 18, 24, 28));
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), Color.argb(255, 60, 74, 82));
        return bg;
    }

    private View markerStrip() {
        View v = new View(ctx);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(MARKER);
        sbg.setCornerRadius(dp(2));
        v.setBackground(sbg);
        return v;
    }

    private TextView text(float sp, boolean bold, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams weighted(float weight, boolean gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), weight);
        if (gap) p.setMarginStart(dp(6));
        return p;
    }

    /** Button that shares the row with others; the text shrinks if it does not fit. */
    private TextView button(String label, int bgColor, int textColor, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setMaxLines(1);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(255, 60, 74, 82));
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    private TextView smallButton(String label, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(NEUTRAL);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BUTTON_BG);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(30));
        p.setMarginStart(dp(6));
        p.gravity = Gravity.TOP;
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    private void side(Actions actions, boolean whiteToMove) {
        chooserAsked = false;
        updateChooser();
        actions.onSide(whiteToMove);
    }

    private void updateChooser() {
        chooser.setVisibility(chooserAuto || chooserAsked ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(chooserAuto ? View.INVISIBLE : View.VISIBLE);
    }

    /** Main thread only. */
    private void collapse() {
        if (collapsed) return;
        collapsed = true;
        markerX = -1;
        if (!bubblePlaced) {
            blp.y = lp.y;
            bubblePlaced = true;
        }
        if (shown) {
            wm.removeView(root);
            wm.addView(bubble, blp);
        }
    }

    /** Main thread only. */
    private void expand() {
        if (!collapsed) return;
        collapsed = false;
        markerX = -1;
        if (shown) {
            wm.removeView(bubble);
            wm.addView(root, lp);
        }
    }

    void show() {
        main.post(() -> {
            if (!shown) {
                if (collapsed) wm.addView(bubble, blp);
                else wm.addView(root, lp);
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
                wm.removeView(collapsed ? bubble : root);
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

    /** Text of the collapsed bubble. */
    void setShort(String s, int color) {
        main.post(() -> {
            bubbleText.setText(s);
            bubbleText.setTextColor(color);
        });
    }

    void setArrows(boolean on) {
        main.post(() -> {
            arrowsButton.setText(on ? "Стрелки вкл" : "Стрелки выкл");
            arrowsButton.setTextColor(on ? NEUTRAL : INACCURACY);
        });
    }

    /** Shows the side chooser on its own while no game is followed. */
    void setChooserAuto(boolean auto) {
        main.post(() -> {
            if (chooserAuto != auto) {
                chooserAuto = auto;
                updateChooser();
            }
        });
    }

    /** Opens or closes the side chooser ("Позиция" button). */
    void toggleChooser() {
        main.post(() -> {
            chooserAsked = !chooserAsked;
            updateChooser();
        });
    }
}
