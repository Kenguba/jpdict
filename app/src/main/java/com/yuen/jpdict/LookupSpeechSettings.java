package com.yuen.jpdict;

import android.content.Context;

/** Shared persisted preference for automatic lookup pronunciation. */
final class LookupSpeechSettings {
    private LookupSpeechSettings() {}

    static boolean autoplay(Context context) {
        return context.getSharedPreferences("lookup_audio", Context.MODE_PRIVATE)
                .getBoolean("autoplay", true);
    }

    static void setAutoplay(Context context, boolean enabled) {
        context.getSharedPreferences("lookup_audio", Context.MODE_PRIVATE)
                .edit().putBoolean("autoplay", enabled).apply();
    }
}
