package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small, scalable stroke icons shared by the native search surface. */
public final class SearchIconView extends View {
    public enum Kind {
        MENU, PRIVATE, PLUS, LIGHTNING, CHEVRON, MIC, VOICE, SEND, CLOSE, BACK,
        SEARCH, SETTINGS, NEW_CHAT, CLOCK, CAMERA, IMAGE, FILE, SKILLS,
        CONNECTOR, CHECK, BOT, PROJECT, TASK, MARK, MORE
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Kind kind;

    public SearchIconView(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.8f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xff171819);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setKind(Kind kind) {
        if (this.kind == kind) return;
        this.kind = kind;
        invalidate();
    }

    public void setColor(int color) {
        stroke.setColor(color);
        invalidate();
    }

    public int getColor() { return stroke.getColor(); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = Math.round(24 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(desired + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(desired + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == null) return;
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft() + (getWidth() - getPaddingLeft()
                        - getPaddingRight() - size) / 2f,
                getPaddingTop() + (getHeight() - getPaddingTop()
                        - getPaddingBottom() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        path.reset();
        switch (kind) {
            case MENU:
                line(canvas, 4, 8, 20, 8); line(canvas, 4, 16, 20, 16); break;
            case PRIVATE:
                path.moveTo(5, 10); path.lineTo(8, 4); path.lineTo(16, 4);
                path.lineTo(19, 10); canvas.drawPath(path, stroke);
                line(canvas, 3, 10, 21, 10);
                circle(canvas, 7.5f, 16.5f, 3.5f); circle(canvas, 16.5f, 16.5f, 3.5f);
                line(canvas, 11, 16, 13, 16); break;
            case PLUS:
                line(canvas, 12, 4, 12, 20); line(canvas, 4, 12, 20, 12); break;
            case LIGHTNING:
                path.moveTo(13.5f, 2.5f); path.lineTo(5, 13); path.lineTo(11, 13);
                path.lineTo(10.5f, 21.5f); path.lineTo(19, 10.5f);
                path.lineTo(13, 10.5f); path.close(); canvas.drawPath(path, stroke); break;
            case CHEVRON:
                poly(canvas, 6, 9, 12, 15, 18, 9); break;
            case MIC:
                roundRect(canvas, 8.5f, 3, 15.5f, 15, 3.5f);
                arc(canvas, 5.5f, 7, 18.5f, 18, 0, 180);
                line(canvas, 12, 18, 12, 21); line(canvas, 9, 21, 15, 21); break;
            case VOICE:
                line(canvas, 3, 10, 3, 14); line(canvas, 7, 6, 7, 18);
                line(canvas, 12, 3, 12, 21); line(canvas, 17, 7, 17, 17);
                line(canvas, 21, 10, 21, 14); break;
            case SEND:
                line(canvas, 12, 19, 12, 5); poly(canvas, 6, 11, 12, 5, 18, 11); break;
            case CLOSE:
                line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); break;
            case BACK:
                line(canvas, 5, 12, 20, 12); poly(canvas, 11, 5, 4, 12, 11, 19); break;
            case SEARCH:
                circle(canvas, 10, 10, 6.5f); line(canvas, 15, 15, 21, 21); break;
            case SETTINGS:
                for (int i = 0; i < 32; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 16;
                    float radius = (i % 4 == 1 || i % 4 == 2) ? 9.3f : 7.6f;
                    float x = 12 + (float) Math.cos(angle) * radius;
                    float y = 12 + (float) Math.sin(angle) * radius;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close(); canvas.drawPath(path, stroke); circle(canvas, 12, 12, 3); break;
            case NEW_CHAT:
                path.moveTo(10, 4); path.lineTo(5, 4); path.quadTo(3, 4, 3, 6);
                path.lineTo(3, 19); path.quadTo(3, 21, 5, 21);
                path.lineTo(18, 21); path.quadTo(20, 21, 20, 19); path.lineTo(20, 14);
                canvas.drawPath(path, stroke);
                path.reset(); path.moveTo(10, 16); path.lineTo(11, 11);
                path.lineTo(18.5f, 3.5f); path.quadTo(20, 2, 21.5f, 3.5f);
                path.quadTo(23, 5, 21.5f, 6.5f); path.lineTo(14, 14);
                path.close(); canvas.drawPath(path, stroke); line(canvas, 17, 5, 20, 8); break;
            case CLOCK:
                circle(canvas, 12, 12, 9); poly(canvas, 12, 6, 12, 12, 16, 14); break;
            case CAMERA:
                path.moveTo(8, 6); path.lineTo(9.5f, 3.5f); path.lineTo(14.5f, 3.5f);
                path.lineTo(16, 6); path.lineTo(20, 6); path.quadTo(22, 6, 22, 8);
                path.lineTo(22, 19); path.quadTo(22, 21, 20, 21);
                path.lineTo(4, 21); path.quadTo(2, 21, 2, 19);
                path.lineTo(2, 8); path.quadTo(2, 6, 4, 6); path.close();
                canvas.drawPath(path, stroke); circle(canvas, 12, 13, 4); break;
            case IMAGE:
                roundRect(canvas, 3, 3, 21, 21, 3);
                circle(canvas, 8, 8, 1.6f); poly(canvas, 4, 17, 9, 12, 13, 16, 16, 13, 21, 18); break;
            case FILE:
                path.moveTo(14, 3); path.lineTo(5, 3); path.lineTo(5, 21);
                path.lineTo(19, 21); path.lineTo(19, 8); path.close();
                canvas.drawPath(path, stroke); poly(canvas, 14, 3, 14, 8, 19, 8);
                line(canvas, 8, 13, 16, 13); line(canvas, 8, 17, 14, 17); break;
            case SKILLS:
                path.moveTo(12, 2); path.lineTo(14.5f, 9.5f); path.lineTo(22, 12);
                path.lineTo(14.5f, 14.5f); path.lineTo(12, 22);
                path.lineTo(9.5f, 14.5f); path.lineTo(2, 12);
                path.lineTo(9.5f, 9.5f); path.close(); canvas.drawPath(path, stroke); break;
            case CONNECTOR:
                roundRect(canvas, 4, 8, 14, 20, 4);
                roundRect(canvas, 10, 4, 20, 16, 4); break;
            case CHECK:
                poly(canvas, 4, 12, 9, 17, 20, 6); break;
            case BOT:
                circle(canvas, 12, 12, 9);
                line(canvas, 9, 9, 9, 12); line(canvas, 15, 9, 15, 12);
                arc(canvas, 8, 12, 16, 17, 30, 120); break;
            case PROJECT:
                path.moveTo(3, 6); path.quadTo(3, 4, 5, 4); path.lineTo(9, 4);
                path.lineTo(11, 6); path.lineTo(19, 6); path.quadTo(21, 6, 21, 8);
                path.lineTo(21, 19); path.quadTo(21, 21, 19, 21);
                path.lineTo(5, 21); path.quadTo(3, 21, 3, 19); path.close();
                canvas.drawPath(path, stroke); line(canvas, 3, 10, 21, 10); break;
            case TASK:
                arc(canvas, 3, 3, 21, 21, 195, 305);
                poly(canvas, 12, 6, 12, 12, 16, 14);
                path.moveTo(7, 13); path.lineTo(2.5f, 19); path.lineTo(6.5f, 19);
                path.lineTo(5, 23); path.lineTo(10.5f, 16.5f); path.lineTo(7, 16.5f);
                path.close(); canvas.drawPath(path, stroke); break;
            case MARK:
                canvas.save();
                canvas.rotate(-35, 12, 12);
                arc(canvas, 4, 6, 20, 18, -35, 295);
                canvas.restore();
                line(canvas, 3, 21, 21, 3); break;
            case MORE:
                Paint.Style style = stroke.getStyle(); stroke.setStyle(Paint.Style.FILL);
                circle(canvas, 12, 5, 1.4f); circle(canvas, 12, 12, 1.4f);
                circle(canvas, 12, 19, 1.4f); stroke.setStyle(style); break;
        }
        canvas.restore();
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, stroke);
    }
    private void circle(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, stroke);
    }
    private void arc(Canvas canvas, float left, float top, float right,
            float bottom, float start, float sweep) {
        rect.set(left, top, right, bottom); canvas.drawArc(rect, start, sweep, false, stroke);
    }
    private void roundRect(Canvas canvas, float left, float top, float right,
            float bottom, float radius) {
        rect.set(left, top, right, bottom); canvas.drawRoundRect(rect, radius, radius, stroke);
    }
    private void poly(Canvas canvas, float... coordinates) {
        path.reset(); path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) {
            path.lineTo(coordinates[i], coordinates[i + 1]);
        }
        canvas.drawPath(path, stroke);
    }
}
