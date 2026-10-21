package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.media.Image;
import android.media.ImageWriter;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Platform-only interaction, bitmap ownership and optional real-model font probes. */
final class OcrValidation {
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation, boolean fonts) throws Exception {
        OcrValidation test = new OcrValidation();
        test.mainFailureReporting(instrumentation); test.images(); test.selection(); test.repeatedCapture(instrumentation); test.canceledCapture(instrumentation); test.queuedLanguageSnapshot(instrumentation); test.nonPreviewRequestsSerialized(instrumentation); test.capturePhaseTimeoutDoesNotRetireModel(instrumentation); test.stalledPreviewRecovery(instrumentation); test.captureStopRecovery(instrumentation); test.stoppedCaptureCallbacks(instrumentation); test.repeatedStallStopsCapture(instrumentation); test.gestures(instrumentation);
        String measured = fonts ? test.fonts(instrumentation) : "";
        return "OCR: " + test.checks + " assertions passed\n" + OcrLanguageValidation.run(instrumentation) + measured;
    }
    private void mainFailureReporting(Instrumentation instrumentation) {
        AssertionError expected = new AssertionError("UI assertion probe");
        Throwable delivered = null;
        try { onMain(instrumentation, () -> { throw expected; }); }
        catch (Throwable failure) { delivered = failure; }
        check(delivered == expected, "UI assertion failures return to the instrumentation thread without terminating the app process");
    }
    private void images() {
        Bitmap screen = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888);
        screen.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(screen); Paint ink = new Paint(); ink.setColor(Color.WHITE);
        canvas.drawRect(103, 201, 117, 211, ink);
        Rect target = new Rect(100, 200, 120, 212);
        try (OcrImagePreparation normal = OcrImagePreparation.prepare(screen, target, false);
             OcrImagePreparation enhanced = OcrImagePreparation.prepare(screen, target, true)) {
            check(normal.image.getHeight() > normal.crop.height(), "Small text is enlarged before recognition");
            check(normal.image.getWidth()*normal.image.getHeight() <= OcrImagePreparation.MAX_PIXELS, "Prepared crop obeys the pixel/memory bound");
            check(Color.red(enhanced.image.getPixel(0, 0)) > 240, "Dark background is normalized to light background");
            check(Color.red(enhanced.image.getPixel(enhanced.image.getWidth()/2, enhanced.image.getHeight()/2)) < 20, "Light text becomes dark without thresholding glyph edges");
            Rect mapped = normal.toScreen(new Rect(0, 0, normal.image.getWidth(), normal.image.getHeight()));
            check(mapped.equals(normal.crop), "Recognition coordinates map exactly back to original screen pixels");
        }
        try (OcrImagePreparation clipped = OcrImagePreparation.prepare(screen, new Rect(-50, -30, 1024, 510), true)) {
            check(clipped.crop.left == 0 && clipped.crop.top == 0 && clipped.crop.right == 1024, "Edge crops are clamped to the source image");
            check(clipped.image.getWidth()*clipped.image.getHeight() <= OcrImagePreparation.MAX_PIXELS, "Large crop stays bounded");
        }
        check(!screen.isRecycled(), "Preparing and closing variants never recycle the source capture");
        OcrPreviewFrame frame = new OcrPreviewFrame(42, screen);
        check(frame.borrow(41) == null, "Old sessions cannot borrow the current screenshot");
        check(frame.borrow(42) == screen, "Current recognition borrows the exact captured image");
        frame.close(); check(!screen.isRecycled(), "Cancellation retains a bitmap still used by recognition");
        frame.release(); check(screen.isRecycled(), "Last recognition release disposes a canceled screenshot");
        check(frame.borrow(42) == null, "Disposed snapshots cannot be refined again");
        check("向上滑动".equals(OcrPickupController.swipeDirection(1, -100, 32)), "Up swipe");
        check("向下滑动".equals(OcrPickupController.swipeDirection(1, 100, 32)), "Down swipe");
        check("向左滑动".equals(OcrPickupController.swipeDirection(-100, 1, 32)), "Left swipe");
        check("向右滑动".equals(OcrPickupController.swipeDirection(100, 1, 32)), "Right swipe");
        check(OcrPickupController.swipeDirection(10, 10, 32) == null, "Small finger jitter does not emit directional Toast");
    }
    private void selection() {
        OcrSweepSelection sweep = new OcrSweepSelection();
        MultilingualOcrRecognizer.Region first = new MultilingualOcrRecognizer.Region("first", new Rect(0, 0, 50, 20));
        MultilingualOcrRecognizer.Region second = new MultilingualOcrRecognizer.Region("second", new Rect(55, 0, 110, 20));
        sweep.touch(second, OcrPickupMode.WORD); sweep.touch(first, OcrPickupMode.WORD); sweep.touch(second, OcrPickupMode.WORD);
        check(sweep.picked.size() == 2 && "first second".equals(sweep.combined().text), "Repeated pointer sweeps retain nearby words once, in reading order");
        check(sweep.bounds().equals(new Rect(0, 0, 110, 20)), "Sweep refinement uses exactly the union of selected text bounds");
        sweep.touch(first, OcrPickupMode.ENGLISH);
        check(sweep.picked.size() == 2, "Repeated points do not duplicate existing selection");
        sweep.clear(); sweep.touch(first, OcrPickupMode.ENGLISH); sweep.touch(second, OcrPickupMode.ENGLISH);
        check(sweep.picked.size() == 2 && "first second".equals(sweep.combined().text), "Generic sweep still supports intentionally selected phrases");
        MultilingualOcrRecognizer.Region line = symbols("the first place", 100, 40);
        MultilingualOcrRecognizer.Region lower = symbols("another", 100, 70);
        List<MultilingualOcrRecognizer.Region> twoLines = Arrays.asList(line, lower);
        sweep.clear(); sweep.move(twoLines, 145, 50, 12, true);
        check("first".equals(sweep.combined().text) && sweep.bounds().equals(new Rect(140, 40, 190, 60)),
                "English pointer inside first extracts only the word from a whole-line OCR element");
        sweep.move(twoLines, 135, 50, 12, true); sweep.move(twoLines, 185, 50, 12, true);
        check("first".equals(sweep.combined().text), "Whitespace and return movement never greedily combine words");
        sweep.move(twoLines, 145, 65, 12, true);
        check("first".equals(sweep.combined().text), "Line gaps cannot select text from the next line using tolerance");
        sweep.move(twoLines, 145, 75, 12, true);
        check("another".equals(sweep.combined().text), "An actual pointer hit in another row can intentionally change selection");
        sweep.clear();
        MultilingualOcrRecognizer.Region withoutGlyphs = new MultilingualOcrRecognizer.Region("the first place", line.bounds);
        sweep.move(Collections.singletonList(withoutGlyphs), 145, 50, 12, true);
        check(sweep.picked.isEmpty(), "A line lacking character geometry is never guessed into a wrong word");
        sweep.move(Collections.singletonList(first), 25, 10, 12, true);
        check("first".equals(sweep.combined().text), "Whole-word model elements work without glyph geometry");
        MultilingualOcrRecognizer.Region linked = symbols("well-being", 100, 40);
        sweep.clear(); sweep.move(Collections.singletonList(linked), 145, 50, 12, true);
        check("well-being".equals(sweep.combined().text), "In-word hyphens remain within a single English token");
        MultilingualOcrRecognizer.Region distant = new MultilingualOcrRecognizer.Region("distant", new Rect(700, 500, 760, 520));
        sweep.touch(distant, OcrPickupMode.WORD);
        check(sweep.picked.size() == 1 && "distant".equals(sweep.combined().text), "Moving to a distant region clears the old sweep");
        MultilingualOcrRecognizer.Region mixed = symbols("这是father父亲", 100, 40);
        List<MultilingualOcrRecognizer.Region> regions = Collections.singletonList(mixed);
        sweep.clear(); sweep.move(regions, 125, 50, 0);
        check(mixed.text.equals(sweep.combined().text), "Dwell alone keeps the complete model element");
        sweep.move(regions, 129, 50, 0); sweep.move(regions, 131, 50, 0);
        check(mixed.text.equals(sweep.combined().text) && !sweep.combined().boundedRange,
                "Initial placement across a glyph boundary keeps the automatically selected complete word");
        sweep.move(regions, 130, 50, 0);
        check(mixed.text.equals(sweep.combined().text), "Sub-threshold return jitter cannot replace an automatic word with a fragment");
        sweep.clear(); sweep.move(regions, 125, 50, 0);
        sweep.move(regions, 175, 50, 0); sweep.move(regions, 125, 50, 0);
        check("father".equals(sweep.combined().text), "One f-to-r-to-f sweep selects just father from mixed Chinese and Latin");
        check(sweep.combined().boundedRange && sweep.bounds().equals(new Rect(120,40,180,60)), "Substring selection uses actual symbol extrema");
        MultilingualOcrRecognizer.Region nested = symbols("father",120,40);
        regions=Arrays.asList(mixed,nested);
        sweep.clear();sweep.move(regions,125,50,0);sweep.move(regions,175,50,0);sweep.move(regions,125,50,0);
        check("father".equals(sweep.combined().text),"Overlapping mixed-script and Latin model regions cannot duplicate the swept word");
        mixed = symbols("あいうえお", 100, 40); regions = Collections.singletonList(mixed);
        sweep.clear(); sweep.move(regions,115,50,0); sweep.move(regions,135,50,0); sweep.move(regions,115,50,0);
        check("いうえ".equals(sweep.combined().text), "Return sweeps can select arbitrary Japanese ranges");
        MultilingualOcrRecognizer.Region latin = symbols("hello",100,40), next = symbols("world",155,40);
        regions = Arrays.asList(latin,next);
        sweep.clear(); sweep.move(regions,105,50,0); sweep.move(regions,175,50,0);
        check("world".equals(sweep.combined().text) && sweep.bounds().equals(next.bounds),
                "One-way placement automatically selects the current word instead of earlier text crossed on the way");
        sweep.clear(); sweep.move(regions,105,50,0); sweep.move(regions,200,50,0); sweep.move(regions,105,50,0);
        check("hello world".equals(sweep.combined().text), "A coalesced return sweep fills all intervening glyphs and adjacent words");
        for (int repeat=0; repeat<10; repeat++) {
            sweep.clear(); sweep.move(regions,175,50,0);
            check("world".equals(sweep.combined().text) && !sweep.combined().boundedRange,
                    "Every new selection restores automatic whole-word selection after a precise return sweep");
            sweep.move(regions,195,50,0); sweep.move(regions,175,50,0);
            check("rld".equals(sweep.combined().text), "Repeated return sweeps keep exact independent glyph ranges");
            check(sweep.bounds().equals(new Rect(175,40,205,60)),
                    "A sweep starting on r's left edge never includes o's adjacent right edge");
        }
        sweep.clear(); sweep.move(regions,195,50,0); sweep.move(regions,175,50,0); sweep.move(regions,195,50,0);
        check("rld".equals(sweep.combined().text), "Shared-edge return selection is independent of horizontal travel direction");
        MultilingualOcrRecognizer.Region vertical = new MultilingualOcrRecognizer.Region("あいう",new Rect(300,100,320,130));
        for (int i=0;i<3;i++) vertical.symbols.add(new MultilingualOcrRecognizer.Symbol(i,i+1,new Rect(300,100+i*10,320,110+i*10)));
        sweep.clear(); regions=Collections.singletonList(vertical);
        sweep.move(regions,310,110,0); sweep.move(regions,310,120,0); sweep.move(regions,310,110,0);
        check("いう".equals(sweep.combined().text) && sweep.bounds().equals(new Rect(300,110,320,130)),
                "Vertical return selection uses exclusive bottom edges and retains only the touched glyphs");
        mixed = symbols("中文father",100,40);
        MultilingualOcrRecognizer.Region cleaned = mixed.clean(OcrPickupMode.ENGLISH);
        check("father".equals(cleaned.text) && cleaned.symbols.size()==6 && cleaned.symbols.get(0).start==0,
                "English filtering preserves exact glyph locations and reindexes substring offsets");
        MultilingualOcrRecognizer.Region seed = new MultilingualOcrRecognizer.Region("abcdefg",new Rect(0,0,70,20));
        seed.boundedRange=true;
        MultilingualOcrRecognizer.Region shortened = new MultilingualOcrRecognizer.Region("abcdef",new Rect(0,0,60,20));shortened.quality=100;
        check(MultilingualOcrRecognizer.selectionResult(Arrays.asList(seed,shortened),seed,5,10,0)==seed,
                "Higher-confidence 6-of-7 refinement cannot shorten a user-defined range despite 85 percent overlap");
        MultilingualOcrRecognizer.Region phrase = new MultilingualOcrRecognizer.Region("helo wurld",new Rect(100,40,205,60));
        phrase.boundedRange=true;
        MultilingualOcrRecognizer.Region hello = symbols("hello",100,40), world = symbols("world",155,40);
        hello.quality=world.quality=1;
        check("hello world".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(hello,world),phrase,110,50,0).text),
                "Refinement combines separate model elements to correct the complete swept phrase");
        check(MultilingualOcrRecognizer.selectionResult(Collections.singletonList(hello),phrase,110,50,0)==phrase,
                "A correct first word alone cannot truncate a swept phrase");
        MultilingualOcrRecognizer.Region geometrylessHello = new MultilingualOcrRecognizer.Region("hello",hello.bounds);
        MultilingualOcrRecognizer.Region geometrylessWorld = new MultilingualOcrRecognizer.Region("world",world.bounds);
        geometrylessHello.quality=geometrylessWorld.quality=1;
        check("hello world".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(geometrylessHello,geometrylessWorld),phrase,110,50,0).text),
                "Models without symbol geometry can still refine all selected words together");
        hello.quality=world.quality=-1;
        check(MultilingualOcrRecognizer.selectionResult(Arrays.asList(hello,world),phrase,110,50,0)==phrase,
                "Lower-quality phrase refinement retains the complete original selection");
        MultilingualOcrRecognizer.Region substring = new MultilingualOcrRecognizer.Region("fether",new Rect(120,40,180,60));
        substring.boundedRange=true;
        MultilingualOcrRecognizer.Region mixedSource=symbols("这是father父亲",100,40), nestedSource=symbols("father",120,40);
        mixedSource.quality=nestedSource.quality=1;
        check("father".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(mixedSource,nestedSource),substring,125,50,0).text),
                "Clipped mixed-script and Latin elements cannot duplicate an improved substring");
        MultilingualOcrRecognizer.Region whole = new MultilingualOcrRecognizer.Region("father",new Rect(0,0,60,20));whole.quality=1;
        MultilingualOcrRecognizer.Region partA = new MultilingualOcrRecognizer.Region("fat",new Rect(0,0,30,20));partA.quality=1;
        MultilingualOcrRecognizer.Region partB = new MultilingualOcrRecognizer.Region("her",new Rect(30,0,60,20));partB.quality=1;
        MultilingualOcrRecognizer.Region fatherSeed = new MultilingualOcrRecognizer.Region("father",whole.bounds);fatherSeed.boundedRange=true;
        check("father".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(whole,partA,partB),fatherSeed,5,10,0).text),
                "Whole-word and split-element alternatives covering the same glyphs cannot concatenate father twice");
        MultilingualOcrRecognizer.Region repeatSeed = new MultilingualOcrRecognizer.Region("had had",new Rect(0,0,70,20));repeatSeed.boundedRange=true;
        MultilingualOcrRecognizer.Region repeatA = new MultilingualOcrRecognizer.Region("had",new Rect(0,0,30,20));repeatA.quality=1;
        MultilingualOcrRecognizer.Region repeatB = new MultilingualOcrRecognizer.Region("had",new Rect(40,0,70,20));repeatB.quality=1;
        check("had had".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(repeatA,repeatB),repeatSeed,5,10,0).text),
                "Identical words at different screen positions remain a valid repeated phrase");
        MultilingualOcrRecognizer.Region lines = new MultilingualOcrRecognizer.Region("helo\nwurld",new Rect(100,40,150,90));
        lines.boundedRange=true;
        lines.selectedBounds.add(new Rect(100,40,150,60));lines.selectedBounds.add(new Rect(100,70,150,90));
        hello=symbols("hello",100,40); world=symbols("world",100,70);hello.quality=world.quality=1;
        check("hello\nworld".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(hello,world),lines,110,50,0).text),
                "Refinement preserves reading order and line breaks across a swept range");
        MultilingualOcrRecognizer.Region sparse = new MultilingualOcrRecognizer.Region("end\nstart",new Rect(0,0,100,60));
        sparse.boundedRange=true;sparse.quality=1;
        MultilingualOcrRecognizer.Region end = new MultilingualOcrRecognizer.Region("end",new Rect(60,0,100,20));end.quality=2;
        MultilingualOcrRecognizer.Region start = new MultilingualOcrRecognizer.Region("start",new Rect(0,40,40,60));start.quality=2;
        MultilingualOcrRecognizer.Region extraFirst = new MultilingualOcrRecognizer.Region("EXTRA",new Rect(0,0,40,20));extraFirst.quality=2;
        MultilingualOcrRecognizer.Region extraLast = new MultilingualOcrRecognizer.Region("EXTRA",new Rect(60,40,100,60));extraLast.quality=2;
        List<MultilingualOcrRecognizer.Region> sparseFound=Arrays.asList(extraFirst,end,start,extraLast);
        check(MultilingualOcrRecognizer.selectionResult(sparseFound,sparse,80,10,0)==sparse,
                "A multi-line selection without a precise mask retains its original text rather than collecting its bounding rectangle");
        sparse.selectedBounds.add(new Rect(end.bounds));sparse.selectedBounds.add(new Rect(start.bounds));
        check("end\nstart".equals(MultilingualOcrRecognizer.selectionResult(sparseFound,sparse,80,10,0).text),
                "Precise selected rectangles exclude unselected EXTRA words inside the same multi-line bounding rectangle");
        MultilingualOcrRecognizer.Region firstLine = new MultilingualOcrRecognizer.Region("EXTRA end",new Rect(0,0,100,20));firstLine.quality=2;
        firstLine.symbols.add(new MultilingualOcrRecognizer.Symbol(0,5,extraFirst.bounds));firstLine.symbols.add(new MultilingualOcrRecognizer.Symbol(6,9,end.bounds));
        MultilingualOcrRecognizer.Region lastLine = new MultilingualOcrRecognizer.Region("start EXTRA",new Rect(0,40,100,60));lastLine.quality=2;
        lastLine.symbols.add(new MultilingualOcrRecognizer.Symbol(0,5,start.bounds));lastLine.symbols.add(new MultilingualOcrRecognizer.Symbol(6,11,extraLast.bounds));
        check("end\nstart".equals(MultilingualOcrRecognizer.selectionResult(Arrays.asList(firstLine,lastLine),sparse,80,10,0).text),
                "Symbol clipping obeys each selected rectangle when another model merges selected and unselected text into full lines");
    }
    private void canceledCapture(Instrumentation instrumentation) throws Exception {
        List<Intent> responses = new ArrayList<>(); int[] renewals = {0}, calls = {0};
        AtomicReference<Intent> nextRequest = new AtomicReference<>();
        TaskCompletionSource<Text> first = new TaskCompletionSource<>(), second = new TaskCompletionSource<>();
        MainActivity.ScreenCaptureService capture = new MainActivity.ScreenCaptureService() {
            @Override public String getPackageName() { return instrumentation.getTargetContext().getPackageName(); }
            @Override public void sendBroadcast(Intent intent) { responses.add(new Intent(intent)); }
            @Override boolean captureAlive() { return false; }
            @Override void requireProjection(Intent request) { renewals[0]++; nextRequest.set(new Intent(request)); }
        };
        TextRecognizer model = (TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class}, (proxy, method, arguments) -> {
                    if ("process".equals(method.getName())) return calls[0]++==0 ? first.getTask() : second.getTask();
                    return null;
                });
        MultilingualOcrRecognizer recognizer = new MultilingualOcrRecognizer(model,model,model,null);
        Bitmap old = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888), current = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        onMain(instrumentation, () -> {
            capture.ocrRecognizer=recognizer;capture.previewLanguage=OcrLanguageSettings.Language.ENGLISH;
            capture.previewSession=capture.previewOperation=51;capture.previewInFlight=true;capture.recognizePreview(old,51);
        });
        awaitMain(instrumentation, () -> calls[0]==1, "The background recognizer never started");
        onMain(instrumentation, () -> {
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",51L),0,1);
            check(capture.previewSession==0 && capture.previewInFlight && capture.previewFrame==null,
                    "Cancel retires the current session while preserving the single physical model operation");
            check(!old.isRecycled(),"Canceled OCR work retains its borrowed screenshot until its callback returns");
            capture.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",52L));
            capture.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",53L));
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",52L),0,2);
            check(capture.pendingPreview!=null && capture.pendingPreview.getLongExtra("session",-1)==53
                            && renewals[0]==0 && calls[0]==1 && responses.size()==2
                            && responses.get(0).getBooleanExtra("queued",false)
                            && responses.get(1).getBooleanExtra("queued",false),
                    "Repeated gestures retain only the latest request, acknowledge queueing, and create no extra screenshot or model task");
            first.setResult(new Text("",Collections.emptyList()));
        });
        for(int i=0;i<100 && nextRequest.get()==null;i++)Thread.sleep(20);
        onMain(instrumentation, () -> {
            check(old.isRecycled(),"Canceled screenshot is recycled after the old OCR callback releases it");
            check(nextRequest.get()!=null && nextRequest.get().getLongExtra("session",-1)==53 && renewals[0]==1
                            && !capture.previewInFlight && capture.pendingPreview==null && responses.size()==2,
                    "Old completion starts only the newest pending capture and never publishes its stale result");
            responses.clear();
            capture.previewSession=capture.previewOperation=53;capture.previewInFlight=true;capture.recognizePreview(current,53);
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",51L),0,3);
            capture.sendPreview(51,Collections.emptyList(),null);capture.sendRefined(51,"stale",null);
            check(capture.previewSession==53 && capture.previewInFlight && responses.isEmpty(),
                    "Old cancellation and completion cannot release a replacement model operation");
            second.setResult(new Text("",Collections.emptyList()));
        });
        for(int i=0;i<100;i++){
            instrumentation.waitForIdleSync();
            if(!responses.isEmpty())break;
            Thread.sleep(20);
        }
        onMain(instrumentation, () -> {
            check(responses.size()==1 && responses.get(0).getLongExtra("session",-1)==53 && !capture.previewInFlight,
                    "Only the replacement session publishes its completed preview");
            check(!current.isRecycled(),"The replacement screenshot stays available for refinement");
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",53L),0,4);
            check(current.isRecycled(),"Canceling the completed replacement disposes its screenshot");
            recognizer.close();
        });
    }
    private void stalledPreviewRecovery(Instrumentation instrumentation) throws Exception {
        AtomicReference<Intent> dispatched=new AtomicReference<>();
        CountDownLatch started=new CountDownLatch(1);
        MultilingualOcrRecognizer replacement=fakeRecognizer();
        OcrPreviewFrame abandonedFrame=new OcrPreviewFrame(71,Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
        List<Intent> responses=new ArrayList<>();
        MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
            @Override public String getPackageName(){return instrumentation.getTargetContext().getPackageName();}
            @Override public void sendBroadcast(Intent intent){responses.add(new Intent(intent));}
            @Override MultilingualOcrRecognizer createOcrRecognizer(){return replacement;}
            @Override void captureOnce(Intent request){dispatched.set(new Intent(request));started.countDown();}
        };
        MultilingualOcrRecognizer stalled=fakeRecognizer();
        abandonedFrame.borrow(71);
        onMain(instrumentation,()->{
            service.ocrRecognizer=stalled;
            service.previewFrame=abandonedFrame;
            service.previewSession=service.previewOperation=71;
            service.modelOperation=71;
            service.previewInFlight=true;
            service.pendingPreview=new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",72L);
            service.previewSession=0; // A newer gesture replaced the visible session while 71 was in flight.
            service.timeoutPreviewOperation(71);
            check(!service.previewInFlight && service.previewOperation==0 && service.previewFrame==null,
                    "A lost callback releases the logical gate and retires its screenshot without recycling a borrowed bitmap");
            check(service.ocrRecognizer==replacement && service.retiredPreviewOperations.contains(71L),
                    "A timed-out model client is replaced while its uncancelable task remains isolated");
        });
        check(started.await(5,TimeUnit.SECONDS),"The newest floating OCR request starts after a lost callback is retired");
        onMain(instrumentation,()->{
            check(dispatched.get()!=null && dispatched.get().getLongExtra("session",-1)==72
                            && service.pendingPreview==null,
                    "Recovery dispatches only the latest queued gesture");
            service.previewSession=72;
            service.beginPreviewOperation(72);
            service.finishPreviewOperation(71);
            check(service.previewInFlight && service.previewOperation==72,
                    "A late callback from the retired sweep cannot release a newer OCR operation");
            check(responses.size()==1 && responses.get(0).getLongExtra("session",-1)==72
                            && responses.get(0).getBooleanExtra("processing",false),
                    "The replacement request receives progress while retired work emits no stale result");
            service.finishPreviewOperation(72);
            check(!service.previewInFlight,"The replacement operation clears its own gate normally");
            service.captureMain.removeCallbacksAndMessages(null);
            stalled.close();replacement.close();
        });
        abandonedFrame.release();
        check(abandonedFrame.borrow(71)==null,"A retired frame rejects future use after its original reader releases it");
    }
    private void capturePhaseTimeoutDoesNotRetireModel(Instrumentation instrumentation) throws Exception {
        List<Intent> responses=new ArrayList<>();
        MultilingualOcrRecognizer recognizer=fakeRecognizer();
        MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
            @Override public String getPackageName(){return instrumentation.getTargetContext().getPackageName();}
            @Override public void sendBroadcast(Intent intent){responses.add(new Intent(intent));}
            @Override MultilingualOcrRecognizer createOcrRecognizer(){throw new AssertionError("No replacement is needed before a model task is submitted");}
        };
        onMain(instrumentation,()->{
            service.ocrRecognizer=recognizer;
            service.previewSession=70;
            service.activeRequest=new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",70L);
            service.beginPreviewOperation(70);
            service.timeoutPreviewOperation(70);
            check(MainActivity.ScreenCaptureService.retiredPreviewTaskCount()==0
                            &&service.retiredPreviewOperations.isEmpty()&&service.ocrRecognizer==recognizer,
                    "A timeout during screenshot acquisition does not consume the uncancelable-model task budget");
            check(responses.stream().anyMatch(in->"OCR 响应超时，已重置识别器，请重试".equals(in.getStringExtra("error"))),
                    "The stalled capture phase still reports its timeout to the active preview");
            service.captureMain.removeCallbacksAndMessages(null);
            recognizer.close();
        });
    }
    private static MultilingualOcrRecognizer fakeRecognizer(){
        TextRecognizer model=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
                    if("process".equals(method.getName()))return Tasks.forResult(new Text("",Collections.emptyList()));
                    return null;
                });
        return new MultilingualOcrRecognizer(model,model,model,null);
    }
    private void queuedLanguageSnapshot(Instrumentation instrumentation) throws Exception {
        Context app=instrumentation.getTargetContext();
        OcrLanguageSettings settings=OcrLanguageSettings.get(app);
        OcrLanguageSettings.Language previous=settings.language();
        onMain(instrumentation,()->settings.setLanguage(OcrLanguageSettings.Language.CHINESE));
        for(String language:new String[]{OcrLanguageSettings.Language.ENGLISH.value,null}){
            MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
                @Override public String getPackageName(){return app.getPackageName();}
                @Override boolean captureAlive(){return true;}
                @Override public void sendBroadcast(Intent intent){}
            };
            onMain(instrumentation,()->{
                Intent request=new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                        .putExtra("session",language==null?202L:201L).putExtra("pickup_mode","word");
                if(language!=null)request.putExtra("language",language);
                service.captureOnce(request);
                check(service.previewLanguage==(language==null?OcrLanguageSettings.Language.CHINESE:OcrLanguageSettings.Language.ENGLISH),
                        "Queued preview uses its captured language; legacy intents still use the shared preference");
                service.captureMain.removeCallbacksAndMessages(null);
            });
        }
        onMain(instrumentation,()->settings.setLanguage(previous));
    }
    private void nonPreviewRequestsSerialized(Instrumentation instrumentation) throws Exception {
        Context app=instrumentation.getTargetContext();
        List<Intent> responses=new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        TaskCompletionSource<Text> held=new TaskCompletionSource<>();
        TextRecognizer model=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
                    if("process".equals(method.getName()))return calls.incrementAndGet()==1?held.getTask()
                            :Tasks.forException(new IllegalStateException("injected OCR failure"));
                    return null;
                });
        MultilingualOcrRecognizer recognizer=new MultilingualOcrRecognizer(model,model,model,null);
        recognizer.initialize(app);
        MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
            {attachBaseContext(app);}
            @Override public String getPackageName(){return app.getPackageName();}
            @Override boolean captureAlive(){return captureFrames!=null;}
            @Override public void sendBroadcast(Intent intent){responses.add(new Intent(intent));}
        };
        AtomicReference<ImageWriter> writer=new AtomicReference<>();
        OcrLanguageSettings settings=OcrLanguageSettings.get(app);
        OcrLanguageSettings.Language previous=settings.language();
        try{
            onMain(instrumentation,()->{
                settings.setLanguage(OcrLanguageSettings.Language.ENGLISH);
                service.w=service.h=8;service.ocrRecognizer=recognizer;
                service.captureFrames=new ScreenCaptureFrames(8,8,service.captureMain);
                writer.set(ImageWriter.newInstance(service.captureFrames.surface(),1));
                Image image=writer.get().dequeueInputImage();
                java.nio.ByteBuffer pixels=image.getPlanes()[0].getBuffer();while(pixels.remaining()>=4)pixels.putInt(Color.WHITE);
                writer.get().queueInputImage(image);
            });
            for(int index=0;index<12;index++){
                onMain(instrumentation,()->service.captureOnce(null));
                Thread.sleep(120);
            }
            awaitMain(instrumentation,()->calls.get()==1,"Serialized notification OCR did not start");
            onMain(instrumentation,()->{
                check(service.previewInFlight&&service.pendingPreview!=null
                                &&MainActivity.ScreenCaptureService.ACT_OCR.equals(service.pendingPreview.getAction()),
                        "Notification captures share the same in-flight gate and retain only the newest full-screen request");
                service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_REGION)
                        .putExtra("x",0).putExtra("y",0).putExtra("w",8).putExtra("h",8));
                check(calls.get()==1&&MainActivity.ScreenCaptureService.ACT_OCR_REGION.equals(service.pendingPreview.getAction()),
                        "A region request replaces the queued notification request without starting a second model task");
                held.setException(new IllegalStateException("release injected OCR task"));
            });
            awaitMain(instrumentation,()->calls.get()==2&&responses.stream().anyMatch(in->MainActivity.ScreenCaptureService.ACTION_RESULT.equals(in.getAction())),
                    "The latest queued region request did not run after the active task completed");
            onMain(instrumentation,()->check(!service.previewInFlight&&service.pendingPreview==null,
                    "Failure completion releases the shared operation gate and drains its latest request"));
        }finally{
            if(!held.getTask().isComplete())held.setException(new IllegalStateException("test cleanup"));
            onMain(instrumentation,()->{
                service.captureMain.removeCallbacksAndMessages(null);
                if(writer.get()!=null)writer.get().close();
                if(service.captureFrames!=null)service.captureFrames.close();
                recognizer.close();settings.setLanguage(previous);
            });
        }
    }
    private void captureStopRecovery(Instrumentation instrumentation) throws Exception {
        MultilingualOcrRecognizer replacement=fakeRecognizer();
        MultilingualOcrRecognizer stalled=fakeRecognizer();
        MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
            @Override public String getPackageName(){return instrumentation.getTargetContext().getPackageName();}
            @Override public void sendBroadcast(Intent intent){}
            @Override MultilingualOcrRecognizer createOcrRecognizer(){return replacement;}
        };
        onMain(instrumentation,()->{
            service.ocrRecognizer=stalled;
            service.previewSession=88;
            service.modelOperation=88;
            service.beginPreviewOperation(88);
            service.stopCapture();
            check(service.previewInFlight && service.previewOperation==88,
                    "Stopping screen capture does not mistake an uncancelable OCR task for a completed operation");
            check(service.previewSession==0,"A stopped capture session cannot publish its eventual OCR result");
            service.timeoutPreviewOperation(88);
            check(!service.previewInFlight && service.ocrRecognizer==replacement,
                    "The OCR watchdog still retires a stalled task after screen capture stops");
            service.finishPreviewOperation(88);
            service.captureMain.removeCallbacksAndMessages(null);
            stalled.close();replacement.close();
        });
    }
    private void stoppedCaptureCallbacks(Instrumentation instrumentation) throws Exception {
        Context app=instrumentation.getTargetContext();
        OcrLanguageSettings settings=OcrLanguageSettings.get(app);
        OcrLanguageSettings.Language previous=settings.language();
        onMain(instrumentation,()->settings.setLanguage(OcrLanguageSettings.Language.ENGLISH));
        try{
            for(int variant=0;variant<3;variant++){
                boolean region=variant!=2, failure=variant==1;
                long operation=-301L-variant;
                List<String> results=new ArrayList<>();
                AtomicReference<Intent> renewed=new AtomicReference<>();
                java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
                TaskCompletionSource<Text> held=new TaskCompletionSource<>();
                TextRecognizer model=(TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                        new Class<?>[]{TextRecognizer.class},(proxy,method,args)->{
                            if("process".equals(method.getName()))return calls.incrementAndGet()==1?held.getTask()
                                    :Tasks.forResult(new Text("current",Collections.emptyList()));
                            return null;
                        });
                MultilingualOcrRecognizer recognizer=new MultilingualOcrRecognizer(model,model,model,null);
                recognizer.initialize(app);
                MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
                    {attachBaseContext(app);ocrRecognizer=recognizer;}
                    @Override public void sendBroadcast(Intent intent){}
                    @Override void sendOcrResult(String text,String error){results.add(error==null?text:error);}
                    @Override public void startActivity(Intent intent){results.add(intent.getStringExtra("query"));}
                    @Override boolean captureAlive(){return false;}
                    @Override void requireProjection(Intent request){renewed.set(new Intent(request));}
                };
                Bitmap old=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
                Bitmap current=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
                try{
                    onMain(instrumentation,()->{
                        service.activeRequest=new Intent(region?MainActivity.ScreenCaptureService.ACT_OCR_REGION:MainActivity.ScreenCaptureService.ACT_OCR);
                        service.beginPreviewOperation(operation);service.recognize(old,region,operation);
                    });
                    awaitMain(instrumentation,()->calls.get()==1,"The old model task did not start");
                    onMain(instrumentation,()->{
                        service.stopCapture();
                        check(service.activeRequest==null && service.previewInFlight && service.previewOperation==operation && !old.isRecycled(),
                                "Stopping invalidates the logical request while preserving its physical model lock and bitmap");
                        service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_REGION).putExtra("marker",operation));
                        check(service.pendingPreview!=null && calls.get()==1 && renewed.get()==null,
                                "A renewed capture waits for the stopped model to release its physical operation");
                    });
                    if(failure)held.setException(new IllegalStateException("stale failure"));
                    else held.setResult(new Text("stale success",Collections.emptyList()));
                    awaitMain(instrumentation,()->renewed.get()!=null,"The queued capture did not resume after the old callback");
                    onMain(instrumentation,()->{
                        check(results.isEmpty() && old.isRecycled() && !service.previewInFlight && service.pendingPreview==null
                                        && renewed.get().getLongExtra("marker",0)==operation,
                                "A stopped region success/failure or notification success cannot publish, but releases the bitmap and newest request");
                        service.activeRequest=renewed.get();service.beginPreviewOperation(operation-10);
                        service.recognize(current,true,operation-10);
                    });
                    awaitMain(instrumentation,current::isRecycled,"The renewed model task did not finish");
                    onMain(instrumentation,()->check(results.equals(Collections.singletonList("current")) && !service.previewInFlight,
                            "Only the renewed capture publishes and releases its operation normally"));
                }finally{
                    if(!held.getTask().isComplete())held.setException(new IllegalStateException("test cleanup"));
                    onMain(instrumentation,()->{service.captureMain.removeCallbacksAndMessages(null);recognizer.close();});
                }
            }
        }finally{onMain(instrumentation,()->settings.setLanguage(previous));}
    }
    private void repeatedStallStopsCapture(Instrumentation instrumentation) throws Exception {
        List<Intent> responses=new ArrayList<>();int[] stopped={0};
        MultilingualOcrRecognizer recognizer=fakeRecognizer();
        MainActivity.ScreenCaptureService service=new MainActivity.ScreenCaptureService(){
            {attachBaseContext(instrumentation.getTargetContext());}
            @Override public String getPackageName(){return instrumentation.getTargetContext().getPackageName();}
            @Override public void sendBroadcast(Intent intent){responses.add(new Intent(intent));}
            @Override void stopCaptureService(){stopped[0]++;}
            @Override MultilingualOcrRecognizer createOcrRecognizer(){throw new AssertionError("A second uncancelable task must stop capture instead of allocating another model client");}
        };
        boolean wasReady=MainActivity.ScreenCaptureService.READY;
        onMain(instrumentation,()->{
            MainActivity.ScreenCaptureService.READY=false;
            service.ocrRecognizer=recognizer;
            service.previewSession=service.previewOperation=94;
            service.modelOperation=94;
            service.previewInFlight=true;
            service.retiredPreviewOperations.add(93L);
            synchronized(MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS){
                MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.add(93L);
            }
            service.pendingPreview=new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",95L);
            service.timeoutPreviewOperation(94);
            check(!service.previewInFlight && service.previewOperation==0 && service.pendingPreview==null,
                    "A second permanently stalled task stops the retry queue instead of accumulating orphan jobs");
            check(responses.size()==1 && responses.get(0).getLongExtra("session",-1)==95
                            && MainActivity.ScreenCaptureService.STALLED_ERROR.equals(responses.get(0).getStringExtra("error"))
                            && !responses.get(0).getBooleanExtra("permission_required",false)
                            && stopped[0]==1 && !MainActivity.ScreenCaptureService.READY,
                    "The bounded recovery reports an OCR-engine stall instead of requesting useless permission and tears down capture");
            check(service.retiredPreviewOperations.size()==2 && service.retiredPreviewOperations.contains(93L)
                            && service.retiredPreviewOperations.contains(94L)
                            && MainActivity.ScreenCaptureService.retiredPreviewTaskCount()==2,
                    "Repeated stalls are counted across the service restart boundary and capped at two physical tasks");
            List<Intent> blocked=new ArrayList<>();
            MainActivity.ScreenCaptureService restarted=new MainActivity.ScreenCaptureService(){
                {attachBaseContext(instrumentation.getTargetContext());}
                @Override public String getPackageName(){return instrumentation.getTargetContext().getPackageName();}
                @Override public void sendBroadcast(Intent intent){blocked.add(new Intent(intent));}
                @Override boolean captureAlive(){throw new AssertionError("No new capture may start while two retired tasks remain");}
            };
            restarted.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",96L));
            check(blocked.size()==1 && MainActivity.ScreenCaptureService.STALLED_ERROR.equals(blocked.get(0).getStringExtra("error")),
                    "A newly created service cannot reset the process-wide retired-task cap and reports the recovery cause");
            synchronized(MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS){
                MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.remove(93L);
                MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.remove(94L);
            }
            service.retiredPreviewOperations.clear();
            service.captureMain.removeCallbacksAndMessages(null);
            recognizer.close();
            MainActivity.ScreenCaptureService.READY=wasReady;
        });
    }
    private void repeatedCapture(Instrumentation instrumentation) throws Exception {
        List<Bitmap> copies = new ArrayList<>(); List<Intent> responses = new ArrayList<>(); int[] renewals = {0};
        MainActivity.ScreenCaptureService service = new MainActivity.ScreenCaptureService() {
            @Override public String getPackageName() { return instrumentation.getTargetContext().getPackageName(); }
            @Override public void sendBroadcast(Intent intent) { responses.add(new Intent(intent)); }
            @Override boolean captureAlive() { return captureFrames != null; }
            @Override void requireProjection(Intent request) { renewals[0]++; }
            @Override void recognizePreview(Bitmap bitmap, long session) {
                copies.add(bitmap); sendPreview(session, Collections.emptyList(), null);
            }
        };
        AtomicReference<ImageWriter> writer = new AtomicReference<>();
        onMain(instrumentation, () -> {
            service.w = 7; service.h = 5;
            service.captureFrames = new ScreenCaptureFrames(7, 5, service.captureMain);
            writer.set(ImageWriter.newInstance(service.captureFrames.surface(), 1));
        });
        try {
            for (int frame = 0; frame < 5; frame++) {
                final int color = Color.rgb(31 + frame, 73, 149);
                onMain(instrumentation, () -> queueCaptureFrame(writer.get(), color));
                // No new display frame is produced during these two captures.
                for (int request = 0; request < 2; request++) {
                    final long session = 100 + frame * 2 + request; final int count = copies.size() + 1;
                    onMain(instrumentation, () -> service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                            .putExtra("session", session).putExtra("pickup_mode", OcrPickupMode.ENGLISH.value)));
                    for (int wait = 0; wait < 150; wait++) {
                        instrumentation.waitForIdleSync();
                        if (copies.size() >= count) break;
                        Thread.sleep(20);
                    }
                    onMain(instrumentation, () -> {
                        check(copies.size() == count && copies.get(count - 1).getPixel(2, 2) == color,
                                "Every repeated capture reads the current frame, including an unchanged static screen");
                        check(!service.previewInFlight && renewals[0] == 0,
                                "Successful repeated captures release the operation without requesting another grant");
                    });
                }
            }
            onMain(instrumentation, () -> {
                check(copies.get(0) != copies.get(1) && copies.get(0).getPixel(2, 2) == Color.rgb(31, 73, 149),
                        "Each OCR owns its bitmap and later native frames cannot overwrite an earlier screenshot");
                service.captureFrames.close(); service.captureFrames = new ScreenCaptureFrames(7, 5, service.captureMain);
                service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                        .putExtra("session", 200L).putExtra("pickup_mode", OcrPickupMode.ENGLISH.value));
            });
            Thread.sleep(2300); instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                Intent response = responses.get(responses.size() - 1);
                check(response.getLongExtra("session", -1) == 200 && response.hasExtra("error")
                                && !service.previewInFlight && renewals[0] == 0,
                        "No first frame reports a retryable error without revoking valid sharing or locking later OCR");
                writer.get().close(); writer.set(ImageWriter.newInstance(service.captureFrames.surface(), 1));
                queueCaptureFrame(writer.get(), Color.GREEN);
                service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                        .putExtra("session", 201L).putExtra("pickup_mode", OcrPickupMode.ENGLISH.value));
            });
            for (int wait = 0; wait < 150; wait++) {
                instrumentation.waitForIdleSync(); if (copies.size() == 11) break; Thread.sleep(20);
            }
            onMain(instrumentation, () -> check(copies.size() == 11 && copies.get(10).getPixel(2, 2) == Color.GREEN
                            && !service.previewInFlight && renewals[0] == 0,
                    "A delayed first frame recovers on the next request using the same authorized capture"));
        } finally {
            onMain(instrumentation, () -> {
                service.captureMain.removeCallbacksAndMessages(null);
                writer.get().close(); service.captureFrames.close();
                for (Bitmap bitmap : copies) bitmap.recycle();
            });
        }
    }
    private static void queueCaptureFrame(ImageWriter writer, int color) {
        Image image = writer.dequeueInputImage();
        Image.Plane plane = image.getPlanes()[0]; java.nio.ByteBuffer buffer = plane.getBuffer();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int offset = y * plane.getRowStride() + x * plane.getPixelStride();
            buffer.put(offset, (byte)Color.red(color)); buffer.put(offset + 1, (byte)Color.green(color));
            buffer.put(offset + 2, (byte)Color.blue(color)); buffer.put(offset + 3, (byte)255);
        }
        writer.queueInputImage(image);
    }
    private static MultilingualOcrRecognizer.Region symbols(String text, int left, int top) {
        MultilingualOcrRecognizer.Region region = new MultilingualOcrRecognizer.Region(text,new Rect(left,top,left+text.length()*10,top+20));
        for(int i=0;i<text.length();i++)region.symbols.add(new MultilingualOcrRecognizer.Symbol(i,i+1,new Rect(left+i*10,top,left+(i+1)*10,top+20)));
        return region;
    }
    private void gestures(Instrumentation instrumentation) throws Exception {
        Context app = instrumentation.getTargetContext();
        LookupDestinationSettings destinations = LookupDestinationSettings.get(app);
        OcrLanguageSettings languages=OcrLanguageSettings.get(app);
        OcrLanguageSettings.Language previousLanguage=languages.language();
        LookupDestinationSettings.Model previousDestination = destinations.model();
        boolean previousThirdParty = destinations.isThirdParty();
        AtomicReference<Intent> request = new AtomicReference<>();
        AtomicLong requestStartedAt = new AtomicLong();
        int[] activities = {0};
        Context capture = new ContextWrapper(app) {
            @Override public ComponentName startService(Intent intent) {
                if (!MainActivity.ScreenCaptureService.ACT_OCR_CANCEL.equals(intent.getAction())) {
                    requestStartedAt.set(SystemClock.uptimeMillis()); request.set(new Intent(intent));
                }
                return new ComponentName(getPackageName(), MainActivity.ScreenCaptureService.class.getName());
            }
            @Override public void startActivity(Intent intent) { activities[0]++; }
        };
        final Rect position = new Rect(400, 700, 444, 744);
        final Rect rendered = new Rect(position);
        int[] moves = {0}, saves = {0}, taps = {0}; AtomicReference<String> query = new AtomicReference<>();
        OcrPickupController.Host host = new OcrPickupController.Host() {
            public Rect position() { return new Rect(rendered); }
            public Rect move(int x, int y) { position.offsetTo(x, y); moves[0]++; return new Rect(position); }
            public void save() { saves[0]++; }
            public void tap() { taps[0]++; }
            public void lookup(String text) { query.set(text); }
        };
        AtomicReference<OcrPickupController> owner = new AtomicReference<>(); AtomicReference<View.OnTouchListener> listener = new AtomicReference<>();
        AtomicReference<View> view = new AtomicReference<>();
        boolean wasReady = MainActivity.ScreenCaptureService.READY;
        MainActivity.ScreenCaptureService.READY = false;
        try {
            onMain(instrumentation, () -> { owner.set(new OcrPickupController(capture, host)); listener.set(owner.get().listener()); view.set(new View(app)); });
            onMain(instrumentation,()->{
                synchronized(MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS){
                    MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.add(-901L);
                    MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.add(-902L);
                }
                try{
                    java.lang.reflect.Method retry=OcrPickupController.class.getDeclaredMethod("requestAuthorization");
                    retry.setAccessible(true);retry.invoke(owner.get());
                    check(activities[0]==0,"A known stalled engine does not open another screen-sharing authorization flow");
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
                finally{synchronized(MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS){
                    MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.remove(-901L);
                    MainActivity.ScreenCaptureService.RETIRED_PREVIEW_TASKS.remove(-902L);
                }}
            });
            onMain(instrumentation, () -> {
                touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
                touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
                try { java.lang.reflect.Field field=OcrPickupController.class.getDeclaredField("overlay");field.setAccessible(true);
                    check(field.get(owner.get()) != null && request.get()==null, "Pointer is mounted as soon as dragging starts, before dwell or screenshot");
                } catch(ReflectiveOperationException error){throw new AssertionError(error);}
                touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            });
            onMain(instrumentation, () -> {
                check(moves[0] == 1 && saves[0] == 1 && position.left == 500, "Quick ordinary drag moves and saves the button");
                check(request.get() == null && activities[0] == 0 && taps[0] == 0, "Quick movement never starts OCR or authorization");
                position.set(rendered); moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_CANCEL, 520, 720);
            Thread.sleep(360);
            onMain(instrumentation, () -> {
                check(position.equals(rendered) && saves[0] == 1, "Cancel restores drag and prevents the pending dwell callback");
                check(request.get() == null && activities[0] == 0, "Canceled drag cannot request screen sharing");
                check(field(owner.get(),"overlay")==null && field(owner.get(),"dismissingJoystick")==null,
                        "Canceled pointer and joystick fades finish by removing their windows");
                moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 420, 720);
            onMain(instrumentation, () -> check(taps[0] == 1 && activities[0] == 0, "Short tap still opens global search"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            Thread.sleep(600);
            onMain(instrumentation, () -> check(request.get() == null && activities[0] == 0, "600ms hold does not activate the joystick"));
            Thread.sleep(170);
            onMain(instrumentation, () -> check(request.get() == null && activities[0] == 0, "Showing the joystick alone does not request sharing"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 320, 720);
            onMain(instrumentation, () -> MainActivity.ScreenCaptureService.READY=true);
            onMain(instrumentation, () -> {
                touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 320, 720);
                check(request.get()==null, "Capture waits for the outgoing white joystick ring instead of photographing its fade");
            });
            awaitRequest(request);
            onMain(instrumentation, () -> {
                check(position.equals(rendered) && saves[0] == 1, "Joystick selection preserves the saved bubble position");
                check(request.get()!=null && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction())
                                && "word".equals(request.get().getStringExtra("pickup_mode")) && activities[0]==0,
                        "Left sector starts OCR with the shared language and the changed lookup destination");
                check(destinations.isThirdParty()!=previousThirdParty,"Joystick left release toggles the same persisted destination used by the search model menu");
                owner.get().cancel();request.set(null);MainActivity.ScreenCaptureService.READY=false;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            Thread.sleep(760);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            onMain(instrumentation, () -> check(activities[0] == 1 && request.get() == null, "Explicit Japanese OCR reauthorizes a missing screen projection"));
            app.sendBroadcast(new Intent(MainActivity.OcrActivity.ACTION_FINISHED).setPackage(app.getPackageName()).putExtra("granted", false));
            Thread.sleep(100);
            onMain(instrumentation, () -> { MainActivity.ScreenCaptureService.READY = true; moves[0] = 0;
                languages.setLanguage(OcrLanguageSettings.Language.MIXED); });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 421, 721);
            onMain(instrumentation, () -> check(request.get() == null && moves[0] == 0, "Finger jitter cannot move the bubble or start OCR"));
            float density = app.getResources().getDisplayMetrics().density;
            float finalX = 520 + Math.max(40, Math.max(ViewConfiguration.get(app).getScaledTouchSlop(), 12*density)+1);
            CountDownLatch relocated = new CountDownLatch(1);
            AtomicLong relocatedAt = new AtomicLong();
            AtomicReference<Throwable> relocationFailure = new AtomicReference<>();
            onMain(instrumentation, () -> {
                touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
                check(request.get() == null, "Moving the button cannot synchronously bypass the 150ms dwell");
                Object gate = field(listener.get(), "dwell");
                long firstDwellAt = (Long)field(gate, "since");
                // Both messages use the same UI queue. Even if it is busy past
                // both deadlines, this MOVE is processed before the old 150ms
                // activation; sleeps on the instrumentation thread cannot prove that.
                new Handler(Looper.getMainLooper()).postAtTime(() -> {
                    try {
                        touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, finalX, 720);
                        relocatedAt.set((Long)field(gate, "since"));
                        check(relocatedAt.get() > firstDwellAt, "Travel outside the density-scaled dwell radius resets its timestamp");
                        check(request.get() == null, "Travel to another pointer area removes the previous activation");
                    } catch (Throwable failure) { relocationFailure.set(failure); }
                    finally { relocated.countDown(); }
                }, firstDwellAt + 50);
            });
            check(relocated.await(5,TimeUnit.SECONDS), "The queued pointer relocation is delivered");
            rethrow(relocationFailure.get());
            awaitRequest(request);
            onMain(instrumentation, () -> {
                check(request.get() != null && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction())
                        && "word".equals(request.get().getStringExtra("pickup_mode")), "Normal dwell uses the composer language rather than inheriting a previous joystick mode");
                check(OcrLanguageSettings.Language.MIXED.value.equals(request.get().getStringExtra("language")),
                        "A preview request carries the language selected when its gesture began");
                languages.setLanguage(OcrLanguageSettings.Language.CHINESE);
                check(requestStartedAt.get()-relocatedAt.get() >= OcrDwellGate.DELAY_MS,
                        "Only a full 150ms at the replacement pointer area permits capture; the old deadline cannot fire");
            });
            long session = request.get().getLongExtra("session", -1);
            onMain(instrumentation, () -> {
                BroadcastReceiver receiver = (BroadcastReceiver)field(owner.get(), "receiver");
                receiver.onReceive(app, new Intent(MainActivity.ScreenCaptureService.ACTION_STOPPED));
                check((Boolean)field(owner.get(), "armed")
                                && (Long)field(owner.get(), "session") == session,
                        "A delayed stop from the old projection cannot silently cancel a fresh pickup");
            });
            int finalLeft = Math.round(finalX+1)-20;
            float y = rendered.exactCenterY()-48*density;
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("words",new String[]{"日本語"}).putExtra("boxes",new int[]{finalLeft,(int)y-10,finalLeft+44,(int)y+10}));
            Thread.sleep(100); // Deliver the main-thread preview result before releasing the pointer.
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, finalX+1, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, finalX+1, 720);
            onMain(instrumentation, () -> check(saves[0] == 2 && position.left == finalLeft, "OCR drag saves the moved bubble once on release"));
            awaitAction(request,MainActivity.ScreenCaptureService.ACT_OCR_REFINE);
            check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()), "Release requests localized refinement on the same captured frame");
            check("日本語".equals(request.get().getStringExtra("seed"))
                            && Math.abs(request.get().getFloatExtra("aim_x",0)-(finalLeft+rendered.width()/2f))<0.1f,
                    "Aim uses the requested bubble centre before WindowManager renders the move");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","日本語"));
            for (int n = 0; n < 30 && query.get() == null; n++) Thread.sleep(50);
            check("日本語".equals(query.get()), "Refinement hands complete recognized text to direct lookup");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","stale"));
            Thread.sleep(100); check("日本語".equals(query.get()), "Canceled sessions reject old OCR updates");
            String[] modes={"sentence","japanese","chinese"};
            float[][] directions={{0,-100},{100,0},{0,100}};
            for(int index=0;index<directions.length;index++){
                request.set(null);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
                Thread.sleep(760);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,420+directions[index][0],720+directions[index][1]);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_UP,420+directions[index][0],720+directions[index][1]);
                awaitRequest(request);
                check(request.get()!=null && modes[index].equals(request.get().getStringExtra("pickup_mode")),"Joystick release dispatches "+modes[index]+" as a single-session mode");
                OcrLanguageSettings.Language[] expected={OcrLanguageSettings.Language.MIXED,OcrLanguageSettings.Language.JAPANESE,OcrLanguageSettings.Language.CHINESE};
                check(OcrLanguageSettings.get(app).language()==expected[index],"Joystick selection also commits the shared cached language");
                onMain(instrumentation, owner.get()::cancel);
            }
            request.set(null);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            awaitRequest(request);
            long revokedSession=request.get().getLongExtra("session",-1);
            int beforePermission=activities[0],beforeTaps=taps[0];
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session",revokedSession).putExtra("permission_required",true));
            awaitMain(instrumentation, () -> activities[0]==beforePermission+1,
                    "Capture-health failure never requested a replacement authorization host");
            check(activities[0]==beforePermission+1,"A capture-health failure reopens the transparent system authorization host");
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_UP,520,720);
            check(taps[0]==beforeTaps,"Release of the old gesture cannot open search while renewal is pending");
            app.sendBroadcast(new Intent(MainActivity.OcrActivity.ACTION_FINISHED).setPackage(app.getPackageName()).putExtra("granted",true));
            long readySentAt=SystemClock.uptimeMillis();
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_READY).setPackage(app.getPackageName()));
            awaitMain(instrumentation, () -> request.get()!=null
                            && request.get().getLongExtra("session",-1)!=revokedSession,
                    "Granted renewal never dispatched a replacement pickup session");
            long renewedSession=request.get().getLongExtra("session",-1);
            check(renewedSession!=revokedSession
                            && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction()),
                    "Granted renewal starts a new pickup session after the grant UI closes");
            check(requestStartedAt.get()-readySentAt>=300,
                    "Renewal preserves the full 300ms grant-UI settling delay before starting capture");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session",revokedSession).putExtra("refined",true).putExtra("text","revoked"));
            Thread.sleep(100);
            check("日本語".equals(query.get()),"Revoked projection updates never reach lookup after reauthorization");
            onMain(instrumentation, () -> {owner.get().cancel();position.set(rendered);request.set(null);query.set(null);});
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,570,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            awaitRequest(request);
            long sweepSession=request.get().getLongExtra("session",-1);
            int[] owners=new int[10],offsets=new int[20],glyphBoxes=new int[40];
            for(int i=0;i<10;i++){
                offsets[i*2]=i;offsets[i*2+1]=i+1;
                glyphBoxes[i*4]=497+i*10;glyphBoxes[i*4+1]=(int)y-10;glyphBoxes[i*4+2]=507+i*10;glyphBoxes[i*4+3]=(int)y+10;
            }
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",sweepSession)
                    .putExtra("words",new String[]{"这是father父亲"}).putExtra("boxes",new int[]{497,(int)y-10,597,(int)y+10})
                    .putExtra("symbol_owners",owners).putExtra("symbol_offsets",offsets).putExtra("symbol_boxes",glyphBoxes));
            Thread.sleep(150);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_UP,520,720);
            awaitAction(request,MainActivity.ScreenCaptureService.ACT_OCR_REFINE);
            check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()) && "father".equals(request.get().getStringExtra("seed")),
                    "A real f-to-r-to-f gesture made while recognition is pending is replayed into the exact mixed-text range");
            check(request.get().getBooleanExtra("bounded_range",false),"Refinement carries the explicit range so it cannot expand back to the whole phrase");
            int[] selectionBoxes=request.get().getIntArrayExtra("selection_boxes");
            check(selectionBoxes!=null && selectionBoxes.length==4 && selectionBoxes[0]==517 && selectionBoxes[2]==577,
                    "Refinement carries the actual swept glyph bounds instead of only a union rectangle");
            View.OnTouchListener proxyListener=owner.get().listener();
            for (int repeat=0; repeat<4; repeat++) {
                final String word="repeat"+repeat;
                app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                        .putExtra("session",request.get().getLongExtra("session",-1)).putExtra("refined",true).putExtra("text",word));
                for (int wait=0;wait<30 && !word.equals(query.get());wait++) Thread.sleep(20);
                check(word.equals(query.get()),"Each consecutive refined gesture reaches direct lookup");
                request.set(null);
                View.OnTouchListener active=repeat%2==0?proxyListener:listener.get();
                touch(instrumentation,active,view.get(),MotionEvent.ACTION_DOWN,420,720);
                touch(instrumentation,active,view.get(),MotionEvent.ACTION_MOVE,520,720);
                touch(instrumentation,active,view.get(),MotionEvent.ACTION_MOVE,524,720);
                awaitRequest(request);
                check(request.get()!=null && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction()),
                        "Original and proxy listeners rearm after every result without inheriting stale gesture state");
                onMain(instrumentation, () -> {
                    View pointer=(View)field(owner.get(),"overlay");
                    check(pointer!=null && pointer.isAttachedToWindow() && field(pointer,"frozen")==null,
                            "Restarting during a pointer fade reuses a live view without old-frame content or stale removal");
                });
                long nextSession=request.get().getLongExtra("session",-1);
                app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                        .putExtra("session",nextSession).putExtra("words",new String[]{"complete"})
                        .putExtra("boxes",new int[]{500,(int)y-10,580,(int)y+10}));
                Thread.sleep(80);
                touch(instrumentation,active,view.get(),MotionEvent.ACTION_UP,524,720);
                awaitAction(request,MainActivity.ScreenCaptureService.ACT_OCR_REFINE);
                check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction())
                                && "complete".equals(request.get().getStringExtra("seed")),
                        "Every subsequent placement automatically refines a complete word after earlier precise selections");
            }
            onMain(instrumentation, () -> {
                MainActivity.ScreenCaptureService.READY = false;
                ((BroadcastReceiver)field(owner.get(), "receiver")).onReceive(app,
                        new Intent(MainActivity.ScreenCaptureService.ACTION_STOPPED));
                check(!(Boolean)field(owner.get(), "armed") && !(Boolean)field(owner.get(), "waiting")
                                && field(owner.get(), "selected") == null,
                        "A genuinely stopped projection clears the active pickup and its selection");
                request.set(null);
            });
            int grantsBeforeStopRetry = activities[0];
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            awaitMain(instrumentation, () -> activities[0] == grantsBeforeStopRetry+1,
                    "The next pickup after a real projection stop must request authorization");
            onMain(instrumentation, () -> MainActivity.ScreenCaptureService.READY = true);
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_READY).setPackage(app.getPackageName()));
            awaitRequest(request);
            check(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction()),
                    "Renewed authorization restarts capture through the existing pickup entry");
            onMain(instrumentation, () -> {
                for (int dwell : new int[]{300, 150, 149, 40}) {
                    owner.get().cancel(); request.set(null); position.set(rendered);
                    long now = SystemClock.uptimeMillis();
                    int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
                    long[] times = {now-600, now-100-dwell, now-100};
                    for (int i=0; i<actions.length; i++) {
                        MotionEvent event = MotionEvent.obtain(times[0], times[i], actions[i], i==0?420:520, 720, 0);
                        try { listener.get().onTouch(view.get(), event); } finally { event.recycle(); }
                    }
                    check((request.get()!=null) == (dwell>=OcrDwellGate.DELAY_MS),
                            "Batched touch events preserve the real "+dwell+"ms dwell when the UI cannot run its timer before UP");
                }
            });
            onMain(instrumentation, () -> {
                MainActivity.ScreenCaptureService detached = new MainActivity.ScreenCaptureService();
                check(!detached.captureAlive(), "A stale READY flag without actual projection, reader and virtual display cannot authorize capture");
            });
        } finally {
            onMain(instrumentation, () -> {
                if (owner.get() != null) {
                    owner.get().close();
                    check(field(owner.get(),"overlay")==null && field(owner.get(),"joystick")==null
                                    && field(owner.get(),"dismissingJoystick")==null,
                            "Closing during any animation immediately releases every owned OCR window");
                }
                destinations.select(previousDestination);
                languages.setLanguage(previousLanguage);
            });
            MainActivity.ScreenCaptureService.READY = wasReady;
        }
    }
    private static void onMain(Instrumentation instrumentation, Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) { action.run(); return; }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        rethrow(failure.get());
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof Error) throw (Error)failure;
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure != null) throw new AssertionError(failure);
    }
    private static void awaitRequest(AtomicReference<Intent> request) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis()+5000;
        while (request.get()==null && SystemClock.uptimeMillis()<deadline) Thread.sleep(10);
        if (request.get()==null) throw new AssertionError("No OCR request was delivered within 5 seconds");
    }
    private static void awaitMain(Instrumentation instrumentation, java.util.function.BooleanSupplier condition,
            String message) throws InterruptedException {
        long deadline=SystemClock.uptimeMillis()+5000;
        AtomicReference<Boolean> satisfied=new AtomicReference<>(false);
        do {
            onMain(instrumentation, () -> satisfied.set(condition.getAsBoolean()));
            if (satisfied.get()) return;
            Thread.sleep(10);
        } while (SystemClock.uptimeMillis()<deadline);
        throw new AssertionError(message);
    }
    private static void awaitAction(AtomicReference<Intent> request, String action) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis()+5000;
        while ((request.get()==null || !action.equals(request.get().getAction()))
                && SystemClock.uptimeMillis()<deadline) Thread.sleep(10);
        if (request.get()==null || !action.equals(request.get().getAction()))
            throw new AssertionError("OCR never reached " + action);
    }
    private static Object field(Object target, String name) {
        try {
            java.lang.reflect.Field field=target.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(target);
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void touch(Instrumentation test, View.OnTouchListener listener, View view, int action, float x, float y) {
        onMain(test, () -> {
            long time = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
            try { listener.onTouch(view, event); } finally { event.recycle(); }
        });
    }
    private String fonts(Instrumentation instrumentation) throws Exception {
        AtomicReference<MultilingualOcrRecognizer> client = new AtomicReference<>();
        onMain(instrumentation, () -> { client.set(new MultilingualOcrRecognizer()); client.get().initialize(instrumentation.getTargetContext()); });
        StringBuilder report = new StringBuilder();
        int original = 0, refined = 0, total = 0;
        try {
            String[] words = {"avocado", "日本語", "中文词典"};
            for (String word : words) for (String family : new String[]{"sans-serif", "serif", "monospace"}) for (int size : new int[]{8, 10, 14}) {
                Bitmap image = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
                Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK); ink.setTextSize(size); ink.setTypeface(Typeface.create(family, Typeface.NORMAL));
                new Canvas(image).drawText(word,20,40,ink);
                Rect target = new Rect(20, (int)Math.floor(40+ink.ascent()), 20+(int)Math.ceil(ink.measureText(word)), (int)Math.ceil(40+ink.descent()));
                AtomicReference<List<MultilingualOcrRecognizer.Region>> regions = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
                CountDownLatch detected = new CountDownLatch(1);
                onMain(instrumentation, () -> client.get().recognizeRegions(image, new MultilingualOcrRecognizer.RegionsCallback() {
                    public void onSuccess(List<MultilingualOcrRecognizer.Region> found) { regions.set(found); detected.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); detected.countDown(); }
                }));
                if (!detected.await(20, TimeUnit.SECONDS)) throw new AssertionError("Font probe timeout");
                MultilingualOcrRecognizer.Region seed = regions.get() == null ? null : MultilingualOcrRecognizer.regionAt(regions.get(), target.exactCenterX(), target.exactCenterY(), 8);
                String before = seed == null ? "" : seed.text;
                AtomicReference<String> after = new AtomicReference<>(""); CountDownLatch enhanced = new CountDownLatch(1);
                onMain(instrumentation, () -> client.get().recognizeSelection(image,target,seed,target.exactCenterX(),target.exactCenterY(),8,new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String text) { after.set(text); enhanced.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); enhanced.countDown(); }
                }));
                if (!enhanced.await(20,TimeUnit.SECONDS)) throw new AssertionError("Refined probe timeout");
                if (word.equals(before)) original++; if (word.equals(after.get())) refined++; total++;
                report.append(word).append(' ').append(family).append(' ').append(size).append("px: ").append(before).append(" -> ").append(after.get()).append(error.get() == null ? "" : " ["+error.get()+"]").append('\n');
                image.recycle();
            }
            report.insert(0, "Font exact matches: original="+original+", refined="+refined+", total="+total+"\n");
        } finally { onMain(instrumentation, () -> client.get().close()); }
        return report.toString();
    }
}
