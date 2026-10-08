package com.yuen.nativejpocr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Shared card bounds and gestures, independent of the lookup entry point. */
final class LookupOverlayWindow {
    private final WindowManager wm;
    private final View root;
    private final View card;
    private final WindowManager.LayoutParams window;
    private final boolean fullScreenRoot;
    private final SharedPreferences prefs;
    private final float density;
    private final int touchSlop;
    private final DisplayMetrics screen = new DisplayMetrics();
    private int topInset;
    private int bottomInset;
    private int x;
    private int y;
    private int width;
    private int height;

    LookupOverlayWindow(Context context, WindowManager wm, View root, View card,
            WindowManager.LayoutParams window, boolean fullScreenRoot, int topInset) {
        this.wm = wm;
        this.root = root;
        this.card = card;
        this.window = window;
        this.fullScreenRoot = fullScreenRoot;
        this.topInset = topInset;
        prefs = context.getSharedPreferences("lookup_overlay_window", Context.MODE_PRIVATE);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        wm.getDefaultDisplay().getRealMetrics(screen);
        int navId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        bottomInset = navId == 0 ? 0 : context.getResources().getDimensionPixelSize(navId);
        width = prefs.getInt("width", screen.widthPixels - dp(16));
        height = prefs.getInt("height", Math.round(screen.heightPixels * 0.72f));
        x = prefs.getInt("x", dp(8));
        y = prefs.getInt("y", topInset + dp(52));
        clamp();
        apply(false);
    }

    private int dp(int value) { return Math.round(value * density); }

    void fitSystemBars(int top, int bottom) {
        topInset = top;
        bottomInset = bottom;
        clamp();
        apply(true);
    }

    void installGestures(View header, View title, View footer) {
        View.OnTouchListener drag = new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = x;
                        startY = y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        moved |= Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop;
                        if (moved) {
                            x = startX + Math.round(dx);
                            y = startY + Math.round(dy);
                            clamp();
                            apply(true);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) save(); else view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) save();
                        return true;
                    default:
                        return true;
                }
            }
        };
        header.setOnTouchListener(drag);
        title.setOnTouchListener(drag);
        footer.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startWidth, startHeight;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startWidth = width;
                        startHeight = height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // Keep the top-left anchor fixed while resizing.
                        width = Math.min(screen.widthPixels - x - dp(8),
                                startWidth + Math.round(event.getRawX() - downX));
                        height = Math.min(screen.heightPixels - bottomInset - y - dp(8),
                                startHeight + Math.round(event.getRawY() - downY));
                        clamp();
                        apply(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        save();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void clamp() {
        int margin = dp(8);
        int minY = topInset + margin;
        int maxBottom = screen.heightPixels - bottomInset - margin;
        int maxWidth = Math.max(1, screen.widthPixels - 2 * margin);
        int maxHeight = Math.max(1, maxBottom - minY);
        width = Math.max(Math.min(dp(250), maxWidth), Math.min(width, maxWidth));
        height = Math.max(Math.min(dp(300), maxHeight), Math.min(height, maxHeight));
        x = Math.max(margin, Math.min(x, screen.widthPixels - width - margin));
        y = Math.max(minY, Math.min(y, maxBottom - height));
    }

    private void apply(boolean attached) {
        if (fullScreenRoot) {
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(width, height);
            bounds.leftMargin = x;
            bounds.topMargin = y;
            card.setLayoutParams(bounds);
        } else {
            window.width = width;
            window.height = height;
            window.x = x;
            // Small OCR windows are positioned relative to the system-bar
            // inset; the shared saved coordinates are display coordinates.
            window.y = y - topInset;
            if (attached && root.isAttachedToWindow()) wm.updateViewLayout(root, window);
        }
    }

    void save() {
        prefs.edit().putInt("width", width).putInt("height", height)
                .putInt("x", x).putInt("y", y).apply();
    }
}
