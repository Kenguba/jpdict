package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.Locale;

/** One library's presentation. The page owns expansion; Repository owns settings. */
final class DictionaryCardView extends LinearLayout {
    interface Actions {
        void toggleDetails(DictionaryCardView card);
        void enable(DictionaryCardView card, boolean enabled);
        void expandLookup(DictionaryCardView card, boolean expanded);
        void rename(DictionaryCardView card);
        void files(DictionaryCardView card);
        void browse(DictionaryCardView card);
        void uninstall(DictionaryCardView card);
        void drag(DictionaryCardView card);
        void move(DictionaryCardView card, int direction);
    }
    final DictionaryRepository.Dictionary dictionary;
    private final LinearLayout details;
    private final LinearLayout header;
    private final Switch enabled;
    private Switch expanded;
    private final String fileCount;
    private boolean lookupChecked;
    private final Actions actions;
    private boolean open;

    DictionaryCardView(Context context, DictionaryRepository.Dictionary dictionary, Actions actions) {
        super(context); this.dictionary = dictionary; this.actions = actions; lookupChecked = dictionary.lookupExpanded;
        setOrientation(VERTICAL); setPadding(dp(10), dp(10), dp(10), dp(10));
        setBackground(background(false)); setTag(dictionary.id);
        header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        DictionaryBookIcon book = new DictionaryBookIcon(context, dictionary.name);
        header.addView(book, new LayoutParams(dp(36), dp(44)));
        LinearLayout summary = new LinearLayout(context); summary.setOrientation(VERTICAL);
        summary.setPadding(dp(10), 0, dp(4), 0);
        TextView title = label(dictionary.name, 15, 0xff171819);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setMaxLines(2);
        summary.addView(title);
        long mdd = 0;
        for (DictionaryRepository.Document file : dictionary.files) if (file.path.toLowerCase(Locale.ROOT).endsWith(".mdd")) mdd++;
        String count = "1 MDX / " + mdd + " MDD / " + (dictionary.files.size() - 1 - mdd) + " 资源文件";
        fileCount = count;
        TextView files = label(android.text.format.Formatter.formatFileSize(context, dictionary.bytes) + " · " + count, 10, 0xff72777f);
        files.setPadding(0, dp(4), 0, dp(3)); summary.addView(files);
        TextView state = label(dictionary.indexState + " · " + ("INSTALLED".equals(dictionary.status) ? "已安装" : "需要重新关联"), 11, 0xff72777f);
        summary.addView(state); header.addView(summary, new LayoutParams(0, -2, 1));
        enabled = toggle("启用 " + dictionary.name, dictionary.enabled);
        header.addView(enabled, new LayoutParams(dp(42), dp(44)));
        enabled.setOnCheckedChangeListener((b, checked) -> actions.enable(this, checked));
        View separator = new View(context); separator.setBackgroundColor(0xffe7e9ed);
        LayoutParams sep = new LayoutParams(dp(1), dp(26)); sep.setMargins(dp(6), 0, 0, 0); header.addView(separator, sep);
        SearchIconView handle = new SearchIconView(context, SearchIconView.Kind.DRAG);
        handle.setColor(0xffa4a7ad); handle.setPadding(dp(4), dp(9), dp(4), dp(9));
        handle.setContentDescription("长按拖动排序 " + dictionary.name); handle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        header.addView(handle, new LayoutParams(dp(24), dp(44)));
        handle.setOnLongClickListener(v -> { actions.drag(this); return true; });
        header.setOnClickListener(v -> actions.toggleDetails(this));
        header.setOnLongClickListener(v -> { actions.drag(this); return true; });
        header.setContentDescription(dictionary.name + "，详情与管理，已收起");
        header.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01020001, "向上移动"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01020002, "向下移动"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == 0x01020001 || action == 0x01020002) { actions.move(DictionaryCardView.this, action == 0x01020001 ? -1 : 1); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        addView(header);
        details = new LinearLayout(context); details.setOrientation(VERTICAL); details.setVisibility(GONE);
        details.setPadding(0, dp(12), 0, 0); addView(details, new LayoutParams(-1, -2));
    }
    private void buildDetails() {
        Context context = getContext();
        LinearLayout metadata = new LinearLayout(context); metadata.setOrientation(VERTICAL);
        metadata.setPadding(dp(10), dp(4), dp(10), dp(4)); metadata.setBackground(rounded(0xfff9fbfe, 14)); details.addView(metadata);
        metadata(metadata, "导入方式", (dictionary.source == DictionaryRepository.SourceType.USER_IMPORT ? "用户导入" : dictionary.source == DictionaryRepository.SourceType.BUILTIN ? "内置词典" : "词库中心")
                + " · " + (dictionary.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "内部存储" : "外部文件"));
        String location = dictionary.mdx().uri.toString(); int slash = location.lastIndexOf('/');
        metadata(metadata, "存储位置", slash > 0 ? location.substring(0, slash + 1) : location);
        metadata(metadata, "索引状态", dictionary.indexState + "（" + fileCount + "）");
        metadata(metadata, "安装状态", "INSTALLED".equals(dictionary.status) ? "已安装" : "需要重新关联");
        LinearLayout buttons = new LinearLayout(context); buttons.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams buttonsLp = new LayoutParams(-1, -2); buttonsLp.topMargin = dp(10); details.addView(buttons, buttonsLp);
        command(buttons, "浏览词库", SearchIconView.Kind.BOOK, false, () -> actions.browse(this));
        command(buttons, "重命名", SearchIconView.Kind.NEW_CHAT, false, () -> actions.rename(this));
        command(buttons, "文件管理", SearchIconView.Kind.FOLDER, false, () -> actions.files(this));
        LinearLayout expand = tile(buttons, false);
        expanded = toggle("在查词窗口默认展开 " + dictionary.name, lookupChecked);
        expand.addView(expanded, new LayoutParams(dp(40), dp(30)));
        TextView expandLabel = label("查词展开", 10, 0xff34455d); expandLabel.setGravity(Gravity.CENTER); expand.addView(expandLabel);
        expanded.setOnCheckedChangeListener((b, checked) -> { lookupChecked = checked; actions.expandLookup(this, checked); });
        expand.setOnClickListener(v -> expanded.setChecked(!expanded.isChecked()));
        command(buttons, "卸载", SearchIconView.Kind.TRASH, true, () -> actions.uninstall(this));
    }
    void setOpen(boolean value) {
        if (open == value) return;
        open = value;
        if (value) buildDetails(); else { details.removeAllViews(); expanded = null; }
        details.setVisibility(value ? VISIBLE : GONE); setBackground(background(value));
        header.setContentDescription(dictionary.name + "，详情与管理，" + (value ? "已展开" : "已收起"));
    }
    boolean isOpen() { return open; }
    void restoreEnabled(boolean checked) { enabled.setOnCheckedChangeListener(null); enabled.setChecked(checked); enabled.setOnCheckedChangeListener((b, v) -> actions.enable(this, v)); }
    void restoreExpanded(boolean checked) { lookupChecked = checked; if (expanded == null) return; expanded.setOnCheckedChangeListener(null); expanded.setChecked(checked); expanded.setOnCheckedChangeListener((b, v) -> { lookupChecked = v; actions.expandLookup(this, v); }); }
    private void metadata(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.TOP); row.setPadding(0, dp(9), 0, dp(9));
        row.addView(label(label, 11, 0xff72777f), new LayoutParams(dp(66), -2));
        row.addView(label(value, 11, 0xff57616f), new LayoutParams(0, -2, 1)); parent.addView(row);
        if (parent.getChildCount() < 7) { View line = new View(getContext()); line.setBackgroundColor(0xffe9edf2); parent.addView(line, new LayoutParams(-1, dp(1))); }
    }
    private LinearLayout tile(LinearLayout parent, boolean destructive) {
        LinearLayout tile = new LinearLayout(getContext()); tile.setOrientation(VERTICAL); tile.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(destructive ? 0xfffff5f5 : 0xffffffff, 10); bg.setStroke(dp(1), destructive ? 0xffffd2d2 : 0xffdbe8fa); tile.setBackground(bg);
        LayoutParams lp = new LayoutParams(0, dp(60), 1); if (parent.getChildCount() > 0) lp.leftMargin = dp(4); parent.addView(tile, lp); return tile;
    }
    private void command(LinearLayout parent, String label, SearchIconView.Kind kind, boolean destructive, Runnable command) {
        LinearLayout tile = tile(parent, destructive);
        SearchIconView icon = new SearchIconView(getContext(), kind); icon.setColor(destructive ? 0xffd93d3d : 0xff006bff);
        tile.addView(icon, new LayoutParams(dp(23), dp(28))); TextView text = label(label, 10, destructive ? 0xffd93d3d : 0xff34455d); text.setGravity(Gravity.CENTER); tile.addView(text);
        tile.setContentDescription(label + " " + dictionary.name); tile.setOnClickListener(v -> command.run());
    }
    private Switch toggle(String label, boolean checked) {
        Switch button = new Switch(getContext()); button.setShowText(false); button.setSwitchMinWidth(dp(36));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = rounded(0xff006bff, 12); on.setSize(dp(36), dp(22));
        GradientDrawable off = rounded(0xffd5d8df, 12); off.setSize(dp(36), dp(22));
        track.addState(new int[]{android.R.attr.state_checked}, on); track.addState(new int[]{}, off); button.setTrackDrawable(track);
        button.setThumbDrawable(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { paint.setColor(0xffffffff); }
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds();
                float radius = Math.max(0, Math.min(bounds.width(), bounds.height()) / 2f - dp(3));
                android.graphics.Rect rail = track.getBounds();
                float center = Math.max(rail.left + radius + dp(3), Math.min(rail.right - radius - dp(3), bounds.exactCenterX()));
                canvas.drawCircle(center, bounds.exactCenterY(), radius, paint);
            }
            public int getIntrinsicWidth() { return dp(22); }
            public int getIntrinsicHeight() { return dp(22); }
            public boolean getPadding(android.graphics.Rect padding) { padding.set(dp(3), dp(3), dp(3), dp(3)); return true; }
            public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }); button.setSplitTrack(false);
        button.setChecked(checked); button.setContentDescription(label); return button;
    }
    private TextView label(String value, int size, int color) { TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); return t; }
    private GradientDrawable background(boolean open) { GradientDrawable bg = rounded(open ? 0xfff2f7ff : 0xffffffff, 16); if (open) bg.setStroke(dp(1), 0xffb8d8ff); return bg; }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
