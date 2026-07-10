package com.ebremer.jpegxl.entropy;

import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/**
 * rANS decoder with the alias mapping mandated by 18181-1 annex C. All
 * distributions sum to {@code 1 << 12}; the alias table construction is
 * normative because it defines how slots map to symbols.
 */
public final class AnsDecoder {

    public static final int DIST_BITS = 12;
    public static final int DIST_SUM = 1 << DIST_BITS;
    public static final int INIT_STATE = 0x130000;

    final int logBucketSize;
    final int[] freq;          // D[i], summing to DIST_SUM
    private final int[] cutoff;
    private final int[] offsetOrNext;
    private final int[] symbol;

    private AnsDecoder(int logAlphaSize, int[] freq) throws IOException {
        this.logBucketSize = DIST_BITS - logAlphaSize;
        this.freq = freq;
        int tableSize = 1 << logAlphaSize;
        int bucketSize = 1 << logBucketSize;
        cutoff = new int[tableSize];
        offsetOrNext = new int[tableSize];
        symbol = new int[tableSize];

        int nonZero = -1;
        int numNonZero = 0;
        for (int i = 0; i < tableSize; i++) {
            if (freq[i] != 0) {
                nonZero = i;
                numNonZero++;
            }
        }
        if (numNonZero == 1) {
            for (int j = 0; j < tableSize; j++) {
                symbol[j] = nonZero;
                offsetOrNext[j] = j << logBucketSize;
                cutoff[j] = 0;
            }
            return;
        }

        // underfull/overfull stacks as implicit linked lists
        int u = -1;
        int o = -1;
        for (int i = 0; i < tableSize; i++) {
            int c = freq[i];
            cutoff[i] = c;
            if (c > bucketSize) {
                offsetOrNext[i] = o;
                o = i;
            } else if (c < bucketSize) {
                offsetOrNext[i] = u;
                u = i;
            } else {
                symbol[i] = i;
                offsetOrNext[i] = 0;
            }
        }
        while (o >= 0) {
            if (u < 0) {
                throw new IOException("invalid ANS distribution");
            }
            int by = bucketSize - cutoff[u];
            int nextU = offsetOrNext[u];
            cutoff[o] -= by;
            symbol[u] = o;
            offsetOrNext[u] = cutoff[o] - cutoff[u];
            u = nextU;
            if (cutoff[o] < bucketSize) {
                int nextO = offsetOrNext[o];
                offsetOrNext[o] = u;
                u = o;
                o = nextO;
            } else if (cutoff[o] == bucketSize) {
                int nextO = offsetOrNext[o];
                symbol[o] = o;
                offsetOrNext[o] = 0;
                o = nextO;
            }
        }
    }

    /**
     * Decodes one symbol; {@code state[0]} carries the 32-bit ANS state across
     * calls (0 = not yet initialized).
     */
    public int decode(Bits in, int[] state) throws IOException {
        int s = state[0];
        if (s == 0) {
            s = in.u(16);
            s |= in.u(16) << 16;
        }
        int index = s & 0xfff;
        int i = index >>> logBucketSize;
        int pos = index & ((1 << logBucketSize) - 1);
        boolean direct = pos < cutoff[i];
        int sym = direct ? i : symbol[i];
        int offset = direct ? 0 : offsetOrNext[i];
        s = freq[sym] * (s >>> 12) + offset + pos;
        if ((s & 0xffff0000) == 0) {
            s = (s << 16) | in.u(16);
        }
        state[0] = s;
        return sym;
    }

    /** Reads a distribution over {@code 1 << logAlphaSize} entries. */
    public static AnsDecoder readDistribution(Bits in, int logAlphaSize) throws IOException {
        int tableSize = 1 << logAlphaSize;
        int[] freq = new int[tableSize];
        int kind = in.u(2); // two Bool() reads combined; bit0 is the first bool
        switch (kind) {
            case 1 -> { // single entry
                int v = in.u8dist();
                if (v >= tableSize) {
                    throw new IOException("ANS symbol out of range");
                }
                freq[v] = DIST_SUM;
            }
            case 3 -> { // two entries
                int v1 = in.u8dist();
                int v2 = in.u8dist();
                if (v1 == v2 || v1 >= tableSize || v2 >= tableSize) {
                    throw new IOException("bad two-symbol ANS distribution");
                }
                freq[v1] = in.u(DIST_BITS);
                freq[v2] = DIST_SUM - freq[v1];
            }
            case 2 -> { // evenly distributed
                int alphaSize = in.u8dist() + 1;
                if (alphaSize > tableSize) {
                    throw new IOException("ANS alphabet too large");
                }
                int d = DIST_SUM / alphaSize;
                int bias = DIST_SUM % alphaSize;
                for (int i = 0; i < alphaSize; i++) {
                    freq[i] = d + (i < bias ? 1 : 0);
                }
            }
            case 0 -> readGeneral(in, tableSize, freq);
            default -> throw new AssertionError();
        }
        return new AnsDecoder(logAlphaSize, freq);
    }

    private static void readGeneral(Bits in, int tableSize, int[] freq) throws IOException {
        int len = 0;
        while (len < 3 && in.u(1) != 0) {
            len++;
        }
        int shift = in.u(len) + (1 << len) - 1;
        if (shift > 13) {
            throw new IOException("bad ANS shift " + shift);
        }
        int alphaSize = in.u8dist() + 3;

        // log counts with RLE (code 13 = repeat previous count)
        int[] codes = new int[alphaSize + 1];
        int nCodes = 0;
        int omitLog = -1;
        for (int i = 0; i < alphaSize; ) {
            int code = PrefixCode.LOG_COUNT.decode(in);
            if (code < 13) {
                i++;
                codes[nCodes++] = code;
                omitLog = Math.max(omitLog, code);
            } else {
                int rep = in.u8dist() + 4;
                i += rep;
                codes[nCodes++] = -rep;
            }
        }
        if (omitLog < 0) {
            throw new IOException("ANS distribution has no non-RLE code");
        }

        int omitPos = -1;
        int total = 0;
        int n = 0;
        for (int i = 0; i < nCodes && n < tableSize; i++) {
            int code = codes[i];
            if (code < 0) { // repeat previous count
                int prev = n > 0 ? freq[n - 1] : 0;
                if (prev < 0) {
                    throw new IOException("ANS RLE after omitted count");
                }
                int rep = Math.min(-code, tableSize - n);
                total += prev * rep;
                while (rep-- > 0) {
                    freq[n++] = prev;
                }
            } else if (code == omitLog) {
                omitPos = n;
                omitLog = -1;
                freq[n++] = -1;
            } else if (code < 2) {
                total += code;
                freq[n++] = code;
            } else {
                int c = code - 1;
                int bitCount = Math.min(Math.max(0, shift - ((DIST_BITS - c) >> 1)), c);
                int v = (1 << c) + (in.u(bitCount) << (c - bitCount));
                total += v;
                freq[n++] = v;
            }
        }
        if (omitPos < 0) {
            throw new IOException("ANS distribution is missing the omitted count");
        }
        if (total > DIST_SUM) {
            throw new IOException("over-subscribed ANS distribution");
        }
        freq[omitPos] = DIST_SUM - total;
    }
}
