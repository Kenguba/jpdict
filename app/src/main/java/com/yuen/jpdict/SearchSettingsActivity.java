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
    private static final int INK = AppTheme.INK;
    private static final int MUTED = AppTheme.MUTED;
    private static final int BLUE = AppTheme.BLUE;
    private TextView bubbleSummary;
    private TextView groqSummary;
    private TextView drawerSummary;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new AppTheme.Shape();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        AppTheme.text(view, color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = createPage("设置", true);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        addIdentityCard(content);
        addDictionaryCard(content);

        addSection(content, "外观");
        LinearLayout app = group();
        addThemeControl(app);
        bubbleSummary = addRow(app, SearchIconView.Kind.SETTINGS, "悬浮按钮", "",
                () -> FloatingSearchRoutes.openFromActivity(
                        this, FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS, true));
        drawerSummary = addRow(app, SearchIconView.Kind.SIDEBAR, "侧边栏展开方式", "",
                () -> FloatingSearchRoutes.openFromActivity(this, FloatingSearchRoutes.PAGE_DRAWER_STYLE, true));
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

    private void addThemeControl(LinearLayout parent) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        LinearLayout segments = new LinearLayout(this);
        segments.setPadding(dp(3), dp(3), dp(3), dp(3));
        segments.setBackground(shape(AppTheme.SOFT, 22));
        LinearLayout.LayoutParams segmentParams = new LinearLayout.LayoutParams(-1, dp(44));
        panel.addView(segments, segmentParams);
        LinearLayout[] options = new LinearLayout[3];
        TextView[] labels = new TextView[3];
        SearchIconView[] icons = new SearchIconView[3];
        LinearLayout times = new LinearLayout(this); times.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-1, dp(56));
        timeParams.topMargin = dp(8); panel.addView(times, timeParams);
        TextView start = text("", 14, INK, true), end = text("", 14, INK, true);
        Runnable update = () -> {
            for (int i = 0; i < 3; i++) if (options[i] != null) {
                boolean selected = i == AppTheme.mode(); options[i].setSelected(selected);
                AppTheme.text(labels[i], selected ? BLUE : MUTED);
                icons[i].setColor(selected ? BLUE : MUTED);
                options[i].setBackground(shape(selected ? AppTheme.CARD : Color.TRANSPARENT, 19));
                if (Build.VERSION.SDK_INT >= 30) options[i].setStateDescription(selected ? "已选中" : "未选中");
            }
            times.setVisibility(AppTheme.mode() == 2 ? android.view.View.VISIBLE : android.view.View.GONE);
            start.setText(themeTime(AppTheme.start())); end.setText(themeTime(AppTheme.end()));
        };
        String[] names = {"浅色", "深色", "自动"};
        SearchIconView.Kind[] symbols = {SearchIconView.Kind.SUN, SearchIconView.Kind.MOON, SearchIconView.Kind.CLOCK};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            LinearLayout option = new LinearLayout(this); options[i] = option;
            option.setGravity(Gravity.CENTER); option.setContentDescription(names[i]); option.setFocusable(true);
            SearchIconView icon = new SearchIconView(this, symbols[i]); icons[i] = icon;
            option.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));
            TextView label = text(names[i], 14, MUTED, true); labels[i] = label;
            label.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
            labelParams.leftMargin = dp(6); option.addView(label, labelParams);
            option.setOnClickListener(v -> { AppTheme.mode(mode); update.run(); });
            segments.addView(option, new LinearLayout.LayoutParams(0, -1, 1));
        }
        for (int i = 0; i < 2; i++) {
            boolean opening = i == 0;
            TextView label = text(opening ? "开启" : "关闭", 12, MUTED, false);
            if (!opening) label.setPadding(dp(10), 0, 0, 0);
            times.addView(label);
            TextView value = opening ? start : end; value.setGravity(Gravity.CENTER);
            value.setBackground(shape(AppTheme.SOFT, 12));
            value.setContentDescription(opening ? "深色模式开启时间" : "深色模式关闭时间");
            LinearLayout.LayoutParams time = new LinearLayout.LayoutParams(0, dp(44), 1);
            time.leftMargin = dp(8); times.addView(value, time);
            value.setOnClickListener(v -> {
                int minute = opening ? AppTheme.start() : AppTheme.end();
                android.widget.FrameLayout container = new android.widget.FrameLayout(this);
                android.widget.TimePicker[] picker = {null};
                AppTheme.observe(container, () -> {
                    android.util.SparseArray<android.os.Parcelable> saved = new android.util.SparseArray<>();
                    if (picker[0] != null) picker[0].saveHierarchyState(saved);
                    picker[0] = new android.widget.TimePicker(AppTheme.dialogContext(this));
                    picker[0].setId(android.R.id.content); picker[0].setIs24HourView(true);
                    picker[0].setHour(minute / 60); picker[0].setMinute(minute % 60);
                    picker[0].restoreHierarchyState(saved);
                    container.removeAllViews(); container.addView(picker[0]);
                });
                new AppTheme.DialogBuilder(this).setView(container).setNegativeButton("取消", null)
                        .setPositiveButton("确定", (dialog, which) -> {
                    picker[0].clearFocus(); int picked = picker[0].getHour() * 60 + picker[0].getMinute();
                    if (!AppTheme.times(opening ? picked : AppTheme.start(), opening ? AppTheme.end() : picked))
                        Toast.makeText(this, "开启和关闭时间不能相同", Toast.LENGTH_SHORT).show();
                    update.run();
                }).show();
            });
        }
        AppTheme.observe(panel, update);
        parent.addView(panel, new LinearLayout.LayoutParams(-1, -2));
    }

    private String themeTime(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private void addIdentityCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(ripple(Color.WHITE, 20));
        TextView avatar = text("JP", 25, Color.WHITE, false);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable portrait = shape(BLUE, 32);
        portrait.setStroke(dp(2), AppTheme.LINE);
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

    private void addDictionaryCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(10), dp(16), dp(10));
        card.setBackground(ripple(Color.WHITE, 20));
        card.addView(icon(SearchIconView.Kind.FILE), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = labels("词库管理", "安装、下载、排序、备份词库", 14, true);
        labels.setPadding(dp(10), 0, dp(8), 0);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = text("关于", 14, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), 0, dp(16), 0);
        action.setBackground(shape(BLUE, 20));
        card.addView(action, new LinearLayout.LayoutParams(-2, dp(34)));
        action.setOnClickListener(v -> showInfo("jpdict", "版本 " + BuildConfig.VERSION_NAME
                + "\n原生 Android 多语种 OCR 与词条查询。"));
        card.setContentDescription("词库管理：安装、下载、排序、备份词库");
        card.setOnClickListener(v -> FloatingSearchRoutes.openFromActivity(this, FloatingSearchRoutes.PAGE_DICTIONARIES, true));
        card.setMinimumHeight(dp(68));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(16);
        content.addView(card, layout);
    }

    private TextView addRow(LinearLayout group, SearchIconView.Kind kind,
            String title, String subtitle, Runnable action) {
        if (group.getChildCount() > 0) {
            android.view.View divider = new android.view.View(this);
            AppTheme.background(divider, AppTheme.LINE);
            group.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setBackground(ripple(Color.WHITE, 0));
        row.addView(icon(kind), new LinearLayout.LayoutParams(dp(23), dp(23)));
        LinearLayout labels = labels(title, subtitle, 15, true);
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
                + (saved.roundnessPercent == 100 ? "圆形"
                        : saved.roundnessPercent == 0 ? "方形" : "圆角 " + saved.roundnessPercent + "%"));
        refreshGroqStatus();
        DrawerStyleSettings drawerStyle = new DrawerStyleSettings(this);
        drawerSummary.setText(drawerStyle.mode().title + " · " + drawerStyle.ratio(drawerStyle.mode()) + "%");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FloatingSearchRoutes.REQUEST_DRAWER_STYLE && result == RESULT_OK) {
            FloatingSearchRoutes.experienceDrawer(this);
        }
    }

    private void showAdvanced() {
        boolean autoplay = LookupSpeechSettings.autoplay(this);
        String[] items = {"自动发音：" + (autoplay ? "开启" : "关闭"), "发音引擎",
                "本地文件", "悬浮窗权限", "通知设置", "私密查询说明"};
        new AppTheme.DialogBuilder(this).setTitle("高级设置").setItems(items, (dialog, which) -> {
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
        new AppTheme.DialogBuilder(this).setTitle(title).setMessage(message)
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
        TextView heading = text(title, 12, MUTED, true);
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
