package com.yuen.jpdict;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Tests real state retention and pixels; never opens production user preferences or dictionaries. */
final class AppThemeValidation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (!context.getPackageName().endsWith(".drawerqa")) throw new AssertionError("Disposable QA package required");
        AppThemeValidation validation = new AppThemeValidation();
        main(test, () -> context.getSharedPreferences("appearance", 0).edit().clear().commit());
        validation.clock(); validation.pages(test, context); validation.retention(test, context);
        validation.html(test, context);
        validation.widgets(test, context);
        main(test, () -> AppTheme.mode(0));
        return "Global theme: " + validation.checks + " Android assertions passed\n";
    }
    private void clock() {
        check(AppTheme.mode() == 0 && AppTheme.start() == 1320 && AppTheme.end() == 420, "First install preserves light and defaults to 22:00–07:00");
        Calendar time = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        for (int minute = 0; minute < 1440; minute++) {
            time.set(Calendar.HOUR_OF_DAY, minute / 60); time.set(Calendar.MINUTE, minute % 60);
            check(AppTheme.darkAt(2, 1320, 420, time) == (minute >= 1320 || minute < 420), "Every minute of the overnight interval");
            check(AppTheme.darkAt(2, 480, 1020, time) == (minute >= 480 && minute < 1020), "Every minute of a same-day interval");
            check(!AppTheme.darkAt(0, 1320, 420, time) && AppTheme.darkAt(1, 1320, 420, time), "Manual modes ignore schedule");
        }
    }
    private void pages(Instrumentation test, Context context) throws Exception {
        Activity page = test.startActivitySync(new Intent(context, SearchSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(test, () -> {
                View root = page.getWindow().getDecorView();
                View title = text(root, "外观"), bubble = text(root, "悬浮按钮");
                View content = ((ViewGroup)page.findViewById(android.R.id.content)).getChildAt(0);
                check(((android.graphics.drawable.ColorDrawable)content.getBackground()).getColor() == 0xfff2f2f2
                                && AppTheme.color(AppTheme.PAPER) == 0xfffcfcfc,
                        "Light settings and search retain their distinct original backgrounds");
                int[] a = new int[2], b = new int[2]; title.getLocationOnScreen(a); bubble.getLocationOnScreen(b);
                check(a[1] < b[1], "Theme control is above the bubble entry in App group");
                check(!description(root, "深色模式开启时间").isShown(), "Manual light hides automatic times");
                description(root, "深色").performClick();
                check(AppTheme.isDark() && !page.isDestroyed(), "Manual dark applies without Activity recreation");
                check(AppTheme.color(AppTheme.PAGE) == 0xff171e2b && AppTheme.color(AppTheme.PAPER) == 0xff171e2b,
                        "Both restored light backgrounds still map to the existing dark page color");
                check(((TextView)title).getCurrentTextColor() == AppTheme.ink(AppTheme.MUTED), "Already-mounted section heading recolors");
                check(!description(root, "深色模式开启时间").isShown(), "Manual dark hides automatic times");
            });
            test.waitForIdleSync(); screenshot(test, context, "theme-settings-dark");
            main(test, () -> {
                View root = page.getWindow().getDecorView(); description(root, "自动").performClick();
                check(description(root, "深色模式开启时间").isShown(), "Automatic exposes time inputs");
                check("22:00".contentEquals(((TextView)description(root, "深色模式开启时间")).getText()), "Default opening label");
                check("07:00".contentEquals(((TextView)description(root, "深色模式关闭时间")).getText()), "Default closing label");
                check(!AppTheme.times(600, 600) && AppTheme.start() == 1320, "Equal times are rejected without overwriting saved interval");
            });
            test.waitForIdleSync(); screenshot(test, context, "theme-settings-auto");
            main(test, () -> description(page.getWindow().getDecorView(), "深色模式开启时间").performClick());
            test.waitForIdleSync(); screenshot(test, context, "theme-time-picker");
            main(test, () -> {
                android.widget.TimePicker before = activePicker();
                before.setHour(23); before.setMinute(17);
                AppTheme.mode(0);
                android.widget.TimePicker after = activePicker();
                check(after != before && after.getHour() == 23 && after.getMinute() == 17,
                        "Open native time picker refreshes theme without losing pending time");
                check(AppTheme.start() == 1320, "Changing theme in time picker does not save an unconfirmed time");
            });
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); test.waitForIdleSync();
            main(test, () -> description(page.getWindow().getDecorView(), "深色").performClick());
        } finally { main(test, page::finish); }
        Activity preview = test.startActivitySync(new Intent(context, DrawerStyleSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.waitForIdleSync(); screenshot(test, context, "theme-drawer-dark");
            for (DrawerStyleSettings.Mode mode : new DrawerStyleSettings.Mode[]{DrawerStyleSettings.Mode.OVERLAY, DrawerStyleSettings.Mode.PUSH}) {
                main(test, () -> ((View[])field(preview, "cards"))[mode.ordinal()].performClick());
                test.waitForIdleSync(); SystemClock.sleep(500);
                screenshot(test, context, "theme-card-" + mode.key);
            }
            main(test, () -> ((View[])field(preview, "cards"))[0].performClick());
            test.waitForIdleSync(); SystemClock.sleep(500);
            main(test, () -> AppTheme.mode(0)); test.waitForIdleSync(); screenshot(test, context, "theme-drawer-light");
            main(test, () -> AppTheme.mode(1));
        } finally { main(test, preview::finish); }
        for (Class<? extends Activity> type : new Class[]{FloatingBubbleSettingsActivity.class, DictionaryActivity.class, DrawerFilesActivity.class}) {
            Activity activity = test.startActivitySync(new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try { test.waitForIdleSync(); screenshot(test, context, "theme-" + type.getSimpleName()); }
            finally { main(test, activity::finish); }
        }
    }
    private void widgets(Instrumentation test, Context context) throws Exception {
        android.appwidget.AppWidgetHost[] host = {null};
        android.appwidget.AppWidgetHostView[] view = {null}; int[] id = {0};
        main(test, () -> {
            host[0] = new android.appwidget.AppWidgetHost(context, 742);
            id[0] = host[0].allocateAppWidgetId();
            android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(context);
            check(manager.bindAppWidgetIdIfAllowed(id[0], new android.content.ComponentName(context, MainActivity.SearchWidgetProvider.class)),
                    "QA widget binding permitted");
            host[0].startListening(); view[0] = host[0].createView(context, id[0], manager.getAppWidgetInfo(id[0]));
        });
        try {
            for (int mode : new int[]{0, 1}) {
                main(test, () -> AppTheme.mode(mode));
                boolean[] updated = {false};
                for (int attempt = 0; attempt < 30 && !updated[0]; attempt++) {
                    SystemClock.sleep(50);
                    main(test, () -> {
                        TextView label = view[0].findViewById(R.id.widget_label);
                        updated[0] = label != null && label.getCurrentTextColor() == AppTheme.ink(AppTheme.INK);
                    });
                }
                check(updated[0], "Already bound widget follows global theme mode " + mode);
            }
        } finally { main(test, () -> { host[0].stopListening(); host[0].deleteAppWidgetId(id[0]); }); }
    }
    static String restart(Instrumentation test, boolean save) throws Exception {
        Context context = test.getTargetContext();
        if (!context.getPackageName().endsWith(".drawerqa")) throw new AssertionError("Disposable QA package required");
        if (save) {
            main(test, () -> { AppTheme.times(1410, 370); AppTheme.mode(1); });
            context.getSharedPreferences("appearance", 0).edit().commit();
            return "Global theme persistence fixture saved\n";
        }
        main(test, () -> {
            if (AppTheme.mode() != 1 || !AppTheme.isDark() || AppTheme.start() != 1410 || AppTheme.end() != 370)
                throw new AssertionError("Theme policy must survive Application initialization after process restart");
        });
        main(test, () -> { AppTheme.times(1320, 420); AppTheme.mode(0); });
        return "Global theme persistence: mode, effective palette and both times survived process restart\n";
    }
    private static android.widget.TimePicker activePicker() throws Exception {
        Class<?> windows = Class.forName("android.view.WindowManagerGlobal");
        Object global = windows.getDeclaredMethod("getInstance").invoke(null);
        for (View root : (java.util.List<View>)field(global, "mViews")) {
            android.widget.TimePicker picker = picker(root); if (picker != null) return picker;
        }
        throw new AssertionError("Native time picker is mounted");
    }
    private static android.widget.TimePicker picker(View view) {
        if (view instanceof android.widget.TimePicker) return (android.widget.TimePicker)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            android.widget.TimePicker child = picker(((ViewGroup)view).getChildAt(i)); if (child != null) return child;
        }
        return null;
    }
    private void retention(Instrumentation test, Context context) throws Exception {
        main(test, () -> {
            AppTheme.mode(0);
            FloatingSearchView search = new FloatingSearchView(context, "私密草稿", true, new FloatingSearchView.Callback() {
                public void onQueryChanged(String value) {} public void onLookup(String value, boolean privacy) {}
                public void onPrivateModeChanged(boolean privacy) {} public void onEditingChanged(boolean editing) {}
                public void onOpenPage(String page, boolean privacy) {} public void onClose() {}
            });
            EditText input = (EditText)field(search, "input"); input.setText("保留 selected draft"); input.setSelection(2, 6);
            FloatingSearchState state = (FloatingSearchState)field(search, "state"); state.setDrawerOpen(true);
            FloatingBubbleAppearance.Builder builder = new FloatingBubbleAppearance.Builder(FloatingBubbleAppearance.load(context));
            builder.backgroundPaint = FloatingBubbleAppearance.defaultPaint(AppTheme.CARD);
            builder.iconPaint = FloatingBubbleAppearance.defaultPaint(AppTheme.INK); builder.legacyVisual = false;
            FloatingBubbleAppearance.Config config = builder.build();
            final int[] edits = {0};
            FloatingBubbleSettingsView editor = new FloatingBubbleSettingsView(context, config, c -> edits[0]++, () -> {}, () -> {});
            int initialEdits = edits[0];
            for (int mode = 0; mode < 3; mode++) {
                builder.backgroundPaint = FloatingBubbleAppearance.defaultPaint(AppTheme.CARD).withMode(mode);
                FloatingBubbleAppearance.Config artwork = builder.build();
                AppTheme.mode(0); Bitmap light = render(context, artwork);
                AppTheme.mode(1); Bitmap dark = render(context, artwork);
                check(light.sameAs(dark), "User solid/linear/free paint pixels are unchanged by global theme: " + mode);
                light.recycle(); dark.recycle();
            }
            check(editor.draft().equals(config) && edits[0] == initialEdits, "Theme switching never edits or saves bubble configuration");
            check(input == field(search, "input") && input.getText().toString().equals("保留 selected draft"), "Same search editor and draft survive theme change");
            check(input.getSelectionStart() == 2 && input.getSelectionEnd() == 6, "Text selection survives theme change");
            check(state.isPrivateMode() && state.isDrawerOpen(), "Private mode and drawer logical state survive");
            check(input.getCurrentTextColor() == AppTheme.ink(AppTheme.INK), "Retained search input is readable in dark mode");
            TextView bannerSubtitle = (TextView)field(field(search, "drawer"), "backendStatus");
            for (int mode : new int[]{0, 1}) {
                AppTheme.mode(mode);
                check(contrast(bannerSubtitle.getCurrentTextColor(), AppTheme.color(AppTheme.BLUE)) >= 4.5,
                        "Bubble banner subtitle remains readable on blue in theme " + mode);
            }
            for (int background : new int[]{AppTheme.PAGE, AppTheme.CARD, AppTheme.SOFT}) {
                check(contrast(AppTheme.ink(AppTheme.INK), AppTheme.color(background)) >= 4.5, "Primary text contrast");
                check(contrast(AppTheme.ink(AppTheme.MUTED), AppTheme.color(background)) >= 4.5, "Secondary text contrast");
            }
            search.dispose();
        });
    }
    private void html(Instrumentation test, Context context) throws Exception {
        String[] columns = {"dict_id","name","package_id","installed_version","source","storage","enabled",
                "sort_order","index_state","status","fingerprint","installed_at","updated_at","files","lookup_expanded"};
        DictionaryRepository.Dictionary dictionary;
        try (MatrixCursor cursor = new MatrixCursor(columns)) {
            cursor.addRow(new Object[]{"theme-fixture","Theme fixture","","","USER_IMPORT","APP_PRIVATE",1,0,"READY","INSTALLED","",0,0,"[]",1});
            cursor.moveToFirst(); dictionary = new DictionaryRepository.Dictionary(cursor);
        }
        AtomicReference<DictionaryEntryView> web = new AtomicReference<>(); CountDownLatch ready = new CountDownLatch(1);
        main(test, () -> web.set(new DictionaryEntryView(context, DictionaryRepository.get(context), dictionary,
                Collections.singletonList("<meta name='color-scheme' content='light dark'><style>body{background:#fff;color:#123456}@media(prefers-color-scheme:dark){body{background:#000;color:#fff}}</style><input id='draft' value='original'>"),
                new DictionaryEntryView.Navigation() { public void lookup(String word) {} public boolean closeOnBack() { return true; } }, ready::countDown)));
        try {
            check(ready.await(10, TimeUnit.SECONDS), "Dictionary HTML loads in dark native theme");
            String before = javascript(test, web.get(), "document.getElementById('draft').value='still here';JSON.stringify([matchMedia('(prefers-color-scheme:dark)').matches,getComputedStyle(document.body).color,getComputedStyle(document.body).backgroundColor])");
            check(before.contains("false") && before.contains("18, 52, 86") && before.contains("255, 255, 255"), "Dictionary HTML keeps its original light preference and author colors");
            main(test, () -> { AppTheme.mode(0); AppTheme.mode(1); });
            check(javascript(test, web.get(), "document.getElementById('draft').value").contains("still here"), "Theme changes do not reload dictionary HTML or lose its input");
        } finally { main(test, () -> web.get().close()); }
    }
    private static Bitmap render(Context context, FloatingBubbleAppearance.Config config) {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        BubbleAppearanceRenderer.draw(new Canvas(bitmap), context, config, new RectF(0,0,128,128)); return bitmap;
    }
    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second); return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    }
    private static double luminance(int color) {
        double[] c = {android.graphics.Color.red(color)/255d,android.graphics.Color.green(color)/255d,android.graphics.Color.blue(color)/255d};
        for (int i=0;i<3;i++) c[i]=c[i]<=.04045?c[i]/12.92:Math.pow((c[i]+.055)/1.055,2.4);
        return c[0]*.2126+c[1]*.7152+c[2]*.0722;
    }
    private static String javascript(Instrumentation test, DictionaryEntryView web, String source) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        main(test, () -> web.evaluateJavascript(source, result -> { value.set(result); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("JavaScript timeout"); return value.get();
    }
    private static View text(View root, String value) { return find(root, value, false); }
    private static View description(View root, String value) { return find(root, value, true); }
    private static View find(View root, String value, boolean description) {
        if (description ? value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())
                : root instanceof TextView && value.contentEquals(((TextView)root).getText())) return root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found=find(((ViewGroup)root).getChildAt(i),value,description); if(found!=null)return found;
        }
        return null;
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void screenshot(Instrumentation test, Context context, String name) throws Exception {
        SystemClock.sleep(150); Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        } finally { bitmap.recycle(); }
    }
    private interface Action { void run() throws Exception; }
    private static void main(Instrumentation test, Action action) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
}
