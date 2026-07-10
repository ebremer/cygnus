package com.ebremer.jpegxl.brotli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Self-contained Brotli decoder (RFC 7932), used for the {@code jbrd} JPEG
 * reconstruction box. Supports the complete format: all context modes, the
 * static dictionary with its 121 word transforms, and metadata blocks.
 */
public final class Brotli {

    private Brotli() {
    }

    private static byte[] lastPartial = new byte[0];

    /** Decompresses a complete brotli stream. */
    public static byte[] decode(byte[] data, int off, int len, int maxOutput) throws IOException {
        State s = new State(data, off, len, maxOutput);
        try {
            return s.run();
        } catch (IOException | RuntimeException e) {
            lastPartial = java.util.Arrays.copyOf(s.out, s.outPos);
            throw e;
        }
    }

    /** Output produced before the last failure; debugging aid. */
    public static byte[] partial() {
        return lastPartial;
    }

    public static byte[] decode(byte[] data, int maxOutput) throws IOException {
        return decode(data, 0, data.length, maxOutput);
    }

    // ------------------------------------------------------------- constants

    /** Code length code order (RFC 7932 section 3.5). */
    private static final int[] CL_ORDER = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /** Insert length codes: base values and extra bits (section 5). */
    private static final int[] INSERT_BASE = {0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50,
        66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594};
    private static final int[] INSERT_EXTRA = {0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4,
        5, 5, 6, 7, 8, 9, 10, 12, 14, 24};
    private static final int[] COPY_BASE = {2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30,
        38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118};
    private static final int[] COPY_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3,
        4, 4, 5, 5, 6, 7, 8, 9, 10, 24};

    /** Insert-and-copy command cells (section 5). */
    private static final int[] CMD_INSERT_LUT = {0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16};
    private static final int[] CMD_COPY_LUT = {0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16};

    /** Block count codes (section 6). */
    private static final int[] BLOCK_BASE = {1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97,
        113, 145, 177, 209, 241, 305, 369, 497, 753, 1265, 2289, 4337, 8433, 16625};
    private static final int[] BLOCK_EXTRA = {2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
        5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24};

    /** Short distance codes: ring index and offset (section 4). */
    private static final int[] DIST_SHORT_INDEX = {0, 1, 2, 3, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1};
    private static final int[] DIST_SHORT_OFFSET = {0, 0, 0, 0, -1, 1, -2, 2, -3, 3, -1, 1, -2, 2, -3, 3};

    /** Static dictionary word-count bits and offsets per word length. */
    private static final int[] DICT_SIZE_BITS = {0, 0, 0, 0, 10, 10, 11, 11, 10, 10,
        10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5};
    private static final int[] DICT_OFFSET = {0, 0, 0, 0, 0, 4096, 9216, 21504, 35840, 44032,
        53248, 63488, 74752, 87040, 93696, 100864, 104704, 106752, 108928, 113536, 115968,
        118528, 119872, 121280, 122016, 122784};

    private static final byte[] DICTIONARY = loadResource("dictionary.bin", 122784);

    // The 121 transforms: (prefix id, operation, suffix id) triplets over the
    // length-prefixed string table below (RFC 7932 appendix B).
    private static final byte[] PREFIX_SUFFIX = {
        1, 32, 2, 44, 32, 8, 32, 111, 102, 32, 116, 104, 101, 32, 4, 32, 111, 102, 32, 2,
        115, 32, 1, 46, 5, 32, 97, 110, 100, 32, 4, 32, 105, 110, 32, 1, 34, 4, 32, 116,
        111, 32, 2, 34, 62, 1, 10, 2, 46, 32, 1, 93, 5, 32, 102, 111, 114, 32, 3, 32,
        97, 32, 6, 32, 116, 104, 97, 116, 32, 1, 39, 6, 32, 119, 105, 116, 104, 32, 6, 32,
        102, 114, 111, 109, 32, 4, 32, 98, 121, 32, 1, 40, 6, 46, 32, 84, 104, 101, 32, 4,
        32, 111, 110, 32, 4, 32, 97, 115, 32, 4, 32, 105, 115, 32, 4, 105, 110, 103, 32, 2,
        10, 9, 1, 58, 3, 101, 100, 32, 2, 61, 34, 4, 32, 97, 116, 32, 3, 108, 121, 32,
        1, 44, 2, 61, 39, 5, 46, 99, 111, 109, 47, 7, 46, 32, 84, 104, 105, 115, 32, 5,
        32, 110, 111, 116, 32, 3, 101, 114, 32, 3, 97, 108, 32, 4, 102, 117, 108, 32, 4, 105,
        118, 101, 32, 5, 108, 101, 115, 115, 32, 4, 101, 115, 116, 32, 4, 105, 122, 101, 32, 2,
        (byte) 194, (byte) 160, 4, 111, 117, 115, 32, 5, 32, 116, 104, 101, 32, 2, 101, 32, 0,
    };
    private static final int[] PS_MAP = {
        0, 2, 5, 14, 19, 22, 24, 30, 35, 37,
        42, 45, 47, 50, 52, 58, 62, 69, 71, 78,
        85, 90, 92, 99, 104, 109, 114, 119, 122, 124,
        128, 131, 136, 140, 142, 145, 151, 159, 165, 169,
        173, 178, 183, 189, 194, 199, 202, 207, 213, 216,
    };
    // operations: 0 identity, 1..9 omit last n, 10 uppercase first,
    // 11 uppercase all, 12..20 omit first n
    private static final byte[] TRANSFORMS = {
        49, 0, 49, 49, 0, 0, 0, 0, 0, 49, 12, 49, 49, 10, 0, 49, 0, 47, 0, 0, 49,
        4, 0, 0, 49, 0, 3, 49, 10, 49, 49, 0, 6, 49, 13, 49, 49, 1, 49, 1, 0, 0,
        49, 0, 1, 0, 10, 0, 49, 0, 7, 49, 0, 9, 48, 0, 0, 49, 0, 8, 49, 0, 5,
        49, 0, 10, 49, 0, 11, 49, 3, 49, 49, 0, 13, 49, 0, 14, 49, 14, 49, 49, 2, 49,
        49, 0, 15, 49, 0, 16, 0, 10, 49, 49, 0, 12, 5, 0, 49, 0, 0, 1, 49, 15, 49,
        49, 0, 18, 49, 0, 17, 49, 0, 19, 49, 0, 20, 49, 16, 49, 49, 17, 49, 47, 0, 49,
        49, 4, 49, 49, 0, 22, 49, 11, 49, 49, 0, 23, 49, 0, 24, 49, 0, 25, 49, 7, 49,
        49, 1, 26, 49, 0, 27, 49, 0, 28, 0, 0, 12, 49, 0, 29, 49, 20, 49, 49, 18, 49,
        49, 6, 49, 49, 0, 21, 49, 10, 1, 49, 8, 49, 49, 0, 31, 49, 0, 32, 47, 0, 3,
        49, 5, 49, 49, 9, 49, 0, 10, 1, 49, 10, 8, 5, 0, 21, 49, 11, 0, 49, 10, 10,
        49, 0, 30, 0, 0, 5, 35, 0, 49, 47, 0, 2, 49, 10, 17, 49, 0, 36, 49, 0, 33,
        5, 0, 0, 49, 10, 21, 49, 10, 5, 49, 0, 37, 0, 0, 30, 49, 0, 38, 0, 11, 0,
        49, 0, 39, 0, 11, 49, 49, 0, 34, 49, 11, 8, 49, 10, 12, 0, 0, 21, 49, 0, 40,
        0, 10, 12, 49, 0, 41, 49, 0, 42, 49, 11, 17, 49, 0, 43, 0, 10, 5, 49, 11, 10,
        0, 0, 34, 49, 10, 33, 49, 0, 44, 49, 11, 5, 45, 0, 49, 0, 0, 33, 49, 10, 30,
        49, 11, 30, 49, 0, 46, 49, 11, 1, 49, 10, 34, 0, 10, 33, 0, 11, 30, 0, 11, 1,
        49, 11, 33, 49, 11, 21, 49, 11, 12, 0, 11, 5, 49, 11, 34, 0, 11, 12, 0, 10, 30,
        0, 11, 34, 0, 10, 34,
    };

    /**
     * Literal context lookup (RFC 7932 appendix C): 512 bytes per mode in
     * LSB6, MSB6, UTF8, SIGNED order; the first 256 index on the previous
     * byte, the next 256 on the byte before it.
     */
    private static final byte[] CONTEXT_LUT = loadResource("context.bin", 2048);

    private static byte[] loadResource(String name, int expected) {
        try (InputStream in = Brotli.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("brotli resource missing: " + name);
            }
            byte[] d = in.readAllBytes();
            if (d.length != expected) {
                throw new IllegalStateException("brotli resource corrupted: " + name);
            }
            return d;
        } catch (IOException e) {
            throw new IllegalStateException("cannot load brotli resource " + name, e);
        }
    }

    // -------------------------------------------------------------- decoder

    private static final class State {
        final byte[] in;
        final int end;      // one past the last input byte
        int bytePos;        // next byte index
        long bitBuf;
        int bitCount;
        final int maxOutput;

        byte[] out = new byte[1 << 16];
        int outPos;

        State(byte[] data, int off, int len, int maxOutput) {
            this.in = data;
            this.bytePos = off;
            this.end = off + len;
            this.maxOutput = maxOutput;
        }

        int virtualZeros; // zero bytes appended past the physical end

        // --- bit input, LSB first
        void fill() throws IOException {
            while (bitCount <= 56) {
                long b = 0;
                if (bytePos < end) {
                    b = in[bytePos++] & 0xffL;
                } else if (++virtualZeros > 8) {
                    // a valid stream never consumes this far past its end
                    throw new IOException("brotli: input exhausted");
                }
                bitBuf |= b << bitCount;
                bitCount += 8;
            }
        }

        int bits(int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            if (bitCount < n) {
                fill();
            }
            int v = (int) (bitBuf & ((1L << n) - 1));
            bitBuf >>>= n;
            bitCount -= n;
            return v;
        }

        int bit() throws IOException {
            return bits(1);
        }

        void alignByte() {
            int drop = bitCount & 7;
            bitBuf >>>= drop;
            bitCount -= drop;
        }

        /** Reads one aligned byte (after alignByte). */
        int rawByte() throws IOException {
            if (bitCount >= 8) {
                int v = (int) (bitBuf & 0xff);
                bitBuf >>>= 8;
                bitCount -= 8;
                return v;
            }
            if (bitCount != 0) {
                throw new IOException("brotli: unaligned raw read");
            }
            if (bytePos >= end) {
                throw new IOException("brotli: input exhausted");
            }
            return in[bytePos++] & 0xff;
        }

        void emit(int b) throws IOException {
            if (outPos >= maxOutput) {
                throw new IOException("brotli: output exceeds limit " + maxOutput);
            }
            if (outPos == out.length) {
                out = java.util.Arrays.copyOf(out, out.length * 2);
            }
            out[outPos++] = (byte) b;
        }

        // --- prefix code tables: symbol per padded bit-pattern
        static final class Code {
            int maxBits;
            short[] symbol;  // 1 << maxBits entries
            byte[] length;
        }

        int readSymbol(Code c) throws IOException {
            if (c.maxBits == 0) {
                return c.symbol[0];
            }
            if (bitCount < c.maxBits) {
                fill();
            }
            int idx = (int) (bitBuf & ((1L << c.maxBits) - 1));
            int len = c.length[idx];
            if (len == 0) {
                throw new IOException("brotli: invalid code");
            }
            bitBuf >>>= len;
            bitCount -= len;
            return c.symbol[idx];
        }

        /** Builds the padded lookup table from canonical code lengths. */
        static Code buildCode(int[] lengths, int alphabet) throws IOException {
            int maxBits = 0;
            int nonZero = 0;
            int single = -1;
            for (int i = 0; i < alphabet; i++) {
                if (lengths[i] > 0) {
                    maxBits = Math.max(maxBits, lengths[i]);
                    nonZero++;
                    single = i;
                }
            }
            Code c = new Code();
            if (nonZero == 1) {
                c.maxBits = 0;
                c.symbol = new short[] {(short) single};
                c.length = new byte[] {0};
                return c;
            }
            if (nonZero == 0) {
                throw new IOException("brotli: empty code");
            }
            c.maxBits = maxBits;
            int size = 1 << maxBits;
            c.symbol = new short[size];
            c.length = new byte[size];
            int code = 0;
            int filled = 0;
            for (int len = 1; len <= maxBits; len++) {
                for (int sym = 0; sym < alphabet; sym++) {
                    if (lengths[sym] != len) {
                        continue;
                    }
                    // canonical code value 'code' of 'len' bits, MSB-first;
                    // reverse for the LSB-first reader and fill the table
                    int rev = Integer.reverse(code) >>> (32 - len);
                    for (int idx = rev; idx < size; idx += 1 << len) {
                        c.symbol[idx] = (short) sym;
                        c.length[idx] = (byte) len;
                    }
                    filled += size >> len;
                    code++;
                }
                code <<= 1;
            }
            if (filled != size) {
                throw new IOException("brotli: code space mismatch");
            }
            return c;
        }

        /** Reads a prefix code description (RFC sections 3.4 and 3.5). */
        Code readCode(int alphabet) throws IOException {
            int[] lengths = new int[alphabet];
            int hskip = bits(2);
            if (hskip == 1) { // simple code
                int nsym = bits(2) + 1;
                int maxBits = 32 - Integer.numberOfLeadingZeros(alphabet - 1);
                int[] symbols = new int[nsym];
                for (int i = 0; i < nsym; i++) {
                    symbols[i] = bits(maxBits);
                    if (symbols[i] >= alphabet) {
                        throw new IOException("brotli: symbol out of range");
                    }
                    for (int j = 0; j < i; j++) {
                        if (symbols[j] == symbols[i]) {
                            throw new IOException("brotli: duplicate symbol");
                        }
                    }
                }
                switch (nsym) {
                    case 1 -> lengths[symbols[0]] = 15; // any; built as single
                    case 2 -> {
                        lengths[symbols[0]] = 1;
                        lengths[symbols[1]] = 1;
                    }
                    case 3 -> {
                        lengths[symbols[0]] = 1;
                        lengths[symbols[1]] = 2;
                        lengths[symbols[2]] = 2;
                    }
                    default -> {
                        if (bit() == 0) {
                            for (int i = 0; i < 4; i++) {
                                lengths[symbols[i]] = 2;
                            }
                        } else {
                            lengths[symbols[0]] = 1;
                            lengths[symbols[1]] = 2;
                            lengths[symbols[2]] = 3;
                            lengths[symbols[3]] = 3;
                        }
                    }
                }
                return buildCode(lengths, alphabet);
            }

            // complex code: first the code length code
            int[] clLengths = new int[18];
            int space = 32;
            int numCl = 0;
            for (int i = hskip; i < 18 && space > 0; i++) {
                int len = readClClSymbol();
                clLengths[CL_ORDER[i]] = len;
                if (len != 0) {
                    space -= 32 >> len;
                    numCl++;
                }
            }
            if (space < 0 || (space != 0 && numCl != 1)) {
                throw new IOException("brotli: invalid code length code");
            }
            Code clCode = buildCode(clLengths, 18);

            // then the symbol code lengths with repeats
            int symbol = 0;
            int prevLen = 8;
            int repeat = 0;
            int repeatLen = 0;
            int space2 = 32768;
            while (symbol < alphabet && space2 > 0) {
                int cl = readSymbol(clCode);
                if (cl < 16) {
                    repeat = 0;
                    lengths[symbol++] = cl;
                    if (cl != 0) {
                        prevLen = cl;
                        space2 -= 32768 >> cl;
                    }
                } else {
                    int extraBits = cl == 16 ? 2 : 3;
                    int newLen = cl == 16 ? prevLen : 0;
                    if (repeatLen != newLen) {
                        repeat = 0;
                        repeatLen = newLen;
                    }
                    int old = repeat;
                    if (repeat > 0) {
                        repeat -= 2;
                        repeat <<= extraBits;
                    }
                    repeat += bits(extraBits) + 3;
                    int delta = repeat - old;
                    if (symbol + delta > alphabet) {
                        throw new IOException("brotli: repeat overflows alphabet");
                    }
                    for (int i = 0; i < delta; i++) {
                        lengths[symbol++] = repeatLen;
                    }
                    if (repeatLen != 0) {
                        space2 -= delta * (32768 >> repeatLen);
                    }
                }
            }
            if (space2 < 0) {
                throw new IOException("brotli: over-subscribed code");
            }
            return buildCode(lengths, alphabet);
        }

        /** The fixed prefix code for code length code lengths. */
        int readClClSymbol() throws IOException {
            // LSB-first: 00->0, 01->4, 10->3, 110->2, 0111->1, 1111->5
            int b0 = bits(2);
            switch (b0) {
                case 0 -> {
                    return 0;
                }
                case 1 -> {
                    return 4;
                }
                case 2 -> {
                    return 3;
                }
                default -> {
                    if (bit() == 0) {
                        return 2;
                    }
                    return bit() == 0 ? 1 : 5;
                }
            }
        }

        /** Variable-length count in 1..256 (RFC section 9.2). */
        int readCount() throws IOException {
            if (bit() == 0) {
                return 1;
            }
            int n = bits(3);
            return (1 << n) + 1 + bits(n);
        }

        int[] readContextMap(int contexts, int ntrees) throws IOException {
            int[] map = new int[contexts];
            if (ntrees == 1) {
                return map;
            }
            int rleMax = bit() == 0 ? 0 : bits(4) + 1;
            Code code = readCode(ntrees + rleMax);
            int i = 0;
            while (i < contexts) {
                int sym = readSymbol(code);
                if (sym == 0) {
                    map[i++] = 0;
                } else if (sym <= rleMax) {
                    int reps = (1 << sym) + bits(sym);
                    if (i + reps > contexts) {
                        throw new IOException("brotli: context map RLE overflow");
                    }
                    for (int r = 0; r < reps; r++) {
                        map[i++] = 0;
                    }
                } else {
                    map[i++] = sym - rleMax;
                }
            }
            if (bit() == 1) { // inverse move-to-front
                int[] mtf = new int[256];
                for (int k = 0; k < 256; k++) {
                    mtf[k] = k;
                }
                for (int k = 0; k < contexts; k++) {
                    int idx = map[k];
                    int v = mtf[idx];
                    map[k] = v;
                    System.arraycopy(mtf, 0, mtf, 1, idx);
                    mtf[0] = v;
                }
            }
            return map;
        }

        int readBlockLength(Code code) throws IOException {
            int sym = readSymbol(code);
            return BLOCK_BASE[sym] + bits(BLOCK_EXTRA[sym]);
        }

        // --- main loop

        byte[] run() throws IOException {
            int wbits = readWindowBits();
            int wsize = (1 << wbits) - 16;

            boolean isLast = false;
            // ring[(ringIdx - k) & 3] is the k-th previous distance; the
            // initial "last" distance is 4, then 11, 15, 16 (RFC section 4)
            int[] distRing = {16, 15, 11, 4};
            ringIdx = 3;

            while (!isLast) {
                isLast = bit() == 1;
                if (isLast && bit() == 1) {
                    break; // last, empty
                }
                int mnibbles = bits(2);
                long mlen;
                if (mnibbles == 3) { // metadata block
                    if (bit() != 0) {
                        throw new IOException("brotli: reserved bit set");
                    }
                    int skipBytes = bits(2);
                    long skip = 0;
                    for (int i = 0; i < skipBytes; i++) {
                        skip |= (long) bits(8) << (8 * i);
                    }
                    if (skipBytes > 0) {
                        if (skip >> (8 * (skipBytes - 1)) == 0) {
                            throw new IOException("brotli: non-minimal skip length");
                        }
                        skip += 1;
                    }
                    alignByte();
                    for (long i = 0; i < skip; i++) {
                        rawByte();
                    }
                    continue;
                }
                int nib = 4 + mnibbles;
                mlen = 0;
                for (int i = 0; i < nib; i++) {
                    int v = bits(4);
                    if (i == nib - 1 && nib > 4 && v == 0) {
                        throw new IOException("brotli: non-minimal MLEN");
                    }
                    mlen |= (long) v << (4 * i);
                }
                mlen += 1;

                if (!isLast && bit() == 1) { // uncompressed
                    alignByte();
                    for (long i = 0; i < mlen; i++) {
                        emit(rawByte());
                    }
                    continue;
                }

                decodeCompressedMetaBlock(mlen, wsize, distRing);
            }
            return java.util.Arrays.copyOf(out, outPos);
        }

        int ringIdx;

        void decodeCompressedMetaBlock(long mlen, int wsize, int[] distRing)
                throws IOException {
            // block types and counts for the three categories
            int[] nbl = new int[3];
            Code[] blockTypeCode = new Code[3];
            Code[] blockCountCode = new Code[3];
            int[] blockLen = new int[3];
            int[] curType = new int[3];
            int[] prevType = new int[3];
            for (int cat = 0; cat < 3; cat++) {
                nbl[cat] = readCount();
                curType[cat] = 0;
                prevType[cat] = 1;
                if (nbl[cat] >= 2) {
                    blockTypeCode[cat] = readCode(nbl[cat] + 2);
                    blockCountCode[cat] = readCode(26);
                    blockLen[cat] = readBlockLength(blockCountCode[cat]);
                } else {
                    blockLen[cat] = Integer.MAX_VALUE;
                }
            }

            int npostfix = bits(2);
            int ndirect = bits(4) << npostfix;
            int[] contextMode = new int[nbl[0]];
            for (int i = 0; i < nbl[0]; i++) {
                contextMode[i] = bits(2);
            }
            int ntreesL = readCount();
            int[] literalMap = readContextMap(64 * nbl[0], ntreesL);
            int ntreesD = readCount();
            int[] distMap = readContextMap(4 * nbl[2], ntreesD);

            Code[] literalCode = new Code[ntreesL];
            for (int i = 0; i < ntreesL; i++) {
                literalCode[i] = readCode(256);
            }
            Code[] commandCode = new Code[nbl[1]];
            for (int i = 0; i < nbl[1]; i++) {
                commandCode[i] = readCode(704);
            }
            int distAlphabet = 16 + ndirect + (48 << npostfix);
            Code[] distanceCode = new Code[ntreesD];
            for (int i = 0; i < ntreesD; i++) {
                distanceCode[i] = readCode(distAlphabet);
            }

            long remaining = mlen;
            while (remaining > 0) {
                // command block switch
                if (blockLen[1] == 0) {
                    switchBlock(1, nbl, blockTypeCode, blockCountCode, curType, prevType, blockLen);
                }
                blockLen[1]--;
                int cmd = readSymbol(commandCode[curType[1]]);
                int cell = cmd >> 6;
                int insCode = CMD_INSERT_LUT[cell] + ((cmd >> 3) & 7);
                int copyCode = CMD_COPY_LUT[cell] + (cmd & 7);
                boolean implicitDist = cell < 2;
                int insertLen = INSERT_BASE[insCode] + bits(INSERT_EXTRA[insCode]);
                int copyLen = COPY_BASE[copyCode] + bits(COPY_EXTRA[copyCode]);

                for (int i = 0; i < insertLen; i++) {
                    if (blockLen[0] == 0) {
                        switchBlock(0, nbl, blockTypeCode, blockCountCode, curType, prevType,
                                blockLen);
                    }
                    blockLen[0]--;
                    int p1 = outPos > 0 ? out[outPos - 1] & 0xff : 0;
                    int p2 = outPos > 1 ? out[outPos - 2] & 0xff : 0;
                    int mode = contextMode[curType[0]];
                    int ctx = (CONTEXT_LUT[512 * mode + p1] & 0xff)
                            | (CONTEXT_LUT[512 * mode + 256 + p2] & 0xff);
                    int tree = literalMap[64 * curType[0] + ctx];
                    emit(readSymbol(literalCode[tree]));
                }
                remaining -= insertLen;
                if (remaining <= 0) {
                    break;
                }

                int distance;
                int dcode;
                if (implicitDist) {
                    dcode = 0;
                    distance = distRing[ringIdx & 3];
                } else {
                    if (blockLen[2] == 0) {
                        switchBlock(2, nbl, blockTypeCode, blockCountCode, curType, prevType,
                                blockLen);
                    }
                    blockLen[2]--;
                    int distCtx = 4 * curType[2] + Math.min(copyLen, 5) - 2;
                    dcode = readSymbol(distanceCode[distMap[distCtx]]);
                    if (dcode < 16) {
                        distance = distRing[(ringIdx - DIST_SHORT_INDEX[dcode]) & 3]
                                + DIST_SHORT_OFFSET[dcode];
                        if (distance <= 0) {
                            throw new IOException("brotli: bad short distance");
                        }
                    } else if (dcode < 16 + ndirect) {
                        distance = dcode - 15;
                    } else {
                        int base = dcode - ndirect - 16;
                        int ndistbits = 1 + (base >> (npostfix + 1));
                        int hcode = base >> npostfix;
                        int lcode = base & ((1 << npostfix) - 1);
                        int offset = ((2 + (hcode & 1)) << ndistbits) - 4;
                        distance = ((offset + bits(ndistbits)) << npostfix) + lcode + ndirect + 1;
                    }
                }

                int maxDistance = (int) Math.min(outPos, wsize);
                if (distance <= maxDistance) {
                    if (dcode != 0) {
                        ringIdx = (ringIdx + 1) & 3;
                        distRing[ringIdx & 3] = distance;
                    }
                    if (copyLen > remaining) {
                        throw new IOException("brotli: copy exceeds meta-block");
                    }
                    for (int i = 0; i < copyLen; i++) {
                        emit(out[outPos - distance] & 0xff);
                    }
                    remaining -= copyLen;
                } else {
                    // static dictionary reference
                    if (copyLen < 4 || copyLen > 24 || DICT_SIZE_BITS[copyLen] == 0) {
                        throw new IOException("brotli: bad dictionary length " + copyLen);
                    }
                    int wordId = distance - maxDistance - 1;
                    int shift = DICT_SIZE_BITS[copyLen];
                    int index = wordId & ((1 << shift) - 1);
                    int transformId = wordId >>> shift;
                    if (transformId >= 121) {
                        throw new IOException("brotli: bad transform " + transformId);
                    }
                    int wordOff = DICT_OFFSET[copyLen] + index * copyLen;
                    int written = writeTransformed(wordOff, copyLen, transformId);
                    remaining -= written;
                    if (remaining < 0) {
                        throw new IOException("brotli: dictionary word exceeds meta-block");
                    }
                }
            }
        }

        void switchBlock(int cat, int[] nbl, Code[] typeCode, Code[] countCode,
                int[] curType, int[] prevType, int[] blockLen) throws IOException {
            if (typeCode[cat] == null) {
                throw new IOException("brotli: block switch without code");
            }
            int sym = readSymbol(typeCode[cat]);
            int newType;
            if (sym == 0) {
                newType = prevType[cat];
            } else if (sym == 1) {
                newType = curType[cat] + 1;
                if (newType >= nbl[cat]) {
                    newType -= nbl[cat];
                }
            } else {
                newType = sym - 2;
            }
            prevType[cat] = curType[cat];
            curType[cat] = newType;
            blockLen[cat] = readBlockLength(countCode[cat]);
        }

        /** Applies transform {@code id} to the dictionary word and emits it. */
        int writeTransformed(int wordOff, int len, int id) throws IOException {
            int prefixId = TRANSFORMS[3 * id] & 0xff;
            int op = TRANSFORMS[3 * id + 1] & 0xff;
            int suffixId = TRANSFORMS[3 * id + 2] & 0xff;
            int written = 0;

            int po = PS_MAP[prefixId];
            int plen = PREFIX_SUFFIX[po] & 0xff;
            for (int i = 0; i < plen; i++) {
                emit(PREFIX_SUFFIX[po + 1 + i] & 0xff);
                written++;
            }

            int start = wordOff;
            int wlen = len;
            if (op >= 12) {
                int omit = op - 11;
                start += omit;
                wlen -= omit;
            } else if (op >= 1 && op <= 9) {
                wlen -= op;
            }
            if (wlen < 0) {
                wlen = 0;
            }
            int wordStart = outPos;
            for (int i = 0; i < wlen; i++) {
                emit(DICTIONARY[start + i] & 0xff);
                written++;
            }
            if (op == 10 || op == 11) { // uppercase first / all
                int i = wordStart;
                while (i < outPos) {
                    int b = out[i] & 0xff;
                    if (b < 192) {
                        if (b >= 'a' && b <= 'z') {
                            out[i] ^= 32;
                        }
                        i += 1;
                    } else if (b < 224) {
                        if (i + 1 < outPos) {
                            out[i + 1] ^= 32;
                        }
                        i += 2;
                    } else {
                        if (i + 2 < outPos) {
                            out[i + 2] ^= 5;
                        }
                        i += 3;
                    }
                    if (op == 10) {
                        break;
                    }
                }
            }

            int so = PS_MAP[suffixId];
            int slen = PREFIX_SUFFIX[so] & 0xff;
            for (int i = 0; i < slen; i++) {
                emit(PREFIX_SUFFIX[so + 1 + i] & 0xff);
                written++;
            }
            return written;
        }

        int readWindowBits() throws IOException {
            if (bit() == 0) {
                return 16;
            }
            int n = bits(3);
            if (n != 0) {
                return 17 + n;
            }
            n = bits(3);
            if (n == 0) {
                return 17;
            }
            if (n == 1) {
                throw new IOException("brotli: reserved window bits");
            }
            return 8 + n;
        }
    }

    // convenience for whole-stream decodes used in tests
    public static byte[] decodeStream(InputStream in, int maxOutput) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return decode(buf.toByteArray(), maxOutput);
    }
}
