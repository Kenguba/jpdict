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
    private final Set<String> opening = new HashSet<>();
    private final File indexes;
    private int live, generation;
    private static final class Handle {
        final MdictReader reader;
        final String identity;
        int users;
        boolean retired;
        Handle(MdictReader reader, String identity) { this.reader = reader; this.identity = identity; }
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
    /** App-private installation generations are immutable. External originals
     * still verify their fingerprint; unknown modification times disable reuse. */
    private String privateIdentity(DictionaryRepository.Document document, long modified) {
        if (modified <= 0 || !"file".equals(document.uri.getScheme())) return null;
        File file = new File(document.uri.getPath());
        try {
            if (!file.getCanonicalPath().startsWith(repository.directory().getCanonicalPath() + File.separator)) return null;
        } catch (IOException inaccessible) { return null; }
        return document.uri + ":" + modified + ":" + file.length();
    }
    String resultIdentity(DictionaryRepository.Document document) throws IOException {
        MdictReader.checkCancelled();
        long modified = repository.modified(document);
        String identity = privateIdentity(document, modified);
        if (identity != null) return identity;
        // Opening first also detects revoked permission or a missing original,
        // rather than returning a previously cached definition for that file.
        try (MdictReader.Source source = repository.open(document)) {
            return modified > 0 ? index(document, source, modified).key : null;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("词典身份读取失败", error); }
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
            return MdictReader.open(source, index.file, index.fingerprint, repository.context().getCacheDir());
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
                try {
                    if (handle.retired && handle.users == 0) { live--; handle.reader.close(); }
                } finally { notifyAll(); }
            }
        }
    }
    private Handle acquire(DictionaryRepository.Document document) throws IOException {
        MdictReader.checkCancelled();
        long modified = repository.modified(document);
        String identity = privateIdentity(document, modified);
        if (identity != null) synchronized (this) {
            for (Map.Entry<String, Handle> entry : handles.entrySet()) {
                if (identity.equals(entry.getValue().identity)) {
                    Handle cached = handles.get(entry.getKey()); cached.users++; return cached;
                }
            }
        }
        MdictReader.Source source = repository.open(document);
        boolean reserved = false;
        String openingKey = null;
        try {
            boolean reusable = modified > 0;
            Index index = index(document, source, modified);
            int owner;
            synchronized (this) {
                while (true) {
                    MdictReader.checkCancelled();
                    Handle cached = reusable ? handles.get(index.key) : null;
                    if (cached != null) { source.close(); source = null; cached.users++; return cached; }
                    if (!opening.contains(index.key)) {
                        if (live < MAX) break;
                        String evict = null;
                        for (Map.Entry<String, Handle> entry : handles.entrySet()) if (entry.getValue().users == 0) { evict = entry.getKey(); break; }
                        if (evict != null) {
                            Handle retired = handles.remove(evict); live--;
                            try { retired.reader.close(); } finally { notifyAll(); }
                            break;
                        }
                    }
                    try { wait(); } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt(); MdictReader.checkCancelled();
                    }
                }
                live++; reserved = true; opening.add(index.key); openingKey = index.key; owner = generation;
            }
            // Parsing, index I/O and fingerprints must not hold the pool lock.
            Handle opened = new Handle(MdictReader.open(source, index.file, index.fingerprint, repository.context().getCacheDir()), identity);
            source = null;
            synchronized (this) {
                opened.users = 1; opened.retired = !reusable || owner != generation;
                if (!opened.retired) handles.put(index.key, opened);
                reserved = false; opening.remove(index.key); openingKey = null; notifyAll();
            }
            trimIndexes();
            return opened;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("词典索引打开失败", error); }
        finally {
            if (reserved) synchronized (this) { live--; opening.remove(openingKey); notifyAll(); }
            if (source != null) source.close();
        }
    }
    synchronized void trimIndexes() {
        File[] files = indexes.listFiles((directory, name) -> name.endsWith(".idx")); if (files == null) return;
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        long bytes = 0;
        for (int i = 0; i < files.length; i++) {
            bytes += files[i].length();
            if (i >= 64 || bytes > 32L * 1024 * 1024) files[i].delete();
        }
    }
    synchronized void clear() {
        generation++;
        for (Handle handle : handles.values()) {
            handle.retired = true;
            if (handle.users == 0) { live--; try { handle.reader.close(); } catch (IOException ignored) {} }
        }
        handles.clear(); notifyAll();
    }
}
