package com.yuen.jpdict;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

/** Shared slider appearance; the platform SeekBar still owns input and accessibility. */
final class BubbleSliderStyle {
    private BubbleSliderStyle() { }

    static void apply(SeekBar slider) {
        GradientDrawable remaining = track(slider, new int[]{0xffe5e5ea, 0xffe5e5ea});
        ClipDrawable filled = new ClipDrawable(track(slider, new int[]{0xff007aff, 0xff007aff}),
                Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{remaining, filled});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, dp(slider, 4));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL);
        }
        apply(slider, layers);
    }

    static void applyHue(SeekBar slider) {
        GradientDrawable rainbow = track(slider, new int[]{Color.RED, Color.YELLOW,
                Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{rainbow});
        layers.setLayerHeight(0, dp(slider, 4));
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        apply(slider, layers);
    }

    private static GradientDrawable track(SeekBar slider, int[] colors) {
        GradientDrawable track = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        track.setCornerRadius(dp(slider, 2));
        return track;
    }

    private static void apply(SeekBar slider, Drawable track) {
        slider.setProgressTintList(null);
        slider.setProgressBackgroundTintList(null);
        slider.setThumbTintList(null);
        slider.setProgressDrawable(track);
        slider.setThumb(new Thumb(slider.getResources().getDisplayMetrics().density));
        slider.setThumbOffset(dp(slider, 18));
        slider.setSplitTrack(false);
        slider.setBackground(null);
        slider.setPadding(dp(slider, 18), 0, dp(slider, 18), 0);
    }

    private static final class Thumb extends Drawable {
        private final float density;
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;

        Thumb(float density) { this.density = density; }

        @Override protected void onBoundsChange(Rect bounds) {
            shadow.setShader(new RadialGradient(bounds.exactCenterX(),
                    bounds.exactCenterY() + density, 17 * density,
                    new int[]{0x26000000, 0x18000000, 0x00000000},
                    new float[]{0f, .78f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            canvas.drawCircle(x, y + density, 17 * density, shadow);
            face.setColor(0xffffffff);
            face.setAlpha(alpha);
            face.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, 14 * density, face);
            face.setColor(0xffededed);
            face.setAlpha(alpha);
            face.setStyle(Paint.Style.STROKE);
            face.setStrokeWidth(.5f * density);
            canvas.drawCircle(x, y, 13.75f * density, face);
        }

        @Override public int getIntrinsicWidth() { return Math.round(36 * density); }
        @Override public int getIntrinsicHeight() { return Math.round(36 * density); }
        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
            shadow.setAlpha(alpha);
            invalidateSelf();
        }
        @Override public void setColorFilter(ColorFilter filter) {
            face.setColorFilter(filter);
            shadow.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static int dp(SeekBar slider, float value) {
        return Math.round(value * slider.getResources().getDisplayMetrics().density);
    }
}
