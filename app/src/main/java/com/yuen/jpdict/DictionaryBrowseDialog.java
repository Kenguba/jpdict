package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** A bounded headword browser; rendering and navigation reuse the existing entry view. */
final class DictionaryBrowseDialog {
    private final Context context;
    private final DictionaryRepository repository;
    private final DictionaryRepository.Dictionary dictionary;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout results;
    private final EditText query;
    private final Button previous, next;
    private final TextView position;
    private final AlertDialog dialog;
    private DictionaryScheduler.Ticket ticket;
    private DictionaryEntryView entry;
    private final List<Long> pages = new ArrayList<>();
    private long offset;
    private int generation;
    private boolean closed;

    DictionaryBrowseDialog(Context context, DictionaryRepository repository, DictionaryRepository.Dictionary dictionary) {
        this.context = context; this.repository = repository; this.dictionary = dictionary;
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        query = new EditText(context); AppTheme.input(query); query.setSingleLine(); query.setHint("输入词条查询本词库"); query.setTextSize(14); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); root.addView(query);
        ScrollView scroll = new ScrollView(context); scroll.setVerticalScrollBarEnabled(false);
        results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL); scroll.addView(results); root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(300)));
        LinearLayout navigation = new LinearLayout(context); root.addView(navigation);
        previous = new Button(context); AppTheme.button(previous); previous.setText("上一页"); next = new Button(context); AppTheme.button(next); next.setText("下一页");
        position = new TextView(context); AppTheme.text(position, AppTheme.INK); position.setGravity(android.view.Gravity.CENTER); position.setTextSize(11);
        navigation.addView(previous, new LinearLayout.LayoutParams(-2, dp(44))); navigation.addView(position, new LinearLayout.LayoutParams(0, dp(44), 1)); navigation.addView(next, new LinearLayout.LayoutParams(-2, dp(44)));
        dialog = new AppTheme.DialogBuilder(context).setTitle(dictionary.name).setView(root).create();
        previous.setOnClickListener(v -> { if (!pages.isEmpty()) { offset = pages.remove(pages.size() - 1); loadPage(); } });
        next.setOnClickListener(v -> { pages.add(offset); offset = (Long)next.getTag(); loadPage(); });
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { lookup(query.getText().toString()); return true; } return false; });
        dialog.setOnDismissListener(d -> { closed = true; generation++; if (ticket != null) ticket.cancel(); clearEntry(); });
    }
    void show() {
        dialog.show();
        loadPage();
    }
    private void reset() {
        if (ticket != null) ticket.cancel(); clearEntry(); results.removeAllViews();
        previous.setEnabled(false); next.setEnabled(false);
        position.setOnClickListener(null); position.setClickable(false); position.setFocusable(false); AppTheme.text(position, AppTheme.MUTED);
        TextView loading = new TextView(context); AppTheme.text(loading, AppTheme.INK); loading.setText("正在读取词库…"); results.addView(loading);
    }
    private void loadPage() {
        reset(); position.setText("正在读取…"); int request = ++generation;
        ticket = repository.queries.submit(1, () -> {
            try {
                DictionaryRepository.WordPage page = repository.words(dictionary, offset);
                main.post(() -> {
                    if (closed || request != generation) return;
                    results.removeAllViews(); position.setText((offset + 1) + "–" + (offset + page.words.size()) + " / " + page.total);
                    previous.setEnabled(!pages.isEmpty()); next.setEnabled(!page.words.isEmpty() && offset + page.words.size() < page.total); next.setTag(offset + page.words.size());
                    for (String word : page.words) {
                        TextView row = new TextView(context); AppTheme.text(row, AppTheme.INK); row.setText(word.isEmpty() ? "（空词头）" : word); row.setTextSize(15); row.setPadding(dp(6), dp(12), dp(6), dp(12)); results.addView(row);
                        row.setOnClickListener(v -> { query.setText(word); lookup(word); });
                    }
                });
            } catch (Exception error) { fail(request, error); }
        });
        if (ticket == null) fail(request, new java.io.IOException("查询队列已满，请重试"));
    }
    private void lookup(String word) {
        ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        reset(); int request = ++generation;
        position.setText("返回列表");
        AppTheme.text(position, AppTheme.BLUE); position.setFocusable(true); position.setOnClickListener(v -> loadPage());
        ticket = repository.queries.submit(0, () -> {
            try {
                DictionaryHtmlDocument html = repository.lookupDocument(dictionary, word);
                main.post(() -> {
                    if (closed || request != generation) { html.close(); return; }
                    results.removeAllViews();
                    if (html.entries == 0) { TextView empty = new TextView(context); AppTheme.text(empty, AppTheme.INK); empty.setText("该词库未收录此词条"); results.addView(empty); }
                    else { entry = new DictionaryEntryView(context, repository, dictionary, html, this::lookup, null); results.addView(entry, new LinearLayout.LayoutParams(-1, 1)); }
                    html.close();
                });
            } catch (Exception error) { fail(request, error); }
        });
        if (ticket == null) fail(request, new java.io.IOException("查询队列已满，请重试"));
    }
    private void fail(int request, Exception error) { main.post(() -> { if (closed || request != generation) return; results.removeAllViews(); TextView message = new TextView(context); AppTheme.text(message, AppTheme.INK); message.setText("词库读取失败：" + error.getMessage()); results.addView(message); }); }
    private void clearEntry() { if (entry != null) { entry.close(); entry = null; } }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
