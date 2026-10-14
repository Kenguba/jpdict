package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real query completions with a deliberately small, measured native viewport. */
final class DictionaryLoadingValidation {
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static void run(Instrumentation instrumentation, DictionaryRepository repository, File originals) throws Exception {
        Context context = instrumentation.getTargetContext();
        List<String> originalOrder = new ArrayList<>(), installed = new ArrayList<>();
        for (DictionaryRepository.Dictionary dictionary : repository.list()) originalOrder.add(dictionary.id);
        AtomicReference<DictionaryLookupController> controller = new AtomicReference<>();
        AtomicReference<LinearLayout> host = new AtomicReference<>();
        AtomicReference<ScrollView> viewport = new AtomicReference<>();
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        try {
            String padding = new String(new char[256 * 1024]).replace('\0', 'x');
            for (int i = 0; i < 9; i++) {
                File file = new File(originals, "large-loading-" + i + ".mdx");
                String[] keys = {"alpha"};
                byte[][] values = {("<p>" + i + padding + "</p>").getBytes(StandardCharsets.UTF_8)};
                byte[] bytes = MdictReaderTest.headerChange(
                        MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 2, false, keys, values, keys, values[0].length),
                        "Title=\"JPDict 原创测试\"", "Title=\"Loading fixture " + i + "\"");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                installed.add(repository.install(Collections.singletonList(new DictionaryRepository.Document(file.getName(), Uri.fromFile(file), file.length())),
                        true, null, (message, done, total) -> {}).get(0).id);
            }
            repository.setLookupExpanded(installed.get(0), true);
            List<String> order = new ArrayList<>(); order.add(originalOrder.get(0)); order.addAll(installed);
            order.addAll(originalOrder.subList(1, originalOrder.size())); repository.reorder(order); repository.reload();
            Field sectionsField = field(DictionaryLookupController.class, "sections"), resultsField = field(DictionaryLookupController.class, "results");
            Class<?> sectionType = Class.forName("com.yuen.jpdict.DictionaryLookupController$Section");
            Method request = sectionType.getDeclaredMethod("request", int.class); request.setAccessible(true);
            Field ticket = field(sectionType, "ticket"), attempted = field(sectionType, "attempted");
            Field evicted = field(sectionType, "cacheEvicted");
            instrumentation.runOnMainSync(() -> {
                LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); host.set(view);
                controller.set(new DictionaryLookupController(context, view, "alpha", word -> {}));
            });
            AtomicReference<List<?>> sections = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { sections.set((List<?>) sectionsField.get(controller.get())); request.invoke(sections.get().get(1), 1); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            Object target = sections.get().get(1);
            AtomicBoolean done = new AtomicBoolean();
            for (int wait = 0; wait < 50 && !done.get(); wait++) {
                instrumentation.runOnMainSync(() -> {
                    try { done.set(((Map<?, ?>) resultsField.get(controller.get())).containsKey(target)); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                if (!done.get()) Thread.sleep(100);
            }
            if (!done.get()) throw new AssertionError("Offscreen definition did not finish loading");
            instrumentation.runOnMainSync(() -> {
                try { for (int i = 2; i <= 9; i++) request.invoke(sections.get().get(i), 1); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            done.set(false);
            for (int wait = 0; wait < 50 && !done.get(); wait++) {
                instrumentation.runOnMainSync(() -> {
                    try {
                        boolean complete = true;
                        for (int i = 1; i <= 9; i++) complete &= ticket.get(sections.get().get(i)) == null;
                        done.set(complete && !((Map<?, ?>) resultsField.get(controller.get())).containsKey(target));
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                if (!done.get()) Thread.sleep(100);
            }
            if (!done.get() || attempted.getBoolean(target) || !evicted.getBoolean(target)) throw new AssertionError("Evicted offscreen definition kept its completed-query guard");
            Thread.sleep(450); // Past the deferred delay: an eviction must not start cache churn.
            instrumentation.runOnMainSync(() -> {
                try {
                    if (ticket.get(target) != null || ((Map<?, ?>) resultsField.get(controller.get())).containsKey(target)) throw new AssertionError("Deferred loading immediately reloaded an evicted definition");
                    int row = Math.round(40 * context.getResources().getDisplayMetrics().density);
                    ScrollView scroll = new ScrollView(context); viewport.set(scroll);
                    scroll.addView(host.get(), new ScrollView.LayoutParams(-1, -2));
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(640, row,
                            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    windows.addView(scroll, params);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                View header = host.get().getChildAt(2);
                Rect visible = new Rect();
                if (!viewport.get().isAttachedToWindow() || header.getHeight() <= 0
                        || header.getGlobalVisibleRect(visible))
                    throw new AssertionError("Loading viewport must start with a measured offscreen header: "
                            + header.getTop() + "/" + header.getHeight() + " viewport=" + viewport.get().getHeight());
                viewport.get().scrollTo(0, header.getTop());
                if (!header.getGlobalVisibleRect(visible))
                    throw new AssertionError("Scroll did not expose the target header: top=" + header.getTop()
                            + " height=" + header.getHeight() + " scroll=" + viewport.get().getScrollY());
                host.get().getViewTreeObserver().dispatchOnGlobalLayout();
            });
            done.set(false);
            for (int wait = 0; wait < 50 && !done.get(); wait++) {
                instrumentation.runOnMainSync(() -> {
                    try { done.set(((Map<?, ?>) resultsField.get(controller.get())).containsKey(target)); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                if (!done.get()) Thread.sleep(100);
            }
            if (!done.get()) throw new AssertionError("Scrolling an evicted definition into view did not query it again");
        } finally {
            if (controller.get() != null) instrumentation.runOnMainSync(() -> controller.get().close());
            if (viewport.get() != null) instrumentation.runOnMainSync(() -> {
                if (viewport.get().isAttachedToWindow()) windows.removeViewImmediate(viewport.get());
            });
            for (DictionaryRepository.Dictionary dictionary : new ArrayList<>(repository.list())) if (installed.contains(dictionary.id)) repository.delete(dictionary);
            repository.reorder(originalOrder); repository.reload();
        }
    }
}
