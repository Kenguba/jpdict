package com.yuen.jpdict;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Shared, seek-only MDX/MDD v1/v2 core. Source ownership transfers to open().
 * Index metadata is read once; keys and records are decoded on demand.
 * This class is independent of Android and of any third party MDict engine. */
public final class MdictReader implements Closeable {
    static final int MAX_BLOCK_BYTES = 8 * 1024 * 1024;
    static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    private static final java.util.concurrent.Semaphore LARGE_READ = new java.util.concurrent.Semaphore(1, true);
    private static final int MAX_BLOCKS = 100000;
    private static final int MAX_MATCHES = 256;
    private static final int CACHE_BYTES = 4 * 1024 * 1024;
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);
    private static final Pattern SUMMARY_PUNCTUATION = Pattern.compile("[\\x20-\\x2f\\x3a-\\x40\\x5b-\\x60\\x7b-\\x7e]");
    // A failed download resume can append nginx's complete 416 page to a valid MDD.
    private static final Pattern RANGE_ERROR_TRAILER = Pattern.compile("<html>\\r?\\n"
            + "<head><title>416 Requested Range Not Satisfiable</title></head>\\r?\\n"
            + "<body>\\r?\\n<center><h1>416 Requested Range Not Satisfiable</h1></center>\\r?\\n"
            + "<hr><center>nginx/[0-9.]+</center>\\r?\\n</body>\\r?\\n</html>\\r?\\n");

    public enum Error { UNSUPPORTED, CORRUPT, INACCESSIBLE, CANCELLED, LIMIT }

    public static final class Failure extends IOException {
        public final Error error;
        public Failure(Error error, String message) { super(message); this.error = error; }
    }

    public interface Source extends Closeable {
        long size() throws IOException;
        void read(long position, byte[] target, int offset, int length) throws IOException;
    }

    /** FileChannel positional reads never load or copy the entire dictionary. */
    public static Source file(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        FileChannel channel = stream.getChannel();
        return new Source() {
            public long size() throws IOException { return channel.size(); }
            public void read(long position, byte[] target, int offset, int length) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
                while (buffer.hasRemaining()) {
                    checkCancelled();
                    int read = channel.read(buffer, position);
                    if (read <= 0) throw new Failure(Error.INACCESSIBLE, "文件不可随机读取或已被截断");
                    position += read;
                }
            }
            public void close() throws IOException { stream.close(); }
        };
    }

    public static final class Metadata {
        public final String title, description, version, encoding, format;
        public final boolean resource;
        public final long entries, fileBytes;
        Metadata(Map<String, String> attributes, boolean resource, long entries, long size) {
            title = attribute(attributes, "Title", "");
            description = attribute(attributes, "Description", "");
            version = attributes.get("GeneratedByEngineVersion");
            encoding = resource ? "UTF-16LE" : attribute(attributes, "Encoding", "UTF-8");
            format = attribute(attributes, "Format", "Html");
            this.resource = resource;
            this.entries = entries;
            fileBytes = size;
        }
    }

    private static final class Block {
        long position, start, count;
        int packed, unpacked;
        String first, last, min, max;
        byte[] filter;
    }
    private static final class Key {
        final String text;
        final long offset;
        Key(String text, long offset) { this.text = text; this.offset = offset; }
    }

    private final Source source;
    private final long size;
    private final Map<String, String> attributes = new HashMap<>();
    private final List<Block> keys = new ArrayList<>(), records = new ArrayList<>();
    private final LinkedHashMap<Integer, byte[]> recordCache = new LinkedHashMap<>(4, .75f, true);
    private List<Key> lastKeys;
    private int lastKeyBlock = -1, cachedBytes;
    private int width, unit;
    private Charset charset;
    private long entryCount, totalRecords;
    private boolean resource, caseSensitive, closed;
    private Metadata metadata;
    private final File indexFile;
    private final String indexFingerprint;
    private boolean boundsReady;

    private MdictReader(Source source, File index, String fingerprint) throws IOException {
        this.source = source;
        indexFile = index; indexFingerprint = fingerprint;
        size = source.size();
        if (!restoreIndex(index, fingerprint)) {
            parse();
            saveIndex(index, fingerprint);
        }
    }

    public static MdictReader open(Source source) throws IOException {
        return open(source, null, "");
    }
    static MdictReader open(Source source, File index, String fingerprint) throws IOException {
        try { return new MdictReader(source, index, fingerprint); }
        catch (IOException | RuntimeException failure) {
            try { source.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    public static MdictReader open(File file) throws IOException { return open(file(file)); }
    public Metadata getMetadata() { return metadata; }

    interface ValidationProgress { void update(long complete, long total); }
    /** Import verification visits each block once, without retaining definitions. */
    synchronized void validate(ValidationProgress progress) throws IOException {
        ensureOpen(); boolean heavy = enterLargeRead();
        try {
        long previous = -1, completed = 0, total = keys.size() + records.size();
        for (int i = 0; i < keys.size(); i++) {
            for (Key key : keyBlock(i)) {
                if (key.offset < previous) throw corrupt("跨 Key Block 的记录偏移逆序");
                previous = key.offset;
            }
            progress.update(++completed, total);
        }
        for (Block block : records) {
            checkCancelled(); unpack(read(block.position, block.packed), block.unpacked);
            progress.update(++completed, total);
        }
        boundsReady = true; saveIndex(indexFile, indexFingerprint);
        } finally { if (heavy) LARGE_READ.release(); }
    }

    private boolean restoreIndex(File file, String fingerprint) {
        if (file == null || !file.isFile() || file.length() > MAX_INDEX_BYTES || file.length() < 8) return false;
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] all = new byte[(int) file.length()];
            new DataInputStream(stream).readFully(all);
            byte[] body = java.util.Arrays.copyOf(all, all.length - 8);
            long expected = new DataInputStream(new ByteArrayInputStream(all, all.length - 8, 8)).readLong();
            verify(body, expected);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != 0x4a504433 || !in.readUTF().equals(fingerprint) || in.readLong() != size) return false;
            width = in.readInt(); unit = in.readInt(); resource = in.readBoolean(); caseSensitive = in.readBoolean();
            if ((width != 4 && width != 8) || (unit != 1 && unit != 2)) throw corrupt("缓存字段无效");
            charset = Charset.forName(in.readUTF()); entryCount = in.readLong(); totalRecords = in.readLong();
            if (entryCount < 0 || totalRecords < 0) throw corrupt("缓存汇总无效");
            int count = bounded(in.readInt(), 64, "缓存属性");
            for (int i = 0; i < count; i++) attributes.put(in.readUTF(), in.readUTF());
            count = bounded(in.readInt(), MAX_BLOCKS, "缓存 Key 数量"); long entries = 0;
            for (int i = 0; i < count; i++) {
                Block block = readIndexBlock(in); block.count = in.readLong(); block.first = in.readUTF(); block.last = in.readUTF();
                block.min = in.readUTF(); block.max = in.readUTF();
                block.filter = new byte[filterBytes(count)]; in.readFully(block.filter);
                if (block.min.compareTo(block.max) > 0) throw corrupt("缓存词头范围无效");
                if (block.count <= 0 || block.count > block.unpacked / (width + unit)) throw corrupt("缓存词头数量无效");
                entries = add(entries, block.count); keys.add(block);
            }
            if (entries != entryCount) throw corrupt("缓存 Key 汇总无效");
            count = bounded(in.readInt(), MAX_BLOCKS, "缓存 Record 数量"); long decoded = 0;
            for (int i = 0; i < count; i++) {
                Block block = readIndexBlock(in); block.start = in.readLong();
                if (block.start != decoded) throw corrupt("缓存 Record 汇总无效");
                decoded = add(decoded, block.unpacked); records.add(block);
            }
            if (decoded != totalRecords || in.available() != 0 || (entryCount > 0 && records.isEmpty())) throw corrupt("缓存数据不完整");
            metadata = new Metadata(attributes, resource, entryCount, size);
            boundsReady = true;
            return true;
        } catch (IOException | RuntimeException invalid) {
            attributes.clear(); keys.clear(); records.clear(); totalRecords = 0;
            return false;
        }
    }
    private Block readIndexBlock(DataInputStream in) throws IOException {
        Block block = new Block(); block.position = in.readLong();
        block.packed = bounded(in.readInt(), blockLimit() + 65536, "缓存压缩块");
        block.unpacked = bounded(in.readInt(), blockLimit(), "缓存解压块");
        checkRange(block.position, block.packed); return block;
    }
    private void saveIndex(File file, String fingerprint) {
        if (file == null || !boundsReady) return;
        File temporary = new File(file.getPath() + ".tmp");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(0x4a504433); out.writeUTF(fingerprint); out.writeLong(size);
            out.writeInt(width); out.writeInt(unit); out.writeBoolean(resource); out.writeBoolean(caseSensitive);
            out.writeUTF(charset.name()); out.writeLong(entryCount); out.writeLong(totalRecords);
            out.writeInt(attributes.size());
            for (Map.Entry<String, String> entry : attributes.entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); }
            out.writeInt(keys.size());
            for (Block b : keys) { writeIndexBlock(out, b); out.writeLong(b.count); out.writeUTF(b.first); out.writeUTF(b.last); out.writeUTF(b.min); out.writeUTF(b.max); out.write(b.filter); }
            out.writeInt(records.size());
            for (Block b : records) { writeIndexBlock(out, b); out.writeLong(b.start); }
            out.flush(); if (buffer.size() > MAX_INDEX_BYTES - 8) return;
            byte[] body = buffer.toByteArray(); Adler32 checksum = new Adler32(); checksum.update(body);
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                DataOutputStream disk = new DataOutputStream(stream); disk.write(body); disk.writeLong(checksum.getValue()); disk.flush(); stream.getFD().sync();
            }
            if (!temporary.renameTo(file)) temporary.delete();
        } catch (IOException ignored) {
            // Rebuildable cache errors must not prevent reading the original.
        } finally { temporary.delete(); }
    }
    private static void writeIndexBlock(DataOutputStream out, Block b) throws IOException {
        out.writeLong(b.position); out.writeInt(b.packed); out.writeInt(b.unpacked);
    }

    private void parse() throws IOException {
        int headerLength = bounded(unsigned(read(0, 4), 4), 1024 * 1024, "文件头");
        if (headerLength == 0 || (headerLength & 1) != 0) throw corrupt("文件头长度");
        byte[] header = read(4, headerLength);
        long checksum = ByteBuffer.wrap(read(4L + headerLength, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
        verify(header, checksum);
        String xml = decode(header, StandardCharsets.UTF_16LE).replace("\u0000", "").trim();
        resource = xml.startsWith("<Library_Data");
        if (!resource && !xml.startsWith("<Dictionary")) throw corrupt("无法识别 MDX/MDD 文件头");
        Matcher matcher = ATTRIBUTE.matcher(xml);
        while (matcher.find()) {
            if (attributes.put(matcher.group(1), unescape(matcher.group(3))) != null) throw corrupt("重复文件头属性");
        }
        String version = attributes.get("GeneratedByEngineVersion");
        if (version == null || !version.matches("[12]\\.[0-9]+")) throw unsupported("仅支持 MDX/MDD v1/v2；当前版本 " + version);
        width = version.startsWith("1.") ? 4 : 8;
        String required = attribute(attributes, "RequiredEngineVersion", version);
        if (!required.matches("[12]\\.[0-9]+")) throw unsupported("不支持所需引擎版本 " + required);
        String encrypted = attribute(attributes, "Encrypted", "0");
        boolean encodedIndex = "2".equals(encrypted);
        if (!"0".equals(encrypted) && !"No".equalsIgnoreCase(encrypted) && !encodedIndex) throw unsupported("密码保护或未知加密方式尚不支持（Encrypted=" + encrypted + "）");
        if (encodedIndex && width != 8) throw unsupported("仅支持 v2 的 Encrypted=2 索引混淆");
        // Some builders set the compact flag even for ordinary HTML with no style substitutions.
        if (("Yes".equalsIgnoreCase(attributes.get("Compact")) || "Yes".equalsIgnoreCase(attributes.get("Compat")))
                && !attribute(attributes, "StyleSheet", "").trim().isEmpty()) {
            throw unsupported("词库含非空 StyleSheet，Compact 样式替换尚不支持");
        }
        caseSensitive = !resource && "Yes".equalsIgnoreCase(attributes.get("KeyCaseSensitive"));
        String encoding = resource ? "UTF-16LE" : attribute(attributes, "Encoding", "UTF-8");
        if (encoding.equalsIgnoreCase("UTF16") || encoding.equalsIgnoreCase("UTF-16")) encoding = "UTF-16LE";
        if (encoding.equalsIgnoreCase("UTF8")) encoding = "UTF-8";
        if (!encoding.matches("(?i)UTF-8|UTF-16LE|UTF-16BE|GBK|GB18030|BIG5")) throw unsupported("未验证的文字编码 " + encoding);
        try { charset = Charset.forName(encoding); }
        catch (IllegalArgumentException failure) { throw unsupported("平台不支持编码 " + encoding); }
        unit = charset.name().startsWith("UTF-16") ? 2 : 1;
        long position = 8L + headerLength;
        byte[] keyHeader = read(position, width * (width == 8 ? 5 : 4));
        Cursor kh = new Cursor(keyHeader);
        int keyCount = bounded(kh.number(), MAX_BLOCKS, "Key Block 数量");
        entryCount = kh.number();
        long indexUnpacked = width == 8 ? kh.number() : -1;
        int indexPacked = bounded(kh.number(), MAX_INDEX_BYTES, "Key 索引");
        long keyBytes = kh.number();
        position += keyHeader.length;
        if (width == 8) {
            verify(keyHeader, unsigned(read(position, 4), 4));
            position += 4;
        }
        byte[] index = read(position, indexPacked);
        if (width == 8) {
            if (encodedIndex) MdictIndexDecoder.decode(index);
            index = unpack(index, bounded(indexUnpacked, MAX_INDEX_BYTES, "Key 索引解压"));
        }
        position += indexPacked;
        checkRange(position, keyBytes);
        Cursor ki = new Cursor(index);
        long keyPosition = position, counted = 0;
        for (int i = 0; i < keyCount; i++) {
            checkCancelled();
            Block block = new Block();
            block.count = ki.number();
            if (block.count == 0 || block.count > 100000) throw limit("单块词头数超出限制");
            block.first = ki.summary();
            block.last = ki.summary();
            block.packed = bounded(ki.number(), blockLimit() + 65536, "Key 压缩块");
            block.unpacked = bounded(ki.number(), blockLimit(), "Key 解压块");
            if (block.count > block.unpacked / (width + unit)) throw corrupt("Key 条目数量不符");
            block.position = keyPosition;
            keyPosition = add(keyPosition, block.packed);
            counted = add(counted, block.count);
            keys.add(block);
        }
        ki.end();
        if (counted != entryCount || keyPosition != add(position, keyBytes)) throw corrupt("Key 索引汇总不符");
        position = keyPosition;
        Cursor rh = new Cursor(read(position, width * 4));
        int recordCount = bounded(rh.number(), MAX_BLOCKS, "Record Block 数量");
        if (rh.number() != entryCount) throw corrupt("Key/Record 条目数量不符");
        long recordIndexBytes = rh.number(), recordBytes = rh.number();
        if (recordIndexBytes != (long) recordCount * width * 2) throw corrupt("Record 索引长度不符");
        position += width * 4L;
        Cursor ri = new Cursor(read(position, bounded(recordIndexBytes, MAX_INDEX_BYTES, "Record 索引")));
        position += recordIndexBytes;
        checkRange(position, recordBytes);
        long recordPosition = position;
        for (int i = 0; i < recordCount; i++) {
            Block block = new Block();
            block.packed = bounded(ri.number(), blockLimit() + 65536, "Record 压缩块");
            block.unpacked = bounded(ri.number(), blockLimit(), "Record 解压块");
            block.position = recordPosition;
            block.start = totalRecords;
            recordPosition = add(recordPosition, block.packed);
            totalRecords = add(totalRecords, block.unpacked);
            records.add(block);
        }
        ri.end();
        if (recordPosition != add(position, recordBytes)) throw corrupt("Record 压缩块合计长度与声明不符（差值 "
                + (recordPosition - add(position, recordBytes)) + " bytes）");
        if (recordPosition != size) {
            long trailing = size - recordPosition;
            boolean knownDownloadError = resource && width == 8 && trailing > 0 && trailing <= 512
                    && RANGE_ERROR_TRAILER.matcher(decode(read(recordPosition, (int) trailing), StandardCharsets.US_ASCII)).matches();
            if (!knownDownloadError) throw corrupt("Record 数据结束后还有 " + trailing
                    + " bytes；文件可能附带未支持的扩展或下载错误，请重新下载原文件");
        }
        if (entryCount > 0 && (keys.isEmpty() || records.isEmpty())) throw corrupt("词典缺少数据块");
        metadata = new Metadata(attributes, resource, entryCount, size);
    }

    /** Exact keys, respecting the file's case-sensitivity. All duplicate rows survive. */
    public synchronized List<String> lookup(String word) throws IOException {
        ensureOpen();
        if (resource) throw unsupported("MDD 请使用资源读取接口");
        if (word == null || word.length() > 65536) throw limit("查询词头为空或过长");
        boolean heavy = enterLargeRead();
        try { return Collections.unmodifiableList(lookupText(word, new HashSet<>(), 0, new long[1])); }
        finally { if (heavy) LARGE_READ.release(); }
    }

    /** Bounded headword page; skip block counts without loading preceding keys. */
    public synchronized List<String> words(long offset, int limit) throws IOException {
        ensureOpen();
        if (resource || offset < 0 || limit < 1) return Collections.emptyList();
        limit = Math.min(limit, 50);
        List<String> words = new ArrayList<>();
        boolean heavy = enterLargeRead();
        try {
            int decoded = 0;
            for (int b = 0; b < keys.size(); b++) {
                checkCancelled(); Block block = keys.get(b);
                if (offset >= block.count) { offset -= block.count; continue; }
                if (decoded + block.unpacked > MAX_BLOCK_BYTES) break;
                decoded += block.unpacked;
                List<Key> page = keyBlock(b);
                for (int i = (int)offset; i < page.size() && words.size() < limit; i++) words.add(page.get(i).text);
                offset = 0;
                if (words.size() == limit) break;
            }
            return Collections.unmodifiableList(words);
        } finally { if (heavy) LARGE_READ.release(); }
    }

    /** Best-effort bounded completion; never reads Record blocks or rebuilds a full index. */
    public synchronized List<String> suggest(String prefix, int limit) throws IOException {
        ensureOpen();
        if (resource || prefix == null || prefix.isEmpty() || prefix.length() > 128 || limit < 1) return Collections.emptyList();
        limit = Math.min(limit, 6);
        String query = normalize(prefix), upper = query + '\uffff';
        List<String> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int decoded = 0;
        long deadline = System.nanoTime() + 150000000L;
        boolean heavy = enterLargeRead();
        try {
            for (int b = 0; b < keys.size(); b++) {
                checkCancelled();
                if (System.nanoTime() > deadline) break;
                Block block = keys.get(b);
                if (block.min != null && (block.max.compareTo(query) < 0 || block.min.compareTo(upper) > 0)) continue;
                if (decoded + block.unpacked > MAX_BLOCK_BYTES) break;
                decoded += block.unpacked;
                for (Key key : keyBlock(b)) {
                    String normalized = normalize(key.text);
                    if (key.text.length() <= 128 && normalized.startsWith(query) && seen.add(normalized)) {
                        matches.add(key.text);
                        if (matches.size() == limit) return Collections.unmodifiableList(matches);
                    }
                }
            }
            return Collections.unmodifiableList(matches);
        } finally { if (heavy) LARGE_READ.release(); }
    }

    private List<String> lookupText(String word, Set<String> path, int depth, long[] budget) throws IOException {
        if (depth >= 32) throw corrupt("@@@LINK 超过 32 层");
        List<String> result = new ArrayList<>();
        // A redirect names a concrete spelling. Case variants often redirect to its
        // lowercase entry; including the alias again would manufacture a cycle.
        List<byte[]> found = depth == 0 ? find(word, budget) : find(word, budget, true);
        if (depth > 0 && found.isEmpty()) found = find(word, budget);
        for (byte[] bytes : found) {
            String text = decode(bytes, charset);
            while (text.endsWith("\u0000")) text = text.substring(0, text.length() - 1);
            if (text.startsWith("@@@LINK=")) {
                String target = text.substring(8).trim();
                if (!path.add(target)) throw corrupt("@@@LINK 循环：" + target);
                List<String> links;
                try { links = lookupText(target, path, depth + 1, budget); }
                finally { path.remove(target); }
                if (links.isEmpty()) throw corrupt("@@@LINK 目标不存在：" + target);
                result.addAll(links);
            } else result.add(text);
            if (result.size() > MAX_MATCHES) throw limit("重复词条或重定向结果过多");
        }
        return result;
    }

    /** Null means not found; an empty byte array is a present empty resource. */
    public synchronized byte[] readResource(String path) throws IOException {
        ensureOpen();
        if (!resource) throw unsupported("MDX 请使用词条查询接口");
        boolean heavy = enterLargeRead();
        try {
            List<byte[]> found = find(path.replace('/', '\\'), new long[1]);
            if (found.isEmpty() && !path.startsWith("\\") && !path.startsWith("/")) found = find("\\" + path.replace('/', '\\'), new long[1]);
            return found.isEmpty() ? null : found.get(0);
        } finally { if (heavy) LARGE_READ.release(); }
    }

    private boolean enterLargeRead() throws IOException {
        boolean heavy = false;
        for (Block block : keys) if (block.unpacked > 1024 * 1024) { heavy = true; break; }
        if (!heavy) for (Block block : records) if (block.unpacked > 1024 * 1024) { heavy = true; break; }
        if (!heavy) return false;
        try { LARGE_READ.acquire(); return true; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); checkCancelled(); return false; }
    }

    private List<byte[]> find(String word, long[] budget) throws IOException {
        return find(word, budget, false);
    }
    private List<byte[]> find(String word, long[] budget, boolean exactSpelling) throws IOException {
        List<byte[]> result = new ArrayList<>();
        String query = normalize(word);
        long resultBytes = 0;
        // Auxiliary ranges use actual keys, independent of dictionary collation.
        if (!boundsReady) {
            long previous = -1;
            for (int i = 0; i < keys.size(); i++) {
                List<Key> actual = keyBlock(i);
                if (actual.get(0).offset < previous) throw corrupt("跨 Key Block 的记录偏移逆序");
                previous = actual.get(actual.size() - 1).offset;
            }
            boundsReady = true; saveIndex(indexFile, indexFingerprint);
        }
        for (int b = 0; b < keys.size(); b++) {
            checkCancelled();
            Block block = keys.get(b);
            if (query.compareTo(block.min) < 0 || query.compareTo(block.max) > 0) continue;
            if (!filter(block.filter, query, false)) continue;
            List<Key> entries = keyBlock(b);
            for (int k = 0; k < entries.size(); k++) {
                Key key = entries.get(k);
                if (!normalize(key.text).equals(query)) continue;
                if (exactSpelling && !key.text.equals(word)) continue;
                long end = k + 1 < entries.size() ? entries.get(k + 1).offset
                        : b + 1 < keys.size() ? keyBlock(b + 1).get(0).offset : totalRecords;
                if (end < key.offset || end > totalRecords) throw corrupt("记录偏移逆序或越界");
                int length = bounded(end - key.offset, blockLimit(), "单条记录");
                resultBytes += length;
                budget[0] += length;
                int allowed = resource ? MAX_RESOURCE_BYTES : MAX_RESULT_BYTES;
                if (result.size() >= MAX_MATCHES || resultBytes > allowed || budget[0] > allowed) throw limit("查询结果或重定向超出内存限制");
                result.add(record(key.offset, length));
            }
        }
        return result;
    }

    private List<Key> keyBlock(int index) throws IOException {
        if (lastKeyBlock == index) return lastKeys;
        Block block = keys.get(index);
        Cursor cursor = new Cursor(unpack(read(block.position, block.packed), block.unpacked));
        List<Key> result = new ArrayList<>((int) block.count);
        long previous = -1;
        for (long i = 0; i < block.count; i++) {
            checkCancelled();
            long offset = cursor.number();
            if (offset < previous || offset > totalRecords) throw corrupt("Key 记录偏移逆序或越界");
            previous = offset;
            result.add(new Key(cursor.zeroString(), offset));
        }
        cursor.end();
        if (!summaryKey(result.get(0).text).equals(summaryKey(block.first))
                || !summaryKey(result.get(result.size() - 1).text).equals(summaryKey(block.last))) throw corrupt("Key 首尾词头与索引不符");
        block.min = block.max = normalize(result.get(0).text);
        block.filter = new byte[filterBytes(keys.size())];
        for (Key key : result) {
            String value = normalize(key.text);
            filter(block.filter, value, true);
            if (value.compareTo(block.min) < 0) block.min = value;
            if (value.compareTo(block.max) > 0) block.max = value;
        }
        lastKeys = result;
        lastKeyBlock = index;
        return result;
    }

    // Bounded Bloom summaries avoid decoding every overlapping range in dictionaries
    // with irregular collation. False positives still go through exact key matching.
    private static int filterBytes(int blocks) {
        return Integer.highestOneBit(Math.max(8, Math.min(1024, 2 * 1024 * 1024 / Math.max(1, blocks))));
    }
    private static boolean filter(byte[] bits, String value, boolean insert) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) { hash ^= value.charAt(i); hash *= 0x100000001b3L; }
        int first = (int) hash, step = (int) (hash >>> 32) | 1, mask = bits.length * 8 - 1;
        for (int i = 0; i < 4; i++) {
            int bit = (first + i * step) & mask;
            if (insert) bits[bit >>> 3] |= 1 << (bit & 7);
            else if ((bits[bit >>> 3] & (1 << (bit & 7))) == 0) return false;
        }
        return true;
    }

    // Index summaries may contain publisher-normalized sorting keys. Lookup still uses original keys.
    private String summaryKey(String value) {
        String key = normalize(value);
        String strip = attribute(attributes, "StripKey", attribute(attributes, "Stripkey", "No"));
        return !resource && "Yes".equalsIgnoreCase(strip) ? SUMMARY_PUNCTUATION.matcher(key).replaceAll("") : key;
    }

    private byte[] record(long offset, int length) throws IOException {
        byte[] output = new byte[length];
        int written = 0, low = 0, high = records.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            Block block = records.get(mid);
            if (block.start + block.unpacked <= offset) low = mid + 1;
            else high = mid;
        }
        for (int i = low; written < length && i < records.size(); i++) {
            checkCancelled();
            Block block = records.get(i);
            int start = (int) (offset - block.start);
            if (start < 0 || start > block.unpacked) throw corrupt("跨块记录偏移");
            byte[] decoded = recordCache.get(i);
            if (decoded == null) {
                decoded = unpack(read(block.position, block.packed), block.unpacked);
                if (decoded.length <= CACHE_BYTES) {
                    while (cachedBytes + decoded.length > CACHE_BYTES && !recordCache.isEmpty()) {
                        Integer oldest = recordCache.keySet().iterator().next();
                        cachedBytes -= recordCache.remove(oldest).length;
                    }
                    recordCache.put(i, decoded);
                    cachedBytes += decoded.length;
                }
            }
            int count = Math.min(length - written, decoded.length - start);
            System.arraycopy(decoded, start, output, written, count);
            written += count;
            offset += count;
        }
        if (written != length) throw corrupt("记录数据不完整");
        return output;
    }

    static byte[] unpack(byte[] input, int expected) throws IOException {
        if (input.length < 8) throw corrupt("压缩块头不完整");
        bounded(expected, MAX_RESOURCE_BYTES, "解压长度");
        if (expected > Math.max(1024L, (input.length - 8L) * 1024L)) throw limit("异常压缩比");
        if (input[1] != 0 || input[2] != 0 || input[3] != 0) throw unsupported("无法识别的压缩类型");
        byte[] output;
        int type = input[0] & 255;
        if (type == 0) {
            if (input.length - 8 != expected) throw corrupt("未压缩块长度不符");
            output = java.util.Arrays.copyOfRange(input, 8, input.length);
        } else if (type == 1) {
            output = LzoDecoder.decode(input, 8, expected);
        } else if (type == 2) {
            output = new byte[expected];
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(input, 8, input.length - 8);
                int count = 0;
                byte[] overflow = new byte[1];
                while (!inflater.finished()) {
                    checkCancelled();
                    int read = count < expected ? inflater.inflate(output, count, expected - count) : inflater.inflate(overflow);
                    if (count == expected && read > 0) throw corrupt("zlib 解压超出声明长度");
                    count += read;
                    if (read == 0 && !inflater.finished()) throw corrupt("zlib 截断、字典请求或无进展");
                }
                if (count != expected || inflater.getRemaining() != 0) throw corrupt("zlib 解压长度或文件尾不符");
            } catch (DataFormatException failure) { throw corrupt("zlib 数据损坏"); }
            finally { inflater.end(); }
        } else throw unsupported("不支持的压缩类型 " + type);
        verify(output, unsigned(java.util.Arrays.copyOfRange(input, 4, 8), 4));
        return output;
    }

    private int blockLimit() { return resource ? MAX_RESOURCE_BYTES : MAX_BLOCK_BYTES; }
    private String normalize(String text) { return caseSensitive ? text : text.toLowerCase(Locale.ROOT); }
    private byte[] read(long position, int length) throws IOException {
        checkCancelled();
        checkRange(position, length);
        byte[] bytes = new byte[length];
        source.read(position, bytes, 0, length);
        return bytes;
    }
    private void checkRange(long position, long length) throws IOException {
        if (position < 0 || length < 0 || position > size || length > size - position) throw corrupt("文件偏移或长度越界");
    }
    private static long add(long first, long second) throws IOException {
        if (first < 0 || second < 0 || second > Long.MAX_VALUE - first) throw corrupt("整数溢出");
        return first + second;
    }
    private static int bounded(long value, int max, String field) throws IOException {
        if (value < 0 || value > max) throw limit(field + "超过限制（" + value + "）");
        return (int) value;
    }
    private static long unsigned(byte[] bytes, int width) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long value = width == 4 ? buffer.getInt() & 0xffffffffL : buffer.getLong();
        if (value < 0) throw corrupt("无符号整数超出支持范围");
        return value;
    }
    private static void verify(byte[] bytes, long expected) throws IOException {
        Adler32 checksum = new Adler32();
        checksum.update(bytes);
        if (checksum.getValue() != expected) throw corrupt("Adler32 校验失败");
    }
    private static String decode(byte[] bytes, Charset charset) throws IOException {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw corrupt("无效 Unicode 或文字编码"); }
    }
    private static String unescape(String text) {
        return text.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
    private static String attribute(Map<String, String> attributes, String key, String fallback) {
        String value = attributes.get(key); return value == null ? fallback : value;
    }
    static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new Failure(Error.CANCELLED, "操作已取消");
    }
    private void ensureOpen() throws IOException { if (closed) throw new Failure(Error.INACCESSIBLE, "词典已经关闭"); }
    private static Failure corrupt(String message) { return new Failure(Error.CORRUPT, message); }
    private static Failure unsupported(String message) { return new Failure(Error.UNSUPPORTED, message); }
    private static Failure limit(String message) { return new Failure(Error.LIMIT, message); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        lastKeys = null;
        recordCache.clear();
        cachedBytes = 0;
        source.close();
    }

    private final class Cursor {
        final byte[] bytes;
        int position;
        Cursor(byte[] bytes) { this.bytes = bytes; }
        void require(int count) throws IOException {
            if (count < 0 || count > bytes.length - position) throw corrupt("索引或词头截断");
        }
        long number() throws IOException {
            require(width);
            long value = unsigned(java.util.Arrays.copyOfRange(bytes, position, position + width), width);
            position += width;
            return value;
        }
        String summary() throws IOException {
            int count;
            require(width == 8 ? 2 : 1);
            if (width == 8) { count = ((bytes[position] & 255) << 8) | (bytes[position + 1] & 255); position += 2; }
            else count = bytes[position++] & 255;
            require(count * unit + (width == 8 ? unit : 0));
            String result = decode(java.util.Arrays.copyOfRange(bytes, position, position + count * unit), charset);
            position += count * unit;
            if (width == 8) {
                for (int i = 0; i < unit; i++) if (bytes[position++] != 0) throw corrupt("词头摘要缺少结束符");
            }
            return result;
        }
        String zeroString() throws IOException {
            int start = position;
            for (;;) {
                require(unit);
                boolean zero = bytes[position] == 0 && (unit == 1 || bytes[position + 1] == 0);
                position += unit;
                if (position - start > 65536) throw limit("词头过长");
                if (zero) return decode(java.util.Arrays.copyOfRange(bytes, start, position - unit), charset);
            }
        }
        void end() throws IOException { if (position != bytes.length) throw corrupt("索引存在未消费数据"); }
    }
}
