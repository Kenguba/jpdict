package com.yuen.jpdict;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scrollable original-text chips; confirmed words update in place without blanking the document. */
final class SearchTextExplosionView extends LinearLayout {
    interface Actions { void lookup(String word); }
    private final Flow tokens;
    private final ScrollView scroll;
    private final Actions actions;
    private final List<SearchTextSegments.Part> displayed = new ArrayList<>();
    private final Map<Integer, Chip> chips = new HashMap<>();
    private String source = "";
    private boolean hasText;

    SearchTextExplosionView(Context context, Actions actions) {
        super(context); this.actions = actions;
        setOrientation(VERTICAL); setPadding(dp(18), dp(12), dp(18), dp(10));
        TextView title = label("文字大爆炸", 13, 0xff625271); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        addView(title, new LinearLayout.LayoutParams(-1, dp(28)));
        tokens = new Flow(context);
        scroll = new ScrollView(context); scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(false);
        scroll.addView(tokens, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setVisibility(GONE);
    }
    boolean hasText() { return hasText; }

    void show(SearchTextSegments.Page page, List<SearchTextSegments.Part> parts, boolean loading) {
        boolean sourceChanged = !source.equals(page.source);
        if (sourceChanged) {
            // Appending or editing the tail retains the unchanged prefix, including a long scrolled document.
            int common = 0, limit = Math.min(source.length(), page.source.length());
            while (common < limit && source.charAt(common) == page.source.charAt(common)) common++;
            int keep = 0;
            while (keep < displayed.size() && displayed.get(keep).end <= common) keep++;
            displayed.subList(keep, displayed.size()).clear();
            source = page.source; hasText = false;
            if (source.isEmpty()) scroll.scrollTo(0, 0);
        }
        boolean append = !sourceChanged && (displayed.isEmpty() || displayed.get(displayed.size() - 1).end <= page.start);
        SearchTextSegments.replaceRange(displayed, page, parts);
        int words = 0;
        for (SearchTextSegments.Part unit : page.units) if (unit.word) words++;
        hasText |= words > 1 || source.length() > 32 || source.indexOf('\n') >= 0;
        setVisibility(hasText ? VISIBLE : GONE);
        if (append && !chips.isEmpty()) {
            // Subsequent batches add only their new views instead of rescanning the growing document.
            for (SearchTextSegments.Part part : parts) {
                if (part.text.trim().isEmpty()) continue;
                Chip chip = new Chip(); chip.update(part); chips.put(part.start, chip);
                tokens.addView(chip.view, new ViewGroup.LayoutParams(-2, -2)); reveal(chip);
            }
            return;
        }

        Map<Integer, Chip> next = new HashMap<>();
        List<Chip> ordered = new ArrayList<>();
        for (SearchTextSegments.Part part : displayed) {
            if (part.text.trim().isEmpty()) continue;
            Chip chip = chips.get(part.start);
            if (chip == null) chip = new Chip();
            chip.update(part);
            next.put(part.start, chip); ordered.add(chip);
        }
        // Only obsolete ranges leave the tree; loading, matching and returning keep existing views intact.
        for (Map.Entry<Integer, Chip> item : chips.entrySet()) if (!next.containsKey(item.getKey())) {
            item.getValue().cancel(); tokens.removeView(item.getValue().view);
        }
        for (int i = 0; i < ordered.size(); i++) {
            Chip chip = ordered.get(i);
            if (tokens.getChildAt(i) == chip.view) continue;
            boolean inserted = chip.view.getParent() == null;
            if (!inserted) tokens.removeView(chip.view);
            tokens.addView(chip.view, i, new ViewGroup.LayoutParams(-2, -2));
            if (inserted) reveal(chip);
        }
        chips.clear(); chips.putAll(next);
    }
    private void reveal(Chip chip) {
        if (tokens.isLaidOut() && isShown()) {
            chip.view.setAlpha(.55f); chip.view.animate().alpha(1f).setDuration(160).start();
        }
    }
    private final class Chip {
        final TextView view = label("", 15, 0xff66616c);
        final GradientDrawable shape = new GradientDrawable();
        SearchTextSegments.Part part;
        ValueAnimator colors;
        int ink = 0xff66616c, fill = 0xfff3f1f5, stroke = 0xffebe7ef;
        Chip() { shape.setCornerRadius(dp(11)); }
        void update(SearchTextSegments.Part next) {
            if (part != null && part.text.equals(next.text) && part.word == next.word && part.matched == next.matched) {
                part = next; return;
            }
            boolean changed = part != null && part.matched != next.matched;
            if (part == null || !part.text.equals(next.text)) view.setText(next.text);
            if (part == null || part.word != next.word) {
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(next.word ? 11 : 3), dp(7), dp(next.word ? 11 : 3), dp(7));
                view.setMinHeight(next.word ? dp(36) : 0);
                view.setBackground(next.word ? new RippleDrawable(ColorStateList.valueOf(0x12006bff), shape, null) : null);
            }
            part = next;
            view.setContentDescription(next.matched ? "查询本地词条 " + next.text : next.text);
            view.setOnClickListener(next.matched ? v -> actions.lookup(part.text) : null);
            view.setClickable(next.matched);
            int targetInk = next.matched ? 0xff246bc6 : 0xff66616c;
            int targetFill = next.matched ? 0xffedf5ff : 0xfff3f1f5;
            int targetStroke = next.matched ? 0xffd5e7ff : 0xffebe7ef;
            if (colors != null) { colors.cancel(); colors = null; }
            if (changed && view.isLaidOut() && view.isShown()) {
                int fromInk = ink, fromFill = fill, fromStroke = stroke;
                ArgbEvaluator blend = new ArgbEvaluator();
                colors = ValueAnimator.ofFloat(0, 1); colors.setDuration(180);
                colors.addUpdateListener(animation -> {
                    float progress = (float)animation.getAnimatedValue();
                    color((int)blend.evaluate(progress, fromInk, targetInk),
                            (int)blend.evaluate(progress, fromFill, targetFill),
                            (int)blend.evaluate(progress, fromStroke, targetStroke));
                });
                colors.start();
            } else color(targetInk, targetFill, targetStroke);
        }
        void color(int text, int background, int border) {
            ink = text; fill = background; stroke = border;
            view.setTextColor(ink); shape.setColor(fill); shape.setStroke(dp(1), stroke);
        }
        void cancel() {
            if (colors != null) { colors.cancel(); colors = null; }
            view.animate().cancel();
            if (part != null) color(part.matched ? 0xff246bc6 : 0xff66616c,
                    part.matched ? 0xffedf5ff : 0xfff3f1f5,
                    part.matched ? 0xffd5e7ff : 0xffebe7ef);
        }
    }
    @Override protected void onDetachedFromWindow() {
        for (Chip chip : chips.values()) { chip.cancel(); chip.view.setAlpha(1f); }
        super.onDetachedFromWindow();
    }
    private TextView label(String value, int size, int color) {
        TextView label = new TextView(getContext()); label.setText(value); label.setTextSize(size);
        label.setTextColor(color); label.setIncludeFontPadding(false); return label;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Flow extends ViewGroup {
        Flow(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, rowHeight = 0;
            int gap = dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += rowHeight + gap; rowHeight = 0; }
                x += child.getMeasuredWidth() + gap; rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, resolveSize(y + rowHeight + dp(6), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, rowHeight = 0, gap = dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > width) { x = 0; y += rowHeight + gap; rowHeight = 0; }
                child.layout(x, y, x + w, y + h); x += w + gap; rowHeight = Math.max(rowHeight, h);
            }
        }
    }
}
