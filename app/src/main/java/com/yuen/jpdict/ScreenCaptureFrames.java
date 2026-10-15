package com.yuen.jpdict;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

/** The projection's continuously drained buffer. All access is on its Handler. */
final class ScreenCaptureFrames implements AutoCloseable {
    private ImageReader reader;
    private Image latest;
    private RuntimeException failure;

    ScreenCaptureFrames(int width, int height, Handler handler) {
        // One retained frame plus two acquisition slots lets acquireLatestImage
        // discard queued older frames without blocking the display producer.
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            if (source != reader) return;
            try {
                Image next = source.acquireLatestImage();
                if (next == null) return;
                if (latest != null) latest.close();
                latest = next;
            } catch (RuntimeException error) {
                failure = error;
            }
        }, handler);
    }

    Surface surface() { return reader.getSurface(); }

    // Borrowed until the next frame callback. The caller copies on the same
    // Handler and does not close it: a static screen may emit no further frame.
    Image latest() {
        if (failure != null) throw failure;
        return latest;
    }

    @Override public void close() {
        ImageReader current = reader;
        reader = null;
        if (current == null) return;
        current.setOnImageAvailableListener(null, null);
        if (latest != null) { latest.close(); latest = null; }
        current.close();
    }
}
