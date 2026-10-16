package com.yuen.jpdict;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;

/** The projection's continuously drained buffer and bitmap copies share one worker. */
final class ScreenCaptureFrames implements AutoCloseable {
    interface CopyCallback { void onCopied(Bitmap bitmap, Exception error, boolean retryable); }
    private final ImageReader reader;
    private final HandlerThread thread = new HandlerThread("jpdict-screen-capture");
    private final Handler worker;
    private final Handler callbackHandler;
    private volatile boolean closed;
    private Image latest;
    private RuntimeException failure;

    ScreenCaptureFrames(int width, int height, Handler handler) {
        callbackHandler = handler;
        // One retained frame plus two acquisition slots lets acquireLatestImage
        // discard queued older frames without blocking the display producer.
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        thread.start(); worker = new Handler(thread.getLooper());
        reader.setOnImageAvailableListener(source -> {
            if (closed) return;
            try {
                Image next = source.acquireLatestImage();
                if (next == null) return;
                if (latest != null) latest.close();
                latest = next;
                failure = null;
            } catch (RuntimeException error) {
                failure = error;
            }
        }, worker);
    }

    Surface surface() { return reader.getSurface(); }

    // Native Image buffers never leave this worker: a new frame or close cannot
    // invalidate a buffer while its pixels are being copied. Static screens keep
    // the last native frame, while each OCR request owns its independent bitmap.
    void copyLatest(Rect crop, CopyCallback callback) {
        if (closed || !worker.post(() -> {
            if (closed) { deliver(callback, null, new IllegalStateException("屏幕捕获已关闭"), false); return; }
            if (failure != null) { deliver(callback, null, failure, true); return; }
            if (latest == null) { deliver(callback, null, null, true); return; }
            try { deliver(callback, copy(latest, crop), null, false); }
            catch (Exception error) { deliver(callback, null, error, false); }
        })) deliver(callback, null, new IllegalStateException("屏幕捕获已关闭"), false);
    }

    private void deliver(CopyCallback callback, Bitmap bitmap, Exception error, boolean retryable) {
        if (!callbackHandler.post(() -> callback.onCopied(bitmap, error, retryable)) && bitmap != null) bitmap.recycle();
    }

    private static Bitmap copy(Image image, Rect crop) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate(); buffer.rewind();
        int width = image.getWidth(), height = image.getHeight(), pixelStride = plane.getPixelStride();
        int padding = plane.getRowStride() - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + padding / pixelStride, height, Bitmap.Config.ARGB_8888);
        Bitmap full = null, result = null;
        try {
            padded.copyPixelsFromBuffer(buffer);
            full = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (crop == null) { result = full; return result; }
            int left = Math.max(0, Math.min(crop.left, width - 1));
            int top = Math.max(0, Math.min(crop.top, height - 1));
            int cropWidth = Math.max(1, Math.min(crop.width(), width - left));
            int cropHeight = Math.max(1, Math.min(crop.height(), height - top));
            result = Bitmap.createBitmap(full, left, top, cropWidth, cropHeight);
            return result;
        } finally {
            if (full != null && full != result && full != padded) full.recycle();
            if (padded != result) padded.recycle();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        // Closing an ImageReader also invalidates acquired Images. Queue disposal
        // behind any copy rather than blocking the UI or racing its native buffer.
        worker.post(() -> {
            reader.setOnImageAvailableListener(null, null);
            if (latest != null) { latest.close(); latest = null; }
            reader.close(); thread.quitSafely();
        });
    }
}
