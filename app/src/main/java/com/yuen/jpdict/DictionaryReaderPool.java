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
    private static final class Index {
        final String key, fingerprint;
        final File file;
        Index(String key, String fingerprint, File file) { this.key = key; this.fingerprint = fingerprint; this.file = file; }
    }
    DictionaryReaderPool(DictionaryRepository repository) {
        this.repository = repository;
        indexes = new File(repository.context().getCacheDir(), "dict-indexes");
    }
    private Index index(DictionaryRepository.Document document, MdictReader.Source source, long modified) throws Exception {
        String fingerprint = DictionaryRepository.fingerprint(source) + ":" + modified;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] value = hash.digest((document.uri.toString() + ":" + fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(); for (byte b : value) name.append(String.format(Locale.ROOT, "%02x", b & 255));
        String key = name.toString();
        return new Index(key, fingerprint, modified > 0 ? new File(indexes, key + ".idx") : null);
    }
    /** Validation and lookup use the final URI identity, including before a private copy is committed. */
    MdictReader openForValidation(DictionaryRepository.Document document, MdictReader.Source source, long modified) throws IOException {
        try {
            Index index = index(document, source, modified);
            return MdictReader.open(source, index.file, index.fingerprint);
        } catch (Exception error) {
            try { source.close(); } catch (IOException close) { error.addSuppressed(close); }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("词典索引打开失败", error);
        }
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
        MdictReader.Source source = repository.open(document);
        try {
            long modified = repository.modified(document);
            boolean reusable = modified > 0;
            Index index = index(document, source, modified);
            Handle cached = reusable ? handles.get(index.key) : null;
            if (cached != null) { source.close(); cached.users++; return cached; }
            while (live >= MAX) {
                String evict = null;
                for (Map.Entry<String, Handle> entry : handles.entrySet()) if (entry.getValue().users == 0) { evict = entry.getKey(); break; }
                if (evict != null) { handles.remove(evict).reader.close(); live--; break; }
                try { wait(); } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt(); MdictReader.checkCancelled();
                }
            }
            Handle opened = new Handle(MdictReader.open(source, index.file, index.fingerprint));
            source = null; opened.users = 1; opened.retired = !reusable;
            if (reusable) handles.put(index.key, opened);
            live++;
            trimIndexes();
            return opened;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("词典索引打开失败", error); }
        finally { if (source != null) source.close(); }
    }
    void trimIndexes() {
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
