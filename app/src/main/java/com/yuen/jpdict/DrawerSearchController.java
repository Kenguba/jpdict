package com.yuen.jpdict;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cancels stale local searches and keeps file reads off the drawer's UI thread. */
final class DrawerSearchController {
    interface Results {
        void show(List<String> history, List<DrawerSearchFileRepository.SearchResult> files);
    }

    private final Context context;
    private final Results results;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> current;
    private Runnable pending;
    private int generation;
    private boolean disposed;

    DrawerSearchController(Context context, Results results) {
        this.context = context.getApplicationContext();
        this.results = results;
    }

    void search(String query, boolean privateMode, boolean searching) {
        if (disposed) return;
        int request = ++generation;
        if (pending != null) main.removeCallbacks(pending);
        if (current != null) current.cancel(true);
        pending = () -> current = worker.submit(() -> {
            List<String> history = DrawerSearchFileRepository.history(context, query, privateMode);
            List<DrawerSearchFileRepository.SearchResult> files = searching
                    ? DrawerSearchFileRepository.search(DrawerSearchFileRepository.load(context), query)
                    : Collections.emptyList();
            main.post(() -> {
                if (!disposed && request == generation) results.show(history, files);
            });
        });
        main.postDelayed(pending, searching ? 90 : 0);
    }

    void dispose() {
        disposed = true;
        generation++;
        if (pending != null) main.removeCallbacks(pending);
        if (current != null) current.cancel(true);
        worker.shutdownNow();
    }
}
