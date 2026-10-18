package com.yuen.jpdict;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Central destinations and return handling for the floating search surfaces. */
final class FloatingSearchRoutes {
    static final String ACTION_OPEN_PAGE = "com.yuen.jpdict.OPEN_FLOATING_SEARCH_PAGE";
    static final String ACTION_HOST_OPEN_PAGE = "com.yuen.jpdict.HOST_OPEN_FLOATING_SEARCH_PAGE";
    static final String ACTION_PAGE_READY = "com.yuen.jpdict.FLOATING_SEARCH_PAGE_READY";
    static final String ACTION_CLEAR_PAGE_SESSION = "com.yuen.jpdict.CLEAR_FLOATING_SEARCH_PAGE_SESSION";
    static final String EXTRA_PAGE = "floating_search_page";
    static final String EXTRA_RETURN_QUERY = "floating_search_return_query";
    static final String EXTRA_RESTORE_SEARCH = "floating_search_restore_after_page";
    static final String EXTRA_RETURN_TO_SETTINGS = "floating_search_return_to_settings";
    static final String EXTRA_PRIVATE_MODE = "floating_search_private_mode";
    static final String EXTRA_HOSTED_SEARCH = "floating_search_has_return_host";
    static final String EXTRA_PAGE_REQUEST = "floating_search_page_request";

    static final String PAGE_SETTINGS = "settings";
    static final String PAGE_DRAWER_STYLE = "drawer_style";
    static final int REQUEST_DRAWER_STYLE = 204;
    static final String EXTRA_EXPERIENCE = "drawer_experience_request";
    static final String ACTION_EXPERIENCE_RESULT = "com.yuen.jpdict.DRAWER_EXPERIENCE_RESULT";
    static final String PAGE_BUBBLE_SETTINGS = "bubble_settings";
    static final String PAGE_FILES = "files";
    static final String PAGE_DICTIONARIES = "dictionaries";
    static final String EXTRA_FILE_ID = "drawer_file_id";

    private FloatingSearchRoutes() {}

    static void openFromSearch(Context context, String page, boolean privateMode) {
        Intent request = new Intent(context, FloatingService.class)
                .setAction(ACTION_OPEN_PAGE)
                .putExtra(EXTRA_PAGE, page)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode);
        startService(context, request);
    }

    static Intent pageIntent(Context context, String page, String query,
            boolean restoreSearch, boolean returnToSettings, boolean privateMode,
            boolean hostedSearch) {
        Class<?> destination;
        if (PAGE_BUBBLE_SETTINGS.equals(page)) {
            destination = FloatingBubbleSettingsActivity.class;
        } else if (PAGE_FILES.equals(page) || (page != null && page.startsWith(PAGE_FILES + "/"))) {
            destination = DrawerFilesActivity.class;
        } else if (PAGE_DICTIONARIES.equals(page)) {
            destination = DictionaryActivity.class;
        } else if (PAGE_DRAWER_STYLE.equals(page)) {
            destination = DrawerStyleSettingsActivity.class;
        } else if (PAGE_SETTINGS.equals(page)) {
            destination = SearchSettingsActivity.class;
        } else return null;
        return new Intent(context, destination)
                .putExtra(EXTRA_FILE_ID, page != null && page.startsWith(PAGE_FILES + "/")
                        ? page.substring(PAGE_FILES.length() + 1) : "")
                .putExtra(EXTRA_RETURN_QUERY, privateMode || query == null ? "" : query)
                .putExtra(EXTRA_RESTORE_SEARCH, restoreSearch)
                .putExtra(EXTRA_RETURN_TO_SETTINGS, returnToSettings)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode)
                .putExtra(EXTRA_HOSTED_SEARCH, hostedSearch)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static void openFromActivity(Activity activity, String page, boolean returnToSettings) {
        Intent current = activity.getIntent();
        Intent next = pageIntent(activity, page,
                current == null ? "" : current.getStringExtra(EXTRA_RETURN_QUERY),
                current != null && current.getBooleanExtra(EXTRA_RESTORE_SEARCH, false),
                returnToSettings,
                current != null && current.getBooleanExtra(EXTRA_PRIVATE_MODE, false),
                current != null && current.getBooleanExtra(EXTRA_HOSTED_SEARCH, false));
        if (next == null) { pageError(activity); return; }
        if (current != null) {
            next.putExtra(FloatingService.EXTRA_HOST_SESSION, current.getStringExtra(FloatingService.EXTRA_HOST_SESSION))
                    .putExtra(EXTRA_PAGE_REQUEST, current.getStringExtra(EXTRA_PAGE_REQUEST));
        }
        try {
            if (PAGE_DRAWER_STYLE.equals(page)) activity.startActivityForResult(next, REQUEST_DRAWER_STYLE);
            else activity.startActivity(next);
            activity.overridePendingTransition(0, 0);
        } catch (RuntimeException error) { pageError(activity); }
    }

    static void finishPage(Activity activity) {
        Intent source = activity.getIntent();
        boolean returnToSettings = source != null
                && source.getBooleanExtra(EXTRA_RETURN_TO_SETTINGS, false);
        boolean restoreSearch = source != null
                && source.getBooleanExtra(EXTRA_RESTORE_SEARCH, false);
        boolean privateMode = source != null
                && source.getBooleanExtra(EXTRA_PRIVATE_MODE, false);
        String query = source == null ? "" : source.getStringExtra(EXTRA_RETURN_QUERY);

        if (!returnToSettings && restoreSearch) {
            Intent search = new Intent(activity, FloatingService.class)
                    .setAction(FloatingService.ACTION_SHOW_SEARCH)
                    .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED,
                            source.getBooleanExtra(EXTRA_HOSTED_SEARCH, false))
                    .putExtra(EXTRA_PRIVATE_MODE, privateMode);
            search.putExtra(EXTRA_PAGE_REQUEST, source.getStringExtra(EXTRA_PAGE_REQUEST))
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, source.getStringExtra(FloatingService.EXTRA_HOST_SESSION))
                    .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true);
            if (!privateMode) {
                search.putExtra(FloatingService.EXTRA_SEARCH_QUERY, query == null ? "" : query);
            }
            startService(activity, search);
        }
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    static void experienceDrawer(Activity activity) {
        if (!android.provider.Settings.canDrawOverlays(activity)) { pageError(activity); return; }
        Intent source = activity.getIntent();
        if (activity instanceof DrawerStyleSettingsActivity && source.getBooleanExtra(EXTRA_RETURN_TO_SETTINGS, false)) {
            activity.setResult(Activity.RESULT_OK); activity.finish(); activity.overridePendingTransition(0, 0); return;
        }
        if (source.hasExtra(EXTRA_EXPERIENCE)) return;
        String request = java.util.UUID.randomUUID().toString();
        source.putExtra(EXTRA_EXPERIENCE, request);
        boolean restoring = source.getBooleanExtra(EXTRA_RESTORE_SEARCH, false);
        Intent show = new Intent(activity, FloatingService.class).setAction(FloatingService.ACTION_SHOW_SEARCH)
                .putExtra(EXTRA_EXPERIENCE, request);
        if (restoring) {
            show.putExtra(EXTRA_PAGE_REQUEST, source.getStringExtra(EXTRA_PAGE_REQUEST))
                    .putExtra(FloatingService.EXTRA_HOST_SESSION, source.getStringExtra(FloatingService.EXTRA_HOST_SESSION))
                    .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, source.getBooleanExtra(EXTRA_HOSTED_SEARCH, false))
                    .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true);
        }
        if (!startService(activity, show)) { source.removeExtra(EXTRA_EXPERIENCE); pageError(activity); }
        else activity.getWindow().getDecorView().postDelayed(() -> {
            if (!activity.isDestroyed() && request.equals(source.getStringExtra(EXTRA_EXPERIENCE))) {
                source.removeExtra(EXTRA_EXPERIENCE); pageError(activity);
            }
        }, 6000); // Failure recovery only; opening is synchronized to the real window's layout.
    }

    private static void pageError(Context context) {
        android.widget.Toast.makeText(context, "无法打开页面，请确认悬浮窗权限后重试", android.widget.Toast.LENGTH_SHORT).show();
    }

    static void onPageBackground(Activity activity) {
        Intent source = activity.getIntent();
        if (source == null || !source.getBooleanExtra(EXTRA_RESTORE_SEARCH, false)) return;

        Intent dismiss = new Intent(activity, FloatingService.class)
                .setAction(FloatingService.ACTION_LOOKUP_DISMISS)
                .putExtra(EXTRA_PAGE_REQUEST, source.getStringExtra(EXTRA_PAGE_REQUEST))
                .putExtra(FloatingService.EXTRA_HOST_SESSION, source.getStringExtra(FloatingService.EXTRA_HOST_SESSION));
        startService(activity, dismiss);
        activity.sendBroadcast(new Intent(ACTION_CLEAR_PAGE_SESSION)
                .setPackage(activity.getPackageName())
                .putExtra(FloatingService.EXTRA_HOST_SESSION, source.getStringExtra(FloatingService.EXTRA_HOST_SESSION)));
        // A page returning from the background must not resurrect its former
        // floating search session or retain a private draft in route extras.
        source.putExtra(EXTRA_RESTORE_SEARCH, false)
                .putExtra(EXTRA_PRIVATE_MODE, false)
                .putExtra(EXTRA_RETURN_QUERY, "");
        source.removeExtra(EXTRA_PAGE_REQUEST);
        source.removeExtra(EXTRA_EXPERIENCE);
    }

    static BroadcastReceiver pageSessionReceiver(Activity activity) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Intent route = activity.getIntent();
                if (route == null) return;
                String host = intent.getStringExtra(FloatingService.EXTRA_HOST_SESSION);
                if (host != null && !host.equals(route.getStringExtra(FloatingService.EXTRA_HOST_SESSION))) return;
                if (ACTION_EXPERIENCE_RESULT.equals(intent.getAction())) {
                    String request = route.getStringExtra(EXTRA_EXPERIENCE);
                    if (request == null || !request.equals(intent.getStringExtra(EXTRA_EXPERIENCE))) return;
                    route.removeExtra(EXTRA_EXPERIENCE);
                    if (intent.getBooleanExtra("success", false)) {
                        activity.finish(); activity.overridePendingTransition(0, 0);
                    } else pageError(activity);
                    return;
                }
                route.putExtra(EXTRA_RESTORE_SEARCH, false)
                        .putExtra(EXTRA_PRIVATE_MODE, false)
                        .putExtra(EXTRA_RETURN_QUERY, "");
                route.removeExtra(EXTRA_PAGE_REQUEST);
                route.removeExtra(EXTRA_EXPERIENCE);
                activity.setIntent(route);
            }
        };
    }

    static void registerPageSessionReceiver(Activity activity, BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(ACTION_CLEAR_PAGE_SESSION);
        filter.addAction(ACTION_EXPERIENCE_RESULT);
        ContextCompat.registerReceiver(activity, receiver,
                filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    static boolean startService(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException ignored) {
            // The calling screen remains usable if the persistent service is unavailable.
        }
        return false;
    }
}
