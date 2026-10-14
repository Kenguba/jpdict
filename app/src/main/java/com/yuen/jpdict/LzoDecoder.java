package com.yuen.jpdict;

import java.io.*;

/** Independent LZO1X v0 decoder with a fixed history window. The same grammar
 * serves small byte arrays and file-backed large Record blocks. */
final class LzoDecoder {
    private final InputStream input;
    private final OutputStream output;
    private final int inputLength, expected;
    private final byte[] history = new byte[65536], pending = new byte[65536];
    private int cursor, written, pendingCount;

    private LzoDecoder(InputStream input, int inputLength, int expected, OutputStream output) {
        this.input = new BufferedInputStream(input, 65536); this.inputLength = inputLength;
        this.expected = expected; this.output = output;
    }
    static byte[] decode(byte[] input, int length) throws IOException { return decode(input, 0, length); }
    static byte[] decode(byte[] input, int start, int length) throws IOException {
        if (length < 0 || length > MdictReader.MAX_RESOURCE_BYTES || start < 0 || start > input.length) throw invalid();
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        decode(new ByteArrayInputStream(input, start, input.length - start), input.length - start, length, output);
        return output.toByteArray();
    }
    static void decode(InputStream input, int packed, int expected, OutputStream output) throws IOException {
        if (packed < 0 || expected < 0) throw invalid();
        new LzoDecoder(input, packed, expected, output).run();
    }
    private void run() throws IOException {
        int state = 0;
        if (inputLength == 0) throw invalid();
        int first = next(), opcode = -1;
        if (first > 17) {
            int count = first - 17; literal(count); state = count < 4 ? count : 4;
        } else opcode = first;
        while (cursor < inputLength || opcode >= 0) {
            MdictReader.checkCancelled();
            if (opcode < 0) opcode = next();
            int length, distance, tail;
            if (opcode < 16 && state == 0) {
                literal(extended(opcode, 15) + 3); state = 4; opcode = -1; continue;
            }
            if (opcode < 16) {
                length = state == 4 ? 3 : 2;
                distance = (next() << 2) + (opcode >> 2) + (state == 4 ? 2049 : 1); tail = opcode & 3;
            } else if (opcode < 32) {
                length = extended(opcode & 7, 7) + 2;
                int operand = next() | (next() << 8);
                distance = 16384 + ((opcode & 8) << 11) + (operand >> 2); tail = operand & 3;
                if (distance == 16384) {
                    if (opcode != 17 || operand != 0 || cursor != inputLength || written != expected) throw invalid();
                    flush(); return;
                }
            } else if (opcode < 64) {
                length = extended(opcode & 31, 31) + 2;
                int operand = next() | (next() << 8); distance = (operand >> 2) + 1; tail = operand & 3;
            } else {
                length = (opcode >> 5) + 1; distance = (next() << 3) + ((opcode >> 2) & 7) + 1; tail = opcode & 3;
            }
            if (distance > written || distance <= 0 || length > expected - written) throw invalid();
            for (int i = 0; i < length; i++) emit(history[(written - distance) & 65535]);
            literal(tail); state = tail; opcode = -1;
        }
        throw invalid();
    }
    private int extended(int value, int mask) throws IOException {
        if (value != 0) return value;
        long count = mask; int b;
        while ((b = next()) == 0) { count += 255; if (count > expected) throw invalid(); }
        count += b; if (count > expected - 3L) throw invalid(); return (int)count;
    }
    private int next() throws IOException {
        if (cursor >= inputLength) throw invalid();
        int value = input.read(); if (value < 0) throw invalid(); cursor++; return value;
    }
    private void literal(int count) throws IOException {
        if (count < 0 || count > inputLength - cursor || count > expected - written) throw invalid();
        for (int i = 0; i < count; i++) emit((byte)next());
    }
    private void emit(byte value) throws IOException {
        history[written & 65535] = value; written++; pending[pendingCount++] = value;
        if (pendingCount == pending.length) { MdictReader.checkCancelled(); flush(); }
    }
    private void flush() throws IOException { output.write(pending, 0, pendingCount); pendingCount = 0; }
    private static IOException invalid() { return new MdictReader.Failure(MdictReader.Error.CORRUPT, "LZO 数据损坏或超出解压边界"); }
}
