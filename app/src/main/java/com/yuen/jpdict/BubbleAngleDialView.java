package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** HTML v18's clockwise compass: zero points up; only the outer region starts a drag. */
final class BubbleAngleDialView extends View {
    interface Listener { void onAngleChanged(float angle); }
    private static final String[] DIRECTIONS = {"向上", "右上", "向右", "右下", "向下", "左下", "向左", "左上"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private float angle = 135;
    private boolean dragging;
    private int pointer = -1;
    private Listener listener;

    BubbleAngleDialView(Context context) {
        super(context);
        setFocusable(true);
        setClickable(true);
        updateDescription();
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setAngle(float angle) {
        this.angle = normalize(angle);
        updateDescription();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int wanted = dp(getResources().getConfiguration().screenWidthDp <= 355 ? 160 : 174);
        int width = resolveSize(wanted, widthSpec);
        int height = resolveSize(wanted, heightSpec);
        int side = Math.min(width, height);
        setMeasuredDimension(width, side);
    }

    @Override protected void onDraw(Canvas canvas) {
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.max(1, side / 2f);
        float scale = side / dp(174);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppTheme.color(AppTheme.LINE));
        canvas.drawCircle(cx, cy, radius, paint);
        ring.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(AppTheme.ink(AppTheme.BLUE));
        canvas.drawArc(ring, -90, angle, true, paint);
        paint.setColor(AppTheme.color(AppTheme.SOFT));
        canvas.drawCircle(cx, cy, Math.max(1, radius - dp(9) * scale), paint);
        paint.setColor(AppTheme.color(AppTheme.LINE));
        paint.setStrokeWidth(dp(2) * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float inner = radius - dp(26) * scale;
        float outer = radius - dp(17) * scale;
        for (int i = 0; i < 4; i++) {
            double radians = Math.toRadians(i * 90);
            canvas.drawLine(cx + (float) Math.sin(radians) * inner,
                    cy - (float) Math.cos(radians) * inner,
                    cx + (float) Math.sin(radians) * outer,
                    cy - (float) Math.cos(radians) * outer, paint);
        }
        double radians = Math.toRadians(angle);
        float sin = (float) Math.sin(radians), cos = (float) Math.cos(radians);
        paint.setColor(AppTheme.ink(AppTheme.BLUE));
        paint.setStrokeWidth(dp(3) * scale);
        canvas.drawLine(cx + sin * (radius - dp(56) * scale),
                cy - cos * (radius - dp(56) * scale),
                cx + sin * (radius - dp(32) * scale),
                cy - cos * (radius - dp(32) * scale), paint);
        float orbit = radius - dp(8) * scale;
        float knobX = cx + sin * orbit, knobY = cy - cos * orbit;
        paint.setColor(AppTheme.color(AppTheme.CARD));
        canvas.drawCircle(knobX, knobY, dp(9) * scale, paint);
        paint.setColor(AppTheme.ink(AppTheme.BLUE));
        canvas.drawCircle(knobX, knobY, dp(6) * scale, paint);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(23) * scale);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(AppTheme.ink(AppTheme.BLUE));
        canvas.drawText(Math.round(angle) + "°", cx, cy + dp(1) * scale, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(dp(11) * scale);
        paint.setColor(AppTheme.ink(AppTheme.MUTED));
        canvas.drawText(DIRECTIONS[Math.round(angle / 45) % 8], cx, cy + dp(20) * scale, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int index = pointer < 0 ? 0 : event.findPointerIndex(pointer);
        float dx = index < 0 ? 0 : event.getX(index) - getWidth() / 2f;
        float dy = index < 0 ? 0 : event.getY(index) - getHeight() / 2f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.hypot(dx, dy) < Math.min(getWidth(), getHeight()) * .25) return false;
                dragging = true; pointer = event.getPointerId(0);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                change((float) Math.toDegrees(Math.atan2(dx, -dy)));
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                if (index < 0) { stopDrag(); return true; }
                change((float) Math.toDegrees(Math.atan2(dx, -dy)));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                stopDrag();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: return dragging;
            case MotionEvent.ACTION_POINTER_UP:
                if (dragging && event.getPointerId(event.getActionIndex()) == pointer) stopDrag();
                return true;
            default: return super.onTouchEvent(event);
        }
    }

    private void stopDrag() {
        dragging = false; pointer = -1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
    @Override protected void onDetachedFromWindow() { stopDrag(); super.onDetachedFromWindow(); }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        int step = event.isShiftPressed() ? 15 : 1;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN) change(angle - step);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP) change(angle + step);
        else if (code == KeyEvent.KEYCODE_MOVE_HOME) change(0);
        else if (code == KeyEvent.KEYCODE_MOVE_END) change(359);
        else return super.onKeyDown(code, event);
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 359, angle));
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { change(angle + 15); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { change(angle - 15); return true; }
        if (Build.VERSION.SDK_INT >= 24 && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                && arguments != null && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            change(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
    private void change(float next) {
        setAngle(next);
        if (listener != null) listener.onAngleChanged(angle);
    }
    private void updateDescription() {
        setContentDescription("线性渐变方向 " + Math.round(angle) + " 度 · "
                + DIRECTIONS[Math.round(angle / 45) % 8] + "，拖动外圈或用方向键调整");
    }
    private static float normalize(float value) { return ((Math.round(value) % 360) + 360) % 360; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
