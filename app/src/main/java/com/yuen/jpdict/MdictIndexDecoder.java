package com.yuen.jpdict;

import java.io.IOException;

/** MDict v2 Encrypted=2 index transform, independent of Android and third-party engines.
 * RIPEMD-128 is used only for the format's checksum-derived key, never for application security. */
final class MdictIndexDecoder {
    private static final int[] WORDS = {
            0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
            7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
            3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
            1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2};
    private static final int[] PARALLEL_WORDS = {
            5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
            6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
            15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
            8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14};
    private static final int[] ROTATIONS = {
            11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
            7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
            11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
            11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12};
    private static final int[] PARALLEL_ROTATIONS = {
            8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
            9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
            9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
            15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8};
    private static final int[] CONSTANTS = {0, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc};
    private static final int[] PARALLEL_CONSTANTS = {0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0};

    private MdictIndexDecoder() {}

    /** The compression/checksum prefix stays intact; transform the owned input buffer in place. */
    static void decode(byte[] packed) throws IOException {
        if (packed.length < 8) throw new MdictReader.Failure(MdictReader.Error.CORRUPT, "混淆索引头不完整");
        byte[] seed = {packed[4], packed[5], packed[6], packed[7], (byte) 0x95, 0x36, 0, 0};
        byte[] key = digest(seed);
        int previous = 0x36;
        for (int offset = 8; offset < packed.length; offset++) {
            int index = offset - 8;
            if ((index & 4095) == 0) MdictReader.checkCancelled();
            int encoded = packed[offset] & 255;
            int swapped = ((encoded >>> 4) | (encoded << 4)) & 255;
            packed[offset] = (byte) (swapped ^ previous ^ (index & 255) ^ (key[index & 15] & 255));
            previous = encoded;
        }
    }

    /** Original implementation from the published RIPEMD-128 mathematical specification. */
    static byte[] digest(byte[] input) throws IOException {
        int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        int[] block = new int[16];
        long padded = ((input.length + 9L + 63) / 64) * 64;
        long bits = input.length * 8L;
        for (long position = 0; position < padded; position += 64) {
            MdictReader.checkCancelled();
            for (int word = 0; word < 16; word++) {
                int value = 0;
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    long at = position + word * 4 + byteIndex;
                    int part = at < input.length ? input[(int) at] & 255 : at == input.length ? 0x80
                            : at >= padded - 8 ? (int) (bits >>> ((at - padded + 8) * 8)) & 255 : 0;
                    value |= part << (byteIndex * 8);
                }
                block[word] = value;
            }
            int a = state[0], b = state[1], c = state[2], d = state[3];
            int pa = a, pb = b, pc = c, pd = d;
            for (int step = 0; step < 64; step++) {
                int round = step / 16;
                int next = Integer.rotateLeft(a + mix(round, b, c, d) + block[WORDS[step]] + CONSTANTS[round], ROTATIONS[step]);
                a = d; d = c; c = b; b = next;
                next = Integer.rotateLeft(pa + mix(3 - round, pb, pc, pd) + block[PARALLEL_WORDS[step]] + PARALLEL_CONSTANTS[round], PARALLEL_ROTATIONS[step]);
                pa = pd; pd = pc; pc = pb; pb = next;
            }
            int first = state[1] + c + pd;
            state[1] = state[2] + d + pa;
            state[2] = state[3] + a + pb;
            state[3] = state[0] + b + pc;
            state[0] = first;
        }
        byte[] result = new byte[16];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (state[i / 4] >>> ((i % 4) * 8));
        return result;
    }

    private static int mix(int round, int x, int y, int z) {
        switch (round) {
            case 0: return x ^ y ^ z;
            case 1: return (x & y) | (~x & z);
            case 2: return (x | ~y) ^ z;
            default: return (x & z) | (y & ~z);
        }
    }
}
