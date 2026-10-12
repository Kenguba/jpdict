package com.yuen.jpdict;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
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
    private boolean closed, ready;
    private String latestInput = "";

    SearchSuggestionsController(Context context, Result result) {
        repository = DictionaryRepository.get(context); this.result = result;
        repository.execute(repository::reload, error -> {
            if (!closed && error == null) { ready = true; update(latestInput); }
        });
    }
    void update(String input) {
        latestInput = input;
        int request = ++generation;
        if (pending != null) main.removeCallbacks(pending);
        if (ticket != null) { ticket.cancel(); ticket = null; }
        result.show(Collections.emptyList());
        String prefix = input.trim();
        if (closed || !ready || prefix.isEmpty() || prefix.length() > 128 || prefix.indexOf('\n') >= 0) return;
        pending = () -> {
            pending = null;
            ticket = repository.queries.submit(2, () -> {
                List<String> words;
                try { words = repository.suggest(prefix); }
                catch (IOException unavailable) { words = Collections.emptyList(); }
                List<String> ready = words;
                main.post(() -> {
                    if (!closed && request == generation) { ticket = null; result.show(ready); }
                });
            });
        };
        main.postDelayed(pending, 250);
    }
    void close() {
        closed = true; generation++;
        if (pending != null) main.removeCallbacks(pending);
        if (ticket != null) ticket.cancel();
    }
}
