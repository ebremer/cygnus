package com.ebremer.cygnus.jpegxl.entropy;

import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/**
 * Brotli-style (RFC 7932 section 3) prefix code decoder. The lookup table
 * format follows j40: {@code table[i]} for the first {@code fastLen} bits is
 * either a direct entry {@code (symbol << 16) | length}, or the negated index
 * of the first overflow entry. Overflow entries additionally carry the
 * remaining codeword bits in bits 4..15.
 */
public final class PrefixCode {

    final int fastLen;
    final int maxLen;
    final int[] table;

    private PrefixCode(int fastLen, int maxLen, int[] table) {
        this.fastLen = fastLen;
        this.maxLen = maxLen;
        this.table = table;
    }

    public int decode(Bits in) throws IOException {
        int entry = table[in.peekZeroPad(fastLen) & ((1 << fastLen) - 1)];
        if (entry < 0 && fastLen < maxLen) {
            in.consume(fastLen);
            int idx = -entry;
            while (true) {
                entry = table[idx++];
                int code = (entry >> 4) & 0xfff;
                int codeLen = entry & 15;
                if (code == (in.peekZeroPad(codeLen) & ((1 << codeLen) - 1))) {
                    in.consume(codeLen);
                    return entry >> 16;
                }
            }
        }
        in.consume(entry & 15);
        return entry >> 16;
    }

    /** Builds a full-lookup decoder from canonical code lengths (0 = unused symbol). */
    public static PrefixCode fromLengths(int[] lengths) {
        int maxLen = 0;
        for (int len : lengths) {
            maxLen = Math.max(maxLen, len);
        }
        if (maxLen == 0) {
            // degenerate: a single symbol coded with zero bits
            int sym = 0;
            return new PrefixCode(0, 0, new int[] {sym << 16});
        }
        int[] countPerLen = new int[maxLen + 1];
        for (int len : lengths) {
            if (len > 0) {
                countPerLen[len]++;
            }
        }
        int[] nextCode = new int[maxLen + 2];
        int code = 0;
        for (int len = 1; len <= maxLen; len++) {
            nextCode[len] = code;
            code = (code + countPerLen[len]) << 1;
        }
        int[] table = new int[1 << maxLen];
        for (int sym = 0; sym < lengths.length; sym++) {
            int len = lengths[sym];
            if (len == 0) {
                continue;
            }
            int canonical = nextCode[len]++;
            int reversed = Integer.reverse(canonical) >>> (32 - len);
            for (int i = reversed; i < table.length; i += 1 << len) {
                table[i] = (sym << 16) | len;
            }
        }
        return new PrefixCode(maxLen, maxLen, table);
    }

    /** The single-symbol degenerate code (zero bits per symbol). */
    public static PrefixCode singleSymbol(int symbol) {
        return new PrefixCode(0, 0, new int[] {symbol << 16});
    }

    /**
     * The fixed prefix code used to encode ANS histogram log-counts
     * (symbols 0..12 are exponents, 13 is the RLE marker).
     */
    static final PrefixCode LOG_COUNT = new PrefixCode(4, 7, new int[] {
        0xa0003, -16, 0x70003, 0x30004, 0x60003, 0x80003, 0x90003, 0x50004,
        0xa0003, 0x40004, 0x70003, 0x10004, 0x60003, 0x80003, 0x90003, 0x20004,
        0x00011, 0xb0022, 0xc0003, 0xd0043,
    });

    /** Encoder access to the fixed ANS log-count code. */
    public static PrefixCode logCount() {
        return LOG_COUNT;
    }

    /** The fixed 4-bit code for reading code-length-code lengths (RFC 7932 3.5). */
    private static final PrefixCode CODE_LENGTH_CODE =
            fromLengths(new int[] {2, 4, 3, 2, 2, 4});

    private static final int[] CLC_ZIGZAG = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * Reads a prefix code tree over {@code alphabetSize} symbols, following
     * RFC 7932 section 3 as profiled by JPEG XL.
     */
    public static PrefixCode read(Bits in, int alphabetSize) throws IOException {
        if (alphabetSize < 1 || alphabetSize > (1 << 15)) {
            throw new IOException("bad prefix code alphabet size " + alphabetSize);
        }
        if (alphabetSize == 1) {
            return singleSymbol(0);
        }

        int hskip = in.u(2);
        if (hskip == 1) {
            return readSimple(in, alphabetSize);
        }

        // layer 1: lengths for the 18-symbol code-length alphabet
        int[] clcLengths = new int[18];
        int total = 0;
        int numCodes = 0;
        int i = hskip;
        for (; i < 18 && total < 32; i++) {
            int len = CODE_LENGTH_CODE.decode(in);
            clcLengths[CLC_ZIGZAG[i]] = len;
            if (len != 0) {
                total += 32 >> len;
                numCodes++;
            }
        }
        PrefixCode layer1;
        if (numCodes == 1) {
            int sym = 0;
            while (clcLengths[sym] == 0) {
                sym++;
            }
            layer1 = singleSymbol(sym);
        } else if (total == 32) {
            layer1 = fromLengths(clcLengths);
        } else {
            throw new IOException("under/over-subscribed code length code");
        }

        // layer 2: symbol lengths with RLE codes 16 (repeat last) and 17 (repeat zero)
        int[] lengths = new int[alphabetSize];
        int prev = 8;
        int prevRep = 0; // >0: consecutive 16s so far, <0: consecutive 17s (negated count)
        int space = 0;
        int pos = 0;
        int nonzero = 0;
        while (pos < alphabetSize && space < 32768) {
            int code = layer1.decode(in);
            if (code < 16) {
                lengths[pos++] = code;
                if (code != 0) {
                    space += 32768 >> code;
                    prev = code;
                    nonzero++;
                }
                prevRep = 0;
            } else if (code == 16) {
                if (prevRep < 0) {
                    prevRep = 0;
                }
                int rep = (prevRep > 0 ? 4 * prevRep - 5 : 3) + in.u(2);
                if (pos + (rep - prevRep) > alphabetSize) {
                    throw new IOException("prefix code repeat overruns alphabet");
                }
                space += (32768 * (rep - prevRep)) >> prev;
                nonzero += rep - prevRep;
                while (prevRep < rep) {
                    lengths[pos++] = prev;
                    prevRep++;
                }
            } else { // 17
                if (prevRep > 0) {
                    prevRep = 0;
                }
                int rep = (prevRep < 0 ? 8 * prevRep + 13 : -3) - in.u(3);
                if (pos + (prevRep - rep) > alphabetSize) {
                    throw new IOException("prefix code zero-repeat overruns alphabet");
                }
                while (prevRep > rep) {
                    lengths[pos++] = 0;
                    prevRep--;
                }
            }
        }
        if (nonzero == 1) {
            int sym = 0;
            while (lengths[sym] == 0) {
                sym++;
            }
            return singleSymbol(sym);
        }
        if (space != 32768) {
            throw new IOException("under/over-subscribed prefix code");
        }
        return fromLengths(lengths);
    }

    private static PrefixCode readSimple(Bits in, int alphabetSize) throws IOException {
        int nsym = in.u(2) + 1;
        int[] syms = new int[4];
        for (int i = 0; i < nsym; i++) {
            syms[i] = in.atMost(alphabetSize - 1);
            for (int j = 0; j < i; j++) {
                if (syms[i] == syms[j]) {
                    throw new IOException("duplicate symbol in simple prefix code");
                }
            }
        }
        int[] lengths = new int[alphabetSize];
        switch (nsym) {
            case 1 -> {
                return singleSymbol(syms[0]);
            }
            case 2 -> {
                lengths[syms[0]] = 1;
                lengths[syms[1]] = 1;
            }
            case 3 -> {
                lengths[syms[0]] = 1;
                lengths[syms[1]] = 2;
                lengths[syms[2]] = 2;
            }
            default -> {
                if (in.bool()) { // tree-select: lengths 1,2,3,3
                    lengths[syms[0]] = 1;
                    lengths[syms[1]] = 2;
                    lengths[syms[2]] = 3;
                    lengths[syms[3]] = 3;
                } else {
                    for (int i = 0; i < 4; i++) {
                        lengths[syms[i]] = 2;
                    }
                }
            }
        }
        return fromLengths(lengths);
    }
}
