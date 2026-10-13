package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Shape-clipped image workspace; sliders and one/two-finger input share the appearance draft. */
final class BubbleImageEditorView extends LinearLayout {
    interface Listener { void onChanged(FloatingBubbleAppearance.Config config); }
    private final CropWorkspace crop;
    private final SeekBar zoom, horizontal, vertical;
    private final TextView zoomValue, horizontalValue, verticalValue;
    private FloatingBubbleAppearance.Config config = FloatingBubbleAppearance.defaults();
    private Listener listener;
    private boolean binding;

    BubbleImageEditorView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(10), dp(8), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff4f5f6); background.setCornerRadius(dp(12));
        setBackground(background);
        LinearLayout titleRow = row();
        TextView title = text("图片大小与位置", 11, 0xff354860, true);
        titleRow.addView(title, new LayoutParams(0, -2, 1));
        TextView reset = text("居中重置", 10, 0xff008fd1, true);
        reset.setGravity(Gravity.CENTER); reset.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable button = new GradientDrawable(); button.setColor(0xffdef7ff); button.setCornerRadius(dp(10));
        reset.setBackground(button); reset.setFocusable(true);
        titleRow.addView(reset, new LayoutParams(-2, dp(34)));
        addView(titleRow, new LayoutParams(-1, -2));
        crop = new CropWorkspace(context);
        FrameLayout cropWrap = new FrameLayout(context);
        cropWrap.setClipChildren(false); cropWrap.setClipToPadding(false);
        FrameLayout.LayoutParams cropParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cropWrap.addView(crop, cropParams);
        LayoutParams wrapParams = new LayoutParams(-1, -2);
        wrapParams.topMargin = dp(13); wrapParams.bottomMargin = dp(5);
        addView(cropWrap, wrapParams);
        zoomValue = text("", 10, 0xff34475e, false);
        horizontalValue = text("", 10, 0xff34475e, false);
        verticalValue = text("", 10, 0xff34475e, false);
        zoom = addSlider("大小", "图片大小，20 至 300 百分比", 280, zoomValue);
        horizontal = addSlider("水平", "图片水平位置，负 100 至正 100 百分比", 200, horizontalValue);
        vertical = addSlider("垂直", "图片垂直位置，负 100 至正 100 百分比", 200, verticalValue);
        TextView help = text("单指拖动位置，双指捏合缩放，也可使用滑块调整", 10, 0xff8995a5, false);
        help.setGravity(Gravity.CENTER); help.setLineSpacing(dp(2), 1);
        LayoutParams hintParams = new LayoutParams(-1, -2); hintParams.topMargin = dp(10);
        addView(help, hintParams);
        reset.setOnClickListener(v -> change(100, 0, 0));
        zoom.setOnSeekBarChangeListener(sliderListener(() -> change(zoom.getProgress() + 20,
                config.imageXPercent, config.imageYPercent)));
        horizontal.setOnSeekBarChangeListener(sliderListener(() -> change(config.imageZoomPercent,
                horizontal.getProgress() - 100, config.imageYPercent)));
        vertical.setOnSeekBarChangeListener(sliderListener(() -> change(config.imageZoomPercent,
                config.imageXPercent, vertical.getProgress() - 100)));
        bind();
    }

    void setConfig(FloatingBubbleAppearance.Config config) {
        if (this.config == config) return;
        boolean sameImage = this.config.imageName.equals(config.imageName)
                && this.config.imageZoomPercent == config.imageZoomPercent
                && this.config.imageXPercent == config.imageXPercent
                && this.config.imageYPercent == config.imageYPercent
                && this.config.shape == config.shape
                && this.config.roundnessPercent == config.roundnessPercent
                && this.config.legacyVisual == config.legacyVisual;
        this.config = config;
        if (!sameImage) bind();
    }
    void setListener(Listener listener) { this.listener = listener; }
    void finishEditing() { crop.clearFocus(); }
    private void change(float zoom, float x, float y) {
        FloatingBubbleAppearance.Builder builder = new FloatingBubbleAppearance.Builder(config);
        builder.imageZoomPercent = clamp(Math.round(zoom), 20, 300);
        builder.imageXPercent = clamp(Math.round(x), -100, 100);
        builder.imageYPercent = clamp(Math.round(y), -100, 100);
        builder.legacyVisual = false;
        config = builder.build(); bind();
        if (listener != null) listener.onChanged(config);
    }
    private void bind() {
        binding = true;
        zoom.setProgress(Math.round(config.imageZoomPercent) - 20);
        horizontal.setProgress(Math.round(config.imageXPercent) + 100);
        vertical.setProgress(Math.round(config.imageYPercent) + 100);
        zoomValue.setText(Math.round(config.imageZoomPercent) + "%");
        horizontalValue.setText(Math.round(config.imageXPercent) + "%");
        verticalValue.setText(Math.round(config.imageYPercent) + "%");
        crop.setContentDescription("图片裁切工作区，水平 " + Math.round(config.imageXPercent)
                + "%，垂直 " + Math.round(config.imageYPercent) + "%，大小 "
                + Math.round(config.imageZoomPercent) + "%，单指拖动或双指捏合调整");
        crop.invalidate(); binding = false;
    }

    private final class CropWorkspace extends View {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private int pointerA = -1, pointerB = -1;
        private float beginCenterX, beginCenterY, beginDistance, beginZoom, beginX, beginY;
        private boolean dragging;
        CropWorkspace(Context context) { super(context); setFocusable(true); setClickable(true); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int wanted = dp(getResources().getConfiguration().screenWidthDp <= 355 ? 128 : 145);
            int side = Math.min(resolveSize(wanted, widthSpec), resolveSize(wanted, heightSpec));
            setMeasuredDimension(side, side);
        }
        @Override protected void onDraw(Canvas canvas) {
            bounds.set(0, 0, getWidth(), getHeight());
            Path shape = BubbleAppearanceRenderer.shapePath(config, getWidth(), getHeight());
            int save = canvas.save(); canvas.clipPath(shape);
            brush.setStyle(Paint.Style.FILL); brush.setColor(0xffffffff); canvas.drawRect(bounds, brush);
            brush.setColor(0xffebeff5);
            int cell = Math.max(1, dp(9));
            for (int y = 0; y < getHeight(); y += cell) {
                for (int x = 0; x < getWidth(); x += cell) {
                    if (((x / cell + y / cell) & 1) == 0) canvas.drawRect(x, y, x + cell, y + cell, brush);
                }
            }
            BubbleAppearanceRenderer.drawImage(canvas, getContext(), config, bounds);
            canvas.restoreToCount(save);
            brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(dp(1)); brush.setColor(0xffd4deeb);
            canvas.drawPath(shape, brush);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!FloatingBubbleAppearance.hasCustomImage(getContext(), config)) return false;
                    Region hit = new Region();
                    hit.setPath(BubbleAppearanceRenderer.shapePath(config, getWidth(), getHeight()),
                            new Region(0, 0, getWidth(), getHeight()));
                    if (!hit.contains(Math.round(event.getX()), Math.round(event.getY()))) return false;
                    dragging = true; getParent().requestDisallowInterceptTouchEvent(true);
                    begin(event, -1); return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!dragging) return false;
                    begin(event, -1); return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    move(event); return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (!dragging) return false;
                    begin(event, event.getActionIndex()); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    stopDrag();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick(); return true;
                default: return super.onTouchEvent(event);
            }
        }
        private void stopDrag() {
            dragging = false; pointerA = pointerB = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        @Override protected void onDetachedFromWindow() { stopDrag(); super.onDetachedFromWindow(); }
        private void begin(MotionEvent event, int excluded) {
            pointerA = pointerB = -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == excluded) continue;
                if (pointerA < 0) pointerA = event.getPointerId(i);
                else if (pointerB < 0) { pointerB = event.getPointerId(i); break; }
            }
            int a = event.findPointerIndex(pointerA), b = event.findPointerIndex(pointerB);
            if (a < 0) return;
            beginCenterX = b < 0 ? event.getX(a) : (event.getX(a) + event.getX(b)) / 2;
            beginCenterY = b < 0 ? event.getY(a) : (event.getY(a) + event.getY(b)) / 2;
            beginDistance = b < 0 ? 0 : distance(event, a, b);
            beginZoom = config.imageZoomPercent; beginX = config.imageXPercent; beginY = config.imageYPercent;
        }
        private void move(MotionEvent event) {
            int a = event.findPointerIndex(pointerA), b = event.findPointerIndex(pointerB);
            if (a < 0) { begin(event, -1); return; }
            float centerX = b < 0 ? event.getX(a) : (event.getX(a) + event.getX(b)) / 2;
            float centerY = b < 0 ? event.getY(a) : (event.getY(a) + event.getY(b)) / 2;
            float width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
            if (b >= 0 && beginDistance > 0) {
                float nextZoom = clamp(Math.round(beginZoom * distance(event, a, b) / beginDistance), 20, 300);
                float ratio = nextZoom / beginZoom;
                float nextX = (centerX - width / 2 - ratio * (beginCenterX - width / 2 - beginX * width / 100)) * 100 / width;
                float nextY = (centerY - height / 2 - ratio * (beginCenterY - height / 2 - beginY * height / 100)) * 100 / height;
                change(nextZoom, nextX, nextY);
            } else {
                change(beginZoom, beginX + (centerX - beginCenterX) * 100 / width,
                        beginY + (centerY - beginCenterY) * 100 / height);
            }
        }
        private float distance(MotionEvent event, int a, int b) {
            return (float) Math.hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b));
        }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            float dx = 0, dy = 0, step = event.isShiftPressed() ? 10 : 2;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) dx = -step;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) dx = step;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) dy = -step;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) dy = step;
            else return super.onKeyDown(code, event);
            change(config.imageZoomPercent, config.imageXPercent + dx, config.imageYPercent + dy); return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private SeekBar addSlider(String label, String description, int max, TextView output) {
        LinearLayout row = row(); row.addView(text(label, 11, 0xff60718a, true), new LayoutParams(dp(29), -2));
        SeekBar slider = new SeekBar(getContext()); slider.setMax(max); slider.setContentDescription(description); BubbleSliderStyle.apply(slider);
        row.addView(slider, new LayoutParams(0, dp(36), 1)); output.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); row.addView(output, new LayoutParams(dp(36), -2));
        LayoutParams params = new LayoutParams(-1, -2); params.topMargin = dp(7); addView(row, params); return slider;
    }
    private SeekBar.OnSeekBarChangeListener sliderListener(Runnable change) { return new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser && !binding) change.run(); } public void onStartTrackingTouch(SeekBar bar) { } public void onStopTrackingTouch(SeekBar bar) { } }; }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(String label, int size, int color, boolean bold) { TextView view = new TextView(getContext()); view.setText(label); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false); if (bold) view.setTypeface(Typeface.DEFAULT_BOLD); return view; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
