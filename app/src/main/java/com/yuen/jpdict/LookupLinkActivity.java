package com.yuen.jpdict;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * Transparent Back and mask host for the shared lookup overlay.
 *
 * Visible dictionary UI remains a WindowManager TYPE_APPLICATION_OVERLAY.
 * This Activity supplies the full-display scrim and gives Android / OriginOS
 * a real application window to target for system Back gestures.
 */
public final class LookupLinkActivity extends Activity {
    private static final String STATE_SHOWING_SEARCH = "showing_search";
    private android.window.OnBackInvokedCallback backCallback;
    private boolean finishReceiverRegistered;
    private String currentMode = FloatingService.LOOKUP_MODE_FULLSCREEN;
    private boolean showingSearch;
    private boolean hostVisible;

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (FloatingService.ACTION_LOOKUP_HOST_FINISH.equals(intent.getAction())) {
                markHostAction("HOST_FINISH_BROADCAST");
                finishBridge();
            } else if (FloatingService.ACTION_LOOKUP_HOST_SEARCH.equals(intent.getAction())) {
                if (!hostVisible || isFinishing()) return;
                showingSearch = true;
                configureTransparentHost();
                forwardSearch(getIntent());
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        currentMode = extractMode(getIntent());
        showingSearch = state != null
                ? state.getBoolean(STATE_SHOWING_SEARCH,
                        getIntent().getBooleanExtra(FloatingService.EXTRA_HOST_SEARCH, false))
                : getIntent().getBooleanExtra(FloatingService.EXTRA_HOST_SEARCH, false);
        configureTransparentHost();
        registerFinishReceiver();
        // A lookup host can switch back to search without a new Activity intent.
        // On recreation, retain that mode and let the Service keep the live draft.
        forward(state != null && showingSearch
                ? new Intent(this, LookupLinkActivity.class)
                        .putExtra(FloatingService.EXTRA_HOST_SEARCH, true)
                : getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_SHOWING_SEARCH, showingSearch);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        currentMode = extractMode(intent);
        showingSearch = intent.getBooleanExtra(FloatingService.EXTRA_HOST_SEARCH, false);
        configureTransparentHost();
        forward(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        hostVisible = true;
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        // Register only after the Activity is truly resumed. Some OriginOS
        // versions ignore predictive/system Back callbacks registered too early.
        registerSystemBack();
        markHostAction("HOST_RESUMED:" + currentMode);
    }

    @Override protected void onStop() {
        hostVisible = false;
        super.onStop();
        // Do not leave a modal overlay behind after its mask/Back host is hidden.
        if (!isFinishing() && !isChangingConfigurations()) {
            sendLookupAction(FloatingService.ACTION_LOOKUP_DISMISS);
        }
    }

    @Override public void onBackPressed() {
        markHostAction("HOST_ON_BACK_PRESSED");
        requestLookupBack();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    event.getRepeatCount() == 0 && !event.isCanceled()) {
                markHostAction("HOST_KEY_BACK");
                requestLookupBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        clearSystemBack();
        if (finishReceiverRegistered) {
            try {
                unregisterReceiver(finishReceiver);
            } catch (Throwable ignored) {}
            finishReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void clearSystemBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            } catch (Throwable ignored) {}
        }
        backCallback = null;
    }

    private void registerSystemBack() {
        if (Build.VERSION.SDK_INT < 33) return;

        clearSystemBack();
        backCallback = () -> {
            markHostAction("HOST_ON_BACK_INVOKED");
            requestLookupBack();
        };

        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    backCallback);
        } catch (Throwable first) {
            // PRIORITY_OVERLAY is preferred on OEM gesture navigation; fall
            // back to DEFAULT if the vendor implementation rejects it.
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        backCallback);
            } catch (Throwable ignored) {}
        }
    }

    private void registerFinishReceiver() {
        IntentFilter filter = new IntentFilter(FloatingService.ACTION_LOOKUP_HOST_FINISH);
        filter.addAction(FloatingService.ACTION_LOOKUP_HOST_SEARCH);
        try {
            ContextCompat.registerReceiver(this, finishReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            finishReceiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void configureTransparentHost() {
        try {
            Window w = getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

            // Both entry points are modal. A touchable Activity is also needed
            // for OriginOS to choose this window as the edge-Back target.
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setStatusBarContrastEnforced(false);
                w.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = w.getAttributes();
                attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(attributes);
            }
            w.getDecorView().setSystemUiVisibility(
                    (showingSearch ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                            (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0) : 0) |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

            // TYPE_APPLICATION_OVERLAY is below critical system windows and
            // can be cropped by OEM policy. The Activity owns one edge-to-edge
            // mask; the overlay owns the dictionary card and mask hit target.
            View mask = new View(this);
            mask.setBackgroundColor(showingSearch ? 0xfffcfcfc : 0x6b000000);
            mask.setContentDescription(showingSearch ? "点击关闭搜索浮窗" : "点击关闭查词小窗");
            mask.setOnClickListener(v -> sendLookupAction(FloatingService.ACTION_LOOKUP_DISMISS));
            setContentView(mask);

            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {}
    }

    private void requestLookupBack() {
        markHostAction("HOST_SEND_LOOKUP_BACK");
        sendLookupAction(FloatingService.ACTION_LOOKUP_BACK);
    }

    private void sendLookupAction(String action) {
        Intent back = new Intent(this, FloatingService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(back);
            } else {
                startService(back);
            }
        } catch (Throwable ignored) {
            finishBridge();
        }
    }

    private void markHostAction(String action) {
        try {
            getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_lookup_host_action", action)
                    .putLong("last_lookup_host_action_time", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private String extractQuery(Intent source) {
        String query = "";
        if (source == null) return query;

        Uri data = source.getData();
        if (data != null) {
            String value = data.getQueryParameter("q");
            if (value == null || value.trim().isEmpty()) {
                value = data.getQueryParameter("query");
            }
            if (value != null) query = value.trim();
        }

        if (query.isEmpty()) {
            String extra = source.getStringExtra(FloatingService.EXTRA_LOOKUP_QUERY);
            if (extra != null) query = extra.trim();
        }

        if (query.isEmpty()) {
            String extra = source.getStringExtra("query");
            if (extra != null) query = extra.trim();
        }

        if (query.isEmpty()) {
            CharSequence text = source.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) query = text.toString().trim();
        }

        if (query.isEmpty()) {
            CharSequence text = source.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) query = text.toString().trim();
        }

        if (query.startsWith("intent://") || query.startsWith("jpdict://") || query.startsWith("jp-native://")) {
            try {
                Uri nested = Uri.parse(query);
                String nestedQuery = nested.getQueryParameter("q");
                if (nestedQuery == null || nestedQuery.trim().isEmpty()) {
                    nestedQuery = nested.getQueryParameter("query");
                }
                if (nestedQuery != null && !nestedQuery.trim().isEmpty()) {
                    query = nestedQuery.trim();
                }
            } catch (Throwable ignored) {}
        }

        return query;
    }

    private String extractMode(Intent source) {
        String mode = "";
        if (source != null) {
            Uri data = source.getData();
            if (data != null) {
                String value = data.getQueryParameter("mode");
                if (value != null) mode = value.trim();
            }
            if (mode.isEmpty()) {
                String extra = source.getStringExtra(FloatingService.EXTRA_LOOKUP_MODE);
                if (extra != null) mode = extra.trim();
            }
        }

        return FloatingService.LOOKUP_MODE_FLOAT.equalsIgnoreCase(mode)
                ? FloatingService.LOOKUP_MODE_FLOAT
                : FloatingService.LOOKUP_MODE_FULLSCREEN;
    }

    private void forward(Intent source) {
        if (showingSearch) {
            forwardSearch(source);
            return;
        }
        String query = extractQuery(source);
        String mode = extractMode(source);

        if (query.isEmpty()) {
            Toast.makeText(this, "查询链接没有提供 q 参数", Toast.LENGTH_SHORT).show();
            finishBridge();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, QuickLookupActivity.class)
                    .putExtra("query", query));
            finishBridge();
            return;
        }

        Intent show = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_SHOW_LOOKUP_OVERLAY)
                .putExtra(FloatingService.EXTRA_LOOKUP_QUERY, query)
                .putExtra(FloatingService.EXTRA_LOOKUP_MODE, mode)
                .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, true)
                .putExtra(FloatingService.EXTRA_LOOKUP_PRIVATE,
                        source.getBooleanExtra(FloatingService.EXTRA_LOOKUP_PRIVATE, false))
                .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH,
                        source.getBooleanExtra(FloatingService.EXTRA_RETURN_TO_SEARCH, false))
                .putExtra(FloatingService.EXTRA_SEARCH_QUERY,
                        source.getStringExtra(FloatingService.EXTRA_SEARCH_QUERY));

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(show);
            } else {
                startService(show);
            }
            markHostAction("HOST_FORWARD_LOOKUP:" + mode);
        } catch (Throwable t) {
            Toast.makeText(
                    this,
                    "查词浮层启动失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            finishBridge();
        }
    }

    private void forwardSearch(Intent source) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "没有悬浮窗权限，无法显示搜索浮层", Toast.LENGTH_SHORT).show();
            finishBridge();
            return;
        }
        Intent show = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_SHOW_SEARCH)
                .putExtra(FloatingService.EXTRA_LOOKUP_HOSTED, true)
                .putExtra(FloatingService.EXTRA_SEARCH_QUERY,
                        source.getStringExtra(FloatingService.EXTRA_SEARCH_QUERY));
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(show);
            else startService(show);
            markHostAction("HOST_FORWARD_SEARCH");
        } catch (Throwable error) {
            Toast.makeText(this, "搜索浮层启动失败", Toast.LENGTH_SHORT).show();
            finishBridge();
        }
    }

    private void finishBridge() {
        try {
            clearSystemBack();
            finish();
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {}
    }
}
