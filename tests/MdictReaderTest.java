package com.yuen.jpdict;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Original generated fixtures: no copyrighted dictionary content or engine code.
 * The fixture writer is deliberately separate from the reader's byte cursors. */
@SuppressWarnings("NewApi")
public final class MdictReaderTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static byte[] bytes(String text, Charset encoding) { return text.getBytes(encoding); }
    private static void number(DataOutputStream out, long value, int width) throws IOException {
        if (width == 4) out.writeInt((int) value); else out.writeLong(value);
    }
    private static void summary(DataOutputStream out, String text, Charset encoding, int width) throws IOException {
        byte[] value = bytes(text, encoding);
        int unit = encoding.name().startsWith("UTF-16") ? 2 : 1;
        if (width == 8) out.writeShort(value.length / unit); else out.writeByte(value.length / unit);
        out.write(value);
        if (width == 8) out.write(new byte[unit]);
    }
    private static long checksum(byte[] data) { Adler32 a = new Adler32(); a.update(data); return a.getValue(); }
    private static byte[] lzoLiteral(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (data.length <= 238) out.write(data.length + 17);
        else {
            out.write(0);
            int length = data.length - 18;
            while (length > 255) { out.write(0); length -= 255; }
            out.write(length);
        }
        out.write(data);
        out.write(new byte[]{17, 0, 0});
        return out.toByteArray();
    }
    private static byte[] envelope(byte[] data, int compression) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (compression == 2) {
            try (DeflaterOutputStream z = new DeflaterOutputStream(body)) { z.write(data); }
        } else if (compression == 1) body.write(lzoLiteral(data));
        else body.write(data);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        out.write(new byte[]{(byte) compression, 0, 0, 0});
        out.writeInt((int) checksum(data));
        out.write(body.toByteArray());
        return result.toByteArray();
    }
    static byte[] fixture(int width, Charset encoding, int compression, boolean resource,
            String[] keys, byte[][] values) throws IOException {
        return fixture(width, encoding, compression, resource, keys, values, keys);
    }
    private static byte[] fixture(int width, Charset encoding, int compression, boolean resource,
            String[] keys, byte[][] values, String[] summaries) throws IOException {
        return fixture(width, encoding, compression, resource, keys, values, summaries, 7);
    }
    private static byte[] fixture(int width, Charset encoding, int compression, boolean resource,
            String[] keys, byte[][] values, String[] summaries, int recordBlockBytes) throws IOException {
        String xml = "<" + (resource ? "Library_Data" : "Dictionary")
                + " GeneratedByEngineVersion=\"" + (width == 8 ? "2.0" : "1.2")
                + "\" RequiredEngineVersion=\"" + (width == 8 ? "2.0" : "1.2")
                + "\" Encoding=\"" + encoding.name() + "\" Title=\"JPDict 原创测试\" Encrypted=\"0\" KeyCaseSensitive=\"No\"/>\u0000";
        byte[] header = bytes(xml, StandardCharsets.UTF_16LE);
        int unit = encoding.name().startsWith("UTF-16") ? 2 : 1;
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        long[] offsets = new long[keys.length];
        for (int i = 0; i < keys.length; i++) { offsets[i] = rec.size(); rec.write(values[i]); }
        List<byte[]> keyBlocks = new ArrayList<>();
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        DataOutputStream index = new DataOutputStream(idx);
        // Two rows per block deliberately split duplicate keys across blocks.
        for (int start = 0; start < keys.length; start += 2) {
            int end = Math.min(start + 2, keys.length);
            ByteArrayOutputStream k = new ByteArrayOutputStream();
            DataOutputStream ko = new DataOutputStream(k);
            for (int i = start; i < end; i++) {
                number(ko, offsets[i], width); ko.write(bytes(keys[i], encoding)); ko.write(new byte[unit]);
            }
            byte[] packed = envelope(k.toByteArray(), compression);
            keyBlocks.add(packed);
            number(index, end - start, width);
            summary(index, summaries[start], encoding, width); summary(index, summaries[end - 1], encoding, width);
            number(index, packed.length, width); number(index, k.size(), width);
        }
        byte[] keyIndex = width == 8 ? envelope(idx.toByteArray(), 2) : idx.toByteArray();
        int keyBytes = keyBlocks.stream().mapToInt(a -> a.length).sum();
        List<byte[]> recordBlocks = new ArrayList<>();
        byte[] records = rec.toByteArray();
        // Deliberately split UTF-16 units and UTF-8 code points between blocks.
        for (int start = 0; start < records.length; start += recordBlockBytes) {
            recordBlocks.add(envelope(Arrays.copyOfRange(records, start, Math.min(start + recordBlockBytes, records.length)), compression));
        }
        if (recordBlocks.isEmpty()) recordBlocks.add(envelope(new byte[0], 0));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeInt(header.length); out.write(header); out.writeInt(Integer.reverseBytes((int) checksum(header)));
        ByteArrayOutputStream kh = new ByteArrayOutputStream();
        DataOutputStream khead = new DataOutputStream(kh);
        number(khead, keyBlocks.size(), width); number(khead, keys.length, width);
        if (width == 8) number(khead, idx.size(), width);
        number(khead, keyIndex.length, width); number(khead, keyBytes, width);
        out.write(kh.toByteArray());
        if (width == 8) out.writeInt((int) checksum(kh.toByteArray()));
        out.write(keyIndex);
        for (byte[] k : keyBlocks) out.write(k);
        number(out, recordBlocks.size(), width); number(out, keys.length, width);
        number(out, (long) recordBlocks.size() * width * 2, width);
        number(out, recordBlocks.stream().mapToInt(a -> a.length).sum(), width);
        for (int i = 0; i < recordBlocks.size(); i++) {
            number(out, recordBlocks.get(i).length, width);
            number(out, Math.min(recordBlockBytes, records.length - i * recordBlockBytes), width);
        }
        for (byte[] block : recordBlocks) out.write(block);
        return file.toByteArray();
    }
    private static MdictReader reader(byte[] data) throws IOException {
        return MdictReader.open(new MdictReader.Source() {
            public long size() { return data.length; }
            public void read(long position, byte[] target, int offset, int length) throws IOException {
                if (position < 0 || position + length > data.length) throw new EOFException();
                System.arraycopy(data, (int) position, target, offset, length);
            }
            public void close() {}
        });
    }
    private interface Operation { void run() throws Exception; }
    private static void fails(Operation operation, MdictReader.Error... errors) throws Exception {
        try { operation.run(); throw new AssertionError("Accepted invalid data"); }
        catch (MdictReader.Failure failure) {
            check(Arrays.asList(errors).contains(failure.error), "Wrong failure: " + failure.error + " " + failure.getMessage());
        }
    }
    private static void formats() throws Exception {
        for (int width : new int[]{4, 8}) for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE})
            for (int compression : new int[]{0, 1, 2}) {
                String[] keys = {"Alpha", "dup", "dup", "empty", "link", "日本語", "漢字", "한국어", "😀"};
                String[] text = {"<p>English</p>", "第一义", "第二义", "", "@@@LINK=日本語\r\n", "<p>日本語😀</p>", "中文", "한국어", "emoji"};
                byte[][] values = new byte[text.length][];
                for (int i = 0; i < text.length; i++) values[i] = bytes(text[i] + (i == 3 ? "" : "\u0000"), encoding);
                try (MdictReader r = reader(fixture(width, encoding, compression, false, keys, values))) {
                    r.validate((done,total)->{});
                    check(r.getMetadata().entries == keys.length, "Metadata count");
                    check(r.lookup("alpha").get(0).equals(text[0]), "Case insensitive");
                    check(r.lookup("dup").equals(Arrays.asList("第一义", "第二义")), "Duplicates across key blocks");
                    check(r.lookup("empty").equals(Collections.singletonList("")), "Empty differs from missing");
                    check(r.lookup("missing").isEmpty(), "Missing");
                    check(r.lookup("link").get(0).equals(text[5]), "Link");
                    for (int i = 5; i < keys.length; i++) check(r.lookup(keys[i]).get(0).equals(text[i]), "Unicode cross-block " + keys[i]);
                    check(r.lookup("日本語").get(0).equals(text[5]), "Repeated hot query");
                }
                String[] paths = {"\\audio.wav", "\\font.woff", "\\img.png", "\\style.css"};
                byte[][] data = {{'R','I','F','F',0,1,2,3}, {1,2,3,4,5}, {(byte)137,80,78,71,13,10,26,10}, bytes("@import 'other.css'; p{background:url(img.png)}", StandardCharsets.UTF_8)};
                try (MdictReader r = reader(fixture(width, StandardCharsets.UTF_16LE, compression, true, paths, data))) {
                    r.validate((done,total)->{});
                    for (int i = 0; i < paths.length; i++) check(Arrays.equals(r.readResource(paths[i]), data[i]), "MDD bytes " + paths[i]);
                    check(Arrays.equals(r.readResource("img.png"), data[2]), "MDD slash normalization");
                    check(r.readResource("absent") == null, "MDD not found");
                }
            }
    }
    private static void lzo() throws Exception {
        try(MdictReader r=reader(fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"A","Zulu","B"},new byte[][]{{65},{90},{66}}))) {
            check(r.lookup("zulu").get(0).equals("Z"),"Locale/case collation cannot hide a key outside first/last summary range");
        }
        for (int length : new int[]{1, 2, 3, 4, 18, 238, 239, 273, 274, 510, 8192}) {
            byte[] data = new byte[length]; new Random(length).nextBytes(data);
            check(Arrays.equals(LzoDecoder.decode(lzoLiteral(data), length), data), "LZO literals " + length);
        }
        // M2, M3, overlapping back-references and trailing literal state.
        check(new String(LzoDecoder.decode(new byte[]{21, 'a','b','c','d', 64,0, 17,0,0}, 7), StandardCharsets.US_ASCII).equals("abcdddd"), "M2 overlap");
        check(new String(LzoDecoder.decode(new byte[]{21, 'a','b','c','d', 36,12,0, 17,0,0}, 10), StandardCharsets.US_ASCII).equals("abcdabcdab"), "M3 overlap");
        check(new String(LzoDecoder.decode(new byte[]{21,'a','b','c','d',65,0,'e',0,0,17,0,0},10), StandardCharsets.US_ASCII).equals("abcddddeee"), "Short match state");
        ByteArrayOutputStream longMatch = new ByteArrayOutputStream();
        byte[] prefix = new byte[17000]; Arrays.fill(prefix, (byte)'x');
        byte[] literal = lzoLiteral(prefix); longMatch.write(literal, 0, literal.length - 3);
        longMatch.write(new byte[]{18,4,0,17,0,0});
        byte[] result = LzoDecoder.decode(longMatch.toByteArray(), 17004);
        check(result[17003] == 'x', "M4 distant match");
        fails(() -> LzoDecoder.decode(new byte[]{21,'a','b','c','d',32,0,0,0}, 10), MdictReader.Error.CORRUPT);
        fails(() -> LzoDecoder.decode(new byte[]{64,0,17,0,0}, 3), MdictReader.Error.CORRUPT);
        fails(() -> LzoDecoder.decode(new byte[]{18,'a',17,0,0,0}, 1), MdictReader.Error.CORRUPT);
        fails(() -> LzoDecoder.decode(new byte[]{18,'a',17,0,0}, 2), MdictReader.Error.CORRUPT);
        fails(() -> LzoDecoder.decode(new byte[]{0,0,0,0,0,0,0}, 1), MdictReader.Error.CORRUPT);
    }
    private static void corrupt() throws Exception {
        byte[] good = fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"a","b"}, new byte[][]{bytes("@@@LINK=b",StandardCharsets.UTF_8),bytes("@@@LINK=a",StandardCharsets.UTF_8)});
        try (MdictReader r = reader(good)) { fails(() -> r.lookup("a"), MdictReader.Error.CORRUPT); }
        for (int length = 0; length < good.length; length++) {
            final byte[] truncated = Arrays.copyOf(good, length);
            fails(() -> { try (MdictReader r = reader(truncated)) { r.lookup("a"); } }, MdictReader.Error.CORRUPT, MdictReader.Error.LIMIT);
        }
        byte[] modified = good.clone(); modified[4] ^= 1;
        fails(() -> reader(modified), MdictReader.Error.CORRUPT);
        fails(() -> MdictReader.unpack(new byte[]{9,0,0,0,0,0,0,1},0), MdictReader.Error.UNSUPPORTED);
        fails(() -> MdictReader.unpack(new byte[]{0,0,0,0,0,0,0,1},Integer.MAX_VALUE), MdictReader.Error.LIMIT);
        byte[] z = envelope(bytes("long text",StandardCharsets.UTF_8),2);
        fails(() -> MdictReader.unpack(z,1), MdictReader.Error.CORRUPT);
        fails(() -> MdictReader.unpack(Arrays.copyOf(z,z.length+1),9), MdictReader.Error.CORRUPT);
        fails(() -> MdictReader.unpack(Arrays.copyOf(z,z.length-1),9), MdictReader.Error.CORRUPT);
        Thread.currentThread().interrupt();
        try { fails(() -> reader(good), MdictReader.Error.CANCELLED); } finally { Thread.interrupted(); }
        MdictReader r = reader(good); r.close(); r.close();
        fails(() -> r.lookup("a"), MdictReader.Error.INACCESSIBLE);
        Random random = new Random(20261012);
        for (int i = 0; i < 1000; i++) {
            byte[] fuzz = new byte[random.nextInt(1024)]; random.nextBytes(fuzz);
            fails(() -> reader(fuzz), MdictReader.Error.CORRUPT, MdictReader.Error.LIMIT, MdictReader.Error.UNSUPPORTED);
        }
    }
    public static void main(String[] args) throws Exception {
        formats(); lzo(); corrupt(); persistentIndex(); sparse();
        referenceVectors(args[0]); unsupportedHeaders(); compactHeaders(); normalizedSummaries(); encryptedIndexes(); caseVariantLinks(); largeResources(); overlappingRanges(); downloadTrailers(); completions();
        System.out.println("MdictReader: " + checks + " assertions passed (v1/v2, MDX/MDD, UTF8/UTF16LE, raw/zlib/LZO, cross-block, duplicates, links, malformed/fuzz)");
    }
    private static byte[] appended(byte[] file, byte[] tail) {
        byte[] result = Arrays.copyOf(file, file.length + tail.length);
        System.arraycopy(tail, 0, result, file.length, tail.length); return result;
    }
    private static void downloadTrailers() throws Exception {
        byte[] tail = withDownloadTrailer(new byte[0]);
        check(tail.length == 197, "Reported 197-byte download trailer reproduced with original text");
        for (int compression : new int[]{0, 1, 2}) {
            byte[] good = fixture(8, StandardCharsets.UTF_16LE, compression, true,
                    new String[]{"\\image.png"}, new byte[][]{{1,2,3,4,5}});
            try (MdictReader r = reader(appended(good, tail))) {
                r.validate((d,t)->{});
                check(Arrays.equals(r.readResource("image.png"), new byte[]{1,2,3,4,5}), "416 tail does not change resource bytes");
            }
            fails(() -> reader(appended(good, Arrays.copyOf(tail, tail.length - 1))), MdictReader.Error.CORRUPT);
            fails(() -> reader(appended(good, appended(tail, new byte[]{0}))), MdictReader.Error.CORRUPT);
            fails(() -> reader(appended(good, bytes("<html>unknown extension</html>", StandardCharsets.UTF_8))), MdictReader.Error.CORRUPT);
            fails(() -> reader(appended(Arrays.copyOf(good, good.length - 1), tail)), MdictReader.Error.CORRUPT);
            byte[] damaged = good.clone(); damaged[damaged.length - 4] ^= 0x40;
            fails(() -> { try (MdictReader r = reader(appended(damaged, tail))) { r.validate((d,t)->{}); } }, MdictReader.Error.CORRUPT);
        }
        byte[] mdx = fixture(8, StandardCharsets.UTF_8, 0, false, new String[]{"a"}, new byte[][]{bytes("text", StandardCharsets.UTF_8)});
        fails(() -> reader(appended(mdx, tail)), MdictReader.Error.CORRUPT);
    }
    static byte[] withDownloadTrailer(byte[] file) {
        return appended(file, bytes("<html>\r\n<head><title>416 Requested Range Not Satisfiable</title></head>\r\n<body>\r\n"
                + "<center><h1>416 Requested Range Not Satisfiable</h1></center>\r\n<hr><center>nginx/1.27.0</center>\r\n</body>\r\n</html>\r\n", StandardCharsets.US_ASCII));
    }
    private static void completions() throws Exception {
        String[] fuzzy = {"myselfish", "myselfs", "myself00", "myself01", "myself02", "myself03", "myself04", "myself05", "myself06", "myself07", "myself08", "myself09", "myself10", "myself", "yourself"};
        byte[][] definitions = new byte[fuzzy.length][];
        for (int i = 0; i < definitions.length; i++) definitions[i] = bytes("text", StandardCharsets.UTF_8);
        try (MdictReader r = reader(fixture(8, StandardCharsets.UTF_8, 2, false, fuzzy, definitions))) {
            r.validate((d,t)->{});
            List<String> ranked = r.suggest("myself", 12);
            check(ranked.size() == 12 && ranked.get(0).equals("myself") && ranked.get(1).equals("myselfs"), "Twelve candidates rank exact and closest prefix ahead of earlier dictionary entries");
            check(r.suggest("myslef", 12).get(0).equals("myself"), "Adjacent spelling transposition finds the original word");
            check(r.suggest("mself", 12).contains("myself"), "Missing letter fuzzy completion");
            check(r.suggest("self", 12).contains("myself"), "Substring completion");
            check(r.suggest("ＭＹＳＥＬＦ", 12).get(0).equals("myself"), "Full-width input normalization");
            check(r.suggest("xyzwxyz", 12).isEmpty(), "Unrelated words are excluded");
        }
        WordSuggestions kana = new WordSuggestions("にほん", 12);
        kana.add("ニホン"); kana.add("ﾆﾎﾝ");
        check(kana.words().equals(Collections.singletonList("ニホン")), "Kana width and script equivalents deduplicate");
        String[] keys = {"outside", "Outward", "outside", "日本語", "日本人", "other", "outlook"};
        byte[][] values = new byte[keys.length][];
        for (int i = 0; i < values.length; i++) values[i] = bytes("text", StandardCharsets.UTF_8);
        try (MdictReader r = reader(fixture(8, StandardCharsets.UTF_8, 2, false, keys, values))) {
            r.validate((d,t)->{});
            check(r.suggest("out", 6).equals(Arrays.asList("outside", "Outward", "outlook")), "Completion preserves spelling and irregular dictionary order, deduplicates");
            check(r.suggest("OUT", 1).equals(Collections.singletonList("outside")), "Case folding and bounded completion count");
            check(r.suggest("日本", 6).size() == 2, "Unicode completion");
            check(r.suggest("missing", 6).isEmpty() && r.suggest("", 6).isEmpty(), "No invented or empty completions");
            check(r.lookup("outside").size() == 2, "Completions preserve duplicate exact lookup");
            check(r.words(0, 50).equals(Arrays.asList(keys)), "Browser preserves headword order and duplicates across blocks");
            check(r.words(2, 3).equals(Arrays.asList("outside", "日本語", "日本人")), "Headword page starts inside a block and spans the next");
            check(r.words(keys.length, 50).isEmpty() && r.words(-1, 50).isEmpty(), "Headword page boundaries");
            Thread.currentThread().interrupt();
            try { fails(() -> r.suggest("out", 6), MdictReader.Error.CANCELLED); }
            finally { Thread.interrupted(); }
            Thread.currentThread().interrupt();
            try { fails(() -> r.words(0, 50), MdictReader.Error.CANCELLED); }
            finally { Thread.interrupted(); }
        }
    }
    private static void referenceVectors(String directory) throws Exception {
        for(int v=0;v<3;v++) {
            byte[] original=new byte[65536];int state=20261012;
            for(int i=0;i<original.length;i++) {
                state=state*1664525+1013904223;
                if(v==0)original[i]=(byte)((i/64)%17+65);
                else if(v==1&&i>=32768)original[i]=i%4096==0?(byte)(state>>>24):original[i-20000];
                else if(v==2&&i>=4096)original[i]=i%1024==0?(byte)(state>>>24):original[i-2049];
                else original[i]=(byte)(state>>>24);
            }
            byte[] compressed=Files.readAllBytes(new File(directory,"lzo-2.10-"+v+".bin").toPath());
            check(Arrays.equals(LzoDecoder.decode(compressed,original.length),original),"Independent LZO 2.10 compressor vector "+v);
        }
    }
    static byte[] headerChange(byte[] file,String from,String to)throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(file));int length=in.readInt();byte[] header=new byte[length];in.readFully(header);in.readInt();
        byte[] changed=new String(header,StandardCharsets.UTF_16LE).replace(from,to).getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.writeInt(changed.length);out.write(changed);out.writeInt(Integer.reverseBytes((int)checksum(changed)));out.write(file,8+length,file.length-8-length);
        return buffer.toByteArray();
    }
    private static void unsupportedHeaders()throws Exception {
        byte[] good=fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"a"},new byte[][]{{65}});
        fails(()->reader(headerChange(good,"GeneratedByEngineVersion=\"2.0\"","GeneratedByEngineVersion=\"3.0\"")),MdictReader.Error.UNSUPPORTED);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"2\"")),MdictReader.Error.CORRUPT);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"1\"")),MdictReader.Error.UNSUPPORTED);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"3\"")),MdictReader.Error.UNSUPPORTED);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"4\"")),MdictReader.Error.UNSUPPORTED);
        byte[] mdd=fixture(8,StandardCharsets.UTF_16LE,2,true,new String[]{"\\IMAGE.PNG"},new byte[][]{{1,2,3}});
        try(MdictReader r=reader(headerChange(mdd,"KeyCaseSensitive=\"No\"","KeyCaseSensitive=\"Yes\""))){check(Arrays.equals(r.readResource("image.png"),new byte[]{1,2,3}),"Resource case differences independent of MDX key case policy");}
    }
    private static void compactHeaders() throws Exception {
        for (int width : new int[]{4, 8}) for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
            for (int compression = 0; compression <= 2; compression++) {
                byte[] plain = fixture(width, encoding, compression, false,
                        new String[]{"alias", "empty", "日本語", "日本語"}, new byte[][]{
                                bytes("@@@LINK=日本語\r\n", encoding), new byte[0],
                                bytes("<p>日本語 &amp; English `1`</p>\u0000", encoding), bytes("<b>重複</b>", encoding)});
                List<String> expected = Arrays.asList("<p>日本語 &amp; English `1`</p>", "<b>重複</b>");
                for (String flags : new String[]{"Compact=\"Yes\"", "Compat=\"Yes\"", "Compact=\"Yes\" Compat=\"Yes\""}) {
                    for (String sheet : new String[]{"", " StyleSheet=\"\"", " StyleSheet=\" \r\n\t\""}) {
                        byte[] compact = headerChange(plain, "/>", " " + flags + sheet + "/>");
                        try (MdictReader reader = reader(compact)) {
                            reader.validate((done, total) -> {});
                            check(reader.lookup("日本語").equals(expected), "Compact flag with no styles preserves cross-block Unicode, HTML and duplicates");
                            check(reader.lookup("alias").equals(expected), "Compact flag preserves links");
                            check(reader.lookup("empty").equals(Collections.singletonList("")), "Compact flag preserves empty entries");
                        }
                    }
                    fails(() -> reader(headerChange(plain, "/>", " " + flags + " StyleSheet=\"1\n&lt;b&gt;\n&lt;/b&gt;\"/>")), MdictReader.Error.UNSUPPORTED);
                }
            }
        }
        byte[] mdd = fixture(8, StandardCharsets.UTF_16LE, 2, true, new String[]{"\\style.css"}, new byte[][]{{0, 1, 2, 3}});
        try (MdictReader reader = reader(headerChange(mdd, "/>", " Compact=\"Yes\" StyleSheet=\"\"/>"))) {
            check(Arrays.equals(reader.readResource("style.css"), new byte[]{0, 1, 2, 3}), "Empty compact stylesheet never changes binary resources");
        }
    }
    private static void overlappingRanges() throws Exception {
        String[] keys = new String[200]; byte[][] values = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) { keys[i] = (i % 2 == 0 ? "a" : "z") + i; values[i] = bytes("entry" + i, StandardCharsets.UTF_8); }
        byte[] data = fixture(8, StandardCharsets.UTF_8, 2, false, keys, values);
        int[] reads = {0};
        try (MdictReader r = MdictReader.open(new MdictReader.Source() {
            public long size() { return data.length; }
            public void read(long position, byte[] target, int offset, int length) { reads[0]++; System.arraycopy(data, (int) position, target, offset, length); }
            public void close() {}
        })) {
            r.validate((done, total) -> {}); reads[0] = 0;
            check(r.lookup("middle-missing").isEmpty(), "Overlapping ranges retain correct missing result");
            check(reads[0] < 10, "Bloom summary skips almost all 100 overlapping key blocks; reads=" + reads[0]);
            for (int i = 0; i < keys.length; i++) check(r.lookup(keys[i]).equals(Collections.singletonList("entry" + i)), "Bloom filter never hides a present original key");
        }
    }
    private static void largeResources() throws Exception {
        byte[] data = new byte[9 * 1024 * 1024]; new Random(42).nextBytes(data);
        String[] paths = {"\\font.otf"};
        byte[] mdd = fixture(8, StandardCharsets.UTF_16LE, 2, true, paths, new byte[][]{data}, paths, data.length);
        try (MdictReader r = reader(mdd)) {
            r.validate((done, total) -> {});
            check(Arrays.equals(r.readResource("font.otf"), data), "Large CJK font record is read without loading the whole MDD");
        }
        byte[] mdx = fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"entry"}, new byte[][]{data}, new String[]{"entry"}, data.length);
        fails(() -> reader(mdx), MdictReader.Error.LIMIT);
    }
    private static void caseVariantLinks() throws Exception {
        for (int width : new int[]{4, 8}) for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
            byte[] data = fixture(width, encoding, 2, false, new String[]{"my", "MY", "alias", "Target", "dup", "dup"},
                    new byte[][]{bytes("definition", encoding), bytes("@@@LINK=my", encoding),
                            bytes("@@@LINK=target", encoding), bytes("fallback", encoding), bytes("first", encoding), bytes("second", encoding)});
            try (MdictReader r = reader(data)) {
                check(r.lookup("my").equals(Arrays.asList("definition", "definition")), "Case variant redirect does not create a false cycle");
                check(r.lookup("MY").equals(Arrays.asList("definition", "definition")), "Case insensitive lookup still retains both rows");
                check(r.lookup("alias").equals(Collections.singletonList("fallback")), "Redirect falls back when exact target spelling is absent");
                check(r.lookup("dup").equals(Arrays.asList("first", "second")), "Exact duplicates survive redirect preference");
            }
            byte[] cycle = fixture(width, encoding, 2, false, new String[]{"a", "A"},
                    new byte[][]{bytes("@@@LINK=A", encoding), bytes("@@@LINK=a", encoding)});
            try (MdictReader r = reader(cycle)) { fails(() -> r.lookup("a"), MdictReader.Error.CORRUPT); }
        }
    }
    private static void normalizedSummaries() throws Exception {
        String[] keys = {"$", "12 a.m", "A-b", "audio book", "日本 語"};
        String[] summaries = {"", "12am", "ab", "audiobook", "日本語"};
        for (int width : new int[]{4, 8}) for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
            byte[][] values = new byte[keys.length][];
            for (int i = 0; i < keys.length; i++) values[i] = bytes("entry-" + i, encoding);
            for (int compression = 0; compression <= 2; compression++) {
                byte[] file = fixture(width, encoding, compression, false, keys, values, summaries);
                for (String strip : new String[]{"StripKey", "Stripkey"}) {
                    try (MdictReader reader = reader(headerChange(file, "/>", " " + strip + "=\"Yes\" Compact=\"Yes\" StyleSheet=\"\"/>"))) {
                        reader.validate((done, total) -> {});
                        for (int i = 0; i < keys.length; i++) check(reader.lookup(keys[i]).equals(Collections.singletonList("entry-" + i)), "Normalized summary accepts original key " + keys[i]);
                        check(reader.lookup("ab").isEmpty(), "Sorting normalization does not broaden exact lookup");
                    }
                }
                fails(() -> { try (MdictReader reader = reader(file)) { reader.validate((done, total) -> {}); } }, MdictReader.Error.CORRUPT);
                byte[] wrong = fixture(width, encoding, compression, false, new String[]{"a-b"}, new byte[][]{bytes("entry", encoding)}, new String[]{"unrelated"});
                fails(() -> { try (MdictReader reader = reader(headerChange(wrong, "/>", " StripKey=\"Yes\"/>"))) { reader.lookup("a-b"); } }, MdictReader.Error.CORRUPT);
            }
        }
        byte[] casing = fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"A"}, new byte[][]{{65}}, new String[]{"a"});
        try (MdictReader reader = reader(casing)) { check(reader.lookup("a").equals(Collections.singletonList("A")), "Case-insensitive summary comparison"); }
        fails(() -> { try (MdictReader reader = reader(headerChange(casing, "KeyCaseSensitive=\"No\"", "KeyCaseSensitive=\"Yes\""))) { reader.lookup("A"); } }, MdictReader.Error.CORRUPT);
        byte[] punctuation = fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"日・本"}, new byte[][]{{65}}, new String[]{"日本"});
        fails(() -> { try (MdictReader reader = reader(headerChange(punctuation, "/>", " StripKey=\"Yes\"/>"))) { reader.lookup("日・本"); } }, MdictReader.Error.CORRUPT);
    }
    /** Synthetic writer follows the forward transform, separately from the production decoder. */
    static byte[] encryptedIndex(byte[] file) throws Exception {
        byte[] result = headerChange(file, "Encrypted=\"0\"", "Encrypted=\"2\"");
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(result);
        int header = input.getInt();
        int indexAt = 8 + header + 44;
        int indexLength = Math.toIntExact(input.getLong(8 + header + 24));
        byte[] seed = Arrays.copyOfRange(result, indexAt + 4, indexAt + 12);
        seed[4] = (byte) 0x95; seed[5] = 0x36; seed[6] = seed[7] = 0;
        byte[] key = MdictIndexDecoder.digest(seed);
        int previous = 0x36;
        for (int i = 0; i < indexLength - 8; i++) {
            int value = (result[indexAt + 8 + i] & 255) ^ previous ^ (i & 255) ^ (key[i % 16] & 255);
            int encoded = ((value << 4) | (value >>> 4)) & 255;
            result[indexAt + 8 + i] = (byte) encoded;
            previous = encoded;
        }
        return result;
    }
    private static void encryptedIndexes() throws Exception {
        String[] messages = {"", "a", "abc", "message digest", "abcdefghijklmnopqrstuvwxyz",
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890"};
        String[] hashes = {"cdf26213a150dc3ecb610f18f6b38b46", "86be7afa339d0fc7cfc785e72f578d33",
                "c14a12199c66e4ba84636b0f69144c77", "9e327b3d6e523062afc1132d7df9d1b8",
                "fd2aa607f71dc8f510714922b371834e", "a1aa0689d0fafa2ddc22e88b49133a06",
                "d1e959eb179c911faea4624c60c5c702", "3f45ef194732c2dbb2c4a2c769795fa3"};
        for (int i = 0; i < messages.length; i++) check(hex(MdictIndexDecoder.digest(bytes(messages[i], StandardCharsets.US_ASCII))).equals(hashes[i]), "Official RIPEMD-128 vector " + i);
        byte[] million = new byte[1000000]; Arrays.fill(million, (byte) 'a');
        check(hex(MdictIndexDecoder.digest(million)).equals("4a7f5723f954eba1216c9d8f6320431f"), "Official RIPEMD-128 million-byte vector");
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) for (int compression = 0; compression <= 2; compression++) {
            byte[] file = encryptedIndex(fixture(8, encoding, compression, false, new String[]{"a", "日本語"},
                    new byte[][]{bytes("@@@LINK=日本語", encoding), bytes("<b>索引混淆</b>", encoding)}));
            try (MdictReader reader = reader(file)) {
                reader.validate((done, total) -> {});
                check(reader.lookup("a").equals(Collections.singletonList("<b>索引混淆</b>")), "Encrypted=2 MDX lookup and links");
            }
            byte[] resource = encryptedIndex(fixture(8, StandardCharsets.UTF_16LE, compression, true, new String[]{"\\image.png"}, new byte[][]{{1, 2, 3}}));
            try (MdictReader reader = reader(resource)) {
                reader.validate((done, total) -> {});
                check(Arrays.equals(reader.readResource("image.png"), new byte[]{1, 2, 3}), "Encrypted=2 MDD resource");
            }
            byte[] damaged = file.clone(); damaged[damaged.length - 1] ^= 1;
            fails(() -> { try (MdictReader reader = reader(damaged)) { reader.validate((done, total) -> {}); } }, MdictReader.Error.CORRUPT);
        }
        fails(() -> MdictIndexDecoder.decode(new byte[7]), MdictReader.Error.CORRUPT);
        Thread.currentThread().interrupt();
        try { fails(() -> MdictIndexDecoder.decode(new byte[100]), MdictReader.Error.CANCELLED); } finally { Thread.interrupted(); }
    }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte value : data) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void persistentIndex() throws Exception {
        File directory = Files.createTempDirectory("jpdict-index-test").toFile();
        File dictionary = new File(directory, "test.mdx"), index = new File(directory, "test.idx");
        byte[] data = encryptedIndex(headerChange(fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"a"}, new byte[][]{bytes("entry", StandardCharsets.UTF_8)}),
                "/>", " Compact=\"Yes\" StyleSheet=\"\"/>"));
        try {
            try (FileOutputStream out = new FileOutputStream(dictionary)) { out.write(data); }
            try (MdictReader r = MdictReader.open(MdictReader.file(dictionary), index, "fingerprint")) { check(r.lookup("a").get(0).equals("entry"), "Initial indexed read"); }
            check(index.isFile(), "Index persisted");
            long[] readBytes = {0};
            MdictReader.Source source = MdictReader.file(dictionary);
            try (MdictReader r = MdictReader.open(new MdictReader.Source() {
                public long size() throws IOException { return source.size(); }
                public void read(long pos, byte[] dest, int off, int len) throws IOException { readBytes[0] += len; source.read(pos,dest,off,len); }
                public void close() throws IOException { source.close(); }
            }, index, "fingerprint")) {
                check(readBytes[0] == 0, "Persistent indexes reused without source index reads");
                check(r.lookup("a").get(0).equals("entry"), "Cached index query");
            }
            try (FileOutputStream out = new FileOutputStream(index)) { out.write(new byte[]{1,2,3,4,5,6,7,8}); }
            try (MdictReader r = MdictReader.open(MdictReader.file(dictionary), index, "fingerprint")) { check(r.lookup("a").get(0).equals("entry"), "Corrupt index rebuilt"); }
            try (MdictReader r = MdictReader.open(MdictReader.file(dictionary), index, "changed")) { check(r.lookup("a").get(0).equals("entry"), "Fingerprint invalidates index"); }
        } finally { index.delete(); dictionary.delete(); directory.delete(); }
    }
    private static void sparse() throws Exception {
        // A real seekable sparse >2 GiB file. Only the final record is materialized.
        File file = File.createTempFile("jpdict-sparse-", ".mdx");
        final int blocks = 260, blockSize = MdictReader.MAX_BLOCK_BYTES;
        String xml = "<Dictionary GeneratedByEngineVersion=\"2.0\" Encoding=\"UTF-8\" Encrypted=\"0\"/>";
        byte[] header = bytes(xml, StandardCharsets.UTF_16LE), record = bytes("large-file-entry",StandardCharsets.UTF_8);
        ByteArrayOutputStream keys = new ByteArrayOutputStream(); DataOutputStream k = new DataOutputStream(keys);
        k.writeLong((long)blocks * blockSize); k.write(bytes("z\u0000",StandardCharsets.UTF_8));
        byte[] packedKeys = envelope(keys.toByteArray(),2);
        ByteArrayOutputStream index = new ByteArrayOutputStream(); DataOutputStream ki = new DataOutputStream(index);
        ki.writeLong(1); summary(ki,"z",StandardCharsets.UTF_8,8); summary(ki,"z",StandardCharsets.UTF_8,8);
        ki.writeLong(packedKeys.length); ki.writeLong(keys.size()); byte[] packedIndex = envelope(index.toByteArray(),2);
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(prefix);
        out.writeInt(header.length); out.write(header); out.writeInt(Integer.reverseBytes((int)checksum(header)));
        ByteArrayOutputStream kh = new ByteArrayOutputStream(); DataOutputStream ho = new DataOutputStream(kh);
        ho.writeLong(1); ho.writeLong(1); ho.writeLong(index.size()); ho.writeLong(packedIndex.length); ho.writeLong(packedKeys.length);
        out.write(kh.toByteArray()); out.writeInt((int)checksum(kh.toByteArray())); out.write(packedIndex); out.write(packedKeys);
        out.writeLong(blocks+1); out.writeLong(1); out.writeLong((blocks+1)*16L);
        out.writeLong(blocks*(blockSize+8L) + record.length+8);
        for (int i=0;i<blocks;i++) { out.writeLong(blockSize+8L); out.writeLong(blockSize); }
        out.writeLong(record.length+8); out.writeLong(record.length);
        long[] consumed = {0};
        try {
            try (RandomAccessFile disk = new RandomAccessFile(file,"rw")) {
                disk.write(prefix.toByteArray());
                for (int i = 0; i < blocks; i++) {
                    disk.seek(prefix.size()+i*(blockSize+8L)); disk.writeInt(0);
                    disk.writeInt(((blockSize % 65521) << 16) | 1); // Adler32 for a sparse zero-filled block
                }
                disk.seek(prefix.size()+blocks*(blockSize+8L)); disk.write(envelope(record,0));
            }
            MdictReader.Source disk = MdictReader.file(file);
            long start = System.nanoTime();
            try (MdictReader r = MdictReader.open(new MdictReader.Source() {
                public long size() throws IOException { return disk.size(); }
                public void read(long p,byte[] b,int o,int l) throws IOException { consumed[0]+=l; disk.read(p,b,o,l); }
                public void close() throws IOException { disk.close(); }
            })) {
                check(r.getMetadata().fileBytes > 2L*1024*1024*1024, "Sparse fixture exceeds 2 GiB");
                check(r.lookup("z").get(0).equals("large-file-entry"), "64 bit random access");
                check(consumed[0] < 8192, "Sparse dictionary never fully loaded");
            }
            System.out.println("Sparse file " + file.length() + " bytes; read " + consumed[0] + " bytes; cold open/query " + ((System.nanoTime()-start)/1_000_000.0) + " ms");
        } finally { file.delete(); }
    }
}
