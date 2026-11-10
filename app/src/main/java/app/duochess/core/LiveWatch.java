package app.duochess.core;

/**
 * Detects a live game by a ticking clock: the band near the top of the screen (where Duolingo
 * shows the timer) keeps changing about once a second while the board itself stays still.
 * In the game review that band is static, so hints are only shown there.
 */
public final class LiveWatch {
    private static final int GX = 135, GY = 27;
    private int[] last;
    private final long[] events = new long[8];
    private int eventCount;
    private long lastBoardChange;
    private long lastEvent;

    public void boardChanged(long now) {
        lastBoardChange = now;
    }

    public void update(Frame f, BoardGeom g, long now) {
        int w = f.width(), h = f.height();
        float y1 = h * 0.06f, y2 = Math.min(h * 0.15f, g.y0 - g.sq * 0.5f);
        if (y2 <= y1) return;
        int[] cur = new int[GX * GY];
        for (int j = 0; j < GY; j++) {
            int y = (int) (y1 + (y2 - y1) * (j + 0.5f) / GY);
            for (int i = 0; i < GX; i++) {
                int x = (int) (w * (i + 0.5f) / GX);
                cur[j * GX + i] = Vision.gray(f.rgb(x, y));
            }
        }
        if (last != null) {
            int changed = 0;
            for (int i = 0; i < cur.length; i++) if (Math.abs(cur[i] - last[i]) > 20) changed++;
            boolean boardStill = now - lastBoardChange > 900;
            if (changed >= 2 && changed < cur.length / 3 && boardStill && now - lastEvent > 600) {
                events[eventCount % events.length] = now;
                eventCount++;
                lastEvent = now;
            }
        }
        last = cur;
    }

    /** True if a ticking clock was seen recently. */
    public boolean live(long now) {
        int recent = 0;
        int n = Math.min(eventCount, events.length);
        for (int i = 0; i < n; i++) if (now - events[i] < 8000) recent++;
        return recent >= 3;
    }

    public void reset() {
        eventCount = 0;
        last = null;
    }
}
