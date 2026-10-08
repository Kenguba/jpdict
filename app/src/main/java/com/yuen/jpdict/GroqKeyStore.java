package com.yuen.jpdict;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class GroqKeyStore {
    private static final String PREFS = "groq_secure";
    private static final String ALIAS = "jpdict_groq_api_key";
    private static final String KEY_CIPHER = "ciphertext";
    private static final String KEY_IV = "iv";

    private GroqKeyStore() {}

    public static boolean hasKey(Context context) {
        String key = load(context);
        return key != null && !key.trim().isEmpty();
    }

    public static String load(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CIPHER,
                            Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV,
                            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .commit();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clear(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        } catch (Throwable ignored) {}
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
        int pad = Math.round(
                20 * activity.getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView note = new TextView(activity);
        note.setText(
                hasKey(activity)
                        ? "本机已经保存 Groq Key。输入新 Key 可以覆盖；留空不会修改。"
                        : "请输入自己的 Groq API Key。Key 会使用 Android Keystore 加密后仅保存在本机。");
        note.setTextSize(15);
        note.setPadding(0, 0, 0, pad / 2);
        box.addView(note);

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("gsk_...");
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox show = new CheckBox(activity);
        show.setText("显示 Key");
        show.setOnCheckedChangeListener((button, checked) -> {
            int pos = input.getSelectionStart();
            input.setInputType(
                    InputType.TYPE_CLASS_TEXT |
                            (checked
                                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setTypeface(Typeface.MONOSPACE);
            if (pos >= 0 && pos <= input.length()) input.setSelection(pos);
        });
        box.addView(show);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Groq AI 设置")
                .setView(box)
                .setNegativeButton("取消", null)
                .setNeutralButton(
                        hasKey(activity) ? "清除本机 Key" : null,
                        null)
                .setPositiveButton("保存", null)
                .create();

        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    Toast.makeText(activity, "没有输入 Key，未修改", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!save(activity, value)) {
                    Toast.makeText(activity, "Key 加密保存失败", Toast.LENGTH_LONG).show();
                    return;
                }

                Toast.makeText(activity, "Groq Key 已加密保存在本机", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (onChanged != null) onChanged.run();
            });

            if (hasKey(activity)) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    clear(activity);
                    Toast.makeText(activity, "本机 Groq Key 已清除", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onChanged != null) onChanged.run();
                });
            }
        });

        dialog.show();
    }
}
