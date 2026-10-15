package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real repository/scheduler lifecycle checks in an empty disposable QA package. */
public final class SearchSuggestionsInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            result.putString("stream", "Search suggestions: " + checks + " assertions passed\n");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString());
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private void verify() throws Exception {
        Context context = getTargetContext();
        if ("com.yuen.jpdict".equals(context.getPackageName())) {
            throw new AssertionError("Use an independent disposable QA applicationId");
        }
        DictionaryRepository repository = DictionaryRepository.get(context); repository.reload();
        if (!repository.list().isEmpty()) throw new AssertionError("Use an empty disposable dictionary registry");
        File original = new File(context.getCacheDir(), "suggestions-original.mdx");
        String[] keys = {"alpha", "alphabet", "beta", "betamax"};
        byte[][] definitions = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) definitions[i] = ("<p>" + keys[i] + "</p>").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(original)) {
            out.write(MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 2, false, keys, definitions));
        }
        String installed = repository.install(Collections.singletonList(new DictionaryRepository.Document(
                original.getName(), Uri.fromFile(original), original.length())), true, null,
                (message, done, total) -> {}).get(0).id;
        repository.reload();
        List<String> alpha = repository.suggest("al"), beta = repository.suggest("be");
        check(!alpha.isEmpty() && !beta.isEmpty() && !alpha.equals(beta), "Fixture queries produce different usable candidates");
        AtomicReference<SearchSuggestionsController> controller = new AtomicReference<>();
        AtomicReference<List<String>> visible = new AtomicReference<>(Collections.emptyList());
        List<List<String>> changes = new ArrayList<>();
        try {
            onMain(() -> {
                controller.set(new SearchSuggestionsController(context, words -> {
                    visible.set(words); changes.add(new ArrayList<>(words));
                }));
                controller.get().update("al");
            });
            await(() -> visible.get().equals(alpha), "Initial input resolves real local candidates");
            int initial = changes.size();
            onMain(() -> {
                controller.get().update("be");
                check(visible.get().equals(alpha) && changes.size() == initial,
                        "Typing preserves the prior candidates throughout debounce");
            });
            await(() -> visible.get().equals(beta), "Completed input atomically replaces candidates");
            int completed = changes.size();
            onMain(() -> controller.get().update("be "));
            await(() -> idle(controller.get()), "Equivalent normalized input finishes its query");
            check(changes.size() == completed, "Identical results do not rebuild candidate views");
            onMain(() -> {
                controller.get().pause(); controller.get().resume();
                check(visible.get().equals(beta) && changes.size() == completed,
                        "Returning from lookup retains immediately visible candidates");
            });
            await(() -> idle(controller.get()), "Lookup resume refresh finishes");
            check(changes.size() == completed, "Lookup resume with unchanged results does not redraw candidates");

            onMain(() -> {
                controller.get().update("");
                check(visible.get().isEmpty(), "Empty input immediately clears previous candidates");
                controller.get().update("al");
            });
            await(() -> visible.get().equals(alpha), "Candidates resolve again after clearing the input");
            onMain(() -> {
                controller.get().update("al\nbeta");
                check(visible.get().isEmpty(), "Multiline input immediately clears unsupported local candidates");
                controller.get().update("al");
            });
            await(() -> visible.get().equals(alpha), "Candidates resolve again after multiline input");
            onMain(() -> {
                controller.get().update(new String(new char[129]).replace('\0', 'a'));
                check(visible.get().isEmpty(), "Oversized input immediately clears unsupported local candidates");
                controller.get().update("al");
            });
            await(() -> visible.get().equals(alpha), "Candidates resolve again after oversized input");

            verifyStaleCompletion(repository, controller.get(), visible, changes, alpha, beta);
            int beforePause = changes.size();
            onMain(() -> {
                controller.get().pause(); controller.get().update("be");
                check(visible.get().equals(alpha), "Input changed under a lookup preserves displayed candidates");
            });
            check(changes.size() == beforePause, "Paused input does not publish a temporary empty result");
            onMain(() -> controller.get().resume());
            await(() -> visible.get().equals(beta), "Resume queries the latest paused input");
            onMain(() -> {
                controller.get().pause(); controller.get().update("  ");
                check(visible.get().isEmpty(), "Clearing input while paused cannot revive old candidates");
                controller.get().resume(); controller.get().update("al");
            });
            await(() -> visible.get().equals(alpha), "Valid input remains usable after paused clearing");
            int beforeClose = changes.size();
            onMain(() -> {
                controller.get().update("be"); controller.get().close();
                controller.get().update(""); controller.get().resume();
            });
            await(() -> idle(controller.get()), "Close removes pending debounce and query ownership");
            check(changes.size() == beforeClose && visible.get().equals(alpha),
                    "Closed controllers cannot clear or replace the retained result");
        } finally {
            onMain(() -> { if (controller.get() != null) controller.get().close(); });
            for (DictionaryRepository.Dictionary dictionary : new ArrayList<>(repository.list())) {
                if (dictionary.id.equals(installed)) repository.delete(dictionary);
            }
            repository.reload(); original.delete();
        }
    }

    private void verifyStaleCompletion(DictionaryRepository repository, SearchSuggestionsController controller,
            AtomicReference<List<String>> visible, List<List<String>> changes,
            List<String> alpha, List<String> beta) throws Exception {
        CountDownLatch started = new CountDownLatch(2), first = new CountDownLatch(1), second = new CountDownLatch(1);
        repository.queries.submit(0, () -> block(started, first));
        repository.queries.submit(0, () -> block(started, second));
        try {
            check(started.await(3, TimeUnit.SECONDS), "Both query workers enter a controlled scheduler gate");
            onMain(() -> controller.update("be"));
            await(() -> field(controller, "pending") == null && field(controller, "ticket") != null,
                    "New query queues behind the controlled workers");
            int before = changes.size();
            CountDownLatch workerFinished = new CountDownLatch(1);
            onMain(() -> {
                // Leave one worker blocked: the other must finish the old query
                // and post its UI callback before this lower-priority marker.
                repository.queries.submit(3, workerFinished::countDown);
                first.countDown();
                check(workerFinished.await(3, TimeUnit.SECONDS), "Older query finishes while its UI callback is held");
                controller.update("al");
            });
            second.countDown();
            await(() -> idle(controller), "Newest query completes after the stale callback is released");
            check(visible.get().equals(alpha) && changes.size() == before,
                    "A completed older query cannot replace candidates after a newer input");
            check(!changes.subList(before, changes.size()).contains(beta), "Stale candidates never reach the UI result callback");
        } finally { first.countDown(); second.countDown(); }
    }

    private static void block(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try { release.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
    }
    private static boolean idle(SearchSuggestionsController controller) throws Exception {
        return field(controller, "pending") == null && field(controller, "ticket") == null;
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private interface Action { void run() throws Exception; }
    private interface Condition { boolean value() throws Exception; }
    private void onMain(Action action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void await(Condition condition, String message) throws Exception {
        for (int i = 0; i < 125; i++) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            onMain(() -> value.set(condition.value()));
            if (value.get()) { check(true, message); return; }
            Thread.sleep(40);
        }
        throw new AssertionError(message);
    }
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
