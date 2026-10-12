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
            summary(index, keys[start], encoding, width); summary(index, keys[end - 1], encoding, width);
            number(index, packed.length, width); number(index, k.size(), width);
        }
        byte[] keyIndex = width == 8 ? envelope(idx.toByteArray(), 2) : idx.toByteArray();
        int keyBytes = keyBlocks.stream().mapToInt(a -> a.length).sum();
        List<byte[]> recordBlocks = new ArrayList<>();
        byte[] records = rec.toByteArray();
        // Deliberately split UTF-16 units and UTF-8 code points between blocks.
        for (int start = 0; start < records.length; start += 7) {
            recordBlocks.add(envelope(Arrays.copyOfRange(records, start, Math.min(start + 7, records.length)), compression));
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
            number(out, Math.min(7, records.length - i * 7), width);
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
        referenceVectors(args[0]); unsupportedHeaders();
        System.out.println("MdictReader: " + checks + " assertions passed (v1/v2, MDX/MDD, UTF8/UTF16LE, raw/zlib/LZO, cross-block, duplicates, links, malformed/fuzz)");
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
    private static byte[] headerChange(byte[] file,String from,String to)throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(file));int length=in.readInt();byte[] header=new byte[length];in.readFully(header);in.readInt();
        byte[] changed=new String(header,StandardCharsets.UTF_16LE).replace(from,to).getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.writeInt(changed.length);out.write(changed);out.writeInt(Integer.reverseBytes((int)checksum(changed)));out.write(file,8+length,file.length-8-length);
        return buffer.toByteArray();
    }
    private static void unsupportedHeaders()throws Exception {
        byte[] good=fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"a"},new byte[][]{{65}});
        fails(()->reader(headerChange(good,"GeneratedByEngineVersion=\"2.0\"","GeneratedByEngineVersion=\"3.0\"")),MdictReader.Error.UNSUPPORTED);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"2\"")),MdictReader.Error.UNSUPPORTED);
        fails(()->reader(headerChange(good,"Encrypted=\"0\"","Encrypted=\"1\"")),MdictReader.Error.UNSUPPORTED);
        byte[] mdd=fixture(8,StandardCharsets.UTF_16LE,2,true,new String[]{"\\IMAGE.PNG"},new byte[][]{{1,2,3}});
        try(MdictReader r=reader(headerChange(mdd,"KeyCaseSensitive=\"No\"","KeyCaseSensitive=\"Yes\""))){check(Arrays.equals(r.readResource("image.png"),new byte[]{1,2,3}),"Resource case differences independent of MDX key case policy");}
    }
    private static void persistentIndex() throws Exception {
        File directory = Files.createTempDirectory("jpdict-index-test").toFile();
        File dictionary = new File(directory, "test.mdx"), index = new File(directory, "test.idx");
        byte[] data = fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"a"}, new byte[][]{bytes("entry", StandardCharsets.UTF_8)});
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
