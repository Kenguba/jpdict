package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;

/** Persist only presentation preferences; search drafts never enter this store. */
final class DrawerStyleSettings {
    enum Mode {
        FULL_PAGE("full_page", "整页切换", "Full-page", "菜单整页滑入，主页同步右移，如同翻页", "整页横滑", "原版宽度"),
        OVERLAY("overlay", "覆盖展开", "Overlay", "菜单覆盖主页，背景渐暗，轻触空白关闭", "覆盖菜单", "主页静止"),
        PUSH("push", "推动页面", "Push", "菜单推动主页右移，右侧在前且无遮罩", "推动主页", "右侧在前");

        final String key, title, english, description, firstTag, secondTag;
        Mode(String key, String title, String english, String description, String firstTag, String secondTag) {
            this.key = key; this.title = title; this.english = english;
            this.description = description; this.firstTag = firstTag; this.secondTag = secondTag;
        }
        static Mode parse(String value) {
            for (Mode mode : values()) if (mode.key.equals(value)) return mode;
            return FULL_PAGE;
        }
    }

    private final SharedPreferences preferences;

    DrawerStyleSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("drawer_style", Context.MODE_PRIVATE);
    }

    Mode mode() {
        try { return Mode.parse(preferences.getString("mode", Mode.FULL_PAGE.key)); }
        catch (ClassCastException ignored) { return Mode.FULL_PAGE; }
    }

    int ratio(Mode mode) {
        int initial = mode == Mode.FULL_PAGE ? 100 : 90;
        try { return Math.max(0, Math.min(100, preferences.getInt(mode.key + "_ratio", initial))); }
        catch (ClassCastException ignored) { return initial; }
    }

    void select(Mode mode) { preferences.edit().putString("mode", mode.key).apply(); }

    void saveRatio(Mode mode, int percent) {
        preferences.edit().putInt(mode.key + "_ratio", Math.max(0, Math.min(100, percent))).apply();
    }

    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void removeObserver(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
