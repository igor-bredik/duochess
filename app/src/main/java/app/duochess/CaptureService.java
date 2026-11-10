package app.duochess;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.duochess.core.Advice;
import app.duochess.core.Analyzer;
import app.duochess.core.Engine;
import app.duochess.core.Move;
import app.duochess.core.Pgn;
import app.duochess.core.Position;
import app.duochess.core.Tracker;

/** Captures the screen, follows the board and shows the engine's verdict in an overlay. */
public class CaptureService extends Service implements Tracker.Listener, OverlayPanel.Actions {
    static final String ACTION_START = "app.duochess.START";
    static final String ACTION_STOP = "app.duochess.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "analysis";
    private static final int NOTIF_ID = 1;
    private static final long FRAME_MS = 250;
    private static final String TAG = "DuoChess";

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private OverlayPanel overlay;
    private Tracker tracker;
    private Analyzer analyzer;
    private long lastFrame;
    private long lastRefresh;
    private boolean lastLive;
    private volatile boolean snapshotRequested;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && projection == null) {
            startForeground(NOTIF_ID, notification("Ищу доску…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                    : intent.getParcelableExtra(EXTRA_DATA);
            try {
                begin(code, data);
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                toast("Не удалось начать запись экрана: " + e.getMessage());
                shutdown();
            }
        }
        return START_NOT_STICKY;
    }

    private void begin(int code, Intent data) {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("нет разрешения");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(CaptureService.this::shutdown);
            }
        }, main);

        int w, h, dpi;
        WindowManager wm = getSystemService(WindowManager.class);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            w = real.widthPixels;
            h = real.heightPixels;
        }
        // Half resolution is plenty for recognising the board and four times cheaper.
        w /= 2;
        h /= 2;
        dpi = Math.max(80, dm.densityDpi / 2);

        tracker = new Tracker(this);
        final String enginePath = getApplicationInfo().nativeLibraryDir + "/libstockfish.so";
        analyzer = new Analyzer(() -> new Engine(enginePath, 2, 32), 1500, this::refresh);
        analyzer.start();

        overlay = new OverlayPanel(this, this);
        overlay.setText("DuoChess", "", "Откройте разбор партии в Duolingo (начальная позиция)", OverlayPanel.NEUTRAL);
        overlay.show();

        captureThread = new HandlerThread("capture");
        captureThread.start();
        Handler ch = new Handler(captureThread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, ch);
        display = projection.createVirtualDisplay("duochess", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ch);
        running = true;
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < FRAME_MS) return;
            lastFrame = now;
            ImageFrame f = new ImageFrame(img);
            if (snapshotRequested) {
                snapshotRequested = false;
                saveSnapshot(f);
            }
            if (tracker != null) tracker.process(f, now);
            boolean live = tracker != null && tracker.liveGame(now);
            if (live != lastLive || now - lastRefresh > 1000) {
                lastLive = live;
                refresh();
            }
        } catch (Throwable t) {
            Log.e(TAG, "frame failed", t);
        } finally {
            try {
                img.close();
            } catch (Exception ignored) {
                // reader already closed
            }
        }
    }

    // ------------------------------------------------------------------ Tracker.Listener

    @Override
    public void onPosition(Tracker t) {
        refresh();
    }

    @Override
    public void onStatus(String status) {
        updateNotification(status);
        refresh();
    }

    // ------------------------------------------------------------------ overlay text

    private synchronized void refresh() {
        lastRefresh = SystemClock.uptimeMillis();
        Tracker t = tracker;
        Analyzer a = analyzer;
        OverlayPanel o = overlay;
        if (t == null || a == null || o == null) return;
        if (!t.active()) {
            o.setText(t.status().isEmpty() ? "Ищу доску…" : t.status(), "",
                    "Откройте разбор партии в Duolingo (начальная позиция)", OverlayPanel.NEUTRAL);
            return;
        }
        if (t.liveGame(SystemClock.uptimeMillis())) {
            a.request(new ArrayList<>());
            o.setText("Идёт партия: подсказки выключены", "",
                    "Подсказки появятся в разборе партии после игры", OverlayPanel.NEUTRAL);
            return;
        }
        Position cur;
        Position prev = null;
        Move last;
        synchronized (t) {
            cur = t.current();
            last = t.lastMove();
            if (t.ply() > 0) prev = t.position(t.ply() - 1);
        }
        if (cur == null) return;
        List<Position> want = new ArrayList<>();
        want.add(cur);
        if (prev != null) want.add(prev);
        a.request(want);
        a.start();
        String[] lines = Advice.describe(prev, last, cur, a.get(prev), a.get(cur));
        String err = a.error();
        if (err != null) lines[2] = err;
        o.setText(lines[0], lines[1], lines[2], colorFor(lines[0]));
    }

    private static int colorFor(String line) {
        if (line.endsWith("зевок")) return OverlayPanel.BLUNDER;
        if (line.endsWith("ошибка")) return OverlayPanel.MISTAKE;
        if (line.endsWith("неточность")) return OverlayPanel.INACCURACY;
        if (line.endsWith("лучший ход") || line.endsWith("хороший ход")) return OverlayPanel.GOOD;
        return OverlayPanel.NEUTRAL;
    }

    // ------------------------------------------------------------------ overlay buttons

    @Override
    public void onLichess() {
        Tracker t = tracker;
        if (t == null || !t.active()) {
            toast("Партия ещё не найдена");
            return;
        }
        String url;
        synchronized (t) {
            List<String> san = t.sanLine();
            if (san.contains("--")) {
                url = "https://lichess.org/analysis/standard/" + t.current().toFen().replace(' ', '_');
            } else {
                url = Pgn.lichessUrl(san, t.playerWhite()) + "#" + t.ply();
            }
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Не открылся браузер");
        }
    }

    @Override
    public void onSnapshot() {
        snapshotRequested = true;
    }

    @Override
    public void onClose() {
        shutdown();
    }

    private void saveSnapshot(ImageFrame f) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            Bitmap bmp = Bitmap.createBitmap(f.toArgb(), f.width(), f.height(), Bitmap.Config.ARGB_8888);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "duochess_" + stamp + ".png");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DuoChess");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
            ContentValues tv = new ContentValues();
            tv.put(MediaStore.MediaColumns.DISPLAY_NAME, "duochess_" + stamp + ".txt");
            tv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            tv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DuoChess");
            Uri tu = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, tv);
            if (tu != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("status: ").append(tracker.status()).append('\n');
                sb.append("geom: ").append(tracker.geom()).append('\n');
                Position cur = tracker.current();
                if (cur != null) sb.append("fen: ").append(cur.toFen()).append('\n');
                sb.append("line: ").append(Pgn.movetext(tracker.sanLine())).append("\n\n");
                synchronized (tracker) {
                    sb.append(tracker.log);
                }
                try (OutputStream os = getContentResolver().openOutputStream(tu)) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            toast("Кадр сохранён в «Галерея → DuoChess»");
        } catch (Exception e) {
            Log.e(TAG, "snapshot failed", e);
            toast("Не удалось сохранить кадр: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ notification & lifecycle

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Разбор партии", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("DuoChess: разбор партии")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .build();
    }

    private long lastNotif;

    private void updateNotification(String text) {
        long now = SystemClock.uptimeMillis();
        if (now - lastNotif < 1000) return;
        lastNotif = now;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(text));
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    private void shutdown() {
        running = false;
        if (analyzer != null) analyzer.stop();
        if (overlay != null) overlay.hide();
        if (display != null) display.release();
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        if (captureThread != null) {
            final ImageReader r = reader;
            Handler h = new Handler(captureThread.getLooper());
            h.post(() -> {
                if (r != null) r.close();
            });
            captureThread.quitSafely();
        }
        display = null;
        reader = null;
        captureThread = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (projection != null || overlay != null) {
            running = false;
            if (analyzer != null) analyzer.stop();
            if (overlay != null) overlay.hide();
            if (display != null) display.release();
            if (projection != null) projection.stop();
            if (captureThread != null) captureThread.quitSafely();
        }
        super.onDestroy();
    }
}
