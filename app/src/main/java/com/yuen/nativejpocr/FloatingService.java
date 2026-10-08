package com.yuen.nativejpocr;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingService extends Service {
    private static final String CH = "floating_lookup";
    private static final int NOTIFY_ID = 51;
    public static final String ACTION_SHOW_SEARCH = "com.yuen.nativejpocr.SHOW_FLOATING_SEARCH";
    public static final String ACTION_SHOW_LOOKUP_OVERLAY =
            "com.yuen.nativejpocr.SHOW_LOOKUP_OVERLAY";
    public static final String ACTION_LOOKUP_BACK =
            "com.yuen.nativejpocr.LOOKUP_BACK";
    public static final String ACTION_LOOKUP_DISMISS =
            "com.yuen.nativejpocr.LOOKUP_DISMISS";
    public static final String EXTRA_LOOKUP_HOSTED = "lookup_hosted";
    public static final String EXTRA_HOST_SEARCH = "host_search";
    public static final String ACTION_LOOKUP_HOST_FINISH =
            "com.yuen.nativejpocr.LOOKUP_HOST_FINISH";
    public static final String ACTION_LOOKUP_HOST_SEARCH =
            "com.yuen.nativejpocr.LOOKUP_HOST_SEARCH";
    public static final String EXTRA_SEARCH_QUERY = "floating_search_query";
    public static final String EXTRA_LOOKUP_QUERY = "lookup_query";
    public static final String EXTRA_LOOKUP_MODE = "lookup_mode";
    public static final String EXTRA_RETURN_TO_SEARCH = "return_to_floating_search";
    public static final String EXTRA_POPUP_MODE = "quick_lookup_popup";
    public static final String LOOKUP_MODE_FLOAT = "float";
    public static final String LOOKUP_MODE_FULLSCREEN = "fullscreen";

    private static final ExecutorService LOOKUP_AI_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Map<String, String> LOOKUP_AI_CACHE =
            new ConcurrentHashMap<>();

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;

    private TextView targetBox;
    private WindowManager.LayoutParams targetLp;

    private View searchPanel;
    private WindowManager.LayoutParams searchPanelLp;
    private boolean searchPanelHosted;
    private View resultCard;
    private View lookupOverlay;
    private WindowManager.LayoutParams lookupOverlayLp;
    private String lookupOverlayQuery = "";
    private String lookupOverlayMode = LOOKUP_MODE_FLOAT;
    private boolean lookupOverlayReturnToSearch;
    private boolean lookupOverlayHosted;
    private LookupOverlayWindow lookupWindow;
    private View bubbleTopProxy;
    private WindowManager.LayoutParams bubbleTopProxyLp;

    private String currentSearchQuery = "";
    private boolean projectionRequestInFlight = false;
    private android.window.OnBackInvokedDispatcher searchBackDispatcher;
    private android.window.OnBackInvokedCallback searchBackCallback;
    private android.window.OnBackInvokedDispatcher lookupBackDispatcher;
    private android.window.OnBackInvokedCallback lookupBackCallback;
    private EditText lookupEditor;
    private View lookupHeaderNormal;
    private View lookupHeaderEdit;

    private final Handler main = new Handler(Looper.getMainLooper());

    private void markAction(String action) {
        try {
            getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_action", action)
                    .putLong("last_action_time", System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) {}
    }

    private void recordHandledCrash(String where, Throwable t) {
        try {
            StringBuilder out = new StringBuilder();
            out.append(where).append("\n")
                    .append(t.getClass().getName()).append(": ")
                    .append(t.getMessage() == null ? "" : t.getMessage()).append("\n");
            StackTraceElement[] trace = t.getStackTrace();
            int max = Math.min(trace.length, 32);
            for (int i = 0; i < max; i++) {
                out.append("  at ").append(trace[i].toString()).append("\n");
            }
            getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_handled", out.toString())
                    .commit();
        } catch (Throwable ignored) {}
    }

    private void safeToast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver ocrReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!MainActivity.ScreenCaptureService.ACTION_RESULT.equals(intent.getAction())) return;

            String text = intent.getStringExtra("text");
            String error = intent.getStringExtra("error");

            if (bubble != null) bubble.setVisibility(View.VISIBLE);

            if (error != null && !error.isEmpty()) {
                showResultCard("取词失败", error);
                return;
            }

            if (text == null || text.trim().isEmpty()) {
                showResultCard("没有识别到文字", "把取词框对准要识别的文字后再松手。");
                return;
            }

            // Drag OCR is a direct lookup action. Never route it through the
            // desktop global-search layer; show the lookup itself as a popup.
            removeSearchPanel();
            openOcrLookupPopup(text.trim());
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startAsForeground();

        IntentFilter filter = new IntentFilter(MainActivity.ScreenCaptureService.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ocrReceiver, filter);
        }

        if (Settings.canDrawOverlays(this)) showBubble();
        else stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CH, "多语种浮动取词", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("保持桌面可拖拽的中英日 OCR 取词浮标");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(c);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent p = PendingIntent.getActivity(
                this, 51, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("多语种浮动取词已开启")
                .setContentText("轻点搜索；拖动并松手可 OCR 取词")
                .setContentIntent(p)
                .setOngoing(true)
                .build();
    }

    private void startAsForeground() {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int topSystemBarInset() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                WindowInsets insets = wm.getMaximumWindowMetrics().getWindowInsets();
                return insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() |
                                WindowInsets.Type.displayCutout()).top;
            } catch (Throwable ignored) {}
        }

        int id = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private GradientDrawable bg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable outline(int strokeColor, int strokeDp, int fillColor, int radiusDp) {
        GradientDrawable g = bg(fillColor, radiusDp);
        g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private final class BackGestureFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private boolean trackingEdge;
        private boolean backRecognized;

        BackGestureFrameLayout(Context context) {
            super(context);
            setFocusableInTouchMode(true);
        }

        private boolean isFromEdge(float x) {
            int edge = dp(34);
            return x <= edge || x >= Math.max(edge, getWidth() - edge);
        }

        private boolean isBackDistance(float x, float y) {
            float dx = x - downX;
            float dy = y - downY;
            boolean inward = downX <= dp(34) ? dx >= dp(72) : dx <= -dp(72);
            return inward && Math.abs(dx) > Math.abs(dy) * 1.15f;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (searchPanelHosted || searchBackDispatcher != null) return super.onInterceptTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    trackingEdge = isFromEdge(downX);
                    backRecognized = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (trackingEdge && isBackDistance(e.getX(), e.getY())) {
                        backRecognized = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    trackingEdge = false;
                    backRecognized = false;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (searchPanelHosted || searchBackDispatcher != null) return super.onTouchEvent(e);
            if (!trackingEdge && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
                trackingEdge = isFromEdge(downX);
            }

            if (trackingEdge && e.getActionMasked() == MotionEvent.ACTION_MOVE &&
                    isBackDistance(e.getX(), e.getY())) backRecognized = true;

            // Keep the Back owner alive until completion. CANCEL means the
            // system owns this gesture and must dispatch it to our callback.
            if (trackingEdge && backRecognized &&
                    e.getActionMasked() == MotionEvent.ACTION_UP) {
                markAction("SEARCH_EDGE_BACK");
                removeSearchPanel();
                trackingEdge = false;
                backRecognized = false;
                return true;
            }

            if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                trackingEdge = false;
                backRecognized = false;
            }
            return trackingEdge || super.onTouchEvent(e);
        }
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    private class OverlayEditor extends EditText {
        OverlayEditor(Context context) { super(context); }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus && isFocused()) {
                post(() -> {
                    if (!hasWindowFocus() || !isFocused()) return;
                    InputMethodManager imm =
                            (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
                });
            }
        }
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    private final class SearchEditor extends OverlayEditor {
        SearchEditor(Context context) { super(context); }

        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    removeSearchPanel();
                }
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    // This app intentionally uses platform widgets without AppCompat.
    @android.annotation.SuppressLint("AppCompatCustomView")
    private final class LookupEditor extends OverlayEditor {
        LookupEditor(Context context) { super(context); }

        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    handleLookupBack();
                }
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    private final class LookupOverlayFrameLayout extends FrameLayout {
        private float edgeDownX;
        private float edgeDownY;
        private boolean trackingEdgeBack;
        private boolean edgeFromLeft;
        private boolean backConsumed;

        LookupOverlayFrameLayout(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private boolean isFromEdge(float x) {
            int edge = dp(34);
            return x <= edge || x >= Math.max(edge, getWidth() - edge);
        }

        private boolean isBackSwipe(float x, float y) {
            float dx = x - edgeDownX;
            float dy = y - edgeDownY;
            boolean inward = edgeFromLeft ? dx >= dp(70) : dx <= -dp(70);
            return inward && Math.abs(dx) > Math.abs(dy) * 1.15f;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP &&
                        event.getRepeatCount() == 0) {
                    markAction("LOOKUP_KEY_BACK");
                    handleLookupBack();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            // A hosted lookup uses the system's single Back gesture owner.
            // Running a second recognizer here can close the caller as well.
            if (lookupOverlayHosted || lookupBackDispatcher != null) {
                return super.onInterceptTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeDownX = event.getX();
                    edgeDownY = event.getY();
                    trackingEdgeBack = isFromEdge(edgeDownX);
                    edgeFromLeft = edgeDownX <= dp(34);
                    backConsumed = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (trackingEdgeBack &&
                            !backConsumed &&
                            isBackSwipe(event.getX(), event.getY())) {
                        backConsumed = true;
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    trackingEdgeBack = false;
                    backConsumed = false;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (lookupOverlayHosted || lookupBackDispatcher != null) {
                return super.onTouchEvent(event);
            }
            if (trackingEdgeBack && !backConsumed &&
                    event.getActionMasked() == MotionEvent.ACTION_MOVE &&
                    isBackSwipe(event.getX(), event.getY())) {
                backConsumed = true;
            }

            // Commit only on UP. System Back steals the stream with CANCEL;
            // closing on MOVE would also send that same gesture to the caller.
            if (backConsumed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                markAction("LOOKUP_EDGE_BACK");
                handleLookupBack();
                trackingEdgeBack = false;
                backConsumed = false;
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                trackingEdgeBack = false;
                backConsumed = false;
            }

            return trackingEdgeBack || backConsumed || super.onTouchEvent(event);
        }
    }

    private void showBubble() {
        if (bubble != null) return;

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);

        FrameLayout bubbleView = new FrameLayout(this);
        bubbleView.setBackgroundResource(R.drawable.floating_bubble_bg);
        bubbleView.setElevation(dp(12));

        TextView kana = new TextView(this);
        kana.setText("あ");
        kana.setTextColor(Color.WHITE);
        kana.setTextSize(22);
        kana.setTypeface(Typeface.DEFAULT_BOLD);
        kana.setGravity(Gravity.CENTER);
        bubbleView.addView(kana, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView searchMark = new ImageView(this);
        searchMark.setImageResource(android.R.drawable.ic_menu_search);
        searchMark.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams searchMarkLp = new FrameLayout.LayoutParams(dp(18), dp(18));
        searchMarkLp.gravity = Gravity.END | Gravity.BOTTOM;
        searchMarkLp.rightMargin = dp(4);
        searchMarkLp.bottomMargin = dp(4);
        bubbleView.addView(searchMark, searchMarkLp);

        bubble = bubbleView;

        bubbleLp = new WindowManager.LayoutParams(
                dp(54), dp(54), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences sp = getSharedPreferences("float_pos", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleLp.x = sp.getInt("x", Math.max(0, dm.widthPixels - dp(74)));
        bubbleLp.y = sp.getInt("y", Math.max(dp(120), dm.heightPixels / 2));

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            long downTime;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        downTime = System.currentTimeMillis();
                        moved = false;
                        projectionRequestInFlight = false;
                        removeResultCard();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int nx = startX + Math.round(e.getRawX() - downRawX);
                        int ny = startY + Math.round(e.getRawY() - downRawY);

                        if (Math.abs(e.getRawX() - downRawX) > dp(5) ||
                                Math.abs(e.getRawY() - downRawY) > dp(5)) {
                            if (!moved) {
                                moved = true;
                                removeSearchPanel();
                                if (MainActivity.ScreenCaptureService.READY) {
                                    showTargetBox();
                                } else {
                                    requestProjectionPermissionNow();
                                }
                            }
                        }

                        DisplayMetrics d = getResources().getDisplayMetrics();
                        nx = Math.max(0, Math.min(nx, d.widthPixels - dp(54)));
                        ny = Math.max(0, Math.min(ny, d.heightPixels - dp(54)));

                        bubbleLp.x = nx;
                        bubbleLp.y = ny;

                        try {
                            wm.updateViewLayout(bubble, bubbleLp);
                        } catch (Exception ignored) {}

                        if (moved) updateTargetBox();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getSharedPreferences("float_pos", MODE_PRIVATE).edit()
                                .putInt("x", bubbleLp.x)
                                .putInt("y", bubbleLp.y)
                                .apply();

                        if (!moved && System.currentTimeMillis() - downTime < 650) {
                            hideTargetBox();
                            if (lookupOverlay != null) {
                                markAction("BUBBLE_TAP_CLOSE_LOOKUP_OVERLAY");
                                closeLookupOverlay();
                            } else {
                                markAction("BUBBLE_TAP_SHOW_SEARCH");
                                toggleSearchPanel();
                            }
                        } else if (moved) {
                            if (MainActivity.ScreenCaptureService.READY && targetLp != null) {
                                triggerPickup();
                            } else {
                                hideTargetBox();
                            }
                        }
                        return true;
                    }
                    return false;
                } catch (Throwable t) {
                    recordHandledCrash("FloatingService.onTouch", t);
                    safeToast("悬浮球操作异常：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " · " + t.getMessage()));
                    hideTargetBox();
                    return true;
                }
            }
        });

        wm.addView(bubble, bubbleLp);
    }

    private void showTargetBox() {
        if (targetBox != null) return;

        targetBox = new TextView(this);
        targetBox.setText("  对准单词 · 松手取词  ");
        targetBox.setTextColor(Color.WHITE);
        targetBox.setTextSize(13);
        targetBox.setGravity(Gravity.CENTER);
        targetBox.setBackground(outline(0xff29a8ff, 2, 0x77090d14, 8));

        targetLp = new WindowManager.LayoutParams(
                dp(190), dp(58), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        targetLp.gravity = Gravity.TOP | Gravity.START;

        updateTargetCoordinates();
        wm.addView(targetBox, targetLp);
    }

    private void updateTargetBox() {
        if (targetBox == null || targetLp == null) return;
        updateTargetCoordinates();
        try {
            wm.updateViewLayout(targetBox, targetLp);
        } catch (Exception ignored) {}
    }

    private void updateTargetCoordinates() {
        DisplayMetrics d = getResources().getDisplayMetrics();

        int targetWidth = dp(190);
        int targetHeight = dp(58);

        int centerX = bubbleLp.x + dp(27);
        int x = centerX - targetWidth / 2;
        int y = bubbleLp.y - targetHeight - dp(18);

        x = Math.max(0, Math.min(x, d.widthPixels - targetWidth));
        y = Math.max(dp(4), Math.min(y, d.heightPixels - targetHeight - dp(4)));

        targetLp.x = x;
        targetLp.y = y;
    }

    private void hideTargetBox() {
        if (targetBox != null && wm != null) {
            try {
                wm.removeView(targetBox);
            } catch (Exception ignored) {}
        }

        targetBox = null;
        targetLp = null;
    }

    private void triggerPickup() {
        if (targetLp == null) return;

        final int x = targetLp.x;
        final int y = targetLp.y;
        final int width = targetLp.width;
        final int height = targetLp.height;

        hideTargetBox();

        if (!MainActivity.ScreenCaptureService.READY) {
            requestProjectionPermissionNow();
            return;
        }

        Intent i = new Intent(this, MainActivity.ScreenCaptureService.class)
                .setAction(MainActivity.ScreenCaptureService.ACT_OCR_REGION)
                .putExtra("x", x)
                .putExtra("y", y)
                .putExtra("w", width)
                .putExtra("h", height);

        // The OCR target rectangle is positioned away from the bubble. Keep the
        // bubble mounted and visible so pickup never flashes off/on.
        startService(i);
    }

    private void toggleSearchPanel() {
        try {
            if (searchPanel != null) {
                markAction("BUBBLE_TAP_CLOSE_SEARCH");
                removeSearchPanel();
                return;
            }
            markAction("SHOW_SEARCH_PANEL_BEGIN");
            openSearchHost();
            markAction("SHOW_SEARCH_PANEL_OK");
        } catch (Throwable t) {
            searchPanel = null;
            recordHandledCrash("toggleSearchPanel", t);
            safeToast("搜索页异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " · " + t.getMessage()));
        }
    }

    private void requestProjectionPermissionNow() {
        if (MainActivity.ScreenCaptureService.READY || projectionRequestInFlight) return;
        projectionRequestInFlight = true;
        markAction("OPEN_MEDIA_PROJECTION_PERMISSION");

        Intent i = new Intent(this, MainActivity.OcrActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            projectionRequestInFlight = false;
            showResultCard("授权页打开失败",
                    e.getMessage() == null ? "未知错误" : e.getMessage());
        }
    }

    private void openSearchHost() {
        Intent host = new Intent(this, LookupLinkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_HOST_SEARCH, true)
                .putExtra(EXTRA_SEARCH_QUERY, currentSearchQuery);
        try {
            startActivity(host);
        } catch (Throwable error) {
            recordHandledCrash("openSearchHost", error);
            safeToast("搜索浮层启动失败");
        }
    }

    private void showSearchPanel() {
        if (!Settings.canDrawOverlays(this) || wm == null) return;

        removeResultCard();

        BackGestureFrameLayout root = new BackGestureFrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        View mask = new View(this);
        mask.setBackgroundColor(searchPanelHosted ? Color.TRANSPARENT : 0x6b000000);
        mask.setContentDescription("点击关闭搜索浮窗");
        mask.setOnClickListener(v -> removeSearchPanel());
        root.addView(mask, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.getAction() == KeyEvent.ACTION_UP) {
                markAction("SEARCH_KEY_BACK");
                removeSearchPanel();
                return true;
            }
            return false;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        contentLp.leftMargin = dp(15);
        contentLp.rightMargin = dp(15);
        contentLp.topMargin = dp(6);
        root.addView(content, contentLp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(4), 0);
        bar.setBackground(outline(0xff079bff, 1, 0xf218191d, 10));
        bar.setElevation(dp(4));

        EditText input = new SearchEditor(this);
        input.setSingleLine(true);
        input.setTextColor(0xfff4f5f7);
        input.setHintTextColor(0xff969ca6);
        input.setHint("请输入需要查找的内容");
        input.setTextSize(18);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSelectAllOnFocus(false);
        input.setPadding(0, 0, dp(8), 0);

        TextView clear = new TextView(this);
        clear.setText("×");
        clear.setTextColor(0xff079bff);
        clear.setTextSize(31);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(0, 0, 0, dp(2));

        bar.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
        bar.addView(clear, new LinearLayout.LayoutParams(dp(42), dp(44)));

        content.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackground(bg(0xe6101820, 10));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        suggestions.setPadding(0, dp(8), 0, dp(56));
        scroll.addView(suggestions, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(2);
        content.addView(scroll, scrollLp);

        // The transparent host dims system bars; this window owns search UI
        // and mask touches throughout the display, including its bottom edge.
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                (searchPanelHosted ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

        searchPanel = root;
        searchPanelLp = lp;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        markAction("SHOW_SEARCH_PANEL_ADD_VIEW");
        try {
            wm.addView(searchPanel, lp);
        } catch (Throwable firstError) {
            searchPanel = null;
            showResultCard(
                    "搜索悬浮层打开失败",
                    firstError.getClass().getSimpleName() + ": "
                            + (firstError.getMessage() == null ? "未知窗口错误" : firstError.getMessage()));
            return;
        }

        if (!searchPanelHosted) registerSearchBackCallback();

        markAction("SHOW_SEARCH_PANEL_UPDATE_SUGGESTIONS");
        String initialQuery = currentSearchQuery == null ? "" : currentSearchQuery;
        if (!initialQuery.isEmpty()) {
            input.setText(initialQuery);
            input.setSelection(input.length());
        }
        updateSuggestions(suggestions, initialQuery);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s == null ? "" : s.toString();
                updateSuggestions(suggestions, currentSearchQuery);
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = input.getText().toString().trim();
                if (!q.isEmpty()) {
                    removeSearchPanel(false);
                    openQuickLookup(q);
                }
                return true;
            }
            return false;
        });

        input.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                searchPanelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                searchPanelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
                wm.updateViewLayout(root, searchPanelLp);
                registerSearchBackCallback();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                input.requestFocus();
                input.post(() -> {
                    if (searchPanel != root) return;
                    InputMethodManager imm =
                            (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                });
            }
            return false;
        });

        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.getAction() == KeyEvent.ACTION_UP) {
                markAction("SEARCH_INPUT_BACK");
                removeSearchPanel();
                return true;
            }
            return false;
        });

        clear.setOnClickListener(v -> {
            if (input.length() > 0) {
                input.setText("");
                input.requestFocus();
            } else {
                removeSearchPanel();
            }
        });

        markAction("SHOW_SEARCH_PANEL_FINISH");
        root.requestFocus();
        bringBubbleToFront();
    }

    private void updateSuggestions(LinearLayout suggestions, String raw) {
        suggestions.removeAllViews();

        String q = raw == null ? "" : raw.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        Set<String> shown = new LinkedHashSet<>();
        int count = 0;

        List<String> history = recentHistory();

        if (q.isEmpty()) {
            for (String item : history) {
                if (item.isEmpty() || !shown.add(item)) continue;
                suggestions.addView(createSuggestionRow(
                        item,
                        lookupSubtitle(item),
                        true));
                count++;
                if (count >= 12) break;
            }

            if (count < 12) {
                for (Map.Entry<String, String[]> e : MainActivity.WORDS.entrySet()) {
                    String word = e.getKey();
                    if (!shown.add(word)) continue;
                    suggestions.addView(createSuggestionRow(
                            word,
                            formatSubtitle(e.getValue()),
                            false));
                    count++;
                    if (count >= 12) break;
                }
            }
        } else {
            for (String item : history) {
                if (!item.toLowerCase(Locale.ROOT).contains(lower)) continue;
                if (!shown.add(item)) continue;
                suggestions.addView(createSuggestionRow(
                        item,
                        lookupSubtitle(item),
                        true));
                count++;
                if (count >= 10) break;
            }

            for (Map.Entry<String, String[]> e : MainActivity.WORDS.entrySet()) {
                String word = e.getKey();
                String[] d = e.getValue();

                boolean match =
                        word.contains(q) ||
                        d[0].contains(q) ||
                        d[1].toLowerCase(Locale.ROOT).contains(lower) ||
                        d[3].contains(q);

                if (!match || !shown.add(word)) continue;

                suggestions.addView(createSuggestionRow(
                        word,
                        formatSubtitle(d),
                        false));
                count++;
                if (count >= 10) break;
            }

            if (!shown.contains(q)) {
                suggestions.addView(createSuggestionRow(
                        q,
                        "搜索「" + q + "」",
                        false));
                count++;
            }
        }

        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("输入日语、中文或英文进行查询");
            empty.setTextColor(0xffa4aab3);
            empty.setTextSize(16);
            empty.setPadding(dp(56), dp(28), dp(12), dp(20));
            suggestions.addView(empty);
        }
    }

    private String formatSubtitle(String[] data) {
        if (data == null || data.length < 4) return "最近查询";
        return data[2] + ". " + data[3] + "  ·  " + data[0];
    }

    private String lookupSubtitle(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : MainActivity.WORDS.entrySet()) {
            String[] d = e.getValue();
            if (e.getKey().equals(query) ||
                    d[0].equals(query) ||
                    d[1].toLowerCase(Locale.ROOT).equals(lower)) {
                return formatSubtitle(d);
            }
        }
        return "最近查询";
    }

    private List<String> recentHistory() {
        String saved = getSharedPreferences("lookup_history", MODE_PRIVATE)
                .getString("items", "");
        List<String> out = new ArrayList<>();
        if (saved == null || saved.isEmpty()) return out;

        for (String line : saved.split("\\n")) {
            String item = line.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    private View createSuggestionRow(String word, String subtitle, boolean historyStyle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(4), dp(2), dp(4));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setMinimumHeight(dp(54));

        ImageView icon = new ImageView(this);
        icon.setImageResource(historyStyle
                ? R.drawable.ic_history_clock
                : R.drawable.ic_dictionary_result);
        icon.setColorFilter(0xffd3d9df);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(dp(38), dp(40));
        row.addView(icon, iconLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(2), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(word);
        title.setTextColor(0xfff0f2f4);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView meaning = new TextView(this);
        meaning.setText(subtitle == null ? "" : subtitle);
        meaning.setTextColor(0xff9ea5ae);
        meaning.setTextSize(13);
        meaning.setMaxLines(2);
        meaning.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meaning.setPadding(0, dp(1), 0, 0);

        body.addView(title);
        if (subtitle != null && !subtitle.isEmpty()) body.addView(meaning);

        row.addView(body, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> {
            removeSearchPanel(false);
            openQuickLookup(word);
        });

        return row;
    }

    private View createBubbleVisual() {
        FrameLayout bubbleView = new FrameLayout(this);
        bubbleView.setBackgroundResource(R.drawable.floating_bubble_bg);
        bubbleView.setElevation(dp(12));

        TextView kana = new TextView(this);
        kana.setText("あ");
        kana.setTextColor(Color.WHITE);
        kana.setTextSize(22);
        kana.setTypeface(Typeface.DEFAULT_BOLD);
        kana.setGravity(Gravity.CENTER);
        bubbleView.addView(kana, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView searchMark = new ImageView(this);
        searchMark.setImageResource(android.R.drawable.ic_menu_search);
        searchMark.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams searchMarkLp =
                new FrameLayout.LayoutParams(dp(18), dp(18));
        searchMarkLp.gravity = Gravity.END | Gravity.BOTTOM;
        searchMarkLp.rightMargin = dp(4);
        searchMarkLp.bottomMargin = dp(4);
        bubbleView.addView(searchMark, searchMarkLp);

        return bubbleView;
    }

    private void ensureBubbleProxyOnTop() {
        if (bubble == null || bubbleLp == null || wm == null) return;

        if (bubbleTopProxy != null && bubbleTopProxyLp != null) {
            bubbleTopProxyLp.x = bubbleLp.x;
            bubbleTopProxyLp.y = bubbleLp.y;
            try {
                wm.updateViewLayout(bubbleTopProxy, bubbleTopProxyLp);
            } catch (Exception ignored) {}
            return;
        }

        View proxy = createBubbleVisual();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(54), dp(54), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bubbleLp.x;
        lp.y = bubbleLp.y;

        proxy.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            int startX;
            int startY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = bubbleTopProxyLp == null ? bubbleLp.x : bubbleTopProxyLp.x;
                        startY = bubbleTopProxyLp == null ? bubbleLp.y : bubbleTopProxyLp.y;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - downRawX);
                        int dy = Math.round(e.getRawY() - downRawY);
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;

                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int nx = Math.max(0, Math.min(startX + dx, dm.widthPixels - dp(54)));
                        int ny = Math.max(0, Math.min(startY + dy, dm.heightPixels - dp(54)));

                        bubbleLp.x = nx;
                        bubbleLp.y = ny;
                        if (bubbleTopProxyLp != null) {
                            bubbleTopProxyLp.x = nx;
                            bubbleTopProxyLp.y = ny;
                        }

                        try {
                            wm.updateViewLayout(bubble, bubbleLp);
                        } catch (Exception ignored) {}
                        try {
                            if (bubbleTopProxy != null && bubbleTopProxyLp != null) {
                                wm.updateViewLayout(bubbleTopProxy, bubbleTopProxyLp);
                            }
                        } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getSharedPreferences("float_pos", MODE_PRIVATE)
                                .edit()
                                .putInt("x", bubbleLp.x)
                                .putInt("y", bubbleLp.y)
                                .apply();

                        if (!moved) {
                            if (lookupOverlay != null) {
                                closeLookupOverlay();
                            } else if (searchPanel != null) {
                                removeSearchPanel();
                            }
                        }
                        return true;

                    default:
                        return true;
                }
            }
        });

        bubbleTopProxy = proxy;
        bubbleTopProxyLp = lp;
        try {
            wm.addView(proxy, lp);
        } catch (Exception e) {
            bubbleTopProxy = null;
            bubbleTopProxyLp = null;
        }
    }

    private void removeBubbleProxyIfUnused() {
        if (lookupOverlay != null || searchPanel != null) return;
        if (bubbleTopProxy != null && wm != null) {
            try {
                wm.removeView(bubbleTopProxy);
            } catch (Exception ignored) {}
        }
        bubbleTopProxy = null;
        bubbleTopProxyLp = null;
    }

    private void bringBubbleToFront() {
        // Do not remove/re-add the real bubble: that caused a visible blink.
        // A temporary identical proxy window is added above the content layer
        // while the real bubble stays mounted continuously underneath.
        ensureBubbleProxyOnTop();
    }

    private void openQuickLookup(String q) {
        String value = q == null ? "" : q.trim();
        if (value.isEmpty()) return;
        Intent host = new Intent(this, LookupLinkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_LOOKUP_QUERY, value)
                .putExtra(EXTRA_RETURN_TO_SEARCH, true)
                .putExtra(EXTRA_SEARCH_QUERY, currentSearchQuery);
        try {
            startActivity(host);
        } catch (Throwable error) {
            recordHandledCrash("openLookupHost", error);
            safeToast("查词小窗启动失败");
        }
    }

    private void openOcrLookupPopup(String q) {
        dispatchLookupOverlay(q, false);
    }

    private void dispatchLookupOverlay(String q, boolean returnToSearch) {
        String value = q == null ? "" : q.trim();
        if (value.isEmpty()) return;

        markAction("INTENT_SHOW_LOOKUP_OVERLAY:" + value);

        Intent i = new Intent(this, FloatingService.class)
                .setAction(ACTION_SHOW_LOOKUP_OVERLAY)
                .putExtra(EXTRA_LOOKUP_QUERY, value)
                .putExtra(EXTRA_LOOKUP_MODE, LOOKUP_MODE_FLOAT)
                .putExtra(EXTRA_RETURN_TO_SEARCH, returnToSearch);

        if (returnToSearch) {
            String returnQuery = currentSearchQuery == null || currentSearchQuery.trim().isEmpty()
                    ? value
                    : currentSearchQuery.trim();
            i.putExtra(EXTRA_SEARCH_QUERY, returnQuery);
        }

        try {
            startService(i);
        } catch (Throwable e) {
            recordHandledCrash("dispatchLookupOverlay", e);
            safeToast("查词浮层打开失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " · " + e.getMessage()));
        }
    }

    private TextView lookupText(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private ImageView lookupIcon(int drawable, String description, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setColorFilter(tint);
        icon.setContentDescription(description);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        return icon;
    }

    private String normalizeLookupQuery(String rawQuery) {
        String value = rawQuery == null ? "" : rawQuery.trim();
        if (value.isEmpty()) return "";

        if (value.startsWith("intent://") || value.startsWith("jp-native://")) {
            try {
                android.net.Uri uri = android.net.Uri.parse(value);
                String nested = uri.getQueryParameter("q");
                if (nested == null || nested.trim().isEmpty()) {
                    nested = uri.getQueryParameter("query");
                }
                if (nested != null && !nested.trim().isEmpty()) {
                    return nested.trim();
                }
            } catch (Throwable ignored) {}
        }
        return value;
    }

    private String normalizeLookupMode(String rawMode) {
        return LOOKUP_MODE_FULLSCREEN.equalsIgnoreCase(
                rawMode == null ? "" : rawMode.trim())
                ? LOOKUP_MODE_FULLSCREEN
                : LOOKUP_MODE_FLOAT;
    }

    private void clearLookupBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 &&
                lookupBackDispatcher != null &&
                lookupBackCallback != null) {
            try {
                lookupBackDispatcher.unregisterOnBackInvokedCallback(
                        lookupBackCallback);
            } catch (Throwable ignored) {}
        }
        lookupBackDispatcher = null;
        lookupBackCallback = null;
    }

    private void registerLookupBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || lookupOverlay == null) return;
        View owner = lookupOverlay;
        owner.post(() -> {
            if (lookupOverlay != owner || (lookupOverlayHosted && lookupEditor == null)) return;
            try {
                android.window.OnBackInvokedDispatcher dispatcher =
                        owner.findOnBackInvokedDispatcher();
                if (dispatcher != null) {
                    clearLookupBackCallback();
                    android.window.OnBackInvokedCallback callback = () -> {
                        markAction("LOOKUP_OVERLAY_SYSTEM_BACK");
                        handleLookupBack();
                    };
                    dispatcher.registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
                    lookupBackDispatcher = dispatcher;
                    lookupBackCallback = callback;
                }
            } catch (Throwable t) {
                recordHandledCrash("registerLookupBackCallback", t);
            }
        });
    }

    private void setLookupOverlayFocusable(boolean focusable) {
        if (lookupOverlay == null || lookupOverlayLp == null || wm == null) return;

        lookupOverlayLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                (focusable ? WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                        : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        if (focusable) {
            lookupOverlayLp.flags &=
                    ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                      WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        } else if (lookupOverlayHosted) {
            lookupOverlayLp.flags |=
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lookupOverlayLp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else {
            // OCR has no Activity Back host. Keep its window focused for Back,
            // while staying independent of the IME outside headword editing.
            lookupOverlayLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lookupOverlayLp.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }

        try {
            wm.updateViewLayout(lookupOverlay, lookupOverlayLp);
        } catch (Throwable t) {
            recordHandledCrash("setLookupOverlayFocusable", t);
        }

        if (focusable || !lookupOverlayHosted) {
            lookupOverlay.requestFocus();
            registerLookupBackCallback();
        } else {
            clearLookupBackCallback();
            lookupOverlay.clearFocus();
        }
    }

    private void showLookupOverlay(
            String rawQuery,
            boolean returnToSearch,
            String rawMode) {
        // This is the single result-card renderer for floating search,
        // PROCESS_TEXT/SEND and Deep Link lookup. Mode changes only the window
        // shell and Back host, never the card layout or saved card bounds.
        if (!Settings.canDrawOverlays(this) || wm == null) {
            safeToast("没有悬浮窗权限，无法显示查词浮层");
            return;
        }

        String q = normalizeLookupQuery(rawQuery);
        String mode = normalizeLookupMode(rawMode);
        if (q.isEmpty()) return;

        removeLookupOverlay(false);
        removeSearchPanel(false);
        removeResultCard();

        lookupOverlayQuery = q;
        lookupOverlayMode = mode;
        lookupOverlayReturnToSearch = returnToSearch;
        saveLookupHistory(q);

        LookupOverlayFrameLayout root = new LookupOverlayFrameLayout(this);
        boolean fullscreen = lookupOverlayHosted || LOOKUP_MODE_FULLSCREEN.equals(mode);
        root.setBackgroundColor(Color.TRANSPARENT);

        View fullscreenMask = null;
        if (fullscreen) {
            fullscreenMask = new View(this);
            fullscreenMask.setBackgroundColor(Color.BLACK);
            // The Activity supplies the pixels, including both system bars.
            // Keep one touch target here without doubling the mask opacity.
            fullscreenMask.setAlpha(lookupOverlayHosted ? 0f : 0.42f);
            fullscreenMask.setClickable(true);
            fullscreenMask.setFocusable(false);
            fullscreenMask.setContentDescription("点击关闭查词浮层");
            fullscreenMask.setOnClickListener(v -> {
                markAction("LOOKUP_MASK_DISMISS");
                closeLookupOverlay();
            });
            root.addView(fullscreenMask, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(outline(0x16000000, 1, Color.WHITE, 10));
        panel.setElevation(dp(12));

        // Reference proportions: 48dp title, 46dp pronunciation and toolbar
        // rows, 40dp dictionary headers, and a plain 30dp copyright footer.
        FrameLayout headerHost = new FrameLayout(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), 0, dp(4), 0);

        TextView title = lookupText(q, 20, 0xff242629);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        title.setClickable(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        ImageView favorite = lookupIcon(
                R.drawable.ic_lookup_star, "收藏", 0xff242629);
        ImageView replay = lookupIcon(
                R.drawable.ic_lookup_replay, "重新查询", 0xff242629);
        ImageView list = lookupIcon(
                R.drawable.ic_lookup_list, "词典列表", 0xff242629);
        ImageView more = lookupIcon(
                R.drawable.ic_lookup_plus, "更多", 0xff242629);

        header.addView(favorite, new LinearLayout.LayoutParams(dp(48), dp(48)));

        replay.setOnClickListener(v -> {
            LOOKUP_AI_CACHE.remove(q);
            showLookupOverlay(q, returnToSearch, mode);
        });

        LinearLayout editHeader = new LinearLayout(this);
        editHeader.setOrientation(LinearLayout.HORIZONTAL);
        editHeader.setGravity(Gravity.CENTER_VERTICAL);
        editHeader.setPadding(dp(14), 0, dp(8), 0);
        editHeader.setBackgroundColor(Color.WHITE);
        editHeader.setVisibility(View.GONE);

        EditText editor = new LookupEditor(this);
        editor.setSingleLine(true);
        editor.setText(q);
        editor.setTextSize(18);
        editor.setTextColor(0xff242629);
        editor.setSelectAllOnFocus(false);
        editor.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editor.setPadding(0, 0, dp(8), 0);
        if (Build.VERSION.SDK_INT >= 21) {
            editor.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xff129fe8));
        }

        TextView confirm = lookupText("确认", 15, Color.WHITE);
        confirm.setGravity(Gravity.CENTER);
        confirm.setIncludeFontPadding(false);
        confirm.setBackground(bg(0xff129fe8, 4));

        editHeader.addView(editor, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams confirmLp =
                new LinearLayout.LayoutParams(dp(54), dp(34));
        confirmLp.leftMargin = dp(8);
        editHeader.addView(confirm, confirmLp);

        headerHost.addView(header, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(48)));
        headerHost.addView(editHeader, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(48)));

        Runnable enterEditMode = () -> {
            lookupEditor = editor;
            lookupHeaderNormal = header;
            lookupHeaderEdit = editHeader;
            header.setVisibility(View.GONE);
            editHeader.setVisibility(View.VISIBLE);

            // Normal lookup mode intentionally leaves the WindowManager overlay
            // NOT_FOCUSABLE so Android Back belongs to the transparent host.
            // Only editing temporarily takes focus for IME input.
            setLookupOverlayFocusable(true);

            editor.setText(lookupOverlayQuery);
            editor.setSelection(editor.length());
            editor.requestFocus();
            editor.post(() -> {
                InputMethodManager imm =
                        (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            });
        };
        title.setOnClickListener(v -> enterEditMode.run());

        Runnable submitEdit = () -> {
            String next = editor.getText().toString().trim();
            if (next.isEmpty()) {
                exitLookupEditMode();
                return;
            }

            InputMethodManager imm =
                    (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            lookupEditor = null;
            lookupHeaderNormal = null;
            lookupHeaderEdit = null;

            if (next.equals(q)) {
                header.setVisibility(View.VISIBLE);
                editHeader.setVisibility(View.GONE);
                setLookupOverlayFocusable(false);
            } else {
                showLookupOverlay(next, returnToSearch, mode);
            }
        };

        confirm.setOnClickListener(v -> submitEdit.run());
        editor.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null &&
                     event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                     event.getAction() == KeyEvent.ACTION_UP)) {
                submitEdit.run();
                return true;
            }
            return false;
        });

        panel.addView(headerHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        View topDivider = new View(this);
        topDivider.setBackgroundColor(0xffeeeeef);
        panel.addView(topDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        String[] local = findLocalLookup(q);
        LinearLayout pronunciation = new LinearLayout(this);
        pronunciation.setOrientation(LinearLayout.HORIZONTAL);
        pronunciation.setGravity(Gravity.CENTER_VERTICAL);
        pronunciation.setPadding(dp(14), 0, dp(14), 0);

        ImageView speaker = lookupIcon(
                R.drawable.ic_lookup_speaker, "发音", 0xff00a7eb);
        speaker.setPadding(dp(2), dp(10), dp(8), dp(10));
        pronunciation.addView(speaker, new LinearLayout.LayoutParams(dp(34), dp(46)));

        String pronunciationText = local == null
                ? "发音"
                : local[0] + "   /" + local[1] + "/   " + local[2];
        TextView pronounceLabel = lookupText(pronunciationText, 16, 0xff777b80);
        pronounceLabel.setGravity(Gravity.CENTER_VERTICAL);
        pronounceLabel.setIncludeFontPadding(false);
        pronunciation.addView(pronounceLabel, new LinearLayout.LayoutParams(
                0, dp(46), 1));

        panel.addView(pronunciation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout localBody = new LinearLayout(this);
        localBody.setOrientation(LinearLayout.VERTICAL);
        localBody.setPadding(dp(14), dp(12), dp(14), dp(12));
        localBody.setBackgroundColor(Color.WHITE);

        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setPadding(dp(13), dp(12), dp(13), dp(13));
        summaryCard.setBackground(bg(0xfff4f4f4, 10));
        summaryCard.setMinimumHeight(dp(96));

        if (local != null) {
            TextView word = lookupText(q, 18, 0xff00a6a6);
            word.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            word.setIncludeFontPadding(false);
            summaryCard.addView(word);

            TextView meta = lookupText(
                    local[0] + "   " + local[1] + "   " + local[2],
                    13,
                    0xff6f7378);
            meta.setPadding(0, dp(6), 0, dp(7));
            meta.setIncludeFontPadding(false);
            summaryCard.addView(meta);

            LinearLayout meaningCard = new LinearLayout(this);
            meaningCard.setOrientation(LinearLayout.VERTICAL);
            meaningCard.setPadding(dp(11), dp(10), dp(11), dp(10));
            meaningCard.setBackground(bg(0xffe5f5fb, 8));

            TextView meaning = lookupText(local[3], 16, 0xff25272a);
            meaning.setLineSpacing(dp(2), 1f);
            meaning.setIncludeFontPadding(false);
            meaningCard.addView(meaning, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            summaryCard.addView(meaningCard, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            TextView empty = lookupText("暂无笔记", 16, 0xff303236);
            empty.setIncludeFontPadding(false);
            summaryCard.addView(empty);
        }

        localBody.addView(summaryCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addLookupSection(content, "日语单词总汇", localBody, true);

        LinearLayout aiBodyBox = new LinearLayout(this);
        aiBodyBox.setOrientation(LinearLayout.VERTICAL);
        aiBodyBox.setPadding(dp(14), dp(10), dp(14), dp(12));
        aiBodyBox.setBackgroundColor(Color.WHITE);

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        aiBodyBox.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView aiBody = lookupText("正在查询 AI…", 15, 0xff25272a);
        aiBody.setPadding(dp(11), dp(10), dp(11), dp(10));
        aiBody.setBackground(bg(0xffe5f5fb, 8));
        aiBody.setLineSpacing(dp(3), 1f);
        aiBody.setTextIsSelectable(true);
        aiBody.setIncludeFontPadding(false);
        aiBodyBox.addView(aiBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addLookupSection(content, "Groq AI", aiBodyBox, true);

        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottomActions = new LinearLayout(this);
        bottomActions.setOrientation(LinearLayout.HORIZONTAL);
        bottomActions.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        bottomActions.setPadding(dp(8), 0, dp(8), 0);
        bottomActions.setBackgroundColor(Color.WHITE);
        for (ImageView action : new ImageView[]{replay, list, more}) {
            bottomActions.addView(action, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }
        panel.addView(bottomActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        // The reference has no visible resize handle. The footer itself is
        // the resize hit target, while still looking like a normal footer.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(dp(10), 0, dp(8), 0);
        footer.setBackgroundColor(Color.WHITE);
        footer.setContentDescription("拖动调整查词浮层大小");

        TextView copyright = lookupText("© JP Native Android", 12, 0xff777b80);
        copyright.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        copyright.setIncludeFontPadding(false);
        footer.addView(copyright, new LinearLayout.LayoutParams(0, dp(30), 1));

        TextView badge = lookupText("あ", 12, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setBackground(bg(0xff129fe8, 4));
        LinearLayout.LayoutParams badgeLp =
                new LinearLayout.LayoutParams(dp(18), dp(18));
        badgeLp.leftMargin = dp(7);
        footer.addView(badge, badgeLp);

        panel.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        panel.setClickable(true);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        int lookupWindowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                (lookupOverlayHosted ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        : WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        if (fullscreen) {
            lookupWindowFlags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        lookupOverlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(), lookupWindowFlags, PixelFormat.TRANSLUCENT);
        if (fullscreen) {
            if (Build.VERSION.SDK_INT >= 30) {
                lookupOverlayLp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lookupOverlayLp.setFitInsetsTypes(0);
            } else if (Build.VERSION.SDK_INT >= 28) {
                lookupOverlayLp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        lookupOverlayLp.gravity = Gravity.TOP | Gravity.START;
        lookupOverlayLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        lookupOverlay = root;
        lookupWindow = new LookupOverlayWindow(this, wm, root, panel, lookupOverlayLp,
                fullscreen, topSystemBarInset());
        lookupWindow.installGestures(header, title, footer);
        if (fullscreen) {
            LookupOverlayWindow bounds = lookupWindow;
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                }
                bounds.fitSystemBars(top, bottom);
                return insets;
            });
        }

        try {
            wm.addView(root, lookupOverlayLp);
            if (!lookupOverlayHosted) {
                root.requestFocus();
                registerLookupBackCallback();
            }

            // Keep the original bubble mounted continuously. A proxy is added
            // above the result layer instead of removing/re-adding the bubble.
            ensureBubbleProxyOnTop();
        } catch (Throwable e) {
            lookupOverlay = null;
            lookupOverlayLp = null;
            recordHandledCrash("showLookupOverlay.addView", e);
            safeToast("查词浮层打开失败：" + e.getClass().getSimpleName());
            closeLookupOverlay();
            return;
        }

        loadLookupAi(q, root, progress, aiBody);
    }

    private void addLookupSection(
            LinearLayout parent,
            String label,
            View sectionBody,
            boolean expanded) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(8), 0);
        row.setBackgroundColor(0xfff4f5f6);

        TextView t = lookupText(label, 15, 0xff34373b);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        row.addView(t, new LinearLayout.LayoutParams(0, dp(40), 1));

        ImageView arrow = lookupIcon(
                R.drawable.ic_lookup_arrow_up, "折叠", 0xffb4b7bb);
        arrow.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(40), dp(40)));

        sectionBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setRotation(expanded ? 0f : 180f);

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        parent.addView(sectionBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> {
            boolean collapse = sectionBody.getVisibility() == View.VISIBLE;
            sectionBody.setVisibility(collapse ? View.GONE : View.VISIBLE);
            arrow.animate().rotation(collapse ? 180f : 0f).setDuration(120L).start();
        });
    }

    private String[] findLocalLookup(String q) {
        if (q == null || q.trim().isEmpty()) return null;

        String clean = q.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : MainActivity.WORDS.entrySet()) {
            String[] d = e.getValue();
            if (e.getKey().equals(clean) ||
                    e.getKey().contains(clean) ||
                    d[0].equals(clean) ||
                    d[0].contains(clean) ||
                    d[1].toLowerCase(Locale.ROOT).contains(lower)) {
                return d;
            }
        }
        return null;
    }

    private void loadLookupAi(
            String q,
            View owner,
            ProgressBar progress,
            TextView aiBody) {
        String cached = LOOKUP_AI_CACHE.get(q);
        if (cached != null) {
            progress.setVisibility(View.GONE);
            aiBody.setText(cached);
            return;
        }

        String apiKey = GroqKeyStore.load(this);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            progress.setVisibility(View.GONE);
            aiBody.setText(
                    "Groq AI 尚未配置。请先通过应用内的 Groq Key 配置保存 Key，"
                            + "之后 OCR 浮层会直接在当前应用上方查询。");
            return;
        }

        final String requestKey = apiKey.trim();
        LOOKUP_AI_EXECUTOR.execute(() -> {
            try {
                String answer = GroqClient.query(q, requestKey);
                LOOKUP_AI_CACHE.put(q, answer);
                main.post(() -> {
                    if (lookupOverlay != owner) return;
                    progress.setVisibility(View.GONE);
                    aiBody.setText(answer);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                main.post(() -> {
                    if (lookupOverlay != owner) return;
                    progress.setVisibility(View.GONE);
                    if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
                        aiBody.setText("Groq Key 无效或没有权限，请重新配置 Key。");
                    } else {
                        aiBody.setText("AI 查询失败：\n" + msg);
                    }
                });
            }
        });
    }

    private void saveLookupHistory(String raw) {
        if (raw == null) return;
        String value = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.isEmpty()) return;
        if (value.length() > 80) value = value.substring(0, 80);

        String saved = getSharedPreferences("lookup_history", MODE_PRIVATE)
                .getString("items", "");

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(value);
        if (saved != null && !saved.isEmpty()) {
            for (String line : saved.split("\\n")) {
                String item = line.trim();
                if (!item.isEmpty()) ordered.add(item);
                if (ordered.size() >= 20) break;
            }
        }

        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String item : ordered) {
            if (out.length() > 0) out.append('\n');
            out.append(item);
            count++;
            if (count >= 20) break;
        }

        getSharedPreferences("lookup_history", MODE_PRIVATE)
                .edit()
                .putString("items", out.toString())
                .apply();
    }

    private void exitLookupEditMode() {
        EditText editor = lookupEditor;
        if (editor == null) return;

        try {
            InputMethodManager imm =
                    (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        } catch (Throwable ignored) {}

        if (lookupHeaderNormal != null) {
            lookupHeaderNormal.setVisibility(View.VISIBLE);
        }
        if (lookupHeaderEdit != null) {
            lookupHeaderEdit.setVisibility(View.GONE);
        }

        lookupEditor = null;
        lookupHeaderNormal = null;
        lookupHeaderEdit = null;
        setLookupOverlayFocusable(false);
    }

    private void notifyLookupHostFinish() {
        try {
            Intent done = new Intent(ACTION_LOOKUP_HOST_FINISH)
                    .setPackage(getPackageName());
            sendBroadcast(done);
        } catch (Throwable ignored) {}
    }

    private boolean handleLookupBack() {
        if (lookupOverlay == null) return false;
        markAction("LOOKUP_BACK_CLOSE");
        closeLookupOverlay();
        return true;
    }

    private void closeLookupOverlay() {
        boolean restoreSearch = lookupOverlayReturnToSearch;
        boolean keepHost = restoreSearch && lookupOverlayHosted;
        removeLookupOverlay(false);
        lookupOverlayHosted = false;
        lookupOverlayReturnToSearch = false;
        if (keepHost) {
            sendBroadcast(new Intent(ACTION_LOOKUP_HOST_SEARCH).setPackage(getPackageName()));
        } else {
            notifyLookupHostFinish();
            if (restoreSearch) main.postDelayed(this::openSearchHost, 80);
        }
    }

    private void removeLookupOverlay(boolean clearRestoreState) {
        if (lookupWindow != null) lookupWindow.save();
        if (lookupEditor != null) exitLookupEditMode();
        clearLookupBackCallback();

        if (lookupOverlay != null && wm != null) {
            try {
                wm.removeView(lookupOverlay);
            } catch (Exception ignored) {}
        }

        lookupOverlay = null;
        lookupOverlayLp = null;
        lookupWindow = null;
        lookupOverlayQuery = "";
        lookupOverlayMode = LOOKUP_MODE_FLOAT;
        lookupEditor = null;
        lookupHeaderNormal = null;
        lookupHeaderEdit = null;
        removeBubbleProxyIfUnused();

        if (clearRestoreState) {
            lookupOverlayReturnToSearch = false;
        }
    }

    private void showResultCard(String title, String body) {
        removeResultCard();
        removeSearchPanel();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(outline(0x33ffffff, 1, 0xf2222222, 14));
        card.setElevation(dp(16));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(0xffeeeeee);
        b.setTextSize(16);
        b.setPadding(0, dp(6), 0, 0);
        card.addView(b);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(
                        getResources().getDisplayMetrics().widthPixels - dp(24),
                        dp(330)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics d = getResources().getDisplayMetrics();
        lp.x = Math.max(
                dp(12),
                Math.min(
                        bubbleLp.x - dp(230),
                        d.widthPixels - lp.width - dp(12)));
        lp.y = Math.max(
                dp(40),
                Math.min(
                        bubbleLp.y - dp(30),
                        d.heightPixels - dp(180)));

        resultCard = card;
        wm.addView(resultCard, lp);

        card.setOnClickListener(v -> removeResultCard());

        main.postDelayed(() -> {
            if (resultCard == card) removeResultCard();
        }, 7000);
    }

    private void registerSearchBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || searchPanel == null) return;
        View owner = searchPanel;
        owner.post(() -> {
            if (searchPanel != owner) return;
            try {
                android.window.OnBackInvokedDispatcher dispatcher = owner.findOnBackInvokedDispatcher();
                if (dispatcher == null) return;
                clearSearchBackCallback();
                android.window.OnBackInvokedCallback callback = () -> {
                    markAction("SEARCH_SYSTEM_GESTURE_BACK");
                    removeSearchPanel();
                };
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
                searchBackDispatcher = dispatcher;
                searchBackCallback = callback;
            } catch (Throwable t) {
                recordHandledCrash("registerSearchBackCallback", t);
            }
        });
    }

    private void clearSearchBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 &&
                searchBackDispatcher != null &&
                searchBackCallback != null) {
            try {
                searchBackDispatcher.unregisterOnBackInvokedCallback(searchBackCallback);
            } catch (Throwable ignored) {}
            searchBackDispatcher = null;
            searchBackCallback = null;
        }
    }

    private void removeSearchPanel() { removeSearchPanel(true); }

    private void removeSearchPanel(boolean finishHost) {
        boolean hosted = searchPanelHosted;
        clearSearchBackCallback();

        if (searchPanel != null && wm != null) {
            try {
                InputMethodManager imm =
                        (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(searchPanel.getWindowToken(), 0);
            } catch (Exception ignored) {}

            try {
                wm.removeView(searchPanel);
            } catch (Exception ignored) {}

            searchPanel = null;
        }
        searchPanelLp = null;
        searchPanelHosted = false;
        if (finishHost && hosted) notifyLookupHostFinish();
        removeBubbleProxyIfUnused();
    }

    private void removeResultCard() {
        if (resultCard != null && wm != null) {
            try {
                wm.removeView(resultCard);
            } catch (Exception ignored) {}
            resultCard = null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble();

        if (intent != null && ACTION_SHOW_SEARCH.equals(intent.getAction())) {
            String restoredQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            if (restoredQuery != null) currentSearchQuery = restoredQuery;

            if (Settings.canDrawOverlays(this) && searchPanel == null) {
                if (intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false)) {
                    searchPanelHosted = true;
                    showSearchPanel();
                } else {
                    openSearchHost();
                }
            }
        }

        if (intent != null && ACTION_SHOW_LOOKUP_OVERLAY.equals(intent.getAction())) {
            String q = normalizeLookupQuery(intent.getStringExtra(EXTRA_LOOKUP_QUERY));
            String mode = normalizeLookupMode(intent.getStringExtra(EXTRA_LOOKUP_MODE));
            boolean returnToSearch =
                    intent.getBooleanExtra(EXTRA_RETURN_TO_SEARCH, false);
            String restoredQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            if (restoredQuery != null) currentSearchQuery = restoredQuery;

            if (!q.isEmpty()) {
                lookupOverlayHosted = intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false);
                showLookupOverlay(q, returnToSearch, mode);
            }
        }

        if (intent != null && ACTION_LOOKUP_DISMISS.equals(intent.getAction())) {
            removeSearchPanel(false);
            removeLookupOverlay(true);
            lookupOverlayHosted = false;
            notifyLookupHostFinish();
        }

        if (intent != null && ACTION_LOOKUP_BACK.equals(intent.getAction())) {
            // Service callbacks already run on the main thread. Handle Back
            // immediately so it cannot overtake a queued overlay show/dismiss.
            if (lookupOverlay != null) {
                handleLookupBack();
            } else if (searchPanel != null) {
                removeSearchPanel();
            } else {
                notifyLookupHostFinish();
            }
        }

        return START_STICKY;
    }

    @Override public void onDestroy() {
        try {
            unregisterReceiver(ocrReceiver);
        } catch (Exception ignored) {}

        hideTargetBox();
        removeSearchPanel();
        removeLookupOverlay(true);
        notifyLookupHostFinish();
        removeResultCard();

        if (bubbleTopProxy != null && wm != null) {
            try {
                wm.removeView(bubbleTopProxy);
            } catch (Exception ignored) {}
            bubbleTopProxy = null;
            bubbleTopProxyLp = null;
        }

        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {}
            bubble = null;
        }

        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
