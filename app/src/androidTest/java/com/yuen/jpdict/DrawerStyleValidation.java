package com.yuen.jpdict;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
        validation.fireflyTheme(test, context);
        validation.dividerThemes(test, context);
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
                int foreground = AppTheme.isDark() ? AppTheme.color(AppTheme.PAGE) : android.graphics.Color.WHITE;
                int background = AppTheme.color(0xff151416);
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

    private void fireflyTheme(Instrumentation test, Context context) throws Exception {
        DrawerWaterfallPreview scene = new DrawerWaterfallPreview(context, new View(context));
        Object weather = field(DrawerWaterfallPreview.class, "weather").get(scene);
        field(weather.getClass(), "next").setLong(weather, Long.MAX_VALUE);
        Object noise = field(DrawerWaterfallPreview.class, "NOISE").get(null);
        java.lang.reflect.Method fast = noise.getClass().getDeclaredMethod("fast", double.class, double.class);
        fast.setAccessible(true);
        // Fixtures evaluated from the supplied HTML's Mulberry32 and Float32 noise table.
        for (double[] sample : new double[][]{{0, 0, .724674642086029}, {2.7, 4.2, .7040954619617463},
                {-1.2, -300.3, .30034633593369575}, {800.3, 999.8, .8426149335594052}})
            check(Math.abs(((Number)fast.invoke(noise, sample[0], sample[1])).doubleValue() - sample[2]) < .000001,
                    "Native noise matches the HTML fixture, including wrapped negative coordinates");
        float[][] flies = (float[][])field(DrawerWaterfallPreview.class, "flies").get(scene);
        check(Math.abs(flies[0][0] + 144.14030665252358) < .0001
                        && Math.abs(flies[0][1] + 373.19211871922016) < .0001,
                "Native firefly initialization retains the HTML seed and position");
        java.util.Calendar noon = java.util.Calendar.getInstance();
        noon.set(2026, 9, 19, 12, 0, 0); noon.set(java.util.Calendar.MILLISECOND, 0);
        long day = noon.getTimeInMillis(), night = day - 12 * 3600_000;
        Bitmap first = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        onMain(test, () -> {
            scene.draw(new Canvas(first), 300, 300, day);
            AppTheme.mode(1);
            scene.draw(new Canvas(second), 300, 300, day);
            check(first.sameAs(second), "Scene time is independent of the app theme");
            check(android.graphics.Color.alpha(second.getPixel(150, 40)) == 255, "Noon filtering preserves the scene alpha");
            scene.draw(new Canvas(second), 300, 300, night);
            check(android.graphics.Color.green(first.getPixel(150, 40)) > android.graphics.Color.green(second.getPixel(150, 40)),
                    "The same forest follows the phone's local day and night");
            Bitmap boundary = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
            Canvas shifted = new Canvas(boundary); shifted.drawColor(0xff40516b); shifted.translate(50, 50);
            scene.draw(shifted, 200, 200, night);
            check(boundary.getPixel(0, 0) == 0xff40516b && boundary.getPixel(299, 299) == 0xff40516b,
                    "Scene tint and water stay inside the moving page and preserve the drawer underneath");
            boundary.recycle();
            field(DrawerWaterfallPreview.class, "elapsed").setLong(scene, 8000);
            scene.draw(new Canvas(second), 300, 300, day);
            check(!first.sameAs(second), "HTML lake and fireflies move with their retained animation time");
            scene.resume();
        });
        SystemClock.sleep(60);
        onMain(test, () -> {
            scene.pause();
            long elapsed = field(DrawerWaterfallPreview.class, "elapsed").getLong(scene);
            check(elapsed >= 8060, "Pausing retains elapsed scene time");
            scene.draw(new Canvas(first), 300, 300, day);
            scene.draw(new Canvas(second), 300, 300, day);
            check(first.sameAs(second), "Paused frames remain stable");
            for (int hour : new int[]{0, 3, 5, 7, 10, 12, 15, 18, 20, 23}) {
                noon.set(java.util.Calendar.HOUR_OF_DAY, hour);
                scene.draw(new Canvas(second), 300, 300, noon.getTimeInMillis());
            }
            noon.set(java.util.Calendar.HOUR_OF_DAY, 17); noon.set(java.util.Calendar.MINUTE, 32);
            scene.draw(new Canvas(second), 300, 300, noon.getTimeInMillis());
            int dusk = second.getPixel(50, 170);
            check(android.graphics.Color.green(dusk) > android.graphics.Color.red(dusk) * 1.15
                            && android.graphics.Color.green(dusk) > android.graphics.Color.blue(dusk) * 1.1,
                    "Dusk preserves the forest green instead of a grey-purple matte");
            saveScene(context, second, "nature-dusk");
            scene.draw(new Canvas(second), 300, 300, night);
            saveScene(context, second, "nature-night");
            field(weather.getClass(), "from").setFloat(weather, 1);
            field(weather.getClass(), "to").setFloat(weather, 1);
            scene.draw(new Canvas(second), 300, 300, day);
            saveScene(context, second, "nature-rain");
            check(field(weather.getClass(), "rain").getFloat(weather) == 1, "Rain renders at its stable target intensity");
            field(weather.getClass(), "from").setFloat(weather, 0);
            field(weather.getClass(), "to").setFloat(weather, 0);
            long start = System.nanoTime();
            for (int frame = 0; frame < 20; frame++) {
                field(DrawerWaterfallPreview.class, "elapsed").setLong(scene, 9000 + frame * 34);
                scene.draw(new Canvas(second), 300, 300, day);
            }
            android.util.Log.i("JPDictValidation", "Nature frame mean ms: " + (System.nanoTime() - start) / 20_000_000d);
            saveScene(context, second, "nature-day");
            java.util.TimeZone zone = java.util.TimeZone.getDefault();
            try {
                java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                utc.set(2026, 9, 19, 0, 0, 0);
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT"));
                scene.draw(new Canvas(first), 300, 300, utc.getTimeInMillis());
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+12"));
                scene.draw(new Canvas(second), 300, 300, utc.getTimeInMillis() + 1000);
                check(android.graphics.Color.green(first.getPixel(150, 40)) < android.graphics.Color.green(second.getPixel(150, 40)),
                        "A phone time-zone change recalibrates the scene from midnight to noon");
            } finally { java.util.TimeZone.setDefault(zone); }
            field(weather.getClass(), "next").setLong(weather, 0);
            scene.draw(new Canvas(first), 300, 300, day);
            long next = field(weather.getClass(), "next").getLong(weather);
            float target = field(weather.getClass(), "to").getFloat(weather);
            check(next - day >= 300_000 && next - day <= 900_000, "Local random weather holds for five to fifteen minutes");
            check(target == 0 || target >= .35f && target <= 1, "Rain targets stay within the supported intensity range");
            scene.draw(new Canvas(second), 300, 300, day + 30_000);
            check(field(weather.getClass(), "next").getLong(weather) == next
                            && field(weather.getClass(), "rain").getFloat(weather) == target,
                    "Weather reaches its target without rolling again during the stable period");
            scene.draw(new Canvas(second), 300, 300, day - 1000);
            check(field(weather.getClass(), "changed").getLong(weather) == day - 1000,
                    "Moving the phone clock backwards restarts the weather transition safely");
            AppTheme.mode(0);
        });
        first.recycle(); second.recycle();
    }

    private static void saveScene(Context context, Bitmap image, String name) throws java.io.IOException {
        java.io.File dir = context.getExternalFilesDir("drawer-validation");
        if (dir == null || !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("QA scene directory");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void dividerThemes(Instrumentation test, Context context) throws Exception {
        Activity page = test.startActivitySync(new Intent(context, DictionaryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            onMain(test, () -> {
                FloatingBubbleSettingsView bubble = new FloatingBubbleSettingsView(context, FloatingBubbleAppearance.defaults(), c -> {}, () -> {});
                View[] lines = {divider(page.getWindow().getDecorView()), divider(bubble.stickyHeader())};
                check(lines[0] != null && lines[1] != null, "Both settings pages expose their existing full-width divider");
                for (int mode : new int[]{0, 1, 0}) {
                    AppTheme.mode(mode);
                    for (View line : lines) {
                        check(line.getVisibility() == (mode == 1 ? View.INVISIBLE : View.VISIBLE), "Only dark mode hides the divider");
                        check(line.getLayoutParams().height == Math.round(context.getResources().getDisplayMetrics().density), "Hiding preserves divider spacing");
                    }
                }
            });
        } finally { onMain(test, page::finish); }
    }
    private static View divider(View view) {
        if (view.getBackground() instanceof android.graphics.drawable.ColorDrawable
                && ((android.graphics.drawable.ColorDrawable)view.getBackground()).getColor() == AppTheme.color(0x66e6e8eb)) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = divider(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
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
            onMain(test, () -> {
                View decor = activity.getWindow().getDecorView();
                check(textView(decor, "已收起 · 轻触展开") == null
                                && textView(decor, "已展开 · 轻触收起") == null,
                        "Preview header omits the extra state label");
                TextView hint = (TextView)textView(decor, "↔ 左右滑动预览 · 长按滑块保存比例");
                check(hint != null && hint.getText() instanceof Spanned, "Preview hint has styled text");
                Spanned styled = (Spanned)hint.getText();
                RelativeSizeSpan[] sizes = styled.getSpans(0, 1, RelativeSizeSpan.class);
                ForegroundColorSpan[] colors = styled.getSpans(0, 1, ForegroundColorSpan.class);
                check(sizes.length == 1 && Math.abs(sizes[0].getSizeChange() - 19f / 12) < .01f
                                && colors.length == 1 && colors[0].getForegroundColor() == 0xfff9b748,
                        "Hint arrow matches the larger gold HTML treatment");
            });
            onMain(test, () -> AppTheme.mode(1)); test.waitForIdleSync(); SystemClock.sleep(100);
            screenshot(test, context, "drawer-settings-dark");
            onMain(test, () -> AppTheme.mode(0)); test.waitForIdleSync();
            View slider = (View)field(DrawerStyleSettingsActivity.class, "slider").get(activity);
            DrawerPreviewView preview = (DrawerPreviewView)field(DrawerStyleSettingsActivity.class, "preview").get(activity);
            float density = context.getResources().getDisplayMetrics().density;
            float left = 28 * density, right = slider.getWidth() - 76 * density;
            check(Math.round(preview.progress() * 100) == 0, "Saved width starts with a closed, independent preview");
            onMain(test, () -> {
                long now = SystemClock.uptimeMillis();
                touch(preview, now, MotionEvent.ACTION_DOWN, 20 * density);
                touch(preview, now + 16, MotionEvent.ACTION_MOVE, 60 * density);
                check(preview.progress() > 0, "First-entry drag works from the stage outside the phone frame");
                touch(preview, now + 32, MotionEvent.ACTION_CANCEL, 60 * density);
                preview.seek(0);
            });
            onMain(test, () -> {
                DrawerMotionController motion = (DrawerMotionController)field(DrawerPreviewView.class, "motion").get(preview);
                long now = SystemClock.uptimeMillis();
                preview.seek(0);
                touch(preview, now, MotionEvent.ACTION_DOWN, 150 * density);
                touch(preview, now + 380, MotionEvent.ACTION_MOVE, 90 * density);
                touch(preview, now + 400, MotionEvent.ACTION_MOVE, 70 * density);
                check(motion.spring.velocity < 0, "Preview keeps HTML finger velocity while the closed page is clamped");
                touch(preview, now + 401, MotionEvent.ACTION_UP, 70 * density);
                check(motion.spring.target == 0, "A left drag on a closed preview never becomes a tap to open");
                preview.seek(100);
                touch(preview, now + 1000, MotionEvent.ACTION_DOWN, 150 * density);
                touch(preview, now + 1400, MotionEvent.ACTION_MOVE, 110 * density);
                touch(preview, now + 1401, MotionEvent.ACTION_UP, 110 * density);
                check(motion.spring.target == 0, "HTML slow left drag closes even above the old position threshold");
                preview.seek(30);
                touch(preview, now + 2000, MotionEvent.ACTION_DOWN, 80 * density);
                touch(preview, now + 2016, MotionEvent.ACTION_MOVE, 120 * density);
                touch(preview, now + 2032, MotionEvent.ACTION_CANCEL, 120 * density);
                check(Math.abs(motion.spring.target - .3f) < .001f, "Cancel restores the actual partial preview");
                preview.seek(0);
                touch(preview, now + 3000, MotionEvent.ACTION_DOWN, 40 * density);
                touch(preview, now + 3016, MotionEvent.ACTION_MOVE, 70 * density);
                field(DrawerPreviewView.class, "auraTime").setLong(preview, SystemClock.uptimeMillis() - 160);
                Bitmap glow = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas glowCanvas = new Canvas(glow); glowCanvas.drawColor(AppTheme.color(AppTheme.STAGE));
                preview.draw(glowCanvas);
                int center = glow.getPixel(Math.round(70 * density), preview.getHeight() / 2);
                check(android.graphics.Color.red(center) > android.graphics.Color.blue(center) && android.graphics.Color.red(center) > 180,
                        "The tracked finger center renders the HTML warm glow in light mode");
                java.io.File dir = context.getExternalFilesDir("drawer-validation");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "touch-aura.png"))) {
                    glow.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                glow.recycle();
                touch(preview, now + 3032, MotionEvent.ACTION_CANCEL, 70 * density);
                preview.seek(0);
                android.graphics.RectF phone = (android.graphics.RectF)field(DrawerPreviewView.class, "phone").get(preview);
                int sw = context.getResources().getConfiguration().screenWidthDp, sh = context.getResources().getConfiguration().screenHeightDp;
                near(phone.top / density - 6, sh <= 740 ? 34 : 36, "HTML border-box top margin");
                near(phone.width() / density + 12, sh <= 740 ? 153 : sw <= 365 ? 162 : 174, "HTML outer frame width");
            });
            int stageHeight = context.getResources().getConfiguration().screenHeightDp <= 740 ? 242
                    : context.getResources().getConfiguration().screenWidthDp <= 365 ? 250 : 258;
            check(slider.getParent() != null
                    && ((View)slider.getParent()).getLayoutParams().height == Math.round(stageHeight * density),
                    "Preview stage uses the matching responsive HTML height");
            long down = SystemClock.uptimeMillis();
            onMain(test, () -> { touch(slider, down, MotionEvent.ACTION_DOWN, left + 30 * density);
                touch(slider, down + 1, MotionEvent.ACTION_MOVE, left + (right - left) * .4f);
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
                touch(slider, now + 1, MotionEvent.ACTION_MOVE, left + (right - left) * .75f);
                touch(slider, now + 2, MotionEvent.ACTION_UP, left + (right - left) * .7f); });
            check(new DrawerStyleSettings(context).ratio(DrawerStyleSettings.Mode.FULL_PAGE) == 100
                    && Math.round(preview.progress() * 100) == 50, "Moving the locked thumb cancels adjustment like HTML");
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
            onMain(test, () -> {
                long now = SystemClock.uptimeMillis();
                touch(preview, now, MotionEvent.ACTION_DOWN, 20 * density);
                touch(preview, now + 16, MotionEvent.ACTION_MOVE, 60 * density);
                check(preview.progress() > 0, "Closing with the rail keeps the saved width available to drag open again");
                touch(preview, now + 32, MotionEvent.ACTION_CANCEL, 60 * density);
                preview.seek(0);
            });
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
