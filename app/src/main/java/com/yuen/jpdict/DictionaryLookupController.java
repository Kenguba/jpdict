package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Native sections shared by overlay and Activity fallback. Tasks follow session
 * ownership; completion may never reorder sections or update a closed session. */
final class DictionaryLookupController {
    private final Context context;
    private final DictionaryRepository repository;
    private final LinearLayout parent;
    private final DictionaryEntryView.Navigation navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Section> sections = new ArrayList<>();
    private final LinkedHashMap<Section, List<String>> results = new LinkedHashMap<>(8, .75f, true);
    private final LinkedHashSet<Section> rendered = new LinkedHashSet<>();
    private final String word;
    private boolean closed;
    private int resultChars;
    private PopupWindow listPopup;

    DictionaryLookupController(Context context, LinearLayout parent, String word, DictionaryEntryView.Navigation navigation) {
        this.context = context; this.parent = parent; this.word = word; this.navigation = navigation;
        repository = DictionaryRepository.get(context);
        repository.execute(repository::reload, error -> {
            if (closed) return;
            if (error != null) { parent.addView(label("词库记录读取失败：" + error.getMessage())); return; }
            for (DictionaryRepository.Dictionary d : repository.list()) if (d.enabled) {
                Section section = new Section(d); sections.add(section);
                parent.addView(section.header); parent.addView(section.body);
            }
            for (Section section : sections) if (section.expanded) section.request(1);
            parent.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            parent.post(this::prefetchVisible);
        });
    }
    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener = this::prefetchVisible;
    private void prefetchVisible() {
        if (closed) return;
        Rect visible = new Rect();
        for (Section section : sections) if (!section.expanded && section.header.getGlobalVisibleRect(visible)) section.request(2);
    }
    private int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    private TextView label(String text) {
        TextView label = new TextView(context); label.setText(text); label.setTextSize(15);
        label.setTextColor(0xff73777a); label.setPadding(dp(14), dp(12), dp(14), dp(12)); return label;
    }
    private void cache(Section section, List<String> entries) {
        int count = chars(entries);
        if (count > 512 * 1024) { section.message("词条 HTML 超过 512 KB 显示限制"); return; }
        while (resultChars + count > 2 * 1024 * 1024 && !results.isEmpty()) {
            Section oldest = results.keySet().iterator().next();
            resultChars -= chars(results.remove(oldest));
        }
        List<String> old = results.put(section, entries);
        if (old != null) resultChars -= chars(old);
        resultChars += count;
        if (section.expanded) section.render(entries);
    }
    private static int chars(List<String> entries) {
        int total = 0; for (String entry : entries) total += entry.length(); return total;
    }
    void close() {
        if (closed) return;
        closed = true;
        if (parent.getViewTreeObserver().isAlive()) parent.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        for (Section section : sections) {
            if (section.ticket != null) section.ticket.cancel();
            section.destroyView();
        }
        results.clear(); resultChars = 0;
        if (listPopup != null) { listPopup.dismiss(); listPopup = null; }
        repository.queries.submit(0, repository::closeReaders);
    }
    void showList(View anchor, Runnable manage) {
        LinearLayout menu = new LinearLayout(context); menu.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xffffffff); background.setCornerRadius(dp(16)); menu.setBackground(background);
        ScrollView scroll = new ScrollView(context); scroll.setVerticalScrollBarEnabled(false); scroll.addView(menu);
        PopupWindow popup = new PopupWindow(scroll, dp(280), dp(320), true);
        if (listPopup != null) listPopup.dismiss(); listPopup = popup;
        popup.setBackgroundDrawable(background); popup.setElevation(dp(12)); popup.setOutsideTouchable(true);
        for (DictionaryRepository.Dictionary d : repository.list()) {
            CheckBox row = new CheckBox(context); row.setText(d.name); row.setChecked(d.enabled);
            row.setPadding(dp(12), dp(8), dp(12), dp(8)); menu.addView(row);
            row.setOnCheckedChangeListener((button, checked) -> repository.execute(() -> repository.enable(d.id, checked), error -> {
                if (!closed && error != null) Toast.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
                if (error == null && !closed) { popup.dismiss(); navigation.lookup(word); }
            }));
        }
        TextView entry = label("管理全部词库"); entry.setTextColor(0xff006bff); menu.addView(entry);
        entry.setOnClickListener(v -> { popup.dismiss(); manage.run(); });
        popup.showAsDropDown(anchor, -dp(180), 0);
    }
    private final class Section {
        final DictionaryRepository.Dictionary dictionary;
        final LinearLayout header = new LinearLayout(context), body = new LinearLayout(context);
        final ImageView arrow = new ImageView(context);
        DictionaryScheduler.Ticket ticket;
        DictionaryEntryView web;
        boolean expanded, attempted;
        Section(DictionaryRepository.Dictionary dictionary) {
            this.dictionary = dictionary;
            header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(14), 0, dp(8), 0);
            header.setBackgroundColor(0xfff4f5f6);
            TextView title = label(dictionary.name); title.setPadding(0, dp(9), 0, dp(9)); title.setTextColor(0xff34373b);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            arrow.setImageResource(R.drawable.ic_lookup_arrow_up); arrow.setRotation(180);
            arrow.setPadding(dp(12), dp(12), dp(12), dp(12));
            header.addView(arrow, new LinearLayout.LayoutParams(dp(40), dp(40)));
            header.setMinimumHeight(dp(40)); body.setOrientation(LinearLayout.VERTICAL); body.setBackgroundColor(0xffffffff); body.setVisibility(View.GONE);
            expanded = dictionary.lookupExpanded;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE); arrow.setRotation(expanded ? 0 : 180);
            header.setOnClickListener(v -> {
                expanded = !expanded; body.setVisibility(expanded ? View.VISIBLE : View.GONE); arrow.setRotation(expanded ? 0 : 180);
                if (expanded) {
                    List<String> entries = results.get(this);
                    if (entries != null) render(entries);
                    else { attempted = false; request(0); }
                    if (ticket != null) ticket.promote(0);
                } else { destroyView(); }
            });
        }
        void message(String text) { body.removeAllViews(); body.addView(label(text)); }
        void request(int priority) {
            if (closed) return;
            if (ticket != null) { ticket.promote(priority); return; }
            if (attempted || results.containsKey(this)) return;
            message("正在查询离线词典…");
            ticket = repository.queries.submit(priority, () -> {
                List<String> entries = null; Exception error = null;
                try { entries = repository.lookup(dictionary, word); } catch (Exception e) { error = e; }
                List<String> ready = entries; Exception failure = error;
                main.post(() -> {
                    if (closed) return;
                    ticket = null; attempted = true;
                    if (failure == null) cache(this, ready);
                    else message("词典读取失败：" + failure.getMessage());
                });
            });
            if (ticket == null) { attempted = false; message("查询队列已满，点击分区重试"); }
        }
        void render(List<String> entries) {
            destroyView();
            if (entries.isEmpty()) { message("该词典未收录此词条"); return; }
            while (rendered.size() >= 3) {
                Section oldest = rendered.iterator().next(); oldest.destroyView(); oldest.message("点击分区重新加载释义");
                oldest.expanded = false; oldest.body.setVisibility(View.GONE); oldest.arrow.setRotation(180);
            }
            try {
                web = new DictionaryEntryView(context, repository, dictionary, entries, navigation);
                body.removeAllViews(); body.addView(web, new LinearLayout.LayoutParams(-1, 1));
                rendered.add(this);
            } catch (RuntimeException error) { message("释义显示失败：" + error.getMessage()); }
        }
        void destroyView() {
            if (web != null) { body.removeView(web); web.close(); web = null; }
            rendered.remove(this);
        }
    }
}
