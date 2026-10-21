package com.yuen.jpdict;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.core.content.ContextCompat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingService extends Service {
    private static final String CH = "floating_lookup";
    private static final int NOTIFY_ID = 51;
    public static final String ACTION_SHOW_SEARCH = "com.yuen.jpdict.SHOW_FLOATING_SEARCH";
    public static final String ACTION_SHOW_LOOKUP_OVERLAY =
            "com.yuen.jpdict.SHOW_LOOKUP_OVERLAY";
    public static final String ACTION_LOOKUP_BACK =
            "com.yuen.jpdict.LOOKUP_BACK";
    public static final String ACTION_LOOKUP_DISMISS =
            "com.yuen.jpdict.LOOKUP_DISMISS";
    public static final String EXTRA_LOOKUP_HOSTED = "lookup_hosted";
    public static final String EXTRA_HOST_SESSION = "lookup_host_session";
    public static final String ACTION_SEARCH_HOST_READY = "com.yuen.jpdict.SEARCH_HOST_READY";
    public static final String EXTRA_SEARCH_HOST_TOKEN = "search_host_token";
    public static final String EXTRA_LOOKUP_PRIVATE = "lookup_private";
    public static final String EXTRA_HOST_SEARCH = "host_search";
    public static final String ACTION_LOOKUP_HOST_FINISH =
            "com.yuen.jpdict.LOOKUP_HOST_FINISH";
    public static final String ACTION_LOOKUP_HOST_SEARCH =
            "com.yuen.jpdict.LOOKUP_HOST_SEARCH";
    public static final String ACTION_LOOKUP_HOST_EXTERNAL =
            "com.yuen.jpdict.LOOKUP_HOST_EXTERNAL";
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
    private static volatile long lookupAiCacheRevision = -1;

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;

    private OcrPickupController pickup;

    private View searchPanel;
    private WindowManager.LayoutParams searchPanelLp;
    private boolean searchPanelHosted;
    private String activeHostSession = "";
    private String pendingSearchPageRequest = "";
    private String pendingSearchPageHostSession = "";
    private String pendingDrawerExperience = "";
    private boolean searchPrivateMode;
    private boolean searchKeyboardRequested;
    private boolean lookupPrivateMode;
    private View resultCard;
    private View lookupOverlay;
    private DictionaryLookupController dictionaryLookup;
    private WindowManager.LayoutParams lookupOverlayLp;
    private String lookupOverlayQuery = "";
    private String lookupOverlayMode = LOOKUP_MODE_FLOAT;
    private boolean lookupOverlayReturnToSearch;
    private boolean lookupOverlayHosted;
    private LookupOverlayWindow lookupWindow;
    private View bubbleTopProxy;
    private WindowManager.LayoutParams bubbleTopProxyLp;

    private String currentSearchQuery = "";
    private android.window.OnBackInvokedDispatcher searchBackDispatcher;
    private android.window.OnBackInvokedCallback searchBackCallback;
    private android.window.OnBackInvokedDispatcher lookupBackDispatcher;
    private android.window.OnBackInvokedCallback lookupBackCallback;
    private EditText lookupEditor;
    private DictionaryEntryView lookupHtmlEditor;
    private boolean lookupKeyEditing;
    private int lookupAiRequestGeneration;
    private View lookupHeaderNormal;
    private View lookupHeaderEdit;
    private TextToSpeech textToSpeech;
    private boolean pronunciationReady;
    private String pendingPronunciation;

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
            if (MainActivity.OcrActivity.ACTION_FINISHED.equals(intent.getAction()) || MainActivity.ScreenCaptureService.ACTION_STOPPED.equals(intent.getAction())) {
                return;
            }
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

    private final Runnable bubbleAppearanceListener = this::applyBubbleAppearance;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startAsForeground();

        IntentFilter filter = new IntentFilter(MainActivity.ScreenCaptureService.ACTION_RESULT);
        filter.addAction(MainActivity.OcrActivity.ACTION_FINISHED);
        filter.addAction(MainActivity.ScreenCaptureService.ACTION_STOPPED);
        ContextCompat.registerReceiver(this, ocrReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        FloatingBubbleAppearance.addChangeListener(bubbleAppearanceListener);

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

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("多语种浮动取词已开启")
                .setContentText("轻点搜索；拖动停留取词；长按选择 OCR 模式")
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
        GradientDrawable g = new AppTheme.Shape();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable outline(int strokeColor, int strokeDp, int fillColor, int radiusDp) {
        GradientDrawable g = bg(fillColor, radiusDp);
        g.setStroke(dp(strokeDp), strokeColor);
        return g;
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
        private boolean consumeHeaderRelease;
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
        void consumeEditingGesture() { consumeHeaderRelease = true; }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // Changing window focus and replacing the pressed title invalidates
            // its touch target on some OEMs. The same release must never click Mask.
            if (consumeHeaderRelease && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) consumeHeaderRelease = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) consumeHeaderRelease = false;
            return super.dispatchTouchEvent(event);
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

        FrameLayout bubbleView = createBubbleVisual();

        bubble = bubbleView;

        int bubbleSize = FloatingBubbleAppearance.sizePx(this);
        bubbleLp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        configureBubbleWindow(bubbleLp);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences sp = getSharedPreferences("float_pos", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleLp.x = sp.getInt("x", Math.max(0, dm.widthPixels - bubbleLp.width - dp(20)));
        bubbleLp.y = sp.getInt("y", Math.max(dp(120), dm.heightPixels / 2));

        if (pickup == null) pickup = new OcrPickupController(this, new OcrPickupController.Host() {
            private int screenOffsetX, screenOffsetY;
            private final DisplayMetrics screen = new DisplayMetrics();
            private final Rect movedPosition = new Rect();
            @Override public Rect position() {
                View owner = bubbleTopProxy != null ? bubbleTopProxy : bubble;
                int[] location = new int[2]; owner.getLocationOnScreen(location);
                screenOffsetX = location[0] - bubbleLp.x; screenOffsetY = location[1] - bubbleLp.y;
                return new Rect(location[0], location[1], location[0] + bubbleLp.width, location[1] + bubbleLp.height);
            }
            @Override public Rect move(int x, int y) {
                wm.getDefaultDisplay().getRealMetrics(screen);
                int nextX = Math.max(0, Math.min(x - screenOffsetX, screen.widthPixels - bubbleLp.width));
                int nextY = Math.max(0, Math.min(y - screenOffsetY, screen.heightPixels - bubbleLp.height));
                if (bubbleLp.x != nextX || bubbleLp.y != nextY) {
                    bubbleLp.x = nextX; bubbleLp.y = nextY;
                    wm.updateViewLayout(bubble, bubbleLp);
                }
                if (bubbleTopProxy != null && bubbleTopProxyLp != null
                        && (bubbleTopProxyLp.x != nextX || bubbleTopProxyLp.y != nextY)) {
                    bubbleTopProxyLp.x = bubbleLp.x; bubbleTopProxyLp.y = bubbleLp.y;
                    wm.updateViewLayout(bubbleTopProxy, bubbleTopProxyLp);
                }
                int left = bubbleLp.x + screenOffsetX, top = bubbleLp.y + screenOffsetY;
                movedPosition.set(left, top, left + bubbleLp.width, top + bubbleLp.height);
                return movedPosition;
            }
            @Override public void save() {
                getSharedPreferences("float_pos", MODE_PRIVATE).edit().putInt("x", bubbleLp.x).putInt("y", bubbleLp.y).apply();
            }
            @Override public void tap() {
                if (lookupOverlay != null) closeLookupOverlay(); else toggleSearchPanel();
            }
            @Override public void lookup(String text) { removeSearchPanel(); openOcrLookupPopup(text); }
        });
        bubble.setOnTouchListener(pickup.listener());
        wm.addView(bubble, bubbleLp);
    }

    private void toggleSearchPanel() {
        try {
            if (searchPanel != null) {
                if (searchPanel.getVisibility() != View.VISIBLE ||
                        (searchPanel instanceof FloatingSearchView && ((FloatingSearchView)searchPanel).isLookupPaused())) {
                    openSearchHost();
                    return;
                }
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

    private void openSearchHost() {
        searchKeyboardRequested = pendingDrawerExperience.isEmpty();
        Intent host = new Intent(this, LookupLinkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_HOST_SEARCH, true)
                .putExtra(EXTRA_SEARCH_QUERY, searchPrivateMode ? "" : currentSearchQuery)
                .putExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE, pendingDrawerExperience);
        try {
            startActivity(host);
        } catch (Throwable error) {
            searchKeyboardRequested = false;
            finishDrawerExperience(pendingDrawerExperience, false);
            recordHandledCrash("openSearchHost", error);
            safeToast("搜索浮层启动失败");
        }
    }

    private void showSearchPanel(android.os.IBinder token) {
        if (!Settings.canDrawOverlays(this) || wm == null) return;
        removeResultCard();
        FloatingSearchView root = new FloatingSearchView(this, currentSearchQuery,
                searchPrivateMode, new FloatingSearchView.Callback() {
            @Override public void onQueryChanged(String query) { currentSearchQuery = query; }
            @Override public void onPrivateModeChanged(boolean privacy) { searchPrivateMode = privacy; }
            @Override public void onLookup(String query, boolean privacy) {
                searchPrivateMode = privacy;
                openQuickLookup(query);
            }
            @Override public void onEditingChanged(boolean editing) { setSearchFocusable(editing); }
            @Override public void onOpenPage(String page, boolean privateMode) {
                FloatingSearchRoutes.openFromSearch(FloatingService.this, page, privateMode);
            }
            @Override public void onClose() { removeSearchPanel(); }
        });
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                (searchPanelHosted ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.softInputMode = searchSoftInputAdjustment() |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        searchPanel = root;
        searchPanelLp = lp;
        if (searchKeyboardRequested) root.prefillClipboardOnFocus();
        mountSearchPanel(token);
    }

    private void mountSearchPanel(android.os.IBinder token) {
        if (!(searchPanel instanceof FloatingSearchView) || token == null) return;
        FloatingSearchView root = (FloatingSearchView) searchPanel;
        if (root.isAttachedToWindow() && searchPanelLp.token == token) return;
        try {
            clearSearchBackCallback();
            if (root.getParent() != null) wm.removeViewImmediate(root);
            searchPanelLp.token = token;
            wm.addView(root, searchPanelLp);
            root.requestApplyInsets();
            root.requestFocus();
            registerSearchBackCallback();
            bringBubbleToFront();
            DictionaryOperation.overlayAdded(this);
            if (searchKeyboardRequested) {
                searchKeyboardRequested = false;
                root.post(() -> {
                    if (searchPanel == root) root.focusInput();
                });
            }
            markAction("SHOW_SEARCH_PANEL_FINISH");
            openDrawerWhenReady();
        } catch (WindowManager.BadTokenException staleHost) {
            // Configuration changes may arrive before the replacement host attaches.
            recordHandledCrash("mountSearchPanel", staleHost);
        } catch (Throwable error) {
            root.dispose();
            searchPanel = null;
            searchPanelLp = null;
            searchPanelHosted = false;
            recordHandledCrash("showSearchPanel", error);
            notifyLookupHostFinish();
            safeToast("搜索浮层打开失败");
            finishDrawerExperience(pendingDrawerExperience, false);
        }
    }

    private void finishDrawerExperience(String request, boolean success) {
        if (request == null || request.isEmpty()) return;
        if (request.equals(pendingDrawerExperience)) pendingDrawerExperience = "";
        sendBroadcast(new Intent(FloatingSearchRoutes.ACTION_EXPERIENCE_RESULT).setPackage(getPackageName())
                .putExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE, request).putExtra("success", success));
    }

    private void openDrawerWhenReady() {
        if (pendingDrawerExperience.isEmpty() || !(searchPanel instanceof FloatingSearchView)) return;
        FloatingSearchView target = (FloatingSearchView) searchPanel;
        String request = pendingDrawerExperience, session = activeHostSession;
        Runnable open = () -> {
            if (target != searchPanel || !request.equals(pendingDrawerExperience) || !session.equals(activeHostSession)) return;
            if (!target.isAttachedToWindow() || target.getVisibility() != View.VISIBLE || target.getWidth() == 0) {
                finishDrawerExperience(request, false); return;
            }
            target.experienceDrawer(); finishDrawerExperience(request, true);
        };
        if (target.isLaidOut()) target.postOnAnimation(open);
        else target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this); target.postOnAnimation(open); return true;
            }
        });
    }

    private void setSearchFocusable(boolean editing) {
        if (searchPanel == null || searchPanelLp == null || wm == null) return;
        boolean focused = (searchPanelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (focused == editing) return;
        if (editing) searchPanelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else searchPanelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        searchPanelLp.softInputMode = searchSoftInputAdjustment() |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        try { wm.updateViewLayout(searchPanel, searchPanelLp); }
        catch (Throwable error) { recordHandledCrash("setSearchFocusable", error); }
        if (editing) registerSearchBackCallback(); else clearSearchBackCallback();
    }

    private int searchSoftInputAdjustment() {
        // Android 11+ already positions the composer from animated IME Insets.
        // Resizing the window as well would reserve that keyboard space twice.
        return Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void rootSearchForLookup() {
        if (searchPanel instanceof FloatingSearchView) ((FloatingSearchView)searchPanel).prepareForLookup();
        setSearchFocusable(false);
        clearSearchBackCallback();
    }

    private void handleSearchBack() {
        if (searchPanel instanceof FloatingSearchView) ((FloatingSearchView)searchPanel).handleBack();
        else removeSearchPanel();
    }

    private FrameLayout createBubbleVisual() {
        FrameLayout visual = FloatingBubbleAppearance.createVisual(this);
        visual.setAlpha(FloatingBubbleAppearance.current(this).alpha());
        return visual;
    }

    private void ensureBubbleProxyOnTop() {
        if (bubble == null || bubbleLp == null || wm == null) return;

        if (bubbleTopProxy != null && bubbleTopProxyLp != null) {
            setOriginalBubbleProxyCovered(true);
            bubbleTopProxyLp.x = bubbleLp.x;
            bubbleTopProxyLp.y = bubbleLp.y;
            try {
                // Reorder only the proxy when another surface is mounted.
                // The real bubble and retained search page stay attached.
                wm.removeViewImmediate(bubbleTopProxy);
                wm.addView(bubbleTopProxy, bubbleTopProxyLp);
            } catch (Exception error) {
                try { wm.removeViewImmediate(bubbleTopProxy); } catch (Exception ignored) {}
                bubbleTopProxy = null; bubbleTopProxyLp = null;
                setOriginalBubbleProxyCovered(false);
            }
            return;
        }

        View proxy = createBubbleVisual();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bubbleLp.width, bubbleLp.height, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        configureBubbleWindow(lp);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bubbleLp.x;
        lp.y = bubbleLp.y;

        proxy.setOnTouchListener(pickup.listener());

        bubbleTopProxy = proxy;
        bubbleTopProxyLp = lp;
        // Relinquish drawing and touch ownership before adding the top layer.
        // This keeps both windows from being visible during the keyboard resize.
        setOriginalBubbleProxyCovered(true);
        try {
            wm.addView(proxy, lp);
        } catch (Exception e) {
            bubbleTopProxy = null;
            bubbleTopProxyLp = null;
            setOriginalBubbleProxyCovered(false);
        }
    }

    private void setOriginalBubbleProxyCovered(boolean covered) {
        if (bubble == null || bubbleLp == null || wm == null) return;
        // Both windows stay mounted. The underlying window is explicitly
        // invisible while the proxy owns drawing and touches.
        bubble.setVisibility(covered ? View.INVISIBLE : View.VISIBLE);
        bubble.setAlpha(covered ? 0f : FloatingBubbleAppearance.current(this).alpha());
        // InputDispatcher checks the window opacity rather than View.alpha
        // before allowing touches through an untrusted overlay on Android 12+.
        bubbleLp.alpha = covered ? 0f : 1f;
        if (covered) bubbleLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else bubbleLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(bubble, bubbleLp); }
        catch (Exception ignored) {}
    }

    private void configureBubbleWindow(WindowManager.LayoutParams params) {
        // The bubble uses display coordinates, independent of the IME viewport.
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
    }

    private void applyBubbleAppearance() {
        if (bubble == null || bubbleLp == null || wm == null) return;
        FloatingBubbleAppearance.Config config = FloatingBubbleAppearance.current(this);
        int size = config.sizePx(this);
        FloatingBubbleAppearance.applyVisual((FrameLayout)bubble, config);
        bubbleLp.width = size;
        bubbleLp.height = size;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        android.content.SharedPreferences position = getSharedPreferences("float_pos", MODE_PRIVATE);
        // A larger draft may need to fit on screen; retain the user's saved anchor for rollback.
        bubbleLp.x = Math.max(0, Math.min(position.getInt("x", bubbleLp.x), dm.widthPixels - size));
        bubbleLp.y = Math.max(0, Math.min(position.getInt("y", bubbleLp.y), dm.heightPixels - size));
        if (!FloatingBubbleAppearance.isPreviewing()) position.edit()
                .putInt("x", bubbleLp.x).putInt("y", bubbleLp.y).apply();
        setOriginalBubbleProxyCovered(bubbleTopProxy != null);
        if (bubbleTopProxy != null && bubbleTopProxyLp != null) {
            FloatingBubbleAppearance.applyVisual((FrameLayout)bubbleTopProxy, config);
            bubbleTopProxy.setAlpha(config.alpha());
            bubbleTopProxyLp.width = size;
            bubbleTopProxyLp.height = size;
            bubbleTopProxyLp.x = bubbleLp.x;
            bubbleTopProxyLp.y = bubbleLp.y;
            try { wm.updateViewLayout(bubbleTopProxy, bubbleTopProxyLp); }
            catch (Exception ignored) {}
        }
        if (pickup != null) pickup.cancel();
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
        setOriginalBubbleProxyCovered(false);
    }

    private void bringBubbleToFront() {
        // Do not remove/re-add the real bubble: that caused a visible blink.
        // A temporary identical proxy window is added above the content layer
        // while the real bubble stays mounted continuously underneath and
        // relinquishes its drawing/touch ownership to that proxy.
        ensureBubbleProxyOnTop();
    }

    private void openQuickLookup(String q) {
        if (routeThirdPartyLookup(q, true, searchPrivateMode)) return;
        rootSearchForLookup();
        openLookupHost(q, true, LOOKUP_MODE_FLOAT);
    }

    private boolean routeThirdPartyLookup(String query, boolean returnToSearch, boolean privateMode) {
        if (!LookupDestinationSettings.get(this).isThirdParty()) return false;
        if (!privateMode) saveLookupHistory(query);
        if (returnToSearch && searchPanel != null && searchPanelHosted && searchPanel.isAttachedToWindow()
                && lookupOverlay == null && !activeHostSession.isEmpty()) {
            // The Activity protects its search return route before launching
            // the external app. Keep this exact surface, including chip state.
            rootSearchForLookup();
            sendBroadcast(new Intent(ACTION_LOOKUP_HOST_EXTERNAL).setPackage(getPackageName())
                    .putExtra(EXTRA_HOST_SESSION, activeHostSession)
                    .putExtra(EXTRA_LOOKUP_QUERY, query));
            return true;
        }
        if (ThirdPartyLookup.launch(this, query)) {
            removeSearchPanel();
            removeLookupOverlay(true);
            lookupOverlayHosted = false;
            lookupPrivateMode = false;
            notifyLookupHostFinish();
            removeBubbleProxyIfUnused();
        } else if (searchPanel == null && lookupOverlay == null) {
            lookupOverlayHosted = false;
            lookupPrivateMode = false;
            notifyLookupHostFinish();
        }
        return true;
    }

    private void openLookupHost(String q, boolean returnToSearch, String mode) {
        String value = q == null ? "" : q.trim();
        if (value.isEmpty()) return;
        boolean privateLookup = returnToSearch && searchPrivateMode;
        Intent host = new Intent(this, LookupLinkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_LOOKUP_QUERY, value)
                .putExtra(EXTRA_LOOKUP_MODE, mode)
                .putExtra(EXTRA_RETURN_TO_SEARCH, returnToSearch)
                .putExtra(EXTRA_LOOKUP_PRIVATE, privateLookup);
        // Keep the private draft in this Service. An empty restore extra
        // would overwrite it when the host forwards lookup and search actions.
        if (!privateLookup) host.putExtra(EXTRA_SEARCH_QUERY, currentSearchQuery);
        try {
            startActivity(host);
        } catch (Throwable error) {
            recordHandledCrash("openLookupHost", error);
            safeToast("查词小窗启动失败");
        }
    }

    private void openOcrLookupPopup(String q) {
        if (routeThirdPartyLookup(q, false, false)) return;
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
        AppTheme.text(t, color);
        return t;
    }

    private ImageView lookupIcon(int drawable, String description, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        AppTheme.tint(icon, tint);
        icon.setContentDescription(description);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        return icon;
    }

    private String normalizeLookupQuery(String rawQuery) {
        String value = rawQuery == null ? "" : rawQuery.trim();
        if (value.isEmpty()) return "";

        if (value.startsWith("intent://") || value.startsWith("jpdict://") || value.startsWith("jp-native://")) {
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
            if (lookupOverlay != owner || (lookupOverlayHosted && lookupEditor == null && lookupHtmlEditor == null && !lookupKeyEditing)) return;
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
            if (lookupEditor == null && lookupHtmlEditor == null && !lookupKeyEditing) lookupOverlay.requestFocus();
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
        if (returnToSearch && searchPanel != null) rootSearchForLookup();
        else removeSearchPanel(false);
        removeResultCard();

        lookupOverlayQuery = q;
        lookupOverlayMode = mode;
        lookupOverlayReturnToSearch = returnToSearch;
        if (!lookupPrivateMode) saveLookupHistory(q);

        LookupOverlayFrameLayout root = new LookupOverlayFrameLayout(this);
        boolean fullscreen = lookupOverlayHosted || LOOKUP_MODE_FULLSCREEN.equals(mode);
        AppTheme.background(root, Color.TRANSPARENT);

        View fullscreenMask = null;
        if (fullscreen) {
            fullscreenMask = new View(this);
            AppTheme.background(fullscreenMask, Color.BLACK);
            // The Activity supplies the pixels, including both system bars.
            // Keep one touch target here without doubling the mask opacity.
            fullscreenMask.setAlpha(lookupOverlayHosted && searchPanel == null ? 0f : 0.42f);
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
        // Full-width child rows share the same outer edge as the rounded fill.
        // A stroked background insets its outline and leaves alternating seams.
        panel.setBackground(bg(Color.WHITE, 16));
        panel.setClipToOutline(true);
        panel.setClipChildren(true);
        panel.setElevation(dp(12));

        // Reference proportions: 48dp title, 46dp pronunciation
        // rows, 40dp dictionary headers, and a plain 30dp copyright footer.
        FrameLayout headerHost = new FrameLayout(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), 0, dp(4), 0);

        TextView title = lookupText(q, 20, AppTheme.INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        title.setClickable(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        ImageView favorite = lookupIcon(
                R.drawable.ic_lookup_star, "收藏", AppTheme.INK);
        ImageView replay = lookupIcon(
                R.drawable.ic_lookup_replay, "重新查询", AppTheme.INK);
        ImageView list = lookupIcon(
                R.drawable.ic_lookup_list, "词典列表", AppTheme.INK);
        list.setOnClickListener(v -> {
            if (dictionaryLookup != null) dictionaryLookup.showList(list, this::openDictionaryPage);
        });
        ImageView more = lookupIcon(
                R.drawable.ic_lookup_plus, "更多", AppTheme.INK);

        LinearLayout headerActions = new LinearLayout(this);
        headerActions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        for (ImageView action : new ImageView[]{favorite, replay, list, more}) {
            headerActions.addView(action, new LinearLayout.LayoutParams(dp(42), dp(48)));
        }
        header.addView(headerActions, new LinearLayout.LayoutParams(-2, dp(48)));

        replay.setOnClickListener(v -> {
            LOOKUP_AI_CACHE.remove(q);
            showLookupOverlay(q, returnToSearch, mode);
        });

        LinearLayout editHeader = new LinearLayout(this);
        editHeader.setOrientation(LinearLayout.HORIZONTAL);
        editHeader.setGravity(Gravity.CENTER_VERTICAL);
        editHeader.setPadding(dp(14), 0, dp(8), 0);
        AppTheme.background(editHeader, Color.WHITE);
        editHeader.setVisibility(View.GONE);

        EditText editor = new LookupEditor(this);
        editor.setSingleLine(true);
        editor.setText(q);
        editor.setTextSize(18);
        AppTheme.input(editor);
        editor.setSelectAllOnFocus(false);
        editor.setImeOptions(EditorInfo.IME_ACTION_SEARCH |
                (lookupPrivateMode && Build.VERSION.SDK_INT >= 26 ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        editor.setPadding(0, 0, dp(8), 0);

        TextView confirm = lookupText("确认", 15, Color.WHITE);
        confirm.setGravity(Gravity.CENTER);
        confirm.setIncludeFontPadding(false);
        confirm.setBackground(bg(AppTheme.BLUE, 4));

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
            if (lookupOverlay != root || lookupEditor != null) return;
            root.consumeEditingGesture();
            stopLookupHtmlEditing();
            lookupEditor = editor;
            lookupHeaderNormal = header;
            lookupHeaderEdit = editHeader;
            header.setVisibility(View.GONE);
            editHeader.setVisibility(View.VISIBLE);

            // Normal lookup mode intentionally leaves the WindowManager overlay
            // NOT_FOCUSABLE so Android Back belongs to the transparent host.
            // Only editing temporarily takes focus for IME input.
            editor.setText(lookupOverlayQuery);
            editor.setSelection(editor.length());
            editor.requestFocus();
            // OverlayEditor opens IME after this window actually gains focus.
            setLookupOverlayFocusable(true);
            editor.post(() -> {
                if (lookupOverlay == root && lookupEditor == editor && editor.hasWindowFocus() && editor.isFocused()) {
                    InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                    if (ime != null) ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        };
        title.setContentDescription(q + "，长按 700 毫秒修改");
        title.setOnLongClickListener(v -> { enterEditMode.run(); return true; });
        header.setOnLongClickListener(v -> { enterEditMode.run(); return true; });

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
        AppTheme.background(topDivider, AppTheme.LINE);
        panel.addView(topDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        String[] local = findLocalLookup(q);
        LinearLayout pronunciation = new LinearLayout(this);
        pronunciation.setOrientation(LinearLayout.HORIZONTAL);
        pronunciation.setGravity(Gravity.CENTER_VERTICAL);
        pronunciation.setPadding(dp(14), 0, dp(14), 0);

        ImageView speaker = lookupIcon(
                R.drawable.ic_lookup_speaker, "发音", AppTheme.BLUE);
        speaker.setPadding(dp(2), dp(10), dp(8), dp(10));
        pronunciation.addView(speaker, new LinearLayout.LayoutParams(dp(34), dp(46)));
        speaker.setClickable(true);
        speaker.setOnClickListener(v -> speakLookup(q));

        String pronunciationText = local == null
                ? "发音"
                : local[0] + "   /" + local[1] + "/   " + local[2];
        TextView pronounceLabel = lookupText(pronunciationText, 16, AppTheme.MUTED);
        pronounceLabel.setGravity(Gravity.CENTER_VERTICAL);
        pronounceLabel.setIncludeFontPadding(false);
        pronunciation.addView(pronounceLabel, new LinearLayout.LayoutParams(
                0, dp(46), 1));
        pronounceLabel.setOnClickListener(v -> speakLookup(q));
        pronounceLabel.setContentDescription("播放日语发音");

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
        content.addView(pronunciation, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout dictionarySections = new LinearLayout(this);
        dictionarySections.setOrientation(LinearLayout.VERTICAL);
        content.addView(dictionarySections, new LinearLayout.LayoutParams(-1, -2));
        dictionaryLookup = new DictionaryLookupController(this, dictionarySections, q,
                new DictionaryEntryView.Navigation() {
                    @Override public void lookup(String word) { showLookupOverlay(word, returnToSearch, mode); }
                    @Override public void editingChanged(DictionaryEntryView editor, boolean editing) {
                        if (lookupOverlay != root) return;
                        if (editing) {
                            if (lookupHtmlEditor != editor) stopLookupHtmlEditing();
                            lookupHtmlEditor = editor;
                            if (lookupEditor != null) exitLookupEditMode();
                            setLookupOverlayFocusable(true);
                        } else if (lookupHtmlEditor == editor) {
                            lookupHtmlEditor = null;
                            setLookupOverlayFocusable(lookupEditor != null || lookupKeyEditing);
                        }
                    }
                    @Override public boolean closeOnBack() {
                        return lookupOverlay == root && handleLookupBack();
                    }
                });

        LinearLayout aiBodyBox = new LinearLayout(this);
        aiBodyBox.setOrientation(LinearLayout.VERTICAL);
        aiBodyBox.setPadding(dp(14), dp(10), dp(14), dp(12));
        AppTheme.background(aiBodyBox, Color.WHITE);

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        aiBodyBox.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView aiBody = lookupText("正在查询 AI…", 15, AppTheme.INK);
        aiBody.setPadding(dp(11), dp(10), dp(11), dp(10));
        aiBody.setBackground(bg(AppTheme.SOFT, 8));
        aiBody.setLineSpacing(dp(3), 1f);
        aiBody.setTextIsSelectable(true);
        aiBody.setIncludeFontPadding(false);
        aiBodyBox.addView(aiBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addLookupSection(content, "Groq AI", aiBodyBox, true);

        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // The reference has no visible resize handle. The footer itself is
        // the resize hit target, while still looking like a normal footer.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(dp(10), 0, dp(8), 0);
        AppTheme.background(footer, Color.WHITE);
        footer.setContentDescription("拖动调整查词浮层大小");

        TextView copyright = lookupText("© jpdict", 12, AppTheme.MUTED);
        copyright.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        copyright.setIncludeFontPadding(false);
        footer.addView(copyright, new LinearLayout.LayoutParams(0, dp(30), 1));

        TextView badge = lookupText("あ", 12, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setBackground(bg(AppTheme.BLUE, 4));
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
            DictionaryOperation.overlayAdded(this);
        } catch (Throwable e) {
            lookupOverlay = null;
            lookupOverlayLp = null;
            recordHandledCrash("showLookupOverlay.addView", e);
            safeToast("查词浮层打开失败：" + e.getClass().getSimpleName());
            closeLookupOverlay();
            return;
        }

        if (LookupSpeechSettings.autoplay(this)) speakLookup(q);
        loadLookupAi(q, root, progress, aiBody);
    }

    private void speakLookup(String value) {
        String word = value == null ? "" : value.trim();
        if (word.isEmpty()) return;
        pendingPronunciation = word;
        if (textToSpeech == null) {
            try {
                textToSpeech = new TextToSpeech(getApplicationContext(), status ->
                        main.post(() -> onPronunciationReady(status)));
            } catch (RuntimeException ignored) {
                textToSpeech = null;
            }
            return;
        }
        if (pronunciationReady) speakPendingPronunciation();
    }

    private void onPronunciationReady(int status) {
        TextToSpeech engine = textToSpeech;
        if (engine == null || status != TextToSpeech.SUCCESS) return;
        int language = engine.setLanguage(Locale.JAPAN);
        if (language < TextToSpeech.LANG_AVAILABLE) {
            language = engine.setLanguage(Locale.getDefault());
        }
        if (language < TextToSpeech.LANG_AVAILABLE) return;
        engine.setSpeechRate(0.94f);
        pronunciationReady = true;
        speakPendingPronunciation();
    }

    private void speakPendingPronunciation() {
        if (!pronunciationReady || textToSpeech == null || pendingPronunciation == null) return;
        String word = pendingPronunciation;
        pendingPronunciation = null;
        try {
            textToSpeech.speak(word, TextToSpeech.QUEUE_FLUSH, null,
                    "jpdict-pronunciation-" + System.nanoTime());
        } catch (RuntimeException ignored) {}
    }

    private void addLookupSection(
            LinearLayout parent,
            String label,
            View sectionBody,
            boolean expanded) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(8), 0);
        AppTheme.background(row, AppTheme.SOFT);

        TextView t = lookupText(label, 15, AppTheme.INK);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        row.addView(t, new LinearLayout.LayoutParams(0, dp(40), 1));

        ImageView arrow = lookupIcon(
                R.drawable.ic_lookup_arrow_up, "折叠", AppTheme.LINE);
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
        if (lookupOverlay != owner || aiBody.getParent() == null) return;
        final int requestGeneration = ++lookupAiRequestGeneration;
        observeLookupKey(q, owner, progress, aiBody);
        final long keyRevision = GroqKeyStore.revision(this);
        if (lookupAiCacheRevision != keyRevision) {
            LOOKUP_AI_CACHE.clear();
            lookupAiCacheRevision = keyRevision;
        }
        final boolean privateRequest = lookupPrivateMode;
        String cached = privateRequest ? null : LOOKUP_AI_CACHE.get(q);
        if (cached != null) {
            progress.setVisibility(View.GONE);
            GroqKeySettings.setContent(aiBody, cached);
            return;
        }

        String apiKey = GroqKeyStore.load(this);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            progress.setVisibility(View.GONE);
            GroqKeySettings.bindConfigurationPrompt(aiBody, false,
                    () -> showLookupKeySettings(q, owner, progress, aiBody));
            return;
        }

        final String requestKey = apiKey.trim();
        progress.setVisibility(View.VISIBLE);
        GroqKeySettings.setContent(aiBody, "正在查询 AI…");
        LOOKUP_AI_EXECUTOR.execute(() -> {
            try {
                String answer = GroqClient.query(q, requestKey);
                main.post(() -> {
                    if (lookupOverlay != owner || aiBody.getParent() == null ||
                            lookupAiRequestGeneration != requestGeneration ||
                            GroqKeyStore.revision(this) != keyRevision) return;
                    if (!privateRequest) LOOKUP_AI_CACHE.put(q, answer);
                    progress.setVisibility(View.GONE);
                    GroqKeySettings.setContent(aiBody, answer);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                main.post(() -> {
                    if (lookupOverlay != owner || aiBody.getParent() == null ||
                            lookupAiRequestGeneration != requestGeneration ||
                            GroqKeyStore.revision(this) != keyRevision) return;
                    progress.setVisibility(View.GONE);
                    if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
                        GroqKeySettings.bindConfigurationPrompt(aiBody, true,
                                () -> showLookupKeySettings(q, owner, progress, aiBody));
                    } else {
                        GroqKeySettings.setContent(aiBody, "AI 查询失败，请检查网络后重试。");
                    }
                });
            }
        });
    }

    private void observeLookupKey(String q, View owner, ProgressBar progress, TextView body) {
        if (body.getTag() != null) return;
        final long[] observedRevision = { GroqKeyStore.revision(this) };
        Runnable refresh = () -> {
            long revision = GroqKeyStore.revision(this);
            if (lookupOverlay == owner && body.isAttachedToWindow()
                    && observedRevision[0] != revision) {
                observedRevision[0] = revision;
                loadLookupAi(q, owner, progress, body);
            }
        };
        body.setTag(refresh);
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                GroqKeyStore.addChangeListener(refresh);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                GroqKeyStore.removeChangeListener(refresh);
            }
        });
        if (body.isAttachedToWindow()) GroqKeyStore.addChangeListener(refresh);
    }

    private void showLookupKeySettings(String q, View owner, ProgressBar progress, TextView body) {
        if (lookupOverlay != owner || !(body.getParent() instanceof LinearLayout)) return;
        if (lookupEditor != null) exitLookupEditMode();
        stopLookupHtmlEditing();
        lookupKeyEditing = true;
        lookupAiRequestGeneration++;
        LinearLayout box = (LinearLayout) body.getParent();
        box.removeAllViews();
        LinearLayout keyPanel = GroqKeySettings.createPanel(this, LOOKUP_AI_CACHE::clear, () -> {
            if (lookupOverlay != owner) return;
            InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(box.getWindowToken(), 0);
            View focused = owner.findFocus();
            if (focused != null) focused.clearFocus();
            if (lookupEditor != null) exitLookupEditMode();
            lookupKeyEditing = false;
            setLookupOverlayFocusable(lookupHtmlEditor != null);
            box.removeAllViews();
            box.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
            box.addView(body, new LinearLayout.LayoutParams(-1, -2));
            loadLookupAi(q, owner, progress, body);
        }, () -> {
            if (lookupOverlay != owner || !lookupKeyEditing) return;
            if (lookupEditor != null) exitLookupEditMode();
            if (lookupOverlayLp != null && (lookupOverlayLp.flags &
                    (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)) != 0) {
                setLookupOverlayFocusable(true);
            }
        }, this::handleLookupBack);
        box.addView(keyPanel, new LinearLayout.LayoutParams(-1, -2));
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
        setLookupOverlayFocusable(lookupHtmlEditor != null || lookupKeyEditing);
    }

    private void stopLookupHtmlEditing() {
        DictionaryEntryView editor = lookupHtmlEditor;
        lookupHtmlEditor = null;
        if (editor != null) editor.stopEditing();
    }

    private void notifyLookupHostFinish() {
        if (activeHostSession.isEmpty()) return;
        try {
            Intent done = new Intent(ACTION_LOOKUP_HOST_FINISH)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_HOST_SESSION, activeHostSession);
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
            sendBroadcast(new Intent(ACTION_LOOKUP_HOST_SEARCH).setPackage(getPackageName())
                    .putExtra(EXTRA_HOST_SESSION, activeHostSession));
        } else {
            notifyLookupHostFinish();
            if (restoreSearch) main.postDelayed(this::openSearchHost, 80);
        }
    }

    private void openSearchPage(String page, boolean privateMode) {
        if (FloatingSearchRoutes.pageIntent(this, page, "", false, false, false, false) == null) {
            safeToast("无法打开指定页面"); return;
        }
        if (searchPanel != null && searchPanelHosted) {
            String query = privateMode ? "" : currentSearchQuery;
            searchKeyboardRequested = false;
            pendingSearchPageRequest = java.util.UUID.randomUUID().toString();
            pendingSearchPageHostSession = activeHostSession;
            rootSearchForLookup();
            Intent open = new Intent(FloatingSearchRoutes.ACTION_HOST_OPEN_PAGE)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_HOST_SESSION, activeHostSession)
                    .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, pendingSearchPageRequest)
                    .putExtra(FloatingSearchRoutes.EXTRA_PAGE, page)
                    .putExtra(FloatingSearchRoutes.EXTRA_RETURN_QUERY, query)
                    .putExtra(FloatingSearchRoutes.EXTRA_RESTORE_SEARCH, true)
                    .putExtra(FloatingSearchRoutes.EXTRA_PRIVATE_MODE, privateMode);
            sendBroadcast(open);
        } else if (page != null) {
            if (searchPanel != null) {
                pendingSearchPageRequest = java.util.UUID.randomUUID().toString();
                pendingSearchPageHostSession = "";
                rootSearchForLookup();
            }
            Intent open = FloatingSearchRoutes.pageIntent(this, page,
                    privateMode ? "" : currentSearchQuery,
                    searchPanel != null, false, privateMode, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST, pendingSearchPageRequest);
            try { startActivity(open); }
            catch (RuntimeException error) {
                pendingSearchPageRequest = "";
                pendingSearchPageHostSession = "";
                if (searchPanel instanceof FloatingSearchView) ((FloatingSearchView)searchPanel).resumeAfterLookup();
                safeToast("无法打开设置页面");
            }
        }
    }

    private void openDictionaryPage() {
        boolean restore = lookupOverlayReturnToSearch;
        boolean privacy = lookupPrivateMode;
        if (restore) {
            removeLookupOverlay(false);
            lookupOverlayHosted = false;
            lookupOverlayReturnToSearch = false;
            openSearchPage(FloatingSearchRoutes.PAGE_DICTIONARIES, privacy);
        } else {
            lookupOverlayReturnToSearch = false;
            closeLookupOverlay();
            Intent page = FloatingSearchRoutes.pageIntent(this, FloatingSearchRoutes.PAGE_DICTIONARIES,
                    privacy ? "" : currentSearchQuery, restore, false, privacy, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(page);
        }
    }

    private void removeLookupOverlay(boolean clearRestoreState) {
        stopLookupHtmlEditing();
        if (dictionaryLookup != null) { dictionaryLookup.close(); dictionaryLookup = null; }
        pendingPronunciation = null;
        if (textToSpeech != null) {
            try { textToSpeech.stop(); }
            catch (RuntimeException ignored) {}
        }
        if (lookupWindow != null) lookupWindow.save();
        if (lookupEditor != null) exitLookupEditMode();
        if (lookupKeyEditing && lookupOverlay != null) {
            InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(lookupOverlay.getWindowToken(), 0);
        }
        lookupKeyEditing = false;
        lookupAiRequestGeneration++;
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
        lookupHtmlEditor = null;
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
        AppTheme.text(t, Color.WHITE);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        AppTheme.text(b, AppTheme.LINE);
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
        DictionaryOperation.overlayAdded(this);

        card.setOnClickListener(v -> removeResultCard());

        main.postDelayed(() -> {
            if (resultCard == card) removeResultCard();
        }, 7000);
    }

    private void registerSearchBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || searchPanel == null) return;
        View owner = searchPanel;
        owner.post(() -> {
            if (searchPanel != owner || searchPanelLp == null ||
                    (searchPanelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) return;
            try {
                android.window.OnBackInvokedDispatcher dispatcher = owner.findOnBackInvokedDispatcher();
                if (dispatcher == null) return;
                clearSearchBackCallback();
                android.window.OnBackInvokedCallback callback = () -> {
                    markAction("SEARCH_SYSTEM_GESTURE_BACK");
                    handleSearchBack();
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
        pendingDrawerExperience = "";
        pendingSearchPageRequest = "";
        pendingSearchPageHostSession = "";
        boolean hosted = searchPanelHosted;
        clearSearchBackCallback();

        if (searchPanel != null && wm != null) {
            if (searchPanel instanceof FloatingSearchView) ((FloatingSearchView)searchPanel).dispose();
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
        if (finishHost) {
            searchKeyboardRequested = false;
            if (searchPrivateMode) currentSearchQuery = "";
            searchPrivateMode = false;
            if (hosted) notifyLookupHostFinish();
        }
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

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (pickup != null) pickup.cancel();
        if (searchPanel != null) {
            // Native layout dimensions are created in pixels. Recreate the
            // search surface for density/font changes, retaining its draft
            // and privacy state while leaving the real bubble mounted.
            boolean hosted = searchPanelHosted;
            android.os.IBinder token = searchPanelLp.token;
            boolean paused = searchPanel instanceof FloatingSearchView && ((FloatingSearchView)searchPanel).isLookupPaused();
            int visibility = searchPanel.getVisibility();
            String pageRequest = pendingSearchPageRequest;
            String pageHostSession = pendingSearchPageHostSession;
            String experience = pendingDrawerExperience;
            removeSearchPanel(false);
            searchPanelHosted = hosted;
            showSearchPanel(token);
            pendingSearchPageRequest = pageRequest;
            pendingSearchPageHostSession = pageHostSession;
            pendingDrawerExperience = experience;
            if (paused || visibility != View.VISIBLE || !pageRequest.isEmpty()) rootSearchForLookup();
            if (searchPanel != null) searchPanel.setVisibility(visibility);
            openDrawerWhenReady();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        android.os.IBinder token = intent == null || intent.getExtras() == null ? null
                : intent.getExtras().getBinder(EXTRA_SEARCH_HOST_TOKEN);
        if (intent != null && ACTION_SEARCH_HOST_READY.equals(intent.getAction())) {
            if (activeHostSession.equals(intent.getStringExtra(EXTRA_HOST_SESSION))) {
                mountSearchPanel(token);
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_SHOW_SEARCH.equals(intent.getAction())
                && intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false)
                && !intent.hasExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST)) {
            String request = intent.getStringExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE);
            if (request != null && !request.isEmpty() && !request.equals(pendingDrawerExperience)) {
                finishDrawerExperience(request, false); return START_STICKY;
            }
        }
        if (intent != null && (ACTION_SHOW_SEARCH.equals(intent.getAction()) || ACTION_LOOKUP_DISMISS.equals(intent.getAction()))
                && intent.hasExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST)
                && (searchPanel == null || pendingSearchPageRequest.isEmpty()
                    || !pendingSearchPageRequest.equals(intent.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST))
                    || (!pendingSearchPageHostSession.isEmpty()
                        && !pendingSearchPageHostSession.equals(intent.getStringExtra(EXTRA_HOST_SESSION))))) {
            finishDrawerExperience(intent.getStringExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE), false);
            return START_STICKY;
        }
        if (intent != null && intent.getStringExtra(EXTRA_HOST_SESSION) != null) {
            String session = intent.getStringExtra(EXTRA_HOST_SESSION);
            boolean closing = ACTION_LOOKUP_DISMISS.equals(intent.getAction())
                    || ACTION_LOOKUP_BACK.equals(intent.getAction());
            boolean returningSearch = ACTION_SHOW_SEARCH.equals(intent.getAction()) && searchPanel != null
                    && intent.getBooleanExtra(EXTRA_RETURN_TO_SEARCH, false);
            if ((closing || returningSearch) && !activeHostSession.equals(session)) return START_STICKY;
            if ((ACTION_SHOW_SEARCH.equals(intent.getAction())
                    || ACTION_SHOW_LOOKUP_OVERLAY.equals(intent.getAction()))
                    && intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false)
                    && session != null) activeHostSession = session;
        }
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble();

        if (intent != null && FloatingSearchRoutes.ACTION_OPEN_PAGE.equals(intent.getAction())) {
            openSearchPage(intent.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE), intent.getBooleanExtra(
                    FloatingSearchRoutes.EXTRA_PRIVATE_MODE, searchPrivateMode));
        }

        if (intent != null && FloatingSearchRoutes.ACTION_PAGE_READY.equals(intent.getAction())
                && !pendingSearchPageRequest.isEmpty()
                && pendingSearchPageRequest.equals(intent.getStringExtra(FloatingSearchRoutes.EXTRA_PAGE_REQUEST))
                && (pendingSearchPageHostSession.isEmpty()
                    || pendingSearchPageHostSession.equals(intent.getStringExtra(EXTRA_HOST_SESSION)))) {
            if (searchPanel != null) searchPanel.setVisibility(View.INVISIBLE);
        }

        if (intent != null && ACTION_SHOW_SEARCH.equals(intent.getAction())) {
            String experience = intent.getStringExtra(FloatingSearchRoutes.EXTRA_EXPERIENCE);
            if (experience != null && !experience.isEmpty()) {
                if (!Settings.canDrawOverlays(this)) {
                    finishDrawerExperience(experience, false); return START_STICKY;
                }
                pendingDrawerExperience = experience;
                searchKeyboardRequested = false;
                main.postDelayed(() -> {
                    if (experience.equals(pendingDrawerExperience)) finishDrawerExperience(experience, false);
                }, 5000);
            }
            pendingSearchPageRequest = "";
            pendingSearchPageHostSession = "";
            // Search replaces the result surface, including its mask hit target
            // and Back callback. Never leave both modal overlays mounted.
            removeLookupOverlay(true);
            lookupOverlayHosted = false;
            lookupPrivateMode = false;
            mountSearchPanel(token);
            if (searchPanel != null) searchPanel.setVisibility(View.VISIBLE);
            if (searchPanel instanceof FloatingSearchView) ((FloatingSearchView)searchPanel).resumeAfterLookup();
            String restoredQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            if (restoredQuery != null && searchPanel == null) currentSearchQuery = restoredQuery;
            if (intent.hasExtra(FloatingSearchRoutes.EXTRA_PRIVATE_MODE)) {
                searchPrivateMode = intent.getBooleanExtra(
                        FloatingSearchRoutes.EXTRA_PRIVATE_MODE, false);
            }

            if (Settings.canDrawOverlays(this) && searchPanel == null) {
                if (intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false)) {
                    searchPanelHosted = true;
                    showSearchPanel(token);
                } else {
                    openSearchHost();
                }
            }
            openDrawerWhenReady();
        }

        if (intent != null && ACTION_SHOW_LOOKUP_OVERLAY.equals(intent.getAction())) {
            String q = normalizeLookupQuery(intent.getStringExtra(EXTRA_LOOKUP_QUERY));
            String mode = normalizeLookupMode(intent.getStringExtra(EXTRA_LOOKUP_MODE));
            boolean returnToSearch =
                    intent.getBooleanExtra(EXTRA_RETURN_TO_SEARCH, false);
            if (returnToSearch) mountSearchPanel(token);
            String restoredQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            if (restoredQuery != null) currentSearchQuery = restoredQuery;

            if (!q.isEmpty()) {
                if (routeThirdPartyLookup(q, returnToSearch, intent.getBooleanExtra(EXTRA_LOOKUP_PRIVATE, false))) {
                    // All initial lookup entry points share the selected destination.
                } else if (intent.getBooleanExtra(EXTRA_LOOKUP_HOSTED, false)) {
                    lookupPrivateMode = intent.getBooleanExtra(EXTRA_LOOKUP_PRIVATE, false);
                    lookupOverlayHosted = true;
                    showLookupOverlay(q, returnToSearch, mode);
                } else {
                    // OCR still delivers its result directly to this Service.
                    // The shared transparent host supplies full-display mask
                    // pixels and a real system Back target, then forwards the
                    // hosted request back here to render the same overlay card.
                    openLookupHost(q, returnToSearch, mode);
                }
            }
        }

        if (intent != null && ACTION_LOOKUP_DISMISS.equals(intent.getAction())) {
            removeSearchPanel(false);
            removeLookupOverlay(true);
            lookupOverlayHosted = false;
            if (searchPrivateMode) currentSearchQuery = "";
            searchPrivateMode = false;
            lookupPrivateMode = false;
            notifyLookupHostFinish();
        }

        if (intent != null && ACTION_LOOKUP_BACK.equals(intent.getAction())) {
            // Service callbacks already run on the main thread. Handle Back
            // immediately so it cannot overtake a queued overlay show/dismiss.
            if (lookupOverlay != null) {
                handleLookupBack();
            } else if (searchPanel != null) {
                handleSearchBack();
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
        FloatingBubbleAppearance.removeChangeListener(bubbleAppearanceListener);

        if (pickup != null) { pickup.close(); pickup = null; }
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

        if (textToSpeech != null) {
            try { textToSpeech.stop(); textToSpeech.shutdown(); }
            catch (RuntimeException ignored) {}
            textToSpeech = null;
            pronunciationReady = false;
            pendingPronunciation = null;
        }

        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
