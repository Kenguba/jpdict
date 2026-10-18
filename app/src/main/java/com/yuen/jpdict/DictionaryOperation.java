package com.yuen.jpdict;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.Future;

/** A process-owned operation; dismissing or recreating its Activity does not cancel I/O. */
final class DictionaryOperation {
    interface Continuation { void proceed(DictionaryActivity page); }
    private static DictionaryOperation instance;
    static synchronized DictionaryOperation get(Context context) {
        if (instance == null) instance = new DictionaryOperation(context.getApplicationContext());
        return instance;
    }
    /** New app overlays are added above older windows of the same type. */
    static void overlayAdded(Context context) {
        DictionaryOperation operation;
        synchronized (DictionaryOperation.class) { operation = instance; }
        if (operation == null) return;
        operation.main.post(() -> {
            if (!operation.hasTask() || !operation.background || operation.dialog != null || operation.animation != null) return;
            operation.hideBubble(); operation.showBubble();
        });
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private Future<?> work;
    private volatile String message = "准备中…";
    private volatile long done, total;
    private volatile boolean complete;
    private Exception failure;
    private Continuation continuation;
    private WeakReference<Activity> owner = new WeakReference<>(null);
    private AlertDialog dialog;
    private LinearLayout panel;
    private TextView detail, button;
    private ProgressBar progress;
    private Bubble bubble;
    private WindowManager.LayoutParams bubbleParams;
    private ValueAnimator animation;
    private View animationView;
    private long lastUpdate;
    private int generation;
    private boolean background, released;
    private final Runnable update = this::updateViews;

    private DictionaryOperation(Context context) { this.context = context; windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE); }
    boolean running() { return hasTask() && !complete; }
    boolean hasTask() { return generation > 0 && !released; }
    boolean start(Activity activity, DictionaryRepository repository, DictionaryRepository.Work task, DictionaryRepository.Complete callback) {
        return start(activity, repository, task, callback, null);
    }
    boolean start(Activity activity, DictionaryRepository repository, DictionaryRepository.Work task, DictionaryRepository.Complete callback, Continuation next) {
        if (running()) { show(activity); return false; }
        dismiss();
        owner = new WeakReference<>(activity); complete = false; failure = null; background = false; released = false;
        continuation = next; message = "准备中…"; done = total = lastUpdate = 0; int request = ++generation;
        show(activity);
        Intent service = new Intent(context, DictionaryOperationService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        work = repository.execute(task, error -> {
            if (request != generation) return;
            complete = true; failure = error;
            if (error != null) { continuation = null; }
            message = error == null ? (continuation == null ? "操作完成" : "扫描完成，点按继续选择词库") : "操作失败";
            updateViews(); callback.done(error);
            Activity foreground = owner.get();
            if (foreground instanceof DictionaryActivity) continueIn((DictionaryActivity)foreground);
        });
        if (work == null) { complete = true; failure = new java.io.IOException("操作队列已满，请稍后重试"); }
        return true;
    }
    void attach(Activity activity) {
        owner = new WeakReference<>(activity);
        if (activity instanceof DictionaryActivity && continueIn((DictionaryActivity)activity)) { return; }
        if (hasTask() && !background) show(activity);
    }
    void detach(Activity activity) {
        if (owner.get() != activity) return;
        owner.clear();
        if (hasTask()) { background = true; dismissDialog(); showBubble(); }
    }
    void background(Activity activity) {
        if (owner.get() != activity) return;
        if (hasTask() && dialog != null) minimize();
        owner.clear(); // Restoration over another app must use an overlay window.
    }
    void configurationChanged() {
        if (bubbleParams != null) { clampBubble(); if (bubble != null) windows.updateViewLayout(bubble, bubbleParams); }
    }
    void progress(String text, long completed, long count) {
        message = text; done = completed; total = count;
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            if (now - lastUpdate < 100 && (count <= 0 || completed != count)) return;
            lastUpdate = now;
        }
        main.removeCallbacks(update); main.post(update);
    }
    boolean requiresActivity() { return complete && failure == null && continuation != null && hasTask(); }
    private boolean continueIn(DictionaryActivity page) {
        if (!requiresActivity() || owner.get() != page || page.isFinishing() || page.isDestroyed()) return false;
        Continuation next = continuation; continuation = null; dismiss(); next.proceed(page); return true;
    }
    String notificationText() { if (requiresActivity()) return "扫描完成，点按继续选择词库"; return complete ? (failure == null ? "词库操作完成，点按查看" : "词库操作失败，点按查看") : message; }
    void restore() {
        if (!hasTask()) return;
        Activity activity = owner.get();
        if (requiresActivity()) {
            if (activity instanceof DictionaryActivity && continueIn((DictionaryActivity)activity)) return;
            // Selection UI belongs to a foreground library page. Only an explicit
            // bubble/notification tap brings that page forward; completion never does.
            context.startActivity(new Intent(context, DictionaryActivity.class).putExtra("show_dictionary_progress", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        show(activity != null && !activity.isFinishing() && !activity.isDestroyed() ? activity : null);
    }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private TextView label(Context themed, String text, int size, int color) {
        TextView result = new TextView(themed); result.setText(text); result.setTextSize(size); AppTheme.text(result, color); result.setIncludeFontPadding(false); return result;
    }
    private void show(Activity activity) {
        if (dialog != null && dialog.isShowing()) return;
        if (activity == null && !Settings.canDrawOverlays(context)) return;
        Context themed = AppTheme.dialogContext(activity == null ? context : activity);
        panel = new LinearLayout(themed); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView heading = label(themed, "词库操作", 18, AppTheme.INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams headingLayout = new LinearLayout.LayoutParams(-1, -2); headingLayout.bottomMargin = dp(8); panel.addView(heading, headingLayout);
        detail = label(themed, message, 14, AppTheme.MUTED); panel.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(themed, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, dp(6)); progressLayout.topMargin = dp(8); panel.addView(progress, progressLayout);
        button = label(themed, "后台加载", 14, AppTheme.BLUE); button.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); button.setMinHeight(dp(44));
        button.setOnClickListener(v -> { if (complete) dismiss(); else minimize(); }); panel.addView(button, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog = new AppTheme.DialogBuilder(themed).create(); dialog.setView(panel, 0, 0, 0, 0); dialog.setCancelable(false);
        if (activity == null) dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        try {
            dialog.show(); dialog.getWindow().setLayout(Math.min(dp(360), context.getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            background = false; updateViews();
            if (bubble != null) {
                int[] target = new int[2]; panel.post(() -> {
                    if (panel == null) return;
                    panel.getLocationOnScreen(target);
                    RectF to = new RectF(target[0], target[1], target[0] + panel.getWidth(), target[1] + panel.getHeight());
                    RectF from = bubbleRect(); hideBubble(); animate(panel, from, to, false, () -> {});
                });
            }
        } catch (WindowManager.BadTokenException | SecurityException unavailable) { dialog = null; background = true; showBubble(); }
    }
    private void updateViews() {
        if (released) return;
        if (detail != null) {
            String text = failure == null ? message : "操作失败\n" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            if (!complete && total > 0) text += "\n" + java.text.NumberFormat.getIntegerInstance().format(done) + " / " + java.text.NumberFormat.getIntegerInstance().format(total);
            detail.setText(text); button.setText(complete ? "关闭" : "后台加载");
            progress.setVisibility(complete ? View.GONE : View.VISIBLE); progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int)Math.min(1000, (double)done / total * 1000));
        }
        if (bubble != null) { bubble.setContentDescription(requiresActivity() ? "扫描完成，点按继续选择词库" : complete ? (failure == null ? "词库加载完成，点按查看" : "词库加载失败，点按查看") : "词库后台加载，点按恢复进度"); bubble.invalidate(); }
        DictionaryOperationService.refresh(context);
    }
    private RectF bubbleRect() {
        if (bubbleParams == null) {
            bubbleParams = new WindowManager.LayoutParams(dp(60), dp(60), Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            android.content.SharedPreferences preferences = context.getSharedPreferences("dictionary-operation", Context.MODE_PRIVATE);
            bubbleParams.x = preferences.getInt("x", metrics.widthPixels - dp(76)); bubbleParams.y = preferences.getInt("y", metrics.heightPixels - dp(148)); clampBubble();
        }
        return new RectF(bubbleParams.x, bubbleParams.y, bubbleParams.x + dp(60), bubbleParams.y + dp(60));
    }
    private void clampBubble() {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        bubbleParams.x = Math.max(0, Math.min(metrics.widthPixels - dp(60), bubbleParams.x));
        bubbleParams.y = Math.max(0, Math.min(metrics.heightPixels - dp(60), bubbleParams.y));
    }
    private void showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(context) || released) return;
        bubbleRect(); bubble = new Bubble(context);
        try { windows.addView(bubble, bubbleParams); updateViews(); }
        catch (WindowManager.BadTokenException | SecurityException unavailable) { bubble = null; }
    }
    private void hideBubble() { if (bubble != null) { try { windows.removeView(bubble); } catch (IllegalArgumentException ignored) {} bubble = null; } }
    private void minimize() {
        if (dialog == null) { background = true; showBubble(); return; }
        background = true;
        int[] location = new int[2]; panel.getLocationOnScreen(location);
        RectF from = new RectF(location[0], location[1], location[0] + panel.getWidth(), location[1] + panel.getHeight());
        LinearLayout source = panel;
        animate(source, from, bubbleRect(), true, this::showBubble);
        dismissDialog();
    }
    private void animate(View source, RectF from, RectF to, boolean minimize, Runnable after) {
        stopAnimation();
        float scale = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
        if (scale == 0 || !Settings.canDrawOverlays(context) || source.getWidth() <= 0 || source.getHeight() <= 0) { after.run(); return; }
        Bitmap picture = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888); source.draw(new Canvas(picture));
        Genie genie = new Genie(context, picture, from, to, minimize); animationView = genie;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        try { windows.addView(genie, params); }
        catch (WindowManager.BadTokenException | SecurityException unavailable) { animationView = null; picture.recycle(); after.run(); return; }
        animation = ValueAnimator.ofFloat(0, 1); animation.setDuration(380); animation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animation.addUpdateListener(value -> { genie.time = (float)value.getAnimatedValue(); genie.invalidate(); });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) { stopAnimation(); after.run(); }
        }); animation.start();
    }
    private void stopAnimation() {
        if (animation != null) { animation.removeAllListeners(); animation.cancel(); animation = null; }
        if (animationView != null) {
            Genie old = (Genie)animationView; try { windows.removeView(old); } catch (IllegalArgumentException ignored) {}
            // removeView may leave a queued hardware draw holding this bitmap.
            // Its immutable pixels stay alive until the detached View/render refs
            // are collected; explicit recycle can crash Android's render thread.
            animationView = null;
        }
    }
    private void dismissDialog() { if (dialog != null) dialog.dismiss(); dialog = null; panel = null; detail = button = null; progress = null; }
    void cancel() { if (work != null && !complete) work.cancel(true); }
    void dismiss() {
        released = true; continuation = null; stopAnimation(); dismissDialog(); hideBubble();
        DictionaryOperationService.clearResult(context);
        context.stopService(new Intent(context, DictionaryOperationService.class));
    }
    private final class Bubble extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY; int startX, startY; boolean dragged;
        Bubble(Context context) { super(context); AppTheme.observe(this, this::invalidate); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            float center = getWidth() / 2f, radius = center - dp(4);
            paint.setStyle(Paint.Style.FILL); paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), AppTheme.color(AppTheme.SOFT), AppTheme.color(AppTheme.PALE), Shader.TileMode.CLAMP));
            paint.setShadowLayer(dp(3), 0, dp(1), 0x22006bff); canvas.drawCircle(center, center, radius, paint); paint.clearShadowLayer(); paint.setShader(null);
            if (!complete) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(AppTheme.ink(AppTheme.BLUE));
                float angle = total > 0 ? Math.max(12, Math.min(360, 360f * done / total)) : 90;
                canvas.drawArc(dp(5), dp(5), getWidth() - dp(5), getHeight() - dp(5), -90, angle, false, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(dp(12)); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(AppTheme.ink(failure == null ? AppTheme.BLUE : AppTheme.RED));
            canvas.drawText(requiresActivity() ? "待选择" : complete ? (failure == null ? "完成" : "失败") : "加载", center, center - (paint.ascent() + paint.descent()) / 2, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getRawX(); downY = event.getRawY(); startX = bubbleParams.x; startY = bubbleParams.y; dragged = false; return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float x = event.getRawX() - downX, y = event.getRawY() - downY;
                if (Math.hypot(x, y) > ViewConfiguration.get(context).getScaledTouchSlop()) dragged = true;
                if (dragged) { bubbleParams.x = startX + Math.round(x); bubbleParams.y = startY + Math.round(y); clampBubble(); windows.updateViewLayout(this, bubbleParams); }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (!dragged) { performClick(); restore(); }
                else context.getSharedPreferences("dictionary-operation", Context.MODE_PRIVATE).edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply();
                return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
    private static final class Genie extends View {
        final Bitmap picture; final RectF from, to; final boolean minimize;
        final float[] mesh = new float[(12 + 1) * (16 + 1) * 2]; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); float time;
        Genie(Context context, Bitmap picture, RectF from, RectF to, boolean minimize) { super(context); this.picture = picture; this.from = from; this.to = to; this.minimize = minimize; }
        @Override protected void onDraw(Canvas canvas) {
            float value = minimize ? time : 1 - time; RectF large = minimize ? from : to, small = minimize ? to : from;
            int index = 0;
            for (int row = 0; row <= 16; row++) {
                float vertical = row / 16f;
                // The edge nearer the destination contracts first to form a funnel.
                float contract = Math.min(1, value * (1.55f - .55f * (1 - vertical)));
                float center = large.centerX() + (small.centerX() - large.centerX()) * value;
                float width = large.width() + (small.width() - large.width()) * contract;
                float y = large.top + vertical * large.height();
                y += (small.top + vertical * small.height() - y) * value;
                center += (small.centerX() - large.centerX()) * .15f * (float)Math.sin(Math.PI * vertical) * (float)Math.sin(Math.PI * value);
                for (int column = 0; column <= 12; column++) { mesh[index++] = center + (column / 12f - .5f) * width; mesh[index++] = y; }
            }
            paint.setAlpha(Math.round(255 * (1 - value * .25f))); canvas.drawBitmapMesh(picture, 12, 16, mesh, 0, null, 0, paint);
        }
    }
}
