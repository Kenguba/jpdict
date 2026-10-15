package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.media.Image;
import android.media.ImageWriter;
import android.os.SystemClock;
import android.view.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only interaction, bitmap ownership and optional real-model font probes. */
final class OcrValidation {
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation, boolean fonts) throws Exception {
        OcrValidation test = new OcrValidation();
        test.images(); test.selection(); test.repeatedCapture(instrumentation); test.canceledCapture(instrumentation); test.gestures(instrumentation);
        String measured = fonts ? test.fonts(instrumentation) : "";
        return "OCR: " + test.checks + " assertions passed\n" + OcrLanguageValidation.run(instrumentation) + measured;
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
        check(sweep.picked.size() == 2 && "first second".equals(sweep.combined().text), "English supports user-selected phrases as well as single words");
        MultilingualOcrRecognizer.Region distant = new MultilingualOcrRecognizer.Region("distant", new Rect(700, 500, 760, 520));
        sweep.touch(distant, OcrPickupMode.WORD);
        check(sweep.picked.size() == 1 && "distant".equals(sweep.combined().text), "Moving to a distant region clears the old sweep");
        MultilingualOcrRecognizer.Region mixed = symbols("这是father父亲", 100, 40);
        List<MultilingualOcrRecognizer.Region> regions = Collections.singletonList(mixed);
        sweep.clear(); sweep.move(regions, 125, 50, 0);
        check(mixed.text.equals(sweep.combined().text), "Dwell alone keeps the complete model element");
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
        sweep.clear(); sweep.move(regions,105,50,0); sweep.move(regions,200,50,0); sweep.move(regions,105,50,0);
        check("hello world".equals(sweep.combined().text), "A coalesced return sweep fills all intervening glyphs and adjacent words");
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
        MultilingualOcrRecognizer recognizer = new MultilingualOcrRecognizer(model,model,model,model,null);
        Bitmap old = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888), current = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        instrumentation.runOnMainSync(() -> {
            capture.ocrRecognizer=recognizer;capture.previewLanguage=OcrLanguageSettings.Language.ENGLISH;
            capture.previewSession=capture.previewOperation=51;capture.previewInFlight=true;capture.recognizePreview(old,51);
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",51L),0,1);
            check(capture.previewSession==0 && capture.previewInFlight && capture.previewFrame==null,
                    "Cancel retires the current session while preserving the single physical model operation");
            check(!old.isRecycled(),"Canceled OCR work retains its borrowed screenshot until its callback returns");
            capture.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",52L));
            capture.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW).putExtra("session",53L));
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",52L),0,2);
            check(capture.pendingPreview!=null && capture.pendingPreview.getLongExtra("session",-1)==53
                            && renewals[0]==0 && calls[0]==1 && responses.isEmpty(),
                    "Repeated gestures retain only the latest request without creating another screenshot or model task");
            first.setResult(new Text("",Collections.emptyList()));
        });
        for(int i=0;i<100 && nextRequest.get()==null;i++)Thread.sleep(20);
        instrumentation.runOnMainSync(() -> {
            check(old.isRecycled(),"Canceled screenshot is recycled after the old OCR callback releases it");
            check(nextRequest.get()!=null && nextRequest.get().getLongExtra("session",-1)==53 && renewals[0]==1
                            && !capture.previewInFlight && capture.pendingPreview==null && responses.isEmpty(),
                    "Old completion starts only the newest pending capture and never publishes its stale result");
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
        instrumentation.runOnMainSync(() -> {
            check(responses.size()==1 && responses.get(0).getLongExtra("session",-1)==53 && !capture.previewInFlight,
                    "Only the replacement session publishes its completed preview");
            check(!current.isRecycled(),"The replacement screenshot stays available for refinement");
            capture.onStartCommand(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_CANCEL).putExtra("session",53L),0,4);
            check(current.isRecycled(),"Canceling the completed replacement disposes its screenshot");
            recognizer.close();
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
        instrumentation.runOnMainSync(() -> {
            service.w = 7; service.h = 5;
            service.captureFrames = new ScreenCaptureFrames(7, 5, service.captureMain);
            writer.set(ImageWriter.newInstance(service.captureFrames.surface(), 1));
        });
        try {
            for (int frame = 0; frame < 5; frame++) {
                final int color = Color.rgb(31 + frame, 73, 149);
                instrumentation.runOnMainSync(() -> queueCaptureFrame(writer.get(), color));
                // No new display frame is produced during these two captures.
                for (int request = 0; request < 2; request++) {
                    final long session = 100 + frame * 2 + request; final int count = copies.size() + 1;
                    instrumentation.runOnMainSync(() -> service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                            .putExtra("session", session).putExtra("pickup_mode", OcrPickupMode.ENGLISH.value)));
                    for (int wait = 0; wait < 150; wait++) {
                        instrumentation.waitForIdleSync();
                        if (copies.size() >= count) break;
                        Thread.sleep(20);
                    }
                    instrumentation.runOnMainSync(() -> {
                        check(copies.size() == count && copies.get(count - 1).getPixel(2, 2) == color,
                                "Every repeated capture reads the current frame, including an unchanged static screen");
                        check(!service.previewInFlight && renewals[0] == 0,
                                "Successful repeated captures release the operation without requesting another grant");
                    });
                }
            }
            instrumentation.runOnMainSync(() -> {
                check(copies.get(0) != copies.get(1) && copies.get(0).getPixel(2, 2) == Color.rgb(31, 73, 149),
                        "Each OCR owns its bitmap and later native frames cannot overwrite an earlier screenshot");
                service.captureFrames.close(); service.captureFrames = new ScreenCaptureFrames(7, 5, service.captureMain);
                service.captureOnce(new Intent(MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW)
                        .putExtra("session", 200L).putExtra("pickup_mode", OcrPickupMode.ENGLISH.value));
            });
            Thread.sleep(2300); instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
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
            instrumentation.runOnMainSync(() -> check(copies.size() == 11 && copies.get(10).getPixel(2, 2) == Color.GREEN
                            && !service.previewInFlight && renewals[0] == 0,
                    "A delayed first frame recovers on the next request using the same authorized capture"));
        } finally {
            instrumentation.runOnMainSync(() -> {
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
        LookupDestinationSettings.Model previousDestination = destinations.model();
        boolean previousThirdParty = destinations.isThirdParty();
        AtomicReference<Intent> request = new AtomicReference<>();
        int[] activities = {0};
        Context capture = new ContextWrapper(app) {
            @Override public ComponentName startService(Intent intent) {
                if (!MainActivity.ScreenCaptureService.ACT_OCR_CANCEL.equals(intent.getAction())) request.set(new Intent(intent));
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
            instrumentation.runOnMainSync(() -> { owner.set(new OcrPickupController(capture, host)); listener.set(owner.get().listener()); view.set(new View(app)); });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            instrumentation.runOnMainSync(() -> {
                try { java.lang.reflect.Field field=OcrPickupController.class.getDeclaredField("overlay");field.setAccessible(true);
                    check(field.get(owner.get()) != null && request.get()==null, "Pointer is mounted as soon as dragging starts, before dwell or screenshot");
                } catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 520, 720);
            instrumentation.runOnMainSync(() -> {
                check(moves[0] == 1 && saves[0] == 1 && position.left == 500, "Quick ordinary drag moves and saves the button");
                check(request.get() == null && activities[0] == 0 && taps[0] == 0, "Quick movement never starts OCR or authorization");
                position.set(rendered); moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_CANCEL, 520, 720);
            Thread.sleep(360);
            instrumentation.runOnMainSync(() -> {
                check(position.equals(rendered) && saves[0] == 1, "Cancel restores drag and prevents the pending dwell callback");
                check(request.get() == null && activities[0] == 0, "Canceled drag cannot request screen sharing");
                moves[0] = 0;
            });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 420, 720);
            instrumentation.runOnMainSync(() -> check(taps[0] == 1 && activities[0] == 0, "Short tap still opens global search"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            Thread.sleep(600);
            instrumentation.runOnMainSync(() -> check(request.get() == null && activities[0] == 0, "600ms hold does not activate the joystick"));
            Thread.sleep(170);
            instrumentation.runOnMainSync(() -> check(request.get() == null && activities[0] == 0, "Showing the joystick alone does not request sharing"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 320, 720);
            instrumentation.runOnMainSync(() -> MainActivity.ScreenCaptureService.READY=true);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 320, 720);
            instrumentation.runOnMainSync(() -> {
                check(position.equals(rendered) && saves[0] == 1, "Joystick springs back without changing the saved bubble position");
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
            instrumentation.runOnMainSync(() -> check(activities[0] == 1 && request.get() == null, "Explicit Japanese OCR reauthorizes a missing screen projection"));
            app.sendBroadcast(new Intent(MainActivity.OcrActivity.ACTION_FINISHED).setPackage(app.getPackageName()).putExtra("granted", false));
            Thread.sleep(100);
            instrumentation.runOnMainSync(() -> { MainActivity.ScreenCaptureService.READY = true; moves[0] = 0; });
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_DOWN, 420, 720);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 421, 721);
            instrumentation.runOnMainSync(() -> check(request.get() == null && moves[0] == 0, "Finger jitter cannot move the bubble or start OCR"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 520, 720);
            Thread.sleep(50);
            instrumentation.runOnMainSync(() -> check(request.get() == null, "Moving the button cannot start OCR before 150ms dwell"));
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_MOVE, 560, 720);
            Thread.sleep(50);
            instrumentation.runOnMainSync(() -> check(request.get() == null, "Travel to another pointer area resets the 150ms delay"));
            Thread.sleep(130);
            instrumentation.runOnMainSync(() -> check(request.get() != null && MainActivity.ScreenCaptureService.ACT_OCR_PREVIEW.equals(request.get().getAction())
                    && "word".equals(request.get().getStringExtra("pickup_mode")), "Normal dwell uses the composer language rather than inheriting a previous joystick mode"));
            long session = request.get().getLongExtra("session", -1);
            touch(instrumentation, listener.get(), view.get(), MotionEvent.ACTION_UP, 560, 720);
            instrumentation.runOnMainSync(() -> check(saves[0] == 2 && position.left == 540, "OCR drag saves the moved bubble once on release"));
            float y = 700-12*app.getResources().getDisplayMetrics().density;
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("words",new String[]{"日本語"}).putExtra("boxes",new int[]{540,(int)y-10,584,(int)y+10}));
            for (int n = 0; n < 30 && !MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()); n++) Thread.sleep(50);
            check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()), "Release requests localized refinement on the same captured frame");
            check("日本語".equals(request.get().getStringExtra("seed")) && request.get().getFloatExtra("aim_x",0) == 562,
                    "Aim uses the requested position before WindowManager renders the move");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","日本語"));
            for (int n = 0; n < 30 && query.get() == null; n++) Thread.sleep(50);
            check("日本語".equals(query.get()), "Refinement hands complete recognized text to direct lookup");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName()).putExtra("session",session)
                    .putExtra("refined",true).putExtra("text","stale"));
            Thread.sleep(100); check("日本語".equals(query.get()), "Canceled sessions reject old OCR updates");
            String[] modes={"sentence","japanese","english"};
            float[][] directions={{0,-100},{100,0},{0,100}};
            for(int index=0;index<directions.length;index++){
                request.set(null);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
                Thread.sleep(760);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,420+directions[index][0],720+directions[index][1]);
                touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_UP,420+directions[index][0],720+directions[index][1]);
                check(request.get()!=null && modes[index].equals(request.get().getStringExtra("pickup_mode")),"Joystick release dispatches "+modes[index]+" as a single-session mode");
                OcrLanguageSettings.Language[] expected={OcrLanguageSettings.Language.MIXED,OcrLanguageSettings.Language.JAPANESE,OcrLanguageSettings.Language.ENGLISH};
                check(OcrLanguageSettings.get(app).language()==expected[index],"Joystick selection also commits the shared cached language");
                instrumentation.runOnMainSync(owner.get()::cancel);
            }
            request.set(null);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            Thread.sleep(340);
            long revokedSession=request.get().getLongExtra("session",-1);
            int beforePermission=activities[0],beforeTaps=taps[0];
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session",revokedSession).putExtra("permission_required",true));
            Thread.sleep(100);
            check(activities[0]==beforePermission+1,"A capture-health failure reopens the transparent system authorization host");
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_UP,520,720);
            check(taps[0]==beforeTaps,"Release of the old gesture cannot open search while renewal is pending");
            app.sendBroadcast(new Intent(MainActivity.OcrActivity.ACTION_FINISHED).setPackage(app.getPackageName()).putExtra("granted",true));
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_READY).setPackage(app.getPackageName()));
            Thread.sleep(430);
            long renewedSession=request.get().getLongExtra("session",-1);
            check(renewedSession!=revokedSession,"Granted renewal starts a new pickup session after the grant UI closes");
            app.sendBroadcast(new Intent(MainActivity.ScreenCaptureService.ACTION_PREVIEW).setPackage(app.getPackageName())
                    .putExtra("session",revokedSession).putExtra("refined",true).putExtra("text","revoked"));
            Thread.sleep(100);
            check("日本語".equals(query.get()),"Revoked projection updates never reach lookup after reauthorization");
            instrumentation.runOnMainSync(() -> {owner.get().cancel();position.set(rendered);request.set(null);query.set(null);});
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_DOWN,420,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,570,720);
            touch(instrumentation,listener.get(),view.get(),MotionEvent.ACTION_MOVE,520,720);
            Thread.sleep(190);
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
            check(MainActivity.ScreenCaptureService.ACT_OCR_REFINE.equals(request.get().getAction()) && "father".equals(request.get().getStringExtra("seed")),
                    "A real f-to-r-to-f gesture made while recognition is pending is replayed into the exact mixed-text range");
            check(request.get().getBooleanExtra("bounded_range",false),"Refinement carries the explicit range so it cannot expand back to the whole phrase");
            int[] selectionBoxes=request.get().getIntArrayExtra("selection_boxes");
            check(selectionBoxes!=null && selectionBoxes.length==4 && selectionBoxes[0]==517 && selectionBoxes[2]==577,
                    "Refinement carries the actual swept glyph bounds instead of only a union rectangle");
            instrumentation.runOnMainSync(() -> {
                MainActivity.ScreenCaptureService detached = new MainActivity.ScreenCaptureService();
                check(!detached.captureAlive(), "A stale READY flag without actual projection, reader and virtual display cannot authorize capture");
            });
        } finally {
            instrumentation.runOnMainSync(() -> { if (owner.get() != null) owner.get().close(); destinations.select(previousDestination); });
            MainActivity.ScreenCaptureService.READY = wasReady;
        }
    }
    private static void touch(Instrumentation test, View.OnTouchListener listener, View view, int action, float x, float y) {
        test.runOnMainSync(() -> {
            long time = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
            try { listener.onTouch(view, event); } finally { event.recycle(); }
        });
    }
    private String fonts(Instrumentation instrumentation) throws Exception {
        AtomicReference<MultilingualOcrRecognizer> client = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { client.set(new MultilingualOcrRecognizer()); client.get().prepareModels(instrumentation.getTargetContext()); });
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
                instrumentation.runOnMainSync(() -> client.get().recognizeRegions(image, new MultilingualOcrRecognizer.RegionsCallback() {
                    public void onSuccess(List<MultilingualOcrRecognizer.Region> found) { regions.set(found); detected.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); detected.countDown(); }
                }));
                if (!detected.await(20, TimeUnit.SECONDS)) throw new AssertionError("Font probe timeout");
                MultilingualOcrRecognizer.Region seed = regions.get() == null ? null : MultilingualOcrRecognizer.regionAt(regions.get(), target.exactCenterX(), target.exactCenterY(), 8);
                String before = seed == null ? "" : seed.text;
                AtomicReference<String> after = new AtomicReference<>(""); CountDownLatch enhanced = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> client.get().recognizeSelection(image,target,seed,target.exactCenterX(),target.exactCenterY(),8,new MultilingualOcrRecognizer.Callback() {
                    public void onSuccess(String text) { after.set(text); enhanced.countDown(); }
                    public void onFailure(Exception e) { error.set(e.getMessage()); enhanced.countDown(); }
                }));
                if (!enhanced.await(20,TimeUnit.SECONDS)) throw new AssertionError("Refined probe timeout");
                if (word.equals(before)) original++; if (word.equals(after.get())) refined++; total++;
                report.append(word).append(' ').append(family).append(' ').append(size).append("px: ").append(before).append(" -> ").append(after.get()).append(error.get() == null ? "" : " ["+error.get()+"]").append('\n');
                image.recycle();
            }
            report.insert(0, "Font exact matches: original="+original+", refined="+refined+", total="+total+"\n");
        } finally { instrumentation.runOnMainSync(() -> client.get().close()); }
        return report.toString();
    }
}
