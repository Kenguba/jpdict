package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Lightweight native scene: no query, clipboard, WebView, or second physics engine. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatically created scenes, never inflated from XML.
final class DrawerPreviewView extends View {
    interface ProgressListener { void changed(float progress); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF phone = new RectF();
    private final Path clip = new Path();
    private final DrawerMotionController.Geometry geometry = new DrawerMotionController.Geometry();
    private final DrawerMotionController motion;
    private final boolean interactive;
    private DrawerStyleSettings.Mode mode = DrawerStyleSettings.Mode.FULL_PAGE;
    private float limit = 1, ratio = 100, travel, scale, density;
    private boolean open = true, touching;
    private float touchX, touchY;
    private ProgressListener progressListener;
    private DrawerWaterfallPreview waterfall;
    private android.graphics.Shader menuGradient;
    private android.graphics.Shader thumbnailMenu, thumbnailHome, thumbnailFrame, thumbnailLines;

    private void updateColors() {
        menuGradient = new android.graphics.LinearGradient(0, 0, Math.max(1, travel), Math.max(1, phone.height()),
                AppTheme.isDark() ? 0xff40516b : 0xffe9f0fa,
                AppTheme.isDark() ? 0xff293950 : 0xffdce7f7, android.graphics.Shader.TileMode.CLAMP);
        if (!interactive) {
            thumbnailMenu = gradient(156, AppTheme.isDark() ? new int[]{0xff536e90, 0xff496080, 0xff435874}
                    : new int[]{0xffe9f0fa, 0xffe2ebf8, 0xffdce7f7}, new float[]{0, .64f, 1});
            thumbnailHome = gradient(160, AppTheme.isDark() ? new int[]{0xff8b95a2, 0xff6d7785, 0xff5f6975}
                    : new int[]{0xfffcfcfc, 0xffedf1f6, 0xffdce3ed}, new float[]{0, .6f, 1});
            thumbnailFrame = gradient(160, AppTheme.isDark() ? new int[]{0xff0e1b2e, 0xff111f31}
                    : new int[]{0xffd9e4f3, 0xffcbd9ec}, null);
            thumbnailLines = gradient(90, AppTheme.isDark() ? new int[]{0xffacbfd9, 0xff95accb}
                    : new int[]{0xffa4bcda, 0xff8faacb}, null);
        }
        invalidate();
    }

    private android.graphics.Shader gradient(float angle, int[] colors, float[] stops) {
        double radians = Math.toRadians(angle);
        float x = (float)Math.sin(radians), y = -(float)Math.cos(radians);
        float extent = Math.max(1, Math.abs(phone.width() * x) + Math.abs(phone.height() * y)) / 2;
        return new android.graphics.LinearGradient(phone.width() / 2 - x * extent, phone.height() / 2 - y * extent,
                phone.width() / 2 + x * extent, phone.height() / 2 + y * extent,
                colors, stops, android.graphics.Shader.TileMode.CLAMP);
    }

    DrawerPreviewView(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        AppTheme.observe(this, this::updateColors);
        density = getResources().getDisplayMetrics().density;
        setClickable(interactive); setFocusable(interactive);
        setContentDescription("动态抽屉预览，左右拖动，轻触展开或收起");
        if (!interactive) setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        motion = new DrawerMotionController(this, new DrawerMotionController.Listener() {
            public void onProgress(float value) {
                invalidate();
                if (progressListener != null) progressListener.changed(value * ratio / 100f);
            }
            public void onDragStart() {}
            public void onRelease(boolean expanded) { open = expanded; }
        });
    }

    void setProgressListener(ProgressListener listener) { progressListener = listener; }
    void setWaterfallPreview(DrawerWaterfallPreview waterfall) { this.waterfall = waterfall; invalidate(); }
    float progress() { return motion.progress() * ratio / 100f; }

    void show(DrawerStyleSettings.Mode mode, int percent, boolean animate) {
        this.mode = mode; ratio = DrawerMotionController.clamp(percent, 0, 100);
        limit = ratio == 0 ? 0 : 1;
        travel = phone.width() * ratio / 100f; motion.configure(travel, limit);
        open = limit > 0;
        updateColors();
        if (animate && interactive) motion.closeThen(() -> motion.animate(open, null));
        else motion.snap(open ? limit : 0);
    }
    void pause() { touching = false; motion.snap(motion.progress()); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        android.content.res.Configuration c = getResources().getConfiguration();
        float inset = c.screenWidthDp <= 365 ? 12 : 14;
        float width = interactive ? (c.screenHeightDp <= 740 ? 153 : c.screenWidthDp <= 365 ? 162 : 174) * density : w - inset * density;
        float height = interactive ? (c.screenHeightDp <= 740 ? 154 : c.screenWidthDp <= 365 ? 164 : 173) * density : h - inset * density;
        float top = interactive ? (c.screenHeightDp <= 740 ? 34 : 36) * density : (h - height) / 2;
        phone.set((w - width) / 2, top, (w + width) / 2, top + height);
        float realWidth = getResources().getDisplayMetrics().widthPixels;
        scale = width / realWidth;
        travel = phone.width() * ratio / 100f;
        updateColors();
        motion.configure(travel, limit);
        if (!motion.isRunning() && !motion.isDragging()) motion.snap(open ? limit : 0);
        clip.reset(); clip.addRoundRect(phone, (interactive ? 24 : 7) * density,
                (interactive ? 24 : 7) * density, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (phone.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL); paint.setShader(null);
        if (!interactive) { drawThumbnail(canvas); return; }
        if (interactive) {
            float glow = touching && motion.isDragging() && limit > 0
                    ? DrawerMotionController.clamp((motion.progress() / limit - .5f) * 12.5f, 0, 1) : 0;
            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density * .65f);
            for (int i = 0; i < 2; i++) {
                boolean inner = i == 0;
                boolean dark = AppTheme.isDark();
                int baseAlpha = inner ? (dark ? 0x16 : 0x2e) : (dark ? 0x13 : 0x22);
                int alpha = inner ? Math.round(baseAlpha + (0x48 - baseAlpha) * glow) : baseAlpha;
                paint.setColor((alpha << 24) | (inner && glow > 0 ? 0xffd187 : 0xc8d2e4));
                if (inner && glow > 0) paint.setShadowLayer(12 * density * glow, 0, 0, 0x44ffb947);
                float radius = density * (inner ? 112 : 157.5f) * (inner ? 1 + .015f * glow : 1);
                canvas.drawCircle(centerX, centerY, radius, paint);
                paint.clearShadowLayer();
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AppTheme.isDark() ? 0x18ffb51c : 0x18006bff);
            canvas.drawCircle(20 * density, 20 * density, 11 * density, paint);
            paint.setColor(AppTheme.color(AppTheme.ACCENT));
            canvas.drawCircle(20 * density, 20 * density, 4.5f * density, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(11 * density);
            paint.setColor(AppTheme.ink(AppTheme.MUTED));
            canvas.drawText("L I V E  P R E V I E W", 36 * density, 24 * density, paint);
        }
        paint.setColor(AppTheme.isDark() ? 0x38435e7c : 0x28405f8f);
        float rim = (interactive ? 6 : 5) * density, radius = (interactive ? 30 : 14) * density;
        canvas.drawRoundRect(phone.left - rim - density, phone.top - rim - density,
                phone.right + rim + density, phone.bottom + rim + 2 * density, radius, radius, paint);
        paint.setShadowLayer(6 * density, 0, 4 * density, AppTheme.isDark() ? 0x44030b15 : 0x20426388);
        paint.setColor(AppTheme.color(AppTheme.RIM));
        canvas.drawRoundRect(phone.left - rim, phone.top - rim, phone.right + rim,
                phone.bottom + rim, radius, radius, paint); paint.clearShadowLayer();
        canvas.save(); canvas.clipPath(clip);
        canvas.translate(phone.left, phone.top);
        geometry.update(mode, motion.progress(), limit, travel, density * scale);
        canvas.drawColor(AppTheme.color(AppTheme.STAGE));
        if (mode == DrawerStyleSettings.Mode.PUSH) drawMenu(canvas);
        drawPage(canvas);
        if (mode == DrawerStyleSettings.Mode.OVERLAY && geometry.shade > 0) {
            paint.setColor(((int) (255 * geometry.shade) << 24) | 0x020813);
            canvas.drawRect(0, 0, phone.width(), phone.height(), paint);
        }
        if (mode != DrawerStyleSettings.Mode.PUSH) drawMenu(canvas);
        canvas.restore();
        if (touching) {
            paint.setColor((AppTheme.color(AppTheme.ACCENT) & 0xffffff) | 0x18000000);
            canvas.drawCircle(touchX, touchY, 22 * density, paint);
        }
    }

    /** HTML selector illustrations use a fixed 20% reveal, independent of the saved live progress. */
    private void drawThumbnail(Canvas canvas) {
        float width = phone.width(), height = phone.height(), reveal = width * .2f;
        paint.setColor(0xffffffff);
        paint.setShader(thumbnailFrame);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 11 * density, 11 * density, paint);
        paint.setShader(null); paint.setColor(AppTheme.isDark() ? 0x18bdd3ef : 0x408aa3c4);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
        canvas.drawRoundRect(density / 2, density / 2, getWidth() - density / 2,
                getHeight() - density / 2, 11 * density, 11 * density, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.save(); canvas.clipPath(clip); canvas.translate(phone.left, phone.top);
        paint.setColor(0xffffffff);
        paint.setShader(thumbnailHome); canvas.drawRect(0, 0, width, height, paint);
        canvas.save();
        if (mode != DrawerStyleSettings.Mode.PUSH) canvas.translate(-reveal, 0);
        paint.setShader(thumbnailMenu);
        float radius = mode == DrawerStyleSettings.Mode.FULL_PAGE ? 0 : 6 * density;
        if (mode == DrawerStyleSettings.Mode.OVERLAY) {
            paint.setShader(null); paint.setColor(0x4b061426);
            paint.setShadowLayer(9 * density, 4 * density, 0, 0x4b061426);
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
            paint.clearShadowLayer(); paint.setColor(0xffffffff); paint.setShader(thumbnailMenu);
        }
        canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
        float left = 13 * density + (mode == DrawerStyleSettings.Mode.PUSH ? 0 : reveal);
        float available = width - left - 8 * density;
        paint.setShader(thumbnailLines);
        for (int i = 0; i < 3; i++) {
            float y = (height - 27 * density) / 2 + i * 11 * density;
            float fraction = i == 0 ? .64f : i == 1 ? .55f : .47f;
            canvas.drawRoundRect(left, y, left + available * fraction, y + 5 * density,
                    10 * density, 10 * density, paint);
        }
        canvas.restore();
        if (mode == DrawerStyleSettings.Mode.PUSH) {
            float edge = width - reveal;
            paint.setShader(null); paint.setColor(0x4b061426);
            paint.setShadowLayer(9 * density, -4 * density, 0, 0x4b061426);
            canvas.drawRoundRect(edge, 0, edge + width, height, 6 * density, 6 * density, paint);
            paint.clearShadowLayer(); paint.setColor(0xffffffff); paint.setShader(thumbnailHome);
            canvas.drawRoundRect(edge, 0, edge + width, height, 6 * density, 6 * density, paint);
        }
        paint.setShader(null); canvas.restore();
    }

    private void drawPage(Canvas canvas) {
        canvas.save();
        canvas.translate(geometry.pageX, 0);
        canvas.scale(geometry.pageScale, geometry.pageScale, phone.width(), phone.height() / 2);
        float radius = mode == DrawerStyleSettings.Mode.PUSH ? 12 * density : 0;
        paint.setColor(AppTheme.isDark() ? 0xff122036 : 0xffced8e5);
        canvas.drawRoundRect(-3 * density, 0, phone.width(), phone.height(), radius, radius, paint);
        paint.setColor(AppTheme.color(AppTheme.PAPER));
        canvas.drawRoundRect(0, 0, phone.width(), phone.height(), radius, radius, paint);
        if (interactive && waterfall != null) waterfall.draw(canvas, phone.width(), phone.height());
        if (interactive) {
            paint.setColor(0xfff4f6fd); paint.setTextSize(9 * density);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setShadowLayer(4 * density, 0, density, 0x990b2125);
            float baseline = phone.height() * .42f + paint.getTextSize();
            canvas.drawText("光落林间", 10 * density, baseline, paint);
            canvas.drawText("开启今日学习", 10 * density, baseline + paint.getTextSize() * 1.3f, paint);
            paint.clearShadowLayer();
        } else {
            for (int i = 0; i < 3; i++) {
                paint.setColor(AppTheme.isDark() ? 0xffb0bed0 : 0xffb7cae4);
                float y = phone.height() * (.28f + i * .2f);
                canvas.drawRoundRect(phone.width() * .22f, y, phone.width() * (i == 1 ? .65f : .76f),
                        y + 4 * density, 2 * density, 2 * density, paint);
            }
        }
        canvas.restore();
    }

    private void drawMenu(Canvas canvas) {
        if (motion.progress() <= 0) return;
        canvas.save();
        canvas.translate(geometry.menuX, geometry.menuY);
        canvas.scale(geometry.menuScale, geometry.menuScale, 0, phone.height() / 2);
        canvas.clipRect(0, 0, travel, phone.height());
        paint.setColor(0xffffffff);
        paint.setShader(menuGradient);
        float radius = mode == DrawerStyleSettings.Mode.FULL_PAGE ? 0 : 8 * density;
        canvas.drawRoundRect(0, 0, travel, phone.height(), radius, radius, paint);
        paint.setShader(null);
        if (interactive) {
            paint.setColor(AppTheme.ink(AppTheme.INK)); paint.setTextSize(12 * density);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            canvas.drawText("JPDict", 12 * density, 25 * density, paint);
        }
        int count = interactive ? 5 : 3;
        for (int i = 0; i < count; i++) {
            float y = phone.height() * (interactive ? .23f + i * .135f : .22f + i * .22f);
            float shift = -(1 - geometry.fraction) * (3 + i * 2.4f) * density * scale;
            float rowHeight = phone.height() * .105f;
            if (interactive) {
                paint.setColor(AppTheme.isDark() ? 0x143f5678 : 0x70ffffff);
                canvas.drawRoundRect(10 * density + shift, y, travel - 10 * density + shift,
                        y + rowHeight, 6 * density, 6 * density, paint);
                paint.setColor(AppTheme.isDark() ? 0xffa4bad5 : 0xff8faacb);
                canvas.drawRoundRect(17 * density + shift, y + (rowHeight - 7 * density) / 2,
                        24 * density + shift, y + (rowHeight + 7 * density) / 2, 2 * density, 2 * density, paint);
            }
            paint.setColor(AppTheme.isDark() ? 0xff91a9c6 : 0xff95b0d2);
            float left = interactive ? 31 * density : travel * .18f;
            float right = travel * (i == 2 ? .77f : i == 4 ? .66f : .60f);
            canvas.drawRoundRect(left + shift, y + rowHeight * .4f, right + shift,
                    y + rowHeight * .4f + (interactive ? 4 : 4.5f) * density, 3 * density, 3 * density, paint);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touching = phone.contains(event.getX(), event.getY());
            if (touching && waterfall != null) waterfall.pause();
        }
        if (!touching) return false;
        touchX = event.getX(); touchY = event.getY(); invalidate();
        boolean consumed = motion.handle(event, true, open);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            touching = false;
            if (!consumed) performClick();
            if (waterfall != null) waterfall.resume();
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            touching = false;
            if (waterfall != null) waterfall.resume();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick(); open = !open && limit > 0; motion.animate(open, null); return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (interactive) { info.setClassName("android.widget.Button"); info.setCheckable(true); info.setChecked(open); }
    }

    @Override protected void onDetachedFromWindow() {
        motion.stop(); if (waterfall != null) waterfall.pause(); super.onDetachedFromWindow();
    }
}
