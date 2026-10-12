package com.yuen.jpdict;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** At most two open parsers across lookup and WebView resource reads. Borrowed
 * handles survive eviction; last release closes retired handles. */
final class DictionaryReaderPool {
    interface Read<T> { T run(MdictReader reader) throws IOException; }
    private static final int MAX = 2;
    private final DictionaryRepository repository;
    private final LinkedHashMap<String, Handle> handles = new LinkedHashMap<>(4, .75f, true);
    private final File indexes;
    private int live;
    private static final class Handle {
        final MdictReader reader;
        int users;
        boolean retired;
        Handle(MdictReader reader) { this.reader = reader; }
    }
    DictionaryReaderPool(DictionaryRepository repository) {
        this.repository = repository;
        indexes = new File(repository.context().getCacheDir(), "dict-indexes");
    }
    <T> T read(DictionaryRepository.Document document, Read<T> read) throws IOException {
        Handle handle = acquire(document);
        try { return read.run(handle.reader); }
        finally {
            synchronized (this) {
                handle.users--;
                if (handle.retired && handle.users == 0) { live--; handle.reader.close(); }
                notifyAll();
            }
        }
    }
    private synchronized Handle acquire(DictionaryRepository.Document document) throws IOException {
        MdictReader.checkCancelled();
        String key;
        MdictReader.Source source = repository.open(document);
        try {
            String fingerprint = DictionaryRepository.fingerprint(source);
            long modified = repository.modified(document);
            boolean reusable = modified > 0;
            fingerprint += ":" + modified;
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] value = hash.digest((document.uri.toString() + ":" + fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(); for (byte b : value) name.append(String.format(Locale.ROOT, "%02x", b & 255));
            key = name.toString();
            Handle cached = reusable ? handles.get(key) : null;
            if (cached != null) { source.close(); cached.users++; return cached; }
            while (live >= MAX) {
                String evict = null;
                for (Map.Entry<String, Handle> entry : handles.entrySet()) if (entry.getValue().users == 0) { evict = entry.getKey(); break; }
                if (evict != null) { handles.remove(evict).reader.close(); live--; break; }
                try { wait(); } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt(); MdictReader.checkCancelled();
                }
            }
            Handle opened = new Handle(MdictReader.open(source, reusable ? new File(indexes, key + ".idx") : null, fingerprint));
            source = null; opened.users = 1; opened.retired = !reusable;
            if (reusable) handles.put(key, opened);
            live++;
            trimIndexes();
            return opened;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("词典索引打开失败", error); }
        finally { if (source != null) source.close(); }
    }
    private void trimIndexes() {
        File[] files = indexes.listFiles(); if (files == null) return;
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        long bytes = 0;
        for (int i = 0; i < files.length; i++) {
            bytes += files[i].length();
            if (i >= 64 || bytes > 32L * 1024 * 1024) files[i].delete();
        }
    }
    synchronized void clear() {
        for (Handle handle : handles.values()) {
            handle.retired = true;
            if (handle.users == 0) { live--; try { handle.reader.close(); } catch (IOException ignored) {} }
        }
        handles.clear(); notifyAll();
    }
}
