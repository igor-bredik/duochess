package app.duochess.core;

/**
 * Tells a live game from the game review using a single frame: during a live game Duolingo shows
 * the timer (a yellow clock icon and yellow digits) at the top right; the review screen has no
 * yellow there. No waiting and no dependence on moves being made.
 */
public final class LiveWatch {
    private static final int MIN_PIXELS = 40;
    private long liveUntil;
    public int lastCount;

    private static boolean yellow(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 200 && g > 150 && b < 120 && r - b > 110;
    }

    /** Counts timer-yellow pixels in the top-right band of the screen. */
    public static int timerPixels(Frame f, BoardGeom g) {
        int w = f.width(), h = f.height();
        int y1 = (int) (h * 0.07f);
        int y2 = (int) Math.min(h * 0.13f, g.y0 - g.sq * 0.3f);
        int n = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = w / 2; x < w; x++) {
                if (yellow(f.rgb(x, y))) n++;
            }
        }
        return n;
    }

    public void update(Frame f, BoardGeom g, long now) {
        lastCount = timerPixels(f, g);
        if (lastCount >= MIN_PIXELS) liveUntil = now + 1500;
    }

    public boolean live(long now) {
        return now < liveUntil;
    }

    public void reset() {
        liveUntil = 0;
    }
}
