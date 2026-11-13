package app.duochess;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.duochess.core.Advice;
import app.duochess.core.Analyzer;
import app.duochess.core.BoardGeom;
import app.duochess.core.Calibration;
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
    private static final long FRAME_MS = 150;
    private static final String TAG = "DuoChess";
    private static final String PREFS = "duochess";
    /** Piece looks from the last starting position seen, to start from any position later. */
    private static final String PIECES_FILE = "pieces.bin";

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private volatile Handler captureHandler;
    private final ImageFrame frame = new ImageFrame();
    /** Newest image not processed yet (capture thread only). */
    private Image held;
    private final Runnable processHeldTask = this::processHeld;
    private OverlayPanel overlay;
    private Tracker tracker;
    private Analyzer analyzer;
    private long lastFrame;
    private long lastRefresh;
    private volatile boolean snapshotRequested;
    private volatile boolean arrowsOn = true;
    private BoardOverlay board;
    /** Virtual display size, real display size and size of the captured content. */
    private int vw, vh, dw, dh;
    private volatile int cw, ch;
    private volatile boolean contentVisible = true;
    /** True once we know whether our own overlays end up in the capture. */
    private volatile boolean overlayChecked;
    private volatile boolean overlaysCaptured;
    private long lastOverlayCheck;
    private long startedAt;

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

            @Override
            public void onCapturedContentResize(int width, int height) {
                if (width > 0 && height > 0) {
                    cw = width;
                    ch = height;
                }
            }

            @Override
            public void onCapturedContentVisibilityChanged(boolean isVisible) {
                contentVisible = isVisible;
                refresh();
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
        dw = w;
        dh = h;
        cw = w;
        ch = h;
        // Half resolution is plenty for recognising the board and four times cheaper.
        w /= 2;
        h /= 2;
        vw = w;
        vh = h;
        dpi = Math.max(80, dm.densityDpi / 2);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        arrowsOn = prefs.getBoolean("arrows", true);
        tracker = new Tracker(this);
        loadPieces(tracker);
        final String enginePath = getApplicationInfo().nativeLibraryDir + "/libstockfish.so";
        analyzer = new Analyzer(() -> new Engine(enginePath, 2, 32), 1000, this::refresh);
        analyzer.start();

        board = new BoardOverlay(this);
        board.show();
        overlay = new OverlayPanel(this, this);
        overlay.setText("DuoChess", "", "Ищу доску…", OverlayPanel.NEUTRAL);
        overlay.setArrows(arrowsOn);
        overlay.show();

        captureThread = new HandlerThread("capture");
        captureThread.start();
        Handler ch = new Handler(captureThread.getLooper());
        captureHandler = ch;
        // One image may be held back while the next one is acquired.
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, ch);
        display = projection.createVirtualDisplay("duochess", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ch);
        running = true;
        startedAt = SystemClock.uptimeMillis();
    }

    /**
     * At most one frame per FRAME_MS is processed. The newest image is kept until then, so the
     * last frame before the screen goes still (e.g. the end of a move animation) is not lost.
     */
    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        closeHeld();
        held = img;
        Handler h = captureHandler;
        long wait = lastFrame + FRAME_MS - SystemClock.uptimeMillis();
        if (wait <= 0 || h == null) {
            processHeld();
        } else {
            h.removeCallbacks(processHeldTask);
            h.postDelayed(processHeldTask, wait);
        }
    }

    private void processHeld() {
        Image img = held;
        held = null;
        if (img == null) return;
        try {
            frame.copyFrom(img);
        } catch (Throwable t) {
            Log.e(TAG, "frame copy failed", t);
            return;
        } finally {
            try {
                img.close();
            } catch (Exception ignored) {
                // reader already closed
            }
        }
        long now = SystemClock.uptimeMillis();
        lastFrame = now;
        handleFrame(now);
    }

    private void closeHeld() {
        if (held != null) {
            try {
                held.close();
            } catch (Exception ignored) {
                // reader already closed
            }
            held = null;
        }
    }

    /** Capture thread. */
    private void handleFrame(long now) {
        try {
            if (snapshotRequested) {
                snapshotRequested = false;
                saveSnapshot(frame);
            }
            checkOverlayCaptured(frame, now);
            if (tracker != null) tracker.process(frame, now);
            if (now - lastRefresh > 1000) refresh();
        } catch (Throwable t) {
            Log.e(TAG, "frame failed", t);
        }
    }

    /**
     * New frames arrive only when the screen changes. If a button asked for work on the frame,
     * use the last one when nothing new comes in.
     */
    private void kick() {
        Handler h = captureHandler;
        if (h == null) return;
        h.postDelayed(() -> {
            long now = SystemClock.uptimeMillis();
            Tracker t = tracker;
            boolean pending = snapshotRequested || (t != null && t.readPending());
            if (pending && frame.valid() && now - lastFrame >= FRAME_MS) {
                lastFrame = now;
                handleFrame(now);
            }
        }, 300);
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

    @Override
    public void onCalibrated(Calibration pieces, List<BoardGeom> boards) {
        File dir = getFilesDir();
        File tmp = new File(dir, PIECES_FILE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            pieces.write(out);
            out.writeInt(boards.size());
            for (BoardGeom g : boards) {
                out.writeFloat(g.x0);
                out.writeFloat(g.y0);
                out.writeFloat(g.sq);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not save pieces", e);
            return;
        }
        if (!tmp.renameTo(new File(dir, PIECES_FILE))) Log.w(TAG, "could not save pieces");
    }

    @Override
    public void onNotice(String text) {
        toast(text);
    }

    private void loadPieces(Tracker t) {
        File f = new File(getFilesDir(), PIECES_FILE);
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            Calibration pieces = Calibration.read(in);
            List<BoardGeom> boards = new ArrayList<>();
            int n = in.readInt();
            for (int i = 0; i < n && i < 8; i++) boards.add(new BoardGeom(in.readFloat(), in.readFloat(), in.readFloat()));
            t.setSaved(pieces, boards);
        } catch (Exception e) {
            Log.w(TAG, "could not read saved pieces", e);
        }
    }

    // ------------------------------------------------------------------ overlay text

    private synchronized void refresh() {
        lastRefresh = SystemClock.uptimeMillis();
        Tracker t = tracker;
        Analyzer a = analyzer;
        OverlayPanel o = overlay;
        BoardOverlay bo = board;
        if (t == null || a == null || o == null || bo == null) return;
        if (!t.active()) {
            bo.clear();
            boolean canRead = t.canRead();
            o.setChooserAuto(canRead);
            o.setText(t.status().isEmpty() ? "Ищу доску…" : t.status(), "",
                    canRead ? "Откройте партию в начальной позиции или начните с той, что сейчас на доске:"
                            : "Откройте партию в начальной позиции. Это нужно один раз, чтобы запомнить фигуры: "
                            + "потом можно будет начинать с любой позиции",
                    OverlayPanel.NEUTRAL);
            o.setShort("♞", OverlayPanel.NEUTRAL);
            return;
        }
        o.setChooserAuto(false);
        Position cur, prev = null, after = null;
        Move next = null, last = null;
        BoardGeom g;
        boolean wb, numbered;
        boolean arrows = arrowsOn;
        List<Position> want = new ArrayList<>();
        synchronized (t) {
            int ply = t.ply();
            int n = t.lineLength();
            cur = t.current();
            if (cur == null) return;
            if (ply > 0) {
                prev = t.position(ply - 1);
                last = t.move(ply - 1);
            }
            if (ply < n) {
                next = t.move(ply);
                after = t.position(ply + 1);
            }
            g = t.geom();
            wb = t.playerWhite();
            numbered = t.fromStart();
            // Current position first, then its neighbours, then the rest of the known game.
            want.add(cur);
            if (after != null) want.add(after);
            if (prev != null) want.add(prev);
            for (int k = 1; k <= n; k++) {
                if (ply + 1 + k <= n) want.add(t.position(ply + 1 + k));
                if (ply - 1 - k >= 0) want.add(t.position(ply - 1 - k));
            }
        }
        a.request(want);
        a.start();
        Engine.Eval eCur = a.get(cur);
        Advice.View v = Advice.describe(cur, eCur, next, a.get(after), prev, last, a.get(prev), numbered, arrows);
        String err = a.error();
        if (err != null) v.l3 = err;
        o.setText(v.l1, v.l2, v.l3, colorOf(v.verdict));
        o.setShort("♞ " + (eCur != null ? Advice.tinyEval(eCur) : "…"), colorOf(v.verdict));

        if (!overlayChecked || overlaysCaptured || !contentVisible || !t.boardVisible() || g == null) {
            bo.clear();
            return;
        }
        List<BoardOverlay.Arrow> list = new ArrayList<>();
        if (arrows) {
            if (v.played != null) list.add(new BoardOverlay.Arrow(v.played.from, v.played.to, arrowColor(v.verdict)));
            if (v.best != null) list.add(new BoardOverlay.Arrow(v.best.from, v.best.to, BoardOverlay.GREEN));
        }
        float[] tl = toScreen(g.x0, g.y0);
        bo.update(tl[0], tl[1], g.sq / scale(), wb, list);
    }

    private static int colorOf(int verdict) {
        switch (verdict) {
            case Advice.GOOD: return OverlayPanel.GOOD;
            case Advice.INACCURACY: return OverlayPanel.INACCURACY;
            case Advice.MISTAKE: return OverlayPanel.MISTAKE;
            case Advice.BLUNDER: return OverlayPanel.BLUNDER;
            default: return OverlayPanel.NEUTRAL;
        }
    }

    private static int arrowColor(int verdict) {
        switch (verdict) {
            case Advice.INACCURACY: return android.graphics.Color.argb(215, 255, 213, 79);
            case Advice.MISTAKE: return android.graphics.Color.argb(215, 255, 152, 0);
            case Advice.BLUNDER: return android.graphics.Color.argb(215, 255, 82, 82);
            default: return android.graphics.Color.argb(190, 190, 205, 215);
        }
    }

    // ------------------------------------------------------------------ capture <-> screen

    /** Capture pixels per screen pixel. */
    private float scale() {
        return Math.min(vw / (float) cw, vh / (float) ch);
    }

    private float windowTop() {
        if (ch >= dh - 2) return 0;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private float[] toScreen(float cx, float cy) {
        float s = scale();
        float ox = (vw - cw * s) / 2f, oy = (vh - ch * s) / 2f;
        return new float[] {(cx - ox) / s, (cy - oy) / s + windowTop()};
    }

    private int[] toCapture(float sx, float sy) {
        float s = scale();
        float ox = (vw - cw * s) / 2f, oy = (vh - ch * s) / 2f;
        return new int[] {Math.round(sx * s + ox), Math.round((sy - windowTop()) * s + oy)};
    }

    /**
     * If the user chose to record the whole screen, our own arrows would be recorded too and
     * confuse the board recognition. We check for the panel's coloured strip in the capture.
     */
    private void checkOverlayCaptured(ImageFrame f, long now) {
        if (overlaysCaptured || overlay == null || now - lastOverlayCheck < 1500 || now - startedAt < 1500) return;
        lastOverlayCheck = now;
        int mx = overlay.markerX, my = overlay.markerY;
        if (mx < 0) return;
        int[] c = toCapture(mx, my);
        int hits = 0;
        for (int dx = -2; dx <= 2; dx += 2) {
            int x = Math.max(0, Math.min(f.width() - 1, c[0] + dx));
            int y = Math.max(0, Math.min(f.height() - 1, c[1]));
            if (app.duochess.core.Vision.diff(f.rgb(x, y), OverlayPanel.MARKER & 0xFFFFFF) < 90) hits++;
        }
        if (hits >= 2) {
            overlaysCaptured = true;
            if (board != null) board.clear();
            overlay.setHint("Стрелки и буквы на доске работают, только если при запуске выбрать «Одно приложение» → "
                    + "Duolingo. Нажмите ✕ и запустите заново.");
        }
        if (!overlayChecked) {
            overlayChecked = true;
            refresh();
        }
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
            if (!t.fromStart() || san.contains("--")) {
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
        kick();
    }

    @Override
    public void onArrows() {
        arrowsOn = !arrowsOn;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("arrows", arrowsOn).apply();
        if (overlay != null) overlay.setArrows(arrowsOn);
        refresh();
    }

    @Override
    public void onAskSide() {
        Tracker t = tracker;
        if (t == null || overlay == null) return;
        if (!t.canRead()) {
            toast("Сначала откройте партию в начальной позиции: так я запомню, как выглядят фигуры. "
                    + "Потом можно будет начинать с любой позиции");
            return;
        }
        overlay.toggleChooser();
    }

    @Override
    public void onSide(boolean whiteToMove) {
        Tracker t = tracker;
        if (t == null) return;
        t.requestRead(whiteToMove);
        kick();
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
                sb.append("from start: ").append(tracker.fromStart()).append('\n');
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
        if (board != null) board.hide();
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
                h.removeCallbacks(processHeldTask);
                closeHeld();
                if (r != null) r.close();
            });
            captureThread.quitSafely();
        }
        display = null;
        reader = null;
        captureThread = null;
        captureHandler = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (projection != null || overlay != null) {
            running = false;
            if (analyzer != null) analyzer.stop();
            if (overlay != null) overlay.hide();
            if (board != null) board.hide();
            if (display != null) display.release();
            if (projection != null) projection.stop();
            if (captureThread != null) captureThread.quitSafely();
        }
        super.onDestroy();
    }
}
