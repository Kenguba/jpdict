package com.yuen.jpdict;

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** One frame clock, gesture recognizer and geometry model for both drawer surfaces. */
final class DrawerMotionController {
    interface Listener {
        void onProgress(float progress);
        void onDragStart();
        void onRelease(boolean open);
    }

    /** Units are fractions of the original drawer width, and fractions/second. */
    static final class Spring {
        float position, velocity, target;
        float omega = 25, zeta = .89f;

        boolean step(float seconds) {
            float dt = Math.max(.004f, Math.min(.35f, seconds));
            int steps = Math.max(2, (int) Math.ceil(dt / .008f));
            float h = dt / steps;
            for (int i = 0; i < steps; i++) {
                velocity += (-omega * omega * (position - target) - 2 * zeta * omega * velocity) * h;
                position += velocity * h;
            }
            if (Math.abs(position - target) < .0015f && Math.abs(velocity) < .018f) {
                position = target; velocity = 0;
                return false;
            }
            return true;
        }
    }

    /** Reused output; the preview scales the same real-window geometry. */
    static final class Geometry {
        float pageX, pageScale, menuX, menuY, menuScale, shade, fraction;
        void update(DrawerStyleSettings.Mode mode, float progress, float limit, float width, float density) {
            fraction = limit <= 0 ? 0 : clamp(progress / limit, 0, 1);
            pageX = mode == DrawerStyleSettings.Mode.OVERLAY ? 0 : width * progress;
            pageScale = 1 - fraction * (mode == DrawerStyleSettings.Mode.OVERLAY ? .022f
                    : mode == DrawerStyleSettings.Mode.PUSH ? .028f : 0);
            menuX = -width * (1 - progress); menuY = 0; menuScale = 1;
            shade = mode == DrawerStyleSettings.Mode.OVERLAY ? .659f * fraction : 0;
            if (mode == DrawerStyleSettings.Mode.PUSH) {
                menuX = -12 * density * (1 - fraction);
                menuY = 10 * density * (1 - fraction);
                menuScale = 1 - .042f * (1 - fraction);
            }
        }
    }

    static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    static float rubber(float raw, float limit) {
        return rubber(raw, limit, .05f, .35f);
    }
    private static float rubber(float raw, float limit, float maxStretch, float coefficient) {
        float edge = raw < 0 ? 0 : limit;
        if (raw >= 0 && raw <= limit) return raw;
        float stretch = maxStretch * (1 - 1 / (1 + Math.abs(raw - edge) * coefficient));
        return edge + Math.copySign(stretch, raw - edge);
    }
    private static float unRubber(float shown, float limit, float stretch, float coefficient) {
        if (stretch <= 0) return clamp(shown, 0, limit);
        float edge = shown < 0 ? 0 : limit;
        if (shown >= 0 && shown <= limit) return shown;
        float distance = Math.min(stretch * .998f, Math.abs(shown - edge));
        return edge + Math.copySign((1 / (1 - distance / stretch) - 1) / coefficient, shown - edge);
    }

    static boolean animationsEnabled(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1) != 0;
    }

    private final View view;
    private final Listener listener;
    final Spring spring = new Spring();
    private final Runnable frame = this::frame;
    private final float density, slop;
    private final long[] times = new long[18];
    private final float[] fingers = new float[18], positions = new float[18];
    private float width = 1, limit = 1, downX, downY, rawStart, downTarget, downVelocity;
    private float stretch = .05f, coefficient = .35f;
    private boolean preview;
    private int pointer = -1, samples;
    private boolean eligible, dragging, running, heldAnimation, consumed;
    private long lastFrame, lastEventTime = -1, suppressClickUntil;
    private int lastAction = -1;
    private float lastEventX;
    private Runnable after;
    private float closingTime = -1, closingStart;

    DrawerMotionController(View view, Listener listener) {
        this.view = view; this.listener = listener;
        density = view.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    float progress() { return spring.position; }
    float limit() { return limit; }
    boolean isDragging() { return dragging; }
    boolean isRunning() { return running; }

    void excludeGesture() {
        if (dragging || heldAnimation) settle(downTarget, spring.velocity, null);
        eligible = false; pointer = -1;
    }

    void configure(float width, float limit) {
        this.width = Math.max(1, width);
        this.limit = clamp(limit, 0, 1);
    }

    void configurePreview(float width, float ratio) {
        preview = true; configure(width, ratio == 0 ? 0 : 1);
        stretch = ratio == 0 ? 0 : Math.min(.065f, 1 - ratio) / ratio;
        coefficient = stretch > 0 ? .35f / stretch : .35f;
        spring.omega = 26; spring.zeta = .87f;
    }

    void stop() {
        view.removeCallbacks(frame); running = false; after = null;
        closingTime = -1;
        pointer = -1; dragging = false; eligible = false; heldAnimation = false;
    }

    void snap(float progress) {
        stop(); spring.position = clamp(progress, 0, limit);
        spring.target = spring.position; spring.velocity = 0;
        listener.onProgress(spring.position);
    }

    void animate(boolean open, Runnable completion) {
        float velocity = running ? spring.velocity : open ? .8f : -.8f;
        settle(open ? limit : 0, velocity, completion);
    }

    void closeThen(Runnable next) {
        stop();
        if (!animationsEnabled(view.getContext())) { snap(0); next.run(); return; }
        closingStart = spring.position; closingTime = 0; after = next;
        running = true; lastFrame = System.nanoTime(); view.postOnAnimation(frame);
    }

    private void settle(float target, float velocity, Runnable completion) {
        stop(); spring.target = target; spring.velocity = velocity; after = completion;
        if (!animationsEnabled(view.getContext()) || limit == 0) {
            spring.position = target; spring.velocity = 0;
            listener.onProgress(spring.position);
            Runnable done = after; after = null;
            if (done != null) done.run();
            return;
        }
        running = true; lastFrame = System.nanoTime(); view.postOnAnimation(frame);
    }

    private void frame() {
        if (!running) return;
        long now = System.nanoTime();
        float seconds = (now - lastFrame) / 1_000_000_000f;
        boolean more;
        if (closingTime >= 0) {
            closingTime += seconds;
            float t = Math.min(1, closingTime / .13f);
            spring.position = closingStart * (1 - t * t * (3 - 2 * t));
            spring.velocity = -closingStart * 6 * t * (1 - t) / .13f;
            more = t < 1;
        } else more = spring.step(seconds);
        if (preview && spring.position < 0) { spring.position = 0; spring.velocity = 0; more = spring.target > 0; }
        lastFrame = now;
        if (!more) running = false;
        listener.onProgress(spring.position);
        if (more) view.postOnAnimation(frame);
        else {
            Runnable done = after; after = null;
            if (done != null) done.run();
        }
    }

    /** Safe when interception and touch dispatch deliver the same event twice. */
    boolean handle(MotionEvent event, boolean canStart, boolean open) {
        int action = event.getActionMasked();
        if (event.getEventTime() == lastEventTime && action == lastAction && event.getX() == lastEventX) return consumed;
        lastEventTime = event.getEventTime(); lastAction = action; lastEventX = event.getX(); consumed = false;
        if (action == MotionEvent.ACTION_DOWN) {
            eligible = canStart && limit > 0;
            pointer = event.getPointerId(0); dragging = false; samples = 0;
            downX = event.getX(); downY = event.getY(); downTarget = preview ? spring.position : open ? limit : 0;
            rawStart = unRubber(spring.position, limit, stretch, coefficient);
            downVelocity = spring.velocity; heldAnimation = running && eligible;
            if (eligible) { view.removeCallbacks(frame); running = false; after = null; }
            sample(event.getEventTime(), downX);
            return false;
        }
        if (pointer < 0) return preview;
        int index = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || index < 0 || action == MotionEvent.ACTION_POINTER_UP) {
            consumed = preview || dragging;
            if (dragging || heldAnimation) {
                listener.onRelease(downTarget > 0);
                settle(downTarget, spring.velocity, null);
            }
            eligible = false; pointer = -1;
            return consumed;
        }
        float x = event.getX(index), dx = x - downX, dy = event.getY(index) - downY;
        if (eligible && !dragging && Math.abs(dy) > (preview ? 10 * density : slop) && Math.abs(dy) > Math.abs(dx) * (preview ? 1.13f : 1.1f)) {
            eligible = false;
            if (heldAnimation) settle(downTarget, downVelocity, null);
        }
        if (action == MotionEvent.ACTION_MOVE && eligible && !dragging
                && Math.abs(dx) > (preview ? 5 * density : slop) && Math.abs(dx) > Math.abs(dy) * (preview ? 1.1f : 1.3f)
                && (preview || spring.position > 0 || dx > 0)) {
            dragging = true; heldAnimation = false;
            listener.onDragStart();
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (dragging) {
            spring.position = rubber(rawStart + dx / width, limit, stretch, coefficient);
            sample(event.getEventTime(), x);
            spring.velocity = velocity(preview ? fingers : positions) / width;
            listener.onProgress(spring.position);
            consumed = true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (dragging) {
                float fingerVelocity = velocity(fingers);
                boolean target = Math.abs(fingerVelocity) > (preview ? 320 : 350) * density && Math.abs(dx) > (preview ? 7 : 12) * density
                        ? fingerVelocity > 0 : preview && Math.abs(dx) > 24 * density ? dx > 0 : spring.position > limit * (preview ? .5f : .46f);
                float velocity = spring.velocity;
                suppressClickUntil = event.getEventTime() + 320;
                listener.onRelease(target);
                settle(target ? limit : 0, velocity, null);
            } else {
                consumed = preview && !eligible || eligible && event.getEventTime() < suppressClickUntil;
                if (heldAnimation) settle(downTarget, downVelocity, null);
            }
            pointer = -1;
        }
        return consumed;
    }

    private void sample(long time, float finger) {
        if (samples > 0 && times[samples - 1] == time) samples--;
        if (samples == times.length) {
            System.arraycopy(times, 1, times, 0, samples - 1);
            System.arraycopy(fingers, 1, fingers, 0, samples - 1);
            System.arraycopy(positions, 1, positions, 0, samples - 1);
            samples--;
        }
        times[samples] = time; fingers[samples] = finger; positions[samples++] = spring.position * width;
    }

    private float velocity(float[] values) {
        if (samples < 2) return 0;
        int last = samples - 1, first = last;
        while (first > 0 && times[last] - times[first - 1] <= 95) first--;
        long elapsed = times[last] - times[first];
        return elapsed > 0 ? (values[last] - values[first]) * 1000 / elapsed : 0;
    }
}
