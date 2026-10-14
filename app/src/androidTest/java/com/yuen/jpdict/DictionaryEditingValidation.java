package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.database.MatrixCursor;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;

/** Original HTML fields only; never reads a user's dictionary or real Token. */
final class DictionaryEditingValidation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation) throws Exception {
        DictionaryEditingValidation test = new DictionaryEditingValidation();
        test.fields(instrumentation);
        return "Dictionary HTML editing: " + test.checks + " assertions passed\n";
    }
    private void fields(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        DictionaryRepository repository = DictionaryRepository.get(context);
        String[] columns = {"dict_id", "name", "package_id", "installed_version", "source", "storage", "enabled",
                "sort_order", "index_state", "status", "fingerprint", "installed_at", "updated_at", "files", "lookup_expanded"};
        DictionaryRepository.Dictionary dictionary;
        try (MatrixCursor cursor = new MatrixCursor(columns)) {
            cursor.addRow(new Object[]{"html-ime-fixture", "HTML IME fixture", "", "", "USER_IMPORT", "APP_PRIVATE", 1,
                    0, "READY", "INSTALLED", "", 0, 0, "[]", 1});
            cursor.moveToFirst(); dictionary = new DictionaryRepository.Dictionary(cursor);
        }
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        float density = context.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(Math.round(320 * density), Math.round(360 * density),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 0; params.y = Math.round(40 * density);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        AtomicReference<FrameLayout> root = new AtomicReference<>();
        AtomicReference<DictionaryEntryView> web = new AtomicReference<>();
        AtomicReference<DictionaryEntryView> editing = new AtomicReference<>();
        int[] back = {0}; CountDownLatch loaded = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> {
                FrameLayout host = new FrameLayout(context); root.set(host);
                DictionaryEntryView view = new DictionaryEntryView(context, repository, dictionary, Collections.singletonList(
                        "<input id='token' type='text' placeholder='输入Token' style='display:block;height:36px'>"
                        + "<textarea id='area' style='display:block;height:44px'></textarea>"
                        + "<div id='content' contenteditable='true' style='height:40px;border:1px solid'>Editable</div>"
                        + "<input id='readonly' readonly value='read only' style='display:block;height:36px'>"
                        + "<p id='plain' tabindex='0'>普通释义</p>"), new DictionaryEntryView.Navigation() {
                    public void lookup(String word) { throw new AssertionError("No navigation in editing fixture"); }
                    public void editingChanged(DictionaryEntryView owner, boolean active) {
                        if (active) { editing.set(owner); params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; }
                        else if (editing.get() == owner) { editing.set(null); params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; }
                        windows.updateViewLayout(host, params);
                    }
                    public boolean closeOnBack() {
                        back[0]++; web.get().stopEditing(); windows.removeView(host); return true;
                    }
                }, loaded::countDown);
                web.set(view); host.addView(view, new FrameLayout.LayoutParams(-1, -1));
                windows.addView(host, params);
            });
            check(loaded.await(10, TimeUnit.SECONDS), "Original HTML fields finish loading");
            check((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0, "Loading does not focus a browsing window");
            tap(instrumentation, web.get(), "token");
            awaitEditing(instrumentation, web.get(), editing);
            check((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0, "Text field click enables temporary window focus");
            if (Build.VERSION.SDK_INT >= 30) {
                AtomicReference<Boolean> visibleIme = new AtomicReference<>(false);
                for (int n = 0; n < 100 && !visibleIme.get(); n++) {
                    instrumentation.runOnMainSync(() -> visibleIme.set(web.get().getRootWindowInsets() != null
                            && web.get().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())));
                    if (!visibleIme.get()) Thread.sleep(40);
                }
                check(visibleIme.get(), "Plain HTML Token input shows the system keyboard");
            }
            AtomicReference<InputConnection> connection = new AtomicReference<>(); EditorInfo info = new EditorInfo();
            instrumentation.runOnMainSync(() -> connection.set(web.get().onCreateInputConnection(info)));
            check(connection.get() != null, "HTML text input supplies a native IME connection");
            check((info.inputType & android.text.InputType.TYPE_MASK_VARIATION) != android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    "Ordinary Token input is not converted to a password");
            instrumentation.runOnMainSync(() -> connection.get().commitText("qa-plain-token", 1));
            String value = "";
            for (int n = 0; n < 50 && !"\"qa-plain-token\"".equals(value); n++) {
                value = javascript(instrumentation, web.get(), "document.getElementById('token').value"); Thread.sleep(30);
            }
            check("\"qa-plain-token\"".equals(value), "Native IME commits plain text to the original HTML field");
            for (String id : new String[]{"area", "content"}) {
                tap(instrumentation, web.get(), id); awaitEditing(instrumentation, web.get(), editing);
                instrumentation.runOnMainSync(() -> check(web.get().onCheckIsTextEditor(), id + " is a native text editor"));
            }
            tap(instrumentation, web.get(), "plain");
            for (int n = 0; n < 50 && editing.get() != null; n++) Thread.sleep(30);
            check(editing.get() == null && (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0,
                    "Clicking ordinary text releases temporary editing focus");
            tap(instrumentation, web.get(), "readonly"); Thread.sleep(150);
            check(editing.get() == null, "Readonly HTML does not start IME editing");
            tap(instrumentation, web.get(), "token"); awaitEditing(instrumentation, web.get(), editing);
            instrumentation.runOnMainSync(() -> web.get().onKeyPreIme(KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
            instrumentation.waitForIdleSync();
            check(back[0] == 1 && !root.get().isAttachedToWindow(), "One editor Back closes the containing lookup window");
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (web.get() != null) web.get().close();
                if (root.get() != null && root.get().isAttachedToWindow()) windows.removeView(root.get());
            });
        }
    }
    private void awaitEditing(Instrumentation test, DictionaryEntryView web, AtomicReference<DictionaryEntryView> editing) throws Exception {
        for (int n = 0; n < 100; n++) {
            AtomicReference<Boolean> active = new AtomicReference<>(false);
            test.runOnMainSync(() -> active.set(editing.get() == web && web.hasWindowFocus() && web.onCheckIsTextEditor()));
            if (active.get()) { check(true, "Touched HTML editor receives window focus"); return; }
            Thread.sleep(40);
        }
        throw new AssertionError("Touched HTML editor did not receive native/window focus: " + diagnostics(test, web, editing));
    }
    private static String diagnostics(Instrumentation test, DictionaryEntryView web, AtomicReference<DictionaryEntryView> editing) throws Exception {
        String dom = javascript(test, web, "(function(){var e=document.activeElement;return [e&&e.tagName,e&&e.type,e&&e.id,"
                + "document.hasFocus(),scrollX,scrollY,innerWidth,window.visualViewport&&visualViewport.width];})()");
        AtomicReference<String> nativeState = new AtomicReference<>();
        test.runOnMainSync(() -> {
            StringBuilder state = new StringBuilder("owner=").append(editing.get() == web)
                    .append(" hasFocus=").append(web.hasFocus()).append(" isFocused=").append(web.isFocused())
                    .append(" windowFocus=").append(web.hasWindowFocus()).append(" textEditor=").append(web.onCheckIsTextEditor())
                    .append(" size=").append(web.getWidth()).append('x').append(web.getHeight())
                    .append(" nativeScroll=").append(web.getScrollX()).append(',').append(web.getScrollY());
            for (String name : new String[]{"editing", "editingProbe", "latestTouch", "touches", "touchEditorPending", "focusingEditor", "editorTouchX", "editorTouchY", "editorTouchWidth"}) {
                try {
                    java.lang.reflect.Field field = DictionaryEntryView.class.getDeclaredField(name); field.setAccessible(true);
                    state.append(' ').append(name).append('=').append(field.get(web));
                } catch (ReflectiveOperationException error) { state.append(" reflection=").append(error.getClass().getSimpleName()); }
            }
            nativeState.set(state.toString());
        });
        return nativeState.get() + " DOM(tag,type,id,focus,scrollX,scrollY,innerWidth,visualWidth)=" + dom;
    }
    private static void tap(Instrumentation test, DictionaryEntryView web, String id) throws Exception {
        JSONArray point = new JSONArray(javascript(test, web, "(function(){var r=document.getElementById('" + id
                + "').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth];})()"));
        test.runOnMainSync(() -> {
            float scale = web.getWidth() / (float)point.optDouble(2);
            float x = (float)point.optDouble(0) * scale, y = (float)point.optDouble(1) * scale;
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, x, y, 0);
            try { web.dispatchTouchEvent(down); web.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
        });
    }
    private static String javascript(Instrumentation test, DictionaryEntryView web, String source) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        test.runOnMainSync(() -> web.evaluateJavascript(source, value -> { result.set(value); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("HTML editing fixture JavaScript timeout");
        return result.get();
    }
}
