package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Appearance facade shared by the editor, history, real bubble and its proxy. */
public final class FloatingBubbleAppearance {
    public static final int STYLE_DEFAULT = 0;
    public static final int STYLE_SEARCH = 1;
    public static final int STYLE_CUSTOM = 2;
    public static final int STYLE_IMAGE = 3;
    public static final int SHAPE_CIRCLE = 0;
    public static final int SHAPE_ROUNDED = 1;
    public static final int MIN_SIZE_DP = 32;
    public static final int MAX_SIZE_DP = 88;
    public static final int MIN_OPACITY = 0;
    public static final int DEFAULT_COLOR = 0xff168af2;
    private static final String PREFS = "floating_bubble_appearance";
    private static final String IMAGE_FILE = "floating-bubble-image.png";
    public static final int MAX_SAVED_STYLES = 10;
    private static Object previewOwner;
    private static Config previewConfig;
    private static final Set<Runnable> changeListeners = new HashSet<>();
    private static WeakReference<Bitmap> imageCache = new WeakReference<>(null);
    private static String cachedImagePath;
    private static long cachedImageModified;

    private FloatingBubbleAppearance() { }

    public static final class Config {
        public final int sizeDp;
        public final int opacityPercent;
        public final int style;
        /** 0 is square, 100 is circular; values between interpolate the corner radius. */
        public final int roundnessPercent;
        public final String text;
        public final int color;
        public final String imageName;

        public Config(int sizeDp, int opacityPercent, int style, int roundnessPercent,
                String text, int color) {
            this(sizeDp, opacityPercent, style, roundnessPercent, text, color, IMAGE_FILE);
        }

        public Config(int sizeDp, int opacityPercent, int style, int roundnessPercent,
                String text, int color, String imageName) {
            this.sizeDp = Math.max(MIN_SIZE_DP, Math.min(MAX_SIZE_DP, sizeDp));
            this.opacityPercent = Math.max(MIN_OPACITY, Math.min(100, opacityPercent));
            this.style = style >= STYLE_DEFAULT && style <= STYLE_IMAGE ? style : STYLE_DEFAULT;
            this.roundnessPercent = Math.max(0, Math.min(100, roundnessPercent));
            String label = text == null ? "" : text.trim();
            int count = label.codePointCount(0, label.length());
            this.text = label.isEmpty() ? "あ" : count <= 4 ? label
                    : label.substring(0, label.offsetByCodePoints(0, 4));
            this.color = color | 0xff000000;
            this.imageName = validImageName(imageName) ? imageName : IMAGE_FILE;
        }

        public float alpha() { return opacityPercent / 100f; }

        public int sizePx(Context context) {
            return dp(context, sizeDp);
        }

        public boolean matches(Config other) {
            return other != null && sizeDp == other.sizeDp
                    && opacityPercent == other.opacityPercent && style == other.style
                    && roundnessPercent == other.roundnessPercent && text.equals(other.text)
                    && color == other.color
                    && (style != STYLE_IMAGE || imageName.equals(other.imageName));
        }

        public Bundle toBundle() {
            Bundle state = new Bundle();
            state.putInt("size_dp", sizeDp);
            state.putInt("opacity_percent", opacityPercent);
            state.putInt("style", style);
            state.putInt("roundness_percent", roundnessPercent);
            state.putString("text", text);
            state.putInt("color", color);
            state.putString("image_name", imageName);
            return state;
        }
    }

    public static Config defaults() {
        return new Config(44, 25, STYLE_DEFAULT, 100, "あ", DEFAULT_COLOR);
    }

    public static Config load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(preferences.getInt("size_dp", 44),
                preferences.getInt("opacity_percent", 25),
                preferences.getInt("style", STYLE_DEFAULT),
                preferences.getInt("roundness_percent",
                        preferences.getInt("shape", SHAPE_CIRCLE) == SHAPE_ROUNDED ? 56 : 100),
                preferences.getString("text", "あ"),
                preferences.getInt("color", DEFAULT_COLOR),
                preferences.getString("image_name", IMAGE_FILE));
    }

    public static Config fromBundle(Bundle state) {
        if (state == null) return defaults();
        return new Config(state.getInt("size_dp", 44), state.getInt("opacity_percent", 25),
                state.getInt("style", STYLE_DEFAULT), state.getInt("roundness_percent", 100),
                state.getString("text", "あ"), state.getInt("color", DEFAULT_COLOR),
                state.getString("image_name", IMAGE_FILE));
    }

    /** Drafts are process-local. A process restart always restores the committed appearance. */
    public static Config current(Context context) {
        return previewConfig == null ? load(context) : previewConfig;
    }

    static boolean isPreviewing() { return previewConfig != null; }

    // Appearance drafts and the Service live in the same process on the UI thread.
    // Notify directly so an OEM-delayed broadcast cannot leave a mounted window stale.
    static void addChangeListener(Runnable listener) { changeListeners.add(listener); }

    static void removeChangeListener(Runnable listener) { changeListeners.remove(listener); }

    public static void preview(Context context, Object owner, Config config) {
        previewOwner = owner;
        if (config.matches(previewConfig)) return;
        previewConfig = config;
        notifyChanged();
    }

    public static void endPreview(Context context, Object owner) {
        if (previewOwner != owner) return;
        previewOwner = null;
        previewConfig = null;
        notifyChanged();
    }

    public static List<Config> savedStyles(Context context) {
        List<Config> result = new ArrayList<>();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains("saved_styles") && preferences.contains("size_dp")) {
            result.add(load(context)); // Retain the appearance saved before history was introduced.
            return result;
        }
        String history = preferences.getString("saved_styles", "[]");
        try {
            JSONArray entries = new JSONArray(history);
            for (int i = 0; i < entries.length() && result.size() < MAX_SAVED_STYLES; i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                Config config;
                try {
                    config = new Config(entry.getInt("size_dp"), entry.getInt("opacity_percent"),
                            entry.getInt("style"), entry.getInt("roundness_percent"),
                            entry.getString("text"), entry.getInt("color"),
                            entry.optString("image_name", IMAGE_FILE));
                } catch (JSONException invalidEntry) { continue; }
                if (config.style == STYLE_IMAGE && !hasCustomImage(context, config)) continue;
                boolean duplicate = false;
                for (Config saved : result) if (saved.matches(config)) duplicate = true;
                if (!duplicate) result.add(config);
            }
        } catch (JSONException | RuntimeException ignored) {
            // A damaged history must never prevent loading the independently saved appearance.
        }
        return result;
    }

    static List<Config> withSavedStyle(List<Config> previous, Config config) {
        List<Config> history = new ArrayList<>(previous);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (config.matches(history.get(i))) history.remove(i);
        }
        history.add(0, config);
        while (history.size() > MAX_SAVED_STYLES) history.remove(history.size() - 1);
        return history;
    }

    public static boolean save(Context context, Config config) {
        if (config.style == STYLE_IMAGE && !hasCustomImage(context, config)) return false;
        List<Config> history = withSavedStyle(savedStyles(context), config);
        JSONArray entries = new JSONArray();
        try {
            for (Config item : history) {
                JSONObject entry = new JSONObject();
                entry.put("size_dp", item.sizeDp).put("opacity_percent", item.opacityPercent)
                        .put("style", item.style).put("roundness_percent", item.roundnessPercent)
                        .put("text", item.text).put("color", item.color)
                        .put("image_name", item.imageName);
                entries.put(entry);
            }
        } catch (JSONException error) { return false; }
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("size_dp", config.sizeDp)
                .putInt("opacity_percent", config.opacityPercent)
                .putInt("style", config.style)
                .putInt("roundness_percent", config.roundnessPercent)
                .remove("shape")
                .putString("text", config.text)
                .putInt("color", config.color)
                .putString("image_name", config.imageName)
                .putString("saved_styles", entries.toString()).commit();
        if (saved) {
            cleanupImages(context, config);
            notifyChanged();
        }
        return saved;
    }

    private static void notifyChanged() {
        for (Runnable listener : new ArrayList<>(changeListeners)) listener.run();
    }

    public static int sizePx(Context context) { return current(context).sizePx(context); }

    public static FrameLayout createVisual(Context context) {
        FrameLayout visual = new FrameLayout(context);
        applyVisual(visual, current(context));
        return visual;
    }

    /** Replaces only visual children; touch listeners and service visibility stay intact. */
    public static void applyVisual(FrameLayout visual, Config config) {
        Context context = visual.getContext();
        visual.removeAllViews();
        visual.setElevation(dp(context, 12));
        visual.setClipToOutline(config.style == STYLE_IMAGE);
        visual.setContentDescription("悬浮搜索按钮");
        GradientDrawable background = new GradientDrawable();
        background.setColor(config.color);
        background.setCornerRadius(config.sizePx(context) * config.roundnessPercent / 200f);
        background.setStroke(dp(context, 1), 0x55ffffff);
        visual.setBackground(background);
        if (config.style == STYLE_IMAGE) {
            Bitmap image = loadImage(context, config);
            if (image != null) {
                ImageView picture = new ImageView(context);
                picture.setImageBitmap(image);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                visual.addView(picture, new FrameLayout.LayoutParams(-1, -1));
                return;
            }
        }
        if (config.style == STYLE_SEARCH) {
            SearchIconView icon = new SearchIconView(context, SearchIconView.Kind.SEARCH);
            icon.setColor(Color.WHITE);
            FrameLayout.LayoutParams iconLayout = new FrameLayout.LayoutParams(
                    dp(context, config.sizeDp * 0.5f), dp(context, config.sizeDp * 0.5f),
                    Gravity.CENTER);
            visual.addView(icon, iconLayout);
            return;
        }
        TextView kana = new TextView(context);
        kana.setText(config.style == STYLE_CUSTOM ? config.text : "あ");
        kana.setTextColor(Color.WHITE);
        kana.setTextSize((config.style == STYLE_CUSTOM && config.text.length() > 2 ? 17 : 22)
                * config.sizeDp / 54f);
        kana.setTypeface(Typeface.DEFAULT_BOLD);
        kana.setGravity(Gravity.CENTER);
        kana.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        visual.addView(kana, new FrameLayout.LayoutParams(-1, -1));
        if (config.style != STYLE_CUSTOM) {
            ImageView searchMark = new ImageView(context);
            searchMark.setImageResource(android.R.drawable.ic_menu_search);
            searchMark.setColorFilter(Color.WHITE);
            searchMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            float scale = config.sizeDp / 54f;
            FrameLayout.LayoutParams markLayout = new FrameLayout.LayoutParams(
                    dp(context, 18 * scale), dp(context, 18 * scale), Gravity.END | Gravity.BOTTOM);
            markLayout.rightMargin = dp(context, 4 * scale);
            markLayout.bottomMargin = dp(context, 4 * scale);
            visual.addView(searchMark, markLayout);
        }
    }

    private static boolean validImageName(String name) {
        return IMAGE_FILE.equals(name) || (name != null
                && name.matches("floating-bubble-image-[a-zA-Z0-9-]+\\.png"));
    }

    static File imageFile(Context context, Config config) {
        return new File(context.getFilesDir(), config.imageName);
    }

    public static boolean hasCustomImage(Context context, Config config) {
        return imageFile(context, config).isFile();
    }

    /** Only private bubble copies are pruned, after their preference references are committed. */
    static void cleanupImages(Context context, Config draft) {
        Set<String> keep = new HashSet<>();
        keep.add(load(context).imageName);
        if (draft != null) keep.add(draft.imageName);
        if (previewConfig != null) keep.add(previewConfig.imageName);
        for (Config saved : savedStyles(context)) keep.add(saved.imageName);
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (validImageName(file.getName()) && !keep.contains(file.getName())) file.delete();
        }
    }

    private static synchronized Bitmap loadImage(Context context, Config config) {
        File file = imageFile(context, config);
        if (!file.isFile()) return null;
        Bitmap image = imageCache.get();
        if (image != null && file.getAbsolutePath().equals(cachedImagePath)
                && file.lastModified() == cachedImageModified) return image;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) {
            options.inSampleSize *= 2;
        }
        image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        imageCache = new WeakReference<>(image);
        cachedImagePath = file.getAbsolutePath();
        cachedImageModified = file.lastModified();
        return image;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
