package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real WindowManager and animators in an isolated QA package. */
final class OcrInteractionRegressionValidation {
    private int checks;
    private void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static String run(Instrumentation test) throws Exception {
        OcrInteractionRegressionValidation validation = new OcrInteractionRegressionValidation();
        return validation.interactions(test);
    }
    private String interactions(Instrumentation test) throws Exception {
        Context app = test.getTargetContext();
        List<Intent> requests = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        List<Long> firstDraw = new ArrayList<>(), frameGaps = new ArrayList<>();
        long[] pressedAt = {0};
        Set<View> windows = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] mounts = {0}, removals = {0};
        WindowManager realWindows = (WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        WindowManager countedWindows = (WindowManager)Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
                    if ("addView".equals(method.getName())) {
                        View added = (View)args[0];
                        check(windows.add(added), "A floating window cannot be mounted twice"); mounts[0]++;
                        if (added instanceof OcrJoystickView) added.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
                            boolean drawn;
                            public void onDraw() { if (!drawn) { drawn=true; firstDraw.add(SystemClock.uptimeMillis()-pressedAt[0]); } }
                        });
                    } else if ("removeView".equals(method.getName())) {
                        check(windows.remove((View)args[0]), "Only an owned window is removed"); removals[0]++;
                    }
                    try { return method.invoke(realWindows, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        Context context = new ContextWrapper(app) {
            @Override public Object getSystemService(String name) {
                return Context.WINDOW_SERVICE.equals(name) ? countedWindows : super.getSystemService(name);
            }
            @Override public ComponentName startService(Intent request) {
                requests.add(new Intent(request));
                return new ComponentName(getPackageName(), MainActivity.ScreenCaptureService.class.getName());
            }
            @Override public void startActivity(Intent request) { throw new AssertionError("Unexpected authorization"); }
        };
        Rect position = new Rect(400, 700, 488, 788);
        int[] moves = {0};
        OcrPickupController.Host host = new OcrPickupController.Host() {
            public Rect position() { return new Rect(position); }
            public Rect move(int x, int y) { moves[0]++; position.offsetTo(x, y); return new Rect(position); }
            public void save() {}
            public void tap() {}
            public void lookup(String text) { queries.add(text); }
        };
        AtomicReference<OcrPickupController> owner = new AtomicReference<>();
        AtomicReference<View.OnTouchListener> listener = new AtomicReference<>();
        View view = new View(app);
        boolean ready = MainActivity.ScreenCaptureService.READY;
        OcrLanguageSettings.Language language = OcrLanguageSettings.get(app).language();
        long[] lastFrame = {0};
        boolean[] measuring = {true};
        Choreographer.FrameCallback frames = new Choreographer.FrameCallback() {
            public void doFrame(long time) {
                if (!measuring[0]) return;
                if (lastFrame[0] != 0) frameGaps.add((time-lastFrame[0])/1_000_000);
                lastFrame[0] = time;
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        try {
            main(test, () -> {
                MainActivity.ScreenCaptureService.READY = true;
                OcrLanguageSettings.get(app).setLanguage(OcrLanguageSettings.Language.ENGLISH);
                owner.set(new OcrPickupController(context, host)); listener.set(owner.get().listener());
                Choreographer.getInstance().postFrameCallback(frames);
            });
            // Repeated hide must preserve the original 140ms deadline.
            touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
            touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 540, 840);
            main(test, () -> {
                float aimY = (Float)field(owner.get(), "aimY");
                touch(test, listener.get(), view, MotionEvent.ACTION_CANCEL, 540, 840);
                check((Float)field(owner.get(), "aimY") == aimY,
                        "Canceling an ordinary drag freezes its pointer without moving the fading frame");
            });
            Thread.sleep(90);
            main(test, owner.get()::cancel);
            Thread.sleep(90);
            main(test, () -> check(field(owner.get(), "overlay") == null,
                    "Repeated hide cannot restart the pointer exit animation"));

            // A new gesture takes over a fading pointer, even across listeners.
            for (int i=0; i<20; i++) {
                final int index = i;
                touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
                touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 540+i, 740);
                main(test, () -> {
                    View pointer = (View)field(owner.get(), "overlay");
                    check(pointer != null && pointer.isAttachedToWindow(), "Pointer stays attached on restart " + index);
                    check(field(pointer, "frozen") == null, "New gesture clears the frozen pointer " + index);
                    owner.get().cancel();
                });
            }
            Thread.sleep(200);
            main(test, () -> check(field(owner.get(), "overlay") == null, "Repeated pointer gestures release their window"));

            // Real 700ms holds, drawing, slow/fast movement and all directions.
            float density = app.getResources().getDisplayMetrics().density;
            for (int round=0; round<20; round++) {
                position.set(400, 700, 488, 788);
                pressedAt[0] = SystemClock.uptimeMillis();
                touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
                Thread.sleep(650);
                main(test, () -> check(field(owner.get(), "joystick") == null, "The original long-press threshold is retained"));
                waitFor(test, () -> field(owner.get(), "joystick") != null, "Long press never displayed the ring");
                Rect anchor = new Rect(position);
                float targetX = (Float)field(owner.get(), "aimX"), targetY = (Float)field(owner.get(), "aimY");
                for (int step=0; step<4; step++) {
                    float dx = step==1 ? 90*density : step==3 ? -90*density : 0;
                    float dy = step==0 ? -90*density : step==2 ? 90*density : 0;
                    touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 440+dx, 740+dy);
                    main(test, () -> check(position.equals(anchor), "Choosing a direction keeps the bubble and ring concentric"));
                    int before = moves[0];
                    for (int repeat=0; repeat<8; repeat++) touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 440+dx, 740+dy);
                    main(test, () -> check(moves[0]==before, "Stationary knob events do not update the window"));
                    if (round%2==0) Thread.sleep(16);
                }
                // Re-enter the deadzone, then commit a mode. The next DOWN
                // takes over before the outgoing ring or capture delay ends.
                touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 440, 740);
                main(test, () -> check((Integer)field(listener.get(), "direction")==-1, "The centre retains the original deadzone"));
                float dx = round%3==1 ? 90*density : 0;
                float dy = round%3==0 ? -90*density : round%3==2 ? 90*density : 0;
                long before = requests.stream().filter(r -> MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(r.getAction())).count();
                main(test, () -> {
                    touch(test, listener.get(), view, MotionEvent.ACTION_UP, 440+dx, 740+dy);
                    check((Boolean)field(owner.get(), "armed"), "Every mode release arms one pickup");
                    check((Float)field(owner.get(), "aimX") == targetX && (Float)field(owner.get(), "aimY") == targetY,
                            "Choosing a mode preserves the original target, including an off-centre touch");
                    checkRingCenter(owner.get(), position, "Released");
                    touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
                });
                waitFor(test, () -> field(owner.get(), "dismissingJoystick")==null,
                        "Old ring never exits during the next hold");
                main(test, () -> {
                    check(field(owner.get(), "joystick")==null, "Old ring exits before the new hold activates");
                    check(requests.stream().filter(r -> MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(r.getAction())).count()==before,
                            "An old delayed screenshot cannot start after the replacement DOWN");
                });
                touch(test, listener.get(), view, MotionEvent.ACTION_CANCEL, 440, 740);
            }
            pressedAt[0]=SystemClock.uptimeMillis();
            touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
            waitFor(test, () -> field(owner.get(), "joystick")!=null, "Centre cancel hold did not open");
            int beforeCancel = requests.size();
            touch(test, listener.get(), view, MotionEvent.ACTION_UP, 440, 740);
            main(test, () -> check(requests.size()==beforeCancel && !(Boolean)field(owner.get(), "armed"),
                    "Releasing in the centre cancels without starting a task"));
            // Cancellation preserves the fixed bubble and ring at every edge and size.
            android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
            realWindows.getDefaultDisplay().getRealMetrics(screen);
            for (int sample=0; sample<12; sample++) {
                final int next = sample%4, size = Math.round(new int[]{24,44,100}[sample/4]*density);
                main(test, () -> {
                    int left = next%2==0 ? 0 : screen.widthPixels-size;
                    int top = next<2 ? 0 : screen.heightPixels-size;
                    position.set(left, top, left+size, top+size);
                    float x = position.exactCenterX(), y = position.exactCenterY();
                    touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, x, y);
                    ((Runnable)field(listener.get(), "hold")).run();
                    float dx = next==1 ? 90*density : next==3 ? -90*density : 0;
                    float dy = next==0 ? -90*density : next==2 ? 90*density : 0;
                    touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, x+dx, y+dy);
                    touch(test, listener.get(), view, MotionEvent.ACTION_CANCEL, x+dx, y+dy);
                    checkRingCenter(owner.get(), position, "Canceled");
                    check(!(Boolean)field(owner.get(), "armed"), "Canceled directions never start OCR");
                });
            }
            main(test, () -> position.set(400, 700, 488, 788));
            // Duplicate preview messages and stale sessions cannot enqueue refinement twice.
            touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
            touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 540, 740);
            waitFor(test, () -> (Boolean)field(owner.get(), "armed"), "Dwell did not arm OCR");
            long session = (Long)field(owner.get(), "session");
            touch(test, listener.get(), view, MotionEvent.ACTION_UP, 540, 740);
            float aimX = (Float)field(owner.get(), "aimX"), aimY = (Float)field(owner.get(), "aimY");
            Intent preview = new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session", session).putExtra("words", new String[]{"first"})
                    .putExtra("boxes", new int[]{(int)aimX-20,(int)aimY-12,(int)aimX+20,(int)aimY+12});
            app.sendBroadcast(preview); app.sendBroadcast(preview);
            waitFor(test, () -> requests.stream().anyMatch(r -> MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(r.getAction())), "No refinement");
            Thread.sleep(80);
            main(test, () -> check(requests.stream().filter(r -> MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(r.getAction())).count()==1,
                    "Duplicate preview results cannot start duplicate refinement"));
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session", session).putExtra("refined", true).putExtra("error", "Empty fine candidates"));
            waitFor(test, () -> !queries.isEmpty(), "Valid preview was lost after refinement failure");
            main(test, () -> check(queries.size()==1 && "first".equals(queries.get(0)), "Fine OCR failure preserves a valid preview word"));
            touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
            touch(test, listener.get(), view, MotionEvent.ACTION_MOVE, 540, 740);
            waitFor(test, () -> (Boolean)field(owner.get(), "armed"), "Replacement dwell did not arm OCR");
            main(test, () -> {
                float nextX=(Float)field(owner.get(), "aimX"), nextY=(Float)field(owner.get(), "aimY");
                preview.putExtra("session",(Long)field(owner.get(), "session"))
                        .putExtra("boxes",new int[]{(int)nextX-20,(int)nextY-12,(int)nextX+20,(int)nextY+12});
            });
            touch(test, listener.get(), view, MotionEvent.ACTION_UP, 540, 740);
            app.sendBroadcast(preview);
            waitFor(test, () -> (Boolean)field(owner.get(), "refining"), "Replacement never reached fine OCR");
            main(test, () -> {
                ((Runnable)field(owner.get(), "timeout")).run();
                check(queries.size()==2 && "first".equals(queries.get(1)), "The UI deadline preserves a valid preview when fine OCR stalls");
            });
            touch(test, listener.get(), view, MotionEvent.ACTION_DOWN, 440, 740);
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session", session).putExtra("refined", true).putExtra("text", "stale"));
            Thread.sleep(100);
            main(test, () -> {
                View outgoing = (View)field(owner.get(), "overlay");
                check(queries.size()==2 && (outgoing==null || ((Boolean)field(owner.get(), "overlayHiding")
                                && field(outgoing, "frozen")!=null)),
                        "Old OCR completion cannot show/hide UI or query during a new hold");
            });
        } finally {
            main(test, () -> {
                measuring[0]=false; Choreographer.getInstance().removeFrameCallback(frames);
                if (owner.get()!=null) owner.get().close();
                check(windows.isEmpty() && mounts[0]==removals[0], "All OCR windows are released exactly once");
                MainActivity.ScreenCaptureService.READY=ready;
                OcrLanguageSettings.get(app).setLanguage(language);
            });
        }
        Collections.sort(firstDraw); Collections.sort(frameGaps);
        return "OCR interaction: " + checks + " assertions; 20 mode commits + centre-cancel hold in real WindowManager; first draw ms=" + firstDraw
                + "; choreographer gap p95/max ms=" + percentile(frameGaps, .95) + "/" + percentile(frameGaps, 1) + "\n";
    }
    private static long percentile(List<Long> values, double fraction) {
        return values.isEmpty() ? -1 : values.get(Math.min(values.size()-1, (int)Math.ceil(values.size()*fraction)-1));
    }
    private void checkRingCenter(OcrPickupController owner, Rect bubble, String phase) {
        Object ring = field(owner, "dismissingJoystick");
        check(ring != null && (Float)field(ring, "x") == bubble.exactCenterX()
                        && (Float)field(ring, "y") == bubble.exactCenterY(),
                phase + " ring stays centred on the anchored bubble throughout its fade");
    }
    private static Object field(Object target, String name) {
        try { Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void touch(Instrumentation test, View.OnTouchListener listener, View view, int action, float x, float y) {
        main(test, () -> {
            long now=SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
            try { listener.onTouch(view,event); } finally { event.recycle(); }
        });
    }
    private static void main(Instrumentation test, Runnable action) {
        if (Looper.myLooper()==Looper.getMainLooper()) { action.run(); return; }
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get()!=null) throw new AssertionError(failure.get());
    }
    private static void waitFor(Instrumentation test, java.util.function.BooleanSupplier condition, String message) throws Exception {
        long until=SystemClock.uptimeMillis()+5000;
        AtomicReference<Boolean> ready=new AtomicReference<>(false);
        do { main(test, () -> ready.set(condition.getAsBoolean())); if (ready.get()) return; Thread.sleep(10); }
        while (SystemClock.uptimeMillis()<until);
        throw new AssertionError(message);
    }
}
