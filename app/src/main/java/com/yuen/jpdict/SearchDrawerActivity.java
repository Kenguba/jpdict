package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Independent local search for saved query history and imported text files. */
public final class SearchDrawerActivity extends FloatingSearchPageActivity {
    private static final int REQUEST_TEXT_FILE = 41;
    private static final int INK = 0xff171819;
    private static final int MUTED = 0xff737a80;
    private static final int BLUE = 0xff008fd1;
    private static final int SURFACE = 0xfff4f5f6;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor();

    private EditText input;
    private LinearLayout results;
    private List<DrawerSearchFileRepository.Document> documents = Collections.emptyList();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean documentsLoaded;
    private boolean privateMode;
    private Runnable pendingSearch;
    private Future<?> searchWork;

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
        privateMode = getIntent().getBooleanExtra(FloatingSearchRoutes.EXTRA_PRIVATE_MODE, false);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(16), 0);
        SearchIconView back = new SearchIconView(this, SearchIconView.Kind.BACK);
        back.setColor(INK);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finishPage());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = text("搜索记录和文件", 19, INK, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(15), 0, dp(14), 0);
        searchBox.setBackground(shape(Color.WHITE, 24));
        SearchIconView searchIcon = new SearchIconView(this, SearchIconView.Kind.SEARCH);
        searchIcon.setColor(MUTED);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        input = new EditText(this);
        input.setTextColor(INK);
        input.setHintTextColor(0xff9ba1a5);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(11), 0, dp(8), 0);
        input.setHint("搜索历史或已添加的文本文件");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setContentDescription("搜索记录和文件");
        searchBox.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(52));
        searchParams.setMargins(dp(16), dp(4), dp(16), dp(12));
        root.addView(searchBox, searchParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(16), 0, dp(16), dp(12));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView addFile = text("＋  添加文本文件", 14, BLUE, true);
        addFile.setGravity(Gravity.CENTER);
        addFile.setBackground(shape(0xffdef7ff, 18));
        addFile.setOnClickListener(v -> chooseTextFile());
        LinearLayout.LayoutParams addFileParams = new LinearLayout.LayoutParams(-1, dp(48));
        addFileParams.setMargins(dp(16), dp(4), dp(16), dp(12));
        root.addView(addFile, addFileParams);

        setContentView(root);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleResults(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderResults("");
        loadDocuments();
        input.requestFocus();
        input.post(() -> {
            input.requestFocus();
            InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        overridePendingTransition(0, 0);
    }

    private void loadDocuments() {
        IO.execute(() -> {
            List<DrawerSearchFileRepository.Document> loaded = DrawerSearchFileRepository.load(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                documents = loaded;
                documentsLoaded = true;
                renderResults(input == null ? "" : input.getText().toString());
            });
        });
    }

    private void chooseTextFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"text/plain", "text/markdown", "text/csv", "application/json"});
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_TEXT_FILE);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TEXT_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        IO.execute(() -> {
            try {
                String name = DrawerSearchFileRepository.importDocument(this, uri);
                List<DrawerSearchFileRepository.Document> loaded = DrawerSearchFileRepository.load(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    documents = loaded;
                    documentsLoaded = true;
                    Toast.makeText(this, "已添加：" + name, Toast.LENGTH_SHORT).show();
                    renderResults(input.getText().toString());
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void renderResults(String rawQuery) {
        if (results == null) return;
        int request = ++generation;
        String query = rawQuery == null ? "" : rawQuery.trim();
        results.removeAllViews();
        List<String> history = findHistory(query);
        if (!history.isEmpty()) {
            addSectionTitle("查询记录");
            for (String item : history) addHistoryRow(item);
        }
        if (!documentsLoaded) {
            if (history.isEmpty()) addEmpty("正在读取已添加的文件…");
            return;
        }
        List<DrawerSearchFileRepository.Document> snapshot = new ArrayList<>(documents);
        if (searchWork != null) searchWork.cancel(true);
        searchWork = SEARCH.submit(() -> {
            List<DrawerSearchFileRepository.SearchResult> files =
                    DrawerSearchFileRepository.search(snapshot, query);
            runOnUiThread(() -> {
                if (request != generation || isFinishing() || isDestroyed()) return;
                if (!files.isEmpty()) {
                    addSectionTitle("文件");
                    for (DrawerSearchFileRepository.SearchResult result : files) addFileRow(result);
                }
                if (history.isEmpty() && files.isEmpty()) {
                    addEmpty(query.isEmpty() ? "暂无查询记录或已添加的文件" : "没有匹配的记录或文件");
                }
            });
        });
    }

    private void scheduleResults(String query) {
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        pendingSearch = () -> renderResults(query);
        main.postDelayed(pendingSearch, 90);
    }

    private void addEmpty(String value) {
        TextView empty = text(value, 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(34), dp(20), dp(34));
        results.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private List<String> findHistory(String query) {
        if (privateMode) return Collections.emptyList();
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String saved = getSharedPreferences("lookup_history", MODE_PRIVATE).getString("items", "");
        if (saved != null) {
            for (String item : saved.split("\\n")) {
                String value = item.trim();
                if (matches(value, query)) entries.add(value);
            }
        }
        Set<String> legacy = getSharedPreferences("search_history", MODE_PRIVATE)
                .getStringSet("history", Collections.emptySet());
        if (legacy != null) {
            for (String item : legacy) {
                if (item != null && matches(item.trim(), query)) entries.add(item.trim());
            }
        }
        return new ArrayList<>(entries);
    }

    private boolean matches(String value, String query) {
        return query.isEmpty() || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private void addSectionTitle(String value) {
        TextView title = text(value, 13, MUTED, true);
        title.setPadding(dp(5), dp(8), dp(5), dp(8));
        results.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addHistoryRow(String query) {
        LinearLayout row = row();
        SearchIconView icon = new SearchIconView(this, SearchIconView.Kind.CLOCK);
        icon.setColor(MUTED);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView title = text(query, 15, INK, false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(12), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription("查询记录：" + query);
        row.setOnClickListener(v -> openLookup(query));
        addResultRow(row);
    }

    private void addFileRow(DrawerSearchFileRepository.SearchResult result) {
        DrawerSearchFileRepository.Document document = result.document;
        LinearLayout row = row();
        SearchIconView icon = new SearchIconView(this, SearchIconView.Kind.FILE);
        icon.setColor(BLUE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(document.name, 15, INK, true));
        TextView preview = text(result.preview, 12, MUTED, false);
        preview.setPadding(0, dp(5), 0, 0);
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(preview);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription("文本文件：" + document.name);
        row.setOnClickListener(v -> showDocument(document));
        addResultRow(row);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(ripple(Color.WHITE, 16));
        return row;
    }

    private void addResultRow(View row) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(5);
        results.addView(row, params);
    }

    private void showDocument(DrawerSearchFileRepository.Document document) {
        InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        ScrollView content = new ScrollView(this);
        content.setPadding(dp(20), dp(10), dp(20), dp(10));
        TextView body = text(document.content.isEmpty() ? "（空文件）" : document.content,
                14, INK, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(4), 1f);
        content.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(document.name)
                .setView(content)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openLookup(String query) {
        FloatingSearchRoutes.openLookupFromPage(this, query);
    }

    private void finishPage() {
        FloatingSearchRoutes.finishPage(this);
    }

    private android.graphics.drawable.RippleDrawable ripple(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x16000000), shape(color, radius), shape(Color.WHITE, radius));
    }

    @Override public void onBackPressed() {
        finishPage();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (privateMode && input != null) input.setText("");
    }

    @Override protected void onDestroy() {
        generation++;
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        if (searchWork != null) searchWork.cancel(true);
        super.onDestroy();
    }
}
