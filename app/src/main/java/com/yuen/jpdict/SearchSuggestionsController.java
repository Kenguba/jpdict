package com.yuen.jpdict;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Debounced local completions, owned by one search surface. */
final class SearchSuggestionsController {
    interface Result { void show(List<String> words); }
    private final DictionaryRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Result result;
    private DictionaryScheduler.Ticket ticket;
    private Runnable pending;
    private int generation;
    private boolean closed, ready, paused;
    private String latestInput = "";
    private List<String> shownWords = Collections.emptyList();

    SearchSuggestionsController(Context context, Result result) {
        repository = DictionaryRepository.get(context); this.result = result;
        repository.ensureLoaded(error -> {
            if (!closed && error == null) { ready = true; update(latestInput); }
        });
    }
    void update(String input) {
        if (closed) return;
        latestInput = input;
        int request = ++generation;
        cancel();
        String prefix = input.trim();
        if (prefix.isEmpty() || prefix.length() > 128 || prefix.indexOf('\n') >= 0) {
            show(Collections.emptyList());
            return;
        }
        // Keep usable candidates while debounce/query work runs, including a
        // return from lookup. Only the latest completed query replaces them.
        if (paused || !ready) return;
        pending = () -> {
            pending = null;
            ticket = repository.queries.submit(2, () -> {
                List<String> words;
                try { words = repository.suggest(prefix); }
                catch (IOException unavailable) { words = Collections.emptyList(); }
                List<String> ready = words;
                main.post(() -> {
                    if (!closed && !paused && request == generation) { ticket = null; show(ready); }
                });
            });
        };
        main.postDelayed(pending, 250);
    }
    private void show(List<String> words) {
        if (shownWords.equals(words)) return;
        shownWords = Collections.unmodifiableList(new ArrayList<>(words));
        result.show(shownWords);
    }
    private void cancel() {
        if (pending != null) { main.removeCallbacks(pending); pending = null; }
        if (ticket != null) { ticket.cancel(); ticket = null; }
    }
    void close() {
        closed = true; generation++;
        cancel();
    }
    void pause() {
        paused = true; generation++;
        cancel();
    }
    void resume() { if (paused) { paused = false; update(latestInput); } }
}
