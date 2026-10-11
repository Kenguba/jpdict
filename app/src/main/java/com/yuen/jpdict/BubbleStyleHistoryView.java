package com.yuen.jpdict;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.List;

/** Six visible slots; additional saved appearances remain reachable by horizontal scrolling. */
final class BubbleStyleHistoryView extends HorizontalScrollView {
    interface Listener { void onSelected(FloatingBubbleAppearance.Config config); }
    private static final int VISIBLE_SLOTS = 6;
    private static final int BLUE = 0xff008fd1;
    private final LinearLayout slots;
    private final Listener listener;
    private List<FloatingBubbleAppearance.Config> history;
    private FloatingBubbleAppearance.Config draft;
    private int rowWidth;

    BubbleStyleHistoryView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setBackground(shape(0xffffffff, 18));
        slots = new LinearLayout(context);
        slots.setGravity(Gravity.CENTER_VERTICAL);
        addView(slots, new LayoutParams(-2, -1));
        history = FloatingBubbleAppearance.savedStyles(context);
    }

    void reload() {
        history = FloatingBubbleAppearance.savedStyles(getContext());
        render();
        smoothScrollTo(0, 0);
    }

    void setDraft(FloatingBubbleAppearance.Config config) {
        draft = config;
        // Selection changes must not recreate or move the horizontal scroll container.
        for (int i = 0; i < Math.min(history.size(), slots.getChildCount()); i++) {
            markSelected((FrameLayout) slots.getChildAt(i), history.get(i).matches(config));
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width != rowWidth) {
            rowWidth = width;
            render();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void render() {
        if (rowWidth <= 0) return;
        slots.removeAllViews();
        int count = Math.max(VISIBLE_SLOTS, history.size());
        for (int i = 0; i < count; i++) {
            int width = ((i + 1) * rowWidth / VISIBLE_SLOTS)
                    - (i * rowWidth / VISIBLE_SLOTS);
            FrameLayout slot = new FrameLayout(getContext());
            slots.addView(slot, new LinearLayout.LayoutParams(width, -1));
            if (i >= history.size()) {
                View empty = new View(getContext());
                GradientDrawable circle = shape(0x00000000, 100);
                circle.setStroke(dp(1), 0xffc8cdd2, dp(4), dp(3));
                empty.setBackground(circle);
                int diameter = Math.min(dp(40), width - dp(12));
                slot.addView(empty, new FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER));
                slot.setContentDescription("空样式位置");
                slot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                continue;
            }
            FloatingBubbleAppearance.Config config = history.get(i);
            FrameLayout visual = new FrameLayout(getContext());
            FloatingBubbleAppearance.applyVisual(visual, config);
            visual.setElevation(0);
            visual.setAlpha(config.alpha());
            visual.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = config.sizePx(getContext());
            slot.addView(visual, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            float scale = Math.min(1f, Math.min(width - dp(12), dp(48)) / (float) size);
            visual.setScaleX(scale);
            visual.setScaleY(scale);
            SearchIconView check = new SearchIconView(getContext(), SearchIconView.Kind.CHECK);
            check.setColor(0xffffffff);
            check.setPadding(dp(2), dp(2), dp(2), dp(2));
            check.setBackground(shape(BLUE, 8));
            check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(14), dp(14),
                    Gravity.TOP | Gravity.END);
            badge.topMargin = dp(7);
            badge.rightMargin = dp(4);
            slot.addView(check, badge);
            slot.setContentDescription("已保存样式 " + (i + 1) + "，" + config.sizeDp + " dp，"
                    + config.opacityPercent + "%透明度，"
                    + (config.roundnessPercent == 100 ? "圆形" : config.roundnessPercent == 0
                            ? "方形" : "圆角 " + config.roundnessPercent + "%")
                    + "，" + (config.style == FloatingBubbleAppearance.STYLE_IMAGE ? "图片"
                            : config.style == FloatingBubbleAppearance.STYLE_SEARCH ? "搜索图标"
                            : config.style == FloatingBubbleAppearance.STYLE_CUSTOM ? config.text : "あ"));
            slot.setFocusable(true);
            slot.setOnClickListener(v -> listener.onSelected(config));
            markSelected(slot, config.matches(draft));
        }
    }

    private void markSelected(FrameLayout slot, boolean selected) {
        slot.setSelected(selected);
        GradientDrawable outline = shape(0x00000000, 14);
        if (selected) outline.setStroke(dp(2), BLUE);
        slot.setBackground(outline);
        slot.getChildAt(1).setVisibility(selected ? VISIBLE : GONE);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
