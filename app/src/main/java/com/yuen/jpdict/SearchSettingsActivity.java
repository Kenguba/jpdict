package com.yuen.jpdict;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-page settings hub for the floating search experience. */
public final class SearchSettingsActivity extends FloatingSearchPageActivity {
    private static final int INK = 0xff171819;
    private static final int MUTED = 0xff6d747b;
    private static final int BLUE = 0xff008fd1;
    private static final int SURFACE = 0xfff4f5f6;
    private TextView groqSummary;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(18), 0);
        SearchIconView back = new SearchIconView(this, SearchIconView.Kind.BACK);
        back.setColor(INK);
        back.setBackground(ripple(Color.WHITE, 22));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> FloatingSearchRoutes.finishPage(this));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = text("设置", 19, INK, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(16), dp(4), dp(16), dp(18));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView intro = text("管理查询服务与悬浮按钮外观", 13, MUTED, false);
        intro.setPadding(dp(6), dp(8), dp(6), dp(14));
        content.addView(intro);

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(shape(Color.WHITE, 22));
        group.setClipToOutline(true);
        addSetting(group, SearchIconView.Kind.LIGHTNING, "Groq Key",
                GroqKeyStore.hasKey(this) ? "已配置 · 搜索、OCR 和查词共用" : "配置本机加密保存的查询 Key",
                () -> GroqKeyStore.showEditor(this, this::refreshGroqStatus));
        addDivider(group);
        addSetting(group, SearchIconView.Kind.SETTINGS, "悬浮按钮",
                "调整大小、图标样式、形状和透明度",
                () -> FloatingSearchRoutes.openFromActivity(
                        this, FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS, true));
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.VERTICAL);
        note.setPadding(dp(18), dp(16), dp(18), dp(16));
        note.setBackground(shape(0xffe8f8ff, 18));
        TextView noteTitle = text("发音", 15, INK, true);
        note.addView(noteTitle);
        TextView noteBody = text("查词结果进入时自动播放日语发音，也可点扬声器重播。", 13, MUTED, false);
        noteBody.setPadding(0, dp(7), 0, 0);
        noteBody.setLineSpacing(dp(3), 1f);
        note.addView(noteBody);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(16);
        content.addView(note, noteParams);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        overridePendingTransition(0, 0);
    }

    private void addSetting(LinearLayout group, SearchIconView.Kind iconKind,
            String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setBackground(ripple(Color.WHITE, 18));
        SearchIconView icon = new SearchIconView(this, iconKind);
        icon.setColor(BLUE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(13), 0, dp(8), 0);
        labels.addView(text(title, 16, INK, true));
        TextView detail = text(subtitle, 12, MUTED, false);
        if ("Groq Key".equals(title)) groqSummary = detail;
        detail.setPadding(0, dp(5), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        SearchIconView arrow = new SearchIconView(this, SearchIconView.Kind.CHEVRON);
        arrow.setColor(MUTED);
        arrow.setRotation(-90);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setContentDescription(title + "，" + subtitle);
        row.setOnClickListener(v -> action.run());
        group.addView(row, new LinearLayout.LayoutParams(-1, dp(74)));
    }

    private void refreshGroqStatus() {
        if (groqSummary == null) return;
        groqSummary.setText(GroqKeyStore.hasKey(this)
                ? "已配置 · 搜索、OCR 和查词共用" : "配置本机加密保存的查询 Key");
    }

    private void addDivider(LinearLayout group) {
        View line = new View(this);
        line.setBackgroundColor(SURFACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(56);
        group.addView(line, params);
    }

    private android.graphics.drawable.RippleDrawable ripple(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x16000000), shape(color, radius), shape(Color.WHITE, radius));
    }

    @Override public void onBackPressed() {
        FloatingSearchRoutes.finishPage(this);
    }

}
