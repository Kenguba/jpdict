package com.yuen.jpdict;

/** OCR aim anchoring and edge hysteresis, independent of window layout timing. */
final class OcrPointerGeometry {
    static final float OFFSET_DP = 48, RADIUS_DP = 4, HYSTERESIS_DP = 24;
    private boolean below;

    void reset() { below = false; }
    boolean below() { return below; }

    float horizontalAim(float x, float screenWidth, float density) {
        float radius = RADIUS_DP * density;
        return Math.max(radius, Math.min(x, screenWidth - radius));
    }

    float update(float bubbleTop, float bubbleBottom, float screenBottom, float density) {
        float height = bubbleBottom - bubbleTop;
        float threshold = height + clearance(height, density);
        float room = screenBottom - bubbleBottom;
        if (!below && room <= threshold) below = true;
        else if (below && room >= threshold + HYSTERESIS_DP * density) below = false;
        float y = (bubbleTop + bubbleBottom)/2 + (below ? 1 : -1) * OFFSET_DP * density;
        return Math.max(RADIUS_DP * density, Math.min(y, screenBottom - RADIUS_DP * density));
    }

    private float clearance(float height, float density) {
        return Math.max(0, (OFFSET_DP + RADIUS_DP) * density - height/2);
    }

    float clampBubbleTop(float top, float height, float screenBottom, float density) {
        float clearance = (float)Math.ceil(clearance(height, density));
        return Math.max(below ? 0 : clearance, Math.min(top, screenBottom - height - (below ? clearance : 0)));
    }
}
