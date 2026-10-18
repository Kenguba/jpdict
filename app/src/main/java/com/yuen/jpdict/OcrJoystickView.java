package com.yuen.jpdict;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A fixed four-sector ring centred on the existing floating bubble. */
final class OcrJoystickView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ring = new Path();
    private final int[] location = new int[2];
    private final String[] labels = {"句子 OCR", "日语 OCR", "中文 OCR", ""};
    private final float density, x, y, hole;
    private final LookupDestinationSettings destination;
    private final LookupDestinationSettings.Listener destinationChanged = this::invalidate;
    private int direction = -1;
    OcrJoystickView(Context context, Rect bubble) {
        super(context); AppTheme.observe(this, this::invalidate); density = getResources().getDisplayMetrics().density;
        destination = LookupDestinationSettings.get(context);
        x = bubble.exactCenterX(); y = bubble.exactCenterY(); hole = bubble.width()/2f + 5*density;
        setAlpha(0);
    }
    void select(int next) {
        if (direction == next) return;
        direction = next; invalidate();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); destination.observe(destinationChanged);
        invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        animate().cancel(); destination.removeObserver(destinationChanged); super.onDetachedFromWindow();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        getLocationOnScreen(location);
        canvas.save(); canvas.translate(-location[0], -location[1]);
        float radius = Math.max(92*density, hole+62*density);
        ring.rewind(); ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addCircle(x, y, radius, Path.Direction.CW); ring.addCircle(x, y, hole, Path.Direction.CW);
        paint.setColor((AppTheme.color(AppTheme.CARD) & 0xffffff) | 0xed000000); canvas.drawPath(ring, paint);
        canvas.save(); canvas.clipPath(ring);
        if (direction >= 0) {
            paint.setColor(0x29168af2);
            canvas.drawArc(x-radius, y-radius, x+radius, y+radius, direction*90-135, 90, true, paint);
        }
        canvas.restore();
        paint.setColor(0x30168af2); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
        canvas.drawCircle(x, y, radius, paint); paint.setStyle(Paint.Style.FILL);
        labels[3] = destination.isThirdParty() ? "系统" : "第三方";
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(12*density);
        for (int index = 0; index < 4; index++) {
            float angle = (float)Math.toRadians(index*90-90), r = hole+(radius-hole)*0.55f;
            float tx = x+(float)Math.cos(angle)*r, ty = y+(float)Math.sin(angle)*r;
            float half = paint.measureText(labels[index])/2;
            tx = Math.max(location[0]+half+8*density, Math.min(tx, location[0]+getWidth()-half-8*density));
            ty = Math.max(location[1]+24*density, Math.min(ty, location[1]+getHeight()-24*density));
            paint.setColor(AppTheme.ink(direction == index ? AppTheme.BLUE : AppTheme.MUTED));
            paint.setTypeface(direction == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(labels[index], tx, ty-(paint.ascent()+paint.descent())/2, paint);
        }
        canvas.restore();
    }
}
