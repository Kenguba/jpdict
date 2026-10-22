package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Samples composed screen pixels while the real parent window is moving. */
final class OcrBubbleWindowValidation {
    static String run(Instrumentation test) throws Exception {
        Context app = test.getTargetContext();
        WindowManager windows = (WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        Context context = new ContextWrapper(app) {
            @Override public ComponentName startService(Intent intent) {
                return new ComponentName(getPackageName(), MainActivity.ScreenCaptureService.class.getName());
            }
            @Override public void startActivity(Intent intent) { throw new AssertionError("Unexpected authorization"); }
        };
        float density = app.getResources().getDisplayMetrics().density;
        int size = Math.round(44*density);
        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(screen);
        View paper = new View(app); paper.setBackgroundColor(Color.WHITE);
        View[] bubbles = new View[2];
        WindowManager.LayoutParams[] layouts = {params(size,size),params(size,size)};
        for (int i=0;i<2;i++) bubbles[i] = new View(app) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(0xff168af2);
                canvas.drawCircle(getWidth()/2f,getHeight()/2f,getWidth()/2f,paint);
            }
        };
        int[] active = {0};
        Rect position = new Rect(400,600,400+size,600+size);
        OcrPickupController.Host host = new OcrPickupController.Host() {
            public View anchor() { return bubbles[active[0]]; }
            public Rect position() { return new Rect(position); }
            public Rect move(int x,int y) {
                position.offsetTo(x,y);
                WindowManager.LayoutParams lp=layouts[active[0]]; lp.x=x; lp.y=y;
                windows.updateViewLayout(anchor(),lp);
                return new Rect(position);
            }
            public void save() {}
            public void tap() {}
            public void lookup(String text) { throw new AssertionError("No OCR result was delivered"); }
        };
        AtomicReference<OcrPickupController> owner = new AtomicReference<>();
        AtomicReference<View.OnTouchListener> listener = new AtomicReference<>();
        Set<View> markers = Collections.newSetFromMap(new IdentityHashMap<>());
        Handler handler = new Handler(Looper.getMainLooper());
        boolean ready = MainActivity.ScreenCaptureService.READY;
        float[] grip = new float[2];
        int[] frame = {0};
        Runnable move = new Runnable() {
            public void run() {
                // Reverse direction without stopping; the old full-screen pointer
                // can match a stationary bubble and still lag these moving frames.
                int step = frame[0]++ % 100;
                float dx = (step<50 ? step : 100-step)*8-200;
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_MOVE,grip[0]+dx,grip[1]);
                handler.postDelayed(this,16);
            }
        };
        List<String> offsets = new ArrayList<>();
        try {
            main(test, () -> {
                MainActivity.ScreenCaptureService.READY=true;
                windows.addView(paper,params(-1,-1));
                for (int i=0;i<2;i++) {
                    layouts[i].x=position.left; layouts[i].y=position.top;
                    windows.addView(bubbles[i],layouts[i]);
                }
                bubbles[1].setVisibility(View.INVISIBLE);
                owner.set(new OcrPickupController(context,host)); listener.set(owner.get().listener());
            });
            for (int phase=0;phase<3;phase++) {
                final int next=phase%2;
                final int top=phase==1 ? screen.heightPixels-size-Math.round(55*density) : 600;
                main(test, () -> {
                    owner.get().cancel(); active[0]=next;
                    bubbles[1-next].setVisibility(View.INVISIBLE); bubbles[next].setVisibility(View.VISIBLE);
                    host.move(400,top); listener.set(owner.get().listener());
                    grip[0]=position.left+1; grip[1]=position.exactCenterY();
                    touch(listener.get(),host.anchor(),MotionEvent.ACTION_DOWN,grip[0],grip[1]);
                    frame[0]=0; move.run();
                    View marker=(View)field(field(owner.get(),"overlay"),"marker"); markers.add(marker);
                    WindowManager.LayoutParams lp=(WindowManager.LayoutParams)marker.getLayoutParams();
                    require(lp.token==host.anchor().getWindowToken(),"Pointer must bind to the currently visible original/proxy");
                    require((lp.flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
                            ==(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
                            "The pointer cannot consume touches or keyboard focus");
                });
                Thread.sleep(1500);
                main(test, () -> {
                    View overlay=(View)field(owner.get(),"overlay"), marker=(View)field(overlay,"marker");
                    require(overlay.getAlpha()>.99f && marker.getAlpha()>.99f,
                            "Both windows must finish entering: overlay="+overlay.getAlpha()+", marker="+marker.getAlpha());
                });
                for (int sample=0;sample<8;sample++) {
                    Bitmap shot=test.getUiAutomation().takeScreenshot();
                    require(shot!=null,"Composed screenshot is required");
                    try {
                        float[] offset=measure(shot);
                        float expected=(phase==1?1:-1)*48*density;
                        require(Math.abs(offset[0])<=1 && Math.abs(offset[1]-expected)<=1,
                                "Moving pointer offset="+Arrays.toString(offset)+", expected [0, "+expected+"]");
                        offsets.add(Arrays.toString(offset));
                    } finally { shot.recycle(); }
                    Thread.sleep(25);
                }
                main(test, () -> handler.removeCallbacks(move));
            }
            main(test, () -> {
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_CANCEL,grip[0],grip[1]);
                owner.get().cancel();
            });
            Thread.sleep(220);
            main(test, () -> {
                require(field(owner.get(),"overlay")==null,"Cancel releases both windows after the original fade deadline");
                for (View marker:markers) require(!marker.isAttachedToWindow(),"No pointer window survives cancellation");
                // Remove a proxy while its pointer is fading, then immediately
                // start on the original. Its dead token must never be reused.
                active[0]=1; bubbles[0].setVisibility(View.INVISIBLE); bubbles[1].setVisibility(View.VISIBLE);
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_DOWN,grip[0],grip[1]);
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_MOVE,grip[0]+80,grip[1]);
                markers.add((View)field(field(owner.get(),"overlay"),"marker"));
                owner.get().cancel(); windows.removeViewImmediate(bubbles[1]);
                active[0]=0; bubbles[0].setVisibility(View.VISIBLE);
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_DOWN,grip[0],grip[1]);
                touch(listener.get(),host.anchor(),MotionEvent.ACTION_MOVE,grip[0]+90,grip[1]);
                View marker=(View)field(field(owner.get(),"overlay"),"marker"); markers.add(marker);
                require(((WindowManager.LayoutParams)marker.getLayoutParams()).token==bubbles[0].getWindowToken(),
                        "A removed proxy's marker rebinds to the original");
                owner.get().close();
            });
            Thread.sleep(100);
            main(test, () -> {
                for (View marker:markers) require(!marker.isAttachedToWindow(),"Closing releases every rebound pointer");
            });
        } finally {
            main(test, () -> {
                handler.removeCallbacks(move);
                if (owner.get()!=null && !(Boolean)field(owner.get(),"closed")) owner.get().close();
                for (View bubble:bubbles) if (bubble.isAttachedToWindow()) windows.removeViewImmediate(bubble);
                if (paper.isAttachedToWindow()) windows.removeViewImmediate(paper);
                MainActivity.ScreenCaptureService.READY=ready;
            });
        }
        return "Moving WindowManager pixels: original/proxy/original 24 samples dx,dy="+offsets
                +"; fade restart, proxy removal, cancel and close passed\n";
    }
    static WindowManager.LayoutParams params(int width,int height) {
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(width,height,
                Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;
        if (Build.VERSION.SDK_INT>=30) { lp.setFitInsetsTypes(0); lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; }
        return lp;
    }
    private static float[] measure(Bitmap image) {
        int width=image.getWidth(),height=image.getHeight();
        int[] pixels=new int[width*height]; image.getPixels(pixels,0,width,0,0,width,height);
        Rect ball=new Rect(),marker=new Rect();
        for (int i=0;i<pixels.length;i++) if (Color.blue(pixels[i])-Color.red(pixels[i])>150)
            ball.union(i%width,i/width,i%width+1,i/width+1);
        require(ball.width()>40 && ball.height()>40,"A visible bubble is required for pixel measurement");
        for (int i=0;i<pixels.length;i++) {
            int x=i%width,y=i/width,c=pixels[i];
            if (!ball.contains(x,y) && Color.blue(c)>Color.green(c)
                    && Color.blue(c)-Color.red(c)>25 && Color.green(c)-Color.red(c)>10)
                marker.union(x,y,x+1,y+1);
        }
        require(marker.width()>3 && marker.width()<40 && marker.height()>3 && marker.height()<40,
                "Exactly one visible circle is required: "+marker);
        return new float[]{marker.exactCenterX()-ball.exactCenterX(),marker.exactCenterY()-ball.exactCenterY()};
    }
    private static Object field(Object target,String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void touch(View.OnTouchListener listener,View bubble,int action,float x,float y) {
        long now=SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
        try { listener.onTouch(bubble,event); } finally { event.recycle(); }
    }
    private static void main(Instrumentation test,Runnable action) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get()!=null) throw new AssertionError(error.get());
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
