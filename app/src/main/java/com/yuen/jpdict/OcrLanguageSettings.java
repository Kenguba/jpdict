package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;

/** One persisted OCR preference shared by search and screen capture in this process. */
final class OcrLanguageSettings {
    enum Language {
        MIXED("mixed", "混合"), KOREAN("korean", "韩语"),
        JAPANESE("japanese", "日语"), ENGLISH("english", "英语");

        final String value;
        final String label;
        Language(String value, String label) { this.value = value; this.label = label; }

        static Language fromStored(String value) {
            for (Language language : values()) if (language.value.equals(value)) return language;
            return MIXED;
        }
    }

    private static OcrLanguageSettings shared;
    private final SharedPreferences preferences;
    private volatile Language language;

    static synchronized OcrLanguageSettings get(Context context) {
        if (shared == null) {
            shared = new OcrLanguageSettings(context.getApplicationContext()
                    .getSharedPreferences("ocr_language", Context.MODE_PRIVATE));
        }
        return shared;
    }

    OcrLanguageSettings(SharedPreferences preferences) {
        this.preferences = preferences;
        // Restore before publishing the shared object, including a safe fallback
        // for preferences written by an older/incompatible version.
        try { language = Language.fromStored(preferences.getString("language", "mixed")); }
        catch (ClassCastException ignored) { language = Language.MIXED; }
    }

    Language language() { return language; }

    synchronized void setLanguage(Language value) {
        if (value == null) throw new IllegalArgumentException("OCR language must not be null");
        preferences.edit().putString("language", value.value).apply();
        language = value;
    }
}
