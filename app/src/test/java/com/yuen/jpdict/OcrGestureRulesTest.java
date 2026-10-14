package com.yuen.jpdict;

/** No Android/mock dependency: these are the rules used by the actual gesture. */
public final class OcrGestureRulesTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        OcrDwellGate dwell = new OcrDwellGate(12);
        check(dwell.move(100, 100, 1000) == 300, "A new area requires a full 300ms");
        check(dwell.move(102, 99, 1200) == 100, "Small jitter preserves dwell");
        check(dwell.move(110, 100, 1299) == 1, "299ms does not arm OCR");
        check(dwell.move(100, 101, 1300) == 0, "300ms permits OCR");
        check(dwell.move(125, 100, 1320) == 300, "Travel to another area restarts dwell");
        dwell.reset(); check(dwell.move(125, 100, 1600) == 300, "New gestures cannot inherit previous dwell");
        check(OcrPickupMode.direction(0, -50, 28) == 0, "Up selects sentence");
        check(OcrPickupMode.direction(50, 0, 28) == 1, "Right selects Japanese");
        check(OcrPickupMode.direction(0, 50, 28) == 2, "Down selects English");
        check(OcrPickupMode.direction(-50, 0, 28) == 3, "Left cancels");
        check(OcrPickupMode.direction(18, 18, 28) == -1, "Diagonal finger jitter stays in the dead zone");
        check(OcrPickupMode.direction(25, -50, 28) == 0, "Sector chooses the dominant vertical direction");
        check(OcrPickupMode.forDirection(0) == OcrPickupMode.SENTENCE && OcrPickupMode.forDirection(3) == null, "Direction dispatch does not submit cancellation");
        check("Hold résumé naïve".equals(OcrPickupMode.ENGLISH.clean("中文 Hold 日本語 résumé 한국어 naïve 123")), "Only western letters survive mixed text");
        check("can't well-being l’été".equals(OcrPickupMode.ENGLISH.clean("can't well-being l’été")), "Internal apostrophes and hyphens remain words");
        check("foo bar".equals(OcrPickupMode.ENGLISH.clean("foo中文bar")), "Discarded scripts cannot join unrelated words");
        check(OcrPickupMode.ENGLISH.clean("中文 にほん 한국어 123 🙂").isEmpty(), "Non-Latin captures cannot trigger an English lookup");
        check("cafe\u0301".equals(OcrPickupMode.ENGLISH.clean("cafe\u0301")), "Decomposed Latin accents are retained");
        check("".equals(OcrPickupMode.ENGLISH.clean("αβγ кириллица العربية")), "Greek, Cyrillic and Arabic are excluded");
        String original = "中文 / 日本語 / 한국어\nAn entire sentence.";
        check(original.equals(OcrPickupMode.SENTENCE.clean(original)), "Sentence mode preserves all original scripts and line breaks");
        check(original.equals(OcrPickupMode.WORD.clean(original)), "Existing generic pickup text is never language-cleaned");
        check(OcrPickupMode.fromValue("old") == OcrPickupMode.WORD, "Unrecognized persisted/requested modes preserve normal pickup");
        System.out.println("OCR gesture rules: " + checks + " assertions passed");
    }
}
