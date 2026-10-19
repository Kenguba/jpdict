package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Animated nature scene used only inside the drawer-style settings preview. */
final class DrawerWaterfallPreview implements Runnable {
    private final Context context;
    private final View target;
    private final Bitmap forest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final RadialGradient fireflyGlow;
    private final float density;
    private long started;
    private boolean running;

    DrawerWaterfallPreview(Context context, View target) {
        this.context = context; this.target = target;
        density = context.getResources().getDisplayMetrics().density;
        float r = 8 * density;
        fireflyGlow = new RadialGradient(0, 0, r, 0xbfe2fa8a, 0x0083ca43, Shader.TileMode.CLAMP);
        forest = BitmapFactory.decodeResource(context.getResources(), R.drawable.drawer_preview_forest);
    }

    void resume() {
        if (running || !DrawerMotionController.animationsEnabled(context)) return;
        running = true; started = SystemClock.uptimeMillis(); target.postDelayed(this, 16);
    }

    void pause() { running = false; target.removeCallbacks(this); }

    void draw(Canvas canvas, float width, float height) {
        if (forest != null) {
            float scale = Math.max(width / forest.getWidth(), height / forest.getHeight());
            float cropWidth = width / scale, cropHeight = height / scale;
            source.set(Math.round((forest.getWidth() - cropWidth) / 2), Math.round((forest.getHeight() - cropHeight) / 2),
                    Math.round((forest.getWidth() + cropWidth) / 2), Math.round((forest.getHeight() + cropHeight) / 2));
            destination.set(0, 0, width, height);
            paint.setColor(0xffffffff);
            canvas.drawBitmap(forest, source, destination, paint);
        } else {
            paint.setColor(0xff173833); canvas.drawRect(0, 0, width, height, paint);
        }
        float time = running ? (SystemClock.uptimeMillis() - started) / 1000f : 0;
        boolean dark = AppTheme.isDark(), rain = Math.sin((int)(time / 8) * 127.1) > .5;
        float beam = width * .35f + (float)Math.sin(time * .2f) * 10 * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(18 * density);
        paint.setColor(0x26ffd990);
        canvas.drawLine(beam, 0, beam + width * .25f, height * .76f, paint);
        if (rain) {
            paint.setStrokeWidth(density); paint.setColor(0x709ed8d2);
            for (int i = 0; i < 16; i++) {
                float x = (i * 37 % 97) * width / 97, y = (time * 60 + i * 29) % height;
                canvas.drawLine(x, y, x - density, y + 5 * density, paint);
            }
        }
        paint.setStrokeWidth(density); paint.setColor(dark ? 0x5fdaf5e8 : 0x3ac9e8d9);
        for (int i = 0; i < 5; i++) {
            float r = width * (.12f + i * .08f), y = height * (.7f + i * .05f) + (float)Math.sin(time + i) * density;
            canvas.drawOval(width / 2 - r, y, width / 2 + r, y + 2 * density, paint);
        }
        if (dark) {
            paint.setStyle(Paint.Style.FILL); float r = 8 * density;
            for (int i = 0; i < 5; i++) {
                float phase = time * .3f + i * 2.4f, glow = .24f + .54f * (float)Math.pow(.5f + .5f * Math.sin(time * 1.5f + i), 1.45);
                float x = width * (.5f + .32f * (float)Math.sin(phase)), y = height * (.5f + .38f * (float)Math.cos(phase));
                paint.setShader(fireflyGlow); paint.setColor(i % 2 == 0 ? 0xffffffff : 0xffffd8a0); paint.setAlpha((int)(glow * 255));
                canvas.save(); canvas.translate(x, y); canvas.drawCircle(0, 0, r, paint); canvas.restore();
            }
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public void run() {
        if (!running) return;
        target.invalidate(); target.postDelayed(this, 33);
    }
}
