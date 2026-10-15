package com.yuen.jpdict;

/** Explicit pickup intent; the joystick also commits its language to shared settings. */
enum OcrPickupMode {
    WORD("word", "取词"), SENTENCE("sentence", "句子 OCR"),
    JAPANESE("japanese", "日语 OCR"), ENGLISH("english", "英语 OCR");

    final String value, label;
    OcrPickupMode(String value, String label) { this.value = value; this.label = label; }
    static OcrPickupMode fromValue(String value) {
        for (OcrPickupMode mode : values()) if (mode.value.equals(value)) return mode;
        return WORD;
    }
    /** Four 90 degree sectors, with a dead zone around the resting knob. */
    static int direction(float dx, float dy, float deadZone) {
        if (Math.hypot(dx, dy) < deadZone) return -1;
        double degrees = Math.toDegrees(Math.atan2(dy, dx));
        return (int)Math.floor((degrees + 135 + 360) % 360 / 90);
    }
    static OcrPickupMode forDirection(int direction) {
        return direction == 0 ? SENTENCE : direction == 1 ? JAPANESE : direction == 2 ? ENGLISH : null;
    }
    String clean(String text) {
        if (text == null) return "";
        if (this != ENGLISH) return text;
        // Only Latin-script letters (including accents) and in-word punctuation
        // survive. Replacing other scripts by separators avoids joining words.
        StringBuilder out = new StringBuilder();
        boolean separated = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            boolean latin = isLatinLetter(cp);
            boolean connector = (cp == '\'' || cp == '’' || cp == '-') && out.length() > 0 && !separated
                    && offset < text.length() && isLatinLetter(text.codePointAt(offset));
            boolean mark = Character.getType(cp) == Character.NON_SPACING_MARK && out.length() > 0 && !separated;
            if (latin || connector || mark) {
                if (separated && out.length() > 0) out.append(' ');
                out.appendCodePoint(cp); separated = false;
            } else separated = true;
        }
        return out.toString();
    }
    private static boolean isLatinLetter(int cp) {
        return Character.isLetter(cp) && (cp <= 0x024f || (cp >= 0x1d00 && cp <= 0x1eff)
                || (cp >= 0x2c60 && cp <= 0x2c7f) || (cp >= 0xa720 && cp <= 0xa7ff)
                || (cp >= 0xab30 && cp <= 0xab6f) || (cp >= 0xff21 && cp <= 0xff5a)
                || (cp >= 0x10780 && cp <= 0x107bf) || (cp >= 0x1df00 && cp <= 0x1dfff));
    }
}
