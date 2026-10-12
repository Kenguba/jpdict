package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.Future;

/** Native library page using the existing floating-page route and lifecycle. */
public final class DictionaryActivity extends FloatingSearchPageActivity {
    private static final int FILES = 201, TREE = 202, BACKUP = 203, RESTORE = 204;
    private DictionaryRepository repository;
    private LinearLayout content;
    private TextView status;
    private boolean copy = true, picking, fullBackup, downloads;
    private String replaceId;
    private Future<?> operation;
    private AlertDialog progressDialog;
    private long lastProgress;
    private int pageGeneration, operationGeneration;

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xffffffff); background.setCornerRadius(dp(20));
        card.setBackground(background); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12);
        card.setLayoutParams(params); return card;
    }
    private void action(LinearLayout parent, String label, Runnable action) {
        TextView row = text(label, 15, 0xff006bff); row.setMinimumHeight(dp(48));
        row.setOnClickListener(v -> action.run()); parent.addView(row);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = DictionaryRepository.get(this);
        if (state != null) {
            copy = state.getBoolean("copy", true); picking = state.getBoolean("picking");
            fullBackup = state.getBoolean("full"); replaceId = state.getString("replace"); downloads = state.getBoolean("downloads");
        }
        LinearLayout root = createPage("词库管理", false);
        LinearLayout tabs = new LinearLayout(this);
        for (String tab : new String[]{"已安装", "词库下载"}) {
            TextView button = text(tab, 16, 0xff006bff); button.setGravity(Gravity.CENTER);
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
            button.setOnClickListener(v -> { downloads = tab.equals("词库下载"); refresh(); });
        }
        root.addView(tabs);
        ScrollView scroll = new ScrollView(this); scroll.setPadding(dp(12), dp(8), dp(12), 0);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        refresh();
    }
    private void refresh() {
        int generation = ++pageGeneration;
        content.removeAllViews(); status = text("正在读取词库…", 14, 0xff73777a); content.addView(status);
        if (downloads) {
            repository.execute(() -> {
                List<DictionaryCatalog.Package> catalog = DictionaryCatalog.load(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !downloads || pageGeneration != generation) return;
                    status.setText("示例目录 · 当前不提供真实下载");
                    for (DictionaryCatalog.Package p : catalog) {
                        LinearLayout c = card(); c.addView(text(p.name, 17, 0xff171819));
                        c.addView(text(p.description + "\n" + p.publisher + " · " + p.version + "\n" + p.mode + " · " + p.license.optString("name"), 13, 0xff73777a));
                        action(c, p.mode == DictionaryCatalog.Mode.PREVIEW ? "演示词库 · 不可下载" : "下载器尚未接入", () -> info("词库下载", "当前仅预览目录元数据，不发出下载请求，也不会生成安装记录。"));
                        content.addView(c);
                    }
                });
            }, this::report);
        } else repository.execute(repository::reload, error -> {
            if (isFinishing() || isDestroyed() || downloads || pageGeneration != generation) return;
            if (error != null) { report(error); return; }
            installed();
        });
    }
    private void installed() {
        content.removeAllViews(); LinearLayout actions = card();
        action(actions, "导入本地词库", () -> chooseImport(null));
        action(actions, "存储与迁移", this::migration); content.addView(actions);
        List<DictionaryRepository.Dictionary> list = repository.list();
        if (list.isEmpty()) content.addView(text("尚未安装 MDX 词典。可同时选择 MDX、配套 MDD、JS、CSS 和字体；有子目录的词库请选择整个文件夹。", 14, 0xff73777a));
        for (DictionaryRepository.Dictionary dictionary : list) {
            LinearLayout c = card(); c.setTag(dictionary.id);
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(dictionary.name, 17, 0xff171819); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Switch enabled = new Switch(this); enabled.setChecked(dictionary.enabled); enabled.setContentDescription("启用 " + dictionary.name);
            row.addView(enabled); c.addView(row);
            enabled.setOnCheckedChangeListener((button, value) -> repository.execute(() -> repository.enable(dictionary.id, value), this::report));
            long mdd = 0;
            for (DictionaryRepository.Document file : dictionary.files) if (file.path.toLowerCase(Locale.ROOT).endsWith(".mdd")) mdd++;
            TextView detail = text((dictionary.source == DictionaryRepository.SourceType.USER_IMPORT ? "用户导入" : dictionary.source == DictionaryRepository.SourceType.BUILTIN ? "内置词典" : "词库中心")
                    + " · " + (dictionary.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "内部存储" : "外部文件")
                    + "\n" + android.text.format.Formatter.formatFileSize(this, dictionary.bytes)
                    + " · 1 MDX / " + mdd + " MDD / " + (dictionary.files.size() - 1 - mdd) + " 资源文件\n" + dictionary.indexState + " · " + ("INSTALLED".equals(dictionary.status) ? "已安装" : "需要重新关联文件"), 12, 0xff73777a);
            c.addView(detail);
            action(c, "详情与管理", () -> details(dictionary));
            TextView handle = text("长按拖动排序", 13, 0xff73777a); c.addView(handle);
            handle.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("dictionary", dictionary.id);
                if (android.os.Build.VERSION.SDK_INT >= 24) c.startDragAndDrop(data, new View.DragShadowBuilder(c), dictionary.id, 0);
                else c.startDrag(data, new View.DragShadowBuilder(c), dictionary.id, 0);
                return true;
            });
            c.setOnDragListener((v, event) -> {
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return event.getLocalState() instanceof String;
                if (event.getAction() == DragEvent.ACTION_DROP && event.getLocalState() instanceof String) {
                    move((String) event.getLocalState(), dictionary.id); return true;
                }
                return true;
            });
            content.addView(c);
        }
    }
    private void move(String id, String before) {
        List<String> order = new ArrayList<>(); for (DictionaryRepository.Dictionary d : repository.list()) order.add(d.id);
        if (id.equals(before) || !order.remove(id)) return;
        order.add(order.indexOf(before), id);
        repository.execute(() -> repository.reorder(order), error -> { report(error); if (error == null) refresh(); });
    }
    private void details(DictionaryRepository.Dictionary d) {
        new AlertDialog.Builder(this).setTitle(d.name).setItems(new String[]{"查看文件和状态", "重命名", "向上移动", "向下移动", "更新 / 替换 / 重新关联", "复制到内部存储", d.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "删除内部副本" : "解除外部关联"}, (dialog, which) -> {
            if (which == 0) {
                StringBuilder files = new StringBuilder(d.status + "\nID: " + d.id + "\n");
                for (DictionaryRepository.Document f : d.files) files.append('\n').append(f.path).append("\n").append(f.uri);
                info(d.name, files.toString());
            } else if (which == 1) {
                EditText name = new EditText(this); name.setText(d.name);
                new AlertDialog.Builder(this).setTitle("重命名词典").setView(name).setPositiveButton("保存", (x, y) ->
                        repository.execute(() -> repository.rename(d.id, name.getText().toString()), error -> { report(error); if (error == null) refresh(); })).setNegativeButton("取消", null).show();
            } else if (which == 2 || which == 3) {
                List<String> ids = new ArrayList<>(); for (DictionaryRepository.Dictionary f : repository.list()) ids.add(f.id);
                int index = ids.indexOf(d.id), target = index + (which == 2 ? -1 : 1);
                if (target >= 0 && target < ids.size()) { Collections.swap(ids, index, target); repository.execute(() -> repository.reorder(ids), error -> { report(error); if (error == null) refresh(); }); }
            } else if (which == 4) chooseImport(d.id);
            else if (which == 5) {
                if (d.storage == DictionaryRepository.StorageMode.APP_PRIVATE) info("存储", "此词典已在 JPDict 内部存储。");
                else runOperation(() -> repository.install(d.files, true, d.id, this::progress));
            } else new AlertDialog.Builder(this).setTitle(d.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "删除内部词典副本？" : "解除词典关联？")
                    .setMessage(d.storage == DictionaryRepository.StorageMode.APP_PRIVATE ? "将删除 JPDict 内部文件，原始外部文件保持不变。" : "仅删除安装记录，不删除原始外部文件。")
                    .setPositiveButton("确定", (x,y) -> runOperation(() -> repository.delete(d))).setNegativeButton("取消", null).show();
        }).setNegativeButton("关闭", null).show();
    }
    private void chooseImport(String id) {
        if (operation != null && !operation.isDone()) { info("操作进行中", "请等待或取消当前操作。"); return; }
        replaceId = id;
        new AlertDialog.Builder(this).setTitle("词库存储位置").setItems(new String[]{"导入到 JPDict（独立内部副本）", "外部直接读取（保留 URI 授权）"}, (d, which) -> {
            copy = which == 0;
            new AlertDialog.Builder(this).setTitle("选择词库").setItems(new String[]{"选择文件（MDX / MDD / JS / CSS / 字体）", "选择整个词典文件夹（包含子目录）"}, (x, y) -> {
                Intent intent = new Intent(y == 0 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
                if (y == 0) intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                picking = true; startActivityForResult(intent, y == 0 ? FILES : TREE);
            }).setNegativeButton("取消", null).show();
        }).setNegativeButton("取消", null).show();
    }
    private void migration() {
        new AlertDialog.Builder(this).setTitle("存储与迁移").setItems(new String[]{"完整备份（包含内部词库文件）", "轻量备份（仅清单与设置）", "恢复备份"}, (dialog, which) -> {
            fullBackup = which == 0;
            Intent intent = new Intent(which == 2 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            if (which != 2) intent.putExtra(Intent.EXTRA_TITLE, "jpdict-dictionaries-" + (fullBackup ? "full" : "light") + ".zip");
            picking = true; startActivityForResult(intent, which == 2 ? RESTORE : BACKUP);
        }).setNegativeButton("取消", null).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); picking = false;
        if (result != RESULT_OK || data == null) return;
        if (request == BACKUP || request == RESTORE) {
            Uri uri = data.getData(); if (uri == null) return;
            runOperation(() -> {
                if (request == BACKUP) DictionaryBackup.write(repository, uri, fullBackup, this::progress);
                else DictionaryBackup.restore(repository, uri, this::progress);
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
        if (request == TREE) runOperation(() -> files.addAll(repository.scanTree(uris.get(0))),
                () -> chooseTreeBooks(files, copyFiles, id));
        else runOperation(() -> {
            for (Uri uri : uris) files.add(repository.document(uri, ""));
            repository.install(files, copyFiles, id, this::progress);
        });
    }
    private void chooseTreeBooks(List<DictionaryRepository.Document> files, boolean copyFiles, String id) {
        List<DictionaryRepository.Document> books = new ArrayList<>();
        for (DictionaryRepository.Document f : files) if (f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) books.add(f);
        if (books.isEmpty()) { report(new java.io.IOException("所选文件夹中没有 MDX 词库")); return; }
        if (books.size() == 1) { runOperation(() -> repository.install(files, copyFiles, id, this::progress)); return; }
        String[] names = new String[books.size()]; boolean[] checked = new boolean[books.size()];
        for (int i = 0; i < names.length; i++) names[i] = books.get(i).path;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(id == null ? "选择要安装的词库" : "选择要替换的词库（一本）")
                .setMultiChoiceItems(names, checked, (d, which, value) -> checked[which] = value)
                .setPositiveButton("导入及配套资源", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<DictionaryRepository.Document> selected = new ArrayList<>(); int count = 0;
            for (DictionaryRepository.Document f : files) if (!f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) selected.add(f);
            for (int i = 0; i < checked.length; i++) if (checked[i]) { selected.add(books.get(i)); count++; }
            if (count == 0 || (id != null && count != 1)) {
                Toast.makeText(this, id == null ? "请至少选择一本词库" : "替换时请选择一本词库", Toast.LENGTH_SHORT).show(); return;
            }
            dialog.dismiss(); runOperation(() -> repository.install(selected, copyFiles, id, this::progress));
        }));
        dialog.show();
    }
    private void runOperation(DictionaryRepository.Work work) {
        runOperation(work, null);
    }
    private void runOperation(DictionaryRepository.Work work, Runnable after) {
        if (operation != null && !operation.isDone()) { info("操作进行中", "请等待或取消当前操作。"); return; }
        int generation = ++operationGeneration;
        progressDialog = new AlertDialog.Builder(this).setTitle("词库操作").setMessage("准备中…").setCancelable(false)
                .setNegativeButton("取消", (dialog, which) -> { if (operation != null) operation.cancel(true); }).show();
        operation = repository.execute(work, error -> {
            if (isDestroyed() || operationGeneration != generation) return;
            if (progressDialog != null) progressDialog.dismiss(); progressDialog = null;
            report(error);
            if (error == null && after == null) Toast.makeText(this, "操作完成", Toast.LENGTH_SHORT).show();
            refresh();
            if (error == null && after != null) after.run();
        });
    }
    private void progress(String message, long done, long total) {
        long now = android.os.SystemClock.uptimeMillis(); if (now - lastProgress < 100) return; lastProgress = now;
        runOnUiThread(() -> {
            if (progressDialog != null && !isDestroyed()) progressDialog.setMessage(message + (total > 0 ? "\n" + done + " / " + total + " bytes" : ""));
        });
    }
    private void report(Exception error) {
        if (error != null && !isDestroyed() && !isFinishing()) info("词库操作失败", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
    private void info(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("关闭", null).show(); }
    @Override protected void onUserLeaveHint() { if (!picking) super.onUserLeaveHint(); }
    @Override public void onBackPressed() { FloatingSearchRoutes.finishPage(this); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("copy", copy); state.putBoolean("picking", picking); state.putBoolean("full", fullBackup);
        state.putString("replace", replaceId); state.putBoolean("downloads", downloads); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (operation != null && !operation.isDone()) operation.cancel(true);
        if (progressDialog != null) progressDialog.dismiss(); super.onDestroy();
    }
}
