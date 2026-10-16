package com.yuen.jpdict;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Reusable OCR-only facade for Latin, Chinese, Japanese and Korean scripts.
 * Recognition models are bundled in the APK so recognition works without Google Play services.
 */
final class MultilingualOcrRecognizer implements AutoCloseable {
    interface Callback {
        void onSuccess(String text);
        void onFailure(Exception error);
    }
    interface RegionsCallback {
        void onSuccess(List<Region> regions);
        void onFailure(Exception error);
    }
    static final class Region {
        final String text;
        final Rect bounds;
        final List<Symbol> symbols = new ArrayList<>();
        final List<Rect> selectedBounds = new ArrayList<>();
        float quality;
        boolean boundedRange;
        Region(String text, Rect bounds) { this.text = text; this.bounds = new Rect(bounds); }
        Region slice(int first, int last) {
            Symbol begin = symbols.get(first), end = symbols.get(last);
            Rect selected = new Rect();
            for (int i = first; i <= last; i++) selected.union(symbols.get(i).bounds);
            Region result = new Region(text.substring(begin.start, end.end), selected);
            result.quality = quality; result.boundedRange = true;
            result.selectedBounds.add(new Rect(selected));
            return result;
        }
        Region clean(OcrPickupMode mode) {
            String cleaned = mode.clean(text);
            if (cleaned.equals(text)) return this;
            Region result = new Region(cleaned, bounds); result.quality = quality;
            int cursor = 0;
            for (Symbol symbol : symbols) {
                String letter = mode.clean(text.substring(symbol.start, symbol.end));
                if (letter.isEmpty()) continue;
                int start = cleaned.indexOf(letter, cursor);
                if (start < 0) { result.symbols.clear(); break; }
                cursor = start + letter.length();
                result.symbols.add(new Symbol(start, cursor, symbol.bounds));
            }
            return result;
        }
    }
    static final class Symbol {
        final int start, end;
        final Rect bounds;
        Symbol(int start, int end, Rect bounds) {
            this.start = start; this.end = end; this.bounds = new Rect(bounds);
        }
    }

    enum Script {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN
    }

    private final Object lifecycleLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    // A retired model gets its own worker; one stuck SDK entry cannot occupy
    // the replacement recognizer's lane. Never run rejected work on the UI.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "jpdict-ocr"));
    private final TextRecognizer latin;
    private final TextRecognizer chinese;
    private final TextRecognizer japanese;
    private final TextRecognizer korean;
    private OcrLanguageSettings languageSettings;

    private int inFlight;
    private boolean closeRequested;
    private boolean closed;

    MultilingualOcrRecognizer() {
        this(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()), null);
    }

    MultilingualOcrRecognizer(TextRecognizer latin, TextRecognizer chinese, TextRecognizer japanese,
            TextRecognizer korean, OcrLanguageSettings settings) {
        this.latin = latin; this.chinese = chinese; this.japanese = japanese; this.korean = korean;
        languageSettings = settings;
        worker.allowCoreThreadTimeOut(true);
    }
    void initialize(Context context) {
        languageSettings = OcrLanguageSettings.get(context);
    }

    void recognize(Bitmap bitmap, Callback callback) {
        process(bitmap, language(), false, onMain(callback), null);
    }
    void recognizeRegions(Bitmap bitmap, RegionsCallback callback) {
        recognizeRegions(bitmap, language(), callback);
    }
    void recognizeRegions(Bitmap bitmap, OcrLanguageSettings.Language language, RegionsCallback callback) {
        recognizeRegions(bitmap, language, false, callback);
    }
    void recognizeRegions(Bitmap bitmap, OcrLanguageSettings.Language language, boolean sentences, RegionsCallback callback) {
        process(bitmap, language, sentences, null, onMain(callback));
    }
    void recognizeSelection(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance, Callback callback) {
        recognizeSelection(screen, target, seed, x, y, tolerance, language(), callback);
    }
    void recognizeSelection(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance,
            OcrLanguageSettings.Language language, Callback callback) {
        recognizeSelection(screen,target,seed,x,y,tolerance,language,false,callback);
    }
    void recognizeSelection(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance,
            OcrLanguageSettings.Language language, boolean sentences, Callback callback) {
        List<Region> found = new ArrayList<>();
        if (seed != null) found.add(seed);
        refineVariant(screen, target, seed, x, y, tolerance, language, sentences, false, found, onMain(callback));
    }
    private void refineVariant(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance,
            OcrLanguageSettings.Language language, boolean sentences, boolean enhanced, List<Region> found, Callback callback) {
        execute(() -> prepareVariant(screen, target, seed, x, y, tolerance, language, sentences,
                enhanced, found, callback), error -> completeSelection(found, seed, x, y, tolerance, callback, error));
    }
    private void prepareVariant(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance,
            OcrLanguageSettings.Language language, boolean sentences, boolean enhanced, List<Region> found, Callback callback) {
        final OcrImagePreparation prepared;
        try { prepared = OcrImagePreparation.prepare(screen, target, enhanced, seed == null ? 12 : seed.bounds.height()); }
        catch (RuntimeException error) { completeSelection(found, seed, x, y, tolerance, callback, error); return; }
        process(prepared.image, language, sentences, null, new RegionsCallback() {
            public void onSuccess(List<Region> regions) {
                List<Region> mappedRegions = new ArrayList<>();
                for (Region region : regions) {
                    Region mapped = new Region(region.text, prepared.toScreen(region.bounds));
                    for (Symbol symbol : region.symbols)
                        mapped.symbols.add(new Symbol(symbol.start, symbol.end, prepared.toScreen(symbol.bounds)));
                    mapped.quality = region.quality; mappedRegions.add(mapped);
                }
                // Assemble this pass before merging variants. A swept phrase
                // may span several model elements, none of which alone covers
                // enough of the seed to qualify as a refinement.
                if (language == OcrLanguageSettings.Language.ENGLISH && !sentences) {
                    Region word = OcrSweepSelection.englishWordAt(mappedRegions, x, y, tolerance);
                    mappedRegions = word == null ? Collections.emptyList() : Collections.singletonList(word);
                }
                Region candidate = selectionResult(mappedRegions, seed, x, y, tolerance);
                if (candidate != null && candidate != seed) merge(found, candidate);
                next(null);
            }
            public void onFailure(Exception error) { next(error); }
            private void next(Exception error) {
                prepared.close();
                if (!enhanced) refineVariant(screen, target, seed, x, y, tolerance, language, sentences, true, found, callback);
                else completeSelection(found, seed, x, y, tolerance, callback, error);
            }
        });
    }
    private static void completeSelection(List<Region> found, Region seed, float x, float y, float tolerance, Callback callback, Exception error) {
        // Every variant added to found already passed selectionResult against
        // the original mask. Do not crop a validated multi-line phrase again.
        Region best = regionAt(found, x, y, tolerance);
        if (best == null) best = seed;
        if (best != null) callback.onSuccess(best.text);
        else callback.onFailure(error != null ? error : new IllegalStateException("准星附近没有识别到文字"));
    }
    static Region selectionResult(List<Region> found, Region seed, float x, float y, float tolerance) {
        // A user-defined character range must not expand to a whole model element
        // during refinement. Preserve it when the model cannot supply glyphs.
        if (seed != null && seed.boundedRange) {
            if (seed.selectedBounds.isEmpty() && seed.text.indexOf('\n')>=0) return seed;
            List<Rect> mask = seed.selectedBounds.isEmpty() ? java.util.Collections.singletonList(seed.bounds) : seed.selectedBounds;
            List<Region> clipped = new ArrayList<>();
            OcrSweepSelection selection = new OcrSweepSelection();
            for (Region region : found) {
                if (region == seed) continue;
                for (Rect range : mask) {
                    int first = -1, last = -1;
                    for (int i = 0; i < region.symbols.size(); i++) {
                        Rect b = region.symbols.get(i).bounds;
                        if (range.contains(b.centerX(), b.centerY())) {
                            if (first < 0) first = i;
                            last = i;
                        }
                    }
                    Region piece = null;
                    if (first >= 0) piece = region.slice(first, last);
                    else if ((range.contains(region.bounds) || preservesSelection(range, region.bounds))
                            && region.bounds.width() <= range.width() * 1.15f
                            && region.bounds.height() <= range.height() * 1.15f) piece = region;
                    if (piece != null) merge(clipped, piece);
                }
            }
            // Different scripts may segment the same glyphs as one whole
            // element or several parts. Prefer the smaller, precise elements
            // and never concatenate overlapping alternatives as extra words.
            Collections.sort(clipped, (first, second) -> {
                int area = Long.compare((long)first.bounds.width()*first.bounds.height(),
                        (long)second.bounds.width()*second.bounds.height());
                return area!=0 ? area : Float.compare(second.quality,first.quality);
            });
            List<Region> distinct = new ArrayList<>();
            for (Region region : clipped) {
                boolean overlap = false;
                for (Region accepted : distinct) if (Rect.intersects(region.bounds,accepted.bounds)) { overlap=true;break; }
                if (!overlap) { distinct.add(region);selection.touch(region,OcrPickupMode.WORD); }
            }
            Region combined = selection.combined();
            if (combined != null && selection.picked.size() > 1) {
                clipped.clear(); clipped.add(combined);
            }
            found = clipped;
        }
        Region best = regionAt(found, x, y, tolerance);
        if (best == null) return seed;
        if (seed != null && best != null && !best.text.equals(seed.text)
                && (!preservesSelection(seed.bounds, best.bounds)
                || (seed.boundedRange && !preservesRangeEdges(seed.bounds, best.bounds))
                || best.quality < seed.quality+0.05f)) best = seed;
        return best;
    }
    private static boolean preservesRangeEdges(Rect original, Rect refined) {
        float horizontal = Math.max(1, original.width()*0.04f), vertical = Math.max(1, original.height()*0.04f);
        return refined.left <= original.left+horizontal && refined.right >= original.right-horizontal
                && refined.top <= original.top+vertical && refined.bottom >= original.bottom-vertical;
    }
    static Region regionAt(List<Region> regions, float x, float y, float tolerance) {
        Region selected = null; double best = Double.MAX_VALUE; boolean bestInside = false;
        for (Region region : regions) {
            Rect b = region.bounds;
            boolean inside = x >= b.left && x < b.right && y >= b.top && y < b.bottom;
            double dx = Math.max(Math.max(b.left-x, x-b.right), 0), dy = Math.max(Math.max(b.top-y, y-b.bottom), 0);
            double distance = dx*dx+dy*dy;
            if (!inside && (tolerance <= 0 || distance > tolerance*tolerance)) continue;
            double rank = distance+(double)b.width()*b.height()*0.0001;
            if ((inside && !bestInside) || (inside == bestInside && rank < best)) {
                best = rank; selected = region; bestInside = inside;
            }
        }
        return selected;
    }
    private OcrLanguageSettings.Language language() {
        return languageSettings == null ? OcrLanguageSettings.Language.MIXED : languageSettings.language();
    }

    static Script[] scriptsFor(OcrLanguageSettings.Language language) {
        switch (language) {
            case ENGLISH: return new Script[] { Script.LATIN };
            case JAPANESE: return new Script[] { Script.JAPANESE };
            case KOREAN: return new Script[] { Script.KOREAN };
            default: return Script.values();
        }
    }

    private TextRecognizer client(Script script) {
        switch (script) {
            case CHINESE: return chinese;
            case JAPANESE: return japanese;
            case KOREAN: return korean;
            default: return latin;
        }
    }

    private void process(Bitmap bitmap, OcrLanguageSettings.Language language, boolean sentences, Callback callback, RegionsCallback regionsCallback) {
        synchronized (lifecycleLock) {
            if (closeRequested || closed) {
                fail(callback, regionsCallback, new IllegalStateException("OCR 识别器已关闭"));
                return;
            }
            inFlight++;
        }

        execute(() -> startProcess(bitmap, language, sentences, callback, regionsCallback), error -> {
            finishRequest(); fail(callback, regionsCallback, error);
        });
    }
    private void startProcess(Bitmap bitmap, OcrLanguageSettings.Language language, boolean sentences,
            Callback callback, RegionsCallback regionsCallback) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            Script[] scripts = scriptsFor(language);
            List<Task<Text>> tasks = new ArrayList<>(scripts.length);
            for (Script script : scripts) {
                // A synchronous SDK error must still await already-started clients
                // before releasing the shared bitmap and lifecycle reference.
                try { tasks.add(client(script).process(image)); }
                catch (Exception error) { tasks.add(Tasks.forException(error)); }
            }

            Tasks.whenAllComplete(tasks)
                    .addOnCompleteListener(Runnable::run, ignored -> execute(() -> {
                        try {
                            if (regionsCallback != null) {
                                List<Region> found = new ArrayList<>();
                                for (int i = 0; i < scripts.length; i++) addRegions(found, tasks.get(i), scripts[i], sentences);
                                if (!found.isEmpty()) regionsCallback.onSuccess(found);
                                else {
                                    Exception error = firstError(tasks);
                                    fail(null, regionsCallback, error != null ? error : new IllegalStateException("没有识别到文字"));
                                }
                                return;
                            }
                            String best = "";
                            int bestScore = Integer.MIN_VALUE;

                            for (int i = 0; i < scripts.length; i++) {
                                Task<Text> task = tasks.get(i);
                                if (task.isSuccessful()) {
                                    String value = textOf(task);
                                    int score = score(value, scripts[i]);
                                    if (score > bestScore) { best = value; bestScore = score; }
                                }
                            }

                            // Pass the full recognized text verbatim. The Groq
                            // prompt, not this OCR layer, determines its language.
                            if (!best.trim().isEmpty()) {
                                callback.onSuccess(best);
                                return;
                            }

                            Exception error = firstError(tasks);
                            fail(callback, regionsCallback, error != null
                                    ? error
                                    : new IllegalStateException("没有识别到文字"));
                        } finally {
                            finishRequest();
                        }
                    }, error -> {
                        finishRequest(); fail(callback, regionsCallback, error);
                    }));
        } catch (Exception e) {
            finishRequest();
            fail(callback, regionsCallback, e);
        }
    }
    private interface Rejected { void fail(Exception error); }
    private void execute(Runnable work, Rejected rejected) {
        try { worker.execute(work); }
        catch (RejectedExecutionException error) {
            rejected.fail(new IllegalStateException("OCR 识别器正忙或已关闭，请重试", error));
        }
    }
    private Callback onMain(Callback callback) {
        return new Callback() {
            public void onSuccess(String text) { main.post(() -> callback.onSuccess(text)); }
            public void onFailure(Exception error) { main.post(() -> callback.onFailure(error)); }
        };
    }
    private RegionsCallback onMain(RegionsCallback callback) {
        return new RegionsCallback() {
            public void onSuccess(List<Region> regions) { main.post(() -> callback.onSuccess(regions)); }
            public void onFailure(Exception error) { main.post(() -> callback.onFailure(error)); }
        };
    }
    private static void fail(Callback text, RegionsCallback regions, Exception error) {
        if (regions != null) regions.onFailure(error); else text.onFailure(error);
    }
    private static void addRegions(List<Region> regions, Task<Text> task, Script script, boolean sentences) {
        if (!task.isSuccessful() || task.getResult() == null) return;
        Text result = task.getResult();
        for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
            if (sentences) {
                Region candidate = createRegion(line.getText(), line.getBoundingBox(), script, line.getConfidence());
                if (candidate == null) continue;
                int cursor = 0;
                for (Text.Element element : line.getElements()) {
                    int start = candidate.text.indexOf(element.getText(), cursor);
                    if (start < 0) continue;
                    addSymbols(candidate, element, start); cursor = start + element.getText().length();
                }
                merge(regions, candidate);
            } else for (Text.Element element : line.getElements()) {
                Region candidate = createRegion(element.getText(), element.getBoundingBox(), script, element.getConfidence());
                if (candidate == null) continue;
                addSymbols(candidate, element, 0); merge(regions, candidate);
            }
        }
    }
    private static void addSymbols(Region region, Text.Element element, int offset) {
        int cursor = 0;
        for (Text.Symbol symbol : element.getSymbols()) {
            Rect bounds = symbol.getBoundingBox();
            String text = symbol.getText();
            int start = element.getText().indexOf(text, cursor);
            if (start < 0 || bounds == null || bounds.isEmpty() || text.isEmpty()) continue;
            cursor = start + text.length();
            region.symbols.add(new Symbol(offset + start, offset + cursor, bounds));
        }
    }
    private static Region createRegion(String text, Rect bounds, Script script, float confidence) {
        if (bounds == null || bounds.isEmpty() || text.trim().isEmpty()) return null;
        Region candidate = new Region(text, bounds);
        int letters = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) letters++;
        candidate.quality = (float)score(text, script) / Math.max(1, letters) + confidence;
        return candidate;
    }
    private static boolean overlaps(Rect first, Rect second) {
        if (!Rect.intersects(first, second)) return false;
        Rect overlap = new Rect(first); overlap.intersect(second);
        long area = (long)overlap.width()*overlap.height();
        long union = (long)first.width()*first.height()+(long)second.width()*second.height()-area;
        return area >= union*0.55;
    }
    private static boolean preservesSelection(Rect original, Rect refined) {
        if (!overlaps(original, refined)) return false;
        Rect intersection = new Rect(original); intersection.intersect(refined);
        return (long)intersection.width()*intersection.height() >= (long)original.width()*original.height()*0.85;
    }
    private static void merge(List<Region> regions, Region candidate) {
        for (int i = 0; i < regions.size(); i++) {
            Region old = regions.get(i);
            if (!overlaps(old.bounds, candidate.bounds)) continue;
            if (old.text.equals(candidate.text)) {
                old.quality = Math.max(old.quality, candidate.quality)+0.02f;
                if (old.symbols.isEmpty()) old.symbols.addAll(candidate.symbols);
            } else if (candidate.quality > old.quality) regions.set(i, candidate);
            return;
        }
        regions.add(candidate);
    }

    @Override public void close() {
        boolean shouldClose;
        synchronized (lifecycleLock) {
            closeRequested = true;
            shouldClose = inFlight == 0 && !closed;
        }
        if (shouldClose) closeClients();
    }

    private void finishRequest() {
        boolean shouldClose;
        synchronized (lifecycleLock) {
            if (inFlight > 0) inFlight--;
            shouldClose = closeRequested && inFlight == 0 && !closed;
        }
        if (shouldClose) closeClients();
    }

    private void closeClients() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
        }
        worker.shutdown();
        latin.close();
        chinese.close();
        japanese.close();
        korean.close();
    }

    private static String textOf(Task<Text> task) {
        Text result = task.getResult();
        return result == null ? "" : result.getText();
    }

    private static Exception firstError(List<Task<Text>> tasks) {
        for (Task<Text> task : tasks) {
            if (!task.isSuccessful() && task.getException() != null) {
                return task.getException();
            }
        }
        return null;
    }

    private static int score(String text, Script script) {
        if (text == null || text.trim().isEmpty()) return Integer.MIN_VALUE / 2;

        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (isLatin(c)) {
                score += script == Script.LATIN ? 5 : 1;
            } else if (isKana(c)) {
                score += script == Script.JAPANESE ? 6 : 1;
            } else if (isHangul(c)) {
                score += script == Script.KOREAN ? 6 : 1;
            } else if (isHan(c)) {
                score += script == Script.CHINESE ? 5
                        : script == Script.JAPANESE ? 5
                        : 1;
            } else if (Character.isDigit(c)) {
                score += 2;
            }
        }
        return score;
    }

    private static boolean isLatin(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isKana(char c) {
        return (c >= '\u3040' && c <= '\u30ff') || c == 'ー';
    }

    private static boolean isHan(char c) {
        return (c >= '\u3400' && c <= '\u9fff') || c == '々' || c == '〆';
    }

    private static boolean isHangul(char c) {
        return (c >= '\u1100' && c <= '\u11ff')
                || (c >= '\u3130' && c <= '\u318f')
                || (c >= '\uac00' && c <= '\ud7af');
    }
}
