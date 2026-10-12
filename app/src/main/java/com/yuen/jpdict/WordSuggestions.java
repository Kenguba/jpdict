package com.yuen.jpdict;

import java.text.Normalizer;
import java.util.*;

/** Small, bounded local ranking shared by readers and the multi-dictionary merge. */
final class WordSuggestions {
    static final int LIMIT = 12;
    private final String query;
    private final int limit;
    private final List<Match> words = new ArrayList<>();
    private static final class Match {
        final String word, key;
        final int rank;
        Match(String word, String key, int rank) { this.word = word; this.key = key; this.rank = rank; }
    }
    WordSuggestions(String query, int limit) { this.query = folded(query); this.limit = limit; }
    static String folded(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); out.append(c >= '\u30a1' && c <= '\u30f6' ? (char)(c - 0x60) : c);
        }
        return out.toString();
    }
    void add(String word) {
        if (word.isEmpty() || word.length() > 128) return;
        String key = folded(word);
        int rank = score(key);
        if (rank == Integer.MAX_VALUE) return;
        for (Match present : words) if (present.key.equals(key)) return;
        int at = 0;
        while (at < words.size() && words.get(at).rank <= rank) at++;
        if (at >= limit) return;
        words.add(at, new Match(word, key, rank));
        if (words.size() > limit) words.remove(words.size() - 1);
    }
    List<String> words() {
        List<String> result = new ArrayList<>();
        for (Match match : words) result.add(match.word);
        return Collections.unmodifiableList(result);
    }
    private int score(String value) {
        if (value.equals(query)) return 0;
        if (value.startsWith(query)) return 10 + Math.min(127, value.length() - query.length());
        if (query.length() >= 2 && value.contains(query)) return 160 + Math.min(127, value.length() - query.length());
        if (query.length() < 2 || query.length() > 48 || value.length() > 48) return Integer.MAX_VALUE;
        int allowed = query.length() <= 4 ? 1 : query.length() <= 8 ? 2 : 3;
        if (Math.abs(value.length() - query.length()) > allowed) return Integer.MAX_VALUE;
        int distance = distance(query, value);
        return distance <= allowed ? 320 + distance * 32 + Math.abs(value.length() - query.length()) : Integer.MAX_VALUE;
    }
    private static int distance(String a, String b) {
        int[] older = new int[b.length() + 1], previous = new int[b.length() + 1], row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                row[j] = Math.min(Math.min(row[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) row[j] = Math.min(row[j], older[j - 2] + 1);
            }
            int[] swap = older; older = previous; previous = row; row = swap;
        }
        return previous[b.length()];
    }
}
