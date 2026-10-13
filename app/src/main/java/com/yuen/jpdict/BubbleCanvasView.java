package com.yuen.jpdict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.BitmapShader;
import android.view.View;
import java.util.LinkedHashMap;

/** Shared native Canvas renderer: settings, saved thumbnails and floating service use this. */
final class BubbleCanvasView extends View {
    private final FloatingBubbleAppearance.Config config;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path shape=new Path();
    private final Path glyph=new Path();
    private final RectF bounds=new RectF();
    private final Bitmap image;
    private static final LinkedHashMap<String,Bitmap> FREE_CACHE=new LinkedHashMap<String,Bitmap>(12,.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Bitmap> entry) {
            return size()>12;
        }
    };

    BubbleCanvasView(Context context,FloatingBubbleAppearance.Config cfg,Bitmap bitmap){
        super(context); config=cfg; image=bitmap;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if(config.design==null)return;
        final BubbleDesign d=config.design;
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        c.save();
        c.scale(w/100f,h/100f);
        shape.reset();makeShape(shape,d.shape);
        c.save();c.clipPath(shape);
        paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
        applyPaint(paint,d.background,0,0,100,100);
        c.drawRect(0,0,100,100,paint);
        paint.setShader(null);
        if(config.style==FloatingBubbleAppearance.STYLE_IMAGE && image!=null){
            paint.setColor(Color.WHITE);
            Matrix m=new Matrix();
            // CSS equivalent: image element covers the square, then translate and scale.
            float base=Math.min(100f/image.getWidth(),100f/image.getHeight());
            float bw=image.getWidth()*base, bh=image.getHeight()*base;
            float zoom=d.imageZoom/100f;
            float cx=50+ d.imageX,cy=50+d.imageY;
            m.postScale(base*zoom,base*zoom);
            m.postTranslate(cx-bw*zoom/2f,cy-bh*zoom/2f);
            c.drawBitmap(image,m,paint);
        } else if(config.style==FloatingBubbleAppearance.STYLE_CUSTOM) {
            drawText(c,d,config);
        }else if(config.style==FloatingBubbleAppearance.STYLE_DEFAULT){
            drawText(c,d,config);
            drawSearch(c,d,66,66,27,true);
        }else{
            drawSearch(c,d,50,50,d.icon==5?40:52,false);
        }
        c.restore();
        c.restore();
    }
    private void drawText(Canvas c,BubbleDesign d,FloatingBubbleAppearance.Config cfg){
        String str=cfg.style==FloatingBubbleAppearance.STYLE_CUSTOM
                ?(d.label.isEmpty()?cfg.text:d.label):"あ";
        paint.reset();paint.setAntiAlias(true);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));
        float unit=Math.min(43f,85f/Math.max(1,str.codePointCount(0,str.length()))*1.5f);
        paint.setTextSize(unit);
        paint.setTextAlign(Paint.Align.CENTER);
        applyPaint(paint,d.foreground,0,10,100,90);
        Paint.FontMetrics fm=paint.getFontMetrics();
        c.drawText(str,50,50-(fm.ascent+fm.descent)/2f,paint);
        paint.setShader(null);
    }
    private void drawSearch(Canvas c,BubbleDesign d,float centerX,float centerY,float side,boolean small){
        if(d.icon==6 && !d.svg.isEmpty()){
            if(BubbleSvgIcon.draw(c,d.svg,d.foreground,centerX,centerY,side*d.iconSize/100f))return;
        }
        c.save();c.translate(centerX,centerY);
        float scale=side/28f*d.iconSize/100f;c.scale(scale,scale);c.translate(-14,-14);
        glyph.reset();
        paint.reset();paint.setAntiAlias(true);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        int kind=small?0:d.icon;
        switch(kind){
            case 1:
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3f);
                glyph.addCircle(12.2f,12f,7.1f,Path.Direction.CW);
                glyph.moveTo(17.7f,17.5f);glyph.lineTo(23.7f,23.5f);break;
            case 2:
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);
                glyph.addCircle(11.9f,11.9f,7.8f,Path.Direction.CW);
                glyph.moveTo(18.2f,18.2f);glyph.lineTo(23.3f,23.3f);break;
            case 3:
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f);
                glyph.addCircle(12.2f,12f,7.4f,Path.Direction.CW);
                glyph.moveTo(12.2f,8.5f);glyph.lineTo(12.2f,15.5f);
                glyph.moveTo(8.7f,12);glyph.lineTo(15.7f,12);
                glyph.moveTo(17.8f,17.8f);glyph.lineTo(23.1f,23.1f);break;
            case 4:
                paint.setStyle(Paint.Style.FILL);
                glyph.addCircle(11.4f,11.4f,6.1f,Path.Direction.CW);
                glyph.moveTo(14,15.5f);glyph.lineTo(18.8f,20.3f);
                glyph.lineTo(21.2f,18f);glyph.lineTo(16.5f,13.7f);
                glyph.close();break;
            case 5:
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.9f);
                glyph.moveTo(7,4.8f);glyph.lineTo(4.8f,4.8f);glyph.lineTo(4.8f,7);
                glyph.moveTo(4.8f,18.8f);glyph.lineTo(4.8f,21);glyph.lineTo(7,21);
                glyph.moveTo(18.8f,4.8f);glyph.lineTo(21,4.8f);glyph.lineTo(21,7);
                glyph.moveTo(18.8f,21);glyph.lineTo(21,21);glyph.lineTo(21,18.8f);
                glyph.addCircle(11.4f,11.4f,5.9f,Path.Direction.CW);
                glyph.moveTo(15.8f,15.8f);glyph.lineTo(21,21);break;
            default:
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.35f);
                glyph.addCircle(12.2f,12f,7.1f,Path.Direction.CW);
                glyph.moveTo(17.7f,17.5f);glyph.lineTo(23.7f,23.5f);
        }
        applyPaint(paint,d.foreground,0,0,28,28);
        c.drawPath(glyph,paint);
        paint.setShader(null);c.restore();
    }

    private static void makeShape(Path p,int type){
        switch(type){
            case 1:p.addRoundRect(new RectF(0,0,100,100),26,26,Path.Direction.CW);break;
            case 2:p.addRoundRect(new RectF(0,20,100,80),50,50,Path.Direction.CW);break;
            case 3:poly(p,new float[]{25,7,75,7,100,50,75,93,25,93,0,50});break;
            case 4:poly(p,new float[]{50,0,100,50,50,100,0,50});break;
            case 5:poly(p,new float[]{50,0,62,31,95,35,70,57,78,91,50,73,22,91,30,57,5,35,38,31});break;
            default:p.addCircle(50,50,50,Path.Direction.CW);
        }
    }
    private static void poly(Path p,float[] values){
        p.moveTo(values[0],values[1]);
        for(int i=2;i<values.length;i+=2)p.lineTo(values[i],values[i+1]);
        p.close();
    }
    static void applyPaint(Paint dst,BubbleDesign.ColorSpec spec,float l,float t,float r,float b){
        dst.setShader(null);dst.setColor(Color.WHITE);
        if(spec.mode==0){dst.setColor(spec.solid);return;}
        if(spec.mode==1){
            float rad=(float)Math.toRadians(spec.angle),dx=(float)Math.sin(rad),dy=-(float)Math.cos(rad);
            float cx=(l+r)/2,cy=(t+b)/2;
            float half=(Math.abs(dx)*(r-l)+Math.abs(dy)*(b-t))/2;
            dst.setShader(new LinearGradient(cx-dx*half,cy-dy*half,cx+dx*half,cy+dy*half,
                    spec.start,spec.end,Shader.TileMode.CLAMP));
            return;
        }
        Bitmap bmp=freeBitmap(spec);
        Matrix matrix=new Matrix();matrix.setScale((r-l)/bmp.getWidth(),(b-t)/bmp.getHeight());
        matrix.postTranslate(l,t);
        BitmapShader shader=new BitmapShader(bmp,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);dst.setShader(shader);
    }
    private static Bitmap freeBitmap(BubbleDesign.ColorSpec spec){
        String key=spec.json().toString();
        synchronized(FREE_CACHE){
            Bitmap b=FREE_CACHE.get(key);if(b!=null&&!b.isRecycled())return b;
        }
        final int size=128;
        int[] data=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){
            float nx=x/(float)(size-1),ny=y/(float)(size-1);
            double sum=0,rr=0,gg=0,bb=0;
            for(BubbleDesign.Point p:spec.points){
                double dx=nx-p.x,dy=ny-p.y;
                double spread=.085+.0035*p.radius;
                double weight=Math.exp(-(dx*dx+dy*dy)/(2*spread*spread))+.012;
                sum+=weight;rr+=Color.red(p.color)*weight;gg+=Color.green(p.color)*weight;bb+=Color.blue(p.color)*weight;
            }
            if(sum==0)sum=1;
            data[y*size+x]=Color.rgb((int)(rr/sum+.5),(int)(gg/sum+.5),(int)(bb/sum+.5));
        }
        Bitmap bmp=Bitmap.createBitmap(data,size,size,Bitmap.Config.ARGB_8888);
        synchronized(FREE_CACHE){FREE_CACHE.put(key,bmp);}
        return bmp;
    }
}