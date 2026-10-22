package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Android geometry, real model and UI-thread regressions without dictionary data. */
final class OcrSelectionRegressionValidation {
    private int checks;
    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static String run(Instrumentation instrumentation) throws Exception {
        OcrSelectionRegressionValidation test = new OcrSelectionRegressionValidation();
        test.englishHitTolerance();
        test.geometryFallbacks();
        test.refinementFallback(instrumentation);
        test.backgroundDispatch(instrumentation);
        return "OCR selection regression: " + test.checks + " assertions passed\n";
    }
    static String models(Instrumentation instrumentation) throws Exception {
        OcrSelectionRegressionValidation test = new OcrSelectionRegressionValidation();
        StringBuilder measured = new StringBuilder();
        try {
            test.realModels(instrumentation, measured);
            return "OCR real-model regression: " + test.checks + " assertions passed\n" + measured;
        } catch (Throwable failure) {
            throw new AssertionError("Real-model regression failed after " + test.checks + " checks; measurements:\n"
                    + measured + "Failure: " + failure.getMessage(), failure);
        }
    }
    private void englishHitTolerance() {
        MultilingualOcrRecognizer.Region line = symbols("the first place", 100, 40);
        MultilingualOcrRecognizer.Region lower = symbols("another row", 100, 70);
        List<MultilingualOcrRecognizer.Region> regions = Arrays.asList(line, lower);
        OcrSweepSelection sweep = new OcrSweepSelection();
        sweep.move(regions, 145, 38, 12, true);
        check(sweep.combined() != null && "first".equals(sweep.combined().text),
                "First English hit tolerates a pointer two screen pixels above the glyph bounds");
        for (int round=0; round<20; round++) {
            sweep.move(regions, 185, 62, 12, true);
            sweep.move(regions, 145, 38, 12, true);
            check(sweep.combined() != null && "first".equals(sweep.combined().text),
                    "Repeated movement and return retain one complete word across slight vertical offsets");
            check(sweep.bounds().equals(new Rect(140,40,190,60)), "Tolerance never expands actual selected word bounds");
        }
        sweep.move(regions, 135, 50, 12, true);
        check("first".equals(sweep.combined().text), "An equidistant word gap retains the previous nearby selection");
        sweep.move(regions, 125, 50, 12, true);
        check("the".equals(sweep.combined().text), "An actual hit on an adjacent word replaces the previous word");
        sweep.move(regions, 145, 75, 12, true);
        check("another".equals(sweep.combined().text), "An actual hit in another row selects only that row's word");
        sweep.move(regions, 600, 400, 12, true);
        check(sweep.combined() == null, "Moving far away clears a previous English hit");
        sweep.move(regions, 145, 38, 0, true);
        check(sweep.combined() == null, "An explicitly zero tolerance remains an exact hit test");
        MultilingualOcrRecognizer.Region left = new MultilingualOcrRecognizer.Region("left",new Rect(0,0,40,20));
        MultilingualOcrRecognizer.Region right = new MultilingualOcrRecognizer.Region("right",new Rect(40,0,90,20));
        check(MultilingualOcrRecognizer.regionAt(Arrays.asList(left,right),40,10,0)==right,
                "A shared character edge belongs to the containing word, using exclusive right bounds");
        check(MultilingualOcrRecognizer.regionAt(Collections.singletonList(left),40,10,0)==null,
                "Zero tolerance excludes a word's right edge");
        check(MultilingualOcrRecognizer.regionAt(Collections.singletonList(left),10,20,0)==null,
                "Zero tolerance excludes a word's bottom edge");
    }
    private void geometryFallbacks() {
        OcrSweepSelection sweep = new OcrSweepSelection();
        MultilingualOcrRecognizer.Region whole = new MultilingualOcrRecognizer.Region("first", new Rect(140,40,190,60));
        sweep.move(Collections.singletonList(whole), 145, 38, 12, true);
        check(sweep.combined() != null && "first".equals(sweep.combined().text),
                "A complete model word without glyph geometry retains safe element-level tolerance");
        MultilingualOcrRecognizer.Region partial = symbols("first",140,40);
        partial.symbols.remove(0);
        sweep.clear(); sweep.move(Collections.singletonList(partial),145,50,12,true);
        check(sweep.combined()!=null && "first".equals(sweep.combined().text) && sweep.bounds().equals(partial.bounds),
                "Partial glyph geometry falls back to the complete single-word element instead of losing its first glyph");
        MultilingualOcrRecognizer.Region line = new MultilingualOcrRecognizer.Region("the first place",new Rect(100,40,250,60));
        sweep.clear(); sweep.move(Collections.singletonList(line),145,50,12,true);
        check(sweep.combined()==null, "A multiword line without word or glyph geometry never guesses a token offset");
        line.symbols.add(new MultilingualOcrRecognizer.Symbol(0,line.text.length(),line.bounds));
        sweep.move(Collections.singletonList(line),145,50,12,true);
        check(sweep.combined()==null, "A grouped symbol spanning whitespace cannot masquerade as a word's exact bounds");
        sweep.move(Arrays.asList(line,whole),145,50,12,true);
        check("first".equals(sweep.combined().text), "A same-frame whole-word element recovers an unsplittable geometryless line");
        for (String text : new String[]{"中文", "日本語", "한국어", "中文 father 日本語 한국어"}) {
            MultilingualOcrRecognizer.Region mixed = new MultilingualOcrRecognizer.Region(text,new Rect(100,40,400,70));
            sweep.clear(); sweep.move(Collections.singletonList(mixed),140,38,12);
            check(sweep.combined()!=null && text.equals(sweep.combined().text),
                    "General CJK and mixed selection keeps the original model text without glyph geometry: " + text);
        }
        MultilingualOcrRecognizer.Region typo = symbols("girst",140,40);
        sweep.clear(); sweep.move(Collections.singletonList(typo),145,50,12,true);
        check("girst".equals(sweep.combined().text), "Selection never guesses first from the raw OCR token girst");
    }
    private void refinementFallback(Instrumentation test) throws Exception {
        TextRecognizer model = fakeModel(() -> Tasks.forResult(new Text("",Collections.emptyList())));
        MultilingualOcrRecognizer recognizer = new MultilingualOcrRecognizer(model,model,model,null);
        Bitmap image = Bitmap.createBitmap(320,160,Bitmap.Config.ARGB_8888);
        try {
            for (String text : new String[]{"first", "girst", "中文", "日本語", "한국어", "中文 father 日本語 한국어"}) {
                MultilingualOcrRecognizer.Region seed = new MultilingualOcrRecognizer.Region(text,new Rect(100,40,150,60));
                seed.boundedRange=true; seed.selectedBounds.add(new Rect(seed.bounds));
                check(MultilingualOcrRecognizer.selectionResult(Collections.emptyList(),seed,125,38,12)==seed,
                        "Empty candidate filtering retains an existing valid preview seed: " + text);
                AtomicReference<String> result = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>();
                CountDownLatch complete = new CountDownLatch(1);
                test.runOnMainSync(() -> recognizer.recognizeSelection(image,seed.bounds,seed,125,38,12,
                        OcrLanguageSettings.Language.ENGLISH,new MultilingualOcrRecognizer.Callback() {
                            public void onSuccess(String value) { result.set(value); complete.countDown(); }
                            public void onFailure(Exception failure) { error.set(failure); complete.countDown(); }
                        }));
                check(complete.await(5,TimeUnit.SECONDS), "Both empty refinement variants finish with a bounded callback");
                check(error.get()==null && text.equals(result.get()), "Empty local refinement returns the valid preview verbatim: " + text);
            }
        } finally { test.runOnMainSync(recognizer::close); image.recycle(); }
    }
    private void backgroundDispatch(Instrumentation test) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicBoolean processOnMain = new AtomicBoolean(), callbackOnMain = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        TextRecognizer model = (TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class}, (proxy, method, arguments) -> {
                    if ("process".equals(method.getName())) {
                        processOnMain.set(Looper.myLooper()==Looper.getMainLooper()); entered.countDown();
                        if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic SDK gate timed out");
                        return Tasks.forResult(new Text("",Collections.emptyList()));
                    }
                    if ("close".equals(method.getName())) closes.incrementAndGet();
                    return null;
                });
        MultilingualOcrRecognizer recognizer = new MultilingualOcrRecognizer(model,model,model,null);
        Bitmap image = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        try {
            long start = SystemClock.uptimeMillis();
            test.runOnMainSync(() -> recognizer.recognizeRegions(image,OcrLanguageSettings.Language.ENGLISH,
                    new MultilingualOcrRecognizer.RegionsCallback() {
                        public void onSuccess(List<MultilingualOcrRecognizer.Region> regions) { done(); }
                        public void onFailure(Exception error) { done(); }
                        private void done() { callbackOnMain.set(Looper.myLooper()==Looper.getMainLooper());completed.countDown(); }
                    }));
            check(SystemClock.uptimeMillis()-start<1000, "A blocked SDK entry returns control to the UI immediately");
            check(entered.await(3,TimeUnit.SECONDS) && !processOnMain.get(), "Synchronous model process entry runs on the OCR worker");
            AtomicBoolean responsive = new AtomicBoolean();
            test.runOnMainSync(() -> { responsive.set(true); recognizer.close(); });
            check(responsive.get() && closes.get()==0, "Main-thread gestures remain responsive and active model clients stay alive until completion");
            // A replacement recognizer must not queue behind the retired worker.
            TextRecognizer quickModel = fakeModel(() -> Tasks.forResult(new Text("first",Collections.emptyList())));
            MultilingualOcrRecognizer replacement = new MultilingualOcrRecognizer(quickModel,quickModel,quickModel,null);
            CountDownLatch replaced = new CountDownLatch(1);
            try {
                test.runOnMainSync(() -> replacement.recognize(image,new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String text) { replaced.countDown(); }
                    public void onFailure(Exception error) { replaced.countDown(); }
                }));
                check(replaced.await(3,TimeUnit.SECONDS), "A replacement recognizer completes while an old SDK entry remains blocked");
            } finally { test.runOnMainSync(replacement::close); }
            release.countDown();
            check(completed.await(3,TimeUnit.SECONDS) && callbackOnMain.get(), "OCR completion returns to the main thread");
            for (int attempt=0;attempt<100 && closes.get()<3;attempt++) Thread.sleep(5);
            check(closes.get()==3, "All reused model clients close once the retired physical operation returns");
        } finally {
            release.countDown(); completed.await(3,TimeUnit.SECONDS);
            test.runOnMainSync(recognizer::close); image.recycle();
        }
    }
    private void realModels(Instrumentation test, StringBuilder measured) throws Exception {
        AtomicReference<MultilingualOcrRecognizer> holder = new AtomicReference<>();
        test.runOnMainSync(() -> holder.set(new MultilingualOcrRecognizer()));
        MultilingualOcrRecognizer recognizer = holder.get();
        try {
            Bitmap english = render("the first place", "another second line");
            try {
                long start = SystemClock.uptimeMillis();
                List<MultilingualOcrRecognizer.Region> words = recognize(test,recognizer,english,OcrLanguageSettings.Language.ENGLISH,false);
                measured.append("English raw=").append(text(words)).append("; ms=").append(SystemClock.uptimeMillis()-start).append('\n');
                MultilingualOcrRecognizer.Region first = null;
                for (MultilingualOcrRecognizer.Region region : words) if ("first".equals(region.text)) first=region;
                check(first!=null, "Bundled Latin model detects first in a real bitmap containing two English lines");
                OcrSweepSelection selection = new OcrSweepSelection();
                for (int round=0;round<20;round++) {
                    selection.clear();
                    selection.move(words,first.bounds.exactCenterX(),first.bounds.top-2,12,true);
                    check(selection.combined()!=null && "first".equals(selection.combined().text),
                            "Real ML Kit geometry tolerates an initial two-pixel vertical offset on every repeat");
                    selection.move(words,first.bounds.right-2,first.bounds.top-2,12,true);
                    selection.move(words,first.bounds.left+2,first.bounds.top-2,12,true);
                    check("first".equals(selection.combined().text), "Real word movement and return never select the adjacent word or row");
                }
                List<MultilingualOcrRecognizer.Region> sentences = recognize(test,recognizer,english,OcrLanguageSettings.Language.ENGLISH,true);
                check(text(sentences).contains("the first place"), "English sentence mode retains the complete first line");
                MultilingualOcrRecognizer.Region target = first;
                CountDownLatch recovered = new CountDownLatch(1); AtomicReference<String> recoveredText = new AtomicReference<>();
                AtomicReference<Exception> recoveryError = new AtomicReference<>();
                test.runOnMainSync(() -> recognizer.recognizeSelection(english,target.bounds,null,
                        target.bounds.exactCenterX(),target.bounds.top-2,12,OcrLanguageSettings.Language.ENGLISH,
                        new MultilingualOcrRecognizer.Callback() {
                            public void onSuccess(String value) { recoveredText.set(value);recovered.countDown(); }
                            public void onFailure(Exception error) { recoveryError.set(error);recovered.countDown(); }
                        }));
                check(recovered.await(20,TimeUnit.SECONDS), "Geometryless-preview local recovery completes without a seed");
                check(recoveryError.get()==null && "first".equals(recoveredText.get()),
                        "Enlarged local OCR restores a word when a multiword preview could not safely locate it, preserving inverse screen geometry");
                measured.append("English seedless local refine=").append(recoveredText.get()).append('\n');
            } finally { english.recycle(); }
            pointerRendering(test, recognizer, measured);
            String[] texts={"中文识别", "日本語の辞書", "中文 father 日本語"};
            OcrLanguageSettings.Language[] languages={OcrLanguageSettings.Language.CHINESE,
                    OcrLanguageSettings.Language.JAPANESE,OcrLanguageSettings.Language.MIXED};
            for (int i=0;i<texts.length;i++) {
                Bitmap image=render(texts[i],null);
                try {
                    long start=SystemClock.uptimeMillis();
                    List<MultilingualOcrRecognizer.Region> regions=recognize(test,recognizer,image,languages[i],true);
                    String raw=text(regions);
                    measured.append(languages[i].label).append(" raw=").append(raw).append("; ms=").append(SystemClock.uptimeMillis()-start).append('\n');
                    if (i<2) check(texts[i].equals(raw), "Bundled language model preserves the complete original sample: " + texts[i]);
                    if (i==2) {
                        List<MultilingualOcrRecognizer.Region> elementRegions = recognize(test,recognizer,image,languages[i],false);
                        String elementText = text(elementRegions);
                        measured.append("Mixed word elements raw=").append(elementText).append('\n');
                        check(elementText.contains("中文") && elementText.contains("father")
                                        && elementText.contains("日本語"),
                                "Mixed word elements retain Chinese, Latin and Japanese text fragments");
                        measured.append("Mixed sentence exact sample match=").append(texts[i].equals(raw))
                                .append(" (quality probe; known baseline limitation, not an accuracy pass)\n");
                    }
                } finally { image.recycle(); }
            }
        } finally { test.runOnMainSync(recognizer::close); }
    }
    private void pointerRendering(Instrumentation test, MultilingualOcrRecognizer recognizer, StringBuilder measured) throws Exception {
        Context context = test.getTargetContext();
        float density = context.getResources().getDisplayMetrics().density;
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setTextSize(48); ink.setColor(Color.BLACK);
        ink.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float wordLeft = 100+ink.measureText("the ");
        AtomicReference<OcrPickupController> owner = new AtomicReference<>();
        AtomicReference<View> pointer = new AtomicReference<>();
        onMain(test, () -> {
            OcrPickupController controller = new OcrPickupController(context, new OcrPickupController.Host() {
                public View anchor() { throw new AssertionError("Bitmap-only fixture has no window"); }
                public Rect position() { return new Rect(); }
                public Rect move(int x,int y) { return new Rect(); }
                public void save() {}
                public void tap() {}
                public void lookup(String text) { throw new AssertionError("Rendering must not start a lookup"); }
            });
            owner.set(controller);
            try {
                Class<?> type = Class.forName("com.yuen.jpdict.OcrPickupController$SelectionView");
                java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(OcrPickupController.class, Context.class);
                constructor.setAccessible(true); pointer.set((View)constructor.newInstance(controller,context));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        try {
            for (int[] size : new int[][]{{1000,260,100,190},{1080,2424,500,580}}) {
                Bitmap plain = Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888); plain.eraseColor(Color.WHITE);
                Canvas canvas = new Canvas(plain);
                canvas.drawText("the first place",100,size[2],ink); canvas.drawText("another second line",100,size[3],ink);
                float y = Math.round(size[2]+(ink.ascent()+ink.descent())/2);
                try {
                    List<MultilingualOcrRecognizer.Region> base = recognize(test,recognizer,plain,OcrLanguageSettings.Language.ENGLISH,false);
                    measured.append("Pointer ").append(size[0]).append('x').append(size[1]).append(" none raw=").append(text(base)).append('\n');
                    check(text(base).contains("first"), "The clean pointer fixture recognizes first before adding any overlay");
                    // Historical white outline is a counterfactual fixture only;
                    // the repaired pointer below is drawn by the actual View.
                    Bitmap legacy = plain.copy(Bitmap.Config.ARGB_8888,true);
                    try {
                        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG); outline.setStyle(Paint.Style.STROKE);
                        float center = Math.round(wordLeft+ink.measureText("first")/2);
                        outline.setStrokeWidth(3*density); outline.setColor(Color.WHITE);
                        new Canvas(legacy).drawCircle(center,y,4*density,outline);
                        outline.setStrokeWidth(1.5f*density); outline.setColor(0xff168af2);
                        new Canvas(legacy).drawCircle(center,y,4*density,outline);
                        List<MultilingualOcrRecognizer.Region> old = recognize(test,recognizer,legacy,OcrLanguageSettings.Language.ENGLISH,false);
                        measured.append("Pointer legacy white outline raw=").append(text(old)).append('\n');
                    } finally { legacy.recycle(); }
                    for (int point=0;point<9;point++) {
                        float x = point<5 ? Math.round(wordLeft+(ink.measureText("first".substring(0,point))
                                +ink.measureText("first".substring(0,point+1)))/2)
                                : Math.round(wordLeft+ink.measureText("first".substring(0,point-4)));
                        Bitmap frame = plain.copy(Bitmap.Config.ARGB_8888,true);
                        try {
                            onMain(test, () -> {
                                try {
                                    java.lang.reflect.Field aimX = OcrPickupController.class.getDeclaredField("aimX");
                                    java.lang.reflect.Field aimY = OcrPickupController.class.getDeclaredField("aimY");
                                    aimX.setAccessible(true); aimY.setAccessible(true);
                                    aimX.setFloat(owner.get(),x); aimY.setFloat(owner.get(),y);
                                    pointer.get().layout(0,0,size[0],size[1]); pointer.get().draw(new Canvas(frame));
                                    java.lang.reflect.Field markerField = pointer.get().getClass().getDeclaredField("marker");
                                    markerField.setAccessible(true);
                                    View marker = (View)markerField.get(pointer.get());
                                    int edge = Math.round(44*density); marker.layout(0,0,edge,edge);
                                    Canvas markerCanvas = new Canvas(frame);
                                    markerCanvas.translate(x-edge/2f,y-edge/2f); marker.draw(markerCanvas);
                                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                            });
                            List<MultilingualOcrRecognizer.Region> regions = recognize(test,recognizer,frame,OcrLanguageSettings.Language.ENGLISH,false);
                            MultilingualOcrRecognizer.Region selected = OcrSweepSelection.englishWordAt(regions,x,y,12*density);
                            measured.append("Pointer actual SelectionView point=").append(point).append(" raw=").append(text(regions)).append('\n');
                            check(selected!=null && "first".equals(selected.text),
                                    "The real pointer View preserves first at every letter center and inter-letter boundary; point="+point);
                        } finally { frame.recycle(); }
                    }
                } finally { plain.recycle(); }
            }
        } finally { onMain(test, () -> { if (owner.get()!=null) owner.get().close(); }); }
    }
    private static void onMain(Instrumentation test, Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get()!=null) throw new AssertionError(error.get());
    }
    private List<MultilingualOcrRecognizer.Region> recognize(Instrumentation test,MultilingualOcrRecognizer recognizer,
            Bitmap image,OcrLanguageSettings.Language language,boolean sentences) throws Exception {
        CountDownLatch complete=new CountDownLatch(1);
        AtomicReference<List<MultilingualOcrRecognizer.Region>> result=new AtomicReference<>();
        AtomicReference<Exception> failure=new AtomicReference<>();
        test.runOnMainSync(() -> recognizer.recognizeRegions(image,language,sentences,new MultilingualOcrRecognizer.RegionsCallback() {
            public void onSuccess(List<MultilingualOcrRecognizer.Region> value) { result.set(value);complete.countDown(); }
            public void onFailure(Exception error) { failure.set(error);complete.countDown(); }
        }));
        check(complete.await(20,TimeUnit.SECONDS), "Real-model recognition completes within the instrumentation timeout");
        if (failure.get()!=null) throw failure.get();
        return result.get();
    }
    private static TextRecognizer fakeModel(Callable<Object> response) {
        return (TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),new Class<?>[]{TextRecognizer.class},
                (proxy,method,arguments) -> "process".equals(method.getName()) ? response.call() : null);
    }
    private static Bitmap render(String first,String second) {
        Bitmap image=Bitmap.createBitmap(1000,260,Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(image); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK); paint.setTextSize(48); paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        canvas.drawText(first,40,100,paint); if (second!=null) canvas.drawText(second,40,190,paint);
        return image;
    }
    private static String text(List<MultilingualOcrRecognizer.Region> regions) {
        StringBuilder text=new StringBuilder();
        for (MultilingualOcrRecognizer.Region region : regions) { if(text.length()>0)text.append(" | "); text.append(region.text); }
        return text.toString();
    }
    private static MultilingualOcrRecognizer.Region symbols(String text, int left, int top) {
        MultilingualOcrRecognizer.Region region = new MultilingualOcrRecognizer.Region(text,
                new Rect(left, top, left + text.length()*10, top+20));
        for (int i=0; i<text.length(); i++) if (!Character.isWhitespace(text.charAt(i)))
            region.symbols.add(new MultilingualOcrRecognizer.Symbol(i, i+1,
                    new Rect(left+i*10, top, left+(i+1)*10, top+20)));
        return region;
    }
}
