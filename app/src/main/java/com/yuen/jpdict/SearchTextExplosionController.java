package com.yuen.jpdict;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Debounced, cancellable dictionary batches; the view accumulates their original-text ranges. */
final class SearchTextExplosionController {
    interface Result { void show(SearchTextSegments.Page page, List<SearchTextSegments.Part> parts, boolean loading); }
    private final DictionaryRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Result result;
    private final Set<String> confirmedMatches = new HashSet<>(), scanMatches = new HashSet<>();
    private DictionaryScheduler.Ticket ticket;
    private Runnable pending;
    private String input = "";
    private int offset, generation;
    private boolean closed, ready, paused, complete;

    SearchTextExplosionController(Context context, Result result) {
        repository = DictionaryRepository.get(context); this.result = result;
        repository.ensureLoaded(error -> {
            if (!closed && error == null) {
                ready = true; offset = 0; complete = false; scanMatches.clear();
                generation++; cancel();
                if (!paused) schedule(SearchTextSegments.page(input, offset), 250);
            }
        });
    }
    void update(String value) {
        String next = value == null ? "" : value;
        if (closed || input.equals(next)) return;
        input = next; offset = 0; complete = false; scanMatches.clear();
        generation++; cancel();
        SearchTextSegments.Page page = SearchTextSegments.page(input, offset);
        // Keep confirmed words during the debounce instead of bleaching all chips on each keystroke.
        result.show(page, SearchTextSegments.match(page, confirmedMatches), !paused);
        if (!paused) schedule(page, 250);
    }
    private void schedule(SearchTextSegments.Page page, long delay) {
        if (closed || paused || complete) return;
        int request = generation;
        boolean canMatch = ready;
        Set<String> known = new HashSet<>(confirmedMatches);
        List<SearchTextSegments.Part> plain = SearchTextSegments.match(page, known);
        pending = () -> {
            pending = null;
            ticket = repository.queries.submit(2, () -> {
                Set<String> matches = known;
                if (canMatch && !page.candidates.isEmpty()) {
                    try { matches = repository.matchTerms(page.candidates); }
                    catch (IOException unavailable) {
                        if (Thread.currentThread().isInterrupted()) return;
                        matches = Collections.emptySet();
                    }
                }
                if (Thread.currentThread().isInterrupted()) return;
                Set<String> found = matches;
                List<SearchTextSegments.Part> parts = SearchTextSegments.match(page, found);
                main.post(() -> deliver(request, page, parts));
            });
            // Scheduler saturation must not prevent the remaining original text from being readable.
            if (ticket == null) deliver(request, page, plain);
        };
        main.postDelayed(pending, delay);
    }
    private void deliver(int request, SearchTextSegments.Page page,
            List<SearchTextSegments.Part> parts) {
        if (closed || paused || request != generation) return;
        ticket = null;
        for (SearchTextSegments.Part part : parts) if (part.matched) scanMatches.add(part.text);
        confirmedMatches.addAll(scanMatches);
        result.show(page, parts, false);
        offset = page.end; complete = !page.hasNext();
        if (complete) { confirmedMatches.clear(); confirmedMatches.addAll(scanMatches); }
        else schedule(SearchTextSegments.page(input, offset), 16);
    }
    private void cancel() {
        if (pending != null) { main.removeCallbacks(pending); pending = null; }
        if (ticket != null) { ticket.cancel(); ticket = null; }
    }
    void pause() { paused = true; generation++; cancel(); }
    void resume() {
        if (!paused || closed) return;
        paused = false;
        // A lookup round trip keeps the existing chips and scroll; only unfinished batches resume.
        if (!complete) schedule(SearchTextSegments.page(input, offset), 0);
    }
    void close() { closed = true; generation++; cancel(); }
}
