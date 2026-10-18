package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import java.util.List;

/** Saved identities are separate from appearance, including visually equal styles. */
final class BubbleStyleHistoryView extends LinearLayout {
    interface Listener {
        void onSelected(FloatingBubbleAppearance.Config config);
        void onAdd();
        void onDeleteRequested(String presetId);
    }
    private static final int BLUE = AppTheme.BLUE;
    private final LinearLayout slots;
    private final HorizontalScrollView scroll;
    private final Pager pager;
    private final Listener listener;
    private List<FloatingBubbleAppearance.Config> history;
    private String selectedId;

    BubbleStyleHistoryView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(6), dp(10), dp(5));
        setBackground(shape(AppTheme.CARD, 18));
        scroll = new HorizontalScrollView(context) {
            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
                super.onScrollChanged(x, y, oldX, oldY);
                if (pager != null) pager.invalidate();
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setClipToPadding(false);
        slots = new LinearLayout(context);
        slots.setGravity(Gravity.CENTER_VERTICAL);
        slots.setPadding(dp(3), dp(3), dp(3), dp(5));
        scroll.addView(slots, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(scroll, new LayoutParams(-1, dp(72)));
        pager = new Pager(context);
        LayoutParams track = new LayoutParams(dp(72), dp(4));
        track.gravity = Gravity.CENTER_HORIZONTAL;
        track.topMargin = dp(2);
        addView(pager, track);
        reload();
    }
    int count() { return history.size(); }
    void reload() { history = FloatingBubbleAppearance.savedStyles(getContext()); render(); }
    void scrollToStart() { scroll.smoothScrollTo(0, 0); }
    void setDraft(FloatingBubbleAppearance.Config config) {
        selectedId = config == null ? null : config.presetId;
        for (int i = 0; i < history.size() && i < slots.getChildCount(); i++) {
            markSelected(slots.getChildAt(i), history.get(i).presetId.equals(selectedId));
        }
    }
    private void render() {
        slots.removeAllViews();
        boolean narrow = getResources().getConfiguration().screenWidthDp <= 355;
        int width = dp(narrow ? 54 : 59), diameter = dp(narrow ? 42 : 46);
        int gap = dp(narrow ? 8 : 10);
        for (int i = 0; i < history.size(); i++) {
            FloatingBubbleAppearance.Config config = history.get(i);
            PresetSlot slot = new PresetSlot(getContext(), config.presetId);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(width, dp(64));
            if (i > 0) cell.leftMargin = gap;
            slots.addView(slot, cell);
            FrameLayout visual = new FrameLayout(getContext());
            FloatingBubbleAppearance.Builder sample = new FloatingBubbleAppearance.Builder(config);
            sample.sizeDp = narrow ? 42 : 46;
            FloatingBubbleAppearance.applyVisual(visual, sample.build());
            visual.setElevation(0);
            // HTML keeps thumbnails opaque so a saved 0% bubble can still be selected.
            visual.setAlpha(1f);
            visual.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            slot.addView(visual, new FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER));
            slot.setContentDescription("已保存样式 " + (i + 1) + "，点按应用，长按删除，"
                    + config.sizeDp + " dp，" + config.opacityPercent + "%透明度");
            slot.setFocusable(true);
            slot.setOnClickListener(v -> listener.onSelected(config));
            slot.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)) {
                    listener.onDeleteRequested(config.presetId); return true;
                }
                return false;
            });
            slot.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                }
                @Override public boolean performAccessibilityAction(View host, int action,
                        android.os.Bundle arguments) {
                    if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                        listener.onDeleteRequested(config.presetId); return true;
                    }
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
            markSelected(slot, config.presetId.equals(selectedId));
        }
        View add = new AddSymbol(getContext());
        GradientDrawable dashed = shape(0x00000000, 100);
        dashed.setStroke(dp(1.6f), AppTheme.LINE, dp(4), dp(3));
        add.setBackground(dashed);
        FrameLayout plus = new FrameLayout(getContext());
        plus.setBackground(shape(AppTheme.SOFT, 14));
        plus.addView(add, new FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER));
        plus.setContentDescription(history.size() >= 10 ? "已达十个样式，无法新增" : "添加当前样式");
        plus.setFocusable(true); plus.setAlpha(history.size() >= 10 ? .45f : 1f);
        plus.setOnClickListener(v -> listener.onAdd());
        LinearLayout.LayoutParams addCell = new LinearLayout.LayoutParams(width, dp(64));
        if (!history.isEmpty()) addCell.leftMargin = gap;
        slots.addView(plus, addCell); pager.invalidate();
    }
    private void markSelected(View slot, boolean selected) {
        slot.setSelected(selected);
        GradientDrawable outline = shape(selected ? AppTheme.SOFT : AppTheme.CARD, 14);
        if (selected) outline.setStroke(dp(1.6f), BLUE);
        slot.setBackground(outline);
    }
    private final class PresetSlot extends FrameLayout {
        private final String id;
        private float startX, startY;
        private boolean longPressed;
        private final Runnable deletion;
        PresetSlot(Context context, String id) {
            super(context); this.id = id;
            deletion = () -> {
                longPressed = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onDeleteRequested(this.id);
            };
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false; startX = event.getX(); startY = event.getY();
                    removeCallbacks(deletion); postDelayed(deletion, 560); break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - startX, event.getY() - startY) > dp(11)) {
                        removeCallbacks(deletion);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(deletion);
                    if (longPressed) { setPressed(false); return true; }
                    break;
                case MotionEvent.ACTION_CANCEL: removeCallbacks(deletion); break;
                case MotionEvent.ACTION_POINTER_DOWN: removeCallbacks(deletion); break;
                default: break;
            }
            return super.onTouchEvent(event);
        }
        @Override protected void onDetachedFromWindow() {
            removeCallbacks(deletion); super.onDetachedFromWindow();
        }
    }
    private final class Pager extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pager(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            int total = slots.getWidth(), visible = scroll.getWidth(), overflow = total - visible;
            if (overflow < dp(2)) return;
            float thumb = Math.max(dp(15), getWidth() * visible / (float) total);
            float left = (getWidth() - thumb) * scroll.getScrollX() / overflow;
            paint.setColor(AppTheme.color(AppTheme.LINE));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(4), dp(4), paint);
            paint.setColor(AppTheme.color(AppTheme.LINE));
            canvas.drawRoundRect(left, 0, left + thumb, getHeight(), dp(4), dp(4), paint);
        }
    }
    private final class AddSymbol extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        AddSymbol(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setColor(AppTheme.ink(AppTheme.MUTED)); paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(AppTheme.ink(AppTheme.MUTED));
            float centerX = getWidth()/2f, centerY = getHeight()/2f;
            float radius = dp(11);
            canvas.drawLine(centerX-radius, centerY, centerX+radius, centerY, paint);
            canvas.drawLine(centerX, centerY-radius, centerX, centerY+radius, paint);
        }
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new AppTheme.Shape(); result.setColor(color);
        result.setCornerRadius(dp(radius)); return result;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
