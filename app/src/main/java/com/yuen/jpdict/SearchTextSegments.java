package com.yuen.jpdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Original-text ranges and longest dictionary phrase matching, independent of Android. */
final class SearchTextSegments {
    private static final int PAGE_UNITS = 48, PHRASE_UNITS = 8, MAX_TERM_CHARS = 128;
    static final class Part {
        final int start, end;
        final String text;
        final boolean word, matched;
        Part(String source, int start, int end, boolean word, boolean matched) {
            this.start = start; this.end = end; this.text = source.substring(start, end);
            this.word = word; this.matched = matched;
        }
    }
    static final class Page {
        final String source;
        final int start, end;
        final List<Part> units;
        final List<String> candidates;
        Page(String source, int start, int end, List<Part> units, Set<String> candidates) {
            this.source = source; this.start = start; this.end = end;
            this.units = Collections.unmodifiableList(units);
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }
        boolean hasNext() { return end < source.length(); }
    }

    static Page page(String source, int offset) {
        String input = source == null ? "" : source;
        int start = Math.max(0, Math.min(input.length(), offset));
        if (start > 0 && start < input.length() && Character.isLowSurrogate(input.charAt(start))) start--;
        List<Part> units = new ArrayList<>();
        int cursor = start, wordCount = 0;
        while (cursor < input.length() && wordCount < PAGE_UNITS) {
            int from = cursor, cp = input.codePointAt(cursor);
            boolean word = Character.isLetterOrDigit(cp);
            cursor += Character.charCount(cp);
            if (word && !isCjk(cp)) {
                while (cursor < input.length()) {
                    int next = input.codePointAt(cursor);
                    if (isCjk(next) || (!Character.isLetterOrDigit(next) && !isMark(next)
                            && !((next == '\'' || next == '’' || next == '-') && cursor + 1 < input.length()
                            && Character.isLetter(input.codePointAt(cursor + 1))))) break;
                    cursor += Character.charCount(next);
                }
            } else if (!word) {
                // Whitespace is retained in ranges but rendered as flow spacing.
                while (cursor < input.length() && Character.isWhitespace(cp)
                        && Character.isWhitespace(input.codePointAt(cursor))) cursor += Character.charCount(input.codePointAt(cursor));
            }
            units.add(new Part(input, from, cursor, word, false));
            if (word) wordCount++;
            // Very long punctuation-only text must also remain bounded per page.
            if (units.size() >= PAGE_UNITS * 2) break;
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i < units.size(); i++) {
            Part first = units.get(i);
            if (!first.word) continue;
            int phraseWords = 0;
            for (int j = i; j < units.size() && phraseWords < PHRASE_UNITS; j++) {
                Part last = units.get(j);
                if (!last.word) {
                    if (!last.text.trim().isEmpty() || last.text.indexOf('\n') >= 0 || last.text.indexOf('\r') >= 0) break;
                    continue;
                }
                phraseWords++;
                String term = input.substring(first.start, last.end);
                if (term.length() > MAX_TERM_CHARS) break;
                candidates.add(term);
            }
        }
        return new Page(input, start, cursor, units, candidates);
    }

    static List<Part> match(Page page, Set<String> found) {
        List<Part> result = new ArrayList<>();
        for (int i = 0; i < page.units.size(); i++) {
            Part first = page.units.get(i);
            if (!first.word) { result.add(first); continue; }
            int longest = -1, words = 0;
            for (int j = i; j < page.units.size() && words < PHRASE_UNITS; j++) {
                Part last = page.units.get(j);
                if (!last.word) {
                    if (!last.text.trim().isEmpty() || last.text.indexOf('\n') >= 0 || last.text.indexOf('\r') >= 0) break;
                    continue;
                }
                words++;
                String term = page.source.substring(first.start, last.end);
                if (term.length() > MAX_TERM_CHARS) break;
                if (found.contains(term)) longest = j;
            }
            if (longest >= i) {
                result.add(new Part(page.source, first.start, page.units.get(longest).end, true, true));
                i = longest;
            } else result.add(first);
        }
        return Collections.unmodifiableList(result);
    }
    /** Replace one bounded batch while retaining earlier/later batches and their source ranges. */
    static void replaceRange(List<Part> displayed, Page page, List<Part> replacement) {
        int low = 0, high = displayed.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (displayed.get(middle).end <= page.start) low = middle + 1;
            else high = middle;
        }
        int start = low;
        while (low < displayed.size() && displayed.get(low).start < page.end) low++;
        displayed.subList(start, low).clear();
        displayed.addAll(start, replacement);
    }
    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS;
    }
    private SearchTextSegments() { }
}
