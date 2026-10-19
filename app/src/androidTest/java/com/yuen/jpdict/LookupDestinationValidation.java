package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Destination persistence, exact external Intent and mounted menu/joystick checks. */
final class LookupDestinationValidation {
    private int checks;

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static String run(Instrumentation instrumentation) throws Exception {
        LookupDestinationValidation test = new LookupDestinationValidation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("lookup_destination_validation", Context.MODE_PRIVATE);
        checkCommit(preferences.edit().clear());
        try {
            test.persistence(preferences);
            test.invalidPreferences(preferences);
            test.observers(instrumentation, preferences);
            test.intents();
            test.launches(instrumentation);
            test.check(LookupDestinationSettings.get(context) == LookupDestinationSettings.get(new ContextWrapper(context)),
                    "Activity and Service contexts share the same destination facade");
            test.menuAndJoystick(instrumentation);
        } finally { checkCommit(preferences.edit().clear()); }
        return "Lookup destination: " + test.checks + " assertions passed\n";
    }

    private void persistence(SharedPreferences preferences) {
        LookupDestinationSettings settings = new LookupDestinationSettings(preferences);
        check(settings.model() == LookupDestinationSettings.Model.FAST && !settings.isThirdParty(),
                "Fresh installations default to the internal Fast route");
        settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.THIRD_PARTY && settings.isThirdParty(),
                "The joystick action switches the current destination to third party");
        settings = new LookupDestinationSettings(preferences);
        check(settings.isThirdParty(), "Third-party selection survives recreating the preference facade");
        settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.FAST && !settings.isThirdParty(),
                "First return to System restores Fast");
        for (LookupDestinationSettings.Model internal : LookupDestinationSettings.Model.values()) {
            if (internal == LookupDestinationSettings.Model.THIRD_PARTY) continue;
            settings.select(internal);
            check(settings.model() == internal && !settings.isThirdParty(), "Every internal model uses the internal route: " + internal);
            settings = new LookupDestinationSettings(preferences);
            check(settings.model() == internal, "Internal selection persists across recreation: " + internal);
            settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
            settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
            settings = new LookupDestinationSettings(preferences);
            check(settings.isThirdParty(), "Recreated third-party route remains selected: " + internal);
            settings.toggleSystem();
            check(settings.model() == internal && !settings.isThirdParty(),
                    "Repeated third-party selection cannot overwrite the last internal model: " + internal);
        }
        settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
        settings.select(LookupDestinationSettings.Model.EXPERT);
        settings.toggleSystem(); settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.EXPERT,
                "A newer internal menu choice replaces the remembered System destination");
        try {
            settings.select(null);
            throw new AssertionError("Null destination accepted");
        } catch (IllegalArgumentException expected) {
            check(settings.model() == LookupDestinationSettings.Model.EXPERT
                            && new LookupDestinationSettings(preferences).model() == LookupDestinationSettings.Model.EXPERT,
                    "Rejected null selection preserves both current and persisted destination");
        }
    }

    private void invalidPreferences(SharedPreferences preferences) {
        checkCommit(preferences.edit().clear().putString("model", "future-model").putString("system_model", "expert"));
        LookupDestinationSettings settings = new LookupDestinationSettings(preferences);
        check(settings.model() == LookupDestinationSettings.Model.FAST && !settings.isThirdParty(),
                "An unknown selected model safely falls back to Fast");
        settings.toggleSystem(); settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.FAST,
                "Unknown selected values do not revive an unrelated previous internal model");
        checkCommit(preferences.edit().clear().putInt("model", 7).putBoolean("system_model", true));
        settings = new LookupDestinationSettings(preferences);
        check(settings.model() == LookupDestinationSettings.Model.FAST, "Wrong selected preference types cannot crash startup");
        for (String remembered : new String[] {"future-model", "third_party"}) {
            checkCommit(preferences.edit().clear().putString("model", "third_party").putString("system_model", remembered));
            settings = new LookupDestinationSettings(preferences);
            check(settings.isThirdParty(), "A damaged remembered model preserves the selected third-party destination");
            settings.toggleSystem();
            check(settings.model() == LookupDestinationSettings.Model.FAST,
                    "System always restores a usable internal model when its cache is " + remembered);
        }
        checkCommit(preferences.edit().clear().putString("model", "third_party").putLong("system_model", 19));
        settings = new LookupDestinationSettings(preferences); settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.FAST,
                "A wrong remembered preference type restores Fast without a class cast failure");
        checkCommit(preferences.edit().clear().putString("model", "third_party"));
        settings = new LookupDestinationSettings(preferences); settings.toggleSystem();
        check(settings.model() == LookupDestinationSettings.Model.FAST, "Missing remembered System state restores Fast");
    }

    private void observers(Instrumentation instrumentation, SharedPreferences preferences) throws Exception {
        checkCommit(preferences.edit().clear());
        LookupDestinationSettings settings = new LookupDestinationSettings(preferences);
        int[] updates = {0};
        List<LookupDestinationSettings.Model> observed = new ArrayList<>();
        LookupDestinationSettings.Listener listener = () -> {
            check(Looper.myLooper() == Looper.getMainLooper(), "Mounted destination observers run on the UI thread");
            check(settings.model() == new LookupDestinationSettings(preferences).model(),
                    "Observers see the committed selection, so menu and OCR never read different routes");
            updates[0]++; observed.add(settings.model());
        };
        instrumentation.runOnMainSync(() -> {
            settings.observe(listener); settings.observe(listener);
            check(updates[0] == 0, "Subscribing does not manufacture a destination change");
            settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
            check(updates[0] == 1, "Duplicate subscriptions receive one notification per actual change");
            settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
            check(updates[0] == 1, "Selecting the current model does not restart mounted interfaces");
            settings.toggleSystem();
            check(updates[0] == 2 && observed.equals(Arrays.asList(
                            LookupDestinationSettings.Model.THIRD_PARTY, LookupDestinationSettings.Model.FAST)),
                    "Both directions publish the shared route immediately");
            settings.removeObserver(listener); settings.select(LookupDestinationSettings.Model.EXPERT);
            check(updates[0] == 2, "A detached observer receives no later changes");
        });
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LookupDestinationSettings.Listener backgroundListener = () -> {
            try {
                check(Looper.myLooper() == Looper.getMainLooper(), "Worker updates are marshalled to the UI thread");
                check(settings.isThirdParty(), "Worker notification reads the new shared destination");
            } catch (Throwable error) { failure.set(error); }
            finally { received.countDown(); }
        };
        instrumentation.runOnMainSync(() -> settings.observe(backgroundListener));
        try {
            settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
            check(received.await(5, TimeUnit.SECONDS), "A background destination update reaches the mounted UI");
            if (failure.get() != null) throw new AssertionError("Background destination notification", failure.get());
        } finally { instrumentation.runOnMainSync(() -> settings.removeObserver(backgroundListener)); }
        // Hold the main queue until a worker posts, then detach before that post runs.
        int[] staleUpdates = {0};
        instrumentation.runOnMainSync(() -> {
            LookupDestinationSettings.Listener detached = () -> staleUpdates[0]++;
            settings.observe(detached);
            Thread worker = new Thread(() -> settings.select(LookupDestinationSettings.Model.FAST), "destination-detach-check");
            worker.start();
            try { worker.join(5000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            check(!worker.isAlive(), "Destination persistence never waits for the UI observer to execute");
            settings.removeObserver(detached);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() ->
                check(staleUpdates[0] == 0, "Queued notifications do not call an observer detached before delivery"));
    }

    private void intents() {
        String query = "  日本語 / café + a&b?x=#fragment;end;S.EXTRA_QUERY=wrong\n韩语 한국어 😀  ";
        Intent intent = ThirdPartyLookup.intent(query);
        check("colordict.intent.action.SEARCH".equals(intent.getAction()), "The external action matches the requested ColorDict protocol");
        check(Collections.singleton(Intent.CATEGORY_DEFAULT).equals(intent.getCategories()), "The external route has exactly the default category");
        check("text/plain".equals(intent.getType()) && "eudic:peek".equals(intent.getDataString()),
                "The route retains both the eudic scheme and text MIME type");
        check(new ComponentName("com.eusoft.trans.en", "com.eusoft.dict.activity.dict.LightpeekActivity").equals(intent.getComponent()),
                "The route addresses the requested Eudic activity explicitly");
        check(query.equals(intent.getStringExtra("EXTRA_QUERY")),
                "Whitespace, non-ASCII, emoji and Intent/URI punctuation reach EXTRA_QUERY unchanged");
        check(intent.getExtras() != null && intent.getExtras().size() == 1,
                "Query punctuation cannot inject additional Intent extras");
        check((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0, "The explicit destination can launch from an OCR Service context");
        Intent second = ThirdPartyLookup.intent("second query");
        intent.putExtra("EXTRA_QUERY", "changed");
        check(second != intent && "second query".equals(second.getStringExtra("EXTRA_QUERY"))
                        && "eudic:peek".equals(second.getDataString()),
                "Each lookup has an independent Intent and never embeds the word into its URI");
    }

    private void menuAndJoystick(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        LookupDestinationSettings settings = LookupDestinationSettings.get(context);
        SharedPreferences preferences = context.getSharedPreferences("lookup_destination", Context.MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll();
        LookupDestinationSettings.Model previous = settings.model();
        Object remembered = saved.get("system_model");
        LookupDestinationSettings.Model restoredInternal = previous == LookupDestinationSettings.Model.THIRD_PARTY
                ? LookupDestinationSettings.Model.fromStored(remembered instanceof String ? (String)remembered : "fast") : previous;
        LookupDestinationSettings.Model previousInternal = restoredInternal == LookupDestinationSettings.Model.THIRD_PARTY
                ? LookupDestinationSettings.Model.FAST : restoredInternal;
        AtomicReference<FloatingSearchView> search = new AtomicReference<>();
        AtomicReference<OcrJoystickView> joystick = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<String> queries = new ArrayList<>();
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        instrumentation.runOnMainSync(() -> {
            try {
                settings.select(LookupDestinationSettings.Model.FAST);
                FloatingSearchView surface = new FloatingSearchView(context, "", false, new FloatingSearchView.Callback() {
                    public void onQueryChanged(String query) {}
                    public void onLookup(String query, boolean privateMode) { queries.add(query); }
                    public void onPrivateModeChanged(boolean privateMode) {}
                    public void onEditingChanged(boolean editing) {}
                    public void onOpenPage(String page, boolean privateMode) {}
                    public void onClose() {}
                });
                search.set(surface);
                windows.addView(surface, overlayParams());
                OcrJoystickView ring = new OcrJoystickView(context, new Rect(280, 280, 324, 324));
                joystick.set(ring); windows.addView(ring, overlayParams());
            } catch (Throwable error) { failure.set(error); }
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                FloatingSearchView surface = search.get();
                check(surface.isAttachedToWindow() && surface.getWidth() > 0, "Destination menu fixture is mounted in a real window");
                View button = (View)field(surface, "modelButton"); button.performClick();
                Map<?, ?> rows = (Map<?, ?>)field(surface, "modelRows");
                View fast = (View)rows.get(LookupDestinationSettings.Model.FAST);
                View third = (View)rows.get(LookupDestinationSettings.Model.THIRD_PARTY);
                check(fast != null && third != null, "The model menu offers Fast and Third Party");
                check(fast.isSelected() && !third.isSelected(), "Only Fast is selected for the default System route");
                check(labels(joystick.get()).contains("第三方") && !labels(joystick.get()).contains("取消"),
                        "The mounted joystick paints Third Party as its next action");
                third.performClick();
                check(settings.isThirdParty(), "Selecting Third Party in the homepage changes the shared OCR destination");
                check(((FloatingSearchState)field(surface, "state")).getMenu() == FloatingSearchState.Menu.NONE,
                        "Choosing a destination closes the model menu");
                check(labels(joystick.get()).contains("系统") && !labels(joystick.get()).contains("第三方"),
                        "An already-mounted joystick immediately paints System after the menu changes");
                button.performClick(); rows = (Map<?, ?>)field(surface, "modelRows");
                fast = (View)rows.get(LookupDestinationSettings.Model.FAST); third = (View)rows.get(LookupDestinationSettings.Model.THIRD_PARTY);
                check(third.isSelected() && !fast.isSelected(), "Reopening the menu restores mutually exclusive Third Party selection");
                settings.toggleSystem();
                check(fast.isSelected() && !third.isSelected(), "A joystick-style shared toggle refreshes an already-open model menu");
                settings.toggleSystem();
                check(third.isSelected() && !fast.isSelected(), "Shared updates refresh both directions without reopening the menu");
                fast.performClick();
                check(settings.model() == LookupDestinationSettings.Model.FAST && !settings.isThirdParty(),
                        "Choosing Fast in the menu returns future lookups to the internal destination");
                check(queries.isEmpty(), "Switching destination never submits text or starts OCR by itself");
                serviceRoutes(context, settings);
            } catch (Throwable error) { failure.set(error); }
            finally {
                if (joystick.get() != null && joystick.get().isAttachedToWindow()) windows.removeViewImmediate(joystick.get());
                if (search.get() != null) {
                    if (search.get().isAttachedToWindow()) windows.removeViewImmediate(search.get());
                    search.get().dispose();
                }
                settings.select(previousInternal); settings.select(previous); restore(preferences, saved);
            }
        });
        if (failure.get() != null) throw new AssertionError("Destination menu and joystick", failure.get());
    }

    private void serviceRoutes(Context context, LookupDestinationSettings settings) throws Exception {
        settings.select(LookupDestinationSettings.Model.THIRD_PARTY);
        check(!"com.yuen.jpdict".equals(context.getPackageName()), "History tests require an isolated package");
        SharedPreferences history = context.getSharedPreferences("lookup_history", Context.MODE_PRIVATE);
        for (boolean privacy : new boolean[]{false, true}) {
            history.edit().putString("items", "previous query").commit();
            CaptureService route = captureService(context);
            route.onStartCommand(lookupRequest("history check", true)
                    .putExtra(FloatingService.EXTRA_LOOKUP_PRIVATE, privacy), 0, 1);
            check(history.getString("items", "").equals(privacy ? "previous query" : "history check\nprevious query"),
                    "Third-party history uses the request privacy, including stale Service privacy=" + privacy);
            route.onStartCommand(lookupRequest("history check", true)
                    .putExtra(FloatingService.EXTRA_LOOKUP_PRIVATE, privacy), 0, 2);
            check(history.getString("items", "").equals(privacy ? "previous query" : "history check\nprevious query"),
                    "Repeated third-party query preserves order without duplicates");
        }
        String query = "日本語 ;end; a&b + café\nsecond line";
        for (boolean hosted : new boolean[] {false, true}) {
            CaptureService service = captureService(context);
            View search = new View(context), lookup = new View(context);
            serviceField("searchPanel").set(service, search);
            serviceField("searchPanelHosted").setBoolean(service, true);
            serviceField("lookupOverlay").set(service, lookup);
            serviceField("lookupOverlayHosted").setBoolean(service, true);
            serviceField("lookupPrivateMode").setBoolean(service, true);
            service.onStartCommand(lookupRequest(query, hosted), 0, 1);
            check(service.activities.size() == 1 && query.equals(service.activities.get(0).getStringExtra("EXTRA_QUERY"))
                            && "com.eusoft.trans.en".equals(service.activities.get(0).getComponent().getPackageName()),
                    "Every initial Service lookup routes directly to the selected third party, hosted=" + hosted);
            check(serviceField("searchPanel").get(service) == null && serviceField("lookupOverlay").get(service) == null
                            && !serviceField("lookupOverlayHosted").getBoolean(service)
                            && !serviceField("lookupPrivateMode").getBoolean(service),
                    "Successful external lookup clears covered internal surfaces and their host state");
            check(finishesHost(service, hosted ? "incoming-lookup-host" : "existing-lookup-host"),
                    "Successful external lookup dismisses the correct transparent Mask host");
        }
        for (boolean hosted : new boolean[] {false, true}) {
            CaptureService service = captureService(context); service.unavailable = true;
            serviceField("lookupOverlayHosted").setBoolean(service, true);
            service.onStartCommand(lookupRequest(query, hosted), 0, 1);
            check(service.activities.size() == 1 && serviceField("lookupOverlay").get(service) == null,
                    "A missing external dictionary cannot fall through to internal lookup, hosted=" + hosted);
            check(finishesHost(service, hosted ? "incoming-lookup-host" : "existing-lookup-host")
                            && !serviceField("lookupOverlayHosted").getBoolean(service),
                    "Failed external lookup without content finishes its Mask instead of leaving a black screen");
        }
        CaptureService retained = captureService(context); retained.unavailable = true;
        View originalSearch = new View(context);
        serviceField("searchPanel").set(retained, originalSearch);
        serviceField("searchPanelHosted").setBoolean(retained, true);
        retained.onStartCommand(lookupRequest(query, false), 0, 1);
        check(serviceField("searchPanel").get(retained) == originalSearch && retained.broadcasts.isEmpty()
                        && retained.activities.size() == 1 && serviceField("lookupOverlay").get(retained) == null,
                "A failed external dispatch preserves an existing search and never submits the word internally");

        settings.select(LookupDestinationSettings.Model.FAST);
        CaptureService internal = captureService(context);
        internal.onStartCommand(lookupRequest(query, false), 0, 1);
        check(internal.activities.size() == 1 && new ComponentName(context, LookupLinkActivity.class)
                        .equals(internal.activities.get(0).getComponent())
                        && query.equals(internal.activities.get(0).getStringExtra(FloatingService.EXTRA_LOOKUP_QUERY))
                        && internal.broadcasts.isEmpty(),
                "Returning to System restores the existing internal transparent-host route");
    }

    private static Intent lookupRequest(String query, boolean hosted) {
        return new Intent(FloatingService.ACTION_SHOW_LOOKUP_OVERLAY)
                .putExtra(FloatingService.EXTRA_LOOKUP_QUERY, query)
                .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, hosted)
                .putExtra(FloatingService.EXTRA_HOST_SESSION, "incoming-lookup-host");
    }

    private static CaptureService captureService(Context context) throws Exception {
        CaptureService service = new CaptureService(context);
        serviceField("wm").set(service, context.getSystemService(Context.WINDOW_SERVICE));
        serviceField("bubble").set(service, new View(context));
        serviceField("activeHostSession").set(service, "existing-lookup-host");
        return service;
    }

    private static boolean finishesHost(CaptureService service, String session) {
        for (Intent broadcast : service.broadcasts)
            if (FloatingService.ACTION_LOOKUP_HOST_FINISH.equals(broadcast.getAction())
                    && session.equals(broadcast.getStringExtra(FloatingService.EXTRA_HOST_SESSION))) return true;
        return false;
    }

    private static Field serviceField(String name) throws Exception {
        Field field = FloatingService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static final class CaptureService extends FloatingService {
        final List<Intent> activities = new ArrayList<>(), broadcasts = new ArrayList<>();
        boolean unavailable;
        CaptureService(Context context) { attachBaseContext(context); }
        @Override public void startActivity(Intent intent) {
            activities.add(new Intent(intent));
            if (unavailable) throw new ActivityNotFoundException("validation fixture");
        }
        @Override public void sendBroadcast(Intent intent) { broadcasts.add(new Intent(intent)); }
    }

    private void launches(Instrumentation instrumentation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Context base = instrumentation.getTargetContext();
                AtomicReference<Intent> received = new AtomicReference<>();
                Context available = new ContextWrapper(base) {
                    @Override public void startActivity(Intent intent) { received.set(intent); }
                };
                String query = "查词 ;end; 日本語\n a&b + café";
                check(ThirdPartyLookup.launch(available, query), "A successful external dispatch reports success");
                check(received.get() != null && query.equals(received.get().getStringExtra("EXTRA_QUERY"))
                                && "com.eusoft.trans.en".equals(received.get().getComponent().getPackageName()),
                        "The launcher passes its original text to the exact requested dictionary");
                int[] attempts = {0};
                Context absent = new ContextWrapper(base) {
                    @Override public void startActivity(Intent intent) {
                        attempts[0]++; throw new ActivityNotFoundException("validation fixture");
                    }
                };
                check(!ThirdPartyLookup.launch(absent, query) && attempts[0] == 1,
                        "A missing dictionary reports failure without crashing or silently launching another activity");
                Context forbidden = new ContextWrapper(base) {
                    @Override public void startActivity(Intent intent) {
                        attempts[0]++; throw new SecurityException("validation fixture");
                    }
                };
                check(!ThirdPartyLookup.launch(forbidden, query) && attempts[0] == 2,
                        "An OEM or exported-activity security rejection reports failure safely");
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("External dictionary launcher", failure.get());
    }

    private static WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; return params;
    }

    private static List<String> labels(OcrJoystickView joystick) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, joystick.getWidth()), Math.max(1, joystick.getHeight()), Bitmap.Config.ARGB_8888);
        List<String> labels = new ArrayList<>();
        try {
            joystick.onDraw(new Canvas(bitmap) {
                @Override public void drawText(String text, float x, float y, Paint paint) { labels.add(text); }
            });
        } finally { bitmap.recycle(); }
        return labels;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static void checkCommit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new AssertionError("Unable to persist destination validation fixture");
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String)value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) editor.putInt(key, (Integer)value);
            else if (value instanceof Long) editor.putLong(key, (Long)value);
            else if (value instanceof Float) editor.putFloat(key, (Float)value);
            else throw new AssertionError("Unexpected destination fixture type: " + key);
        }
        checkCommit(editor);
    }
}
