package com.yuen.jpdict;

import java.io.*;
import java.util.zip.*;

/** Checks and expands Record blocks with a fixed buffer, including large blocks. */
final class MdictBlockDecoder {
    static void decode(MdictReader.Source source, long position, int packed, int expected, OutputStream output) throws IOException {
        if (packed < 8 || expected < 0) throw corrupt("压缩块头或声明长度不完整");
        byte[] header = new byte[8]; source.read(position, header, 0, 8);
        if (header[1] != 0 || header[2] != 0 || header[3] != 0) throw corrupt("无法识别的压缩类型");
        long checksum = 0;
        for (int i = 4; i < 8; i++) checksum = (checksum << 8) | (header[i] & 255);
        RangeInput input = new RangeInput(source, position + 8, packed - 8);
        Adler32 adler = new Adler32();
        OutputStream checked = new OutputStream() {
            long written;
            @Override public void write(int value) throws IOException {
                MdictReader.checkCancelled();
                if (++written > expected) throw corrupt("解压超出声明长度");
                output.write(value); adler.update(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                MdictReader.checkCancelled();
                if (length > expected - written) throw corrupt("解压超出声明长度");
                output.write(bytes, offset, length); adler.update(bytes, offset, length); written += length;
            }
        };
        int type = header[0] & 255;
        long total = 0;
        if (type == 1) {
            LzoDecoder.decode(input, packed - 8, expected, checked); total = expected;
        } else if (type == 0 || type == 2) {
            Inflater inflater = type == 2 ? new Inflater() : null;
            InputStream decoded = inflater == null ? input : new InflaterInputStream(input, inflater, 65536);
            try {
                byte[] buffer = new byte[65536]; int count;
                while ((count = decoded.read(buffer)) != -1) {
                    MdictReader.checkCancelled(); checked.write(buffer, 0, count); total += count;
                }
                if (inflater != null && (!inflater.finished() || inflater.getRemaining() != 0 || input.remaining != 0))
                    throw corrupt("zlib 解压长度或文件尾不符");
            } catch (ZipException damaged) { throw corrupt("zlib 数据损坏"); }
            finally { if (inflater != null) inflater.end(); }
        } else throw new MdictReader.Failure(MdictReader.Error.UNSUPPORTED, "不支持的压缩类型 " + type);
        if (total != expected || input.remaining != 0) throw corrupt("解压长度与声明不符");
        if (adler.getValue() != checksum) throw corrupt("Adler32 校验失败");
    }
    private static final class RangeInput extends InputStream {
        final MdictReader.Source source;
        long position, remaining;
        RangeInput(MdictReader.Source source, long position, long length) { this.source = source; this.position = position; remaining = length; }
        @Override public int read() throws IOException { byte[] bytes = new byte[1]; return read(bytes, 0, 1) == -1 ? -1 : bytes[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            MdictReader.checkCancelled(); if (remaining == 0) return -1;
            int count = (int)Math.min(length, remaining);
            source.read(position, bytes, offset, count); position += count; remaining -= count; return count;
        }
    }
    private static IOException corrupt(String message) { return new MdictReader.Failure(MdictReader.Error.CORRUPT, message); }
}
