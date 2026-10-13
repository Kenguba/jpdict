package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Native Canvas text fixtures for Chinese, Japanese and English, using the shared renderer. */
final class BubbleTextRenderingValidation {
    private BubbleTextRenderingValidation() { }
    static int run(Instrumentation instrumentation) {
        Context context=instrumentation.getTargetContext();
        AtomicReference<Throwable> failure=new AtomicReference<>(); int[] checks={0};
        instrumentation.runOnMainSync(()->{
            try {
                String[] scripts={"chinese","japanese","english"},labels={"中文追加","日本語追加","Search"};
                for (int i=0;i<labels.length;i++) {
                    FloatingBubbleAppearance.Builder builder=new FloatingBubbleAppearance.Builder();
                    builder.style=FloatingBubbleAppearance.STYLE_CUSTOM; builder.opacityPercent=100; builder.text=labels[i];
                    FloatingBubbleAppearance.Config complete=builder.build();
                    builder.text=BubbleAppearanceRenderer.displayText(complete);
                    FloatingBubbleAppearance.Config two=builder.build();
                    Bitmap full=render(context,complete),shortened=render(context,two);
                    if (!full.sameAs(shortened)) throw new AssertionError("Text renders beyond two codepoints: "+scripts[i]);
                    checks[0]++;
                    if (!complete.text.equals(labels[i])) throw new AssertionError("Persistence label was truncated");
                    checks[0]++;
                    builder=new FloatingBubbleAppearance.Builder(complete); builder.legacyVisual=true;
                    Bitmap legacy=render(context,builder.build());
                    if (legacy.sameAs(full)) throw new AssertionError("Legacy full text was replaced");
                    checks[0]++;
                    Bitmap comparison=Bitmap.createBitmap(640,300,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(comparison); canvas.drawColor(0xfff2f2f2);
                    Paint heading=new Paint(Paint.ANTI_ALIAS_FLAG); heading.setColor(0xff171819); heading.setTextSize(18);
                    canvas.drawText(scripts[i]+" | HTML preview 14px / 48px",16,27,heading);
                    canvas.drawBitmap(full,16,44,null); canvas.drawBitmap(shortened,224,44,null); canvas.drawBitmap(legacy,432,44,null);
                    canvas.drawText("new complete label",16,274,heading); canvas.drawText("new first 2",224,274,heading); canvas.drawText("legacy full",432,274,heading);
                    File directory=context.getExternalFilesDir("v18-qa");
                    if (directory==null || !directory.isDirectory()&&!directory.mkdirs()) throw new AssertionError("Text fixture output unavailable");
                    try (FileOutputStream out=new FileOutputStream(new File(directory,"native-text-"+scripts[i]+".png"))) {
                        if (!comparison.compress(Bitmap.CompressFormat.PNG,100,out)) throw new AssertionError("Text fixture write failed");
                    }
                    full.recycle(); shortened.recycle(); legacy.recycle(); comparison.recycle();
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get()!=null) throw new AssertionError("Native text rendering checks failed",failure.get());
        return checks[0];
    }
    private static Bitmap render(Context context,FloatingBubbleAppearance.Config config) {
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        BubbleAppearanceRenderer.draw(new Canvas(bitmap),context,config,new RectF(0,0,192,192),null);
        return bitmap;
    }
}
