package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;

/** Small platform-only HSV picker. The appearance editor owns the selected RGB color. */
final class BubbleColorPickerView extends LinearLayout {
    interface Listener { void onColorChanged(int color); }

    private final float[] hsv = {0f, 1f, 1f};
    private final ColorPlane plane;
    private final SeekBar hue;
    private Listener listener;

    BubbleColorPickerView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        plane = new ColorPlane(context);
        addView(plane, new LayoutParams(-1, dp(144)));
        hue = new SeekBar(context);
        hue.setMax(360);
        hue.setContentDescription("色相");
        BubbleSliderStyle.applyHue(hue);
        addView(hue, new LayoutParams(-1, dp(44)));
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                hsv[0] = value;
                publishColor();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    void setListener(Listener listener) { this.listener = listener; }

    void setColor(int color) {
        // Preserve a chosen hue at white/black, where RGB cannot encode that hue.
        if (Color.HSVToColor(hsv) == color) return;
        Color.colorToHSV(color, hsv);
        hue.setProgress(Math.round(hsv[0]));
        plane.updateDescription();
        plane.invalidate();
    }

    private void publishColor() {
        plane.updateDescription();
        plane.invalidate();
        if (listener != null) listener.onColorChanged(Color.HSVToColor(hsv));
    }

    private final class ColorPlane extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hueColor = {0f, 1f, 1f};
        private final RectF area = new RectF();
        private Shader saturation;
        private Shader brightness;

        ColorPlane(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            updateDescription();
        }

        void updateDescription() {
            setContentDescription("饱和度 " + Math.round(hsv[1] * 100) + "% · 明度 "
                    + Math.round(hsv[2] * 100) + "%（方向键可调节）");
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float inset = dp(8);
            area.set(inset, inset, Math.max(inset + 1, w - inset), Math.max(inset + 1, h - inset));
            saturation = new LinearGradient(area.left, 0, area.right, 0,
                    Color.WHITE, 0x00ffffff, Shader.TileMode.CLAMP);
            brightness = new LinearGradient(0, area.top, 0, area.bottom,
                    0x00000000, Color.BLACK, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            hueColor[0] = hsv[0];
            paint.setColor(Color.HSVToColor(hueColor));
            canvas.drawRoundRect(area, dp(4), dp(4), paint);
            paint.setShader(saturation);
            canvas.drawRoundRect(area, dp(4), dp(4), paint);
            paint.setShader(brightness);
            canvas.drawRoundRect(area, dp(4), dp(4), paint);
            paint.setShader(null);
            float x = area.left + hsv[1] * area.width();
            float y = area.top + (1 - hsv[2]) * area.height();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(5), paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xff171819);
            canvas.drawCircle(x, y, dp(7), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    hsv[1] = Math.max(0, Math.min(1, (event.getX() - area.left) / area.width()));
                    hsv[2] = 1 - Math.max(0, Math.min(1, (event.getY() - area.top) / area.height()));
                    publishColor();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default: return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: hsv[1] = Math.max(0, hsv[1] - .05f); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: hsv[1] = Math.min(1, hsv[1] + .05f); break;
                case KeyEvent.KEYCODE_DPAD_UP: hsv[2] = Math.min(1, hsv[2] + .05f); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: hsv[2] = Math.max(0, hsv[2] - .05f); break;
                default: return super.onKeyDown(keyCode, event);
            }
            publishColor();
            return true;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
