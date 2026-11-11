package app.duochess;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Start screen: permissions and the start / stop button. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private TextView overlayState;
    private Button overlayButton;
    private Button startButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, pad);
        root.setBackgroundColor(Color.rgb(19, 31, 36));

        root.addView(text("DuoChess", 28, true, Color.WHITE));
        root.addView(text("Разбор партий Duolingo с помощью Stockfish. Подсказки показываются только в разборе "
                + "партии после игры, во время живой партии приложение молчит.", 15, false, Color.rgb(176, 190, 197)));

        root.addView(text("1. Разрешение", 18, true, Color.WHITE));
        overlayState = text("", 15, false, Color.rgb(176, 190, 197));
        root.addView(overlayState);
        overlayButton = button("Разрешить показ поверх других приложений");
        overlayButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(overlayButton);

        root.addView(text("2. Запуск", 18, true, Color.WHITE));
        root.addView(text("Нажмите «Запустить» и в окне записи экрана выберите «Одно приложение» → Duolingo. "
                + "Тогда стрелки и буквы на доске не мешают распознаванию. При записи всего экрана будет только текст.",
                15, false, Color.rgb(176, 190, 197)));
        startButton = button("Запустить");
        startButton.setOnClickListener(v -> toggle());
        root.addView(startButton);

        root.addView(text("3. В Duolingo", 18, true, Color.WHITE));
        root.addView(text("Откройте разбор партии и листайте ходы стрелками с самого начала. Зелёная стрелка на доске — "
                + "лучший ход, цветная — ход из партии (жёлтая: неточность, оранжевая: ошибка, красная: зевок). "
                + "Совет: пролистайте партию до конца и вернитесь в начало, тогда оценка каждого хода видна заранее. "
                + "Плашку можно перетащить за розовую полоску. «Lichess» откроет партию на lichess.org, «Кадр» сохранит "
                + "снимок экрана для отладки.", 15, false, Color.rgb(176, 190, 197)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        overlayState.setText(ok ? "Показ поверх других приложений разрешён ✓"
                : "Нужно разрешить показ поверх других приложений, чтобы плашка была видна поверх Duolingo.");
        overlayButton.setEnabled(!ok);
        overlayButton.setAlpha(ok ? 0.4f : 1f);
        startButton.setText(CaptureService.running ? "Остановить" : "Запустить");
    }

    private void toggle() {
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            CaptureService.running = false;
            startButton.setText("Запустить");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разрешите показ поверх других приложений", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Без записи экрана приложение не видит доску", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        startButton.setText("Остановить");
        Intent duo = getPackageManager().getLaunchIntentForPackage("com.duolingo");
        if (duo != null) {
            startActivity(duo);
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(bold ? 18 : 6), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(19, 31, 36));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(160, 210, 80));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }
}
