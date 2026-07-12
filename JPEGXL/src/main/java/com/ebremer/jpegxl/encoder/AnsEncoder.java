package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.entropy.AnsDecoder;
import com.ebremer.jpegxl.entropy.PrefixCode;
import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/**
 * Encode-side helpers for rANS streams: histogram quantisation to the
 * {@code 1 << 12} table sum and the annex-C distribution serialisation.
 * The alias-table slot mapping itself comes from
 * {@link AnsDecoder#reverseMap}, so encoder and decoder share the normative
 * construction.
 */
final class AnsEncoder {

    private AnsEncoder() {
    }

    // The fixed log-count prefix code, as (LSB-first word, length) per
    // symbol 0..13, derived once from the decoder's own table.
    private static int[] lcWord;
    private static int[] lcLen;

    private static synchronized void initLogCount() {
        if (lcWord != null) {
            return;
        }
        int[] word = new int[14];
        int[] count = new int[14];
        int[] first = new int[14];
        java.util.Arrays.fill(first, -1);
        try {
            for (int p = 0; p < 128; p++) {
                byte[] buf = {(byte) p, 0};
                int sym = PrefixCode.logCount().decode(new Bits(buf));
                count[sym]++;
                if (first[sym] < 0) {
                    first[sym] = p;
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        int[] len = new int[14];
        for (int s = 0; s < 14; s++) {
            if (count[s] == 0) {
                continue;
            }
            if (Integer.bitCount(count[s]) != 1) {
                throw new AssertionError("log-count code is not prefix-free");
            }
            len[s] = 7 - Integer.numberOfTrailingZeros(count[s]);
            word[s] = first[s] & ((1 << len[s]) - 1);
        }
        lcLen = len;
        lcWord = word;
    }

    private static void writeLogCount(BitWriter out, int symbol) {
        out.write(lcWord[symbol], lcLen[symbol]);
    }

    static void writeU8dist(BitWriter out, int v) {
        if (v == 0) {
            out.writeBool(false);
            return;
        }
        out.writeBool(true);
        int n = 31 - Integer.numberOfLeadingZeros(v);
        out.write(n, 3);
        out.write(v - (1 << n), n);
    }

    /**
     * Quantises {@code counts[0..n)} to frequencies summing exactly
     * {@code 1 << 12}, keeping every present symbol nonzero. The returned
     * array has {@code tableSize} entries. An all-zero histogram becomes a
     * degenerate single-symbol distribution (the cluster is never read).
     */
    static int[] quantize(long[] counts, int n, int tableSize) {
        int[] freq = new int[tableSize];
        long total = 0;
        int nz = 0;
        int last = -1;
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0) {
                total += counts[i];
                nz++;
                last = i;
            }
        }
        if (nz == 0) {
            freq[0] = AnsDecoder.DIST_SUM;
            return freq;
        }
        if (nz == 1) {
            freq[last] = AnsDecoder.DIST_SUM;
            return freq;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if (counts[i] > 0) {
                int f = (int) (counts[i] * AnsDecoder.DIST_SUM / total);
                if (f < 1) {
                    f = 1;
                }
                freq[i] = f;
                sum += f;
            }
        }
        // settle the rounding difference on the largest entries
        while (sum != AnsDecoder.DIST_SUM) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (freq[i] > 0 && (best < 0 || freq[i] > freq[best])) {
                    best = i;
                }
            }
            int delta = AnsDecoder.DIST_SUM - sum;
            if (delta < 1 - freq[best]) {
                delta = 1 - freq[best];
            }
            freq[best] += delta;
            sum += delta;
        }
        return freq;
    }

    /** Estimated data bits of {@code counts} coded against {@code freq}. */
    static double dataBits(long[] counts, int[] freq) {
        double bits = 0;
        double log2 = Math.log(2);
        for (int i = 0; i < counts.length && i < freq.length; i++) {
            if (counts[i] > 0) {
                bits += counts[i] * Math.log((double) AnsDecoder.DIST_SUM / freq[i]) / log2;
            }
        }
        return bits;
    }

    /** Writes one distribution in the annex-C histogram encoding. */
    static void writeDistribution(BitWriter out, int[] freq, int tableSize) {
        initLogCount();
        int nz = 0;
        int v1 = -1;
        int v2 = -1;
        int lastNonzero = -1;
        for (int i = 0; i < tableSize; i++) {
            if (freq[i] > 0) {
                nz++;
                if (v1 < 0) {
                    v1 = i;
                } else if (v2 < 0) {
                    v2 = i;
                }
                lastNonzero = i;
            }
        }
        if (nz == 1) {
            out.write(1, 2); // single entry
            writeU8dist(out, v1);
            return;
        }
        if (nz == 2) {
            out.write(3, 2); // two entries
            writeU8dist(out, v1);
            writeU8dist(out, v2);
            out.write(freq[v1], AnsDecoder.DIST_BITS);
            return;
        }
        out.write(0, 2); // general distribution
        // shift = 13: full-precision counts (len bits 1,1,1 then u(3) = 6)
        out.write(1, 1);
        out.write(1, 1);
        out.write(1, 1);
        out.write(6, 3);
        int alphaSize = Math.max(lastNonzero + 1, 3);
        writeU8dist(out, alphaSize - 3);

        int[] code = new int[alphaSize];
        int maxCode = 0;
        for (int i = 0; i < alphaSize; i++) {
            code[i] = freq[i] == 0 ? 0
                    : freq[i] == 1 ? 1
                    : 32 - Integer.numberOfLeadingZeros(freq[i]); // floorLog2 + 1
            maxCode = Math.max(maxCode, code[i]);
        }
        int omitPos = -1;
        for (int i = 0; i < alphaSize; i++) {
            if (code[i] == maxCode) {
                omitPos = i;
                break;
            }
        }
        // pass 1: log-count codes with RLE runs of the previous value
        boolean[] rleCovered = new boolean[alphaSize];
        int i = 0;
        while (i < alphaSize) {
            if (i > 0 && i != omitPos && i - 1 != omitPos && freq[i] == freq[i - 1]) {
                int r = 0;
                while (i + r < alphaSize && i + r != omitPos && freq[i + r] == freq[i - 1]) {
                    r++;
                }
                if (r >= 4) {
                    int rep = Math.min(r, 255 + 4);
                    writeLogCount(out, 13);
                    writeU8dist(out, rep - 4);
                    for (int k = 0; k < rep; k++) {
                        rleCovered[i + k] = true;
                    }
                    i += rep;
                    continue;
                }
            }
            writeLogCount(out, code[i]);
            i++;
        }
        // pass 2: the count bits (shift 13 makes bitCount == code - 1)
        for (i = 0; i < alphaSize; i++) {
            if (rleCovered[i] || i == omitPos || code[i] < 2) {
                continue;
            }
            int c = code[i] - 1;
            out.write(freq[i] - (1 << c), c);
        }
    }
}
