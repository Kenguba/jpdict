package com.yuen.jpdict;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Debounced key-only segmentation on the shared bounded dictionary scheduler. */
final class SearchTextExplosionController {
    interface Result { void show(SearchTextSegments.Page page, List<SearchTextSegments.Part> parts, boolean loading); }
    private final DictionaryRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Result result;
    private DictionaryScheduler.Ticket ticket;
    private Runnable pending;
    private String input = "";
    private int offset, generation;
    private boolean closed, ready, paused;

    SearchTextExplosionController(Context context, Result result) {
        repository = DictionaryRepository.get(context); this.result = result;
        repository.ensureLoaded(error -> {
            if (!closed && error == null) { ready = true; refresh(); }
        });
    }
    void update(String value) {
        if (!input.equals(value)) offset = 0;
        input = value; refresh();
    }
    void showPage(int start) { offset = start; refresh(); }
    private void refresh() {
        int request = ++generation;
        cancel();
        SearchTextSegments.Page page = SearchTextSegments.page(input, offset);
        List<SearchTextSegments.Part> plain = SearchTextSegments.match(page, Collections.emptySet());
        result.show(page, plain, !page.candidates.isEmpty() && ready && !paused && !closed);
        if (closed || paused || !ready || page.candidates.isEmpty()) return;
        pending = () -> {
            pending = null;
            ticket = repository.queries.submit(2, () -> {
                Set<String> matches;
                try { matches = repository.matchTerms(page.candidates); }
                catch (IOException unavailable) { matches = Collections.emptySet(); }
                List<SearchTextSegments.Part> parts = SearchTextSegments.match(page, matches);
                main.post(() -> {
                    if (!closed && !paused && request == generation) { ticket = null; result.show(page, parts, false); }
                });
            });
            if (ticket == null && !closed && !paused && request == generation) result.show(page, plain, false);
        };
        main.postDelayed(pending, 250);
    }
    private void cancel() {
        if (pending != null) { main.removeCallbacks(pending); pending = null; }
        if (ticket != null) { ticket.cancel(); ticket = null; }
    }
    void pause() { paused = true; generation++; cancel(); }
    void resume() { if (paused) { paused = false; refresh(); } }
    void close() { closed = true; generation++; cancel(); }
}
