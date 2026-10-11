package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** File library and document reader; local search itself stays in the drawer. */
public final class DrawerFilesActivity extends FloatingSearchPageActivity {
    private static final int REQUEST_FILE = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = createPage("本地文件", false);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(16), dp(8), dp(16), dp(16));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView add = label("添加文本文件", 15, 0xff008fd1);
        add.setGravity(android.view.Gravity.CENTER);
        add.setBackground(shape(0xffdef7ff));
        add.setOnClickListener(v -> chooseFile());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(44));
        addParams.setMargins(dp(16), 0, dp(16), dp(8));
        root.addView(add, addParams);
        setContentView(root);
        loadFiles();
    }

    private void loadFiles() {
        worker.execute(() -> {
            List<DrawerSearchFileRepository.Document> files = DrawerSearchFileRepository.load(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                content.removeAllViews();
                String selected = getIntent().getStringExtra(FloatingSearchRoutes.EXTRA_FILE_ID);
                DrawerSearchFileRepository.Document document = null;
                for (DrawerSearchFileRepository.Document file : files) {
                    if (file.id.equals(selected)) document = file;
                }
                if (document != null) {
                    TextView title = label(document.name, 17, 0xff171819);
                    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    content.addView(title);
                    TextView body = label(document.content.isEmpty() ? "（空文件）" : document.content,
                            14, 0xff171819);
                    body.setTextIsSelectable(true);
                    body.setLineSpacing(dp(4), 1f);
                    body.setPadding(0, dp(12), 0, dp(12));
                    content.addView(body);
                    return;
                }
                TextView note = label("文件会保存在本机，抽屉搜索可检索文件名和正文。", 13, 0xff737a80);
                note.setPadding(dp(4), 0, dp(4), dp(12));
                content.addView(note);
                if (files.isEmpty()) content.addView(label("尚未添加文本文件", 14, 0xff737a80));
                for (DrawerSearchFileRepository.Document file : files) {
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(14), dp(12), dp(10), dp(12));
                    row.setBackground(shape(0xffffffff));
                    TextView name = label(file.name, 15, 0xff171819);
                    name.setMaxLines(2);
                    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    row.setContentDescription("文本文件：" + file.name);
                    row.setOnClickListener(v -> {
                        getIntent().putExtra(FloatingSearchRoutes.EXTRA_FILE_ID, file.id);
                        loadFiles();
                    });
                    SearchIconView delete = new SearchIconView(this, SearchIconView.Kind.CLOSE);
                    delete.setPadding(dp(11), dp(11), dp(11), dp(11));
                    delete.setContentDescription("删除文件：" + file.name);
                    delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                            .setMessage("删除本机文件“" + file.name + "”？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (dialog, which) -> worker.execute(() -> {
                                boolean removed = DrawerSearchFileRepository.delete(this, file.id);
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    if (!removed) Toast.makeText(this, "删除失败，请重试", Toast.LENGTH_SHORT).show();
                                    loadFiles();
                                });
                            })).show());
                    row.addView(delete, new LinearLayout.LayoutParams(dp(44), dp(44)));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.bottomMargin = dp(6);
                    content.addView(row, params);
                }
            });
        });
    }

    private void chooseFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"text/plain", "text/markdown", "text/csv", "application/json"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try { startActivityForResult(picker, REQUEST_FILE); }
        catch (RuntimeException error) { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_FILE || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        worker.execute(() -> {
            try {
                DrawerSearchFileRepository.importDocument(this, uri);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    getIntent().removeExtra(FloatingSearchRoutes.EXTRA_FILE_ID);
                    loadFiles();
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override public void onBackPressed() {
        if (getIntent().hasExtra(FloatingSearchRoutes.EXTRA_FILE_ID)
                && !getIntent().getStringExtra(FloatingSearchRoutes.EXTRA_FILE_ID).isEmpty()) {
            getIntent().removeExtra(FloatingSearchRoutes.EXTRA_FILE_ID);
            loadFiles();
        } else FloatingSearchRoutes.finishPage(this);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(18));
        return background;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
