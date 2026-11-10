package app.duochess.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Low-level image work: per-square descriptors and finding the board on screen.
 * Image squares are addressed by (row, col) from the top-left of the board as seen on screen.
 */
public final class Vision {
    private Vision() {}

    public static final int GRID = 12;
    public static final int DESC = GRID * GRID;

    private static final int SEG_TOL = 24;
    private static final int ALT_SAME = 26;
    private static final int ALT_DIFF = 30;

    public static int gray(int rgb) {
        return (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000;
    }

    public static int diff(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
    }

    private static int px(Frame f, float x, float y) {
        int ix = Math.max(0, Math.min(f.width() - 1, (int) x));
        int iy = Math.max(0, Math.min(f.height() - 1, (int) y));
        return f.rgb(ix, iy);
    }

    /** Background colour of a square: median of samples near its left and right edges. */
    public static int ringColor(Frame f, BoardGeom g, int row, int col) {
        float sx = g.x0 + col * g.sq, sy = g.y0 + row * g.sq;
        int[] r = new int[14], gg = new int[14], b = new int[14];
        int n = 0;
        for (int i = 0; i < 7; i++) {
            float fy = sy + g.sq * (0.2f + 0.1f * i);
            for (int side = 0; side < 2; side++) {
                float fx = sx + g.sq * (side == 0 ? 0.05f : 0.95f);
                int c = px(f, fx, fy);
                r[n] = (c >> 16) & 255;
                gg[n] = (c >> 8) & 255;
                b[n] = c & 255;
                n++;
            }
        }
        Arrays.sort(r);
        Arrays.sort(gg);
        Arrays.sort(b);
        return (r[7] << 16) | (gg[7] << 8) | b[7];
    }

    private static final int MASK_T = 30;

    private static int fg(int c, int ring) {
        return diff(c, ring) > MASK_T ? gray(c) : 0;
    }

    /**
     * GRID x GRID grey values of the square's inner part, with background pixels (those close to
     * the square's own background colour) set to zero. Independent of square colour and highlights.
     */
    public static float[] descriptor(Frame f, BoardGeom g, int row, int col, int ring) {
        float[] d = new float[DESC];
        float sx = g.x0 + col * g.sq, sy = g.y0 + row * g.sq;
        float lo = 0.12f, span = 0.76f;
        int k = 0;
        for (int j = 0; j < GRID; j++) {
            float y = sy + g.sq * (lo + span * (j + 0.5f) / GRID);
            for (int i = 0; i < GRID; i++) {
                float x = sx + g.sq * (lo + span * (i + 0.5f) / GRID);
                int s = fg(px(f, x, y), ring) + fg(px(f, x + 1, y), ring) + fg(px(f, x, y + 1), ring)
                        + fg(px(f, x + 1, y + 1), ring);
                d[k++] = s / 4f;
            }
        }
        return d;
    }

    public static float mav(float[] d) {
        float s = 0;
        for (float v : d) s += Math.abs(v);
        return s / d.length;
    }

    public static float signedMean(float[] d) {
        float s = 0;
        for (float v : d) s += v;
        return s / d.length;
    }

    public static float dist(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    // ---------------------------------------------------------------- detection

    /** Result of finding a board in (nearly) the starting position. */
    public static final class Detection {
        public BoardGeom geom;
        public boolean whiteBottom;
        /** Image squares (row*8+col) that did not look like the starting position. */
        public boolean[] odd = new boolean[64];
        public int oddCount;
    }

    private static final class RowHit {
        int y;
        float x0, len;
        boolean lightFirst;
    }

    private static RowHit scanRow(Frame f, int y, int[] st, int[] ln, int[] co) {
        int w = f.width();
        int n = 0;
        int x = 0;
        int minLen = Math.max(4, w / 40);
        while (x < w) {
            int c0 = f.rgb(x, y);
            int s = x;
            x++;
            while (x < w && diff(f.rgb(x, y), c0) <= SEG_TOL) x++;
            int len = x - s;
            if (len >= minLen) {
                st[n] = s;
                ln[n] = len;
                co[n] = f.rgb(s + len / 2, y);
                n++;
            }
        }
        for (int i = 0; i + 8 <= n; i++) {
            float L = (st[i + 6] + ln[i + 6] - st[i + 1]) / 6f;
            if (L < w / 16f) continue;
            boolean ok = true;
            float maxGap = Math.max(3f, L * 0.12f);
            for (int j = i; j < i + 7 && ok; j++) {
                int gap = st[j + 1] - (st[j] + ln[j]);
                if (gap > maxGap) ok = false;
            }
            for (int j = i + 1; j <= i + 6 && ok; j++) {
                if (ln[j] < 0.75f * L || ln[j] > 1.2f * L) ok = false;
            }
            if (!ok || ln[i] < 0.7f * L || ln[i + 7] < 0.7f * L) continue;
            for (int j = i; j < i + 6 && ok; j++) if (diff(co[j], co[j + 2]) > ALT_SAME) ok = false;
            for (int j = i; j < i + 7 && ok; j++) if (diff(co[j], co[j + 1]) < ALT_DIFF) ok = false;
            if (!ok) continue;
            RowHit h = new RowHit();
            h.y = y;
            h.len = L;
            h.x0 = st[i + 1] - L;
            h.lightFirst = gray(co[i]) > gray(co[i + 1]);
            return h;
        }
        return null;
    }

    private static float median(List<Float> v) {
        if (v.isEmpty()) return 0;
        Float[] a = v.toArray(new Float[0]);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    /**
     * Looks for a chess board in (or one move away from) the starting position.
     * Returns null if there is none.
     */
    public static Detection detect(Frame f, StringBuilder log) {
        int w = f.width(), h = f.height();
        int[] st = new int[w], ln = new int[w], co = new int[w];
        List<RowHit> hits = new ArrayList<>();
        int step = Math.max(1, h / 600);
        for (int y = 0; y < h; y += step) {
            RowHit hit = scanRow(f, y, st, ln, co);
            if (hit != null) hits.add(hit);
        }
        if (log != null) log.append("row hits: ").append(hits.size()).append('\n');
        if (hits.size() < 6) return null;
        List<Float> xs = new ArrayList<>(), ls = new ArrayList<>();
        for (RowHit r : hits) {
            xs.add(r.x0);
            ls.add(r.len);
        }
        float mx = median(xs), ml = median(ls);
        List<RowHit> kept = new ArrayList<>();
        float sumL = 0;
        for (RowHit r : hits) {
            if (Math.abs(r.x0 - mx) <= Math.max(3f, ml * 0.05f) && Math.abs(r.len - ml) <= ml * 0.03f) {
                kept.add(r);
                sumL += r.len;
            }
        }
        if (kept.size() < 6) return null;
        float L = sumL / kept.size();
        List<Float> bounds = new ArrayList<>();
        for (int i = 1; i < kept.size(); i++) {
            RowHit a = kept.get(i - 1), b = kept.get(i);
            if (b.y - a.y <= 3 * step && a.lightFirst != b.lightFirst) bounds.add((a.y + b.y) / 2f);
        }
        if (log != null) log.append(String.format(Locale.US, "kept %d, L=%.2f x0=%.1f, boundaries %d%n", kept.size(), L, mx, bounds.size()));
        if (bounds.isEmpty()) return null;
        float b0 = bounds.get(0);
        float acc = 0;
        for (float bd : bounds) acc += bd - Math.round((bd - b0) / L) * L;
        float yb = acc / bounds.size();
        List<Float> ys = new ArrayList<>();
        for (RowHit r : kept) ys.add((float) r.y);
        float ym = median(ys);
        float top0 = yb + Math.round((ym - 4 * L - yb) / L) * L;
        float[] tries = {top0, top0 - L, top0 + L, top0 - 2 * L, top0 + 2 * L};
        for (float top : tries) {
            if (top < -0.1f * L || top + 8 * L > h + 0.1f * L) continue;
            BoardGeom g = new BoardGeom(mx, top, L);
            Detection d = validateStart(f, g, log);
            if (d != null) return d;
        }
        return null;
    }

    /** Checks that the board at g shows the starting position (allowing one move). */
    public static Detection validateStart(Frame f, BoardGeom g, StringBuilder log) {
        float[] mav = new float[64];
        float[][] desc = new float[64][];
        List<Float> occ = new ArrayList<>(), emp = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int i = r * 8 + c;
                desc[i] = descriptor(f, g, r, c, ringColor(f, g, r, c));
                mav[i] = mav(desc[i]);
                if (r <= 1 || r >= 6) occ.add(mav[i]);
                else emp.add(mav[i]);
            }
        }
        float mo = median(occ), me = median(emp);
        if (log != null) log.append(String.format(Locale.US, "validate %s: occ=%.1f emp=%.1f%n", g, mo, me));
        if (mo < 2.5f * me + 4f) return null;
        float thr = me + Math.max(3f, (mo - me) * 0.2f);
        Detection d = new Detection();
        d.geom = g;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int i = r * 8 + c;
                boolean expOcc = r <= 1 || r >= 6;
                if (expOcc != (mav[i] >= thr)) {
                    d.odd[i] = true;
                    d.oddCount++;
                }
            }
        }
        if (d.oddCount > 2) return null;
        float bottom = 0, top = 0;
        int nb = 0, nt = 0;
        for (int c = 0; c < 8; c++) {
            for (int r : new int[] {0, 1}) if (!d.odd[r * 8 + c]) { top += signedMean(desc[r * 8 + c]); nt++; }
            for (int r : new int[] {6, 7}) if (!d.odd[r * 8 + c]) { bottom += signedMean(desc[r * 8 + c]); nb++; }
        }
        d.whiteBottom = bottom / nb > top / nt;
        if (log != null) log.append("start position found, white at ").append(d.whiteBottom ? "bottom" : "top").append(", odd squares ").append(d.oddCount).append('\n');
        return d;
    }

    /** Maps an image square to a chess square (a1 = 0). */
    public static int chessSquare(int row, int col, boolean whiteBottom) {
        return whiteBottom ? (7 - row) * 8 + col : row * 8 + (7 - col);
    }

    public static int imageIndex(int chessSq, boolean whiteBottom) {
        int f = chessSq & 7, r = chessSq >> 3;
        return whiteBottom ? (7 - r) * 8 + f : r * 8 + (7 - f);
    }
}
