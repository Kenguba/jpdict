package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Real IME regression: observe screen bounds on every drawn frame after actual calibration. */
public final class SearchKeyboardInstrumentation extends Instrumentation {
    private final List<Frame> frames = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private FloatingSearchView root;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private View composer;
    private EditText input;
    private Context context;
    private boolean recording, baselineReady;
    private String phase = "setup";
    private int checks, baselineTop, baselineBottom, baselineCache;
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        if (recording && composer.getHeight() > 0) frames.add(capture());
        return true;
    };
    private final Runnable requestFrame = new Runnable() {
        @Override public void run() {
            if (recording && root != null) { root.invalidate(); root.postOnAnimation(this); }
        }
    };
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Throwable failure = null;
        try { verify(); } catch (Throwable error) { failure = error; }
        finally {
            runOnMainSync(() -> {
                recording = false;
                if (root != null) {
                    root.removeCallbacks(requestFrame);
                    root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
                    root.dispose();
                    if (root.isAttachedToWindow()) windows.removeViewImmediate(root);
                }
            });
        }
        summarize();
        if (failure == null) report.append("Search keyboard: ").append(checks).append(" assertions passed\n");
        else { StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); report.append(trace); }
        result.putString("stream", report.toString());
        finish(failure == null ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED, result);
    }
    private void verify() throws Exception {
        context = getTargetContext();
        check(!"com.yuen.jpdict".equals(context.getPackageName()), "Use an independent disposable QA applicationId");
        check(Build.VERSION.SDK_INT >= 30, "Real keyboard validation requires API 30+");
        check(Settings.canDrawOverlays(context), "Grant overlay access to the disposable QA package");
        windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        runOnMainSync(() -> {
            root = new FloatingSearchView(context, "keyboard", false, new FloatingSearchView.Callback() {
                public void onQueryChanged(String value) { }
                public void onLookup(String value, boolean privacy) { }
                public void onPrivateModeChanged(boolean value) { }
                public void onEditingChanged(boolean editing) { setFocusable(editing); }
                public void onOpenPage(String page, boolean privacy) { }
                public void onClose() { }
            });
            params = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0);
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            windows.addView(root, params); root.requestApplyInsets(); root.requestFocus();
            composer = (View)field("composer"); input = (EditText)field("input");
        });
        await(() -> root.hasWindowFocus() && root.isLaidOut() && composer.getHeight() > 0,
                "Search overlay receives real focus and a measured composer");
        runOnMainSync(() -> input.setText("keyboard"));
        // Calibrate from the real keyboard rather than injecting Insets or predicting its size.
        setPhase("calibration show"); runOnMainSync(() -> root.focusInput());
        int calibratedIme = settledIme(true); Thread.sleep(450);
        setPhase("calibration hide"); runOnMainSync(() -> root.handleBack());
        settledIme(false); Thread.sleep(450);
        runOnMainSync(() -> {
            Frame baseline = capture(); baselineTop = baseline.top; baselineBottom = baseline.bottom;
            baselineCache = baseline.cache; baselineReady = true;
            report.append("Baseline screen composer=").append(baselineTop).append("..").append(baselineBottom)
                    .append(", settled IME=").append(calibratedIme).append(", saved keyboard=")
                    .append(baselineCache).append("px\n");
            root.getViewTreeObserver().addOnPreDrawListener(preDraw);
            recording = true; root.postOnAnimation(requestFrame);
        });
        for (int cycle = 1; cycle <= 4; cycle++) {
            setPhase("cycle " + cycle + " show"); runOnMainSync(() -> root.focusInput());
            int shown = settledIme(true);
            check(shown == calibratedIme, "Settled keyboard height changed in cycle " + cycle
                    + ": expected " + calibratedIme + ", actual " + shown);
            Thread.sleep(300);
            setPhase("cycle " + cycle + " hide"); runOnMainSync(() -> root.handleBack());
            settledIme(false); Thread.sleep(300);
        }
        String[] prefixes = {"key", "keyb", "keyboard"};
        for (int i = 0; i < prefixes.length; i++) {
            setPhase("candidate update " + (i + 1)); final int index = i;
            runOnMainSync(() -> {
                input.setText(prefixes[index]);
                showSuggestions(index == 1 ? Arrays.asList("keyboard", "keycap", "keycode", "keyhole", "keypad", "keystone")
                        : Arrays.asList("keyboard", "keycap"));
            });
            Thread.sleep(450);
        }
        runOnMainSync(() -> recording = false);
        check(callbackFailure.get() == null, "Window focus callbacks complete: " + callbackFailure.get());
        check(frames.size() >= 20, "Pre-draw sampling covers actual show/hide frames");
        int moved = 0, changedCache = 0;
        for (Frame frame : frames) {
            if (Math.abs(frame.top - baselineTop) > 1 || Math.abs(frame.bottom - baselineBottom) > 1) moved++;
            if (frame.cache != baselineCache) changedCache++;
        }
        check(changedCache == 0, "Same-height keyboard changed its calibrated cache in " + changedCache + " frames");
        check(moved == 0, "Composer screen bounds moved during " + moved + " sampled frames");
    }
    /** Match FloatingService.setSearchFocusable on Android 11+. */
    private void setFocusable(boolean editing) {
        if (params == null || root == null || !root.isAttachedToWindow()) return;
        boolean focused = (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (focused == editing) return;
        if (editing) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        try { windows.updateViewLayout(root, params); }
        catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); }
    }
    private void showSuggestions(List<String> words) {
        try { Method method = FloatingSearchView.class.getDeclaredMethod("showSuggestions", List.class);
            method.setAccessible(true); method.invoke(root, words); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private Object field(String name) {
        try { Field field = FloatingSearchView.class.getDeclaredField(name); field.setAccessible(true); return field.get(root); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private void setPhase(String value) { runOnMainSync(() -> phase = value); }
    private int settledIme(boolean visible) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 7000, unchangedSince = 0; int previous = -1;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Integer> current = new AtomicReference<>(-1);
            runOnMainSync(() -> {
                WindowInsets insets = root.getRootWindowInsets();
                if (insets != null && insets.isVisible(WindowInsets.Type.ime()) == visible) {
                    int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    if (visible ? bottom > 0 : bottom == 0) current.set(bottom);
                }
            });
            int height = current.get();
            if (height >= 0 && height == previous) {
                if (SystemClock.uptimeMillis() - unchangedSince >= 450) return height;
            } else { previous = height; unchangedSince = SystemClock.uptimeMillis(); }
            if (callbackFailure.get() != null) throw new AssertionError("Focus callback failed", callbackFailure.get());
            Thread.sleep(35);
        }
        throw new AssertionError("Real IME did not settle " + (visible ? "shown" : "hidden") + " during " + phase);
    }
    private interface Condition { boolean value(); }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 7000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            runOnMainSync(() -> ready.set(condition.value()));
            if (ready.get()) { check(true, message); return; }
            Thread.sleep(35);
        }
        throw new AssertionError(message);
    }
    private Frame capture() {
        int[] point = new int[2], rootPoint = new int[2];
        composer.getLocationOnScreen(point); root.getLocationOnScreen(rootPoint);
        WindowInsets insets = root.getRootWindowInsets();
        int ime = insets == null ? -1 : insets.getInsets(WindowInsets.Type.ime()).bottom;
        String key = "height_" + context.getResources().getConfiguration().orientation;
        int cache = Math.round(context.getSharedPreferences("search_keyboard", Context.MODE_PRIVATE)
                .getFloat(key, 280) * context.getResources().getDisplayMetrics().density);
        return new Frame(phase, SystemClock.uptimeMillis(), point[1], point[1] + composer.getHeight(),
                rootPoint[1], root.getHeight(), ime, cache);
    }
    private void summarize() {
        Map<String, List<Frame>> phases = new LinkedHashMap<>();
        for (Frame frame : frames) phases.computeIfAbsent(frame.phase, key -> new ArrayList<>()).add(frame);
        for (Map.Entry<String, List<Frame>> entry : phases.entrySet()) {
            int minTop = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE, minBottom = Integer.MAX_VALUE;
            int maxBottom = Integer.MIN_VALUE, minIme = Integer.MAX_VALUE, maxIme = Integer.MIN_VALUE;
            for (Frame frame : entry.getValue()) {
                minTop = Math.min(minTop, frame.top); maxTop = Math.max(maxTop, frame.top);
                minBottom = Math.min(minBottom, frame.bottom); maxBottom = Math.max(maxBottom, frame.bottom);
                minIme = Math.min(minIme, frame.ime); maxIme = Math.max(maxIme, frame.ime);
            }
            report.append(entry.getKey()).append(": frames=").append(entry.getValue().size())
                    .append(", composer top=").append(minTop).append("..").append(maxTop)
                    .append(", bottom=").append(minBottom).append("..").append(maxBottom)
                    .append(", IME=").append(minIme).append("..").append(maxIme).append('\n');
        }
        int details = 0;
        if (baselineReady) for (Frame frame : frames) {
            if (Math.abs(frame.top - baselineTop) <= 1 && Math.abs(frame.bottom - baselineBottom) <= 1
                    && frame.cache == baselineCache) continue;
            report.append("Moved ").append(frame.phase).append(" @").append(frame.time)
                    .append(": composer=").append(frame.top).append("..").append(frame.bottom)
                    .append(", root=").append(frame.rootTop).append('+').append(frame.rootHeight)
                    .append(", IME=").append(frame.ime).append(", saved=").append(frame.cache).append('\n');
            if (++details >= 24) { report.append("Further moved frames omitted\n"); break; }
        }
    }
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Frame {
        final String phase; final long time;
        final int top, bottom, rootTop, rootHeight, ime, cache;
        Frame(String phase, long time, int top, int bottom, int rootTop, int rootHeight, int ime, int cache) {
            this.phase = phase; this.time = time; this.top = top; this.bottom = bottom;
            this.rootTop = rootTop; this.rootHeight = rootHeight; this.ime = ime; this.cache = cache;
        }
    }
}
