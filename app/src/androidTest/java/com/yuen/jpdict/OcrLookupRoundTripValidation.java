package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import androidx.core.content.ContextCompat;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Opt-in: real input, capture, models, lookup windows and Back in a disposable QA package. */
final class OcrLookupRoundTripValidation {
    static String run(Instrumentation test) throws Exception {
        Context app = test.getTargetContext();
        if (!app.getPackageName().endsWith(".repeatqa"))
            throw new AssertionError("This test requires the disposable .repeatqa applicationId");
        WindowManager windows = (WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        AtomicReference<String> word = new AtomicReference<>("first");
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK); ink.setTextSize(48);
        List<String> events = new ArrayList<>();
        List<Long> elapsed = new ArrayList<>();
        boolean[] permissionClosed = {false};
        BroadcastReceiver trace = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (MainActivity.OcrActivity.ACTION_FINISHED.equals(intent.getAction()))
                    permissionClosed[0] = intent.getBooleanExtra("granted", false);
                String[] words = intent.getStringArrayExtra("words");
                events.add(intent.getAction() + " session=" + intent.getLongExtra("session", -1)
                        + " words=" + Arrays.toString(words) + " refined=" + intent.getBooleanExtra("refined", false)
                        + " text=" + intent.getStringExtra("text") + " error=" + intent.getStringExtra("error"));
                Bundle status = new Bundle(); status.putString("stream", events.get(events.size()-1) + "\n");
                test.sendStatus(0, status);
            }
        };
        IntentFilter filter = new IntentFilter(MainActivity.ScreenCaptureService.ACTION_PREVIEW);
        filter.addAction(MainActivity.ScreenCaptureService.ACTION_STOPPED);
        filter.addAction(MainActivity.OcrActivity.ACTION_FINISHED);
        ContextCompat.registerReceiver(app, trace, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        View paper = new View(app) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
                canvas.drawText(word.get(), 200, 500, ink);
            }
        };
        WindowManager.LayoutParams paperParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        paperParams.gravity = Gravity.TOP | Gravity.START;
        paperParams.alpha = 0.8f;
        if (Build.VERSION.SDK_INT >= 30) paperParams.setFitInsetsTypes(0);
        try {
            main(test, () -> {
                OcrLanguageSettings.get(app).setLanguage(OcrLanguageSettings.Language.ENGLISH);
                LookupDestinationSettings.get(app).select(LookupDestinationSettings.Model.FAST);
                app.startActivity(new Intent(app, MainActivity.OcrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            waitFor(test, () -> MainActivity.ScreenCaptureService.READY && permissionClosed[0],
                    60_000, "Approve the QA capture dialog and let it close");
            main(test, () -> {
                windows.addView(paper, paperParams);
                app.startService(new Intent(app, FloatingService.class));
            });
            waitFor(test, () -> service(FloatingService.class) != null, 5000, "Floating service missing");
            AtomicReference<FloatingService> floating = new AtomicReference<>();
            AtomicReference<MainActivity.ScreenCaptureService> capture = new AtomicReference<>();
            main(test, () -> {
                floating.set(service(FloatingService.class));
                capture.set(service(MainActivity.ScreenCaptureService.class));
                View bubble = (View)field(floating.get(), "bubble");
                try {
                    Field listeners = View.class.getDeclaredField("mListenerInfo"); listeners.setAccessible(true);
                    Object info = listeners.get(bubble);
                    Field touch = info.getClass().getDeclaredField("mOnTouchListener"); touch.setAccessible(true);
                    View.OnTouchListener original = (View.OnTouchListener)touch.get(info);
                    bubble.setOnTouchListener((view, event) -> {
                        long received = SystemClock.uptimeMillis();
                        boolean handled = original.onTouch(view, event);
                        events.add("touch=" + event.getActionMasked() + " event=" + event.getEventTime()
                                + " received=" + received + " xy=" + event.getRawX() + "," + event.getRawY()
                                + " moved=" + field(original, "moved") + " requested=" + field(original, "ocrRequested")
                                + " generation=" + field(original, "generation"));
                        return handled;
                    });
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            waitFor(test, () -> paper.getWidth() > 0, 5000, "Test page was not laid out");
            Object projection = capture.get().projection;
            for (int round = 0; round < 20; round++) {
                String expected = round % 2 == 0 ? "first" : "second";
                word.set(expected);
                int[] origin = new int[2];
                main(test, () -> paper.getLocationOnScreen(origin));
                float aimX = origin[0] + 200 + ink.measureText(expected) / 2;
                float aimY = origin[1] + 500 + (ink.ascent() + ink.descent()) / 2;
                float gap = 12 * app.getResources().getDisplayMetrics().density;
                AtomicReference<Rect> position = new AtomicReference<>();
                main(test, () -> {
                    paper.invalidate();
                    OcrPickupController owner = (OcrPickupController)field(floating.get(), "pickup");
                    OcrPickupController.Host host = (OcrPickupController.Host)field(owner, "host");
                    host.position();
                    Rect before = host.position();
                    host.move(Math.round(aimX - 120 - before.width()/2f), Math.round(aimY + gap));
                });
                Thread.sleep(250);
                main(test, () -> {
                    View bubble = (View)field(floating.get(), "bubble");
                    int[] location = new int[2]; bubble.getLocationOnScreen(location);
                    position.set(new Rect(location[0], location[1], location[0]+bubble.getWidth(), location[1]+bubble.getHeight()));
                });
                Rect start = position.get();
                long started = SystemClock.uptimeMillis();
                inject(test, started, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY());
                Thread.sleep(40);
                inject(test, started, MotionEvent.ACTION_MOVE, aimX, start.exactCenterY());
                Thread.sleep(350);
                if (round == 1) {
                    app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_STOPPED).setPackage(app.getPackageName()));
                    waitFor(test, () -> events.stream().anyMatch(event -> event.startsWith(MainActivity.ScreenCaptureService.ACTION_STOPPED)),
                            5000, "Delayed stop was not delivered");
                    test.waitForIdleSync();
                }
                inject(test, started, MotionEvent.ACTION_UP, aimX, start.exactCenterY());
                waitFor(test, () -> {
                    View lookup = (View)field(floating.get(), "lookupOverlay");
                    return lookup != null && lookup.isAttachedToWindow()
                            && expected.equals(field(floating.get(), "lookupOverlayQuery"));
                }, 25_000, "No real lookup for round " + (round+1) + " expected=" + expected);
                elapsed.add(SystemClock.uptimeMillis()-started);
                main(test, () -> {
                    if (field(floating.get(), "bubbleTopProxy") == null)
                        throw new AssertionError("Real lookup must transfer the bubble to its proxy");
                });
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                waitFor(test, () -> field(floating.get(), "lookupOverlay") == null
                        && field(floating.get(), "bubbleTopProxy") == null
                        && ((View)field(floating.get(), "bubble")).isShown(), 5000, "Back did not restore the real bubble");
                main(test, () -> {
                    if (service(FloatingService.class) != floating.get()
                            || service(MainActivity.ScreenCaptureService.class) != capture.get()
                            || capture.get().projection != projection || !capture.get().captureAlive()
                            || capture.get().previewInFlight || capture.get().pendingPreview != null)
                        throw new AssertionError("Round trip did not preserve the idle services and projection");
                });
            }
            return "Real input + MediaProjection + ML Kit + lookup/Back: 20/20; alternating first/second; delayed old stop in round 2; same services/projection; ms=" + elapsed + "\n";
        } catch (Throwable failure) {
            int[] origin = new int[2];
            AtomicReference<String> recent = new AtomicReference<>();
            main(test, () -> {
                paper.getLocationOnScreen(origin);
                recent.set(events.subList(Math.max(0, events.size()-24), events.size()).toString());
            });
            throw new AssertionError("paperOrigin=" + Arrays.toString(origin) + "; events=" + recent.get(), failure);
        } finally {
            main(test, () -> {
                app.unregisterReceiver(trace);
                if (paper.isAttachedToWindow()) windows.removeViewImmediate(paper);
                app.stopService(new Intent(app, FloatingService.class));
                app.stopService(new Intent(app, MainActivity.ScreenCaptureService.class));
            });
        }
    }

    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static <T> T service(Class<T> type) {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object current = thread.getMethod("currentActivityThread").invoke(null);
            Field services = thread.getDeclaredField("mServices"); services.setAccessible(true);
            for (Object instance : ((Map<?,?>)services.get(current)).values())
                if (type.isInstance(instance)) return type.cast(instance);
            return null;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void inject(Instrumentation test, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Input rejected");
        } finally { event.recycle(); }
    }
    private static void main(Instrumentation test, Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static void waitFor(Instrumentation test, BooleanSupplier condition, long timeout, String error) throws Exception {
        long until = SystemClock.uptimeMillis()+timeout;
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        do {
            main(test, () -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(25);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError(error);
    }
}
