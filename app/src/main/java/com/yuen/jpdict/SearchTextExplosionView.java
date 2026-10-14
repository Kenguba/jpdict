package com.yuen.jpdict;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Readable wrapping text chips; blue chips are confirmed local dictionary entries. */
final class SearchTextExplosionView extends LinearLayout {
    interface Actions { void lookup(String word); void page(int offset); }
    private final TextView hint;
    private final Flow tokens;
    private final ScrollView scroll;
    private final LinearLayout paging;
    private final Actions actions;
    private final List<Integer> previousOffsets = new ArrayList<>();
    private String source = "";
    private int currentOffset;
    private boolean hasText;

    SearchTextExplosionView(Context context, Actions actions) {
        super(context); this.actions = actions;
        setOrientation(VERTICAL); setPadding(dp(18), dp(12), dp(18), dp(10));
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("文字大爆炸", 13, 0xff625271); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title);
        hint = label("", 11, 0xff95909b); hint.setGravity(Gravity.RIGHT); hint.setSingleLine(true);
        heading.addView(hint, new LinearLayout.LayoutParams(0, dp(28), 1));
        addView(heading, new LinearLayout.LayoutParams(-1, -2));
        tokens = new Flow(context);
        scroll = new ScrollView(context); scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(false);
        scroll.addView(tokens, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        paging = new LinearLayout(context); paging.setGravity(Gravity.CENTER_VERTICAL);
        addView(paging, new LinearLayout.LayoutParams(-1, -2));
        setVisibility(GONE);
    }
    boolean hasText() { return hasText; }

    void show(SearchTextSegments.Page page, List<SearchTextSegments.Part> parts, boolean loading) {
        if (!source.equals(page.source)) { source = page.source; previousOffsets.clear(); currentOffset = 0; }
        if (page.start != currentOffset) { currentOffset = page.start; scroll.scrollTo(0, 0); }
        int words = 0, matches = 0;
        for (SearchTextSegments.Part unit : page.units) if (unit.word) words++;
        for (SearchTextSegments.Part part : parts) if (part.matched) matches++;
        hasText = words > 1 || page.source.length() > 32 || page.source.indexOf('\n') >= 0;
        setVisibility(hasText ? VISIBLE : GONE);
        hint.setText(loading ? "正在匹配本地词库…" : matches > 0 ? "点击蓝色词条查询" : "蓝色词条可点击查询");
        tokens.removeAllViews();
        for (SearchTextSegments.Part part : parts) {
            if (part.text.trim().isEmpty()) continue;
            TextView chip = label(part.text, 15, part.matched ? 0xff246bc6 : 0xff66616c);
            chip.setMaxLines(2); chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(part.word ? 11 : 3), dp(7), dp(part.word ? 11 : 3), dp(7));
            if (part.word) {
                chip.setMinHeight(dp(36));
                chip.setBackground(background(part.matched ? 0xffedf5ff : 0xfff3f1f5,
                        part.matched ? 0xffd5e7ff : 0xffebe7ef));
            }
            if (part.matched) {
                chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                chip.setContentDescription("查询本地词条 " + part.text);
                chip.setOnClickListener(v -> actions.lookup(part.text));
            }
            tokens.addView(chip, new ViewGroup.LayoutParams(-2, -2));
        }
        paging.removeAllViews();
        paging.setVisibility(page.start > 0 || page.hasNext() ? VISIBLE : GONE);
        if (page.start > 0) addPageButton("上一段", () -> {
            int previous = previousOffsets.isEmpty() ? 0 : previousOffsets.remove(previousOffsets.size() - 1);
            actions.page(previous);
        });
        TextView range = label("", 11, 0xff9b95a0);
        if (page.start > 0 || page.hasNext()) range.setText((page.start + 1) + "–" + page.end + " / " + page.source.length());
        range.setGravity(Gravity.CENTER);
        paging.addView(range, new LinearLayout.LayoutParams(0, dp(36), 1));
        if (page.hasNext()) addPageButton("下一段", () -> {
            previousOffsets.add(page.start); actions.page(page.end);
        });
    }
    private void addPageButton(String text, Runnable action) {
        TextView button = label(text, 12, 0xff7945a1); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0); button.setBackground(background(0xfff3edf8, 0xffe9dfef));
        button.setOnClickListener(v -> action.run());
        paging.addView(button, new LinearLayout.LayoutParams(-2, dp(36)));
    }
    private TextView label(String value, int size, int color) {
        TextView label = new TextView(getContext()); label.setText(value); label.setTextSize(size);
        label.setTextColor(color); label.setIncludeFontPadding(false); return label;
    }
    private RippleDrawable background(int color, int stroke) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(dp(11)); shape.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x12006bff), shape, null);
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
