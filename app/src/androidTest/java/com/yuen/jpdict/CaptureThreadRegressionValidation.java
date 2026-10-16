package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Native ImageReader/Bitmap regression coverage, without a real projection grant. */
final class CaptureThreadRegressionValidation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation) throws Exception {
        CaptureThreadRegressionValidation test = new CaptureThreadRegressionValidation();
        test.cancelAndRestart(instrumentation);
        return "Capture worker: " + test.checks + " device assertions passed\n";
    }
    private void cancelAndRestart(Instrumentation instrumentation) throws Exception {
        List<Long> recognized = new ArrayList<>(); List<Bitmap> copies = new ArrayList<>();
        List<Intent> responses = new ArrayList<>();
        MainActivity.ScreenCaptureService service = new MainActivity.ScreenCaptureService() {
            @Override public String getPackageName() { return instrumentation.getTargetContext().getPackageName(); }
            @Override public void sendBroadcast(Intent intent) { responses.add(new Intent(intent)); }
            @Override boolean captureAlive() { return captureFrames != null; }
            @Override void recognizePreview(Bitmap bitmap, long session) {
                check(Looper.myLooper() == Looper.getMainLooper(), "Capture completion returns to the UI owner before touching service state");
                recognized.add(session); copies.add(bitmap); sendPreview(session, Collections.emptyList(), null);
            }
        };
        AtomicReference<ScreenCaptureFrames> source = new AtomicReference<>();
        AtomicReference<ImageWriter> writer = new AtomicReference<>();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        onMain(instrumentation, () -> {
            service.w = 7; service.h = 5;
            source.set(new ScreenCaptureFrames(7, 5, service.captureMain)); service.captureFrames = source.get();
            writer.set(ImageWriter.newInstance(source.get().surface(), 1)); queue(writer.get(), Color.GREEN);
        });
        Handler worker = (Handler) field(source.get(), "worker");
        HandlerThread thread = (HandlerThread) field(source.get(), "thread");
        check(worker.getLooper() != Looper.getMainLooper(), "Image acquisition and bitmap conversion use a background looper");
        worker.post(() -> {
            blocked.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Capture worker was not released"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        check(blocked.await(5, TimeUnit.SECONDS), "The actual capture worker can be delayed independently of the UI");
        try {
            onMain(instrumentation, () -> service.captureOnce(request(1001)));
            SystemClock.sleep(180);
            long started = SystemClock.uptimeMillis();
            onMain(instrumentation, () -> {
                check(service.previewInFlight && service.previewOperation == 1001, "A screenshot waiting on its worker retains only its own operation");
                service.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session", 1001L), 0, 1);
                service.captureOnce(request(1002)); service.captureOnce(request(1003));
                check(service.pendingPreview.getLongExtra("session", -1) == 1003,
                        "While a copy is pending, repeated gestures retain only the latest request");
            });
            check(SystemClock.uptimeMillis() - started < 500, "A blocked capture worker does not block UI cancellation and new gestures");
            release.countDown(); await(instrumentation, () -> recognized.size() == 1);
            onMain(instrumentation, () -> {
                check(recognized.size() == 1 && recognized.get(0) == 1003, "A canceled copy never starts OCR or publishes over the newest gesture");
                check(copies.get(0).getPixel(2, 2) == Color.GREEN && !service.previewInFlight,
                        "The newest request receives an independent valid bitmap and clears its own gate");
            });
            for (int index = 0; index < 20; index++) {
                final long session = 1100 + index; final int expected = recognized.size() + 1;
                onMain(instrumentation, () -> service.captureOnce(request(session)));
                await(instrumentation, () -> recognized.size() == expected);
                onMain(instrumentation, () -> check(recognized.get(expected - 1) == session
                                && copies.get(expected - 1).getPixel(2, 2) == Color.GREEN && !service.previewInFlight,
                        "Twenty repeated requests reuse the static native frame without stale callbacks or a blocked operation"));
            }
            onMain(instrumentation, () -> { service.captureFrames = null; source.get().close(); source.get().close(); });
            thread.join(3000);
            check(!thread.isAlive(), "Closing the capture source is idempotent and terminates its worker");
            CountDownLatch closedResult = new CountDownLatch(1);
            AtomicReference<Boolean> rejected = new AtomicReference<>();
            source.get().copyLatest(null, (bitmap, error, retryable) -> {
                rejected.set(bitmap == null && error != null && !retryable); closedResult.countDown();
            });
            check(closedResult.await(3, TimeUnit.SECONDS), "A request after source destruction returns a bounded completion");
            check(Boolean.TRUE.equals(rejected.get()), "Closed sources reject late requests without retaining images or retry loops");
        } finally {
            release.countDown();
            onMain(instrumentation, () -> {
                service.captureMain.removeCallbacksAndMessages(null); service.captureFrames = null;
                writer.get().close(); source.get().close();
                for (Bitmap bitmap : copies) bitmap.recycle();
            });
        }
    }
    private static Intent request(long session) {
        return new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session", session)
                .putExtra("pickup_mode", OcrPickupMode.ENGLISH.value);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void queue(ImageWriter writer, int color) {
        Image image = writer.dequeueInputImage(); Image.Plane plane = image.getPlanes()[0]; ByteBuffer buffer = plane.getBuffer();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int offset = y * plane.getRowStride() + x * plane.getPixelStride();
            buffer.put(offset, (byte) Color.red(color)); buffer.put(offset + 1, (byte) Color.green(color));
            buffer.put(offset + 2, (byte) Color.blue(color)); buffer.put(offset + 3, (byte) 255);
        }
        writer.queueInputImage(image);
    }
    private interface Ready { boolean ready(); }
    private static void await(Instrumentation instrumentation, Ready ready) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> result = new AtomicReference<>(); onMain(instrumentation, () -> result.set(ready.ready()));
            if (result.get()) return; SystemClock.sleep(20);
        }
        throw new AssertionError("Capture completion timed out");
    }
    private static void onMain(Instrumentation instrumentation, Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("UI capture regression failed", failure.get());
    }
}
