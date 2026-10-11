package com.yuen.jpdict;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class GroqKeyStore {
    private static final String PREFS = "groq_secure";
    private static final String ALIAS = "jpdict_groq_api_key";
    private static final String KEY_CIPHER = "ciphertext";
    private static final String KEY_IV = "iv";
    private static final String KEY_REVISION = "revision";
    private static final CopyOnWriteArraySet<Runnable> CHANGE_LISTENERS =
            new CopyOnWriteArraySet<>();

    private GroqKeyStore() {}

    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every entry point reads the same app-wide encrypted key and revision. */
    public static long revision(Context context) {
        return preferences(context).getLong(KEY_REVISION, 0L);
    }

    public static void addChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.remove(listener);
    }

    private static void notifyChanged() {
        Handler main = new Handler(Looper.getMainLooper());
        for (Runnable listener : CHANGE_LISTENERS) {
            main.post(() -> {
                if (CHANGE_LISTENERS.contains(listener)) listener.run();
            });
        }
    }

    public static boolean hasKey(Context context) {
        String key = load(context);
        return key != null && !key.trim().isEmpty();
    }

    public static String load(Context context) {
        try {
            SharedPreferences p = preferences(context);
            String ciphertext = p.getString(KEY_CIPHER, "");
            String iv = p.getString(KEY_IV, "");
            if (ciphertext.isEmpty() || iv.isEmpty()) return "";

            SecretKey secret = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secret,
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));

            byte[] plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean save(Context context, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return false;

        try {
            SecretKey secret = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret);

            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            SharedPreferences p = preferences(context);
            boolean saved = p
                    .edit()
                    .putString(KEY_CIPHER,
                            Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV,
                            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putLong(KEY_REVISION, p.getLong(KEY_REVISION, 0L) + 1L)
                    .commit();
            if (saved) notifyChanged();
            return saved;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clear(Context context) {
        try {
            SharedPreferences p = preferences(context);
            boolean cleared = p
                    .edit()
                    .clear()
                    .putLong(KEY_REVISION, p.getLong(KEY_REVISION, 0L) + 1L)
                    .commit();
            if (cleared) notifyChanged();
            return cleared;
        } catch (Throwable ignored) { return false; }
    }

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");

        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());

        return generator.generateKey();
    }

    public static void showEditor(Activity activity, Runnable onChanged) {
        GroqKeySettings.showDialog(activity, onChanged);
    }
}
