package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

/** One persisted OCR preference shared by search and screen capture in this process. */
final class OcrLanguageSettings {
    interface Listener { void onLanguageChanged(); }
    enum Language {
        MIXED("mixed", "混合"), CHINESE("chinese", "中文"),
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
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

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
    void observe(Listener listener) { listeners.addIfAbsent(listener); }
    void removeObserver(Listener listener) { listeners.remove(listener); }

    synchronized void setLanguage(Language value) {
        if (value == null) throw new IllegalArgumentException("OCR language must not be null");
        if (language == value) return;
        preferences.edit().putString("language", value.value).apply();
        language = value;
        for (Listener listener : listeners) {
            if (Looper.myLooper() == Looper.getMainLooper()) listener.onLanguageChanged();
            else main.post(() -> { if (listeners.contains(listener)) listener.onLanguageChanged(); });
        }
    }
}
