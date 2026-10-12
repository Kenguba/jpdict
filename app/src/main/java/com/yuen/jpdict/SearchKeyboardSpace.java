package com.yuen.jpdict;

import android.content.Context;

/** Keyboard space survives hide/show; only a settled, measured IME changes it. */
final class SearchKeyboardSpace {
    private final Context context;
    private final String key;
    private int height;
    SearchKeyboardSpace(Context context) {
        this.context = context;
        key = "height_" + context.getResources().getConfiguration().orientation;
        float density = context.getResources().getDisplayMetrics().density;
        height = Math.round(context.getSharedPreferences("search_keyboard", Context.MODE_PRIVATE).getFloat(key, 280) * density);
    }
    void measured(int pixels) {
        float density = context.getResources().getDisplayMetrics().density;
        if (pixels < 120 * density || pixels == height) return;
        height = pixels;
        context.getSharedPreferences("search_keyboard", Context.MODE_PRIVATE).edit().putFloat(key, pixels / density).apply();
    }
    int reserved(int viewportHeight) {
        return Math.min(height, Math.max(0, viewportHeight - Math.round(220 * context.getResources().getDisplayMetrics().density)));
    }
}
