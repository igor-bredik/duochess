package app.duochess;

import android.media.Image;

import java.nio.ByteBuffer;

import app.duochess.core.Frame;

/** Frame backed by an RGBA_8888 Image from the screen capture. Valid only until the image is closed. */
final class ImageFrame implements Frame {
    private final ByteBuffer buf;
    private final int rowStride;
    private final int pixelStride;
    private final int w;
    private final int h;

    ImageFrame(Image img) {
        Image.Plane p = img.getPlanes()[0];
        buf = p.getBuffer();
        rowStride = p.getRowStride();
        pixelStride = p.getPixelStride();
        w = img.getWidth();
        h = img.getHeight();
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public int rgb(int x, int y) {
        int i = y * rowStride + x * pixelStride;
        return ((buf.get(i) & 255) << 16) | ((buf.get(i + 1) & 255) << 8) | (buf.get(i + 2) & 255);
    }

    /** Copies the frame into ARGB pixels (for saving a debug screenshot). */
    int[] toArgb() {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = 0xFF000000 | rgb(x, y);
        }
        return out;
    }
}
