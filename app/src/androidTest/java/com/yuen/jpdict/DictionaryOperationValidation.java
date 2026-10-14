package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only operation lifecycle regression; no dictionary is imported or deleted. */
final class DictionaryOperationValidation {
    private static Field field(String name) throws Exception {
        Field result = DictionaryOperation.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private interface Check { boolean run() throws Exception; }
    private static void await(Instrumentation instrumentation, Check test, String message) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            AtomicReference<Throwable> error = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { result.set(test.run()); } catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw new AssertionError(message, error.get());
            if (result.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError(message);
    }
    static String run(Instrumentation instrumentation) throws Exception {
        if (!android.provider.Settings.canDrawOverlays(instrumentation.getTargetContext())) throw new AssertionError("Operation lifecycle regression needs overlay permission on its disposable QA package");
        DictionaryActivity activity = (DictionaryActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), DictionaryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DictionaryOperation operation = DictionaryOperation.get(instrumentation.getTargetContext());
        DictionaryRepository repository = DictionaryRepository.get(instrumentation.getTargetContext());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        Field dialog = field("dialog"), bubble = field("bubble"), button = field("button"), bubbleParams = field("bubbleParams");
        try {
            instrumentation.runOnMainSync(() -> {
                if (!operation.start(activity, repository, () -> {
                    operation.progress("验证原创测试词库", 3, 10); entered.countDown();
                    if (!release.await(60, TimeUnit.SECONDS)) throw new java.io.IOException("QA operation timed out");
                }, failure -> { if (failure == null) finished.countDown(); })) throw new AssertionError("QA operation was not started");
            });
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("QA worker did not start");
            await(instrumentation, () -> {
                TextView action = (TextView)button.get(operation);
                return action != null && "后台加载".contentEquals(action.getText()) && operation.running();
            }, "Running dialog exposes 后台加载");
            instrumentation.runOnMainSync(() -> {
                try { ((TextView)button.get(operation)).performClick(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            await(instrumentation, () -> bubble.get(operation) instanceof View && ((View)bubble.get(operation)).isAttachedToWindow()
                    && dialog.get(operation) == null && operation.running(), "Minimization leaves a live global bubble and running worker");
            instrumentation.runOnMainSync(() -> {
                try {
                    View floating = (View)bubble.get(operation);
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams)bubbleParams.get(operation);
                    int x = params.x, y = params.y; long now = android.os.SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100, 100, 0);
                    MotionEvent move = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_MOVE, 20, 20, 0);
                    MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, 20, 20, 0);
                    floating.dispatchTouchEvent(down); floating.dispatchTouchEvent(move); floating.dispatchTouchEvent(up);
                    down.recycle(); move.recycle(); up.recycle();
                    if (params.x >= x || params.y >= y) throw new AssertionError("Progress bubble can be freely dragged to another screen position");
                    activity.finish();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            await(instrumentation, () -> activity.isDestroyed() && operation.running(), "Destroying the library Activity must not cancel its operation");
            instrumentation.runOnMainSync(operation::restore);
            await(instrumentation, () -> {
                android.app.AlertDialog window = (android.app.AlertDialog)dialog.get(operation);
                return window != null && window.isShowing() && window.getWindow().getAttributes().type
                        == (android.os.Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
            }, "Restoring after Activity destruction creates a global overlay dialog");
            instrumentation.runOnMainSync(() -> {
                try { ((TextView)button.get(operation)).performClick(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            await(instrumentation, () -> bubble.get(operation) != null && dialog.get(operation) == null, "Restored overlay can minimize a second time");
            // Consecutive hardware-rendered mesh transitions used to recycle a
            // bitmap while a render-thread draw still referenced it.
            for (int cycle = 0; cycle < 6; cycle++) {
                instrumentation.runOnMainSync(operation::restore);
                await(instrumentation, () -> dialog.get(operation) != null && bubble.get(operation) == null,
                        "Repeated restore must remove the previous bubble");
                Thread.sleep(450);
                instrumentation.runOnMainSync(() -> {
                    try { ((TextView)button.get(operation)).performClick(); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                await(instrumentation, () -> bubble.get(operation) != null && dialog.get(operation) == null,
                        "Repeated minimize must complete without recycled bitmap access");
            }
            release.countDown();
            if (!finished.await(8, TimeUnit.SECONDS)) throw new AssertionError("Background operation did not complete successfully");
            await(instrumentation, () -> {
                View floating = (View)bubble.get(operation);
                return floating != null && floating.getContentDescription().toString().contains("完成") && !operation.running();
            }, "Completion updates the global bubble label and state");
            instrumentation.runOnMainSync(operation::restore);
            await(instrumentation, () -> button.get(operation) instanceof TextView && "关闭".contentEquals(((TextView)button.get(operation)).getText()), "Completed task restores a closable completion dialog");
            instrumentation.runOnMainSync(operation::dismiss);
            await(instrumentation, () -> !operation.hasTask() && bubble.get(operation) == null && dialog.get(operation) == null, "Closing completion removes both task windows");
            pendingStage(instrumentation, operation, repository, bubble, dialog, button);
            return "Dictionary operation: minimize, drag, Activity destruction, global restore, repeated mesh animation, background completion, pending selection restore and cleanup passed\n";
        } finally {
            release.countDown();
            instrumentation.runOnMainSync(() -> { operation.cancel(); operation.dismiss(); if (!activity.isDestroyed()) activity.finish(); });
        }
    }
    private static void pendingStage(Instrumentation instrumentation, DictionaryOperation operation, DictionaryRepository repository,
            Field bubble, Field dialog, Field button) throws Exception {
        DictionaryActivity scanner = (DictionaryActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), DictionaryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<DictionaryActivity> continued = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> operation.start(scanner, repository, () -> {
                operation.progress("扫描测试词库目录", 1, 2); entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("QA scan stage timed out");
            }, failure -> { if (failure == null) finished.countDown(); }, continued::set));
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Pending-stage worker did not start");
            instrumentation.runOnMainSync(() -> {
                try { ((TextView)button.get(operation)).performClick(); scanner.finish(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            await(instrumentation, () -> scanner.isDestroyed() && bubble.get(operation) != null, "Scan caller is destroyed while work is minimized");
            release.countDown();
            if (!finished.await(8, TimeUnit.SECONDS)) throw new AssertionError("Background scan did not complete");
            await(instrumentation, () -> {
                View floating = (View)bubble.get(operation);
                return operation.requiresActivity() && continued.get() == null && dialog.get(operation) == null
                        && floating != null && floating.getContentDescription().toString().contains("继续选择");
            }, "Background scan retains its continuation without opening a chooser on the old Activity");
            instrumentation.runOnMainSync(operation::restore);
            await(instrumentation, () -> continued.get() != null && continued.get() != scanner && !continued.get().isDestroyed(),
                    "Explicit restore delivers pending selection to a new foreground library Activity");
            await(instrumentation, () -> !operation.hasTask() && bubble.get(operation) == null && dialog.get(operation) == null,
                    "Consuming the pending stage releases its completed progress windows");
        } finally {
            release.countDown();
            instrumentation.runOnMainSync(() -> {
                operation.cancel(); operation.dismiss();
                if (!scanner.isDestroyed()) scanner.finish();
                DictionaryActivity restored = continued.get(); if (restored != null && !restored.isDestroyed()) restored.finish();
            });
        }
    }

}
