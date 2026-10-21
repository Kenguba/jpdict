package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in full display capture + bundled ML Kit + controller-to-lookup test. */
final class OcrLivePipelineValidation {
    static String run(Instrumentation test) throws Exception {
        Context app = test.getTargetContext();
        WindowManager windows = (WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        List<String> queries = new ArrayList<>();
        List<String> previewWords = new ArrayList<>();
        List<Long> captures = new ArrayList<>(), previews = new ArrayList<>(), refined = new ArrayList<>(), releaseCallbacks = new ArrayList<>();
        long[] gestureAt = {0};
        BroadcastReceiver preview = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (MainActivity.ScreenCaptureService.ACTION_PREVIEW_FRAME.equals(intent.getAction())) captures.add(SystemClock.uptimeMillis()-gestureAt[0]);
                String[] words = intent.getStringArrayExtra("words");
                if (words!=null) { previewWords.add(Arrays.toString(words)); previews.add(SystemClock.uptimeMillis()-gestureAt[0]); }
                if (intent.getBooleanExtra("refined",false)) refined.add(SystemClock.uptimeMillis()-gestureAt[0]);
            }
        };
        IntentFilter previewFilter = new IntentFilter(MainActivity.ScreenCaptureService.ACTION_PREVIEW);
        previewFilter.addAction(MainActivity.ScreenCaptureService.ACTION_PREVIEW_FRAME);
        ContextCompat.registerReceiver(app,preview,previewFilter,ContextCompat.RECEIVER_NOT_EXPORTED);
        List<Long> elapsed = new ArrayList<>();
        OcrLanguageSettings.Language previous = OcrLanguageSettings.get(app).language();
        AtomicReference<OcrPickupController> owner = new AtomicReference<>();
        AtomicReference<View.OnTouchListener> listener = new AtomicReference<>();
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setTextSize(48); ink.setColor(Color.BLACK); ink.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        int x = Math.round(100+ink.measureText("the ")+ink.measureText("first")/2);
        int[] hits = new int[5];
        for (int i=0;i<hits.length;i++) hits[i]=Math.round(100+ink.measureText("the ")+ink.measureText("first",0,i)+ink.measureText("first",i,i+1)/2);
        int y = Math.round(500+(ink.ascent()+ink.descent())/2);
        int gap = Math.round(48*app.getResources().getDisplayMetrics().density)-44;
        Rect position = new Rect(x-144, y+gap, x-56, y+gap+88);
        View paper = new View(app) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
                canvas.drawText("the first place", 100, 500, ink);
                canvas.drawText("another second line", 100, 580, ink);
            }
        };
        View bubble = new View(app); bubble.setBackgroundColor(0xff168af2);
        WindowManager.LayoutParams paperParams = params(-1, -1), bubbleParams = params(88,88);
        OcrPickupController.Host host = new OcrPickupController.Host() {
            public Rect position() { return new Rect(position); }
            public Rect move(int left, int top) {
                position.offsetTo(left,top); bubbleParams.x=left; bubbleParams.y=top;
                windows.updateViewLayout(bubble,bubbleParams); return new Rect(position);
            }
            public void save() {}
            public void tap() {}
            public void lookup(String text) { queries.add(text); }
        };
        try {
            main(test, () -> app.startActivity(new Intent(app,MainActivity.OcrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            waitFor(test, () -> MainActivity.ScreenCaptureService.READY, 60_000, "Approve the QA screen-capture dialog to run the live test");
            main(test, () -> {
                OcrLanguageSettings.get(app).setLanguage(OcrLanguageSettings.Language.ENGLISH);
                windows.addView(paper,paperParams);
                bubbleParams.x=position.left; bubbleParams.y=position.top; windows.addView(bubble,bubbleParams);
                owner.set(new OcrPickupController(app,host)); listener.set(owner.get().listener());
            });
            Thread.sleep(300);
            for (int round=0; round<20; round++) {
                final int expected = round+1;
                final int offset = round%3-1;
                final int hit = hits[round%hits.length];
                main(test, () -> host.move(hit-144, y+gap+offset*2));
                long start = SystemClock.uptimeMillis();
                gestureAt[0]=start;
                touch(test,listener.get(),bubble,MotionEvent.ACTION_DOWN,hit-100,y+gap+44);
                touch(test,listener.get(),bubble,MotionEvent.ACTION_MOVE,hit,y+gap+44);
                // Release while capture/model is pending; the controller must replay the pointer.
                Thread.sleep(180);
                if (round==0) {
                    Bitmap screenshot=test.getUiAutomation().takeScreenshot();
                    if (screenshot!=null) {
                        try (FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),"ocr-live-pointer.png"))) {
                            screenshot.compress(Bitmap.CompressFormat.PNG,100,out);
                        } finally { screenshot.recycle(); }
                    }
                }
                touch(test,listener.get(),bubble,MotionEvent.ACTION_MOVE,hit+2,y+gap+44);
                touch(test,listener.get(),bubble,MotionEvent.ACTION_MOVE,hit,y+gap+44);
                main(test, () -> {
                    long releasedAt=SystemClock.uptimeMillis();
                    long now=SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,hit,y+gap+44,0);
                    try { listener.get().onTouch(bubble,event); } finally { event.recycle(); }
                    releaseCallbacks.add(SystemClock.uptimeMillis()-releasedAt);
                });
                waitFor(test, () -> queries.size()==expected, 24_000, "Live OCR did not return a query for round " + round);
                if (!"first".equals(queries.get(round))) throw new AssertionError("Live OCR expected first, got " + queries.get(round)
                        + "; preview=" + previewWords);
                elapsed.add(SystemClock.uptimeMillis()-start);
            }
        } finally {
            main(test, () -> {
                if (owner.get()!=null) owner.get().close();
                if (paper.isAttachedToWindow()) windows.removeViewImmediate(paper);
                if (bubble.isAttachedToWindow()) windows.removeViewImmediate(bubble);
                OcrLanguageSettings.get(app).setLanguage(previous);
                app.unregisterReceiver(preview);
                app.startService(new Intent(app,MainActivity.ScreenCaptureService.class).setAction(MainActivity.ScreenCaptureService.ACT_STOP));
            });
        }
        return "Live MediaProjection + bundled English ML Kit: 20/20 first across five letters; gesture-to-lookup ms=" + elapsed
                + "; capture/preview/refined callbacks after DOWN ms="+captures+" / "+previews+" / "+refined
                + "; UP callback ms="+releaseCallbacks+"\n";
    }
    private static WindowManager.LayoutParams params(int width,int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        if (Build.VERSION.SDK_INT>=30) { params.setFitInsetsTypes(0); params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; }
        return params;
    }
    private static void touch(Instrumentation test,View.OnTouchListener listener,View view,int action,float x,float y) {
        main(test, () -> {
            long now=SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
            try { listener.onTouch(view,event); } finally { event.recycle(); }
        });
    }
    private static void main(Instrumentation test,Runnable action) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get()!=null) throw new AssertionError(error.get());
    }
    private static void waitFor(Instrumentation test,java.util.function.BooleanSupplier condition,long timeout,String failure) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout; AtomicReference<Boolean> ready=new AtomicReference<>(false);
        do { main(test, () -> ready.set(condition.getAsBoolean())); if (ready.get()) return; Thread.sleep(20); }
        while (SystemClock.uptimeMillis()<end);
        throw new AssertionError(failure);
    }
}
