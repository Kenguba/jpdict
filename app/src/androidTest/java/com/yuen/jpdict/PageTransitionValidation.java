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
        return "Page transitions: " + validation.checks + " assertions passed\n";
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
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
        AtomicReference<Exception> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Exception error) { failure.set(error); } });
        if (failure.get() != null) throw failure.get();
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
