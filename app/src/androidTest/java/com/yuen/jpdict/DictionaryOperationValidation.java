package com.yuen.jpdict;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.service.notification.StatusBarNotification;
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
    private static Notification notice(Context context, int id) {
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification active : manager.getActiveNotifications()) if (active.getId() == id) return active.getNotification();
        return null;
    }
    private static boolean isOrange(int color) {
        int red = android.graphics.Color.red(color), green = android.graphics.Color.green(color), blue = android.graphics.Color.blue(color);
        return red > 210 && red > green + 45 && green > 80 && green < 210 && blue < 100;
    }
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
        NotificationManager notifications = (NotificationManager)instrumentation.getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 24 && !notifications.areNotificationsEnabled()) throw new AssertionError("Operation notification regression needs notification permission on its disposable QA package");
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
                    && ((View)bubble.get(operation)).getWidth() > 0 && dialog.get(operation) == null && operation.running(), "Minimization leaves a live global bubble and running worker");
            overlayOrder(instrumentation, operation, bubble, bubbleParams);
            await(instrumentation, () -> {
                Notification notice = notice(instrumentation.getTargetContext(), 7304);
                return notice != null && (notice.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            }, "Running operation exposes its foreground progress notification");
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
            await(instrumentation, () -> {
                Notification notice = notice(instrumentation.getTargetContext(), 7305);
                return notice != null && notice(instrumentation.getTargetContext(), 7304) == null
                        && notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("完成")
                        && (notice.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_ONLY_ALERT_ONCE)) == 0
                        && (android.os.Build.VERSION.SDK_INT < 31 || notice.contentIntent.isActivity());
            }, "Completion posts an alertable result with a direct Activity tap and removes the ongoing notification");
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && notifications.getNotificationChannel("dictionary-operation-results").getImportance() != NotificationManager.IMPORTANCE_DEFAULT)
                throw new AssertionError("Completion uses a separate default-importance notification channel");
            instrumentation.getTargetContext().stopService(new Intent(instrumentation.getTargetContext(), DictionaryOperationService.class));
            Field activeService = DictionaryOperationService.class.getDeclaredField("current"); activeService.setAccessible(true);
            await(instrumentation, () -> activeService.get(null) == null && notice(instrumentation.getTargetContext(), 7305) != null,
                    "Completion notification remains available after its worker service stops");
            instrumentation.runOnMainSync(operation::restore);
            await(instrumentation, () -> button.get(operation) instanceof TextView && "关闭".contentEquals(((TextView)button.get(operation)).getText()), "Completed task restores a closable completion dialog");
            instrumentation.runOnMainSync(operation::dismiss);
            await(instrumentation, () -> !operation.hasTask() && bubble.get(operation) == null && dialog.get(operation) == null
                    && notice(instrumentation.getTargetContext(), 7305) == null, "Closing completion removes both task windows and its result notice");
            pendingStage(instrumentation, operation, repository, bubble, dialog, button);
            failedStage(instrumentation, operation, repository, bubble, dialog, button);
            return "Dictionary operation: minimize, drag, overlay order, Activity destruction, global restore, repeated mesh animation, persistent completion/failure notifications, pending selection restore and cleanup passed\n";
        } finally {
            release.countDown();
            instrumentation.runOnMainSync(() -> { operation.cancel(); operation.dismiss(); if (!activity.isDestroyed()) activity.finish(); });
        }
    }
    private static void overlayOrder(Instrumentation instrumentation, DictionaryOperation operation, Field bubble, Field bubbleParams) throws Exception {
        Context context = instrumentation.getTargetContext();
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        AtomicReference<View> previous = new AtomicReference<>(), cover = new AtomicReference<>();
        int[] sample = new int[2];
        try {
            instrumentation.runOnMainSync(() -> {
                try {
                    View floating = (View)bubble.get(operation); previous.set(floating);
                    floating.getLocationOnScreen(sample); sample[0] += floating.getWidth() / 2; sample[1] += floating.getHeight() / 2;
                    WindowManager.LayoutParams original = (WindowManager.LayoutParams)bubbleParams.get(operation);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(); params.copyFrom(original);
                    params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    View blocker = new View(context); blocker.setBackgroundColor(0xffff8000); cover.set(blocker); windows.addView(blocker, params);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            instrumentation.waitForIdleSync(); Thread.sleep(150);
            Bitmap obscured = instrumentation.getUiAutomation().takeScreenshot();
            try { if (obscured == null || !isOrange(obscured.getPixel(sample[0], sample[1]))) throw new AssertionError("A later same-type overlay reproduces the hidden progress bubble"); }
            finally { if (obscured != null) obscured.recycle(); }
            instrumentation.runOnMainSync(() -> DictionaryOperation.overlayAdded(context));
            await(instrumentation, () -> bubble.get(operation) != previous.get() && ((View)bubble.get(operation)).isAttachedToWindow(),
                    "Adding an app overlay raises the minimized operation above its new window");
            instrumentation.waitForIdleSync(); Thread.sleep(150);
            Bitmap visible = instrumentation.getUiAutomation().takeScreenshot();
            try { if (visible == null || isOrange(visible.getPixel(sample[0], sample[1]))) throw new AssertionError("Progress remains visible over the new app page"); }
            finally { if (visible != null) visible.recycle(); }
        } finally {
            instrumentation.runOnMainSync(() -> { if (cover.get() != null) windows.removeView(cover.get()); });
        }
    }
    private static void failedStage(Instrumentation instrumentation, DictionaryOperation operation, DictionaryRepository repository,
            Field bubble, Field dialog, Field button) throws Exception {
        DictionaryActivity activity = (DictionaryActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), DictionaryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> operation.start(activity, repository, () -> {
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("QA failure stage timed out");
                throw new java.io.IOException("原创失败说明");
            }, error -> { if (error != null) finished.countDown(); }));
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Failure-stage worker did not start");
            instrumentation.runOnMainSync(() -> {
                try { ((TextView)button.get(operation)).performClick(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            await(instrumentation, () -> bubble.get(operation) != null && dialog.get(operation) == null, "Failure stage is minimized before completion");
            release.countDown();
            if (!finished.await(8, TimeUnit.SECONDS)) throw new AssertionError("Failure-stage callback did not finish");
            await(instrumentation, () -> {
                Notification result = notice(instrumentation.getTargetContext(), 7305);
                return result != null && result.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("失败")
                        && notice(instrumentation.getTargetContext(), 7304) == null;
            }, "Failure has its own clickable result notice without an ongoing progress notification");
            instrumentation.runOnMainSync(operation::restore);
            await(instrumentation, () -> {
                TextView detail = (TextView)field("detail").get(operation);
                return detail != null && detail.getText().toString().contains("原创失败说明");
            }, "Result details retain the actual failure message");
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
            Notification pending = notice(instrumentation.getTargetContext(), 7305);
            if (pending == null || pending.contentIntent == null) throw new AssertionError("Pending selection exposes a notification tap");
            pending.contentIntent.send();
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
