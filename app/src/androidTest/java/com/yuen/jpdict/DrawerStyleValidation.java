package com.yuen.jpdict;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android event, layout, settings and retained-session regressions in a disposable package. */
final class DrawerStyleValidation {
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private void near(float value, float expected, String message) { check(Math.abs(value - expected) < .5f, message); }
    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(int color) {
        double[] c = {android.graphics.Color.red(color) / 255d, android.graphics.Color.green(color) / 255d,
                android.graphics.Color.blue(color) / 255d};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= .04045 ? c[i] / 12.92 : Math.pow((c[i] + .055) / 1.055, 2.4);
        return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
    }

    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if ("com.yuen.jpdict".equals(context.getPackageName())) throw new AssertionError("Use disposable QA applicationId");
        DrawerStyleValidation validation = new DrawerStyleValidation();
        validation.voiceTheme(test, context);
        validation.preferences(test, context);
        validation.search(test, context);
        validation.page(test, context);
        return "Drawer style: " + validation.checks + " Android assertions passed\n";
    }

    private void voiceTheme(Instrumentation test, Context context) throws Exception {
        AtomicReference<FloatingSearchView> holder = new AtomicReference<>();
        onMain(test, () -> holder.set(new FloatingSearchView(context, "", false, new FloatingSearchView.Callback() {
            public void onQueryChanged(String value) {} public void onLookup(String value, boolean privacy) {}
            public void onPrivateModeChanged(boolean privacy) {} public void onEditingChanged(boolean editing) {}
            public void onOpenPage(String page, boolean privacy) {} public void onClose() {}
        })));
        FloatingSearchView voice = holder.get();
        LinearLayout action = (LinearLayout)field(FloatingSearchView.class, "actionButton").get(voice);
        for (int mode : new int[]{0, 1}) {
            onMain(test, () -> AppTheme.mode(mode)); test.waitForIdleSync();
            onMain(test, () -> {
                int foreground = AppTheme.ink(AppTheme.PAGE), background = AppTheme.color(AppTheme.INK);
                check(((SearchIconView)action.getChildAt(0)).getColor() == foreground
                                && contrast(foreground, background) >= 4.5,
                        "Voice button icon stays readable in theme " + mode);
                if (action.getChildCount() > 1) {
                    check(((TextView)action.getChildAt(1)).getCurrentTextColor() == foreground
                                    && contrast(foreground, background) >= 4.5,
                            "Voice button label stays readable in theme " + mode);
                }
            });
        }
        onMain(test, () -> { voice.dispose(); AppTheme.mode(0); });
    }

    private void preferences(Instrumentation test, Context context) throws Exception {
        onMain(test, () -> {
            android.content.SharedPreferences store = context.getSharedPreferences("drawer_style", 0);
            store.edit().clear().commit();
            DrawerStyleSettings settings = new DrawerStyleSettings(context);
            check(settings.mode() == DrawerStyleSettings.Mode.FULL_PAGE && settings.ratio(settings.mode()) == 100,
                    "Fresh install retains original full-page width");
            check(DrawerStyleSettings.Mode.values().length == 3, "Exactly three modes");
            for (DrawerStyleSettings.Mode mode : DrawerStyleSettings.Mode.values()) {
                settings.select(mode); settings.saveRatio(mode, 20 + mode.ordinal());
                DrawerStyleSettings reloaded = new DrawerStyleSettings(context);
                check(reloaded.mode() == mode && reloaded.ratio(mode) == 20 + mode.ordinal(), "Reload preserves each choice and ratio");
            }
            store.edit().putInt("mode", 2).putString("full_page_ratio", "bad type").commit();
            check(settings.mode() == DrawerStyleSettings.Mode.FULL_PAGE && settings.ratio(settings.mode()) == 100,
                    "Bad storage types do not crash or overwrite other preferences");
            store.edit().putString("mode", "future").commit();
            check(settings.mode() == DrawerStyleSettings.Mode.FULL_PAGE, "Unknown mode has a safe first-mode fallback");
            store.edit().clear().commit();
        });
    }

    private void search(Instrumentation test, Context context) throws Exception {
        WindowManager windows = context.getSystemService(WindowManager.class);
        DrawerStyleSettings settings = new DrawerStyleSettings(context);
        CaptureService service = new CaptureService(context);
        AtomicReference<FloatingSearchView> holder = new AtomicReference<>();
        onMain(test, () -> {
            FloatingSearchView view = new FloatingSearchView(context, "private original draft", true, new FloatingSearchView.Callback() {
                public void onQueryChanged(String text) {}
                public void onLookup(String text, boolean privacy) {}
                public void onPrivateModeChanged(boolean privacy) {}
                public void onEditingChanged(boolean editing) {}
                public void onOpenPage(String page, boolean privacy) {}
                public void onClose() {}
            });
            holder.set(view);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            windows.addView(view, params);
            field(FloatingService.class, "wm").set(service, windows);
            field(FloatingService.class, "bubble").set(service, new View(context));
            field(FloatingService.class, "searchPanel").set(service, view);
            field(FloatingService.class, "searchPanelLp").set(service, params);
            field(FloatingService.class, "searchPanelHosted").setBoolean(service, true);
            field(FloatingService.class, "activeHostSession").set(service, "drawer-session");
            field(FloatingService.class, "currentSearchQuery").set(service, "private original draft");
            field(FloatingService.class, "searchPrivateMode").setBoolean(service, true);
        });
        test.waitForIdleSync(); SystemClock.sleep(100);
        FloatingSearchView view = holder.get();
        try {
            for (DrawerStyleSettings.Mode mode : DrawerStyleSettings.Mode.values()) {
                for (int ratio : new int[]{0, 30, 45, 90, 100}) {
                    onMain(test, () -> { settings.select(mode); settings.saveRatio(mode, ratio); view.experienceDrawer(); });
                    SystemClock.sleep(650);
                    onMain(test, () -> {
                        View page = (View)field(FloatingSearchView.class, "page").get(view);
                        View menu = (View)field(FloatingSearchView.class, "drawer").get(view);
                        View dismiss = (View)field(FloatingSearchView.class, "drawerDismiss").get(view);
                        float width = Math.max(1, Math.round(view.getWidth() * ratio / 100f));
                        near(menu.getWidth(), width, "Actual drawer width matches saved percentage");
                        near(page.getTranslationX(), ratio == 0 || mode == DrawerStyleSettings.Mode.OVERLAY ? 0 : width,
                                "Page travel matches actual drawer width");
                        check(ratio > 0 ? menu.getVisibility() == View.VISIBLE : menu.getVisibility() != View.VISIBLE && dismiss.getVisibility() == View.GONE,
                                "Zero percent cannot leave an invisible touch blocker");
                        check(ratio == 0 || mode != DrawerStyleSettings.Mode.PUSH || page.getZ() > menu.getZ(), "Push keeps the home page in front");
                    });
                    if (ratio == 100) screenshot(test, context, "drawer-" + mode.key);
                }
            }
            onMain(test, () -> { settings.select(DrawerStyleSettings.Mode.FULL_PAGE); settings.saveRatio(settings.mode(), 100); });
            nativeGesture(test, view);
            gestures(test, view);
            onMain(test, () -> {
                Intent open = new Intent(FloatingSearchRoutes.ACTION_OPEN_PAGE)
                        .putExtra(FloatingSearchRoutes.EXTRA_PAGE, FloatingSearchRoutes.PAGE_SETTINGS)
                        .putExtra(FloatingSearchRoutes.EXTRA_PRIVATE_MODE, true);
                service.onStartCommand(open, 0, 1);
                check("".equals(service.broadcast.getStringExtra(FloatingSearchRoutes.EXTRA_RETURN_QUERY)), "Private draft never enters route Intent");
                String request = (String)field(FloatingService.class, "pendingSearchPageRequest").get(service);
                service.onStartCommand(new Intent(FloatingSearchRoutes.ACTION_PAGE_READY)
                        .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, request)
                        .putExtra(FloatingService.EXTRA_HOST_SESSION, "drawer-session"), 0, 2);
                check(view.isAttachedToWindow() && view.getVisibility() == View.INVISIBLE, "Settings hides rather than disposes the real search view");
                EditText input = (EditText)field(FloatingSearchView.class, "input").get(view);
                check("private original draft".contentEquals(input.getText()), "The live private input survives settings");
                Intent experience = new Intent(FloatingService.ACTION_SHOW_SEARCH)
                        .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, request)
                        .putExtra(FloatingService.EXTRA_HOST_SESSION, "drawer-session")
                        .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true)
                        .putExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE, "one-shot");
                service.onStartCommand(experience, 0, 3);
            });
            SystemClock.sleep(750);
            onMain(test, () -> {
                check(service.broadcast.getBooleanExtra("success", false), "Experience acknowledges only a mounted real search drawer");
                check("".equals(field(FloatingService.class, "pendingDrawerExperience").get(service)), "Experience is consumed exactly once");
                check(view.getVisibility() == View.VISIBLE && ((FloatingSearchState)field(FloatingSearchView.class, "state").get(view)).isDrawerOpen(),
                        "Experience returns the retained view with its actual drawer open");
                service.onStartCommand(new Intent(FloatingService.ACTION_SHOW_SEARCH)
                        .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, "stale-page")
                        .putExtra(FloatingService.EXTRA_HOST_SESSION, "former-host")
                        .putExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE, "stale-experience"), 0, 4);
                check(!service.broadcast.getBooleanExtra("success", true)
                        && "drawer-session".equals(field(FloatingService.class, "activeHostSession").get(service)), "Stale page cannot replace the current session");
            });
        } finally {
            onMain(test, () -> { view.dispose(); if (view.isAttachedToWindow()) windows.removeViewImmediate(view); });
        }
    }

    private void nativeGesture(Instrumentation test, FloatingSearchView view) throws Exception {
        onMain(test, () -> {
            DrawerMotionController motion = (DrawerMotionController)field(FloatingSearchView.class, "drawerMotion").get(view);
            FloatingSearchState state = (FloatingSearchState)field(FloatingSearchView.class, "state").get(view);
            View page = (View)field(FloatingSearchView.class, "page").get(view);
            state.setDrawerOpen(false); motion.snap(0);
            float d = view.getResources().getDisplayMetrics().density;
            long time = SystemClock.uptimeMillis();
            dispatch(view, time, MotionEvent.ACTION_DOWN, 80 * d, 160 * d);
            dispatch(view, time + 16, MotionEvent.ACTION_MOVE, 160 * d, 160 * d);
            check(page.getTranslationX() > 60 * d, "Real blank-content dispatch moves the drawer before release");
            int height = view.getHeight(); float progress = motion.progress();
            view.layout(0, 0, view.getWidth(), height - (int)(80 * d));
            check(motion.isDragging() && motion.progress() == progress, "Height-only IME resize keeps the active drag and position");
            view.layout(0, 0, view.getWidth(), height);
            dispatch(view, time + 32, MotionEvent.ACTION_UP, 300 * d, 160 * d);
            check(state.isDrawerOpen(), "Real horizontal fling selects open");
            motion.snap(1);
            dispatch(view, time + 50, MotionEvent.ACTION_DOWN, 300 * d, 160 * d);
            dispatch(view, time + 66, MotionEvent.ACTION_MOVE, 200 * d, 160 * d);
            dispatch(view, time + 82, MotionEvent.ACTION_UP, 80 * d, 160 * d);
            check(!state.isDrawerOpen(), "Real reverse drag closes the drawer");
            motion.snap(0);
            dispatch(view, time + 100, MotionEvent.ACTION_DOWN, 2 * d, 160 * d);
            dispatch(view, time + 116, MotionEvent.ACTION_MOVE, 180 * d, 160 * d);
            dispatch(view, time + 132, MotionEvent.ACTION_UP, 250 * d, 160 * d);
            check(!state.isDrawerOpen() && motion.progress() == 0, "System Back edge is never captured as a drawer swipe");
        });
    }

    private static void dispatch(View view, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }

    private void gestures(Instrumentation test, View surface) throws Exception {
        AtomicReference<DrawerMotionController> holder = new AtomicReference<>();
        onMain(test, () -> {
            DrawerMotionController motion = new DrawerMotionController(surface, new DrawerMotionController.Listener() {
                public void onProgress(float value) {}
                public void onDragStart() {}
                public void onRelease(boolean open) {}
            });
            holder.set(motion); motion.configure(1000, 1); motion.snap(.25f);
            long time = SystemClock.uptimeMillis();
            event(motion, time, MotionEvent.ACTION_DOWN, 100, false);
            event(motion, time + 16, MotionEvent.ACTION_MOVE, 300, false);
            near(motion.progress(), .45f, "Drag has no added delay");
            event(motion, time + 32, MotionEvent.ACTION_UP, 500, false);
            check(Math.abs(motion.progress() - .65f) < .0001f && motion.spring.velocity > 10, "Last UP coordinate and release velocity are retained");
            motion.snap(.3f); motion.animate(true, null);
        });
        SystemClock.sleep(50);
        float[] held = new float[1];
        onMain(test, () -> {
            held[0] = holder.get().progress();
            event(holder.get(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 100, true);
        });
        SystemClock.sleep(40);
        onMain(test, () -> {
            check(held[0] == holder.get().progress(), "Touch immediately stops animation at its displayed position");
            event(holder.get(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 100, true);
            check(holder.get().spring.target == 1, "Cancellation restores the pre-gesture target");
            holder.get().stop();
        });
    }

    private static void event(DrawerMotionController motion, long time, int action, float x, boolean open) {
        MotionEvent event = MotionEvent.obtain(time, time, action, x, 200, 0);
        motion.handle(event, true, open); event.recycle();
    }

    private void page(Instrumentation test, Context context) throws Exception {
        onMain(test, () -> {
            DrawerStyleSettings settings = new DrawerStyleSettings(context);
            settings.select(DrawerStyleSettings.Mode.FULL_PAGE); settings.saveRatio(settings.mode(), 100);
        });
        Activity settingsPage = test.startActivitySync(new Intent(context, SearchSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor = test.addMonitor(DrawerStyleSettingsActivity.class.getName(), null, false);
        onMain(test, () -> FloatingSearchRoutes.openFromActivity(settingsPage, FloatingSearchRoutes.PAGE_DRAWER_STYLE, true));
        Activity activity = test.waitForMonitorWithTimeout(monitor, 5000); test.removeMonitor(monitor);
        check(activity instanceof DrawerStyleSettingsActivity, "Settings launches the separately registered native Activity");
        try {
            test.waitForIdleSync(); SystemClock.sleep(100);
            screenshot(test, context, "drawer-settings");
            View slider = (View)field(DrawerStyleSettingsActivity.class, "slider").get(activity);
            DrawerPreviewView preview = (DrawerPreviewView)field(DrawerStyleSettingsActivity.class, "preview").get(activity);
            float density = context.getResources().getDisplayMetrics().density;
            float left = 28 * density, right = slider.getWidth() - 76 * density;
            check(Math.round(preview.progress() * 100) == 0, "Saved width starts with a closed, independent preview");
            int stageHeight = context.getResources().getConfiguration().screenHeightDp <= 740 ? 242
                    : context.getResources().getConfiguration().screenWidthDp <= 365 ? 250 : 258;
            check(slider.getParent() != null
                    && ((View)slider.getParent()).getLayoutParams().height == Math.round(stageHeight * density),
                    "Preview stage uses the matching responsive HTML height");
            long down = SystemClock.uptimeMillis();
            onMain(test, () -> { touch(slider, down, MotionEvent.ACTION_DOWN, left + 30 * density);
                touch(slider, down + 1, MotionEvent.ACTION_MOVE, (left + right) / 2);
                touch(slider, down + 2, MotionEvent.ACTION_UP, (left + right) / 2); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 100
                    && Math.round(preview.progress() * 100) == 50, "Rail drag previews without changing the saved width");
            test.waitForIdleSync(); SystemClock.sleep(100);
            screenshot(test, context, "drawer-settings-half");
            onMain(test, () -> { long now = SystemClock.uptimeMillis(); touch(slider, now, MotionEvent.ACTION_DOWN, right);
                touch(slider, now + 1, MotionEvent.ACTION_UP, right); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 100
                    && Math.round(preview.progress() * 100) == 50, "A quick thumb tap neither saves nor changes the preview");
            onMain(test, () -> { long now = SystemClock.uptimeMillis(); touch(slider, now, MotionEvent.ACTION_DOWN, right);
                touch(slider, now + 1, MotionEvent.ACTION_MOVE, (left + right) / 2);
                touch(slider, now + 2, MotionEvent.ACTION_UP, (left + right) / 2); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 100
                    && Math.round(preview.progress() * 100) == 50, "Moving before the hold threshold cancels thumb adjustment");
            long hold = SystemClock.uptimeMillis();
            onMain(test, () -> touch(slider, hold, MotionEvent.ACTION_DOWN, right));
            SystemClock.sleep(750);
            long move = SystemClock.uptimeMillis();
            onMain(test, () -> { touch(slider, hold, move, MotionEvent.ACTION_MOVE, (left + right) / 2);
                touch(slider, hold, move + 1, MotionEvent.ACTION_UP, (left + right) / 2); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 50,
                    "A stationary 700ms thumb hold enables width adjustment and saves on release");
            onMain(test, () -> { long now = SystemClock.uptimeMillis(); touch(slider, now, MotionEvent.ACTION_DOWN, right);
                touch(slider, now + 1, MotionEvent.ACTION_UP, right); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 50,
                    "Preview cannot expand past the saved width");
            long hold2 = SystemClock.uptimeMillis();
            onMain(test, () -> touch(slider, hold2, MotionEvent.ACTION_DOWN, (left + right) / 2));
            SystemClock.sleep(750);
            long move2 = SystemClock.uptimeMillis();
            onMain(test, () -> { touch(slider, hold2, move2, MotionEvent.ACTION_MOVE, right);
                touch(slider, hold2, move2 + 1, MotionEvent.ACTION_CANCEL, right); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 50
                    && Math.round(preview.progress() * 100) == 50, "Cancelled width adjustment restores both saved width and preview");
            onMain(test, () -> { touch(slider, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, left);
                touch(slider, SystemClock.uptimeMillis() + 1, MotionEvent.ACTION_UP, left); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 50
                    && Math.round(preview.progress() * 100) == 0, "Track tap previews closed without saving width");
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                onMain(test, () -> {
                    android.os.Bundle progress = new android.os.Bundle();
                    progress.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 60);
                    slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, progress);
                });
                check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 60
                        && Math.round(preview.progress() * 100) == 0, "Accessible width changes save without altering preview progress");
            }
            onMain(test, activity::onBackPressed); test.waitForIdleSync();
            check(activity.isFinishing() && !settingsPage.isFinishing(), "Back returns to the existing parent settings page");
            onMain(test, () -> new DrawerStyleSettings(context).saveRatio(DrawerStyleSettings.Mode.FULL_PAGE, 100));
            Instrumentation.ActivityMonitor next = test.addMonitor(DrawerStyleSettingsActivity.class.getName(), null, false);
            onMain(test, () -> FloatingSearchRoutes.openFromActivity(settingsPage, FloatingSearchRoutes.PAGE_DRAWER_STYLE, true));
            Activity experiencePage = test.waitForMonitorWithTimeout(next, 5000); test.removeMonitor(next);
            check(experiencePage != null, "The drawer settings route remains usable after Back");
            test.waitForIdleSync(); SystemClock.sleep(100);
            onMain(test, () -> {
                android.widget.TextView button = (android.widget.TextView)textView(experiencePage.getWindow().getDecorView(), "立即体验当前方式");
                check(button != null, "The native experience button is reachable");
                check(button.getCompoundDrawables()[2] == null, "Experience button has no trailing arrow");
                check((button.getGravity() & android.view.Gravity.CENTER_HORIZONTAL) != 0, "Experience button content is centered");
                check(button.getLineCount() == 1, "Experience button label fits on one centered line");
                button.performClick();
            });
            for (int i = 0; i < 100 && !settingsPage.isFinishing(); i++) SystemClock.sleep(50);
            check(settingsPage.isFinishing() && experiencePage.isFinishing(),
                    "Actual button, parent result, real foreground Service and host complete the acknowledged return");
            SystemClock.sleep(3000);
            screenshot(test, context, "drawer-experience");
        } finally {
            onMain(test, () -> {
                activity.finish(); settingsPage.finish();
                context.stopService(new Intent(context, FloatingService.class));
            });
        }
    }

    private static View textView(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = textView(group.getChildAt(i), text); if (result != null) return result;
            }
        }
        return null;
    }

    private static void touch(View view, long time, int action, float x) {
        touch(view, time, time, action, x);
    }
    private static void touch(View view, long down, long time, int action, float x) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, view.getHeight() / 2f, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private static void screenshot(Instrumentation test, Context context, String name) throws Exception {
        android.graphics.Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(context.getExternalFilesDir(null), name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private interface MainAction { void run() throws Exception; }
    private static void onMain(Instrumentation test, MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static final class CaptureService extends FloatingService {
        Intent broadcast;
        CaptureService(Context context) { attachBaseContext(context); }
        @Override public void sendBroadcast(Intent intent) { broadcast = new Intent(intent); }
        @Override public void startActivity(Intent intent) { throw new AssertionError("The retained session must not create another host"); }
    }
}
