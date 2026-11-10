package app.duochess.core;

/** Where the board is on the screen: top-left corner and square size, in frame pixels. */
public final class BoardGeom {
    public final float x0;
    public final float y0;
    public final float sq;

    public BoardGeom(float x0, float y0, float sq) {
        this.x0 = x0;
        this.y0 = y0;
        this.sq = sq;
    }

    public boolean near(BoardGeom o) {
        return o != null && Math.abs(o.x0 - x0) < sq * 0.12f && Math.abs(o.y0 - y0) < sq * 0.12f
                && Math.abs(o.sq - sq) < sq * 0.05f;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "x0=%.1f y0=%.1f sq=%.2f", x0, y0, sq);
    }
}
