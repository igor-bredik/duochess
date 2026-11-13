package app.duochess.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What empty squares and pieces look like on this screen. Learned from the starting position
 * and extended while tracking, then used to classify every square of a frame.
 */
public final class Calibration {
    public static final int EMPTY = 0, WHITE = 1, BLACK = 2, UNKNOWN = 3;

    /** Observation of one square. */
    public static final class Obs {
        public int state;
        /** Most likely of EMPTY / WHITE / BLACK, even when state is UNKNOWN. */
        public int best;
        /** Most likely piece code (signed, 0 = empty). */
        public int code;
        public float conf;
        public float[] desc;
        public int ring;
    }

    private static final class Template {
        final int code;
        final int parity;
        final float[] d;

        Template(int code, int parity, float[] d) {
            this.code = code;
            this.parity = parity;
            this.d = d;
        }
    }

    private final List<Template> templates = new ArrayList<>();
    /** Background colour of empty squares for image parity 0 and 1. */
    private final int[] parityColor = new int[2];
    private static final float[] ZERO = new float[Vision.DESC];

    private Calibration() {}

    /**
     * Learns from a frame showing position pos. Image squares marked in skip (may be null) are ignored.
     */
    public static Calibration build(Frame f, BoardGeom g, boolean whiteBottom, Position pos, boolean[] skip) {
        Calibration c = new Calibration();
        c.measureBackground(f, g);
        for (int r = 0; r < 8; r++) {
            for (int col = 0; col < 8; col++) {
                int img = r * 8 + col;
                if (skip != null && skip[img]) continue;
                int ring = Vision.ringColor(f, g, r, col);
                int code = pos.b[Vision.chessSquare(r, col, whiteBottom)];
                float[] d = Vision.descriptor(f, g, r, col, ring);
                c.add(code, (r + col) & 1, d);
            }
        }
        return c;
    }

    /** Background colour of the two kinds of squares, averaged over the board. */
    private void measureBackground(Frame f, BoardGeom g) {
        long[][] acc = new long[2][4];
        for (int r = 0; r < 8; r++) {
            for (int col = 0; col < 8; col++) {
                int ring = Vision.ringColor(f, g, r, col);
                int par = (r + col) & 1;
                acc[par][0] += (ring >> 16) & 255;
                acc[par][1] += (ring >> 8) & 255;
                acc[par][2] += ring & 255;
                acc[par][3]++;
            }
        }
        for (int p = 0; p < 2; p++) {
            long n = Math.max(1, acc[p][3]);
            parityColor[p] = (int) ((acc[p][0] / n) << 16 | (acc[p][1] / n) << 8 | (acc[p][2] / n));
        }
    }

    /** Copy of what is known now; later learning does not change it. */
    public Calibration snapshot() {
        Calibration c = new Calibration();
        c.templates.addAll(templates);
        c.parityColor[0] = parityColor[0];
        c.parityColor[1] = parityColor[1];
        return c;
    }

    /** The same piece looks, with the square colours of the board now at g in f. */
    public Calibration forFrame(Frame f, BoardGeom g) {
        Calibration c = snapshot();
        c.measureBackground(f, g);
        return c;
    }

    /** True if pieces of this kind (signed code, 0 = empty) have been seen. */
    public boolean knows(int code) {
        if (code == 0) return true;
        for (Template t : templates) if (t.code == code) return true;
        return false;
    }

    /**
     * Distance from d to the closest look of every kind of piece: index code + 6
     * (0..12, 6 = empty). Float.MAX_VALUE for pieces never seen.
     */
    public float[] distances(float[] d) {
        float[] out = new float[13];
        Arrays.fill(out, Float.MAX_VALUE);
        out[6] = Vision.mav(d);
        for (Template t : templates) {
            float x = Vision.dist(d, t.d);
            int k = t.code + 6;
            if (x < out[k]) out[k] = x;
        }
        return out;
    }

    private static final int MAGIC = 0x44434331;

    public void write(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(Vision.DESC);
        out.writeInt(parityColor[0]);
        out.writeInt(parityColor[1]);
        out.writeInt(templates.size());
        for (Template t : templates) {
            out.writeInt(t.code);
            out.writeInt(t.parity);
            for (float v : t.d) out.writeFloat(v);
        }
    }

    public static Calibration read(DataInput in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != Vision.DESC) throw new IOException("unknown format");
        Calibration c = new Calibration();
        c.parityColor[0] = in.readInt();
        c.parityColor[1] = in.readInt();
        int n = in.readInt();
        if (n < 1 || n > 1000) throw new IOException("bad template count " + n);
        for (int i = 0; i < n; i++) {
            int code = in.readInt();
            int parity = in.readInt();
            if (code < -6 || code > 6 || (parity & ~1) != 0) throw new IOException("bad template");
            float[] d = new float[Vision.DESC];
            for (int k = 0; k < d.length; k++) d[k] = in.readFloat();
            c.templates.add(new Template(code, parity, d));
        }
        return c;
    }

    private int count(int code, int parity) {
        int n = 0;
        for (Template t : templates) if (t.code == code && t.parity == parity) n++;
        return n;
    }

    private void add(int code, int parity, float[] d) {
        int limit = code == 0 ? 6 : 3;
        if (count(code, parity) < limit) templates.add(new Template(code, parity, d));
    }

    /** Remembers how a piece looks on a square, if this kind of piece/square is still rare. */
    public void learn(int code, int parity, float[] d) {
        if (code != 0) add(code, parity, d);
    }

    public Obs classify(float[] d, int ring) {
        float dE = Vision.mav(d);
        float dW = Float.MAX_VALUE, dB = Float.MAX_VALUE;
        int cW = Position.P, cB = -Position.P;
        for (Template t : templates) {
            float x = Vision.dist(d, t.d);
            if (t.code == 0) {
                if (x < dE) dE = x;
            } else if (t.code > 0) {
                if (x < dW) { dW = x; cW = t.code; }
            } else {
                if (x < dB) { dB = x; cB = t.code; }
            }
        }
        Obs o = new Obs();
        o.desc = d;
        o.ring = ring;
        float best, second;
        if (dE <= dW && dE <= dB) {
            o.best = EMPTY; o.code = 0; best = dE; second = Math.min(dW, dB);
        } else if (dW <= dB) {
            o.best = WHITE; o.code = cW; best = dW; second = Math.min(dE, dB);
        } else {
            o.best = BLACK; o.code = cB; best = dB; second = Math.min(dE, dW);
        }
        o.conf = (second - best) / Math.max(second, 1e-3f);
        o.state = o.conf < 0.15f ? UNKNOWN : o.best;
        return o;
    }

    /** Classifies all 64 image squares (index row*8+col). */
    public Obs[] observe(Frame f, BoardGeom g) {
        Obs[] out = new Obs[64];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int ring = Vision.ringColor(f, g, r, c);
                out[r * 8 + c] = classify(Vision.descriptor(f, g, r, c, ring), ring);
            }
        }
        return out;
    }

    /** True if the frame still shows the board at g (enough squares have the expected colours). */
    public boolean visible(Obs[] obs) {
        int ok = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (Vision.diff(obs[r * 8 + c].ring, parityColor[(r + c) & 1]) <= 40) ok++;
            }
        }
        return ok >= 40;
    }

    public static float[] zero() {
        return ZERO;
    }
}
