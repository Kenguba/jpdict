package com.yuen.jpdict;

import android.graphics.Bitmap;

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
 * Recognition models are provided by Google Play services to keep APK size low.
 */
final class MultilingualOcrRecognizer implements AutoCloseable {
    interface Callback {
        void onSuccess(String text);
        void onFailure(Exception error);
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

    void recognize(Bitmap bitmap, Callback callback) {
        synchronized (lifecycleLock) {
            if (closeRequested || closed) {
                callback.onFailure(new IllegalStateException("OCR 识别器已关闭"));
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
                            callback.onFailure(error != null
                                    ? error
                                    : new IllegalStateException("没有识别到文字"));
                        } finally {
                            finishRequest();
                        }
                    });
        } catch (Exception e) {
            finishRequest();
            callback.onFailure(e);
        }
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
                        : script == Script.JAPANESE ? 4
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
