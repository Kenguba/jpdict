package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.GridLayout;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Preference and model routing checks independent of Play services model downloads. */
final class OcrLanguageValidation {
    private int checks;
    private void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static String run(Instrumentation instrumentation) throws Exception {
        OcrLanguageValidation test = new OcrLanguageValidation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("ocr_language_validation", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        try {
            OcrLanguageSettings settings = new OcrLanguageSettings(preferences);
            test.check(settings.language() == OcrLanguageSettings.Language.MIXED, "New OCR settings default to mixed scripts");
            for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
                settings.setLanguage(language);
                test.check(settings.language() == language, "Selection is immediately shared in memory");
                test.check(new OcrLanguageSettings(preferences).language() == language, "Recreated settings restore the persisted selection");
            }
            int[] updates={0};
            OcrLanguageSettings.Listener observer=()->updates[0]++;
            instrumentation.runOnMainSync(()->{
                settings.observe(observer);settings.observe(observer);
                settings.setLanguage(OcrLanguageSettings.Language.JAPANESE);
                test.check(updates[0]==1,"Shared preference changes notify a mounted observer once on the UI thread");
                settings.setLanguage(OcrLanguageSettings.Language.JAPANESE);
                test.check(updates[0]==1,"Choosing the same language cannot restart every mounted input");
                settings.removeObserver(observer);settings.setLanguage(OcrLanguageSettings.Language.ENGLISH);
                test.check(updates[0]==1,"Detached observers receive no further shared language updates");
            });
            try {
                settings.setLanguage(null);
                throw new AssertionError("Null OCR language accepted");
            } catch (IllegalArgumentException expected) {
                test.check(settings.language() == OcrLanguageSettings.Language.ENGLISH, "Invalid selection does not corrupt shared state");
            }
            preferences.edit().putString("language", "future-mode").commit();
            test.check(new OcrLanguageSettings(preferences).language() == OcrLanguageSettings.Language.MIXED, "Unknown cached values safely fall back to mixed");
            preferences.edit().putInt("language", 1).commit();
            test.check(new OcrLanguageSettings(preferences).language() == OcrLanguageSettings.Language.MIXED, "Wrong cached value types safely fall back to mixed");
            test.check(OcrLanguageSettings.get(context) == OcrLanguageSettings.get(new ContextWrapper(context)), "Activity and Service contexts share one preference source");
            test.route(OcrLanguageSettings.Language.ENGLISH, MultilingualOcrRecognizer.Script.LATIN);
            test.route(OcrLanguageSettings.Language.JAPANESE, MultilingualOcrRecognizer.Script.JAPANESE);
            test.route(OcrLanguageSettings.Language.KOREAN, MultilingualOcrRecognizer.Script.KOREAN);
            test.check(Arrays.equals(MultilingualOcrRecognizer.scriptsFor(OcrLanguageSettings.Language.MIXED),
                    MultilingualOcrRecognizer.Script.values()), "Mixed recognition retains all four script models");
            test.check(Arrays.equals(OcrLanguageSettings.Language.values(), new OcrLanguageSettings.Language[] {
                    OcrLanguageSettings.Language.MIXED, OcrLanguageSettings.Language.KOREAN,
                    OcrLanguageSettings.Language.JAPANESE, OcrLanguageSettings.Language.ENGLISH }), "Menu order is mixed, Korean, Japanese, English");
            test.dispatch(instrumentation, settings);
            test.clipboardInputs();
            test.menuGesture(instrumentation);
        } finally { preferences.edit().clear().commit(); }
        return "OCR language: " + test.checks + " assertions passed\n";
    }

    private void route(OcrLanguageSettings.Language language, MultilingualOcrRecognizer.Script script) {
        check(Arrays.equals(MultilingualOcrRecognizer.scriptsFor(language), new MultilingualOcrRecognizer.Script[] { script }),
                language.label + " selects exactly one OCR model");
    }

    private void dispatch(Instrumentation test, OcrLanguageSettings settings) throws Exception {
        int[] calls = new int[4], closes = new int[4];
        String[] fullText = {"Latin complete original text", "中文词典原文", "日本語の原文", "한국어 원문"};
        TextRecognizer[] clients = new TextRecognizer[4];
        for (int i = 0; i < clients.length; i++) {
            final int index = i;
            clients[i] = (TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                    new Class<?>[] { TextRecognizer.class }, (proxy, method, arguments) -> {
                        if ("process".equals(method.getName())) {
                            calls[index]++;
                            return Tasks.forResult(new Text(fullText[index], Collections.emptyList()));
                        }
                        if ("close".equals(method.getName())) { closes[index]++; return null; }
                        return null;
                    });
        }
        MultilingualOcrRecognizer recognizer = new MultilingualOcrRecognizer(clients[0], clients[1], clients[2], clients[3], settings);
        Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try {
            for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
                settings.setLanguage(language);
                Arrays.fill(calls, 0);
                CountDownLatch complete = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
                test.runOnMainSync(() -> recognizer.recognize(image, new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String value) { result.set(value); complete.countDown(); }
                    public void onFailure(Exception error) { complete.countDown(); }
                }));
                check(complete.await(5, TimeUnit.SECONDS), "Text recognition dispatch completes");
                checkCalls(language, calls, 1);
                if (language != OcrLanguageSettings.Language.MIXED)
                    check(fullText[selectedScript(language)].equals(result.get()), "Fixed-language recognition returns the complete model text verbatim");

                Arrays.fill(calls, 0); CountDownLatch regions = new CountDownLatch(1);
                test.runOnMainSync(() -> recognizer.recognizeRegions(image, new MultilingualOcrRecognizer.RegionsCallback() {
                    public void onSuccess(java.util.List<MultilingualOcrRecognizer.Region> value) { regions.countDown(); }
                    public void onFailure(Exception error) { regions.countDown(); }
                }));
                check(regions.await(5, TimeUnit.SECONDS), "Region recognition dispatch completes");
                checkCalls(language, calls, 1);

                Arrays.fill(calls, 0); CountDownLatch refinement = new CountDownLatch(1);
                Rect target = new Rect(4, 4, 24, 24);
                MultilingualOcrRecognizer.Region seed = new MultilingualOcrRecognizer.Region("complete seed text", target);
                test.runOnMainSync(() -> recognizer.recognizeSelection(image, target, seed, 14, 14, 4, new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String value) { result.set(value); refinement.countDown(); }
                    public void onFailure(Exception error) { refinement.countDown(); }
                }));
                check(refinement.await(5, TimeUnit.SECONDS), "Selection refinement dispatch completes");
                checkCalls(language, calls, 2);
                check("complete seed text".equals(result.get()), "Empty refinement retains the full seed text");
            }
            settings.setLanguage(OcrLanguageSettings.Language.KOREAN);
            Arrays.fill(calls, 0); CountDownLatch lockedPreview = new CountDownLatch(1);
            test.runOnMainSync(() -> recognizer.recognizeRegions(image, OcrLanguageSettings.Language.ENGLISH, new MultilingualOcrRecognizer.RegionsCallback() {
                public void onSuccess(java.util.List<MultilingualOcrRecognizer.Region> value) { lockedPreview.countDown(); }
                public void onFailure(Exception error) { lockedPreview.countDown(); }
            }));
            check(lockedPreview.await(5, TimeUnit.SECONDS), "Snapshot preview dispatch completes");
            checkCalls(OcrLanguageSettings.Language.ENGLISH, calls, 1);
            Arrays.fill(calls, 0); CountDownLatch lockedRefine = new CountDownLatch(1);
            Rect lockedTarget = new Rect(4, 4, 24, 24);
            test.runOnMainSync(() -> recognizer.recognizeSelection(image, lockedTarget, null, 14, 14, 4,
                    OcrLanguageSettings.Language.ENGLISH, new MultilingualOcrRecognizer.Callback() {
                        public void onSuccess(String value) { lockedRefine.countDown(); }
                        public void onFailure(Exception error) { lockedRefine.countDown(); }
                    }));
            check(lockedRefine.await(5, TimeUnit.SECONDS), "Snapshot refinement dispatch completes");
            checkCalls(OcrLanguageSettings.Language.ENGLISH, calls, 2);
        } finally {
            test.runOnMainSync(recognizer::close); image.recycle();
        }
        check(Arrays.equals(closes, new int[] {1, 1, 1, 1}), "Lifecycle teardown releases each reused model exactly once");
    }

    private static int selectedScript(OcrLanguageSettings.Language language) {
        return language == OcrLanguageSettings.Language.ENGLISH ? 0
                : language == OcrLanguageSettings.Language.JAPANESE ? 2 : 3;
    }

    private void checkCalls(OcrLanguageSettings.Language language, int[] calls, int count) {
        for (int i = 0; i < calls.length; i++)
            check(calls[i] == (language == OcrLanguageSettings.Language.MIXED || i == selectedScript(language) ? count : 0),
                    language.label + " dispatch invokes only selected models, index=" + i);
    }

    private void clipboardInputs() {
        SearchClipboard.Consumption consumption = new SearchClipboard.Consumption();
        long now = 1000000;
        ClipData copied = ClipData.newPlainText("fixture", "  one\n二  ");
        check("  one\n二  ".equals(consumption.take(copied, now - 1000, now, false)), "Clipboard consumption preserves spaces and complete multiline text");
        check(consumption.take(copied, now - 1000, now, true) == null, "One timestamped copy replaces input only once");
        check(consumption.take(copied, now - 900, now, true) != null, "Copying identical text again has a new copy timestamp");
        check(consumption.take(copied, now - 120001, now, false) == null, "Expired clipboard text remains excluded");
        check(consumption.take(copied, now + 1, now, false) == null, "Future clipboard timestamps remain excluded");
        check(consumption.take(ClipData.newRawUri("fixture", Uri.parse("content://fixture/file")), now, now, false) == null,
                "Clipboard files and URIs are never coerced into search text");
        check(consumption.take(ClipData.newPlainText("fixture", " \n "), now, now, false) == null, "Blank clipboard text is ignored");
        SearchClipboard.Consumption legacy = new SearchClipboard.Consumption();
        check(legacy.take(copied, 0, now, false) != null && legacy.take(copied, 0, now, false) == null,
                "Untimestamped clips are deduplicated across focus restoration");
        check(legacy.take(copied, 0, now, true) != null, "A foreground legacy clipboard change allows a new copy");
    }

    private void menuGesture(Instrumentation test) throws Exception {
        OcrLanguageSettings settings = OcrLanguageSettings.get(test.getTargetContext());
        OcrLanguageSettings.Language previous = settings.language();
        AtomicReference<FloatingSearchView> search = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        List<String> queries = new ArrayList<>(); List<Boolean> privacy = new ArrayList<>();
        List<String> pages = new ArrayList<>();
        WindowManager windows = (WindowManager)test.getTargetContext().getSystemService(Context.WINDOW_SERVICE);
        test.runOnMainSync(() -> {
            try {
                settings.setLanguage(OcrLanguageSettings.Language.ENGLISH);
                FloatingSearchView surface = new FloatingSearchView(test.getTargetContext(), "", false, new FloatingSearchView.Callback() {
                    public void onQueryChanged(String query) {}
                    public void onLookup(String query, boolean privateMode) { queries.add(query); privacy.add(privateMode); }
                    public void onPrivateModeChanged(boolean privateMode) {}
                    public void onEditingChanged(boolean editing) {}
                    public void onOpenPage(String page, boolean privateMode) { pages.add(page); }
                    public void onClose() {}
                });
                search.set(surface);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                windows.addView(surface, params);
            } catch (Throwable error) { failure.set(error); }
        });
        // View.getLocationOnScreen is only meaningful after the window attaches.
        // A detached, manually measured fixture returns (0,0) for every child.
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                FloatingSearchView surface = search.get();
                check(surface.isAttachedToWindow() && surface.getWidth() > 0, "Language gesture fixture has a real laid-out window");
                check("英语".contentEquals(((TextView)field(surface, "languageLabel")).getText()),
                        "A reopened search displays the cached language instead of the fixed Fast label");
                settings.setLanguage(OcrLanguageSettings.Language.JAPANESE);
                check("日语".contentEquals(((TextView)field(surface,"languageLabel")).getText()),
                        "An external OCR language choice refreshes an already-mounted homepage input without focus changes");
                settings.setLanguage(OcrLanguageSettings.Language.ENGLISH);
                check(new OcrLanguageSettings(test.getTargetContext().getSharedPreferences("ocr_language",Context.MODE_PRIVATE)).language()==OcrLanguageSettings.Language.ENGLISH,
                        "The externally shared choice survives recreating the preference facade");
                int width = surface.getWidth(), height = surface.getHeight();
                keyboardLanguages(surface, false, queries);
                View button = (View)field(surface, "modelButton");
                int[] anchor = new int[2]; button.getLocationOnScreen(anchor);
                float x = anchor[0] + button.getWidth()/2f, y = anchor[1] + button.getHeight()/2f;
                button.performClick();
                check(((FloatingSearchState)field(surface, "state")).getMenu() == FloatingSearchState.Menu.MODEL,
                        "Ordinary tap preserves the existing model menu");
                send(surface, MotionEvent.ACTION_DOWN, x, y);
                check(button.performLongClick(), "Long press opens the native OCR language menu");
                layout(surface, width, height);
                Method position = FloatingSearchView.class.getDeclaredMethod("positionMenu"); position.setAccessible(true); position.invoke(surface);
                layout(surface, width, height);
                LinearLayout[] rows = (LinearLayout[])field(surface, "languageRows");
                check(rows.length == 4, "All four language rows are present");
                View english = rows[OcrLanguageSettings.Language.ENGLISH.ordinal()];
                int[] rowPosition = new int[2]; english.getLocationOnScreen(rowPosition);
                float targetX = rowPosition[0] + english.getWidth()/2f, targetY = rowPosition[1] + english.getHeight()/2f;
                send(surface, MotionEvent.ACTION_MOVE, targetX, targetY);
                String diagnostic = " root=" + describe(surface) + " button=" + describe(button)
                        + " menu=" + describe((View)field(surface, "menuSurface")) + " english=" + describe(english)
                        + " target=" + targetX + "," + targetY + " drag=" + field(surface, "languageDrag")
                        + " routed=" + (field(surface, "menuAnchorTouchTarget") == button)
                        + " hover=" + field(surface, "hoveredLanguage");
                send(surface, MotionEvent.ACTION_UP, targetX, targetY);
                check(settings.language() == OcrLanguageSettings.Language.ENGLISH, "Held slide and release selects English; "
                        + "actual=" + settings.language() + diagnostic);
                FloatingSearchState state = (FloatingSearchState)field(surface, "state");
                check(state.getMenu() == FloatingSearchState.Menu.NONE, "Selection closes the language menu");
                button.performLongClick();
                rows = (LinearLayout[])field(surface, "languageRows");
                rows[OcrLanguageSettings.Language.KOREAN.ordinal()].performClick();
                check(settings.language() == OcrLanguageSettings.Language.KOREAN, "Language rows also accept ordinary clicks");
                send(surface, MotionEvent.ACTION_DOWN, x, y);
                button.performLongClick();
                send(surface, MotionEvent.ACTION_CANCEL, x, y);
                check(settings.language() == OcrLanguageSettings.Language.KOREAN && state.getMenu() == FloatingSearchState.Menu.NONE,
                        "Canceled language gestures close without changing the preference");
                check(queries.isEmpty(), "Language selection does not submit a query");
                check(pages.isEmpty(), "Language selection does not navigate");
                shortcuts(surface, queries, privacy, pages);
                clipboardAndCurrentCandidate(surface, queries, privacy);
                keyboardLanguages(surface, true, queries);
            } catch (Throwable error) { failure.set(error); }
            finally {
                if (search.get() != null) {
                    if (search.get().isAttachedToWindow()) windows.removeViewImmediate(search.get());
                    search.get().dispose();
                }
                settings.setLanguage(previous);
            }
        });
        if (failure.get() != null) throw new AssertionError("Language menu gesture", failure.get());
    }

    private void shortcuts(FloatingSearchView surface, List<String> queries, List<Boolean> privacy, List<String> pages) throws Exception {
        LinearLayout pills = (LinearLayout)((HorizontalScrollView)field(surface, "recommendations")).getChildAt(0);
        String[] labels = {"搜索词库", "词库管理", "悬浮按钮设置", "设置"};
        check(pills.getChildCount() == labels.length, "Four search shortcuts are present");
        for (int i = 0; i < labels.length; i++)
            check(labels[i].contentEquals(((TextView)((LinearLayout)pills.getChildAt(i)).getChildAt(1)).getText()),
                    "Shortcut order at position " + i);
        View search = pills.getChildAt(0), send = (View)field(surface, "actionButton");
        EditText input = (EditText)field(surface, "input");
        search.performClick(); send.performClick();
        check(queries.isEmpty(), "Empty input from either button never queries");
        input.setText("  avocado  ");
        search.performClick(); send.performClick(); input.onEditorAction(EditorInfo.IME_ACTION_SEND);
        check(queries.equals(Arrays.asList("avocado", "avocado", "avocado")), "Shortcut, composer and IME submit the same trimmed query");
        check(privacy.equals(Arrays.asList(false, false, false)), "All submit entrances preserve normal query state");
        ((View)field(surface, "privateButton")).performClick();
        input.setText(" "); search.performClick(); send.performClick();
        check(queries.size() == 3, "Private empty input never queries through either button");
        input.setText("日本語"); search.performClick(); send.performClick();
        check(queries.subList(3, 5).equals(Arrays.asList("日本語", "日本語"))
                && privacy.subList(3, 5).equals(Arrays.asList(true, true)), "Both buttons preserve the private query state");
        pills.getChildAt(3).performClick();
        ((View)field(field(surface, "drawer"), "settingsButton")).performClick();
        check(pages.equals(Arrays.asList(FloatingSearchRoutes.PAGE_SETTINGS, FloatingSearchRoutes.PAGE_SETTINGS)),
                "Shortcut settings and drawer settings use the same existing page route");
    }

    private void clipboardAndCurrentCandidate(FloatingSearchView surface, List<String> queries, List<Boolean> privacy) throws Exception {
        check(!(Boolean)field(surface, "clipboardListening"), "Non-focusable QA fixture never listens to or reads the real clipboard");
        EditText input = (EditText)field(surface, "input");
        input.setText("old text");
        SearchDraftChips drafts = (SearchDraftChips)field(surface, "draftChips");
        drafts.replaceFromClipboard("  new line\n日本語  ");
        String complete = "  new line\n日本語  ";
        check(complete.contentEquals(input.getText()), "Copied text replaces the complete input and preserves its original whitespace");
        SearchDraftState displaced = (SearchDraftState)field(drafts, "state");
        check(displaced.list().size() == 1 && displaced.list().get(0).text.equals("old text"),
                "The displaced original input remains in a draggable draft chip");
        check(input.getSelectionStart() == input.length(), "Clipboard replacement places the cursor after the copied text");
        Method show = FloatingSearchView.class.getDeclaredMethod("showSuggestions", List.class); show.setAccessible(true);
        List<String> locals = new ArrayList<>(); for (int i = 0; i < 12; i++) locals.add("local" + i);
        show.invoke(surface, locals);
        GridLayout grid = (GridLayout)field(surface, "suggestionWords");
        TextView full = (TextView)grid.getChildAt(0);
        check(grid.getChildCount() == 13 && complete.contentEquals(full.getText()), "All local suggestions retain an extra complete multiline input candidate");
        check(full.getMaxLines() == 2 && full.getLayoutParams().width == -1, "Long current input occupies a bounded full-width row above the grid");
        android.animation.ValueAnimator growth = (android.animation.ValueAnimator)field(surface, "suggestionHeightAnimator");
        if (growth != null) growth.end();
        check(((View)field(surface, "suggestionArea")).getLayoutParams().height >= grid.getMeasuredHeight(), "The suggestion area grows to fit the extra row");
        full.performClick();
        check(queries.get(queries.size() - 1).equals(complete.trim()) && privacy.get(privacy.size() - 1),
                "Clicking the full input candidate submits every original line and preserves privacy");
        check(complete.contentEquals(input.getText()), "Candidate lookup leaves the complete whitespace-preserving draft intact");
        input.setText("a"); show.invoke(surface, locals);
        check(grid.getChildCount() == 13 && ((TextView)grid.getChildAt(0)).getText().toString().equals("a")
                && grid.getChildAt(0).getLayoutParams().width == 0, "Short current input adds one normal grid candidate");
        input.setText("local0"); show.invoke(surface, locals);
        check(grid.getChildCount() == 12, "An exact local candidate is not duplicated by the full input candidate");
    }

    private void keyboardLanguages(FloatingSearchView surface, boolean privateMode, List<String> queries) throws Exception {
        EditText input = (EditText)field(surface, "input");
        if (!privateMode && Build.VERSION.SDK_INT >= 24)
            check(input.getImeHintLocales() != null && "en".equals(input.getImeHintLocales().get(0).getLanguage()),
                    "A cached English OCR preference initializes the English keyboard hint");
        Method select = FloatingSearchView.class.getDeclaredMethod("selectLanguage", OcrLanguageSettings.Language.class); select.setAccessible(true);
        for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
            select.invoke(surface, language);
            check(language.label.contentEquals(((TextView)field(surface, "languageLabel")).getText()),
                    "Selecting " + language.label + " immediately updates the visible chip label");
            EditorInfo info = new EditorInfo(); input.onCreateInputConnection(info);
            if (Build.VERSION.SDK_INT >= 24) {
                check(language == OcrLanguageSettings.Language.ENGLISH
                                ? info.hintLocales != null && "en".equals(info.hintLocales.get(0).getLanguage())
                                : info.hintLocales == null && input.getImeHintLocales() == null,
                        "Switching OCR language updates or clears the actual EditorInfo locale hint");
            }
            check((info.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND
                            && (info.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0
                            && (info.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0
                            && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                            && (info.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) == 0
                            && (info.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
                    "Language preference preserves Send/multiline/fullscreen flags and does not force ASCII");
            if (privateMode && Build.VERSION.SDK_INT >= 26)
                check((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "Private keyboard learning preference survives language changes");
            if (!privateMode && language == OcrLanguageSettings.Language.JAPANESE) {
                FloatingSearchView reopened = new FloatingSearchView(surface.getContext(), "", false, new FloatingSearchView.Callback() {
                    public void onQueryChanged(String query) {}
                    public void onLookup(String query, boolean privateMode) {}
                    public void onPrivateModeChanged(boolean privateMode) {}
                    public void onEditingChanged(boolean editing) {}
                    public void onOpenPage(String page, boolean privateMode) {}
                    public void onClose() {}
                });
                try {
                    check("日语".contentEquals(((TextView)field(reopened, "languageLabel")).getText()),
                            "Closing and recreating search restores the selected Japanese label");
                    OcrLanguageSettings restored = new OcrLanguageSettings(surface.getContext()
                            .getSharedPreferences("ocr_language", Context.MODE_PRIVATE));
                    check(restored.language() == language, "A fresh preference reader restores the selected Japanese mode");
                } finally { reopened.dispose(); }
            }
        }
        if (privateMode) {
            input.setText("中文 / 日本語 / 한국어"); input.onEditorAction(EditorInfo.IME_ACTION_SEND);
            check(queries.get(queries.size() - 1).equals("中文 / 日本語 / 한국어"), "The English keyboard hint still accepts and submits unrestricted Unicode text");
        } else select.invoke(surface, OcrLanguageSettings.Language.MIXED);
    }

    private static String describe(View view) {
        if (view == null) return "null";
        int[] position = new int[2]; view.getLocationOnScreen(position);
        return "(" + position[0] + "," + position[1] + ";" + view.getWidth() + "x" + view.getHeight()
                + ";attached=" + view.isAttachedToWindow() + ")";
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void send(View view, int action, float screenX, float screenY) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, screenX, screenY, 0);
        event.offsetLocation(-origin[0], -origin[1]);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
}
