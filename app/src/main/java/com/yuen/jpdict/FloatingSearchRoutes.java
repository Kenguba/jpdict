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
    static final String ACTION_HOST_SHOW_LOOKUP = "com.yuen.jpdict.HOST_SHOW_LOOKUP_FROM_PAGE";
    static final String ACTION_CLEAR_PAGE_SESSION = "com.yuen.jpdict.CLEAR_FLOATING_SEARCH_PAGE_SESSION";
    static final String EXTRA_PAGE = "floating_search_page";
    static final String EXTRA_RETURN_QUERY = "floating_search_return_query";
    static final String EXTRA_RESTORE_SEARCH = "floating_search_restore_after_page";
    static final String EXTRA_RETURN_TO_SETTINGS = "floating_search_return_to_settings";
    static final String EXTRA_PRIVATE_MODE = "floating_search_private_mode";
    static final String EXTRA_HOSTED_SEARCH = "floating_search_has_return_host";

    static final String PAGE_SETTINGS = "settings";
    static final String PAGE_BUBBLE_SETTINGS = "bubble_settings";
    static final String PAGE_DRAWER_SEARCH = "drawer_search";

    private FloatingSearchRoutes() {}

    static void openFromSearch(Context context, String page, boolean privateMode) {
        Intent request = new Intent(context, FloatingService.class)
                .setAction(ACTION_OPEN_PAGE)
                .putExtra(EXTRA_PAGE, page)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode);
        startService(context, request);
    }

    static Intent pageIntent(Context context, String page, String query,
            boolean restoreSearch, boolean returnToSettings) {
        return pageIntent(context, page, query, restoreSearch, returnToSettings, false, false);
    }

    static Intent pageIntent(Context context, String page, String query,
            boolean restoreSearch, boolean returnToSettings, boolean privateMode) {
        return pageIntent(context, page, query, restoreSearch, returnToSettings, privateMode, false);
    }

    static Intent pageIntent(Context context, String page, String query,
            boolean restoreSearch, boolean returnToSettings, boolean privateMode,
            boolean hostedSearch) {
        Class<?> destination;
        if (PAGE_BUBBLE_SETTINGS.equals(page)) {
            destination = FloatingBubbleSettingsActivity.class;
        } else if (PAGE_DRAWER_SEARCH.equals(page)) {
            destination = SearchDrawerActivity.class;
        } else {
            destination = SearchSettingsActivity.class;
        }
        return new Intent(context, destination)
                .putExtra(EXTRA_RETURN_QUERY, query == null ? "" : query)
                .putExtra(EXTRA_RESTORE_SEARCH, restoreSearch)
                .putExtra(EXTRA_RETURN_TO_SETTINGS, returnToSettings)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode)
                .putExtra(EXTRA_HOSTED_SEARCH, hostedSearch)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static void openFromActivity(Activity activity, String page, boolean returnToSettings) {
        Intent current = activity.getIntent();
        Intent next = pageIntent(activity, page,
                current == null ? "" : current.getStringExtra(EXTRA_RETURN_QUERY),
                current != null && current.getBooleanExtra(EXTRA_RESTORE_SEARCH, false),
                returnToSettings,
                current != null && current.getBooleanExtra(EXTRA_PRIVATE_MODE, false),
                current != null && current.getBooleanExtra(EXTRA_HOSTED_SEARCH, false));
        activity.startActivity(next);
        activity.overridePendingTransition(0, 0);
    }

    static void openLookupFromPage(Activity activity, String query) {
        Intent source = activity.getIntent();
        String restoreQuery = source == null ? "" : source.getStringExtra(EXTRA_RETURN_QUERY);
        boolean privateMode = source != null
                && source.getBooleanExtra(EXTRA_PRIVATE_MODE, false);
        boolean hostedSearch = source != null
                && source.getBooleanExtra(EXTRA_HOSTED_SEARCH, false);
        if (hostedSearch) {
            activity.sendBroadcast(new Intent(ACTION_HOST_SHOW_LOOKUP)
                    .setPackage(activity.getPackageName()));
        }
        Intent lookup = new Intent(activity, FloatingService.class)
                .setAction(FloatingService.ACTION_SHOW_LOOKUP_OVERLAY)
                .putExtra(FloatingService.EXTRA_LOOKUP_QUERY, query)
                .putExtra(FloatingService.EXTRA_LOOKUP_MODE, FloatingService.LOOKUP_MODE_FULLSCREEN)
                .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, hostedSearch)
                .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, true)
                .putExtra(FloatingService.EXTRA_LOOKUP_PRIVATE, privateMode);
        if (!privateMode) {
            lookup.putExtra(FloatingService.EXTRA_SEARCH_QUERY, restoreQuery == null ? "" : restoreQuery);
        }
        startService(activity, lookup);
        activity.finish();
        activity.overridePendingTransition(0, 0);
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
                    .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, true)
                    .putExtra(EXTRA_PRIVATE_MODE, privateMode);
            if (!privateMode) {
                search.putExtra(FloatingService.EXTRA_SEARCH_QUERY, query == null ? "" : query);
            }
            startService(activity, search);
        }
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    static void onPageBackground(Activity activity) {
        Intent source = activity.getIntent();
        if (source == null || !source.getBooleanExtra(EXTRA_RESTORE_SEARCH, false)) return;

        Intent dismiss = new Intent(activity, FloatingService.class)
                .setAction(FloatingService.ACTION_LOOKUP_DISMISS);
        startService(activity, dismiss);
        activity.sendBroadcast(new Intent(ACTION_CLEAR_PAGE_SESSION)
                .setPackage(activity.getPackageName()));
        // A page returning from the background must not resurrect its former
        // floating search session or retain a private draft in route extras.
        source.putExtra(EXTRA_RESTORE_SEARCH, false)
                .putExtra(EXTRA_PRIVATE_MODE, false)
                .putExtra(EXTRA_RETURN_QUERY, "");
    }

    static BroadcastReceiver pageSessionReceiver(Activity activity) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Intent route = activity.getIntent();
                if (route == null) return;
                route.putExtra(EXTRA_RESTORE_SEARCH, false)
                        .putExtra(EXTRA_PRIVATE_MODE, false)
                        .putExtra(EXTRA_RETURN_QUERY, "");
                activity.setIntent(route);
            }
        };
    }

    static void registerPageSessionReceiver(Activity activity, BroadcastReceiver receiver) {
        ContextCompat.registerReceiver(activity, receiver,
                new IntentFilter(ACTION_CLEAR_PAGE_SESSION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    static void startService(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ignored) {
            // The calling screen remains usable if the persistent service is unavailable.
        }
    }
}
