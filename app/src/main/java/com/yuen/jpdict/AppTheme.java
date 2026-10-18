package com.yuen.jpdict;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

/** One palette and one persisted theme policy. User artwork and dictionary HTML never enter it. */
final class AppTheme {
    static final int PAGE = 0xfff2f2f2, CARD = Color.WHITE, SOFT = 0xfff4f5f6;
    static final int INK = 0xff171819, MUTED = 0xff73777a, FAINT = 0xffa4a7ad;
    static final int BLUE = 0xff006bff, PALE = 0xffe8f2ff, LINE = 0xffe1e5eb;
    static final int PURPLE = 0xff7945a1, LAVENDER = 0xfff2e9fa;
    static final int RED = 0xffcf3e54, ROSE = 0xfffff0f2, GREEN = 0xff118844;
    static final int STAGE = 0xfff0f5fc, RIM = 0xffd9e4f3, MENU = 0xffe2eaf5;
    static final int ACCENT = 0xff006bfe, ACCENT_PALE = 0xfff3f7ff;
    static final int PAPER = 0xfffefefe, ACCENT_INK = 0xfffefeff;
    static final String BOUNDARY = "com.yuen.jpdict.THEME_BOUNDARY";
    private static final int[][] PALETTE = {
            {PAGE, 0xff171e2b}, {CARD, 0xff252e3d}, {SOFT, 0xff303c4e},
            {INK, 0xffedf2fa}, {MUTED, 0xffacb9cd}, {FAINT, 0xff8291a7},
            {BLUE, 0xff246bc8}, {PALE, 0xff243d60}, {LINE, 0xff45536a},
            {PURPLE, 0xffd3b0ef}, {LAVENDER, 0xff40304f}, {RED, 0xffffa0af},
            {ROSE, 0xff4a2c39}, {GREEN, 0xff6bd6b0}, {STAGE, 0xff253349},
            {RIM, 0xff091724}, {MENU, 0xff35455f}, {ACCENT, 0xffffb51c},
            {ACCENT_PALE, 0xff39404a}, {PAPER, 0xff171e2b}, {ACCENT_INK, 0xff241500}
    };
    private interface Refresh { void refresh(); }
    private interface Update { void apply(View view); }
    private static final ArrayList<WeakReference<Refresh>> observers = new ArrayList<>();
    private static SharedPreferences preferences;
    private static Application app;
    private static boolean dark;
    private static final SharedPreferences.OnSharedPreferenceChangeListener changed = (p, key) -> refresh();

    static void init(Application application) {
        if (app != null) return;
        app = application;
        preferences = app.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        dark = darkAt(mode(), start(), end(), Calendar.getInstance());
        app.setTheme(dark ? R.style.Theme_Jpdict_Page_Dark : R.style.Theme_Jpdict_Page);
        preferences.registerOnSharedPreferenceChangeListener(changed);
        IntentFilter clock = new IntentFilter(Intent.ACTION_TIME_TICK);
        clock.addAction(Intent.ACTION_TIME_CHANGED); clock.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        clock.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { refresh(); }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, clock, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, clock);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) { refresh(); }
            public void onActivityCreated(Activity activity, Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}
        });
        schedule();
    }

    static boolean isDark() { return dark; }
    static int mode() { return number("mode", 0, 2); }
    static int start() { return number("start", 22 * 60, 1439); }
    static int end() { return number("end", 7 * 60, 1439); }
    private static int number(String key, int fallback, int max) {
        if (preferences == null) return fallback;
        try { int value = preferences.getInt(key, fallback); return value < 0 || value > max ? fallback : value; }
        catch (ClassCastException bad) { return fallback; }
    }
    static void mode(int mode) {
        if (mode >= 0 && mode <= 2) preferences.edit().putInt("mode", mode).apply();
    }
    static boolean times(int start, int end) {
        if (start < 0 || end < 0 || start > 1439 || end > 1439 || start == end) return false;
        preferences.edit().putInt("start", start).putInt("end", end).apply(); return true;
    }
    static boolean darkAt(int mode, int start, int end, Calendar now) {
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return mode == 1 || mode == 2 && start != end
                && (start < end ? minute >= start && minute < end : minute >= start || minute < end);
    }

    static int color(int light) {
        if (!dark) return light;
        int rgb = light | 0xff000000;
        for (int[] pair : PALETTE) if (pair[0] == rgb) return (light & 0xff000000) | (pair[1] & 0xffffff);
        return light;
    }
    static int ink(int light) { return light == Color.WHITE ? light : dark && light == BLUE ? 0xff8ab9ff : color(light); }

    private static void add(Refresh observer) { observers.add(new WeakReference<>(observer)); }
    private static final class Binding implements Refresh {
        final View view;
        final HashMap<Integer, Update> values = new HashMap<>();
        Binding(View view) { this.view = view; add(this); }
        public void refresh() {
            for (Integer property : new ArrayList<>(values.keySet())) if (property != 0) values.get(property).apply(view);
            if (values.containsKey(0)) values.get(0).apply(view);
            invalidate(view);
        }
    }
    private static void invalidate(View view) {
        if (view instanceof android.webkit.WebView) return;
        view.invalidate();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            invalidate(((ViewGroup)view).getChildAt(i));
    }
    private static void bind(View view, int property, Update update) {
        Binding binding = (Binding) view.getTag(R.id.app_theme_binding);
        if (binding == null) {
            binding = new Binding(view); view.setTag(R.id.app_theme_binding, binding);
        }
        binding.values.put(property, update); update.apply(view);
    }
    static void observe(View view, Runnable update) { bind(view, 0, v -> update.run()); }
    static void text(TextView view, int light) { bind(view, 1, v -> ((TextView)v).setTextColor(ink(light))); }
    static void hint(TextView view, int light) { bind(view, 2, v -> ((TextView)v).setHintTextColor(ink(light))); }
    static void background(View view, int light) { bind(view, 3, v -> v.setBackgroundColor(color(light))); }
    static void tint(ImageView view, int light) { bind(view, 4, v -> ((ImageView)v).setColorFilter(ink(light))); }
    static void input(EditText view) {
        text(view, INK); hint(view, MUTED);
        bind(view, 6, v -> v.setBackgroundTintList(ColorStateList.valueOf(ink(BLUE))));
    }
    static void button(android.widget.Button view) {
        bind(view, 1, v -> ((TextView)v).setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{ink(FAINT), ink(BLUE)})));
        Shape shape = new Shape(); shape.setColor(SOFT);
        shape.setCornerRadius(10 * view.getResources().getDisplayMetrics().density); view.setBackground(shape);
    }

    /** A UI shape retains its semantic colors, so updating the palette keeps the same View. */
    static class Shape extends GradientDrawable implements Refresh {
        private int fill, edge, width;
        private float dash, gap;
        Shape() { add(this); }
        @Override public void setColor(int color) { fill = color; super.setColor(AppTheme.color(color)); }
        @Override public void setStroke(int width, int color) { setStroke(width, color, 0, 0); }
        @Override public void setStroke(int width, int color, float dash, float gap) {
            this.width = width; edge = color; this.dash = dash; this.gap = gap;
            super.setStroke(width, AppTheme.color(color), dash, gap);
        }
        public void refresh() { super.setColor(color(fill)); super.setStroke(width, color(edge), dash, gap); }
    }

    static void refresh() {
        if (app == null) return;
        boolean next = darkAt(mode(), start(), end(), Calendar.getInstance());
        boolean switched = dark != next; dark = next;
        app.setTheme(dark ? R.style.Theme_Jpdict_Page_Dark : R.style.Theme_Jpdict_Page);
        for (int i = observers.size() - 1; i >= 0; i--) {
            Refresh observer = observers.get(i).get();
            if (observer == null) observers.remove(i); else if (switched) observer.refresh();
        }
        updateWidgets(); schedule();
    }
    static void prepare(Activity activity) {
        activity.setTheme(dark ? R.style.Theme_Jpdict_Page_Dark : R.style.Theme_Jpdict_Page);
    }
    static void window(Activity activity, int background) {
        bind(activity.getWindow().getDecorView(), 5, v -> {
            prepare(activity);
            activity.getWindow().setStatusBarColor(color(background));
            activity.getWindow().setNavigationBarColor(color(background));
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            v.setSystemUiVisibility(dark ? v.getSystemUiVisibility() & ~flags : v.getSystemUiVisibility() | flags);
        });
    }
    static Context dialogContext(Context context) {
        return new ContextThemeWrapper(context, dark ? R.style.Theme_Jpdict_Dialog_Dark : R.style.Theme_Jpdict_Dialog);
    }
    static final class DialogBuilder extends AlertDialog.Builder {
        DialogBuilder(Context context) { super(dialogContext(context)); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog(dialog);
            return dialog;
        }
    }
    static void dialog(AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) {
                Shape shape = new Shape(); shape.setColor(CARD);
                shape.setCornerRadius(24 * view.getResources().getDisplayMetrics().density);
                dialog.getWindow().setBackgroundDrawable(shape);
                view.post(() -> observe(view, () -> platform(view)));
            }
            public void onViewDetachedFromWindow(View view) {}
        });
    }
    private static void platform(View view) {
        // TimePicker owns activated hour/minute colors and is rebuilt with its saved state by its host.
        if (view instanceof android.widget.TimePicker) return;
        if (view.getTag(R.id.app_theme_binding) == null && view instanceof TextView) {
            if (view instanceof android.widget.Button) button((android.widget.Button)view);
            else if (view instanceof EditText) input((EditText)view);
            else text((TextView)view, INK);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) platform(group.getChildAt(i));
        }
    }
    private static void updateWidgets() {
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] ids = manager.getAppWidgetIds(new ComponentName(app, MainActivity.SearchWidgetProvider.class));
        if (ids.length > 0) new MainActivity.SearchWidgetProvider().onUpdate(app, manager, ids);
    }
    private static void schedule() {
        AlarmManager alarms = (AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(app, MainActivity.SearchWidgetProvider.class).setAction(BOUNDARY);
        PendingIntent pending = PendingIntent.getBroadcast(app, 63, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(pending);
        if (mode() != 2) return;
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        for (int minute : new int[]{start(), end()}) {
            Calendar time = Calendar.getInstance();
            time.set(Calendar.HOUR_OF_DAY, minute / 60); time.set(Calendar.MINUTE, minute % 60);
            time.set(Calendar.SECOND, 0); time.set(Calendar.MILLISECOND, 0);
            if (time.getTimeInMillis() <= now) time.add(Calendar.DAY_OF_YEAR, 1);
            next = Math.min(next, time.getTimeInMillis());
        }
        // No exact-alarm permission: visible surfaces also refresh at each clock tick/resume.
        alarms.set(AlarmManager.RTC, next, pending);
    }
}
