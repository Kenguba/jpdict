package com.yuen.jpdict;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import java.util.*;

/** One pickup gesture/session for the original bubble and its overlay proxy. */
final class OcrPickupController implements AutoCloseable {
    interface Host {
        Rect position();
        Rect move(int x, int y);
        void save();
        void tap();
        void lookup(String text);
    }
    private final Context context;
    private final Host host;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int slop;
    private final float density;
    private SelectionView overlay;
    private boolean overlayHiding;
    private OcrJoystickView joystick;
    private OcrJoystickView dismissingJoystick;
    private final OcrSweepSelection sweep = new OcrSweepSelection();
    private final OcrPointerGeometry pointer = new OcrPointerGeometry();
    private final List<float[]> path = new ArrayList<>();
    private OcrPickupMode mode = OcrPickupMode.WORD;
    private boolean pendingAuthorization;
    private long gestureGeneration;
    private final List<MultilingualOcrRecognizer.Region> regions = new ArrayList<>();
    private MultilingualOcrRecognizer.Region selected;
    private long session;
    private boolean armed, waiting, released, closed, dragging, captureVisible;
    private float aimX, aimY;
    private final Rect aimBubble = new Rect();
    private final Runnable timeout = () -> fail("OCR 超时，请确认识别模型已下载后重试");
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (closed) return;
            String action = intent.getAction();
            if (MainActivity.ScreenCaptureService.ACTION_READY.equals(action)) {
                if (pendingAuthorization) main.postDelayed(() -> {
                    if (!pendingAuthorization || closed) return;
                    pendingAuthorization = false; startPickup(true);
                }, 300);
                return;
            }
            if (MainActivity.OcrActivity.ACTION_FINISHED.equals(action)) {
                if (pendingAuthorization && !intent.getBooleanExtra("granted", false)) cancel();
                return;
            }
            if (MainActivity.ScreenCaptureService.ACTION_STOPPED.equals(action)) {
                if (armed) cancel();
                return;
            }
            if (!armed || intent.getLongExtra("session", -1) != session) return;
            if (intent.getBooleanExtra("permission_required", false)) { requestAuthorization(); return; }
            if (MainActivity.ScreenCaptureService.ACTION_PREVIEW_FRAME.equals(intent.getAction())) {
                captureVisible = true; showOverlay(); return;
            }
            String error = intent.getStringExtra("error");
            if (error != null) { fail(error); return; }
            if (intent.getBooleanExtra("refined", false)) {
                String text = intent.getStringExtra("text");
                cancel();
                if (text != null && !text.trim().isEmpty()) host.lookup(text);
                else Toast.makeText(context, "准星附近没有文字，请对准文字重试", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] words = intent.getStringArrayExtra("words");
            int[] boxes = intent.getIntArrayExtra("boxes");
            if (words == null || boxes == null || boxes.length != words.length * 4) { fail("OCR 结果无效，请重试"); return; }
            waiting = false; main.removeCallbacks(timeout);
            float[] quality = intent.getFloatArrayExtra("quality");
            for (int i = 0; i < words.length; i++) {
                MultilingualOcrRecognizer.Region region = new MultilingualOcrRecognizer.Region(words[i], new Rect(boxes[i*4], boxes[i*4+1], boxes[i*4+2], boxes[i*4+3]));
                if (quality != null && quality.length == words.length) region.quality = quality[i];
                regions.add(region);
            }
            int[] owners = intent.getIntArrayExtra("symbol_owners");
            int[] offsets = intent.getIntArrayExtra("symbol_offsets");
            int[] symbolBoxes = intent.getIntArrayExtra("symbol_boxes");
            if (owners != null && offsets != null && symbolBoxes != null
                    && offsets.length == owners.length * 2 && symbolBoxes.length == owners.length * 4) {
                for (int i=0; i<owners.length; i++) {
                    if (owners[i] < 0 || owners[i] >= regions.size()) continue;
                    MultilingualOcrRecognizer.Region region = regions.get(owners[i]);
                    int start = offsets[i*2], end = offsets[i*2+1];
                    if (start < 0 || end <= start || end > region.text.length()) continue;
                    region.symbols.add(new MultilingualOcrRecognizer.Symbol(start, end,
                            new Rect(symbolBoxes[i*4], symbolBoxes[i*4+1], symbolBoxes[i*4+2], symbolBoxes[i*4+3])));
                }
            }
            for (float[] point : path) sweep.move(regions, point[0], point[1], 12*density);
            path.clear();
            select();
            if (released) finish(); else showOverlay();
        }
    };
    OcrPickupController(Context context, Host host) {
        this.context = context; this.host = host;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        IntentFilter filter = new IntentFilter(MainActivity.ScreenCaptureService.ACTION_PREVIEW);
        filter.addAction(MainActivity.ScreenCaptureService.ACTION_PREVIEW_FRAME);
        filter.addAction(MainActivity.ScreenCaptureService.ACTION_READY);
        filter.addAction(MainActivity.ScreenCaptureService.ACTION_STOPPED);
        filter.addAction(MainActivity.OcrActivity.ACTION_FINISHED);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    View.OnTouchListener listener() {
        return new View.OnTouchListener() {
            float downX, downY;
            Rect start;
            int lastX, lastY, direction;
            View pressed;
            boolean moved, held, ocrRequested;
            long generation;
            final OcrDwellGate dwell = new OcrDwellGate(Math.max(slop, 12*density));
            final Runnable activate = () -> {
                if (pressed == null || !moved || held || closed || ocrRequested || generation != gestureGeneration) return;
                ocrRequested = true;
                startPickup(false);
            };
            final Runnable hold = () -> {
                if (pressed == null || moved || closed || generation != gestureGeneration) return;
                held = true;
                pressed.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                joystick = new OcrJoystickView(context, start);
                wm.addView(joystick, overlayParams());
                DictionaryOperation.overlayAdded(context);
            };
            private void drag(MotionEvent event) {
                float dx = event.getRawX()-downX, dy = event.getRawY()-downY;
                if (held) {
                    int next = OcrPickupMode.direction(dx, dy, 28*density);
                    if (next != direction) {
                        direction = next;
                        if (pressed != null && next >= 0) pressed.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    float distance = (float)Math.hypot(dx, dy), travel = 26*density;
                    float scale = distance > travel ? travel/distance : 1;
                    Rect knob=host.move(start.left+Math.round(dx*scale), start.top+Math.round(dy*scale));
                    if (joystick != null) joystick.select(direction, knob);
                    return;
                }
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    moved = true; main.removeCallbacks(hold);
                }
                if (!moved) return;
                dragging = true;
                int x = start.left+Math.round(dx), y = start.top+Math.round(dy);
                if (x != lastX || y != lastY) {
                    boolean wasBelow = pointer.below();
                    android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(screen);
                    pointer.update(y, y+start.height(), screen.heightPixels, density);
                    int top = Math.round(pointer.clampBubbleTop(y, start.height(), screen.heightPixels, density));
                    updateAim(host.move(x, top)); lastX = x; lastY = y;
                    if (wasBelow != pointer.below()) {
                        // Side changes are positioning, not a text-selection stroke.
                        sweep.clear(); path.clear(); selected = null; dwell.reset();
                    }
                }
                showOverlay();
                if (!armed || (waiting && regions.isEmpty())) recordPoint();
                if (!ocrRequested) {
                    long remaining = dwell.move(aimX, aimY, SystemClock.uptimeMillis());
                    main.removeCallbacks(activate); main.postDelayed(activate, remaining);
                }
                if (armed) {
                    if (!waiting || !regions.isEmpty()) select();
                }
            }
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (closed) return true;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancel(); main.removeCallbacks(hold); main.removeCallbacks(activate);
                        mode = OcrPickupMode.WORD;
                        pointer.reset();
                        downX = event.getRawX(); downY = event.getRawY(); start = host.position();
                        updateAim(start); lastX = start.left; lastY = start.top; direction = -1;
                        moved = held = ocrRequested = false; pressed = view; dwell.reset();
                        generation = gestureGeneration;
                        main.postDelayed(hold, 700); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (generation == gestureGeneration) drag(event); return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(hold); main.removeCallbacks(activate);
                        if (generation != gestureGeneration) { pressed = null; return true; }
                        drag(event); pressed = null; main.removeCallbacks(activate);
                        dragging = false;
                        if (held) {
                            hideJoystick(); updateAim(host.move(start.left, start.top));
                            OcrPickupMode chosen = OcrPickupMode.forDirection(direction);
                            if (direction == 3) {
                                LookupDestinationSettings.get(context).toggleSystem();
                                startPickup(true);
                            }
                            else if (chosen != null) {
                                mode = chosen;
                                OcrLanguageSettings.get(context).setLanguage(chosen == OcrPickupMode.ENGLISH
                                        ? OcrLanguageSettings.Language.ENGLISH : chosen == OcrPickupMode.JAPANESE
                                        ? OcrLanguageSettings.Language.JAPANESE : OcrLanguageSettings.Language.MIXED);
                                startPickup(true);
                            }
                        } else if (moved) {
                            host.save();
                            if (armed) { released = true; if (!waiting) finish(); }
                        } else host.tap();
                        if (!armed) hideOverlay();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(hold); main.removeCallbacks(activate); pressed = null;
                        if (moved || held) host.move(start.left, start.top);
                        cancel(); return true;
                    default: return true;
                }
            }
        };
    }
    private void startPickup(boolean immediatelyRelease) {
        if (!MainActivity.ScreenCaptureService.READY) { requestAuthorization(); return; }
        armed = waiting = true; released = immediatelyRelease;
        captureVisible = false;
        sweep.clear(); regions.clear(); selected = null;
        if (immediatelyRelease) path.clear();
        if (path.isEmpty()) recordPoint();
        showOverlay();
        session = SystemClock.elapsedRealtimeNanos();
        final long requestedSession = session;
        Runnable capture = () -> {
            if (!armed || closed || session != requestedSession) return;
            context.startService(new Intent(context, MainActivity.ScreenCaptureService.class)
                    .setAction(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                    .putExtra("session", requestedSession).putExtra("pickup_mode", mode.value));
        };
        // Let the outgoing white direction ring leave the next display frame
        // before requesting capture; it must never cover the text being read.
        if (dismissingJoystick != null) main.postDelayed(capture, 160); else capture.run();
        main.removeCallbacks(timeout); main.postDelayed(timeout, 10000);
    }
    private void requestAuthorization() {
        cancel(); pendingAuthorization = true;
        context.startActivity(new Intent(context, MainActivity.OcrActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
    private void hideJoystick() {
        if (dismissingJoystick != null) {
            dismissingJoystick.animate().cancel(); wm.removeView(dismissingJoystick); dismissingJoystick = null;
        }
        if (joystick == null) return;
        OcrJoystickView outgoing = joystick; joystick = null;
        outgoing.animate().cancel();
        if (closed) { wm.removeView(outgoing); return; }
        dismissingJoystick = outgoing;
        outgoing.animate().alpha(0).setDuration(140).withEndAction(() -> {
            if (dismissingJoystick != outgoing) return;
            wm.removeView(outgoing); dismissingJoystick = null;
        }).start();
    }
    static String swipeDirection(float dx, float dy, float threshold) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) < threshold) return null;
        return Math.abs(dy) >= Math.abs(dx) ? (dy < 0 ? "向上滑动" : "向下滑动") : (dx < 0 ? "向左滑动" : "向右滑动");
    }
    private void updateAim(Rect bubble) {
        aimBubble.set(bubble);
        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(screen);
        aimX = bubble.exactCenterX(); aimY = pointer.update(bubble.top, bubble.bottom, screen.heightPixels, density);
        if (overlay != null) overlay.invalidate();
    }
    private void recordPoint() {
        if (!path.isEmpty()) {
            float[] previous = path.get(path.size()-1);
            if (previous[0] == aimX && previous[1] == aimY) return;
        }
        // Covers the 10s recognition deadline even at 240 pointer samples/s.
        if (path.size() >= 4096) path.remove(1);
        path.add(new float[]{aimX, aimY});
    }
    private void select() {
        sweep.move(regions, aimX, aimY, 12*density); selected = sweep.combined();
        if (overlay != null) overlay.invalidate();
    }
    static MultilingualOcrRecognizer.Region regionAt(List<MultilingualOcrRecognizer.Region> regions, float x, float y, float tolerance) {
        return MultilingualOcrRecognizer.regionAt(regions, x, y, tolerance);
    }
    private void showOverlay() {
        if (overlay != null) {
            overlay.frozen = null;
            if (overlayHiding) {
                overlayHiding = false; overlay.animate().cancel();
                overlay.animate().alpha(1).setDuration(140).start();
            }
            overlay.invalidate(); return;
        }
        if (!armed && !dragging) return;
        overlay = new SelectionView(context);
        overlay.setAlpha(0);
        wm.addView(overlay, overlayParams());
        overlay.animate().alpha(1).setDuration(140).start();
        DictionaryOperation.overlayAdded(context);
    }
    private void hideOverlay() {
        if (overlay == null) return;
        SelectionView outgoing = overlay;
        outgoing.animate().cancel();
        if (closed) { wm.removeView(outgoing); overlay = null; overlayHiding = false; return; }
        outgoing.freeze();
        overlayHiding = true;
        outgoing.animate().alpha(0).setDuration(140).withEndAction(() -> {
            if (overlay != outgoing || !overlayHiding) return;
            wm.removeView(outgoing); overlay = null; overlayHiding = false;
        }).start();
    }
    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
    }
    private void finish() {
        waiting = true;
        Rect target = selected == null ? new Rect(Math.round(aimX-140*density), Math.round(aimY-36*density), Math.round(aimX+140*density), Math.round(aimY+36*density)) : selected.bounds;
        Intent request = new Intent(context, MainActivity.ScreenCaptureService.class)
                .setAction(MainActivity.ScreenCaptureService.ACT_OCR_REFINE).putExtra("session", session)
                .putExtra("target", target).putExtra("aim_x", aimX).putExtra("aim_y", aimY).putExtra("tolerance", 12*density);
        if (selected != null) {
            request.putExtra("seed", selected.text).putExtra("quality", selected.quality)
                    .putExtra("bounded_range", selected.boundedRange);
            int[] selectionBoxes = new int[sweep.picked.size()*4];
            for (int i=0;i<sweep.picked.size();i++) {
                Rect bounds=sweep.picked.get(i).bounds;
                selectionBoxes[i*4]=bounds.left;selectionBoxes[i*4+1]=bounds.top;
                selectionBoxes[i*4+2]=bounds.right;selectionBoxes[i*4+3]=bounds.bottom;
            }
            request.putExtra("selection_boxes",selectionBoxes);
        }
        context.startService(request);
        main.removeCallbacks(timeout); main.postDelayed(timeout, 10000);
        if (overlay != null) overlay.invalidate();
    }
    private void fail(String error) { cancel(); Toast.makeText(context, error, Toast.LENGTH_LONG).show(); }
    void cancel() {
        if (armed && MainActivity.ScreenCaptureService.READY) context.startService(new Intent(context, MainActivity.ScreenCaptureService.class)
                .setAction(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session", session));
        hideOverlay();
        armed = waiting = released = pendingAuthorization = dragging = captureVisible = false; session++; gestureGeneration++;
        hideJoystick(); sweep.clear();
        main.removeCallbacks(timeout); regions.clear(); path.clear(); selected = null;
    }
    @Override public void close() { closed = true; cancel(); main.removeCallbacksAndMessages(null); context.unregisterReceiver(receiver); }
    private final class SelectionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Picture frozen;
        SelectionView(Context c) { super(c); }
        void freeze() {
            if (frozen != null || getWidth() <= 0 || getHeight() <= 0) return;
            frozen = new Picture();
            drawSelection(frozen.beginRecording(getWidth(), getHeight())); frozen.endRecording();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (frozen != null) { canvas.drawPicture(frozen); return; }
            drawSelection(canvas);
        }
        private void drawSelection(Canvas canvas) {
            int[] location = new int[2]; getLocationOnScreen(location);
            canvas.save(); canvas.translate(-location[0], -location[1]);
            if (captureVisible && selected != null) {
                paint.setColor(0x6639c99a);
                for (MultilingualOcrRecognizer.Region region : sweep.picked)
                    canvas.drawRoundRect(new RectF(region.bounds), 3*density, 3*density, paint);
            }
            // A hollow centre keeps the pointed glyph readable during capture.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3*density); paint.setColor(Color.WHITE);
            canvas.drawCircle(aimX, aimY, 4*density, paint);
            paint.setStrokeWidth(1.5f*density); paint.setColor(selected == null ? 0xff168af2 : 0xff00a777);
            canvas.drawCircle(aimX, aimY, 4*density, paint); paint.setStyle(Paint.Style.FILL);
            if (!captureVisible || (waiting && released)) { canvas.restore(); return; }
            String tip = waiting ? "识别中 · 继续按住并拖动" : selected == null ? "移动准星对准文字 · 松手取词" : mode.label+" · 可来回划选 · 松手查词";
            paint.setTextSize(12*density);
            float width = paint.measureText(tip) + 24*density;
            float left = Math.max(8*density, Math.min(aimX-width/2, getWidth()-width-8*density));
            float tipY = pointer.below() ? aimBubble.top-38*density : aimY-54*density;
            float top = Math.min(getHeight()-38*density, Math.max(32*density, tipY));
            paint.setColor(waiting ? 0xe63c4654 : selected == null ? 0xe6168af2 : 0xe6008a68);
            canvas.drawRoundRect(left, top, left+width, top+30*density, 8*density, 8*density, paint);
            paint.setColor(Color.WHITE); canvas.drawText(tip, left+12*density, top+20*density, paint);
            canvas.restore();
        }
    }
}
