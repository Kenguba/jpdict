package com.yuen.jpdict;

/** OCR aim anchoring and edge hysteresis, independent of window layout timing. */
final class OcrPointerGeometry {
    static final float GAP_DP = 12, RADIUS_DP = 4, HYSTERESIS_DP = 24;
    private boolean below;

    void reset() { below = false; }
    boolean below() { return below; }

    float horizontalAim(float touchX, float screenWidth, float density) {
        float radius = RADIUS_DP * density;
        return Math.max(radius, Math.min(touchX, screenWidth - radius));
    }

    float update(float bubbleTop, float bubbleBottom, float screenBottom, float density) {
        float threshold = bubbleBottom - bubbleTop + (GAP_DP + RADIUS_DP) * density;
        float room = screenBottom - bubbleBottom;
        if (!below && room <= threshold) below = true;
        else if (below && room >= threshold + HYSTERESIS_DP * density) below = false;
        float y = below ? bubbleBottom + GAP_DP * density : bubbleTop - GAP_DP * density;
        return Math.max(RADIUS_DP * density, Math.min(y, screenBottom - RADIUS_DP * density));
    }

    float clampBubbleTop(float top, float height, float screenBottom, float density) {
        float clearance = (GAP_DP + RADIUS_DP) * density;
        return Math.max(below ? 0 : clearance, Math.min(top, screenBottom - height - (below ? clearance : 0)));
    }
}
