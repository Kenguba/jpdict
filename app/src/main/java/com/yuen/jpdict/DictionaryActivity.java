package com.yuen.jpdict;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native library page using the existing floating-page route and lifecycle. */
public final class DictionaryActivity extends FloatingSearchPageActivity {
    private static final int FILES = 201, TREE = 202, BACKUP = 203, RESTORE = 204;
    private DictionaryRepository repository;
    private LinearLayout content;
    private TextView status;
    private ScrollView scroll;
    private DictionaryListView libraries;
    private String expandedId, filter = "";
    private final TextView[] tabButtons = new TextView[2];
    private boolean copy = true, picking, fullBackup, downloads;
    private String replaceId;
    private DictionaryOperation operations;
    private int pageGeneration;
    private boolean started, wasStarted;

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); AppTheme.text(t, color);
        t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new AppTheme.Shape(); background.setColor(AppTheme.CARD); background.setCornerRadius(dp(20));
        card.setBackground(background); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12);
        card.setLayoutParams(params); return card;
    }
    private void action(LinearLayout parent, String label, Runnable action) {
        TextView row = text(label, 15, AppTheme.BLUE); row.setMinimumHeight(dp(48));
        row.setOnClickListener(v -> action.run()); parent.addView(row);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = DictionaryRepository.get(this);
        operations = DictionaryOperation.get(this);
        if (state != null) {
            copy = state.getBoolean("copy", true); picking = state.getBoolean("picking");
            fullBackup = state.getBoolean("full"); replaceId = state.getString("replace"); downloads = state.getBoolean("downloads");
            expandedId = state.getString("expanded"); filter = state.getString("filter", "");
        }
        LinearLayout root = createPage("词库管理", false, 44);
        int edge = getResources().getConfiguration().screenWidthDp <= 355 ? 8 : 16;
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(edge), 0, dp(edge), 0);
        for (String tab : new String[]{"已安装", "词库下载"}) {
            TextView button = text(tab, 16, 0xff007aff); button.setGravity(Gravity.CENTER);
            button.setPadding(0, 0, 0, 0); button.setIncludeFontPadding(false);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setBackgroundResource(R.drawable.dictionary_tab);
            tabButtons[tab.equals("已安装") ? 0 : 1] = button;
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(36), 1));
            button.setOnClickListener(v -> { downloads = tab.equals("词库下载"); refresh(); });
        }
        root.addView(tabs);
        View tabLine = new View(this); AppTheme.background(tabLine, 0x66e6e8eb);
        AppTheme.observe(tabLine, () -> tabLine.setVisibility(AppTheme.isDark() ? View.INVISIBLE : View.VISIBLE));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.leftMargin = dp(edge); lineParams.rightMargin = dp(edge);
        root.addView(tabLine, lineParams);
        scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.setPadding(dp(edge), dp(4), dp(edge), 0);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        refresh();
    }
    private void refresh() {
        for (int i = 0; i < tabButtons.length; i++) {
            boolean selected = downloads == (i == 1);
            tabButtons[i].setSelected(selected);
            AppTheme.text(tabButtons[i], selected ? 0xff007aff : 0xff5c687d);
        }
        int generation = ++pageGeneration;
        content.removeAllViews(); status = text("正在读取词库…", 14, AppTheme.MUTED); content.addView(status);
        if (downloads) {
            repository.execute(() -> {
                List<DictionaryCatalog.Package> catalog = DictionaryCatalog.load(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !downloads || pageGeneration != generation) return;
                    status.setText("示例目录 · 当前不提供真实下载");
                    for (DictionaryCatalog.Package p : catalog) {
                        LinearLayout c = card(); c.addView(text(p.name, 17, AppTheme.INK));
                        c.addView(text(p.description + "\n" + p.publisher + " · " + p.version + "\n" + p.mode + " · " + p.license.optString("name"), 13, AppTheme.MUTED));
                        action(c, p.mode == DictionaryCatalog.Mode.PREVIEW ? "演示词库 · 不可下载" : "下载器尚未接入", () -> info("词库下载", "当前仅预览目录元数据，不发出下载请求，也不会生成安装记录。"));
                        content.addView(c);
                    }
                });
            }, this::report);
        } else repository.execute(() -> {}, error -> {
            if (isFinishing() || isDestroyed() || downloads || pageGeneration != generation) return;
            if (error != null) { report(error); return; }
            installed();
        });
    }
    private void installed() {
        int position = scroll.getScrollY();
        content.removeAllViews();
        LinearLayout search = new LinearLayout(this); search.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new AppTheme.Shape(); bg.setColor(0xffe8ebef); bg.setCornerRadius(dp(14)); search.setBackground(bg);
        search.setPadding(dp(12), 0, dp(8), 0);
        SearchIconView magnifier = new SearchIconView(this, SearchIconView.Kind.SEARCH); magnifier.setColor(0xff80858d);
        search.addView(magnifier, new LinearLayout.LayoutParams(dp(21), dp(21)));
        EditText field = new EditText(this); AppTheme.input(field); field.setSingleLine(); field.setTextSize(14); field.setHint("搜索已安装的词库"); field.setText(filter);
        AppTheme.background(field, android.graphics.Color.TRANSPARENT); field.setPadding(dp(9), 0, dp(4), 0); field.setContentDescription("搜索已安装的词库");
        search.addView(field, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2); searchLp.bottomMargin = dp(8); content.addView(search, searchLp);
        LinearLayout actions = new LinearLayout(this);
        shortcut(actions, "导入本地词库", "MDX / MDD 及配套资源", SearchIconView.Kind.IMPORT, () -> chooseImport(null));
        shortcut(actions, "存储与迁移", "存储、备份与恢复", SearchIconView.Kind.DATABASE, this::migration);
        content.addView(actions);
        LinearLayout reorderHint = new LinearLayout(this); reorderHint.setGravity(Gravity.CENTER_VERTICAL);
        reorderHint.setPadding(dp(6), dp(12), dp(6), dp(12));
        SearchIconView reorder = new SearchIconView(this, SearchIconView.Kind.REORDER); reorder.setColor(0xffa4a7ad);
        reorderHint.addView(reorder, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView hint = text("长按卡片或右侧手柄拖动排序", 11, 0xff90959d); hint.setPadding(dp(8), 0, 0, 0);
        reorderHint.addView(hint); content.addView(reorderHint);
        renderLibraries();
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter = s.toString(); renderLibraries(); }
            public void afterTextChanged(Editable s) {}
        });
        scroll.post(() -> scroll.scrollTo(0, position));
    }
    private void shortcut(LinearLayout parent, String title, String subtitle, SearchIconView.Kind kind, Runnable run) {
        LinearLayout tile = card(); tile.setOrientation(LinearLayout.HORIZONTAL); tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(9), dp(12), dp(6), dp(12));
        SearchIconView icon = new SearchIconView(this, kind); icon.setColor(AppTheme.BLUE); tile.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(28)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(7), 0, 0, 0);
        TextView heading = text(title, 13, AppTheme.INK); heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); heading.setPadding(0, 0, 0, dp(4)); labels.addView(heading);
        TextView note = text(subtitle, 9, AppTheme.MUTED); note.setPadding(0, 0, 0, 0); labels.addView(note); tile.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); if (parent.getChildCount() > 0) lp.leftMargin = dp(8); parent.addView(tile, lp); tile.setOnClickListener(v -> run.run());
    }
    private void renderLibraries() {
        if (libraries != null) content.removeView(libraries);
        if (status != null) content.removeView(status);
        libraries = new DictionaryListView(this, scroll, this::saveOrder);
        content.addView(libraries);
        int count = 0;
        for (DictionaryRepository.Dictionary d : repository.list()) {
            if (!d.name.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT))) continue;
            count++;
            DictionaryCardView card = new DictionaryCardView(this, d, new DictionaryCardView.Actions() {
                public void toggleDetails(DictionaryCardView c) {
                    if (libraries.isDragging()) return;
                    AutoTransition transition = new AutoTransition(); transition.setDuration(180); TransitionManager.beginDelayedTransition(libraries, transition);
                    expandedId = c.isOpen() ? null : c.dictionary.id;
                    for (DictionaryCardView row : libraries.cards()) row.setOpen(row.dictionary.id.equals(expandedId));
                }
                public void enable(DictionaryCardView c, boolean value) {
                    repository.execute(() -> repository.enable(d.id, value), error -> { report(error); if (error != null) c.restoreEnabled(!value); });
                }
                public void expandLookup(DictionaryCardView c, boolean value) {
                    repository.execute(() -> repository.setLookupExpanded(d.id, value), error -> { report(error); if (error != null) c.restoreExpanded(!value); });
                }
                public void rename(DictionaryCardView c) { renameDictionary(d); }
                public void files(DictionaryCardView c) { manageFiles(d); }
                public void browse(DictionaryCardView c) { new DictionaryBrowseDialog(DictionaryActivity.this, repository, d).show(); }
                public void uninstall(DictionaryCardView c) { uninstallDictionary(d); }
                public void drag(DictionaryCardView c) { libraries.start(c); }
                public void move(DictionaryCardView c, int direction) { moveDictionary(d, direction); }
            });
            card.setOpen(d.id.equals(expandedId)); libraries.add(card);
        }
        if (count == 0) { status = text(repository.list().isEmpty() ? "尚未安装词库，可导入 MDX、MDD 及配套资源文件。" : "没有匹配的词库", 14, AppTheme.MUTED); content.addView(status); }
    }
    private void saveOrder(List<String> visibleOrder) {
        // Filtering keeps hidden libraries in place; only reorder the visible slots.
        List<String> order = new ArrayList<>(); int index = 0;
        for (DictionaryRepository.Dictionary d : repository.list()) order.add(visibleOrder.contains(d.id) ? visibleOrder.get(index++) : d.id);
        DictionaryListView savingList = libraries; savingList.setSaving(true);
        repository.execute(() -> repository.reorder(order), error -> { savingList.setSaving(false); report(error); if (error != null) refresh(); });
    }
    private void moveDictionary(DictionaryRepository.Dictionary d, int direction) {
        List<String> ids = new ArrayList<>(); for (DictionaryRepository.Dictionary f : repository.list()) ids.add(f.id);
        int index = ids.indexOf(d.id), target = index + direction;
        if (target >= 0 && target < ids.size()) { Collections.swap(ids, index, target); repository.execute(() -> repository.reorder(ids), error -> { report(error); if (error == null) refresh(); }); }
    }
    private void renameDictionary(DictionaryRepository.Dictionary d) {
        EditText name = new EditText(this); AppTheme.input(name); name.setText(d.name); name.setSingleLine(); name.setSelectAllOnFocus(true);
        AlertDialog dialog = new AppTheme.DialogBuilder(this).setTitle("重命名词库").setView(name).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                repository.execute(() -> repository.rename(d.id, name.getText().toString()), error -> { report(error); if (error == null) { dialog.dismiss(); refresh(); } })));
        dialog.show();
    }
    private void manageFiles(DictionaryRepository.Dictionary d) {
        choices("文件管理", new String[]{"查看文件与状态", "更新 / 替换 / 重新关联", "复制到内部存储", "向上移动", "向下移动"}, (dialog, which) -> {
            if (which == 0) {
                StringBuilder files = new StringBuilder(d.status + "\nID: " + d.id + "\n");
                for (DictionaryRepository.Document f : d.files) files.append('\n').append(f.path).append("\n").append(f.uri);
                info(d.name, files.toString());
            } else if (which == 1) chooseImport(d.id);
            else if (which == 2) {
                if (d.storage == DictionaryRepository.StorageMode.APP_PRIVATE) info("存储", "此词典已在 JPDict 内部存储。");
                else runOperation(() -> repository.install(d.files, true, d.id, operations::progress));
            } else moveDictionary(d, which == 3 ? -1 : 1);
        });
    }
    private void uninstallDictionary(DictionaryRepository.Dictionary d) {
        new AppTheme.DialogBuilder(this).setTitle(d.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "删除内部词库副本？" : "解除词库关联？")
                .setMessage(d.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "将删除 JPDict 内部文件，原始外部文件保持不变。" : "仅删除安装记录，不删除原始外部文件。")
                .setPositiveButton("确定", (x,y) -> runOperation(() -> repository.delete(d))).setNegativeButton("取消", null).show();
    }
    private void chooseImport(String id) {
        if (operations.running()) { info("操作进行中", "当前操作正在后台继续，点按进度浮球查看。"); return; }
        replaceId = id;
        choices("词库存储位置", new String[]{"导入到 JPDict（独立内部副本）", "外部直接读取（保留 URI 授权）"}, (d, which) -> {
            copy = which == 0;
            choices("选择词库", new String[]{"选择文件（MDX / MDD / JS / CSS / 字体）", "选择整个词典文件夹（包含子目录）"}, (x, y) -> {
                Intent intent = new Intent(y == 0 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
                if (y == 0) intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                picking = true; startActivityForResult(intent, y == 0 ? FILES : TREE);
            });
        });
    }
    private void migration() {
        choices("存储与迁移", new String[]{"完整备份（包含内部词库文件）", "轻量备份（仅清单与设置）", "恢复备份"}, (dialog, which) -> {
            fullBackup = which == 0;
            Intent intent = new Intent(which == 2 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            if (which != 2) intent.putExtra(Intent.EXTRA_TITLE, "jpdict-dictionaries-" + (fullBackup ? "full" : "light") + ".zip");
            picking = true; startActivityForResult(intent, which == 2 ? RESTORE : BACKUP);
        });
    }
    private void choices(String title, String[] labels, android.content.DialogInterface.OnClickListener select) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 16, AppTheme.INK); heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setIncludeFontPadding(false); heading.setPadding(dp(8), dp(2), dp(8), dp(8)); panel.addView(heading);
        AlertDialog dialog = new AppTheme.DialogBuilder(this).create(); dialog.setView(panel, 0, 0, 0, 0);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView row = text(labels[i], 14, 0xff34373b); row.setIncludeFontPadding(false);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(10), dp(8), dp(10)); row.setMinimumHeight(dp(44));
            android.util.TypedValue feedback = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
            row.setBackgroundResource(feedback.resourceId); panel.addView(row);
            row.setOnClickListener(v -> { dialog.dismiss(); select.onClick(dialog, index); });
        }
        dialog.setCanceledOnTouchOutside(true); dialog.show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); picking = false;
        if (result != RESULT_OK || data == null) return;
        if (request == BACKUP || request == RESTORE) {
            Uri uri = data.getData(); if (uri == null) return;
            runOperation(() -> {
                if (request == BACKUP) DictionaryBackup.write(repository, uri, fullBackup, operations::progress);
                else DictionaryBackup.restore(repository, uri, operations::progress);
            }); return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException error) { if (!copy) { report(new java.io.IOException("文件提供方不允许持久授权，请选择内部导入")); return; } }
        }
        String id = replaceId; boolean copyFiles = copy;
        List<DictionaryRepository.Document> files = new ArrayList<>();
        DictionaryRepository backend = repository;
        if (request == TREE) runOperation(() -> files.addAll(backend.scanTree(uris.get(0))),
                page -> page.chooseTreeBooks(files, copyFiles, id));
        else runOperation(() -> {
            for (Uri uri : uris) files.add(repository.document(uri, ""));
            repository.install(files, copyFiles, id, operations::progress);
        });
    }
    private void chooseTreeBooks(List<DictionaryRepository.Document> files, boolean copyFiles, String id) {
        List<DictionaryRepository.Document> books = new ArrayList<>();
        for (DictionaryRepository.Document f : files) if (f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) books.add(f);
        if (books.isEmpty()) { report(new java.io.IOException("所选文件夹中没有 MDX 词库")); return; }
        if (books.size() == 1) { runOperation(() -> repository.install(files, copyFiles, id, operations::progress)); return; }
        String[] names = new String[books.size()]; boolean[] checked = new boolean[books.size()];
        for (int i = 0; i < names.length; i++) names[i] = books.get(i).path;
        AlertDialog dialog = new AppTheme.DialogBuilder(this).setTitle(id == null ? "选择要安装的词库" : "选择要替换的词库（一本）")
                .setMultiChoiceItems(names, checked, (d, which, value) -> checked[which] = value)
                .setPositiveButton("导入及配套资源", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<DictionaryRepository.Document> selected = new ArrayList<>(); int count = 0;
            for (DictionaryRepository.Document f : files) if (!f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) selected.add(f);
            for (int i = 0; i < checked.length; i++) if (checked[i]) { selected.add(books.get(i)); count++; }
            if (count == 0 || (id != null && count != 1)) {
                Toast.makeText(this, id == null ? "请至少选择一本词库" : "替换时请选择一本词库", Toast.LENGTH_SHORT).show(); return;
            }
            dialog.dismiss(); runOperation(() -> repository.install(selected, copyFiles, id, operations::progress));
        }));
        dialog.show();
    }
    private void runOperation(DictionaryRepository.Work work) {
        runOperation(work, null);
    }
    private void runOperation(DictionaryRepository.Work work, DictionaryOperation.Continuation after) {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("operation_notification_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("operation_notification_requested", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 205);
        }
        java.lang.ref.WeakReference<DictionaryActivity> activity = new java.lang.ref.WeakReference<>(this);
        operations.start(this, repository, work, error -> {
            DictionaryActivity page = activity.get();
            if (page == null || !page.started || page.isDestroyed() || page.isFinishing()) return;
            page.refresh();
        }, after);
    }
    private void report(Exception error) {
        if (error != null && !isDestroyed() && !isFinishing()) info("词库操作失败", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
    private void info(String title, String message) { new AppTheme.DialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("关闭", null).show(); }
    @Override protected void onUserLeaveHint() { if (!picking) super.onUserLeaveHint(); }
    @Override public void onBackPressed() { if (libraries != null && libraries.cancelDrag()) return; FloatingSearchRoutes.finishPage(this); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("copy", copy); state.putBoolean("picking", picking); state.putBoolean("full", fullBackup);
        state.putString("expanded", expandedId); state.putString("filter", filter);
        state.putString("replace", replaceId); state.putBoolean("downloads", downloads); super.onSaveInstanceState(state);
    }
    @Override protected void onStart() {
        super.onStart();
        boolean refreshOnReturn = wasStarted; started = wasStarted = true;
        if (operations != null) {
            operations.attach(this);
            if (getIntent().getBooleanExtra("show_dictionary_progress", false)) {
                getIntent().removeExtra("show_dictionary_progress"); operations.restore();
            }
        }
        if (refreshOnReturn) refresh();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (operations != null && intent.getBooleanExtra("show_dictionary_progress", false)) {
            intent.removeExtra("show_dictionary_progress"); operations.attach(this); operations.restore();
        }
    }
    @Override protected void onStop() { started = false; if (operations != null) operations.background(this); super.onStop(); }
    @Override protected void onDestroy() { if (operations != null) operations.detach(this); super.onDestroy(); }
}
