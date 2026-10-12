package com.yuen.jpdict;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.common.MlKitException;
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
 * Recognition models are provided by Google Play services to keep APK size low.
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
        float quality;
        Region(String text, Rect bounds) { this.text = text; this.bounds = new Rect(bounds); }
    }

    private enum Script {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN
    }

    private final Object lifecycleLock = new Object();
    private final TextRecognizer latin;
    private final TextRecognizer chinese;
    private final TextRecognizer japanese;
    private final TextRecognizer korean;

    private int inFlight;
    private boolean closeRequested;
    private boolean closed;

    MultilingualOcrRecognizer() {
        latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        chinese = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        japanese = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build());
        korean = TextRecognition.getClient(
                new KoreanTextRecognizerOptions.Builder().build());
    }
    void prepareModels(Context context) {
        // An accepted request is not proof of installation; process() still
        // reports UNAVAILABLE until Play services finishes each model download.
        ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder()
                .addApi(latin).addApi(chinese).addApi(japanese).addApi(korean).build());
    }

    void recognize(Bitmap bitmap, Callback callback) {
        process(bitmap, callback, null);
    }
    void recognizeRegions(Bitmap bitmap, RegionsCallback callback) {
        process(bitmap, null, callback);
    }
    void recognizeSelection(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance, Callback callback) {
        List<Region> found = new ArrayList<>();
        if (seed != null) found.add(seed);
        refineVariant(screen, target, seed, x, y, tolerance, false, found, callback);
    }
    private void refineVariant(Bitmap screen, Rect target, Region seed, float x, float y, float tolerance,
            boolean enhanced, List<Region> found, Callback callback) {
        final OcrImagePreparation prepared;
        try { prepared = OcrImagePreparation.prepare(screen, target, enhanced, seed == null ? 12 : seed.bounds.height()); }
        catch (RuntimeException error) { completeSelection(found, seed, x, y, tolerance, callback, error); return; }
        recognizeRegions(prepared.image, new RegionsCallback() {
            public void onSuccess(List<Region> regions) {
                for (Region region : regions) {
                    Region mapped = new Region(region.text, prepared.toScreen(region.bounds));
                    mapped.quality = region.quality; merge(found, mapped);
                }
                next(null);
            }
            public void onFailure(Exception error) { next(error); }
            private void next(Exception error) {
                prepared.close();
                if (!enhanced) refineVariant(screen, target, seed, x, y, tolerance, true, found, callback);
                else completeSelection(found, seed, x, y, tolerance, callback, error);
            }
        });
    }
    private static void completeSelection(List<Region> found, Region seed, float x, float y, float tolerance, Callback callback, Exception error) {
        Region best = regionAt(found, x, y, tolerance);
        if (seed != null && best != null && !best.text.equals(seed.text)
                && (!preservesSelection(seed.bounds, best.bounds) || best.quality < seed.quality+0.05f)) best = seed;
        if (best != null) callback.onSuccess(best.text);
        else callback.onFailure(error != null ? error : new IllegalStateException("准星附近没有识别到文字"));
    }
    static Region regionAt(List<Region> regions, float x, float y, float tolerance) {
        Region selected = null; double best = Double.MAX_VALUE;
        for (Region region : regions) {
            Rect b = region.bounds;
            double dx = Math.max(Math.max(b.left-x, x-b.right), 0), dy = Math.max(Math.max(b.top-y, y-b.bottom), 0);
            double distance = dx*dx+dy*dy;
            if (distance > tolerance*tolerance) continue;
            double rank = distance+(double)b.width()*b.height()*0.0001;
            if (rank < best) { best = rank; selected = region; }
        }
        return selected;
    }
    private void process(Bitmap bitmap, Callback callback, RegionsCallback regionsCallback) {
        synchronized (lifecycleLock) {
            if (closeRequested || closed) {
                fail(callback, regionsCallback, new IllegalStateException("OCR 识别器已关闭"));
                return;
            }
            inFlight++;
        }

        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            Task<Text> latinTask = latin.process(image);
            Task<Text> chineseTask = chinese.process(image);
            Task<Text> japaneseTask = japanese.process(image);
            Task<Text> koreanTask = korean.process(image);

            Tasks.whenAllComplete(latinTask, chineseTask, japaneseTask, koreanTask)
                    .addOnCompleteListener(ignored -> {
                        try {
                            if (regionsCallback != null) {
                                List<Region> found = new ArrayList<>();
                                addRegions(found, latinTask, Script.LATIN);
                                addRegions(found, chineseTask, Script.CHINESE);
                                addRegions(found, japaneseTask, Script.JAPANESE);
                                addRegions(found, koreanTask, Script.KOREAN);
                                if (!found.isEmpty()) regionsCallback.onSuccess(found);
                                else {
                                    Exception error = firstError(latinTask, chineseTask, japaneseTask, koreanTask);
                                    fail(null, regionsCallback, error != null ? error : new IllegalStateException("没有识别到文字"));
                                }
                                return;
                            }
                            String best = "";
                            int bestScore = Integer.MIN_VALUE;

                            if (latinTask.isSuccessful()) {
                                String value = textOf(latinTask);
                                int score = score(value, Script.LATIN);
                                if (score > bestScore) {
                                    best = value;
                                    bestScore = score;
                                }
                            }

                            if (chineseTask.isSuccessful()) {
                                String value = textOf(chineseTask);
                                int score = score(value, Script.CHINESE);
                                if (score > bestScore) {
                                    best = value;
                                    bestScore = score;
                                }
                            }

                            if (japaneseTask.isSuccessful()) {
                                String value = textOf(japaneseTask);
                                int score = score(value, Script.JAPANESE);
                                if (score > bestScore) {
                                    best = value;
                                    bestScore = score;
                                }
                            }

                            if (koreanTask.isSuccessful()) {
                                String value = textOf(koreanTask);
                                int score = score(value, Script.KOREAN);
                                if (score > bestScore) {
                                    best = value;
                                    bestScore = score;
                                }
                            }

                            // Pass the full recognized text verbatim. The Groq
                            // prompt, not this OCR layer, determines its language.
                            if (!best.trim().isEmpty()) {
                                callback.onSuccess(best);
                                return;
                            }

                            Exception error = firstError(
                                    latinTask, chineseTask, japaneseTask, koreanTask);
                            fail(callback, regionsCallback, error != null
                                    ? error
                                    : new IllegalStateException("没有识别到文字"));
                        } finally {
                            finishRequest();
                        }
                    });
        } catch (Exception e) {
            finishRequest();
            fail(callback, regionsCallback, e);
        }
    }
    private static void fail(Callback text, RegionsCallback regions, Exception error) {
        if (error instanceof MlKitException && ((MlKitException)error).getErrorCode() == MlKitException.UNAVAILABLE)
            error = new IllegalStateException("OCR 识别模型尚未就绪，请保持联网让 Google Play 服务完成中 / 日 / 英模型下载后重试", error);
        if (regions != null) regions.onFailure(error); else text.onFailure(error);
    }
    private static void addRegions(List<Region> regions, Task<Text> task, Script script) {
        if (!task.isSuccessful() || task.getResult() == null) return;
        Text result = task.getResult();
        for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
            for (Text.Element element : line.getElements()) {
                Rect bounds = element.getBoundingBox();
                String text = element.getText();
                if (bounds == null || bounds.isEmpty() || text.trim().isEmpty()) continue;
                Region candidate = new Region(text, bounds);
                int letters = 0;
                for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) letters++;
                candidate.quality = (float)score(text, script) / Math.max(1, letters) + element.getConfidence();
                merge(regions, candidate);
            }
        }
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
        latin.close();
        chinese.close();
        japanese.close();
        korean.close();
    }

    private static String textOf(Task<Text> task) {
        Text result = task.getResult();
        return result == null ? "" : result.getText();
    }

    private static Exception firstError(Task<Text>... tasks) {
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
