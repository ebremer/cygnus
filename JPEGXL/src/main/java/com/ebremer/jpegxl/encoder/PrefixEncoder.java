package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.io.BitWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a length-limited canonical prefix code from a histogram and emits it
 * in the RFC 7932 (Brotli) representation used by JPEG XL.
 */
final class PrefixEncoder {

    private static final int MAX_SYMBOL_LENGTH = 15;
    private static final int MAX_CLC_LENGTH = 5;
    private static final int[] CLC_ZIGZAG = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    /** Fixed layer-0 code lengths for the code-length-code alphabet {0..5}. */
    private static final int[] L0_LENGTHS = {2, 4, 3, 2, 2, 4};
    private static final int[] L0_CODES = canonicalCodes(L0_LENGTHS);

    private final int alphabetSize;
    private final List<Integer> usedSymbols = new ArrayList<>();
    private final int[] lengths;
    private final int[] codes; // pre-reversed, ready for LSB-first writing

    PrefixEncoder(long[] histogram, int alphabetSize) {
        this.alphabetSize = alphabetSize;
        for (int i = 0; i < alphabetSize && i < histogram.length; i++) {
            if (histogram[i] > 0) {
                usedSymbols.add(i);
            }
        }
        if (usedSymbols.size() <= 1) {
            // zero-bit code: the single symbol costs nothing
            this.lengths = new int[alphabetSize];
            this.codes = new int[alphabetSize];
        } else {
            this.lengths = packageMerge(histogram, alphabetSize, MAX_SYMBOL_LENGTH);
            this.codes = canonicalCodes(lengths);
        }
    }

    void writeSymbol(BitWriter out, int symbol) {
        out.write(codes[symbol], lengths[symbol]);
    }

    /** Emits the prefix code description (RFC 7932 section 3, as profiled by JPEG XL). */
    void writeHeader(BitWriter out) {
        if (alphabetSize == 1) {
            return; // implicit zero-bit code, no description coded at all
        }
        int nsym = usedSymbols.size();
        if (nsym == 0) {
            throw new IllegalStateException("empty histogram");
        }
        if (nsym <= 4) {
            writeSimple(out);
        } else {
            writeComplex(out);
        }
    }

    private void writeSimple(BitWriter out) {
        List<Integer> order = new ArrayList<>(usedSymbols);
        // template lengths are assigned by position: shorter codes first
        order.sort((a, b) -> {
            int la = lengths[a];
            int lb = lengths[b];
            return la != lb ? Integer.compare(la, lb) : Integer.compare(a, b);
        });
        int nsym = order.size();
        out.write(1, 2); // hskip = 1: simple code
        out.write(nsym - 1, 2);
        int symBits = 32 - Integer.numberOfLeadingZeros(alphabetSize - 1);
        for (int sym : order) {
            out.write(sym, symBits);
        }
        if (nsym == 4) {
            // tree-select: true = lengths {1,2,3,3}, false = {2,2,2,2}
            out.writeBool(lengths[order.get(0)] == 1);
        }
    }

    private void writeComplex(BitWriter out) {
        int lastUsed = alphabetSize - 1;
        while (lengths[lastUsed] == 0) {
            lastUsed--;
        }
        long[] clcHist = new long[18];
        for (int i = 0; i <= lastUsed; i++) {
            clcHist[lengths[i]]++;
        }
        int distinct = 0;
        for (long c : clcHist) {
            if (c > 0) {
                distinct++;
            }
        }
        int[] clcLengths = distinct == 1
                ? singleLength(clcHist)
                : packageMerge(clcHist, 18, MAX_CLC_LENGTH);
        int[] clcCodes = canonicalCodes(clcLengths);

        out.write(0, 2); // hskip = 0
        int total = 0;
        for (int i = 0; i < CLC_ZIGZAG.length; i++) {
            int sym = CLC_ZIGZAG[i];
            int len = clcLengths[sym];
            out.write(L0_CODES[len], L0_LENGTHS[len]);
            if (len > 0) {
                total += 32 >> len;
                if (total == 32) {
                    break; // decoder stops here
                }
            }
        }
        if (distinct == 1) {
            // the decoder infers a zero-bit code-length code and fills every
            // symbol with the single length without consuming any bits
            return;
        }

        // symbol lengths, stopping once the code space is exactly filled
        int space = 0;
        for (int i = 0; i <= lastUsed && space < 32768; i++) {
            int len = lengths[i];
            out.write(clcCodes[len], clcLengths[len]);
            if (len > 0) {
                space += 32768 >> len;
            }
        }
    }

    private static int[] singleLength(long[] hist) {
        int[] lengths = new int[18];
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] > 0) {
                lengths[i] = 1; // arbitrary non-zero; decoder treats it as zero-bit
            }
        }
        return lengths;
    }

    /** Package-merge, yielding optimal code lengths bounded by {@code maxLen}. */
    private static int[] packageMerge(long[] histogram, int alphabetSize, int maxLen) {
        int[] lengths = new int[alphabetSize];
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < alphabetSize && i < histogram.length; i++) {
            if (histogram[i] > 0) {
                used.add(i);
            }
        }
        int n = used.size();
        if (n == 0) {
            return lengths;
        }
        if (n == 1) {
            lengths[used.get(0)] = 1;
            return lengths;
        }
        if ((1L << maxLen) < n) {
            throw new IllegalArgumentException("alphabet too large for length limit");
        }

        record Node(long weight, int[] leaves) {
        }
        List<Node> leaves = new ArrayList<>(n);
        for (int sym : used) {
            leaves.add(new Node(histogram[sym], new int[] {sym}));
        }
        leaves.sort((a, b) -> Long.compare(a.weight, b.weight));

        List<Node> prev = new ArrayList<>(leaves);
        for (int level = 1; level < maxLen; level++) {
            List<Node> packaged = new ArrayList<>(prev.size() / 2);
            for (int i = 0; i + 1 < prev.size(); i += 2) {
                Node a = prev.get(i);
                Node b = prev.get(i + 1);
                int[] merged = new int[a.leaves.length + b.leaves.length];
                System.arraycopy(a.leaves, 0, merged, 0, a.leaves.length);
                System.arraycopy(b.leaves, 0, merged, a.leaves.length, b.leaves.length);
                packaged.add(new Node(a.weight + b.weight, merged));
            }
            List<Node> next = new ArrayList<>(leaves.size() + packaged.size());
            int li = 0;
            int pi = 0;
            while (li < leaves.size() || pi < packaged.size()) {
                if (pi >= packaged.size()
                        || (li < leaves.size() && leaves.get(li).weight <= packaged.get(pi).weight)) {
                    next.add(leaves.get(li++));
                } else {
                    next.add(packaged.get(pi++));
                }
            }
            prev = next;
        }
        for (int i = 0; i < 2 * n - 2; i++) {
            for (int sym : prev.get(i).leaves) {
                lengths[sym]++;
            }
        }
        return lengths;
    }

    /** Canonical code values, already bit-reversed for LSB-first emission. */
    private static int[] canonicalCodes(int[] lengths) {
        int maxLen = 0;
        for (int len : lengths) {
            maxLen = Math.max(maxLen, len);
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
        int[] codes = new int[lengths.length];
        for (int sym = 0; sym < lengths.length; sym++) {
            int len = lengths[sym];
            if (len > 0) {
                codes[sym] = Integer.reverse(nextCode[len]++) >>> (32 - len);
            }
        }
        return codes;
    }
}
