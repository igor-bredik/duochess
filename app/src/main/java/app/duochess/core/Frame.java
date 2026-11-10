package app.duochess.core;

/** A captured screen frame. rgb() returns 0xRRGGBB. */
public interface Frame {
    int width();

    int height();

    int rgb(int x, int y);
}
