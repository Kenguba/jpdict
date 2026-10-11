package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Reference settings layout; Appearance and Customize Grok slots host jpdict features. */
public final class SearchSettingsActivity extends FloatingSearchPageActivity {
    private static final int INK = 0xff171819;
    private static final int MUTED = 0xff73777a;
    private static final int BLUE = 0xff006bff;
    private TextView bubbleSummary;
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
        LinearLayout root = createPage("设置", true);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(12), dp(16), dp(12), dp(20));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        addIdentityCard(content);
        addIntroCard(content);

        addSection(content, "应用");
        LinearLayout app = group();
        bubbleSummary = addRow(app, SearchIconView.Kind.SETTINGS, "悬浮按钮", "",
                () -> FloatingSearchRoutes.openFromActivity(
                        this, FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS, true));
        addRow(app, SearchIconView.Kind.HAPTICS, "触感反馈", null,
                () -> openSystemPage(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        addRow(app, SearchIconView.Kind.WIDGET, "桌面小组件", null,
                () -> showInfo("桌面小组件", "长按桌面空白处，打开小组件列表，选择 jpdict 搜索小组件。点击小组件即可打开悬浮搜索。"));
        addRow(app, SearchIconView.Kind.LANGUAGE, "应用语言", null,
                () -> showInfo("应用语言", "当前界面使用简体中文。词条查询支持中文、英语、日语和韩语。"));
        addRow(app, SearchIconView.Kind.ADVANCED, "高级设置", null, this::showAdvanced);
        content.addView(app, new LinearLayout.LayoutParams(-1, -2));

        addSection(content, "Groq");
        LinearLayout groq = group();
        groqSummary = addRow(groq, SearchIconView.Kind.SETTINGS, "Groq Key", "",
                () -> GroqKeyStore.showEditor(this, this::refreshGroqStatus));
        addRow(groq, SearchIconView.Kind.CONNECTOR, "连接器", null,
                () -> showInfo("连接器", "此入口尚未接入。当前可以在高级设置中添加本地文本文件，并在抽屉中搜索文件内容。"));
        addRow(groq, SearchIconView.Kind.SKILLS, "技能", null,
                () -> showInfo("技能", "此入口尚未接入。当前查询使用词典和 Groq 多语种解释。"));
        content.addView(groq, new LinearLayout.LayoutParams(-1, -2));

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addIdentityCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(ripple(Color.WHITE, 20));
        TextView avatar = text("JP", 25, Color.WHITE, false);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable portrait = shape(0xff526d78, 32);
        portrait.setStroke(dp(2), 0xffdadcdd);
        avatar.setBackground(portrait);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = labels("JP Native", "本机查询", 17, true);
        labels.setPadding(dp(18), 0, 0, 0);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription("本机查询资料");
        card.setOnClickListener(v -> showInfo("本机查询", "jpdict 使用本机设置和查询记录，无需账户登录。私密模式不保存查询历史。"));
        card.setMinimumHeight(dp(108));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addIntroCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(10), dp(16), dp(10));
        card.setBackground(ripple(Color.WHITE, 20));
        card.addView(icon(SearchIconView.Kind.MARK), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = labels("jpdict 多语种查询", "OCR · 词典 · AI", 14, true);
        labels.setPadding(dp(10), 0, dp(8), 0);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = text("关于", 14, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), 0, dp(16), 0);
        action.setBackground(shape(BLUE, 20));
        card.addView(action, new LinearLayout.LayoutParams(-2, dp(34)));
        card.setContentDescription("关于 jpdict");
        card.setOnClickListener(v -> showInfo("jpdict", "版本 " + BuildConfig.VERSION_NAME
                + "\n原生 Android 多语种 OCR 与词条查询。"));
        card.setMinimumHeight(dp(68));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(16);
        content.addView(card, layout);
    }

    private TextView addRow(LinearLayout group, SearchIconView.Kind kind,
            String title, String subtitle, Runnable action) {
        if (group.getChildCount() > 0) {
            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(0xfff1f1f1);
            group.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setBackground(ripple(Color.WHITE, 0));
        row.addView(icon(kind), new LinearLayout.LayoutParams(dp(23), dp(23)));
        LinearLayout labels = labels(title, subtitle, 15, false);
        labels.setPadding(dp(18), 0, dp(8), 0);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) row.setOnClickListener(v -> action.run());
        row.setMinimumHeight(dp(subtitle == null ? 52 : 64));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return subtitle == null ? null : (TextView) labels.getChildAt(1);
    }

    private SearchIconView icon(SearchIconView.Kind kind) {
        SearchIconView icon = new SearchIconView(this, kind);
        icon.setColor(MUTED);
        return icon;
    }

    private LinearLayout labels(String title, String subtitle, float size, boolean bold) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, size, INK, bold));
        if (subtitle != null) {
            TextView detail = text(subtitle, 12, MUTED, false);
            detail.setPadding(0, dp(5), 0, 0);
            labels.addView(detail);
        }
        return labels;
    }

    private void refreshGroqStatus() {
        if (groqSummary != null) groqSummary.setText(GroqKeyStore.hasKey(this) ? "已配置" : "未配置");
    }

    @Override protected void onResume() {
        super.onResume();
        if (bubbleSummary == null) return;
        FloatingBubbleAppearance.Config saved = FloatingBubbleAppearance.load(this);
        bubbleSummary.setText(saved.sizeDp + " dp · " + saved.opacityPercent + "% · "
                + (saved.shape == FloatingBubbleAppearance.SHAPE_CIRCLE ? "圆形" : "圆角方形"));
        refreshGroqStatus();
    }

    private void showAdvanced() {
        boolean autoplay = LookupSpeechSettings.autoplay(this);
        String[] items = {"自动发音：" + (autoplay ? "开启" : "关闭"), "发音引擎",
                "本地文件", "悬浮窗权限", "通知设置", "私密查询说明"};
        new AlertDialog.Builder(this).setTitle("高级设置").setItems(items, (dialog, which) -> {
            switch (which) {
                case 0:
                    LookupSpeechSettings.setAutoplay(this, !autoplay);
                    Toast.makeText(this, autoplay ? "自动发音已关闭" : "自动发音已开启",
                            Toast.LENGTH_SHORT).show();
                    break;
                case 1: openSystemPage(new Intent("com.android.settings.TTS_SETTINGS")); break;
                case 2: FloatingSearchRoutes.openFromActivity(this, FloatingSearchRoutes.PAGE_FILES, true); break;
                case 3: openSystemPage(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))); break;
                case 4:
                    openSystemPage(Build.VERSION.SDK_INT >= 26
                            ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                            : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                    break;
                case 5: showInfo("私密查询", "在搜索页点右上角的私密图标启用。私密模式不读取或保存查询历史；离开搜索会清除草稿。Groq 查询仍会把输入发送给查询服务。"); break;
                default: break;
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void openSystemPage(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException unavailable) {
            showInfo("系统设置", "当前系统未提供此设置入口，请在系统设置中查找。");
        }
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("知道了", null).show();
    }

    private LinearLayout group() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(shape(Color.WHITE, 20));
        group.setClipToOutline(true);
        return group;
    }

    private void addSection(LinearLayout content, String title) {
        TextView heading = text(title, 12, MUTED, false);
        heading.setPadding(dp(18), dp(18), dp(18), dp(12));
        content.addView(heading);
    }

    private android.graphics.drawable.RippleDrawable ripple(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x16000000), shape(color, radius), shape(Color.WHITE, radius));
    }

    @Override public void onBackPressed() {
        FloatingSearchRoutes.finishPage(this);
    }
}
