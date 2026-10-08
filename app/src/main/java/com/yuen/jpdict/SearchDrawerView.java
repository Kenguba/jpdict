package com.yuen.jpdict;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Drawer content. Its host owns the drawer's position, dimming, and swipe gesture. */
public final class SearchDrawerView extends LinearLayout {
    public interface Callback {
        void onNewChat();
        void onHistorySelected(String query);
        void onSearch();
        void onSettings();
        void onClose();
        void onUnavailable(String feature);
    }

    private static final int INK = 0xff18191b;
    private static final int MUTED = 0xff757a7f;
    private static final int SURFACE = 0xfff2f2f2;
    private final Callback callback;
    private final LinearLayout history;
    private final SearchIconView historyChevron;
    private final TextView backendStatus;
    private boolean expanded = true;
    private boolean privateMode;
    private ValueAnimator collapseAnimator;

    public SearchDrawerView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);
        setBackgroundColor(SURFACE);
        setPadding(dp(16), dp(6), dp(16), dp(12));
        setClickable(true);
        setFocusable(false);
        setContentDescription("查询侧栏");

        LinearLayout account = horizontal();
        account.setPadding(dp(2), dp(12), 0, dp(18));
        TextView avatar = text("JP", 17, 0xffffffff);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setBackground(shape(0xff008d80, 24));
        account.addView(avatar, new LayoutParams(dp(44), dp(44)));
        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(VERTICAL);
        identity.setPadding(dp(13), 0, 0, 0);
        TextView name = text("JP Native", 19, INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(name);
        TextView local = text("本机查询", 11, MUTED);
        local.setPadding(0, dp(3), 0, 0);
        identity.addView(local);
        account.addView(identity, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout close = horizontal();
        close.setGravity(Gravity.CENTER);
        close.setBackground(ripple(0xffffffff, 24));
        close.setContentDescription("收起侧栏");
        SearchIconView closeOne = icon(SearchIconView.Kind.CHEVRON, INK);
        SearchIconView closeTwo = icon(SearchIconView.Kind.CHEVRON, INK);
        closeOne.setRotation(-90); closeTwo.setRotation(-90);
        close.addView(closeOne, new LayoutParams(dp(15), dp(22)));
        LayoutParams second = new LayoutParams(dp(15), dp(22));
        second.leftMargin = -dp(5);
        close.addView(closeTwo, second);
        close.setOnClickListener(v -> callback.onClose());
        account.addView(close, new LayoutParams(dp(44), dp(44)));
        addView(account, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(0, 0, 0, dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout utilities = new LinearLayout(context);
        utilities.setOrientation(VERTICAL);
        utilities.setBackground(shape(0xffffffff, 22));
        utilities.setClipToOutline(true);
        addUtility(utilities, SearchIconView.Kind.TASK, "自动化任务", true);
        addUtility(utilities, SearchIconView.Kind.IMAGE, "照片库", true);
        addUtility(utilities, SearchIconView.Kind.PROJECT, "项目", true);
        addUtility(utilities, SearchIconView.Kind.BOT, "AI 助手", false);
        body.addView(utilities, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout banner = horizontal();
        banner.setPadding(dp(15), dp(13), dp(12), dp(13));
        banner.setBackground(ripple(0xff1748e9, 30));
        banner.setContentDescription("Groq AI，本机查询服务，管理 Key");
        banner.addView(icon(SearchIconView.Kind.LIGHTNING, 0xffffffff),
                new LayoutParams(dp(25), dp(25)));
        LinearLayout bannerText = new LinearLayout(context);
        bannerText.setOrientation(VERTICAL);
        bannerText.setPadding(dp(11), 0, dp(4), 0);
        TextView backendName = text("Groq AI", 17, 0xffffffff);
        backendName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        backendName.setSingleLine();
        backendName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bannerText.addView(backendName);
        backendStatus = text("", 11, 0xffdce5ff);
        backendStatus.setSingleLine();
        backendStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        backendStatus.setPadding(0, dp(2), 0, 0);
        bannerText.addView(backendStatus);
        banner.addView(bannerText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView manage = text("管理", 12, 0xffffffff);
        manage.setGravity(Gravity.CENTER);
        GradientDrawable manageShape = shape(0x142fffff, 18);
        manageShape.setStroke(dp(1), 0x55ffffff);
        manage.setBackground(manageShape);
        banner.addView(manage, new LayoutParams(dp(48), dp(31)));
        banner.setOnClickListener(v -> callback.onSettings());
        LayoutParams bannerParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerParams.topMargin = dp(16);
        body.addView(banner, bannerParams);

        LinearLayout historyHeading = horizontal();
        historyHeading.setPadding(dp(1), dp(18), dp(4), dp(10));
        historyHeading.setMinimumHeight(dp(52));
        historyHeading.setBackground(ripple(SURFACE, 12));
        TextView headingText = text("对话", 13, MUTED);
        headingText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        historyHeading.addView(headingText,
                new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        historyChevron = icon(SearchIconView.Kind.CHEVRON, MUTED);
        historyChevron.setRotation(180);
        historyHeading.addView(historyChevron, new LayoutParams(dp(18), dp(18)));
        historyHeading.setContentDescription("展开或收起查询历史");
        historyHeading.setOnClickListener(v -> toggleHistory());
        body.addView(historyHeading);
        history = new LinearLayout(context);
        history.setOrientation(VERTICAL);
        body.addView(history, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tools = horizontal();
        tools.setPadding(0, dp(10), 0, 0);
        LinearLayout search = horizontal();
        search.setPadding(dp(15), 0, dp(12), 0);
        search.setBackground(ripple(0xffffffff, 25));
        search.addView(icon(SearchIconView.Kind.SEARCH, MUTED),
                new LayoutParams(dp(23), dp(23)));
        TextView searchText = text("搜索", 15, MUTED);
        searchText.setPadding(dp(10), 0, 0, 0);
        search.addView(searchText);
        search.setContentDescription("搜索");
        search.setOnClickListener(v -> callback.onSearch());
        tools.addView(search, new LayoutParams(0, dp(48), 1));
        addTool(tools, SearchIconView.Kind.SETTINGS, "设置", callback::onSettings);
        addTool(tools, SearchIconView.Kind.NEW_CHAT, "新对话", callback::onNewChat);
        addView(tools, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshHistory();
    }

    public void setPrivateMode(boolean privateMode) {
        if (this.privateMode == privateMode) return;
        this.privateMode = privateMode;
        refreshHistory();
    }

    public void refreshHistory() {
        if (collapseAnimator != null) {
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        history.removeAllViews();
        history.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        history.setAlpha(1);
        history.setVisibility(expanded ? VISIBLE : GONE);
        backendStatus.setText(GroqKeyStore.hasKey(getContext())
                ? "本机 Key 已配置" : "配置 Key 即可查询");
        List<String> queries = privateMode ? Collections.emptyList() : readHistory();
        if (queries.isEmpty()) {
            TextView empty = text(privateMode ? "私密模式不显示查询历史" : "暂无查询历史", 13, MUTED);
            empty.setPadding(dp(2), dp(13), dp(2), dp(22));
            history.addView(empty);
            return;
        }
        for (String query : queries) {
            LinearLayout row = horizontal();
            row.setPadding(dp(2), dp(11), dp(4), dp(11));
            row.setMinimumHeight(dp(54));
            row.setBackground(ripple(SURFACE, 12));
            TextView label = text(query, 16, INK);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            SearchIconView clock = icon(SearchIconView.Kind.CLOCK, MUTED);
            LayoutParams iconParams = new LayoutParams(dp(17), dp(17));
            iconParams.leftMargin = dp(12);
            row.addView(clock, iconParams);
            row.setContentDescription("重新查询：" + query);
            row.setOnClickListener(v -> callback.onHistorySelected(query));
            history.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private List<String> readHistory() {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String saved = getContext().getSharedPreferences("lookup_history", Context.MODE_PRIVATE)
                .getString("items", "");
        if (saved != null) {
            for (String line : saved.split("\\n")) {
                if (!line.trim().isEmpty()) queries.add(line.trim());
            }
        }
        // Older search clients used a StringSet. Read it without changing its storage format.
        Set<String> legacy = getContext().getSharedPreferences("search_history", Context.MODE_PRIVATE)
                .getStringSet("history", Collections.emptySet());
        if (legacy != null) {
            List<String> sorted = new ArrayList<>();
            for (String query : legacy) {
                if (query != null && !query.trim().isEmpty()) sorted.add(query.trim());
            }
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            queries.addAll(sorted);
        }
        List<String> result = new ArrayList<>(queries);
        return result.size() > 12 ? new ArrayList<>(result.subList(0, 12)) : result;
    }

    private void toggleHistory() {
        if (collapseAnimator != null) collapseAnimator.cancel();
        expanded = !expanded;
        historyChevron.animate().cancel();
        historyChevron.animate().rotation(expanded ? 180 : 0).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
        int start = history.getVisibility() == GONE ? 0 : history.getHeight();
        history.setVisibility(VISIBLE);
        int width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        history.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int target = expanded ? history.getMeasuredHeight() : 0;
        final boolean opening = expanded;
        ValueAnimator animator = ValueAnimator.ofInt(start, target);
        collapseAnimator = animator;
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            history.getLayoutParams().height = (int) a.getAnimatedValue();
            history.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                history.setVisibility(opening ? VISIBLE : GONE);
                history.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                history.requestLayout();
                collapseAnimator = null;
            }
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (collapseAnimator != null) {
            collapseAnimator.cancel();
            collapseAnimator = null;
        }
        historyChevron.animate().cancel();
        historyChevron.setRotation(expanded ? 180 : 0);
        history.setVisibility(expanded ? VISIBLE : GONE);
        history.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        super.onDetachedFromWindow();
    }

    private void addUtility(LinearLayout group, SearchIconView.Kind kind,
            String label, boolean divider) {
        LinearLayout row = horizontal();
        row.setPadding(dp(15), 0, dp(13), 0);
        row.setBackground(ripple(0xffffffff, 0));
        row.addView(icon(kind, INK), new LayoutParams(dp(25), dp(25)));
        TextView title = text(label, 17, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(13), 0, dp(4), 0);
        row.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView status = text("未接入", 10, MUTED);
        row.addView(status);
        row.setContentDescription(label + "，尚未接入");
        row.setOnClickListener(v -> callback.onUnavailable(label));
        group.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(55)));
        if (divider) {
            View line = new View(getContext());
            line.setBackgroundColor(SURFACE);
            group.addView(line, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        }
    }

    private void addTool(LinearLayout tools, SearchIconView.Kind kind,
            String description, Runnable action) {
        LinearLayout button = horizontal();
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(0xffffffff, 24));
        button.addView(icon(kind, INK), new LayoutParams(dp(24), dp(24)));
        button.setContentDescription(description);
        button.setOnClickListener(v -> action.run());
        LayoutParams params = new LayoutParams(dp(48), dp(48));
        params.leftMargin = dp(8);
        tools.addView(button, params);
    }

    private SearchIconView icon(SearchIconView.Kind kind, int color) {
        SearchIconView icon = new SearchIconView(getContext(), kind);
        icon.setColor(color);
        return icon;
    }
    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private TextView text(String value, float size, int color) {
        TextView label = new TextView(getContext());
        label.setText(value); label.setTextSize(size); label.setTextColor(color);
        label.setIncludeFontPadding(false);
        return label;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(radius));
        return background;
    }
    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x18000000),
                shape(color, radius), shape(0xffffffff, radius));
    }
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
