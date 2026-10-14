package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only interaction, bitmap ownership and optional real-model font probes. */
final class OcrValidation {
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation, boolean fonts) throws Exception {
        OcrValidation test = new OcrValidation();
        test.images(); test.gestures(instrumentation);
        String measured = fonts ? test.fonts(instrumentation) : "";
        return "OCR: " + test.checks + " assertions passed\n" + OcrLanguageValidation.run(instrumentation) + measured;
    }
    private void images() {
        Bitmap screen = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888);
        screen.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(screen); Paint ink = new Paint(); ink.setColor(Color.WHITE);
        canvas.drawRect(103, 201, 117, 211, ink);
        Rect target = new Rect(100, 200, 120, 212);
        try (OcrImagePreparation normal = OcrImagePreparation.prepare(screen, target, false);
             OcrImagePreparation enhanced = OcrImagePreparation.prepare(screen, target, true)) {
            check(normal.image.getHeight() > normal.crop.height(), "Small text is enlarged before recognition");
            check(normal.image.getWidth()*normal.image.getHeight() <= OcrImagePreparation.MAX_PIXELS, "Prepared crop obeys the pixel/memory bound");
            check(Color.red(enhanced.image.getPixel(0, 0)) > 240, "Dark background is normalized to light background");
            check(Color.red(enhanced.image.getPixel(enhanced.image.getWidth()/2, enhanced.image.getHeight()/2)) < 20, "Light text becomes dark without thresholding glyph edges");
            Rect mapped = normal.toScreen(new Rect(0, 0, normal.image.getWidth(), normal.image.getHeight()));
            check(mapped.equals(normal.crop), "Recognition coordinates map exactly back to original screen pixels");
        }
        try (OcrImagePreparation clipped = OcrImagePreparation.prepare(screen, new Rect(-50, -30, 1024, 510), true)) {
            check(clipped.crop.left == 0 && clipped.crop.top == 0 && clipped.crop.right == 1024, "Edge crops are clamped to the source image");
            check(clipped.image.getWidth()*clipped.image.getHeight() <= OcrImagePreparation.MAX_PIXELS, "Large crop stays bounded");
        }
        check(!screen.isRecycled(), "Preparing and closing variants never recycle the source capture");
        OcrPreviewFrame frame = new OcrPreviewFrame(42, screen);
        check(frame.borrow(41) == null, "Old sessions cannot borrow the current screenshot");
        check(frame.borrow(42) == screen, "Current recognition borrows the exact captured image");
        frame.close(); check(!screen.isRecycled(), "Cancellation retains a bitmap still used by recognition");
        frame.release(); check(screen.isRecycled(), "Last recognition release disposes a canceled screenshot");
        check(frame.borrow(42) == null, "Disposed snapshots cannot be refined again");
        check("向上滑动".equals(OcrPickupController.swipeDirection(1, -100, 32)), "Up swipe");
        check("向下滑动".equals(OcrPickupController.swipeDirection(1, 100, 32)), "Down swipe");
        check("向左滑动".equals(OcrPickupController.swipeDirection(-100, 1, 32)), "Left swipe");
        check("向右滑动".equals(OcrPickupController.swipeDirection(100, 1, 32)), "Right swipe");
        check(OcrPickupController.swipeDirection(10, 10, 32) == null, "Small finger jitter does not emit directional Toast");
    }
    private void gestures(Instrumentation instrumentation) throws Exception {
        Context app = instrumentation.getTargetContext();
        AtomicReference<Intent> request = new AtomicReference<>();
        int[] activities = {0};
        Context capture = new ContextWrapper(app) {
            @Override public ComponentName startService(Intent intent) {
                if (!MainActivity.ScreenCaptureService.ACT_OCR_CANCEL.equals(intent.getAction())) request.set(new Intent(intent));
                return new ComponentName(getPackageName(), MainActivity.ScreenCaptureService.class.getName());
            }
            @Override public void startActivity(Intent intent) { activities[0]++; }
        };
        final Rect position = new Rect(400, 700, 444, 744);
        final Rect rendered = new Rect(position);
        int[] moves = {0}, saves = {0}, taps = {0}; AtomicReference<String> query = new AtomicReference<>();
        OcrPickupController.Host host = new OcrPickupController.Host() {
            public Rect position() { return new Rect(rendered); }
            public Rect move(int x, int y) { position.offsetTo(x, y); moves[0]++; return new Rect(position); }
            public void save() { saves[0]++; }
            public void tap() { taps[0]++; }
            public void lookup(String text) { query.set(text); }
        };
        AtomicReference<OcrPickupController> owner = new AtomicReference<>(); AtomicReference<View.OnTouchListener> listener = new AtomicReference<>();
        AtomicReference<View> view = new AtomicReference<>();
        boolean wasReady = MainActivity.ScreenCaptureService.READY;
        MainActivity.ScreenCaptureService.READY = false;
        try {
            instrumentation.runOnMainSync(() -> { owner.set(new OcrPickupController(capture, host)); listener.set(owner.get().listener()); view.set(new View(app)); });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            Thread.sleep(760);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            instrumentation.runOnMainSync(() -> {
                check(moves[0] == 1 && saves[0] == 1 && position.left == 500, "Ordinary drag moves and saves before screen sharing is authorized");
                check(request.get() == null && activities[0] == 0 && taps[0] == 0, "Unauthorised drag never starts OCR, authorization or search");
                position.set(rendered); moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_CANCEL, 520, 720);
            instrumentation.runOnMainSync(() -> {
                check(position.equals(rendered) && saves[0] == 1, "Cancel restores an ordinary drag without saving its temporary position");
                check(request.get() == null && activities[0] == 0, "Canceling an unauthorised drag never requests sharing");
                moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 420, 720);
            instrumentation.runOnMainSync(() -> check(taps[0] == 1 && activities[0] == 0, "Short tap still opens search without requesting sharing"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            Thread.sleep(600);
            instrumentation.runOnMainSync(() -> check(request.get() == null && activities[0] == 0, "600ms hold does not arm OCR or request sharing"));
            Thread.sleep(170);
            instrumentation.runOnMainSync(() -> check(request.get() == null && activities[0] == 0, "Stationary 700ms hold enters direction mode without authorization"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            instrumentation.runOnMainSync(() -> {
                check(moves[0] == 0 && saves[0] == 1 && position.left == 400, "Held direction swipe keeps the bubble position unchanged");
                check(request.get() == null && activities[0] == 0 && taps[0] == 1, "Held swipe does not request OCR, authorization or search");
                MainActivity.ScreenCaptureService.READY = true;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            Thread.sleep(760);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 320, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 320, 720);
            instrumentation.runOnMainSync(() -> check(request.get() == null && moves[0] == 0 && saves[0] == 1, "Authorized held swipe still only uses direction mode"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 421, 721);
            instrumentation.runOnMainSync(() -> check(request.get() == null && moves[0] == 0, "Finger jitter cannot move the bubble or start OCR"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            instrumentation.runOnMainSync(() -> check(request.get() != null && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction()), "Authorized ordinary drag immediately starts OCR before long press"));
            long session = request.get().getLongExtra("session", -1);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            instrumentation.runOnMainSync(() -> check(moves[0] == 1 && saves[0] == 2 && position.left == 500, "OCR drag saves the moved bubble once on release"));
            float y = 700-12*app.getResources().getDisplayMetrics().density;
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("words",new String[]{"日本語"}).putExtra("boxes",new int[]{500,(int)y-10,544,(int)y+10}));
            for (int n = 0; n < 30 && !MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()); n++) Thread.sleep(50);
            check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()), "Release requests localized refinement on the captured frame");
            check("日本語".equals(request.get().getStringExtra("seed")) && request.get().getFloatExtra("aim_x",0) == 522,
                    "Aim uses the requested move immediately even before WindowManager renders the new frame");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","日本語"));
            for (int n = 0; n < 30 && query.get() == null; n++) Thread.sleep(50);
            check("日本語".equals(query.get()), "Refinement hands complete recognized text to direct lookup");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","stale"));
            Thread.sleep(100); check("日本語".equals(query.get()), "Canceled session rejects old OCR updates");
        } finally {
            instrumentation.runOnMainSync(() -> { if (owner.get() != null) owner.get().close(); });
            MainActivity.ScreenCaptureService.READY = wasReady;
        }
    }
    private static void touch(Instrumentation test, View.OnTouchListener listener, View view, int action, float x, float y) {
        test.runOnMainSync(() -> {
            long time = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
            try { listener.onTouch(view, event); } finally { event.recycle(); }
        });
    }
    private String fonts(Instrumentation instrumentation) throws Exception {
        AtomicReference<MultilingualOcrRecognizer> client = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { client.set(new MultilingualOcrRecognizer()); client.get().prepareModels(instrumentation.getTargetContext()); });
        StringBuilder report = new StringBuilder();
        int original = 0, refined = 0, total = 0;
        try {
            String[] words = {"avocado", "日本語", "中文词典"};
            for (String word : words) for (String family : new String[]{"sans-serif", "serif", "monospace"}) for (int size : new int[]{8, 10, 14}) {
                Bitmap image = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
                Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK); ink.setTextSize(size); ink.setTypeface(Typeface.create(family, Typeface.NORMAL));
                new Canvas(image).drawText(word,20,40,ink);
                Rect target = new Rect(20, (int)Math.floor(40+ink.ascent()), 20+(int)Math.ceil(ink.measureText(word)), (int)Math.ceil(40+ink.descent()));
                AtomicReference<List<MultilingualOcrRecognizer.Region>> regions = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
                CountDownLatch detected = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> client.get().recognizeRegions(image, new MultilingualOcrRecognizer.RegionsCallback() {
                    public void onSuccess(List<MultilingualOcrRecognizer.Region> found) { regions.set(found); detected.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); detected.countDown(); }
                }));
                if (!detected.await(20, TimeUnit.SECONDS)) throw new AssertionError("Font probe timeout");
                MultilingualOcrRecognizer.Region seed = regions.get() == null ? null : MultilingualOcrRecognizer.regionAt(regions.get(), target.exactCenterX(), target.exactCenterY(), 8);
                String before = seed == null ? "" : seed.text;
                AtomicReference<String> after = new AtomicReference<>(""); CountDownLatch enhanced = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> client.get().recognizeSelection(image,target,seed,target.exactCenterX(),target.exactCenterY(),8,new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String text) { after.set(text); enhanced.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); enhanced.countDown(); }
                }));
                if (!enhanced.await(20,TimeUnit.SECONDS)) throw new AssertionError("Refined probe timeout");
                if (word.equals(before)) original++; if (word.equals(after.get())) refined++; total++;
                report.append(word).append(' ').append(family).append(' ').append(size).append("px: ").append(before).append(" -> ").append(after.get()).append(error.get() == null ? "" : " ["+error.get()+"]").append('\n');
                image.recycle();
            }
            report.insert(0, "Font exact matches: original="+original+", refined="+refined+", total="+total+"\n");
        } finally { instrumentation.runOnMainSync(() -> client.get().close()); }
        return report.toString();
    }
}
