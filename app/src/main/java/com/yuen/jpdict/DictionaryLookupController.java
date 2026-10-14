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
    private final LinkedHashMap<Section, DictionaryHtmlDocument> results = new LinkedHashMap<>(8, .75f, true);
    private final LinkedHashSet<Section> rendered = new LinkedHashSet<>();
    private final String word;
    private boolean closed;
    private long resultChars;
    private PopupWindow listPopup;
    private Section deferredLoading;
    private final Runnable deferred = this::loadDeferred;

    DictionaryLookupController(Context context, LinearLayout parent, String word, DictionaryEntryView.Navigation navigation) {
        this.context = context; this.parent = parent; this.word = word; this.navigation = navigation;
        repository = DictionaryRepository.get(context);
        repository.ensureLoaded(error -> {
            if (closed) return;
            if (error != null) { parent.addView(label("词库记录读取失败：" + error.getMessage())); return; }
            for (DictionaryRepository.Dictionary d : repository.list()) if (d.enabled) {
                Section section = new Section(d); sections.add(section);
                parent.addView(section.header); parent.addView(section.body);
            }
            // Start at the front before layout; the viewport pass fills the other
            // worker. Offscreen defaults must not compete with the first result.
            if (!sections.isEmpty()) sections.get(0).request(1);
            parent.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            parent.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            parent.post(this::prefetchVisible);
            scheduleDeferred();
        });
    }
    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener = this::prefetchVisible;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::prefetchVisible;
    private void prefetchVisible() {
        if (closed) return;
        Rect visible = new Rect();
        for (Section section : sections) if (section.header.getGlobalVisibleRect(visible)) {
            section.request(1);
            section.openLoaded(false);
        }
    }
    private void scheduleDeferred() {
        main.removeCallbacks(deferred);
        if (!closed) main.postDelayed(deferred, 300);
    }
    private void loadDeferred() {
        if (closed || deferredLoading != null) return;
        // Only one offscreen query at a time, in display order. Closed libraries
        // are read when visible or tapped instead of parsing all 512 eagerly.
        for (Section section : sections) if (section.wantedExpanded && !section.attempted
                && !section.cacheEvicted && section.ticket == null && !results.containsKey(section)) {
            deferredLoading = section; section.request(3);
            if (section.ticket == null) { deferredLoading = null; scheduleDeferred(); }
            return;
        }
    }
    private int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    private TextView label(String text) {
        TextView label = new TextView(context); label.setText(text); label.setTextSize(15);
        label.setTextColor(0xff73777a); label.setPadding(dp(14), dp(12), dp(14), dp(12)); return label;
    }
    private void cache(Section section, DictionaryHtmlDocument document) {
        // The budget controls retained disk pages, never entry acceptance. A page
        // larger than the budget may occupy it alone until another result arrives.
        long bytes = document.file.length();
        while ((results.size() >= 64 || resultChars + bytes > 2L * 1024 * 1024) && !results.isEmpty()) {
            Section oldest = results.keySet().iterator().next();
            DictionaryHtmlDocument removed = results.remove(oldest); resultChars -= removed.file.length(); removed.close(); oldest.cacheEvicted = true;
            if (oldest.web == null) oldest.attempted = false;
        }
        DictionaryHtmlDocument old = results.put(section, document);
        if (old != null) { resultChars -= old.file.length(); old.close(); }
        resultChars += bytes;
        section.cacheEvicted = false; section.failure = null;
        section.openLoaded(false); parent.post(this::prefetchVisible);
    }
    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(deferred);
        if (parent.getViewTreeObserver().isAlive()) {
            parent.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            parent.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        for (Section section : sections) {
            if (section.ticket != null) section.ticket.cancel();
            section.destroyView();
        }
        for (DictionaryHtmlDocument document : results.values()) document.close();
        results.clear(); resultChars = 0;
        if (listPopup != null) { listPopup.dismiss(); listPopup = null; }
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
        boolean expanded, wantedExpanded, explicitRequest, attempted, cacheEvicted;
        String failure;
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
            wantedExpanded = dictionary.lookupExpanded;
            // The saved preference is intent, not a loaded body. All headers
            // stay collapsed until their definition is available.
            header.setOnClickListener(v -> {
                if (expanded || (explicitRequest && wantedExpanded && (ticket != null || (web != null && !web.isReady())))) {
                    wantedExpanded = false; setExpanded(false);
                } else {
                    wantedExpanded = true; explicitRequest = true;
                    if (web == null && !results.containsKey(this)) { attempted = false; failure = null; request(0); }
                    openLoaded(true);
                    if (ticket != null) ticket.promote(0);
                }
            });
        }
        void setExpanded(boolean value) {
            expanded = value; body.setVisibility(value ? View.VISIBLE : View.GONE); arrow.setRotation(value ? 0 : 180);
        }
        void openLoaded(boolean user) {
            if (!wantedExpanded || expanded || closed) return;
            Rect visible = new Rect();
            if (!user && !explicitRequest && sections.get(0) != this && !header.getGlobalVisibleRect(visible)) return;
            DictionaryHtmlDocument entries = results.get(this);
            if (web != null || entries != null) {
                if (entries != null && !render(entries, user || explicitRequest)) return;
                if (web == null || web.isReady()) setExpanded(true);
            } else if (failure != null) { message(failure); setExpanded(true); }
        }
        void message(String text) { body.removeAllViews(); body.addView(label(text)); }
        void request(int priority) {
            if (closed) return;
            if (ticket != null) { ticket.promote(priority); return; }
            if (attempted || results.containsKey(this)) return;
            message("正在查询离线词典…");
            ticket = repository.queries.submit(priority, () -> {
                DictionaryHtmlDocument entries = null; Exception error = null;
                try { entries = repository.lookupDocument(dictionary, word); } catch (Exception e) { error = e; }
                DictionaryHtmlDocument ready = entries; Exception failure = error;
                main.post(() -> {
                    if (closed) { if (ready != null) ready.close(); return; }
                    ticket = null; attempted = true;
                    if (deferredLoading == this) deferredLoading = null;
                    if (failure == null) cache(this, ready);
                    else { this.failure = "词典读取失败：" + failure.getMessage(); openLoaded(false); }
                    scheduleDeferred();
                });
            });
            if (ticket == null) { attempted = false; failure = "查询队列已满，点击分区重试"; openLoaded(false); }
        }
        boolean render(DictionaryHtmlDocument entries, boolean user) {
            // A collapsed section keeps its DOM, loaded assets and measured height.
            if (web != null) { rendered.remove(this); rendered.add(this); return true; }
            if (entries.entries == 0) { message("该词典未收录此词条"); return true; }
            while (rendered.size() >= 3) {
                Section oldest = null;
                Rect visible = new Rect();
                for (Section candidate : rendered) if (!candidate.wantedExpanded || !candidate.header.getGlobalVisibleRect(visible)) {
                    oldest = candidate; break;
                }
                // Auto-loading another visible header must not evict a front
                // section before its HTML/resources have finished loading.
                if (oldest == null && !user) return false;
                if (oldest == null) oldest = rendered.iterator().next();
                oldest.destroyView(); oldest.message("点击分区重新加载释义");
                oldest.wantedExpanded = false; oldest.setExpanded(false);
            }
            try {
                web = new DictionaryEntryView(context, repository, dictionary, entries, navigation, () -> {
                    openLoaded(false); parent.post(DictionaryLookupController.this::prefetchVisible);
                });
                body.removeAllViews(); body.addView(web, new LinearLayout.LayoutParams(-1, 1));
                rendered.add(this);
            } catch (RuntimeException error) { message("释义显示失败：" + error.getMessage()); }
            return true;
        }
        void destroyView() {
            if (web != null) { body.removeView(web); web.close(); web = null; }
            rendered.remove(this);
        }
    }
}
