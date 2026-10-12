package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.*;

/** Native reorder interaction: one lifted card and one slot, with FLIP row motion. */
final class DictionaryListView extends FrameLayout {
    interface Sorted { void save(List<String> ids); }
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final Sorted sorted;
    private DictionaryCardView lifted;
    private TextView slot;
    private int originalIndex;
    private float pointerY, pointerOffset;
    private boolean settling, saving;
    private final int[] location = new int[2];
    private final Rect viewport = new Rect();

    DictionaryListView(Context context, ScrollView scroll, Sorted sorted) {
        super(context); this.scroll = scroll; this.sorted = sorted;
        setClipChildren(false); setClipToPadding(false);
        rows = new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false); rows.setClipToPadding(false); addView(rows, new LayoutParams(-1, -2));
    }
    void add(DictionaryCardView card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); rows.addView(card, lp);
    }
    List<DictionaryCardView> cards() {
        List<DictionaryCardView> cards = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i) instanceof DictionaryCardView) cards.add((DictionaryCardView)rows.getChildAt(i));
        return cards;
    }
    void setSaving(boolean value) { saving = value; }
    boolean isDragging() { return lifted != null; }
    void start(DictionaryCardView card) {
        if (lifted != null || saving || !card.isAttachedToWindow()) return;
        originalIndex = rows.indexOfChild(card); if (originalIndex < 0) return;
        lifted = card;
        getLocationOnScreen(location); pointerOffset = pointerY - location[1] - card.getTop();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ViewGroup.LayoutParams old = card.getLayoutParams();
        slot = new TextView(getContext()); slot.setGravity(Gravity.CENTER); slot.setText("松手放置"); slot.setTextSize(12); slot.setTextColor(0xff8696ac);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xffeaf1fa); background.setCornerRadius(dp(16)); background.setStroke(dp(1), 0xffb1c8e3, dp(5), dp(4)); slot.setBackground(background);
        slot.setContentDescription("排序占位，原位置已腾空");
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(-1, card.getHeight()); slotLp.bottomMargin = ((LinearLayout.LayoutParams)old).bottomMargin;
        int top = card.getTop(); rows.removeView(card); rows.addView(slot, originalIndex, slotLp);
        LayoutParams lp = new LayoutParams(card.getWidth(), card.getHeight()); lp.topMargin = top; addView(card, lp);
        card.animate().scaleX(1.025f).scaleY(1.025f).setDuration(150).start();
        getParent().requestDisallowInterceptTouchEvent(true);
        postOnAnimation(autoScroll);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        pointerY = event.getRawY();
        if (lifted == null) return super.dispatchTouchEvent(event);
        if (settling) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) updateDrag();
        else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) finish(event.getActionMasked() == MotionEvent.ACTION_CANCEL);
        return true;
    }
    private void updateDrag() {
        if (lifted == null || settling) return;
        getLocationOnScreen(location);
        float top = Math.max(0, Math.min(rows.getHeight() - lifted.getHeight(), pointerY - location[1] - pointerOffset));
        lifted.setTranslationY(top - lifted.getTop());
        float center = top + lifted.getHeight() / 2f;
        int from = rows.indexOfChild(slot), target = from;
        while (target < rows.getChildCount() - 1 && center > midpoint(rows.getChildAt(target + 1))) target++;
        while (target > 0 && center < midpoint(rows.getChildAt(target - 1))) target--;
        if (target != from) moveSlot(target);
    }
    private float midpoint(View row) { return row.getTop() + row.getHeight() / 2f; }
    private void moveSlot(int target) {
        Map<View, Float> before = new HashMap<>();
        for (int i = 0; i < rows.getChildCount(); i++) { View v = rows.getChildAt(i); before.put(v, v.getTop() + v.getTranslationY()); v.animate().cancel(); }
        rows.removeView(slot); rows.addView(slot, target);
        rows.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (rows.getViewTreeObserver().isAlive()) rows.getViewTreeObserver().removeOnPreDrawListener(this);
                for (Map.Entry<View, Float> entry : before.entrySet()) {
                    View row = entry.getKey(); if (row == slot || row.getParent() != rows) continue;
                    row.setTranslationY(entry.getValue() - row.getTop());
                    row.animate().translationY(0).setDuration(190).setInterpolator(new DecelerateInterpolator()).start();
                }
                return true;
            }
        });
    }
    private final Runnable autoScroll = new Runnable() {
        public void run() {
            if (lifted == null || settling) return;
            if (scroll.getGlobalVisibleRect(viewport)) {
                int step = pointerY < viewport.top + dp(56) ? -dp(9) : pointerY > viewport.bottom - dp(56) ? dp(9) : 0;
                if (step != 0) { scroll.scrollBy(0, step); updateDrag(); }
            }
            postOnAnimation(this);
        }
    };
    private void finish(boolean cancelled) {
        if (lifted == null || settling) return;
        settling = true; removeCallbacks(autoScroll);
        if (cancelled) moveSlot(originalIndex);
        rows.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (rows.getViewTreeObserver().isAlive()) rows.getViewTreeObserver().removeOnPreDrawListener(this);
                if (lifted == null) return true;
                DictionaryCardView card = lifted;
                card.animate().translationY(slot.getTop() - card.getTop()).scaleX(1).scaleY(1).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                        int index = rows.indexOfChild(slot); rows.removeView(slot); removeView(card);
                        card.setTranslationY(0);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); rows.addView(card, index, lp);
                        lifted = null; slot = null; settling = false; getParent().requestDisallowInterceptTouchEvent(false);
                        if (!cancelled && index != originalIndex) { List<String> ids = new ArrayList<>(); for (DictionaryCardView c : cards()) ids.add(c.dictionary.id); sorted.save(ids); }
                    }).start();
                return true;
            }
        });
        rows.invalidate();
    }
    boolean cancelDrag() { if (lifted == null) return false; finish(true); return true; }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(autoScroll);
        if (lifted != null) { lifted.animate().cancel(); lifted = null; }
        super.onDetachedFromWindow();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
