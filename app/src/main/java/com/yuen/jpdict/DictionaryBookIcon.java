package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** The reference's stacked book silhouette, drawn at any density without assets. */
final class DictionaryBookIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int color;
    private final String letter;
    private final LinearGradient cover;

    DictionaryBookIcon(Context context, String name) {
        super(context);
        color = colorFor(name);
        letter = letterFor(name);
        int shade = Color.rgb(Math.round(Color.red(color) * .92f), Math.round(Color.green(color) * .92f), Math.round(Color.blue(color) * .92f));
        cover = new LinearGradient(2, 2, 39, 44, color, shade, Shader.TileMode.CLAMP);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static String letterFor(String name) {
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset);
            if ((cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0x20000 && cp <= 0x323af)) return new String(Character.toChars(cp));
            offset += Character.charCount(cp);
        }
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset);
            if (Character.isLetter(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
            offset += Character.charCount(cp);
        }
        return "辞";
    }

    static int colorFor(String name) {
        float hue = (name.hashCode() % 360 + 360) % 360;
        // Muted hues with >= 4.5:1 white-text contrast, including yellow/green.
        float value = .68f;
        int color;
        do {
            color = Color.HSVToColor(new float[]{hue, .46f, value});
            value -= .02f;
        } while (luminance(color) > .1833 && value > .2f);
        return color;
    }

    private static double luminance(int color) {
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color));
    }
    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float scale = Math.min(getWidth() / 44f, getHeight() / 54f);
        canvas.translate((getWidth() - 44 * scale) / 2, (getHeight() - 54 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        rect.set(2, 9, 42, 52); canvas.drawRoundRect(rect, 4, 4, paint);
        paint.setColor(Color.WHITE); rect.set(4, 11, 40, 50); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint);
        paint.setShader(cover); rect.set(2, 2, 39, 45); canvas.drawRoundRect(rect, 4, 4, paint); paint.setShader(null);
        // A narrow spine highlight and the lower page edge follow the reference.
        paint.setColor(0x24ffffff); rect.set(39, 12, 40, 46); canvas.drawRoundRect(rect, .5f, .5f, paint);
        paint.setColor(Color.WHITE); rect.set(5, 45, 38, 48); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint);
        paint.setTextSize(20); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(letter, 20.5f, 25 - (metrics.ascent + metrics.descent) / 2, paint);
        canvas.restore();
    }
}
