package com.yuen.jpdict;

import java.io.IOException;

/** Independent LZO1X v0 decoder, written from the documented byte grammar.
 * No compressor/decompressor implementation is incorporated. */
final class LzoDecoder {
    private final byte[] input, output;
    private int cursor, written;

    private LzoDecoder(byte[] input, int start, int length) {
        this.input = input;
        cursor = start;
        output = new byte[length];
    }

    static byte[] decode(byte[] input, int length) throws IOException {
        return decode(input, 0, length);
    }
    static byte[] decode(byte[] input, int start, int length) throws IOException {
        if (length < 0 || length > MdictReader.MAX_BLOCK_BYTES) throw invalid();
        if (start < 0 || start > input.length) throw invalid();
        return new LzoDecoder(input, start, length).run();
    }

    private byte[] run() throws IOException {
        int state = 0;
        if (cursor == input.length) throw invalid();
        int first = input[cursor] & 255;
        if (first > 17) {
            cursor++;
            int count = first - 17;
            literal(count);
            state = count < 4 ? count : 4;
        }
        while (cursor < input.length) {
            MdictReader.checkCancelled();
            int opcode = next();
            int length, distance, tail;
            if (opcode < 16 && state == 0) {
                literal(extended(opcode, 15) + 3);
                state = 4;
                continue;
            }
            if (opcode < 16) {
                length = state == 4 ? 3 : 2;
                distance = (next() << 2) + (opcode >> 2) + (state == 4 ? 2049 : 1);
                tail = opcode & 3;
            } else if (opcode < 32) {
                length = extended(opcode & 7, 7) + 2;
                int operand = next() | (next() << 8);
                distance = 16384 + ((opcode & 8) << 11) + (operand >> 2);
                tail = operand & 3;
                if (distance == 16384) {
                    if (opcode != 17 || operand != 0 || cursor != input.length
                            || written != output.length) throw invalid();
                    return output;
                }
            } else if (opcode < 64) {
                length = extended(opcode & 31, 31) + 2;
                int operand = next() | (next() << 8);
                distance = (operand >> 2) + 1;
                tail = operand & 3;
            } else {
                length = (opcode >> 5) + 1;
                distance = (next() << 3) + ((opcode >> 2) & 7) + 1;
                tail = opcode & 3;
            }
            if (distance > written || distance <= 0 || length > output.length - written) throw invalid();
            for (int i = 0; i < length; i++) {
                output[written] = output[written - distance];
                written++;
            }
            literal(tail);
            state = tail;
        }
        throw invalid();
    }

    private int extended(int value, int mask) throws IOException {
        if (value != 0) return value;
        long count = mask;
        int b;
        while ((b = next()) == 0) {
            count += 255;
            if (count > output.length) throw invalid();
        }
        count += b;
        if (count > output.length) throw invalid();
        return (int) count;
    }

    private int next() throws IOException {
        if (cursor >= input.length) throw invalid();
        return input[cursor++] & 255;
    }

    private void literal(int count) throws IOException {
        if (count < 0 || count > input.length - cursor || count > output.length - written) throw invalid();
        System.arraycopy(input, cursor, output, written, count);
        cursor += count;
        written += count;
    }

    private static IOException invalid() {
        return new MdictReader.Failure(MdictReader.Error.CORRUPT, "LZO 数据损坏或超出解压边界");
    }
}
