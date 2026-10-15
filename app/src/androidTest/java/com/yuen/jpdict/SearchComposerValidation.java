package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable QA package only: real clipboard events, platform drag/drop and dictionary token click. */
final class SearchComposerValidation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation) throws Exception {
        SearchComposerValidation test = new SearchComposerValidation(); test.interactions(instrumentation);
        return "Search composer: " + test.checks + " assertions passed\n";
    }
    private void interactions(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if ("com.yuen.jpdict".equals(context.getPackageName())) throw new AssertionError("Run composer validation in an independent disposable QA applicationId");
        DictionaryRepository repository = DictionaryRepository.get(context); repository.reload();
        if (!repository.list().isEmpty()) throw new AssertionError("Composer validation requires an empty disposable dictionary registry");
        File original = new File(context.getCacheDir(), "composer-original.mdx");
        String[] keys = {"hold", "hold on", "日本語"};
        byte[][] entries = {"<p>hold</p>".getBytes(StandardCharsets.UTF_8), "<p>hold on</p>".getBytes(StandardCharsets.UTF_8), "<p>日本語</p>".getBytes(StandardCharsets.UTF_8)};
        try (FileOutputStream out = new FileOutputStream(original)) {
            out.write(MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 2, false, keys, entries));
        }
        String installed = repository.install(Collections.singletonList(new DictionaryRepository.Document(
                original.getName(), Uri.fromFile(original), original.length())), true, null, (message, done, total) -> {}).get(0).id;
        repository.reload();
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        ClipboardManager clipboard = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        AtomicReference<FloatingSearchView> root = new AtomicReference<>();
        AtomicReference<String> lookup = new AtomicReference<>(), draft = new AtomicReference<>("previous draft");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        try {
            test.runOnMainSync(() -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("composer setup", "previous draft"));
                FloatingSearchView view = new FloatingSearchView(context, "previous draft", false, new FloatingSearchView.Callback() {
                    public void onQueryChanged(String value) { draft.set(value); }
                    public void onLookup(String value, boolean privateMode) { lookup.set(value); }
                    public void onPrivateModeChanged(boolean privateMode) { }
                    public void onEditingChanged(boolean editing) { }
                    public void onOpenPage(String page, boolean privateMode) { throw new AssertionError("Composer fixture must not open app pages"); }
                    public void onClose() { }
                });
                root.set(view); windows.addView(view, params);
            });
            await(test, () -> root.get().hasWindowFocus(), "Composer overlay receives window focus");
            EditText input = (EditText)field(FloatingSearchView.class, "input").get(root.get());
            SearchDraftChips chips = (SearchDraftChips)field(FloatingSearchView.class, "draftChips").get(root.get());
            View trash = (View)field(FloatingSearchView.class, "draftTrash").get(root.get());
            View explosion = (View)field(FloatingSearchView.class, "textExplosion").get(root.get());
            test.runOnMainSync(() -> clipboard.setPrimaryClip(ClipData.newPlainText("composer copy", "NEW TEXT")));
            await(test, () -> input.getText().toString().equals("NEW TEXT"), "Fresh clipboard text replaces the complete original draft");
            check("NEW TEXT".equals(draft.get()), "Shared query callback receives clipboard replacement");
            await(test, () -> chipCount(chips) == 1, "Displaced draft appears as a purple chip");
            test.runOnMainSync(() -> check(chip(chips, 0).getContentDescription().toString().contains("previous draft"), "Chip retains displaced text as its accessible payload"));
            drag(test, chip(chips, 0), () -> editorPoint(input, 4), trash,
                    () -> input.getSelectionStart() == 4 && dragCaret(input) == 4);
            await(test, () -> input.getText().toString().equals("NEW previous draftTEXT"), "Native drop inserts exactly at the displayed middle caret");
            await(test, () -> chipCount(chips) == 0, "Successful insertion consumes only the dragged chip");
            await(test, () -> trash.getVisibility() == View.GONE && dragCaret(input) < 0, "Drop removes trash and insertion preview");

            test.runOnMainSync(() -> clipboard.setPrimaryClip(ClipData.newPlainText("composer phrase", "Please hold on now.")));
            await(test, () -> input.getText().toString().equals("Please hold on now."), "Second copy replaces the prior composed input");
            await(test, () -> findClickableText(explosion, "hold on") != null, "Longest real dictionary phrase becomes a clickable explosion token");
            AtomicReference<View> phrase = new AtomicReference<>();
            test.runOnMainSync(() -> phrase.set(findClickableText(explosion, "hold on")));
            tap(test, phrase.get());
            await(test, () -> "hold on".equals(lookup.get()), "Matched chip passes the full original phrase to dictionary lookup");
            check("Please hold on now.".equals(draft.get()), "Explosion lookup preserves the complete composer draft");
            test.runOnMainSync(() -> {
                root.get().prepareForLookup(); root.get().resumeAfterLookup();
                check(findClickableText(explosion, "hold on") == phrase.get(),
                        "Returning from lookup immediately retains the same confirmed phrase view");
                input.setText("Please hold on now!");
                check(findClickableText(explosion, "hold on") == phrase.get(),
                        "Editing the tail keeps the existing phrase throughout debounce");
            });
            Thread.sleep(400); test.waitForIdleSync();
            test.runOnMainSync(() -> check(findClickableText(explosion, "hold on") == phrase.get(),
                    "Completed matching updates a confirmed phrase in place"));
            test.runOnMainSync(() -> { input.setText("hold on"); lookup.set(null); });
            await(test, () -> findClickableText(explosion, "hold on") != null,
                    "Matching the complete short phrase does not hide the explosion area");
            test.runOnMainSync(() -> phrase.set(findClickableText(explosion, "hold on")));
            tap(test, phrase.get());
            await(test, () -> "hold on".equals(lookup.get()), "Complete short phrase remains clickable after segmentation");

            StringBuilder longSource = new StringBuilder("hold on ");
            for (int i = 0; i < 180; i++) longSource.append("word").append(i).append(' ');
            longSource.append("日本語");
            String completeSource = longSource.toString();
            test.runOnMainSync(() -> input.setText(completeSource));
            await(test, () -> findClickableText(explosion, "日本語") != null,
                    "Long text loads its final matched term without a next-page action");
            ScrollView explosionScroll = (ScrollView)field(SearchTextExplosionView.class, "scroll").get(explosion);
            test.runOnMainSync(() -> {
                @SuppressWarnings("unchecked") List<SearchTextSegments.Part> displayed =
                        (List<SearchTextSegments.Part>)uncheckedField(SearchTextExplosionView.class, "displayed", explosion);
                StringBuilder visibleText = new StringBuilder();
                for (SearchTextSegments.Part part : displayed) visibleText.append(part.text);
                check(completeSource.contentEquals(visibleText), "Accumulated explosion batches retain the complete original text");
                check(findText(explosion, "上一段") == null && findText(explosion, "下一段") == null
                        && findText(explosion, "蓝色词条可点击查询") == null,
                        "Explosion has scrolling content without pagination or the removed hint");
                explosionScroll.fullScroll(View.FOCUS_DOWN);
            });
            await(test, () -> explosionScroll.getScrollY() > 0, "The complete long document scrolls vertically");
            test.runOnMainSync(() -> {
                int position = explosionScroll.getScrollY(); View last = findClickableText(explosion, "日本語");
                root.get().prepareForLookup(); root.get().resumeAfterLookup();
                check(explosionScroll.getScrollY() == position && findClickableText(explosion, "日本語") == last,
                        "A long-text lookup round trip preserves its matched tail and exact scroll position");
            });

            test.runOnMainSync(() -> clipboard.setPrimaryClip(ClipData.newPlainText("composer delete", "FINAL TEXT")));
            await(test, () -> input.getText().toString().equals("FINAL TEXT") && chipCount(chips) == 2, "Subsequent copy keeps distinct displaced drafts");
            drag(test, chip(chips, 0), () -> center(trash), trash, null);
            await(test, () -> chipCount(chips) == 1 && trash.getVisibility() == View.GONE, "Native trash drop deletes the dragged draft");
            test.runOnMainSync(() -> check(input.getText().toString().equals("FINAL TEXT"), "Trash deletion does not modify the active draft"));
            test.runOnMainSync(() -> { root.get().onWindowFocusChanged(false); root.get().onWindowFocusChanged(true); });
            test.waitForIdleSync();
            test.runOnMainSync(() -> check(chipCount(chips) == 1 && input.getText().toString().equals("FINAL TEXT"),
                    "Restoring search focus cannot consume the same copy again"));
        } finally {
            test.runOnMainSync(() -> {
                if (root.get() != null) { root.get().dispose(); if (root.get().isAttachedToWindow()) windows.removeViewImmediate(root.get()); }
            });
            for (DictionaryRepository.Dictionary dictionary : new ArrayList<>(repository.list())) if (dictionary.id.equals(installed)) repository.delete(dictionary);
            repository.reload(); original.delete();
        }
    }
    private interface Condition { boolean value() throws Exception; }
    private interface Point { float[] value() throws Exception; }
    private void await(Instrumentation test, Condition condition, String message) throws Exception {
        for (int i = 0; i < 125; i++) {
            AtomicReference<Boolean> value = new AtomicReference<>(false); AtomicReference<Throwable> failure = new AtomicReference<>();
            test.runOnMainSync(() -> { try { value.set(condition.value()); } catch (Throwable error) { failure.set(error); } });
            if (failure.get() != null) throw new AssertionError(message, failure.get());
            if (value.get()) { check(true, message); return; }
            Thread.sleep(40);
        }
        throw new AssertionError(message);
    }
    private void drag(Instrumentation test, View source, Point destination, View trash, Condition caret) throws Exception {
        AtomicReference<float[]> from = new AtomicReference<>(), to = new AtomicReference<>();
        test.waitForIdleSync();
        await(test, () -> source.isLaidOut() && source.getWidth() > 0 && source.getHeight() > 0
                && !source.isLayoutRequested() && !source.getRootView().isLayoutRequested(),
                "Native drag source finishes layout before pointer injection");
        test.runOnMainSync(() -> from.set(center(source)));
        long down = SystemClock.uptimeMillis(); float[] start = from.get();
        inject(test, down, MotionEvent.ACTION_DOWN, start[0], start[1]);
        boolean released = false;
        try {
            Thread.sleep(ViewConfiguration.getLongPressTimeout() + 180L);
            await(test, () -> trash.getVisibility() == View.VISIBLE, "Long press starts a native drag and reveals the red trash target");
            test.runOnMainSync(() -> { try { to.set(destination.value()); } catch (Exception error) { throw new RuntimeException(error); } });
            float[] end = to.get();
            for (int i = 1; i <= 8; i++) { float progress = i / 8f;
                inject(test, down, MotionEvent.ACTION_MOVE, start[0] + (end[0] - start[0]) * progress, start[1] + (end[1] - start[1]) * progress); Thread.sleep(35); }
            if (caret != null) await(test, caret, "Native drag tracks an exact visible caret before release");
            inject(test, down, MotionEvent.ACTION_UP, end[0], end[1]); released = true;
        } finally {
            if (!released) inject(test, down, MotionEvent.ACTION_CANCEL, start[0], start[1]);
        }
    }
    private static void tap(Instrumentation test, View view) {
        AtomicReference<float[]> point = new AtomicReference<>(); test.runOnMainSync(() -> point.set(center(view)));
        long down = SystemClock.uptimeMillis(); float[] p = point.get();
        inject(test, down, MotionEvent.ACTION_DOWN, p[0], p[1]); inject(test, down, MotionEvent.ACTION_UP, p[0], p[1]);
    }
    private static void inject(Instrumentation test, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Platform input injection failed"); }
        finally { event.recycle(); }
    }
    private static float[] center(View view) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible) || visible.isEmpty()) throw new AssertionError("Interaction target is not visible");
        // GlobalVisibleRect is relative to the window root, whereas UiAutomation
        // injects screen coordinates; overlay windows may start below the status bar.
        int[] screen = new int[2], window = new int[2];
        view.getLocationOnScreen(screen); view.getLocationInWindow(window);
        return new float[]{visible.exactCenterX() + screen[0] - window[0],
                visible.exactCenterY() + screen[1] - window[1]};
    }
    private static float[] editorPoint(EditText input, int offset) {
        int[] position = new int[2]; input.getLocationOnScreen(position); Layout layout = input.getLayout();
        int line = layout.getLineForOffset(offset);
        return new float[]{position[0] + input.getTotalPaddingLeft() + layout.getPrimaryHorizontal(offset) - input.getScrollX(),
                position[1] + input.getTotalPaddingTop() + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - input.getScrollY()};
    }
    private static int dragCaret(EditText input) throws Exception { return field(input.getClass(), "dragCaret").getInt(input); }
    private static View chip(SearchDraftChips chips, int index) { return ((ViewGroup)chips.getChildAt(0)).getChildAt(index); }
    private static int chipCount(SearchDraftChips chips) { return ((ViewGroup)chips.getChildAt(0)).getChildCount(); }
    private static View findClickableText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)
                && view.isClickable() && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findClickableText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static View findText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static Object uncheckedField(Class<?> type, String name, Object owner) {
        try { return field(type, name).get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
