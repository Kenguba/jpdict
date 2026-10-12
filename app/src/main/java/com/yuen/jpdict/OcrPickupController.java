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
        void move(int x, int y);
        void save();
        void tap();
        void authorize();
        void lookup(String text);
    }
    private final Context context;
    private final Host host;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int slop;
    private final float density;
    private SelectionView overlay;
    private final List<MultilingualOcrRecognizer.Region> regions = new ArrayList<>();
    private MultilingualOcrRecognizer.Region selected;
    private long session;
    private boolean armed, waiting, released, closed;
    private float aimX, aimY;
    private final Runnable timeout = () -> fail("OCR 超时，请确认识别模型已下载后重试");
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (closed || !armed || intent.getLongExtra("session", -1) != session) return;
            if (MainActivity.ScreenCaptureService.ACTION_PREVIEW_FRAME.equals(intent.getAction())) {
                showOverlay(); return;
            }
            String error = intent.getStringExtra("error");
            if (error != null) { fail(error); return; }
            String[] words = intent.getStringArrayExtra("words");
            int[] boxes = intent.getIntArrayExtra("boxes");
            if (words == null || boxes == null || boxes.length != words.length * 4) { fail("OCR 结果无效，请重试"); return; }
            waiting = false; main.removeCallbacks(timeout);
            for (int i = 0; i < words.length; i++) regions.add(new MultilingualOcrRecognizer.Region(words[i], new Rect(boxes[i*4], boxes[i*4+1], boxes[i*4+2], boxes[i*4+3])));
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
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    View.OnTouchListener listener() {
        return new View.OnTouchListener() {
            float downX, downY;
            Rect start;
            View pressed;
            boolean moved, held;
            final Runnable hold = () -> {
                if (pressed == null || moved || closed) return;
                held = true;
                pressed.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (!MainActivity.ScreenCaptureService.READY) { host.authorize(); return; }
                armed = waiting = true; released = false;
                session = SystemClock.elapsedRealtimeNanos();
                updateAim();
                context.startService(new Intent(context, MainActivity.ScreenCaptureService.class)
                        .setAction(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session", session));
                main.postDelayed(timeout, 10000);
            };
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancel(); main.removeCallbacks(hold);
                        downX = event.getRawX(); downY = event.getRawY(); start = host.position();
                        moved = held = false; pressed = view;
                        main.postDelayed(hold, 700); return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX), dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) { moved = true; main.removeCallbacks(hold); }
                        if (moved) host.move(start.left + dx, start.top + dy);
                        if (armed) { updateAim(); select(); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(hold); pressed = null;
                        if (moved) host.save();
                        if (armed) { released = true; if (!waiting) finish(); }
                        else if (!held && !moved) host.tap();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(hold); pressed = null;
                        if (moved) { host.move(start.left, start.top); }
                        cancel(); return true;
                    default: return true;
                }
            }
        };
    }
    private void updateAim() {
        Rect bubble = host.position(); aimX = bubble.exactCenterX(); aimY = Math.max(0, bubble.top - 12 * density);
    }
    private void select() {
        selected = regionAt(regions, aimX, aimY, 18*density);
        if (overlay != null) overlay.invalidate();
    }
    static MultilingualOcrRecognizer.Region regionAt(List<MultilingualOcrRecognizer.Region> regions, float x, float y, float tolerance) {
        MultilingualOcrRecognizer.Region selected = null; double best = Double.MAX_VALUE;
        for (MultilingualOcrRecognizer.Region region : regions) {
            Rect b = region.bounds;
            double dx = Math.max(Math.max(b.left - x, x - b.right), 0);
            double dy = Math.max(Math.max(b.top - y, y - b.bottom), 0);
            double distance = dx*dx + dy*dy;
            // Never select distant text just because OCR found something elsewhere.
            if (distance > tolerance*tolerance) continue;
            double rank = distance + (double)b.width()*b.height()*0.0001;
            if (rank < best) { best = rank; selected = region; }
        }
        return selected;
    }
    private void showOverlay() {
        if (overlay != null || !armed) return;
        overlay = new SelectionView(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(overlay, lp);
    }
    private void finish() {
        String text = selected == null ? null : selected.text;
        cancel();
        if (text != null) host.lookup(text); else Toast.makeText(context, "准星附近没有文字，请对准文字重试", Toast.LENGTH_SHORT).show();
    }
    private void fail(String error) { cancel(); Toast.makeText(context, error, Toast.LENGTH_LONG).show(); }
    void cancel() {
        armed = waiting = released = false; session++;
        main.removeCallbacks(timeout); regions.clear(); selected = null;
        if (overlay != null) { wm.removeView(overlay); overlay = null; }
    }
    @Override public void close() { closed = true; cancel(); main.removeCallbacksAndMessages(null); context.unregisterReceiver(receiver); }
    private final class SelectionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SelectionView(Context c) { super(c); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int[] location = new int[2]; getLocationOnScreen(location);
            canvas.save(); canvas.translate(-location[0], -location[1]);
            if (selected != null) {
                paint.setColor(0x6639c99a); canvas.drawRoundRect(new RectF(selected.bounds), 3*density, 3*density, paint);
            }
            paint.setColor(selected == null ? 0xff168af2 : 0xff00a777);
            canvas.drawCircle(aimX, aimY, 3*density, paint);
            String tip = waiting ? "识别中 · 继续按住并拖动" : selected == null ? "对准准星上方文字 · 松手取词" : "已选中文字 · 松手查词";
            paint.setTextSize(12*density);
            float width = paint.measureText(tip) + 24*density;
            float left = Math.max(8*density, Math.min(aimX-width/2, getWidth()-width-8*density));
            float top = Math.max(32*density, aimY-54*density);
            paint.setColor(waiting ? 0xe63c4654 : selected == null ? 0xe6168af2 : 0xe6008a68);
            canvas.drawRoundRect(left, top, left+width, top+30*density, 8*density, 8*density, paint);
            paint.setColor(Color.WHITE); canvas.drawText(tip, left+12*density, top+20*density, paint);
            canvas.restore();
        }
    }
}
