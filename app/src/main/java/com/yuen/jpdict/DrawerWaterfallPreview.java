package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Native port of Sunbeam's pool and soft fireflies; the existing forest has no waterfall. */
final class DrawerWaterfallPreview implements Runnable {
    private static final float TAU = (float)(Math.PI * 2);
    private static final Noise NOISE = new Noise(271828);
    private final Context context;
    private final View target;
    private final Bitmap forest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final Path path = new Path();
    private final Pool pool = new Pool();
    private final float[][] flies = new float[28][9];
    private final Bitmap[] sprites = new Bitmap[2];
    private final PorterDuffXfermode light = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final Weather weather = new Weather();
    private Bitmap backdrop;
    private float worldBottom, scale;
    private long started, elapsed;
    private boolean running, animated;

    DrawerWaterfallPreview(Context context, View target) {
        this.context = context; this.target = target;
        forest = BitmapFactory.decodeResource(context.getResources(), R.drawable.drawer_preview_forest);
        sprites[0] = sprite(132, 228, 42, 188, 244, 88, 222, 250, 138);
        sprites[1] = sprite(218, 172, 38, 242, 212, 88, 250, 232, 142);
        Rng random = new Rng(88217);
        for (int i = 0; i < flies.length; i++) {
            float[] f = flies[i];
            if (i < 14) {
                f[0] = (i % 4 - 1.5f) * 112 + (random.next() - .5f) * 84;
                f[1] = -355 + (i / 4) * 72 + (random.next() - .5f) * 48;
                f[8] = 1.15f + random.next() * .72f;
            } else if (i < 22) {
                int band = (i - 14) / 2;
                f[0] = (i % 2 == 0 ? -1 : 1) * (86 + band * 28 + (random.next() - .5f) * 36);
                f[1] = -122 + band * 40 + (random.next() - .5f) * 30;
                f[8] = 1.32f + random.next() * .76f;
            } else {
                f[0] = (i % 2 == 0 ? -1 : 1) * (80 + random.next() * 142);
                f[1] = 42 + random.next() * 64; f[8] = 1.45f + random.next() * .78f;
            }
            f[2] = 11 + random.next() * 19; f[3] = 7 + random.next() * 13;
            f[4] = .36f + random.next() * .44f; f[5] = .30f + random.next() * .40f;
            f[6] = random.next() * TAU; f[7] = 1.3f + random.next() * 1.4f;
        }
    }

    void resume() {
        if (running) return;
        animated = DrawerMotionController.animationsEnabled(context);
        running = true; started = SystemClock.uptimeMillis(); target.postOnAnimation(this);
    }

    void pause() {
        if (running && animated) elapsed += SystemClock.uptimeMillis() - started;
        running = false; target.removeCallbacks(this);
    }

    void draw(Canvas canvas, float width, float height) {
        draw(canvas, width, height, System.currentTimeMillis());
    }

    void draw(Canvas canvas, float width, float height, long wallTime) {
        if (width <= 0 || height <= 0) return;
        int w = Math.max(1, Math.round(width)), h = Math.max(1, Math.round(height));
        if (backdrop == null || backdrop.getWidth() != w || backdrop.getHeight() != h) {
            if (backdrop != null) backdrop.recycle();
            backdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas background = new Canvas(backdrop);
            paint.setColor(0xffffffff);
            if (forest != null) {
                float crop = Math.max(width / forest.getWidth(), height / forest.getHeight());
                float cw = width / crop, ch = height / crop;
                source.set(Math.round((forest.getWidth() - cw) / 2), Math.round((forest.getHeight() - ch) / 2),
                        Math.round((forest.getWidth() + cw) / 2), Math.round((forest.getHeight() + ch) / 2));
                destination.set(0, 0, w, h); background.drawBitmap(forest, source, destination, paint);
            } else background.drawColor(0xff173833);
            scale = width / 540; worldBottom = height / (2 * scale);
            pool.resize(worldBottom, width / context.getResources().getDisplayMetrics().density / 540 * 1.35f);
        }
        float time = (elapsed + (running && animated ? SystemClock.uptimeMillis() - started : 0)) / 1000f;
        if (running) time = (float)Math.floor(time * 30) / 30;
        paint.setColor(0xffffffff); canvas.drawBitmap(backdrop, 0, 0, paint);
        canvas.save(); canvas.clipRect(0, 0, width, height);
        canvas.translate(width / 2, height / 2); canvas.scale(scale, scale);
        pool.draw(canvas, time * .68f);
        weather.update(wallTime);
        canvas.drawColor(weather.tint, PorterDuff.Mode.MULTIPLY);
        if (weather.rain > 0) canvas.drawColor(rgba(12, 30, 42, weather.rain * 38));
        drawFlies(canvas, time);
        weather.draw(canvas, time, worldBottom);
        canvas.restore();
    }

    private void drawFlies(Canvas canvas, float t) {
        paint.setXfermode(light);
        for (int i = 0; i < flies.length; i++) {
            float[] f = flies[i];
            float x = f[0] + sin(t * f[4] + f[6]) * f[2] + cos(t * .28f + f[6] * 1.7f) * f[2] * .4f;
            float y = f[1] + cos(t * f[5] + f[6]) * f[3] + sin(t * .22f + f[6] * 2.1f) * f[3] * .4f;
            float glow = .24f + .54f * pow(.5f + .5f * sin(t * f[7] + f[6]), 1.45);
            float r = f[8] * (4.2f + glow * 2);
            paint.setColor(0xffffffff); paint.setAlpha(Math.round(Math.min(.64f, glow * .78f) * 255));
            destination.set(x - r, y - r, x + r, y + r); canvas.drawBitmap(sprites[i % 2], null, destination, paint);
            paint.setColor(i % 2 == 0 ? 0xffd2f67a : 0xfff6de7e);
            paint.setAlpha(Math.round(Math.min(.36f, glow * .42f) * .82f * 255));
            canvas.drawCircle(x, y, f[8] * (.38f + glow * .14f), paint);
            if (y > 36 && y < 124) {
                float ry = y < 94 ? 95 + (94 - y) * .5f : y + 6;
                float shift = sin(t * 3.1f + i * 1.9f) * 2.2f;
                float rx = f[8] * (3.2f + glow * 1.5f), rh = f[8] * (.95f + glow * .42f);
                paint.setColor(0xffffffff); paint.setAlpha(Math.round(Math.min(.2f, glow * .22f) * 255));
                destination.set(x + shift - rx, ry - rh, x + shift + rx, ry + rh);
                canvas.drawBitmap(sprites[i % 2], null, destination, paint);
            }
        }
        paint.setXfermode(null); paint.setColor(0xffffffff);
    }

    private static Bitmap sprite(int ro, int go, int bo, int rm, int gm, int bm, int rc, int gc, int bc) {
        int[] pixels = new int[64 * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            float dist = (float)Math.hypot(x + .5f - 32, y + .5f - 32) / 32;
            if (dist >= 1) continue;
            float core = (float)Math.exp(-dist * dist * 24) * .74f;
            float corona = (float)Math.exp(-dist * dist * 7.8) * .56f;
            float halo = pow(1 - dist, 2.2) * .38f;
            float tc = clamp(core * 1.25f), tm = clamp(corona * 1.3f);
            pixels[y * 64 + x] = rgba(mix(mix(ro, rm, tm), rc, tc), mix(mix(go, gm, tm), gc, tc),
                    mix(mix(bo, bm, tm), bc, tc), Math.min(.92f, core + corona + halo) * 255);
        }
        return Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
    }

    /** The HTML pool's cached perspective grids, noise, refraction and moving wave crests. */
    private final class Pool {
        private Bitmap image;
        private int width, height;
        private float span, lastTime = -1;
        private int[] pixels;
        private float[] u, depth, radial, mask, plume, y3d;
        private final float[][] crests = new float[28][6];
        Pool() {
            Rng random = new Rng(90421);
            for (int i = 0; i < crests.length; i++) {
                int lane = i % 5; float[] c = crests[i];
                c[0] = lane == 0 ? -135 + random.next() * 90 : lane == 1 ? -55 + (random.next() - .5f) * 65
                        : lane == 2 ? -10 + (random.next() - .5f) * 85 : lane == 3 ? 65 + (random.next() - .5f) * 90
                        : -95 + random.next() * 210;
                c[1] = (c[0] + 12) * .36f + (random.next() - .5f) * 42;
                c[2] = random.next(); c[3] = .12f + random.next() * .1f;
                c[4] = 20 + random.next() * 38; c[5] = .35f + random.next() * .6f;
            }
        }
        void resize(float bottom, float resolution) {
            span = Math.max(180, bottom - 86 + 8); width = Math.max(128, Math.min(320, Math.round(556 * resolution)));
            height = Math.max(72, Math.min(380, Math.round(width * span / 556 * .85f)));
            if (image != null) image.recycle();
            image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int size = width * height; pixels = new int[size];
            u = new float[size]; depth = new float[size]; radial = new float[size];
            mask = new float[size]; plume = new float[size]; y3d = new float[size];
            for (int py = 0; py < height; py++) {
                float wy = 86 + (py + .5f) / height * span, z = Math.max(.006f, (wy - 86) / 260);
                for (int px = 0; px < width; px++) {
                    int i = py * width + px; float wx = -278 + (px + .5f) / width * 556;
                    float bank = bank(wx); mask[i] = smooth(bank - 3, bank + 6, wy);
                    y3d[i] = pow(z, .52) * 26; depth[i] = clamp((wy - 92) / 310);
                    u[i] = (wx + 8) / (165 * (.52f + z * .58f));
                    radial[i] = (float)Math.hypot((wx + 4) * .15f, Math.max(0, wy - 92) * .95f) * .14f;
                    float dx = (wx - (-4 + z * 14)) / (158 + z * 130);
                    plume[i] = Math.min(1.1f, (float)(Math.exp(-dx * dx * 1.04) * Math.exp(-z * 1.6)));
                }
            }
            lastTime = -1;
        }
        void draw(Canvas canvas, float t) {
            reflect(canvas, t);
            if (lastTime != t) {
                float flow = t * 1.02f;
                for (int i = 0; i < pixels.length; i++) {
                    if (mask[i] < .004f) continue;
                    float a = u[i], y = y3d[i], p = plume[i], d = depth[i];
                    float warp = (NOISE.fast(a * 1.5 + 17, y * .48 - t * .26) - .5f) * .6f;
                    float w1 = 1 - Math.abs(2 * NOISE.fast(a * 1.65 + warp * .55 + 11, y * 1.85 - flow * .72 + radial[i] * .55) - 1);
                    float w2 = 1 - Math.abs(2 * NOISE.fast(a * 2.95 - warp * .45 + 47, y * 2.9 - flow * .95 + radial[i] * .35) - 1);
                    float w3 = NOISE.fast(a * 5.6 + 83, y * 4.4 - flow * 1.18 + warp * .75);
                    float ridge = pow(clamp(w1 * .52f + w2 * .34f + w3 * .14f), 1.8);
                    float trough = smooth(.45f, .15f, w1 * .55f + w2 * .45f);
                    float glint = smooth(.6f, .86f, ridge * .65f + w3 * .35f) * (.32f + p * .48f + (1 - d) * .2f);
                    float cell = 1 - Math.abs(2 * NOISE.fast(a * 2.9 + warp * .6 + 29, y * 2.15 - flow * .8) - 1);
                    float fine = NOISE.fast(a * 5.8 - warp * .5 + 71, y * 3.6 - flow * 1.05);
                    float lace = smooth(.47f - p * .24f, .77f - p * .15f, cell * .58f + fine * .42f) * p;
                    float r = 37 - trough * 15 + ridge * 39, g = 79 - trough * 15 + ridge * 37, b = 73 - trough * 13 + ridge * 41;
                    float shallow = smooth(.32f, .92f, d);
                    if (shallow > .01f) {
                        float refract = (ridge - .35f) * .11f, pu = a + refract, py = y + refract * .75f;
                        float ca = 1 - Math.abs(2 * NOISE.fast(pu * 5.2 + 13, py * 1.75 + 7) - 1);
                        float cb = 1 - Math.abs(2 * NOISE.fast(pu * 11.2 - 29, py * 3.6 + 19) - 1);
                        float pebble = NOISE.fast(pu * 22 + 5, py * 6.8 - 11);
                        float shape = smooth(.22f, .78f, ca * .54f + cb * .32f + pebble * .14f);
                        float hue = NOISE.fast(pu * 3.4 + 41, py * 1.1 - 17), visibility = shallow * (.8f - ridge * .48f);
                        r = mix(r, 24 + shape * (42 + hue * 30), visibility);
                        g = mix(g, 52 + shape * (42 + (1 - hue) * 18), visibility);
                        b = mix(b, 26 + shape * (24 + (1 - hue) * 16), visibility);
                    }
                    pixels[i] = rgba(r + lace * 84 + glint * 58, g + lace * 75 + glint * 52,
                            b + lace * 78 + glint * 56, mask[i] * .96f * 255);
                }
                image.setPixels(pixels, 0, width, 0, 0, width, height); lastTime = t;
            }
            paint.setColor(0xffffffff); destination.set(-278, 86, 278, 86 + span);
            canvas.drawBitmap(image, null, destination, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < crests.length; i++) {
                float[] c = crests[i]; float age = (t * c[3] + c[2]) % 1, z = pow(age, 1.32);
                float y = 98 + z * 175, x = c[0] + c[1] * z + sin(age * TAU + i) * 9 * z;
                float half = c[4] * (.36f + z * .92f), bow = 1 + z * 2.5f;
                float alpha = sin(age * Math.PI) * (.1f + c[5] * .14f) * (1 - z * .25f);
                paint.setColor(c[5] > .68f ? rgba(206, 236, 228, alpha * 255) : rgba(116, 178, 168, alpha * 255));
                paint.setStrokeWidth(.6f + z * 1.1f); path.reset(); path.moveTo(x - half, y - bow * .35f);
                path.cubicTo(x - half * .3f, y + bow, x + half * .3f, y + bow, x + half, y - bow * .35f);
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        private void reflect(Canvas canvas, float t) {
            canvas.save(); path.reset(); path.moveTo(-270, worldBottom);
            for (int x = -270; x <= 282; x += 12) path.lineTo(x, bank(x) + 1);
            path.lineTo(270, worldBottom); path.close(); canvas.clipPath(path);
            float step = Math.max(1.8f, 1.6f / scale);
            for (float y = 92; y < Math.min(worldBottom, 270); y += step) {
                float dist = y - 94, sy = (92 - dist * 1.25f + worldBottom) * scale;
                if (sy < 0 || sy >= backdrop.getHeight() - 2) continue;
                float drift = sin(y * .28f - t * 1.8f) * (1.5f + dist * .03f)
                        + (NOISE.fast(y * .14 - t * .25, t * .18) - .5f) * 7;
                float fade = (1 - smooth(124, 264, y)) * .18f;
                source.set(0, (int)sy, backdrop.getWidth(), Math.min(backdrop.getHeight(), (int)sy + 2));
                destination.set(-280 + drift, y, 280 + drift, y + step + .25f);
                paint.setColor(0xffffffff); paint.setAlpha(Math.round(fade * 255)); canvas.drawBitmap(backdrop, source, destination, paint);
            }
            paint.setColor(0xffffffff); canvas.restore();
        }
    }

    /** Local weather changes independently of the retained animation clock and app theme. */
    private static final class Weather {
        private static final int[] HOURS = {0, 3, 5, 7, 10, 12, 15, 18, 20, 24};
        // Opaque multiply filters preserve the forest's colors and alpha.
        private static final int[] FILTERS = {0xff515b73, 0xff454d63, 0xff8a95a4, 0xfffff9f0, 0xfff7f7f7,
                0xffffffff, 0xfffffcf8, 0xffd5cdc9, 0xff66718b, 0xff515b73};
        private final java.util.Calendar clock = java.util.Calendar.getInstance();
        private final java.util.Random random = new java.util.Random();
        private final float[][] drops = new float[96][4];
        private long next, changed, sampled = Long.MIN_VALUE;
        private float from, to, rain;
        private int tint;
        Weather() {
            for (float[] drop : drops) {
                drop[0] = random.nextFloat(); drop[1] = random.nextFloat() * 20;
                drop[2] = random.nextFloat(); drop[3] = random.nextFloat();
            }
        }
        void update(long now) {
            if (sampled != now / 1000) {
                sampled = now / 1000; clock.setTimeZone(java.util.TimeZone.getDefault()); clock.setTimeInMillis(now);
                tint = tintAt(clock.get(java.util.Calendar.HOUR_OF_DAY) + clock.get(java.util.Calendar.MINUTE) / 60f
                        + clock.get(java.util.Calendar.SECOND) / 3600f);
            }
            if (next == 0 || now >= next || now < changed) {
                from = rain; to = random.nextFloat() < .4f ? .35f + random.nextFloat() * .65f : 0;
                changed = now; next = now + 300_000 + random.nextInt(600_001);
            }
            rain = mix(from, to, smooth(0, 20_000, now - changed));
        }
        static int tintAt(float hour) {
            hour = ((hour % 24) + 24) % 24;
            int i = 0; while (i < HOURS.length - 2 && hour >= HOURS[i + 1]) i++;
            float t = smooth(HOURS[i], HOURS[i + 1], hour); int a = FILTERS[i], b = FILTERS[i + 1];
            return rgba(mix((a >> 16) & 255, (b >> 16) & 255, t), mix((a >> 8) & 255, (b >> 8) & 255, t),
                    mix(a & 255, b & 255, t), 255);
        }
        void draw(Canvas canvas, float t, float bottom) {
            if (rain <= .005f) return;
            float wind = -26 + sin(t * .11f) * 12;
            for (int i = 0; i < drops.length; i++) {
                float[] d = drops[i]; if (i > rain * drops.length) continue;
                float speed = 200 + d[2] * 330, landing = 94 + d[3] * Math.max(0, bottom - 94);
                float flight = (landing + bottom + 20) / speed, age = (t + d[1]) % (flight + .55f);
                float x = d[0] * 540 - 270, depth = .4f + d[2] * .6f;
                brush.setStrokeWidth(.55f + d[2] * .8f); brush.setStyle(Paint.Style.STROKE);
                if (age < flight) {
                    float y = -bottom - 20 + age * speed, length = 8 + d[2] * 16;
                    length = Math.min(length, landing - y);
                    x += wind * (age - flight); brush.setColor(rgba(167, 212, 216, depth * rain * 150));
                    canvas.drawLine(x, y, x + wind * length / speed, y + length, brush);
                } else {
                    float hit = (age - flight) / .55f, r = (2 + hit * 18) * depth;
                    brush.setColor(rgba(187, 226, 218, (1 - hit) * rain * 110));
                    canvas.drawOval(x - r, landing - r * .16f, x + r, landing + r * .16f, brush);
                }
            }
        }
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    private static float bank(float x) { return 92 + smooth(-180, 180, x) * 4 + (NOISE.fast(x * .014 + 4.2, 2.7) - .5f) * 6; }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static float smooth(float a, float b, float value) { float t = clamp((value - a) / (b - a)); return t * t * (3 - 2 * t); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float sin(double x) { return (float)Math.sin(x); }
    private static float cos(double x) { return (float)Math.cos(x); }
    private static float pow(float x, double n) { return (float)Math.pow(x, n); }
    private static int rgba(float r, float g, float b, float a) {
        return Math.round(clamp(a / 255) * 255) << 24 | Math.round(clamp(r / 255) * 255) << 16
                | Math.round(clamp(g / 255) * 255) << 8 | Math.round(clamp(b / 255) * 255);
    }
    private static final class Rng {
        private int seed;
        Rng(int seed) { this.seed = seed; }
        float next() {
            seed += 0x6d2b79f5; int t = (seed ^ seed >>> 15) * (1 | seed);
            t = (t + (t ^ t >>> 7) * (61 | t)) ^ t;
            return (float)((t ^ t >>> 14) & 0xffffffffL) / 4294967296f;
        }
    }
    private static final class Noise {
        private final float[] table = new float[512 * 512];
        Noise(int seed) { Rng r = new Rng(seed); for (int i = 0; i < table.length; i++) table[i] = r.next(); }
        float fast(double x, double y) {
            int ix = (int)Math.floor(x), iy = (int)Math.floor(y); float u = (float)(x - ix), v = (float)(y - iy);
            u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v);
            int a = (iy & 511) * 512, b = ((iy + 1) & 511) * 512;
            return mix(mix(table[a + (ix & 511)], table[a + ((ix + 1) & 511)], u),
                    mix(table[b + (ix & 511)], table[b + ((ix + 1) & 511)], u), v);
        }
    }

    @Override public void run() {
        if (!running) return;
        target.invalidate(); target.postDelayed(this, animated ? 33 : 60_000);
    }
}
