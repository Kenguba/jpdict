package com.yuen.jpdict;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Single installation state source. UI never opens databases or parses files.
 * Catalog data is deliberately separate from this local repository. */
final class DictionaryRepository {
    enum SourceType { BUILTIN, USER_IMPORT, CATALOG_DOWNLOAD }
    enum StorageMode { APP_PRIVATE, SAF_EXTERNAL }
    enum InstallState { NOT_INSTALLED, QUEUED, DOWNLOADING, PAUSED, VERIFYING, INSTALLING, INSTALLED, FAILED }
    interface Progress { void update(String message, long done, long total); }
    interface Work { void run() throws Exception; }
    interface Complete { void done(Exception error); }
    static final class Document {
        final String path;
        final Uri uri;
        final long size;
        Document(String path, Uri uri, long size) { this.path = path; this.uri = uri; this.size = size; }
    }
    static final class Dictionary {
        final String id, name, packageId, installedVersion, fingerprint, indexState, status;
        final SourceType source;
        final StorageMode storage;
        final boolean enabled, lookupExpanded;
        final int order;
        final long installedAt, updatedAt, bytes;
        final List<Document> files;
        Dictionary(Cursor c) throws JSONException {
            id = c.getString(0); name = c.getString(1); packageId = c.getString(2);
            installedVersion = c.getString(3); source = SourceType.valueOf(c.getString(4));
            storage = StorageMode.valueOf(c.getString(5)); enabled = c.getInt(6) != 0;
            order = c.getInt(7); indexState = c.getString(8); status = c.getString(9);
            fingerprint = c.getString(10); installedAt = c.getLong(11); updatedAt = c.getLong(12);
            lookupExpanded = c.getInt(c.getColumnIndexOrThrow("lookup_expanded")) != 0;
            JSONArray array = new JSONArray(c.getString(13));
            List<Document> documents = new ArrayList<>(); long total = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject f = array.getJSONObject(i);
                long size = f.getLong("size");
                total += Math.max(0, size);
                documents.add(new Document(f.getString("path"), Uri.parse(f.getString("uri")), size));
            }
            files = Collections.unmodifiableList(documents); bytes = total;
        }
        Document mdx() {
            for (Document f : files) if (f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) return f;
            throw new IllegalStateException("词典记录缺少 MDX");
        }
    }

    private static volatile DictionaryRepository instance;
    static DictionaryRepository get(Context context) {
        DictionaryRepository value = instance;
        if (value == null) synchronized (DictionaryRepository.class) {
            if (instance == null) instance = new DictionaryRepository(context.getApplicationContext());
            value = instance;
        }
        return value;
    }
    private final Context context;
    private final Database database;
    private final File root;
    private final ThreadPoolExecutor management = new ThreadPoolExecutor(1, 1, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> new Thread(r, "jpdict-install"));
    final DictionaryScheduler queries = new DictionaryScheduler();
    private final DictionaryReaderPool readers;
    private volatile List<Dictionary> snapshot = Collections.emptyList();
    private volatile boolean loaded;

    private DictionaryRepository(Context context) {
        this.context = context; database = new Database(context);
        root = new File(context.getFilesDir(), "Dicts");
        readers = new DictionaryReaderPool(this);
        management.allowCoreThreadTimeOut(true);
    }
    java.util.concurrent.Future<?> execute(Work work, Complete completion) {
        try {
            return management.submit(() -> {
                Exception error = null;
                try { work.run(); reload(); }
                catch (Exception e) { error = e; }
                final Exception result = error;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> completion.done(result));
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> completion.done(new IOException("操作队列已满，请稍后重试")));
            return null;
        }
    }
    List<Dictionary> list() { return snapshot; }
    void ensureLoaded(Complete complete) {
        if (loaded) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> complete.done(null));
        else execute(() -> {}, complete);
    }
    void reload() throws IOException {
        List<Dictionary> list = new ArrayList<>();
        try (Cursor budget = database.getReadableDatabase().rawQuery("SELECT count(*), coalesce(sum(length(files)),0) FROM dictionaries", null)) {
            if (budget.moveToFirst() && (budget.getInt(0) > 512 || budget.getLong(1) > 4L * 1024 * 1024)) throw new IOException("词库注册清单超过内存限制");
        }
        try (Cursor c = database.getReadableDatabase().rawQuery("SELECT * FROM dictionaries ORDER BY sort_order, installed_at", null)) {
            while (c.moveToNext()) list.add(new Dictionary(c));
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("词典安装记录损坏", e); }
        snapshot = Collections.unmodifiableList(list);
        recoverStorage(list);
        loaded = true;
    }
    void enable(String id, boolean enabled) {
        ContentValues values = new ContentValues(); values.put("enabled", enabled ? 1 : 0);
        database.getWritableDatabase().update("dictionaries", values, "dict_id=?", new String[]{id});
    }
    void setLookupExpanded(String id, boolean expanded) {
        ContentValues values = new ContentValues(); values.put("lookup_expanded", expanded ? 1 : 0);
        database.getWritableDatabase().update("dictionaries", values, "dict_id=?", new String[]{id});
    }
    void reorder(List<String> ids) throws IOException {
        if (new HashSet<>(ids).size() != ids.size()) throw new IOException("重复词典 ID");
        SQLiteDatabase db = database.getWritableDatabase(); db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues(); v.put("sort_order", i);
                if (db.update("dictionaries", v, "dict_id=?", new String[]{ids.get(i)}) != 1) throw new IOException("排序期间词典已被删除");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void rename(String id, String name) throws IOException {
        if (name.trim().isEmpty() || name.length() > 200) throw new IOException("词典名称不能为空且最多 200 字");
        ContentValues values = new ContentValues(); values.put("name", name.trim());
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("dictionaries", values, "dict_id=?", new String[]{id});
    }
    void delete(Dictionary dictionary) throws IOException {
        // An external original is never deleted by this repository.
        File folder = new File(root, dictionary.id), tomb = new File(root, ".delete-" + dictionary.id);
        boolean moved = dictionary.storage == StorageMode.APP_PRIVATE && folder.exists();
        if (moved && !folder.renameTo(tomb)) throw new IOException("无法移除内部词典目录");
        try { database.getWritableDatabase().delete("dictionaries", "dict_id=?", new String[]{dictionary.id}); }
        catch (RuntimeException e) { if (moved) tomb.renameTo(folder); throw e; }
        if (moved) removeTree(tomb);
    }

    Document document(Uri uri, String prefix) throws IOException {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IOException("无法读取文件元数据");
            String name = c.getString(0);
            if (name == null || name.contains("/") || name.contains("\\")) throw new IOException("不安全文件名称");
            return new Document(DictionaryPaths.safe(prefix + name), uri, c.isNull(1) ? -1 : c.getLong(1));
        }
    }
    List<Document> scanTree(Uri tree) throws IOException {
        List<Document> documents = new ArrayList<>();
        scan(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, documents, new HashSet<>());
        return documents;
    }
    private void scan(Uri tree, String parent, String prefix, int depth,
            List<Document> files, Set<String> visited) throws IOException {
        MdictReader.checkCancelled();
        if (depth > 32 || visited.size() >= 10000 || !visited.add(parent)) throw new IOException("词典目录过深、循环或文件过多");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        try (Cursor c = context.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) throw new IOException("无法扫描词典目录");
            while (c.moveToNext()) {
                MdictReader.checkCancelled();
                String id = c.getString(0), name = c.getString(1);
                if (name == null || name.contains("/") || name.contains("\\")) throw new IOException("不安全文件名称");
                if (name.startsWith(".") || name.equals("__MACOSX")) continue;
                String path = DictionaryPaths.safe(prefix + name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) scan(tree, id, path + "/", depth + 1, files, visited);
                else {
                    if (files.size() >= 10000) throw new IOException("目录文件过多");
                    files.add(new Document(path, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.isNull(3) ? -1 : c.getLong(3)));
                }
            }
        }
    }

    List<Dictionary> install(List<Document> selected, boolean copy, String replaceId,
            Progress progress) throws Exception {
        if (selected.isEmpty()) throw new IOException("没有选择文件");
        List<Document> mdx = new ArrayList<>();
        for (Document f : selected) if (f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) mdx.add(f);
        if (mdx.isEmpty()) throw new IOException("选择的文件中没有 MDX");
        if (replaceId != null && mdx.size() != 1) throw new IOException("替换时请选择一本 MDX 及其依赖文件");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法建立内部词典目录");
        List<Dictionary> installed = new ArrayList<>();
        for (Document main : mdx) {
            MdictReader.checkCancelled();
            String directory = main.path.contains("/") ? main.path.substring(0, main.path.lastIndexOf('/') + 1) : "";
            String base = main.path.substring(directory.length(), main.path.length() - 4);
            List<Document> related = new ArrayList<>(); related.add(new Document(main.path.substring(directory.length()), main.uri, main.size));
            for (Document f : selected) {
                if (f == main || !f.path.startsWith(directory)) continue;
                String relative = f.path.substring(directory.length());
                String lower = relative.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mdx")) continue;
                if (lower.endsWith(".mdd") && !lower.matches(java.util.regex.Pattern.quote(base.toLowerCase(Locale.ROOT)) + "(?:\\.[0-9]+)?\\.mdd")) continue;
                // Do not absorb dependencies belonging to nested independent dictionaries.
                boolean nested = false;
                for (Document other : mdx) {
                    if (other == main || !other.path.contains("/")) continue;
                    String parent = other.path.substring(0, other.path.lastIndexOf('/') + 1);
                    if (parent.length() > directory.length() && f.path.startsWith(parent)) { nested = true; break; }
                }
                if (!nested) related.add(new Document(relative, f.uri, f.size));
            }
            Set<String> paths = new HashSet<>();
            long manifestCharacters = 0;
            for (Document f : related) {
                if (!paths.add(f.path.toLowerCase(Locale.ROOT))) throw new IOException("词典含大小写冲突的资源路径");
                manifestCharacters += f.path.length() + f.uri.toString().length() + 128;
            }
            if (manifestCharacters > 4 * 1024 * 1024) throw new IOException("词典外部资源清单超过 4 MB，请优先使用 MDD 打包资源");
            if (replaceId == null && snapshot.size() >= 512) throw new IOException("最多可注册 512 本词典");
            String id = replaceId == null ? UUID.randomUUID().toString() : replaceId;
            Dictionary old = null;
            for (Dictionary d : snapshot) if (d.id.equals(id)) old = d;
            File temporary = new File(root, ".import-" + UUID.randomUUID());
            File dictionaryRoot = new File(root, id);
            File destination = new File(dictionaryRoot, UUID.randomUUID().toString());
            boolean committed = false, movedNew = false;
            try {
                List<Document> files = new ArrayList<>();
                if (copy) {
                    if (!temporary.mkdir()) throw new IOException("无法建立导入临时目录");
                    long total = 0, done = 0;
                    for (Document f : related) { if (f.size > 0) total += f.size; }
                    if (total > root.getUsableSpace() - 16L * 1024 * 1024) throw new IOException("内部存储空间不足");
                    for (Document f : related) {
                        File target = inside(temporary, f.path);
                        File parent = target.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法建立资源目录");
                        long count = 0;
                        try (InputStream in = context.getContentResolver().openInputStream(f.uri);
                                FileOutputStream out = new FileOutputStream(target)) {
                            if (in == null) throw new IOException("文件不可访问：" + f.path);
                            byte[] buffer = new byte[65536]; int read;
                            while ((read = in.read(buffer)) != -1) {
                                MdictReader.checkCancelled();
                                if (root.getUsableSpace() < read + 16L * 1024 * 1024) throw new IOException("内部存储空间不足");
                                out.write(buffer, 0, read); count += read; done += read;
                                progress.update("正在复制 " + f.path, done, total);
                            }
                            out.getFD().sync();
                        }
                        if (f.size >= 0 && count != f.size) throw new IOException("复制期间文件长度变化：" + f.path);
                        files.add(new Document(f.path, Uri.fromFile(inside(destination, f.path)), count));
                    }
                } else files.addAll(related);
                Document first = files.get(0);
                String fingerprint;
                try (MdictReader.Source src = copy ? MdictReader.file(inside(temporary, first.path)) : open(first)) {
                    fingerprint = fingerprint(src);
                }
                for (Dictionary existing : snapshot) if (!existing.id.equals(id) && existing.fingerprint.equals(fingerprint)) throw new IOException("该词典已安装：" + existing.name);
                String title = base;
                for (Document f : files) {
                    if (!f.path.toLowerCase(Locale.ROOT).matches(".*\\.(mdx|mdd)$")) continue;
                    progress.update("验证 " + f.path, 0, 0);
                    File staged = copy ? inside(temporary, f.path) : null;
                    try (MdictReader r = readers.openForValidation(f, copy ? MdictReader.file(staged) : open(f),
                            copy ? staged.lastModified() : modified(f))) {
                        if (f == first && !r.getMetadata().title.isEmpty()) title = r.getMetadata().title;
                        if (r.getMetadata().resource != f.path.toLowerCase(Locale.ROOT).endsWith(".mdd")) throw new IOException("MDX/MDD 类型不符");
                        r.validate((done, total) -> progress.update("验证 " + f.path, done, total));
                    } catch (IOException error) {
                        throw new IOException("验证 " + f.path + " 失败：" + error.getMessage(), error);
                    }
                }
                readers.trimIndexes();
                ContentValues v = new ContentValues();
                long now = System.currentTimeMillis();
                v.put("dict_id", id); v.put("name", old == null ? title : old.name);
                v.put("package_id", ""); v.put("installed_version", "");
                v.put("source_type", SourceType.USER_IMPORT.name());
                v.put("storage_mode", copy ? StorageMode.APP_PRIVATE.name() : StorageMode.SAF_EXTERNAL.name());
                v.put("enabled", old == null || old.enabled ? 1 : 0); v.put("sort_order", old == null ? snapshot.size() : old.order);
                v.put("lookup_expanded", old != null && old.lookupExpanded ? 1 : 0);
                v.put("index_state", "已建立索引"); v.put("install_state", "INSTALLED");
                v.put("fingerprint", fingerprint); v.put("installed_at", old == null ? now : old.installedAt);
                v.put("updated_at", now); v.put("files", filesJson(files).toString());
                try (Cursor budget = database.getReadableDatabase().rawQuery("SELECT coalesce(sum(length(files)),0) FROM dictionaries WHERE dict_id<>?", new String[]{id})) {
                    if (budget.moveToFirst() && budget.getLong(0) + v.getAsString("files").length() > 4L * 1024 * 1024) throw new IOException("词库注册清单超过 4 MB 内存限制");
                }
                if (copy) {
                    if (!dictionaryRoot.isDirectory() && !dictionaryRoot.mkdirs()) throw new IOException("无法建立词典目录");
                    if (!temporary.renameTo(destination)) throw new IOException("无法提交内部词典");
                    movedNew = true;
                }
                if (database.getWritableDatabase().insertWithOnConflict("dictionaries", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IOException("无法提交词典安装记录");
                committed = true;
                reload();
                for (Dictionary d : snapshot) if (d.id.equals(id)) installed.add(d);
            } finally {
                if (!committed) {
                    if (movedNew) removeTree(destination);
                }
                removeTree(temporary);
            }
        }
        return installed;
    }

    MdictReader.Source open(Document file) throws IOException {
        if (file.uri == null || file.uri.getScheme() == null) throw new IOException("词典文件需要重新关联");
        try {
            return "file".equals(file.uri.getScheme()) ? MdictReader.file(new File(file.uri.getPath()))
                    : new DictionarySource(context.getContentResolver(), file.uri);
        } catch (SecurityException e) { throw new IOException("外部文件授权已失效，请重新关联", e); }
    }
    List<String> lookup(Dictionary d, String word) throws IOException {
        return readers.read(d.mdx(), reader -> {
            List<String> entries = reader.lookup(word);
            if (!"Text".equalsIgnoreCase(reader.getMetadata().format)) return entries;
            List<String> escaped = new ArrayList<>();
            for (String entry : entries) escaped.add("<pre>" + entry.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>");
            return escaped;
        });
    }
    List<String> suggest(String prefix) throws IOException {
        WordSuggestions matches = new WordSuggestions(prefix, WordSuggestions.LIMIT);
        long deadline = System.nanoTime() + 300000000L;
        int visited = 0;
        for (Dictionary dictionary : snapshot) {
            MdictReader.checkCancelled();
            if (!dictionary.enabled) continue;
            if (++visited > 8 || System.nanoTime() > deadline) break;
            try {
                for (String word : readers.read(dictionary.mdx(), reader -> reader.suggest(prefix, WordSuggestions.LIMIT))) matches.add(word);
            } catch (MdictReader.Failure failure) {
                if (failure.error == MdictReader.Error.CANCELLED) throw failure;
            } catch (IOException unavailable) { /* A missing dictionary must not block typing. */ }
        }
        return matches.words();
    }
    static final class WordPage {
        final List<String> words;
        final long total;
        WordPage(List<String> words, long total) { this.words = words; this.total = total; }
    }
    WordPage words(Dictionary dictionary, long offset) throws IOException {
        return readers.read(dictionary.mdx(), reader -> new WordPage(reader.words(offset, 50), reader.getMetadata().entries));
    }
    long modified(Document file) {
        if ("file".equals(file.uri.getScheme())) return new File(file.uri.getPath()).lastModified();
        try (Cursor c = context.getContentResolver().query(file.uri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            return c != null && c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        } catch (RuntimeException unavailable) { return 0; }
    }
    byte[] resource(Dictionary d, String requested) throws IOException {
        try (InputStream in = resourceStream(d, requested)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                MdictReader.checkCancelled();
                if (out.size() > MdictReader.MAX_BLOCK_BYTES - n) throw new IOException("资源超过 8 MB 内存限制");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    /** WebView consumes external assets as streams, including large CJK fonts. */
    InputStream resourceStream(Dictionary d, String requested) throws IOException {
        String path = DictionaryPaths.safe(requested);
        List<Document> volumes = new ArrayList<>();
        Document external = null; int priority = 3;
        for (Document f : d.files) {
            if (f.path.toLowerCase(Locale.ROOT).endsWith(".mdd")) volumes.add(f);
            else if (!f.path.toLowerCase(Locale.ROOT).endsWith(".mdx")) {
                // Unpacked dictionaries commonly keep root resources in src/ or mdd/.
                int candidate = f.path.equalsIgnoreCase(path) ? 0 : f.path.equalsIgnoreCase("src/" + path) ? 1
                        : f.path.equalsIgnoreCase("mdd/" + path) ? 2 : 3;
                if (candidate < priority) { priority = candidate; external = f; }
            }
        }
        if (external != null) {
            Document f = external;
            if (f.size > 32L * 1024 * 1024) throw new IOException("外部资源超过 32 MB 流式限制");
            InputStream in = "file".equals(f.uri.getScheme()) ? new FileInputStream(new File(f.uri.getPath()))
                    : context.getContentResolver().openInputStream(f.uri);
            if (in == null) throw new IOException("资源不可访问");
            return new FilterInputStream(in) {
                private long remaining = 32L * 1024 * 1024;
                @Override public int read() throws IOException {
                    MdictReader.checkCancelled(); int value = super.read();
                    if (value != -1 && --remaining < 0) throw new IOException("外部资源超过 32 MB 流式限制");
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    MdictReader.checkCancelled(); int n = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                    if (n > 0 && (remaining -= n) < 0) throw new IOException("外部资源超过 32 MB 流式限制");
                    return n;
                }
                @Override public long skip(long count) throws IOException {
                    MdictReader.checkCancelled(); long skipped = in.skip(Math.min(count, remaining));
                    remaining -= skipped; return skipped;
                }
            };
        }
        Collections.sort(volumes, (left, right) -> Integer.compare(part(left.path), part(right.path)));
        for (Document f : volumes) {
            byte[] bytes = readers.read(f, reader -> reader.readResource(path));
            if (bytes != null) return new ByteArrayInputStream(bytes);
        }
        return null;
    }
    private static int part(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.([0-9]+)\\.mdd$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(path);
        try { return m.find() ? Integer.parseInt(m.group(1)) + 1 : 0; } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }
    static String fingerprint(MdictReader.Source source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = source.size(); digest.update(java.nio.ByteBuffer.allocate(8).putLong(length).array());
        int chunk = (int) Math.min(length, 65536);
        byte[] bytes = new byte[chunk]; source.read(0, bytes, 0, chunk); digest.update(bytes);
        if (length > chunk) { source.read(length - chunk, bytes, 0, chunk); digest.update(bytes); }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    static JSONArray filesJson(List<Document> files) throws JSONException {
        JSONArray array = new JSONArray();
        for (Document f : files) array.put(new JSONObject().put("path", f.path).put("uri", f.uri.toString()).put("size", f.size));
        return array;
    }
    static File inside(File directory, String path) throws IOException {
        File child = new File(directory, DictionaryPaths.safe(path));
        if (!child.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("资源路径越界");
        return child;
    }
    static void removeTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (file.isDirectory() && children == null) throw new IOException("目录不可访问");
        if (children != null) for (File child : children) removeTree(child);
        if (!file.delete()) throw new IOException("无法清理临时文件：" + file.getName());
    }
    File directory() { return root; }
    Context context() { return context; }
    Database database() { return database; }
    void closeReaders() { readers.clear(); }

    private void recoverStorage(List<Dictionary> installed) throws IOException {
        if (!root.isDirectory()) return;
        Set<String> active = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Dictionary d : installed) {
            ids.add(d.id);
            if (d.storage == StorageMode.APP_PRIVATE && "file".equals(d.mdx().uri.getScheme())) {
                File main = new File(d.mdx().uri.getPath());
                active.add(main.getParentFile().getCanonicalPath());
            }
        }
        File[] folders = root.listFiles(); if (folders == null) throw new IOException("词典目录不可访问");
        for (File folder : folders) {
            if (folder.getName().startsWith(".delete-")) {
                String id = folder.getName().substring(8);
                if (ids.contains(id)) {
                    if (!folder.renameTo(new File(root, id))) throw new IOException("中断删除恢复失败");
                } else removeTree(folder);
            } else if (folder.getName().startsWith(".import-") || folder.getName().startsWith(".restore-")) removeTree(folder);
            else if (folder.isDirectory()) {
                File[] generations = folder.listFiles();
                if (generations == null) continue;
                for (File generation : generations) if (generation.isDirectory()
                        && !active.contains(generation.getCanonicalPath())) removeTree(generation);
                if (!ids.contains(folder.getName())) removeTree(folder);
            }
        }
    }

    static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "dictionaries.db", null, 2); }
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE dictionaries (dict_id TEXT PRIMARY KEY, name TEXT NOT NULL, package_id TEXT NOT NULL, installed_version TEXT NOT NULL, source_type TEXT NOT NULL, storage_mode TEXT NOT NULL, enabled INTEGER NOT NULL, sort_order INTEGER NOT NULL, index_state TEXT NOT NULL, install_state TEXT NOT NULL, fingerprint TEXT NOT NULL, installed_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, files TEXT NOT NULL, lookup_expanded INTEGER NOT NULL DEFAULT 0)");
        }
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE dictionaries ADD COLUMN lookup_expanded INTEGER NOT NULL DEFAULT 0");
        }
    }
}
