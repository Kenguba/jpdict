package com.yuen.jpdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Process-local definitions survive window/parser eviction, never retaining Views. */
final class DictionaryResultCache {
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ENTRIES = 64;
    private final LinkedHashMap<String, List<String>> entries = new LinkedHashMap<>(8, .75f, true);
    private int bytes;

    synchronized List<String> get(String identity, String word) {
        return identity == null ? null : entries.get(key(identity, word));
    }
    synchronized void put(String identity, String word, List<String> result) {
        if (identity == null) return; // An external source with no revision cannot be cached safely.
        String key = key(identity, word);
        int size = size(key, result);
        if (size > MAX_BYTES) return;
        List<String> previous = entries.remove(key);
        if (previous != null) bytes -= size(key, previous);
        while (!entries.isEmpty() && (bytes + size > MAX_BYTES || entries.size() >= MAX_ENTRIES)) {
            Map.Entry<String, List<String>> oldest = entries.entrySet().iterator().next();
            bytes -= size(oldest.getKey(), oldest.getValue()); entries.remove(oldest.getKey());
        }
        entries.put(key, Collections.unmodifiableList(new ArrayList<>(result))); bytes += size;
    }
    synchronized void clear() { entries.clear(); bytes = 0; }
    private static String key(String identity, String word) { return identity.length() + ":" + identity + word; }
    private static int size(String key, List<String> result) {
        long count = 2L * key.length() + 64;
        for (String text : result) count += 2L * text.length() + 32;
        return (int) Math.min(Integer.MAX_VALUE, count);
    }
}
