package com.yuen.jpdict;

import android.graphics.Bitmap;

/** One ephemeral capture, shared by preview and refinement without early recycling. */
final class OcrPreviewFrame implements AutoCloseable {
    final long session;
    private Bitmap bitmap;
    private int readers;
    private boolean retired;
    OcrPreviewFrame(long session, Bitmap bitmap) { this.session = session; this.bitmap = bitmap; }
    synchronized Bitmap borrow(long requested) {
        if (retired || requested != session) return null;
        readers++; return bitmap;
    }
    synchronized void release() {
        if (--readers < 0) throw new IllegalStateException("OCR frame release without borrow");
        recycleIfIdle();
    }
    @Override public synchronized void close() { retired = true; recycleIfIdle(); }
    private void recycleIfIdle() {
        if (retired && readers == 0 && bitmap != null) { bitmap.recycle(); bitmap = null; }
    }
}
