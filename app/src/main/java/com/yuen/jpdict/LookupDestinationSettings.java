package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

/** One persisted lookup destination for the search menu and OCR joystick. */
final class LookupDestinationSettings {
    interface Listener { void onDestinationChanged(); }
    enum Model {
        BUILD("build"), HEAVY("heavy"), EXPERT("expert"), FAST("fast"), THIRD_PARTY("third_party");
        final String value;
        Model(String value) { this.value = value; }
        static Model fromStored(String value) {
            for (Model model : values()) if (model.value.equals(value)) return model;
            return FAST;
        }
    }

    private static LookupDestinationSettings shared;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Model model;
    private Model systemModel;

    static synchronized LookupDestinationSettings get(Context context) {
        if (shared == null) shared = new LookupDestinationSettings(context.getApplicationContext()
                .getSharedPreferences("lookup_destination", Context.MODE_PRIVATE));
        return shared;
    }

    LookupDestinationSettings(SharedPreferences preferences) {
        this.preferences = preferences;
        model = readModel("model");
        systemModel = readModel("system_model");
        if (systemModel == Model.THIRD_PARTY) systemModel = Model.FAST;
        if (model != Model.THIRD_PARTY) systemModel = model;
    }

    private Model readModel(String key) {
        try { return Model.fromStored(preferences.getString(key, "fast")); }
        catch (ClassCastException ignored) { return Model.FAST; }
    }

    Model model() { return model; }
    boolean isThirdParty() { return model == Model.THIRD_PARTY; }
    void observe(Listener listener) { listeners.addIfAbsent(listener); }
    void removeObserver(Listener listener) { listeners.remove(listener); }

    synchronized void toggleSystem() { select(isThirdParty() ? systemModel : Model.THIRD_PARTY); }

    synchronized void select(Model value) {
        if (value == null) throw new IllegalArgumentException("Lookup destination must not be null");
        if (model == value) return;
        if (value != Model.THIRD_PARTY) systemModel = value;
        model = value;
        preferences.edit().putString("model", model.value)
                .putString("system_model", systemModel.value).apply();
        for (Listener listener : listeners) {
            if (Looper.myLooper() == Looper.getMainLooper()) listener.onDestinationChanged();
            else main.post(() -> { if (listeners.contains(listener)) listener.onDestinationChanged(); });
        }
    }
}
