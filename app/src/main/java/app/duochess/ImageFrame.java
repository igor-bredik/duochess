package app.duochess;

import android.media.Image;

import java.nio.ByteBuffer;

import app.duochess.core.Frame;

/**
 * Copy of an RGBA_8888 image from the screen capture. The buffer is reused from frame to frame,
 * and the last frame stays available after its image is closed (when the screen does not
 * change, no new frames arrive).
 */
final class ImageFrame implements Frame {
    private byte[] data = new byte[0];
    private int rowStride;
    private int pixelStride;
    private int w;
    private int h;

    /** Copies the image's pixels; the image can be closed right after. */
    void copyFrom(Image img) {
        w = 0;
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        buf.rewind();
        int n = buf.remaining();
        if (data.length < n) data = new byte[n];
        buf.get(data, 0, n);
        rowStride = p.getRowStride();
        pixelStride = p.getPixelStride();
        h = img.getHeight();
        w = img.getWidth();
    }

    /** True once a frame has been copied. */
    boolean valid() {
        return w > 0;
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
        return ((data[i] & 255) << 16) | ((data[i + 1] & 255) << 8) | (data[i + 2] & 255);
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
