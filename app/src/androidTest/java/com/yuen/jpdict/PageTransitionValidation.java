package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real page handoff with attached windows, including delayed/stale first frames. */
final class PageTransitionValidation {
    private int checks;

    static String run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if ("com.yuen.jpdict".equals(context.getPackageName())) {
            throw new AssertionError("Run page validation in an independent disposable QA applicationId");
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            throw new AssertionError("Page validation needs overlay permission on its disposable QA package");
        }
        PageTransitionValidation validation = new PageTransitionValidation();
        validation.handoff(instrumentation, context);
        validation.externalReturn(instrumentation, context);
        validation.externalHostLifecycle(instrumentation, context);
        return "Page transitions: " + validation.checks + " assertions passed\n";
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private void externalHostLifecycle(Instrumentation test, Context context) throws Exception {
        java.util.List<Intent> requests = new java.util.ArrayList<>();
        android.content.ContextWrapper capture = new android.content.ContextWrapper(context) {
            @Override public android.content.ComponentName startForegroundService(Intent intent) {
                requests.add(new Intent(intent)); return intent.getComponent();
            }
            @Override public android.content.ComponentName startService(Intent intent) {
                requests.add(new Intent(intent)); return intent.getComponent();
            }
        };
        AtomicReference<LookupLinkActivity> original = new AtomicReference<>(), restored = new AtomicReference<>();
        onMain(test, () -> {
            try {
                Intent source = new Intent(context, LookupLinkActivity.class)
                        .putExtra(FloatingService.EXTRA_HOST_SEARCH, true)
                        .putExtra(FloatingService.EXTRA_SEARCH_QUERY, "older intent draft");
                LookupLinkActivity host = attachHost(test, capture, source, null); original.set(host);
                String session = (String)hostField("hostSession").get(host);
                android.content.BroadcastReceiver receiver = (android.content.BroadcastReceiver)hostField("finishReceiver").get(host);
                receiver.onReceive(context, new Intent(FloatingService.ACTION_LOOKUP_HOST_EXTERNAL)
                        .putExtra(FloatingService.EXTRA_HOST_SESSION, "former-host")
                        .putExtra(FloatingService.EXTRA_LOOKUP_QUERY, "must not launch"));
                check(!hostField("externalLookupPending").getBoolean(host) && requests.isEmpty(),
                        "An external action from a former session cannot change the active host");

                // Simulate a successful external handoff's state; this checks
                // the real host callbacks without requiring an installed app.
                hostField("externalLookupPending").setBoolean(host, true);
                hostField("pageInFront").setBoolean(host, true);
                test.callActivityOnUserLeaving(host);
                check(requests.isEmpty(), "Leaving for a pending external lookup does not dismiss the retained search");
                android.os.Bundle saved = new android.os.Bundle();
                test.callActivityOnSaveInstanceState(host, saved);
                check(saved.getBoolean("external_lookup_pending") && session.equals(saved.getString("host_session")),
                        "Host configuration state retains the external return flag and session identity");
                LookupLinkActivity rebuilt = attachHost(test, capture, source, saved); restored.set(rebuilt);
                check(session.equals(hostField("hostSession").get(rebuilt))
                                && hostField("externalLookupPending").getBoolean(rebuilt),
                        "A rebuilt host preserves the same pending return ownership");
                java.lang.reflect.Method resume = LookupLinkActivity.class.getDeclaredMethod("onPostResume");
                resume.setAccessible(true); resume.invoke(rebuilt);
                Intent pending = (Intent)hostField("pendingForward").get(rebuilt);
                check(!hostField("externalLookupPending").getBoolean(rebuilt) && !hostField("pageInFront").getBoolean(rebuilt)
                                && pending != null && pending.getBooleanExtra(FloatingService.EXTRA_HOST_SEARCH, false)
                                && pending.getBooleanExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, false)
                                && !pending.hasExtra(FloatingService.EXTRA_SEARCH_QUERY),
                        "External return waits for window focus and restores the retained draft rather than stale Intent text");
                test.callActivityOnUserLeaving(rebuilt);
                check(requests.size() == 1 && FloatingService.ACTION_LOOKUP_DISMISS.equals(requests.get(0).getAction())
                                && session.equals(requests.get(0).getStringExtra(FloatingService.EXTRA_HOST_SESSION)),
                        "A later actual user departure resumes normal host dismissal with its current session");
                hostField("externalLookupPending").setBoolean(rebuilt, true);
                hostField("pageInFront").setBoolean(rebuilt, true);
                test.callActivityOnNewIntent(rebuilt, new Intent(context, LookupLinkActivity.class)
                        .putExtra(FloatingService.EXTRA_HOST_SEARCH, true));
                check(!hostField("externalLookupPending").getBoolean(rebuilt) && !hostField("pageInFront").getBoolean(rebuilt)
                                && !session.equals(hostField("hostSession").get(rebuilt)),
                        "A new host request invalidates a former pending external return");
            } finally {
                if (restored.get() != null) { test.callActivityOnStop(restored.get()); test.callActivityOnDestroy(restored.get()); }
                if (original.get() != null) { test.callActivityOnStop(original.get()); test.callActivityOnDestroy(original.get()); }
            }
        });
    }

    private static LookupLinkActivity attachHost(Instrumentation test, Context context, Intent intent,
            android.os.Bundle state) throws Exception {
        android.content.pm.ActivityInfo info = new android.content.pm.ActivityInfo();
        info.applicationInfo = context.getApplicationInfo(); info.packageName = context.getPackageName();
        info.name = LookupLinkActivity.class.getName(); info.theme = R.style.Theme_LookupLinkBridge;
        LookupLinkActivity host = (LookupLinkActivity)test.newActivity(LookupLinkActivity.class, context,
                new android.os.Binder(), (android.app.Application)context.getApplicationContext(), intent,
                info, "External return host", null, "external-return-fixture", null);
        host.setTheme(R.style.Theme_LookupLinkBridge);
        test.callActivityOnCreate(host, state); test.callActivityOnStart(host);
        return host;
    }

    private static Field hostField(String name) throws Exception {
        Field field = LookupLinkActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private void externalReturn(Instrumentation test, Context context) throws Exception {
        LookupDestinationSettings settings = LookupDestinationSettings.get(context);
        LookupDestinationSettings.Model previous = settings.model();
        android.content.SharedPreferences preferences = context.getSharedPreferences("lookup_destination", Context.MODE_PRIVATE);
        java.util.Map<String, ?> saved = preferences.getAll();
        Object remembered = saved.get("system_model");
        LookupDestinationSettings.Model previousInternal = previous == LookupDestinationSettings.Model.THIRD_PARTY
                ? LookupDestinationSettings.Model.fromStored(remembered instanceof String ? (String)remembered : "fast") : previous;
        if (previousInternal == LookupDestinationSettings.Model.THIRD_PARTY) previousInternal = LookupDestinationSettings.Model.FAST;
        LookupDestinationSettings.Model restoreInternal = previousInternal;
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        CaptureService service = new CaptureService(context);
        field("wm").set(service, windows);
        field("bubble").set(service, new View(context));
        field("currentSearchQuery").set(service, "private original draft");
        field("searchPrivateMode").setBoolean(service, true);
        field("activeHostSession").set(service, "external-host");
        View search = null;
        try {
            onMain(test, () -> settings.select(LookupDestinationSettings.Model.THIRD_PARTY));
            search = attachSearch(test, context, service, windows, true);
            Intent lookup = new Intent(FloatingService.ACTION_SHOW_LOOKUP_OVERLAY)
                    .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true)
                    .putExtra(FloatingService.EXTRA_LOOKUP_QUERY, "hold on");
            dispatch(test, service, lookup);
            check(search.isAttachedToWindow() && search.getVisibility() == View.INVISIBLE
                            && field("searchPanel").get(service) == search && service.activity == null,
                    "Hosted third-party lookup retains the same hidden search window");
            check(service.broadcast != null && FloatingService.ACTION_LOOKUP_HOST_EXTERNAL.equals(service.broadcast.getAction())
                            && context.getPackageName().equals(service.broadcast.getPackage())
                            && "external-host".equals(service.broadcast.getStringExtra(FloatingService.EXTRA_HOST_SESSION))
                            && "hold on".equals(service.broadcast.getStringExtra(FloatingService.EXTRA_LOOKUP_QUERY)),
                    "The active host receives one package-scoped external dispatch with the exact selected text");
            check(field("searchPrivateMode").getBoolean(service)
                            && "private original draft".equals(field("currentSearchQuery").get(service)),
                    "External lookup preserves the original private composer only in the existing Service");
            Intent returning = new Intent(FloatingService.ACTION_SHOW_SEARCH)
                    .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, true)
                    .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true)
                    .putExtra(FloatingService.EXTRA_SEARCH_QUERY, "older host draft")
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, "former-host");
            dispatch(test, service, returning);
            dispatch(test, service, new Intent(FloatingService.ACTION_LOOKUP_DISMISS)
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, "former-host"));
            check(search.isAttachedToWindow() && search.getVisibility() == View.INVISIBLE
                            && "external-host".equals(field("activeHostSession").get(service)),
                    "Former hosts cannot reveal, dispose or take ownership of an external lookup return");
            returning.putExtra(FloatingService.EXTRA_HOST_SESSION, "external-host");
            dispatch(test, service, returning);
            check(search.isAttachedToWindow() && search.getVisibility() == View.VISIBLE
                            && field("searchPanel").get(service) == search,
                    "Success or launch failure returns to the exact retained search View");
            check(field("searchPrivateMode").getBoolean(service)
                            && "private original draft".equals(field("currentSearchQuery").get(service)),
                    "Returning from an external lookup cannot replace the live private draft with stale Intent text");
            dispatch(test, service, lookup);
            onMain(test, () -> {
                java.lang.reflect.Method toggle = FloatingService.class.getDeclaredMethod("toggleSearchPanel");
                toggle.setAccessible(true); toggle.invoke(service);
            });
            check(search.isAttachedToWindow() && field("searchPanel").get(service) == search
                            && service.activity != null && service.activity.getBooleanExtra(FloatingService.EXTRA_HOST_SEARCH, false),
                    "A bubble tap during external lookup opens its host without discarding hidden composer content");
            dispatch(test, service, new Intent(FloatingService.ACTION_LOOKUP_DISMISS)
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, "external-host"));
            check(!search.isAttachedToWindow() && field("searchPanel").get(service) == null
                            && !field("searchPrivateMode").getBoolean(service)
                            && "".equals(field("currentSearchQuery").get(service)),
                    "An actual user departure still releases the search window and clears private input");
        } finally {
            View remaining = search;
            onMain(test, () -> {
                if (remaining != null && remaining.isAttachedToWindow()) windows.removeViewImmediate(remaining);
                settings.select(restoreInternal); settings.select(previous);
                android.content.SharedPreferences.Editor editor = preferences.edit().clear();
                for (java.util.Map.Entry<String, ?> item : saved.entrySet()) {
                    Object value = item.getValue();
                    if (value instanceof String) editor.putString(item.getKey(), (String)value);
                    else if (value instanceof Boolean) editor.putBoolean(item.getKey(), (Boolean)value);
                }
                editor.commit();
            });
        }
    }

    private void handoff(Instrumentation test, Context context) throws Exception {
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        CaptureService service = new CaptureService(context);
        field("wm").set(service, windows);
        // This test owns only its small search fixture; no persistent bubble or
        // actual foreground Service is started by the isolated Service instance.
        field("bubble").set(service, new View(context));
        field("currentSearchQuery").set(service, "page draft");
        field("activeHostSession").set(service, "page-host-1");
        View search = null;
        try {
            search = attachSearch(test, context, service, windows, true);
            Intent open = new Intent(FloatingSearchRoutes.ACTION_OPEN_PAGE)
                    .putExtra(FloatingSearchRoutes.EXTRA_PAGE, FloatingSearchRoutes.PAGE_SETTINGS);
            dispatch(test, service, open);
            String first = (String)field("pendingSearchPageRequest").get(service);
            check(!first.isEmpty() && search.isAttachedToWindow()
                    && field("searchPanel").get(service) == search,
                    "Opening a page keeps the existing search window until a replacement frame exists");
            check(service.broadcast != null
                    && FloatingSearchRoutes.ACTION_HOST_OPEN_PAGE.equals(service.broadcast.getAction())
                    && first.equals(service.broadcast.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST))
                    && "page draft".equals(service.broadcast.getStringExtra(FloatingSearchRoutes.EXTRA_RETURN_QUERY)),
                    "Hosted page receives the matching handoff identity and draft");
            dispatch(test, service, ready("older-page", "page-host-1"));
            check(search.isAttachedToWindow(), "A stale page frame cannot uncover a newer search window");
            dispatch(test, service, ready(first, "older-host"));
            check(search.isAttachedToWindow(), "A frame from a former host cannot finish the current handoff");
            dispatch(test, service, ready(first, "page-host-1"));
            check(!search.isAttachedToWindow() && field("searchPanel").get(service) == null,
                    "The matching page frame releases the covered search window");
            check("".equals(field("pendingSearchPageRequest").get(service))
                    && "".equals(field("pendingSearchPageHostSession").get(service))
                    && FloatingSearchRoutes.ACTION_HOST_OPEN_PAGE.equals(service.broadcast.getAction()),
                    "Completing a handoff consumes its identity while preserving the transparent return host");

            search = attachSearch(test, context, service, windows, true);
            dispatch(test, service, open);
            String second = (String)field("pendingSearchPageRequest").get(service);
            check(!second.equals(first), "Consecutive page requests use different first-frame identities");
            dispatch(test, service, ready(first, "page-host-1"));
            check(search.isAttachedToWindow(), "An earlier successful frame cannot close the next search");
            dispatch(test, service, new Intent(FloatingService.ACTION_SHOW_SEARCH)
                    .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, true));
            dispatch(test, service, ready(second, "page-host-1"));
            check(search.isAttachedToWindow() && "".equals(field("pendingSearchPageRequest").get(service)),
                    "Returning before the target first frame cancels its pending handoff");
            dispatch(test, service, open);
            String canceled = (String)field("pendingSearchPageRequest").get(service);
            dispatch(test, service, new Intent(FloatingService.ACTION_LOOKUP_DISMISS)
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, "page-host-1"));
            check(!search.isAttachedToWindow() && "".equals(field("pendingSearchPageRequest").get(service)),
                    "Leaving the application releases both the search window and pending page identity");

            search = attachSearch(test, context, service, windows, false);
            dispatch(test, service, open);
            String independent = (String)field("pendingSearchPageRequest").get(service);
            check(service.activity != null && !independent.isEmpty()
                    && independent.equals(service.activity.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST))
                    && search.isAttachedToWindow(),
                    "An unhosted page also retains search until its own first frame");
            dispatch(test, service, ready(canceled, "page-host-1"));
            check(search.isAttachedToWindow(), "A canceled hosted frame cannot affect a later independent page");
            dispatch(test, service, ready(independent, null));
            check(!search.isAttachedToWindow(), "An independent page completes its matching first-frame handoff");

            for (String page : new String[]{FloatingSearchRoutes.PAGE_SETTINGS,
                    FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS, FloatingSearchRoutes.PAGE_FILES,
                    FloatingSearchRoutes.PAGE_DICTIONARIES}) {
                Intent route = FloatingSearchRoutes.pageIntent(context, page, "draft", true, false, false, true);
                check((route.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0,
                        "Every native page avoids a cross-window fade or translation: " + page);
            }
            ContextThemeWrapper theme = new ContextThemeWrapper(context, R.style.Theme_Jpdict_Page);
            TypedArray attributes = theme.obtainStyledAttributes(new int[]{android.R.attr.windowBackground,
                    android.R.attr.statusBarColor, android.R.attr.navigationBarColor,
                    android.R.attr.windowDisablePreview, android.R.attr.windowAnimationStyle});
            try {
                check(attributes.getDrawable(0) instanceof ColorDrawable
                        && ((ColorDrawable)attributes.getDrawable(0)).getColor() == 0xfff2f2f2
                        && attributes.getColor(1, 0) == 0xfff2f2f2
                        && attributes.getColor(2, 0) == 0xfff2f2f2,
                        "Native page startup, status and navigation backgrounds match the rendered page");
                check(attributes.getBoolean(3, false) && attributes.getResourceId(4, 0) == 0,
                        "Native pages have no separate preview window or window transition");
            } finally { attributes.recycle(); }
        } finally {
            View remaining = search;
            test.runOnMainSync(() -> {
                if (remaining != null && remaining.isAttachedToWindow()) windows.removeViewImmediate(remaining);
            });
        }
    }

    private static View attachSearch(Instrumentation test, Context context, FloatingService service, WindowManager windows,
            boolean hosted) throws Exception {
        View search = new View(context);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(8, 8,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        onMain(test, () -> {
            windows.addView(search, params);
            field("searchPanel").set(service, search);
            field("searchPanelLp").set(service, params);
            field("searchPanelHosted").setBoolean(service, hosted);
        });
        AtomicBoolean attached = new AtomicBoolean();
        for (int attempt = 0; attempt < 100; attempt++) {
            test.runOnMainSync(() -> attached.set(search.isAttachedToWindow()));
            if (attached.get()) return search;
            Thread.sleep(25);
        }
        throw new AssertionError("The page handoff fixture must attach a real window before requests");
    }

    private static void dispatch(Instrumentation test, CaptureService service, Intent intent) throws Exception {
        onMain(test, () -> service.onStartCommand(intent, 0, 1));
        test.waitForIdleSync();
    }

    private interface MainAction { void run() throws Exception; }
    private static void onMain(Instrumentation test, MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static Intent ready(String request, String host) {
        Intent ready = new Intent(FloatingSearchRoutes.ACTION_PAGE_READY)
                .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, request);
        if (host != null) ready.putExtra(FloatingService.EXTRA_HOST_SESSION, host);
        return ready;
    }

    private static Field field(String name) throws Exception {
        Field field = FloatingService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CaptureService extends FloatingService {
        Intent broadcast, activity;
        CaptureService(Context context) { attachBaseContext(context); }
        @Override public void sendBroadcast(Intent intent) { broadcast = new Intent(intent); }
        @Override public void startActivity(Intent intent) { activity = new Intent(intent); }
    }
}
