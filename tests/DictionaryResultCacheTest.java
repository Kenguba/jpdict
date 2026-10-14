package com.yuen.jpdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DictionaryResultCacheTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        DictionaryResultCache cache = new DictionaryResultCache();
        List<String> mutable = new ArrayList<>(Arrays.asList("first", "duplicate", ""));
        cache.put("file-v1", "Please", mutable); mutable.clear();
        List<String> result = cache.get("file-v1", "Please");
        check(result.size() == 3 && result.get(2).isEmpty(), "Duplicate/empty entries survive an immutable snapshot");
        boolean immutable = false;
        try { result.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "A caller cannot corrupt cached definitions");
        check(cache.get("file-v1", "please") == null, "Original query case remains part of the key");
        check(cache.get("file-v2", "Please") == null, "New fingerprint/revision cannot reuse an old definition");
        check(cache.get("another-file-v1", "Please") == null, "Dictionaries never share cached definitions");
        cache.put(null, "unknown", Collections.singletonList("unsafe"));
        check(cache.get(null, "unknown") == null, "Unknown external modification time bypasses caching");
        cache.put("file-v1", "missing", Collections.emptyList());
        check(cache.get("file-v1", "missing") != null, "An empty result is cached separately from a miss");
        cache.clear();
        for (int i = 0; i < DictionaryResultCache.MAX_ENTRIES; i++) cache.put("revision", "q" + i, Collections.singletonList("meaning"));
        cache.get("revision", "q0");
        cache.put("revision", "new", Collections.singletonList("meaning"));
        check(cache.get("revision", "q0") != null && cache.get("revision", "q1") == null, "Entry cap evicts least recently used data");
        cache.clear();
        String large = new String(new char[DictionaryResultCache.MAX_BYTES / 4]);
        cache.put("revision", "a", Collections.singletonList(large));
        cache.put("revision", "b", Collections.singletonList(large));
        check(cache.get("revision", "a") == null && cache.get("revision", "b") != null, "Byte cap accounts for UTF-16 payload and keys");
        cache.put("revision", "oversize", Collections.singletonList(new String(new char[DictionaryResultCache.MAX_BYTES])));
        check(cache.get("revision", "oversize") == null && cache.get("revision", "b") != null, "Oversized entries do not evict useful cached data");
        cache.put("revision", "b", Collections.singletonList("replaced"));
        check(cache.get("revision", "b").get(0).equals("replaced"), "Replacement updates the existing cache item");
        cache.clear(); check(cache.get("revision", "b") == null, "Explicit reader/cache reset discards definitions");
        System.out.println("DictionaryResultCache: " + checks + " assertions passed");
    }
}
