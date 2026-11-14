package app.duochess;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps a report of the last crash or hang in a file, so that it can be shown on the start
 * screen and sent to the developer.
 */
final class CrashLog {
    private static final String FILE = "crash.txt";
    private static final long HANG_MS = 5000;
    private static boolean installed;
    private static String version = "?";
    private static Thread watchdog;

    private CrashLog() {}

    /** Writes uncaught exceptions of any thread to the report file. */
    static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // keep "?"
        }
        final File file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            StringWriter sw = new StringWriter();
            sw.append("Сбой в потоке ").append(thread.getName()).append('\n');
            e.printStackTrace(new PrintWriter(sw, true));
            write(file, sw.toString());
            if (prev != null) prev.uncaughtException(thread, e);
        });
    }

    /**
     * Watches the main thread while the service runs: if it does not answer for a few seconds
     * (buttons stop working), what every thread is doing is written to the report file.
     */
    static synchronized void startWatchdog(Context ctx) {
        if (watchdog != null) return;
        final File file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
        final Handler main = new Handler(Looper.getMainLooper());
        final long[] seen = {SystemClock.uptimeMillis()};
        Thread t = new Thread(() -> {
            boolean reported = false;
            try {
                while (true) {
                    main.post(() -> seen[0] = SystemClock.uptimeMillis());
                    Thread.sleep(1000);
                    long late = SystemClock.uptimeMillis() - seen[0];
                    if (late < HANG_MS) {
                        reported = false;
                    } else if (!reported) {
                        reported = true;
                        write(file, "Приложение не отвечает " + late / 1000 + " с\n" + allStacks());
                    }
                }
            } catch (InterruptedException ignored) {
                // stopped
            }
        }, "watchdog");
        t.setDaemon(true);
        t.start();
        watchdog = t;
    }

    static synchronized void stopWatchdog() {
        if (watchdog != null) watchdog.interrupt();
        watchdog = null;
    }

    private static String allStacks() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
            Thread th = en.getKey();
            sb.append('\n').append(th.getName()).append(" (").append(th.getState()).append(")\n");
            for (StackTraceElement el : en.getValue()) sb.append("    ").append(el).append('\n');
        }
        return sb.toString();
    }

    private static void write(File file, String text) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write((stamp + ", версия " + version + "\n" + text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // nothing more can be done
        }
    }

    /** The last report, or null. */
    static String read(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (!file.exists()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context ctx) {
        new File(ctx.getFilesDir(), FILE).delete();
    }
}
