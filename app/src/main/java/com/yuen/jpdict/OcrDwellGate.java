package com.yuen.jpdict;

/** Movement inside a small area is intentional; travel between areas resets dwell. */
final class OcrDwellGate {
    static final long DELAY_MS = 300;
    private final float radius;
    private float x, y;
    private long since;
    private boolean started;
    OcrDwellGate(float radius) { this.radius = radius; }
    void reset() { started = false; }
    long move(float nextX, float nextY, long now) {
        if (!started || Math.hypot(nextX-x, nextY-y) > radius) {
            x = nextX; y = nextY; since = now; started = true;
        }
        return Math.max(0, DELAY_MS - (now-since));
    }
}
