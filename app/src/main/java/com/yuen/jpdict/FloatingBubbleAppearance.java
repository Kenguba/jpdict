package com.yuen.jpdict;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;

/** One persisted appearance shared by the real bubble, its proxy and previews. */
public final class FloatingBubbleAppearance {
    public static final String ACTION_APPEARANCE_CHANGED =
            "com.yuen.jpdict.action.BUBBLE_APPEARANCE_CHANGED";
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

        public Config(int sizeDp, int opacityPercent, int style, int roundnessPercent,
                String text, int color) {
            this.sizeDp = Math.max(MIN_SIZE_DP, Math.min(MAX_SIZE_DP, sizeDp));
            this.opacityPercent = Math.max(MIN_OPACITY, Math.min(100, opacityPercent));
            this.style = style >= STYLE_DEFAULT && style <= STYLE_IMAGE ? style : STYLE_DEFAULT;
            this.roundnessPercent = Math.max(0, Math.min(100, roundnessPercent));
            String label = text == null ? "" : text.trim();
            int count = label.codePointCount(0, label.length());
            this.text = label.isEmpty() ? "あ" : count <= 4 ? label
                    : label.substring(0, label.offsetByCodePoints(0, 4));
            this.color = color | 0xff000000;
        }

        public float alpha() { return opacityPercent / 100f; }

        public int sizePx(Context context) {
            return dp(context, sizeDp);
        }

        public Config withStyle(int style) {
            return new Config(sizeDp, opacityPercent, style, roundnessPercent, text, color);
        }
    }

    public static Config defaults() {
        return new Config(54, 100, STYLE_DEFAULT, 100, "あ", DEFAULT_COLOR);
    }

    public static Config load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(preferences.getInt("size_dp", 54),
                preferences.getInt("opacity_percent", 100),
                preferences.getInt("style", STYLE_DEFAULT),
                preferences.getInt("roundness_percent",
                        preferences.getInt("shape", SHAPE_CIRCLE) == SHAPE_ROUNDED ? 56 : 100),
                preferences.getString("text", "あ"),
                preferences.getInt("color", DEFAULT_COLOR));
    }

    public static boolean save(Context context, Config config) {
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("size_dp", config.sizeDp)
                .putInt("opacity_percent", config.opacityPercent)
                .putInt("style", config.style)
                .putInt("roundness_percent", config.roundnessPercent)
                .remove("shape")
                .putString("text", config.text)
                .putInt("color", config.color).commit();
        if (saved) context.sendBroadcast(new Intent(ACTION_APPEARANCE_CHANGED)
                .setPackage(context.getPackageName()));
        return saved;
    }

    public static int sizePx(Context context) { return load(context).sizePx(context); }

    public static FrameLayout createVisual(Context context) {
        FrameLayout visual = new FrameLayout(context);
        applyVisual(visual, load(context));
        return visual;
    }

    /** Replaces only visual children; touch listeners and service visibility stay intact. */
    public static void applyVisual(FrameLayout visual, Config config) {
        Context context = visual.getContext();
        visual.removeAllViews();
        visual.setElevation(dp(context, 12));
        visual.setClipToOutline(config.style == STYLE_IMAGE);
        visual.setContentDescription("悬浮搜索按钮");
        if (config.style == STYLE_CUSTOM || config.color != DEFAULT_COLOR) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(config.color);
            background.setCornerRadius(config.sizePx(context) * config.roundnessPercent / 200f);
            background.setStroke(dp(context, 1), 0x55ffffff);
            visual.setBackground(background);
        } else {
            GradientDrawable background = (GradientDrawable) context.getDrawable(
                    R.drawable.floating_bubble_bg).mutate();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(config.sizePx(context) * config.roundnessPercent / 200f);
            visual.setBackground(background);
        }
        if (config.style == STYLE_IMAGE) {
            Bitmap image = loadImage(context);
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

    static File imageFile(Context context) { return new File(context.getFilesDir(), IMAGE_FILE); }

    public static boolean hasCustomImage(Context context) { return imageFile(context).isFile(); }

    static synchronized void invalidateImage() {
        imageCache = new WeakReference<>(null);
        cachedImagePath = null;
    }

    private static synchronized Bitmap loadImage(Context context) {
        File file = imageFile(context);
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
