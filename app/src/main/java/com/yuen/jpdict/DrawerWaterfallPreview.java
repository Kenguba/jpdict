package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Animated waterfall scene used only inside the drawer-style settings preview. */
final class DrawerWaterfallPreview implements Runnable {
    private final Context context;
    private final View target;
    private final Bitmap forest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final float density;
    private long started;
    private boolean running;

    DrawerWaterfallPreview(Context context, View target) {
        this.context = context; this.target = target;
        density = context.getResources().getDisplayMetrics().density;
        forest = BitmapFactory.decodeResource(context.getResources(), R.drawable.drawer_preview_waterfall);
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
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 5; i++) {
            float x = width * (.42f + i * .04f) + (float)Math.sin(time * 1.4f + i) * 2 * density;
            float phase = (time * .75f + i * .19f) % 1;
            float top = height * (.34f + phase * .18f), bottom = height * .68f;
            path.reset(); path.moveTo(x, top);
            path.cubicTo(x - 2 * density, top + 12 * density, x + 2 * density, bottom - 12 * density, x, bottom);
            paint.setColor(i % 2 == 0 ? 0x55d4eee2 : 0x3994c6b7);
            paint.setStrokeWidth((i % 2 + .7f) * density); canvas.drawPath(path, paint);
        }
        paint.setStrokeWidth(Math.max(1, density));
        for (int i = 0; i < 3; i++) {
            float y = height * (.71f + i * .09f);
            float drift = (float)Math.sin(time * .9f + i) * 3 * density;
            path.reset(); path.moveTo(0, y);
            path.cubicTo(width * .3f, y - drift, width * .7f, y + drift, width, y);
            paint.setColor(0x3ac9e8d9); canvas.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public void run() {
        if (!running) return;
        target.invalidate(); target.postDelayed(this, 33);
    }
}
