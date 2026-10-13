package com.yuen.jpdict;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared paints. Free color fields are computed off the UI thread with bounded cache/queue. */
public final class BubblePaintRenderer {
    private static final int LIMIT=24;
    private static final Map<Key,Bitmap> CACHE=new LinkedHashMap<>(LIMIT,.75f,true);
    private static final Map<Key,Work> PENDING=new LinkedHashMap<>();
    // Keep the preceding field while a drag's next frame is computed. Keys include the
    // paint's local rectangle so Icon and background cannot borrow each other's colors.
    private static final Map<View,Map<String,Bitmap>> LAST=new WeakHashMap<>();
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(LIMIT),r->{ Thread t=new Thread(r,"bubble-color-field"); t.setDaemon(true); return t; });
    static { WORKER.allowCoreThreadTimeOut(true); }
    private BubblePaintRenderer() { }
    public static void draw(Canvas canvas,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner) {
        draw(canvas,area,color,owner,"paint");
    }
    public static void draw(Canvas canvas,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner,String target) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG); configure(p,area,color,owner,target); canvas.drawRect(area,p);
    }
    /** HTML distinguishes the fixed workspace canvas, square swatches and cover-scaled presets. */
    public static void drawSampled(Canvas canvas,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner,
            int sampleWidth,int sampleHeight,boolean cover) {
        drawSampled(canvas,area,color,owner,sampleWidth,sampleHeight,cover,"sample");
    }
    public static void drawSampled(Canvas canvas,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner,
            int sampleWidth,int sampleHeight,boolean cover,String target) {
        if (sampleWidth<2||sampleHeight<2||sampleWidth>256||sampleHeight>256)
            throw new IllegalArgumentException("Invalid color field dimensions");
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        configureSampled(paint,area,color,owner,target+"-"+sampleWidth+"x"+sampleHeight+"-"+cover,sampleWidth,sampleHeight,cover);
        canvas.drawRect(area,paint);
    }
    public static void configure(Paint paint,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner) {
        configure(paint,area,color,owner,"paint");
    }
    public static void configure(Paint paint,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner,String target) {
        configureSampled(paint,area,color,owner,target,96,96,false);
    }
    private static void configureSampled(Paint paint,RectF area,FloatingBubbleAppearance.ColorConfig color,View owner,
            String target,int width,int height,boolean cover) {
        paint.setShader(null); paint.setColor(color.solid);
        if (area.width()<=0 || area.height()<=0) return;
        if (color.mode==FloatingBubbleAppearance.COLOR_LINEAR) {
            double a=Math.toRadians(color.angle),dx=Math.sin(a),dy=-Math.cos(a);
            // CSS gradient line length is the sum of projections, including magic corners.
            float half=(float)(Math.abs(area.width()*dx)+Math.abs(area.height()*dy))/2;
            paint.setShader(new LinearGradient(area.centerX()-(float)dx*half,area.centerY()-(float)dy*half,
                    area.centerX()+(float)dx*half,area.centerY()+(float)dy*half,color.start,color.end,Shader.TileMode.CLAMP));
        } else if (color.mode==FloatingBubbleAppearance.COLOR_FREE) {
            Key key=new Key(color.freePoints,width,height); Bitmap texture;
            synchronized (CACHE) {
                texture=CACHE.get(key);
                if (texture==null) request(key,owner);
                if (owner!=null) {
                    String slot=target+":"+area.left+":"+area.top+":"+area.right+":"+area.bottom;
                    Map<String,Bitmap> previous=LAST.get(owner);
                    if (previous==null) { previous=new LinkedHashMap<>(); LAST.put(owner,previous); }
                    if (texture!=null) {
                        previous.put(slot,texture);
                        while (previous.size()>4) previous.remove(previous.keySet().iterator().next());
                        trimPrevious();
                    } else texture=previous.get(slot);
                }
            }
            if (texture!=null) {
                BitmapShader shader=new BitmapShader(texture,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                float sx=area.width()/texture.getWidth(),sy=area.height()/texture.getHeight();
                if (cover) sx=sy=Math.max(sx,sy);
                Matrix m=new Matrix(); m.setScale(sx,sy);
                m.postTranslate(area.centerX()-texture.getWidth()*sx/2,area.centerY()-texture.getHeight()*sy/2);
                shader.setLocalMatrix(m); paint.setShader(shader);
            }
        }
    }
    private static void trimPrevious() {
        int pixels=0;
        for (Map<String,Bitmap> slots:LAST.values()) for (Bitmap image:slots.values()) pixels+=image.getWidth()*image.getHeight();
        java.util.Iterator<Map<String,Bitmap>> iterator=LAST.values().iterator();
        while ((LAST.size()>32 || pixels>512*1024) && iterator.hasNext()) {
            Map<String,Bitmap> slots=iterator.next();
            for (Bitmap image:slots.values()) pixels-=image.getWidth()*image.getHeight();
            iterator.remove();
        }
    }
    private static void request(Key key,View owner) {
        Work existing=PENDING.get(key);
        if (existing!=null) { existing.watch(owner); return; }
        Work work=new Work(key); work.watch(owner);
        if (WORKER.getQueue().remainingCapacity()==0) {
            Runnable dropped=WORKER.getQueue().poll();
            if (dropped instanceof Work) PENDING.remove(((Work)dropped).key);
        }
        PENDING.put(key,work); WORKER.execute(work);
    }
    private static final class Work implements Runnable {
        final Key key; final List<WeakReference<View>> owners=new ArrayList<>();
        Work(Key key) { this.key=key; }
        void watch(View view) {
            if (view==null) return;
            for (WeakReference<View> reference:owners) if (reference.get()==view) return;
            if (owners.size()<32) owners.add(new WeakReference<>(view));
        }
        @Override public void run() {
            try {
                Bitmap result=Bitmap.createBitmap(BubbleColorField.pixels(key.points,key.width,key.height),
                        key.width,key.height,Bitmap.Config.ARGB_8888);
                List<WeakReference<View>> notify;
                synchronized (CACHE) {
                    CACHE.put(key,result);
                    while (CACHE.size()>LIMIT) CACHE.remove(CACHE.keySet().iterator().next());
                    notify=new ArrayList<>(owners);
                }
                for (WeakReference<View> reference:notify) { View owner=reference.get(); if (owner!=null) owner.postInvalidate(); }
            } catch (OutOfMemoryError memoryPressure) {
                synchronized (CACHE) { CACHE.clear(); LAST.clear(); }
            } finally {
                synchronized (CACHE) { PENDING.remove(key); }
            }
        }
    }
    private static final class Key {
        final List<FloatingBubbleAppearance.FreePoint> points; final int width,height;
        Key(List<FloatingBubbleAppearance.FreePoint> points,int width,int height) { this.points=points; this.width=width; this.height=height; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false; Key k=(Key)o;
            return width==k.width && height==k.height && points.equals(k.points);
        }
        @Override public int hashCode() { return 31*(31*points.hashCode()+width)+height; }
    }
}
