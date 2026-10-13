package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;

/** One set of paths, paints and image transforms for editor, history, bubble and top proxy. */
public final class BubbleAppearanceRenderer {
    private BubbleAppearanceRenderer() { }
    public static void draw(Canvas canvas,Context context,FloatingBubbleAppearance.Config config,RectF bounds) {
        draw(canvas,context,config,bounds,null);
    }
    public static Path shapePath(FloatingBubbleAppearance.Config c,float w,float h) {
        Path path=new Path();
        switch (c.shape) {
            case FloatingBubbleAppearance.SHAPE_ROUNDED:
                path.addRoundRect(0,0,w,h,w*.26f,h*.26f,Path.Direction.CW); break;
            case FloatingBubbleAppearance.SHAPE_HEXAGON:
                polygon(path,w,h,.25f,.07f,.75f,.07f,1,.5f,.75f,.93f,.25f,.93f,0,.5f); break;
            case FloatingBubbleAppearance.SHAPE_DIAMOND:
                polygon(path,w,h,.5f,0,1,.5f,.5f,1,0,.5f); break;
            case FloatingBubbleAppearance.SHAPE_STAR:
                polygon(path,w,h,.5f,0,.62f,.31f,.95f,.35f,.70f,.57f,.78f,.91f,.5f,.73f,.22f,.91f,.30f,.57f,.05f,.35f,.38f,.31f); break;
            case FloatingBubbleAppearance.SHAPE_LEGACY:
                float radius=Math.min(w,h)*c.roundnessPercent/200f;
                path.addRoundRect(0,0,w,h,radius,radius,Path.Direction.CW); break;
            default:
                // HTML gives a square stage both circle and pill a 50%/999px radius.
                path.addRoundRect(0,0,w,h,w/2,h/2,Path.Direction.CW);
        }
        return path;
    }
    private static void polygon(Path path,float w,float h,float... points) {
        path.moveTo(points[0]*w,points[1]*h);
        for (int i=2;i<points.length;i+=2) path.lineTo(points[i]*w,points[i+1]*h); path.close();
    }
    public static void draw(Canvas canvas,Context context,FloatingBubbleAppearance.Config c,RectF bounds,View owner) {
        canvas.save(); canvas.translate(bounds.left,bounds.top);
        RectF local=new RectF(0,0,bounds.width(),bounds.height()); Path shape=shapePath(c,local.width(),local.height());
        canvas.clipPath(shape);
        BubblePaintRenderer.draw(canvas,local,c.backgroundPaint,owner,"background");
        if (c.style==FloatingBubbleAppearance.STYLE_IMAGE) {
            drawImage(canvas,context,c,local);
        } else if (c.legacyVisual || c.style==FloatingBubbleAppearance.STYLE_DEFAULT) {
            drawLegacy(canvas,context,c,local,owner);
        } else if (c.style==FloatingBubbleAppearance.STYLE_CUSTOM) {
            drawText(canvas,displayText(c),c.iconPaint,local,owner,false);
        } else {
            float ratio=c.icon==FloatingBubbleAppearance.ICON_SCAN?19f/48:25f/48;
            float size=Math.min(local.width(),local.height())*ratio;
            drawIcon(canvas,c,new RectF(local.centerX()-size/2,local.centerY()-size/2,local.centerX()+size/2,local.centerY()+size/2),owner);
        }
        if (c.legacyVisual) {
            Paint border=new Paint(Paint.ANTI_ALIAS_FLAG); border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(context.getResources().getDisplayMetrics().density); border.setColor(0x55ffffff); canvas.drawPath(shape,border);
        }
        canvas.restore();
    }
    /** The HTML shows two characters; legacy native styles retain their complete label. */
    public static String displayText(FloatingBubbleAppearance.Config config) {
        String text=config.text;
        return config.legacyVisual || text.codePointCount(0,text.length())<=2
                ?text:text.substring(0,text.offsetByCodePoints(0,2));
    }
    public static void drawImage(Canvas canvas,Context context,FloatingBubbleAppearance.Config c,RectF bounds) {
        Bitmap image=FloatingBubbleAppearance.loadImage(context,c); if (image==null) return;
        canvas.save(); canvas.translate(bounds.left,bounds.top); canvas.clipPath(shapePath(c,bounds.width(),bounds.height()));
        float fit=c.legacyVisual?Math.max(bounds.width()/image.getWidth(),bounds.height()/image.getHeight())
                :Math.min(bounds.width()/image.getWidth(),bounds.height()/image.getHeight());
        float scale=fit*c.imageZoomPercent/100;
        float x=bounds.width()/2+c.imageXPercent*bounds.width()/100,y=bounds.height()/2+c.imageYPercent*bounds.height()/100;
        RectF destination=new RectF(x-image.getWidth()*scale/2,y-image.getHeight()*scale/2,x+image.getWidth()*scale/2,y+image.getHeight()*scale/2);
        canvas.drawBitmap(image,null,destination,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG)); canvas.restore();
    }
    public static void drawIcon(Canvas canvas,FloatingBubbleAppearance.Config c,RectF area,View owner) {
        float size=Math.min(area.width(),area.height())*c.iconSizePercent/100;
        RectF iconArea=new RectF(area.centerX()-size/2,area.centerY()-size/2,area.centerX()+size/2,area.centerY()+size/2);
        if (c.icon==FloatingBubbleAppearance.ICON_CUSTOM) {
            try {
                BubbleSvg svg=BubbleSvg.parse(c.customSvg);
                int layer=canvas.saveLayer(iconArea,null);
                try {
                    svg.drawMask(canvas,iconArea);
                    Paint color=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                    BubblePaintRenderer.configure(color,iconArea,c.iconPaint,owner,"icon");
                    color.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN)); canvas.drawRect(iconArea,color);
                } finally { canvas.restoreToCount(layer); }
            } catch (IllegalArgumentException unsupported) {
                // Corrupt restored SVG is visibly marked, never replaced by a misleading search icon.
                Paint error=new Paint(Paint.ANTI_ALIAS_FLAG); error.setColor(0xffcb4355); error.setStrokeWidth(size*.07f);
                canvas.drawLine(iconArea.left,iconArea.top,iconArea.right,iconArea.bottom,error);
                canvas.drawLine(iconArea.right,iconArea.top,iconArea.left,iconArea.bottom,error);
            }
            return;
        }
        canvas.save(); canvas.translate(iconArea.left,iconArea.top); canvas.scale(size/28,size/28);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        BubblePaintRenderer.configure(p,new RectF(0,0,28,28),c.iconPaint,owner,"icon");
        switch (c.icon) {
            case FloatingBubbleAppearance.ICON_BOLD:
                p.setStrokeWidth(3); canvas.drawCircle(12.2f,12,7.1f,p); canvas.drawLine(17.7f,17.5f,23.7f,23.5f,p); break;
            case FloatingBubbleAppearance.ICON_ROUNDED:
                p.setStrokeWidth(2.2f); canvas.drawCircle(11.9f,11.9f,7.8f,p); p.setStrokeWidth(2.8f); canvas.drawLine(18.2f,18.2f,23.3f,23.3f,p); break;
            case FloatingBubbleAppearance.ICON_PLUS:
                p.setStrokeWidth(2); canvas.drawCircle(12.2f,12,7.4f,p); canvas.drawLine(12.2f,8.5f,12.2f,15.5f,p);
                canvas.drawLine(8.7f,12,15.7f,12,p); canvas.drawLine(17.8f,17.8f,23.1f,23.1f,p); break;
            case FloatingBubbleAppearance.ICON_FILLED:
                p.setStyle(Paint.Style.FILL); canvas.drawCircle(11.4f,11.4f,6.1f,p); p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3.7f); canvas.drawLine(15.8f,15.8f,21,21,p); p.setStyle(Paint.Style.FILL); p.setAlpha(71); canvas.drawCircle(11.4f,11.4f,1.7f,p); break;
            case FloatingBubbleAppearance.ICON_SCAN:
                p.setStrokeWidth(1.9f); Path path=new Path(); path.moveTo(7,4.8f); path.lineTo(4.8f,4.8f); path.lineTo(4.8f,7);
                path.moveTo(4.8f,21); path.lineTo(4.8f,18.8f); path.lineTo(7,18.8f);
                path.moveTo(21,7); path.lineTo(21,4.8f); path.lineTo(18.8f,4.8f);
                path.moveTo(18.8f,21); path.lineTo(21,21); path.lineTo(21,18.8f); canvas.drawPath(path,p);
                canvas.drawCircle(11.4f,11.4f,5.9f,p); p.setStrokeWidth(2.2f); canvas.drawLine(15.8f,15.8f,21,21,p); break;
            default:
                p.setStrokeWidth(1.35f); canvas.drawCircle(12.2f,12,7.1f,p); canvas.drawLine(17.7f,17.5f,23.7f,23.5f,p);
        }
        canvas.restore();
    }
    private static void drawText(Canvas canvas,String text,FloatingBubbleAppearance.ColorConfig color,RectF bounds,View owner,boolean legacy) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextAlign(Paint.Align.CENTER);
        int count=text.codePointCount(0,text.length());
        p.setTextSize(legacy?(count>2?17:22)*bounds.width()/54:bounds.width()*14/48f);
        BubblePaintRenderer.configure(p,bounds,color,owner,"icon"); Paint.FontMetrics metrics=p.getFontMetrics();
        canvas.drawText(text,bounds.centerX(),bounds.centerY()-(metrics.ascent+metrics.descent)/2,p);
    }
    private static void drawLegacy(Canvas canvas,Context context,FloatingBubbleAppearance.Config c,RectF bounds,View owner) {
        if (c.style==FloatingBubbleAppearance.STYLE_SEARCH) {
            canvas.save(); float size=bounds.width()*.5f; canvas.translate(bounds.centerX()-size/2,bounds.centerY()-size/2); canvas.scale(size/24,size/24);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND);
            BubblePaintRenderer.configure(p,new RectF(0,0,24,24),c.iconPaint,owner,"icon");
            canvas.drawCircle(10,10,6.5f,p); canvas.drawLine(15,15,21,21,p); canvas.restore(); return;
        }
        drawText(canvas,c.style==FloatingBubbleAppearance.STYLE_CUSTOM?c.text:"あ",c.iconPaint,bounds,owner,true);
        if (c.style!=FloatingBubbleAppearance.STYLE_CUSTOM) {
            Drawable mark=context.getDrawable(android.R.drawable.ic_menu_search).mutate(); mark.setTint(0xffffffff);
            float scale=bounds.width()/54; int right=Math.round(bounds.right-4*scale),bottom=Math.round(bounds.bottom-4*scale),size=Math.round(18*scale);
            mark.setBounds(right-size,bottom-size,right,bottom); mark.draw(canvas);
        }
    }
    static final class AppearanceDrawable extends Drawable {
        private final WeakReference<View> owner; private final FloatingBubbleAppearance.Config config; private int alpha=255;
        AppearanceDrawable(View owner,FloatingBubbleAppearance.Config config) { this.owner=new WeakReference<>(owner); this.config=config; }
        @Override public void draw(Canvas canvas) {
            View view=owner.get(); if (view==null) return;
            RectF area=new RectF(getBounds()); int layer=canvas.saveLayerAlpha(area,alpha);
            BubbleAppearanceRenderer.draw(canvas,view.getContext(),config,area,view); canvas.restoreToCount(layer);
        }
        @Override public void setAlpha(int alpha) { this.alpha=alpha; invalidateSelf(); }
        @Override public void getOutline(Outline outline) {
            int width=getBounds().width(),height=getBounds().height();
            if (width<=0||height<=0 || config.shape==FloatingBubbleAppearance.SHAPE_STAR) { outline.setEmpty(); return; }
            Path path=shapePath(config,width,height); path.offset(getBounds().left,getBounds().top);
            outline.setConvexPath(path); outline.setAlpha(alpha/255f);
        }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
