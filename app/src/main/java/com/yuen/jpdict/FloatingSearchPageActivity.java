package com.yuen.jpdict;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared lifecycle handling for pages opened from the floating search host. */
abstract class FloatingSearchPageActivity extends Activity {
    private BroadcastReceiver pageSessionReceiver;
    private android.window.OnBackInvokedCallback pageBack;

    @Override protected void onCreate(Bundle state) {
        AppTheme.prepare(this);
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        pageSessionReceiver = FloatingSearchRoutes.pageSessionReceiver(this);
        FloatingSearchRoutes.registerPageSessionReceiver(this, pageSessionReceiver);
        if (Build.VERSION.SDK_INT >= 33) {
            pageBack = this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, pageBack);
        }
    }

    protected final LinearLayout createPage(String title, boolean close) {
        return createPage(title, close, 48);
    }

    protected final LinearLayout createPage(String title, boolean close, int toolbarHeightDp) {
        AppTheme.window(this, AppTheme.PAGE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        AppTheme.background(root, AppTheme.PAGE);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(pageDp(6), 0, pageDp(16), 0);
        SearchIconView back = new SearchIconView(this,
                close ? SearchIconView.Kind.CLOSE : SearchIconView.Kind.BACK);
        back.setPadding(pageDp(11), pageDp(11), pageDp(11), pageDp(11));
        back.setContentDescription(close ? "关闭设置" : "返回");
        back.setOnClickListener(v -> onBackPressed());
        toolbar.addView(back, new LinearLayout.LayoutParams(pageDp(44), pageDp(44)));
        TextView label = new TextView(this);
        label.setText(title);
        AppTheme.text(label, AppTheme.INK);
        label.setTextSize(17);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setIncludeFontPadding(false);
        label.setPadding(pageDp(6), 0, 0, 0);
        toolbar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, pageDp(toolbarHeightDp)));
        return root;
    }

    private int pageDp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void setContentView(View view) {
        super.setContentView(view);
        notifySearchAfterFirstFrame(view);
        if (Build.VERSION.SDK_INT < 30) return;
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + Math.max(bars.bottom, keyboard));
            return WindowInsets.CONSUMED;
        });
        view.requestApplyInsets();
    }

    private void notifySearchAfterFirstFrame(View view) {
        Intent route = getIntent();
        String request = route == null ? null
                : route.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST);
        if (request == null || request.isEmpty()) return;
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                Runnable ready = () -> {
                    Intent current = getIntent();
                    if (isFinishing() || isDestroyed() || current == null
                            || !request.equals(current.getStringExtra(
                                    FloatingSearchRoutes.EXTRA_PAGE_REQUEST))) return;
                    Intent shown = new Intent(FloatingSearchPageActivity.this, FloatingService.class)
                            .setAction(FloatingSearchRoutes.ACTION_PAGE_READY)
                            .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, request);
                    if (current.hasExtra(FloatingService.EXTRA_HOST_SESSION)) {
                        shown.putExtra(FloatingService.EXTRA_HOST_SESSION,
                                current.getStringExtra(FloatingService.EXTRA_HOST_SESSION));
                    }
                    FloatingSearchRoutes.startService(FloatingSearchPageActivity.this, shown);
                };
                // Keep the search window covering the old page until this
                // Activity has submitted its own first frame to the display.
                if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated()) {
                    view.getViewTreeObserver().registerFrameCommitCallback(ready);
                } else {
                    view.postOnAnimation(ready);
                }
                return true;
            }
        });
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        FloatingSearchRoutes.onPageBackground(this);
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && pageBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(pageBack);
            pageBack = null;
        }
        if (pageSessionReceiver != null) {
            try { unregisterReceiver(pageSessionReceiver); }
            catch (RuntimeException ignored) {}
            pageSessionReceiver = null;
        }
        super.onDestroy();
    }
}
