package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.entropy.HybridUintConfig;
import com.ebremer.cygnus.jpegxl.io.BitWriter;

/**
 * Entropy-coded stream writer using prefix codes, with optional LZ77 copies.
 * Values are first counted per context, contexts with similar statistics are
 * merged into at most eight clusters (the limit of the simple cluster-map
 * encoding), and the values are then emitted in a second pass.
 */
final class EntropyEncoder {

    static final int MIN_SYMBOL = 224;
    static final int MIN_LENGTH = 3;
    private static final int MAX_CLUSTERS = 8;
    private static final int ALPHABET = MIN_SYMBOL + 40;

    private final int numDist;      // contexts including the LZ77 distance context
    private final boolean lz77;
    private final boolean perContext;
    private final boolean allowAns;
    private final HybridUintConfig config = new HybridUintConfig(4, 1, 0);
    private final HybridUintConfig lenConfig = new HybridUintConfig(0, 0, 0);
    private final long[][] histograms; // per context (or one shared histogram)
    private final int[] clusterMap;
    private PrefixEncoder[] codes;
    private final int[] scratch = new int[1];

    // rANS state (populated by writeSpec when ANS wins the cost comparison)
    private boolean useAns;
    private int[][] ansFreq;    // per cluster, table-size entries summing 4096
    private int[][] ansStart;   // per cluster, cumulative starts
    private int[][] ansRev;     // per cluster, (start[sym] + r) -> 12-bit index
    private int[] secTok = new int[1024]; // (cluster << 16) | symbol
    private int[] secVal = new int[1024]; // hybrid-uint extra bits value
    private byte[] secBits = new byte[1024];
    private int secN;

    /**
     * @param numCtx number of value contexts
     * @param perContextClusters when true, contexts are counted separately and
     *        merged into at most eight clusters; otherwise all share one code
     * @param lz77 enable LZ77 copies (adds the distance context)
     * @param allowAns permit rANS symbol coding when it beats prefix codes;
     *        every section must then end with {@link #finishSection}
     */
    EntropyEncoder(int numCtx, boolean perContextClusters, boolean lz77, boolean allowAns) {
        this.lz77 = lz77;
        this.perContext = perContextClusters;
        this.allowAns = allowAns;
        this.numDist = numCtx + (lz77 ? 1 : 0);
        this.histograms = new long[perContext ? numDist : 1][ALPHABET];
        this.clusterMap = new int[numDist];
    }

    EntropyEncoder(int numCtx, boolean perContextClusters, boolean lz77) {
        this(numCtx, perContextClusters, lz77, false);
    }

    EntropyEncoder(int numCtx, boolean perContextClusters) {
        this(numCtx, perContextClusters, false, false);
    }

    /** Pass 1: account for {@code value} in context {@code ctx}. */
    void count(int ctx, int value) {
        histograms[perContext ? ctx : 0][(int) config.encode(value, scratch)]++;
    }

    /** Pass 1: account for a copy of {@code len} values at distance value {@code distValue}. */
    void countCopy(int ctx, int len, int distValue) {
        histograms[perContext ? ctx : 0]
                [MIN_SYMBOL + (int) lenConfig.encode(len - MIN_LENGTH, scratch)]++;
        histograms[perContext ? numDist - 1 : 0][(int) config.encode(distValue, scratch)]++;
    }

    /**
     * Distance token values for real distances at this {@code distMult}: the
     * 120 special 2-D entries where applicable, else the linear escape
     * {@code d + 119} (the decoder maps values >= 120 to {@code v - 119}).
     */
    static java.util.HashMap<Integer, Integer> specialDistanceValues(int distMult) {
        var map = new java.util.HashMap<Integer, Integer>(160);
        for (int v = 0; v < 120; v++) {
            int sp = com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder.SPECIAL_DISTANCES[v];
            int d = Math.max(1, ((sp >> 4) - 7) + distMult * (sp & 7));
            map.putIfAbsent(d, v);
        }
        return map;
    }

    private double[] ctxTotals; // per-histogram totals, prepared for cost queries

    /** Prepares {@link #tokenCostBits}/{@link #copyCostBits} after counting. */
    void prepareCosts() {
        ctxTotals = new double[histograms.length];
        for (int i = 0; i < histograms.length; i++) {
            long n = 0;
            for (long c : histograms[i]) {
                n += c;
            }
            ctxTotals[i] = n;
        }
    }

    private double symbolCostBits(int ctx, int symbol) {
        int h = perContext ? ctx : 0;
        double total = ctxTotals[h];
        long cnt = histograms[h][symbol];
        double p = cnt > 0 ? cnt / total : 0.5 / (total + 1);
        return -Math.log(p) / Math.log(2);
    }

    /** Cost in bits of emitting {@code value} in {@code ctx} under the counted stats. */
    double tokenCostBits(int ctx, int value) {
        int[] tmp = new int[1];
        long enc = config.encode(value, tmp);
        return symbolCostBits(ctx, (int) enc) + (int) (enc >>> 32);
    }

    /** Cost in bits of an LZ77 copy of {@code len} values in {@code ctx}. */
    double copyCostBits(int ctx, int len, int distValue) {
        int[] tmp = new int[1];
        long enc = lenConfig.encode(len - MIN_LENGTH, tmp);
        double bits = symbolCostBits(ctx, MIN_SYMBOL + (int) enc) + (int) (enc >>> 32);
        long dist = config.encode(distValue, tmp);
        return bits + symbolCostBits(numDist - 1, (int) dist) + (int) (dist >>> 32);
    }

    /** Estimated cost in bits of the counted stream (before clustering). */
    long estimatedBits() {
        long bits = 0;
        for (long[] h : histograms) {
            long n = 0;
            for (long c : h) {
                n += c;
            }
            if (n == 0) {
                continue;
            }
            for (long c : h) {
                if (c > 0) {
                    bits += Math.round(c * (Math.log((double) n / c) / Math.log(2)));
                }
            }
        }
        return bits;
    }

    /** Writes the code spec (LZ77 params, cluster map, configs, prefix trees). */
    void writeSpec(BitWriter out) {
        cluster();
        out.writeBool(lz77);
        if (lz77) {
            out.write(0, 2); // min_symbol selector 0 -> 224
            out.write(0, 2); // min_length selector 0 -> 3
            // length config for log_alpha_size 8: split_exponent = 0
            out.write(0, 4);
        }
        int numClusters = 0;
        for (int c : clusterMap) {
            numClusters = Math.max(numClusters, c + 1);
        }
        if (numDist > 1) {
            out.writeBool(true); // simple cluster map
            int nbits = numClusters > 1 ? 32 - Integer.numberOfLeadingZeros(numClusters - 1) : 0;
            out.write(nbits, 2);
            for (int i = 0; i < numDist; i++) {
                out.write(clusterMap[i], nbits);
            }
        }
        long[][] clustered = new long[numClusters][ALPHABET];
        int[] maxToken = new int[numClusters];
        for (int i = 0; i < histograms.length; i++) {
            int cl = perContext ? clusterMap[i] : 0;
            for (int t = 0; t < ALPHABET; t++) {
                if (histograms[i][t] > 0) {
                    clustered[cl][t] += histograms[i][t];
                    maxToken[cl] = Math.max(maxToken[cl], t);
                }
            }
        }

        // choose the symbol coder: exact header costs plus entropy estimates
        PrefixEncoder[] prefixCodes = new PrefixEncoder[numClusters];
        int logAlpha = 5;
        for (int i = 0; i < numClusters; i++) {
            prefixCodes[i] = new PrefixEncoder(clustered[i], maxToken[i] + 1);
            logAlpha = Math.max(logAlpha, ceilLog2(maxToken[i] + 1));
        }
        int[][] freqs = null;
        if (allowAns && logAlpha <= 8) {
            double prefixTotal = 0;
            double ansTotal = 2; // log_alpha_size bits
            freqs = new int[numClusters][];
            for (int i = 0; i < numClusters; i++) {
                BitWriter pw = new BitWriter();
                int count = maxToken[i] + 1;
                if (count > 1) {
                    pw.write(0, 5); // alphabet-size field, roughly
                }
                prefixCodes[i].writeHeader(pw);
                prefixTotal += pw.bitLength() + prefixCodes[i].costBits(clustered[i]);
                freqs[i] = AnsEncoder.quantize(clustered[i], count, 1 << logAlpha);
                BitWriter aw = new BitWriter();
                AnsEncoder.writeDistribution(aw, freqs[i], 1 << logAlpha);
                ansTotal += aw.bitLength() + AnsEncoder.dataBits(clustered[i], freqs[i]);
            }
            useAns = ansTotal < prefixTotal;
        }

        out.writeBool(!useAns); // use_prefix_code
        if (useAns) {
            out.write(logAlpha - 5, 2);
            for (int i = 0; i < numClusters; i++) {
                writeConfig(out, config, logAlpha);
            }
            ansFreq = freqs;
            ansStart = new int[numClusters][];
            ansRev = new int[numClusters][];
            for (int i = 0; i < numClusters; i++) {
                AnsEncoder.writeDistribution(out, freqs[i], 1 << logAlpha);
                int[] start = new int[freqs[i].length + 1];
                for (int s = 0; s < freqs[i].length; s++) {
                    start[s + 1] = start[s] + freqs[i][s];
                }
                ansStart[i] = start;
                try {
                    ansRev[i] = com.ebremer.cygnus.jpegxl.entropy.AnsDecoder.reverseMap(logAlpha, freqs[i]);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("invalid quantized distribution", e);
                }
            }
            return;
        }
        for (int i = 0; i < numClusters; i++) {
            writeConfig(out, config, 15);
        }
        for (int i = 0; i < numClusters; i++) {
            int count = maxToken[i] + 1;
            if (count == 1) {
                out.writeBool(false);
            } else {
                out.writeBool(true);
                int n = 31 - Integer.numberOfLeadingZeros(count - 1);
                out.write(n, 4);
                out.write(count - 1 - (1 << n), n);
            }
        }
        codes = prefixCodes;
        for (int i = 0; i < numClusters; i++) {
            codes[i].writeHeader(out);
        }
    }

    private static int ceilLog2(int v) {
        return v <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(v - 1);
    }

    /** Hybrid-uint config in the width scheme of {@code Bits.atMost}. */
    private static void writeConfig(BitWriter out, HybridUintConfig c, int logAlphaSize) {
        out.write(c.splitExp, ceilLog2(logAlphaSize + 1));
        if (c.splitExp != logAlphaSize) {
            out.write(c.msbInToken, ceilLog2(c.splitExp + 1));
            out.write(c.lsbInToken, ceilLog2(c.splitExp - c.msbInToken + 1));
        }
    }

    /**
     * Greedy bottom-up merge of context histograms into at most
     * {@link #MAX_CLUSTERS} clusters, minimising the total entropy increase.
     */
    private void cluster() {
        if (!perContext) {
            return; // everything already shares cluster 0
        }
        int n = numDist;
        long[][] hist = new long[n][];
        int[] owner = new int[n];
        for (int i = 0; i < n; i++) {
            hist[i] = histograms[i].clone();
            owner[i] = i;
        }
        java.util.List<Integer> active = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            active.add(i);
        }
        while (active.size() > MAX_CLUSTERS) {
            double best = Double.MAX_VALUE;
            int bi = -1;
            int bj = -1;
            for (int a = 0; a < active.size(); a++) {
                for (int b = a + 1; b < active.size(); b++) {
                    double cost = mergeCost(hist[active.get(a)], hist[active.get(b)]);
                    if (cost < best) {
                        best = cost;
                        bi = a;
                        bj = b;
                    }
                }
            }
            int i = active.get(bi);
            int j = active.get(bj);
            for (int t = 0; t < ALPHABET; t++) {
                hist[i][t] += hist[j][t];
            }
            for (int k = 0; k < n; k++) {
                if (owner[k] == j) {
                    owner[k] = i;
                }
            }
            active.remove(bj);
        }
        int[] rank = new int[n];
        java.util.Arrays.fill(rank, -1);
        int next = 0;
        for (int k = 0; k < n; k++) {
            int root = owner[k];
            if (rank[root] < 0) {
                rank[root] = next++;
            }
            clusterMap[k] = rank[root];
        }
    }

    private static double mergeCost(long[] a, long[] b) {
        return entropy(a, b) - entropy(a, null) - entropy(b, null);
    }

    private static double entropy(long[] a, long[] b) {
        long n = 0;
        for (int t = 0; t < a.length; t++) {
            n += a[t] + (b != null ? b[t] : 0);
        }
        if (n == 0) {
            return 0;
        }
        double bits = 0;
        double log2 = Math.log(2);
        for (int t = 0; t < a.length; t++) {
            long c = a[t] + (b != null ? b[t] : 0);
            if (c > 0) {
                bits += c * Math.log((double) n / c) / log2;
            }
        }
        return bits;
    }

    /** Pass 2: emit {@code value} in context {@code ctx}. */
    void write(BitWriter out, int ctx, int value) {
        long enc = config.encode(value, scratch);
        int token = (int) enc;
        int midBits = (int) (enc >>> 32);
        if (useAns) {
            secPush(clusterMap[ctx], token, scratch[0], midBits);
            return;
        }
        codes[clusterMap[ctx]].writeSymbol(out, token);
        out.write(scratch[0], midBits);
    }

    /** Pass 2: emit a copy of {@code len} values at distance value {@code distValue}. */
    void writeCopy(BitWriter out, int ctx, int len, int distValue) {
        long enc = lenConfig.encode(len - MIN_LENGTH, scratch);
        int token = (int) enc;
        int midBits = (int) (enc >>> 32);
        if (useAns) {
            secPush(clusterMap[ctx], MIN_SYMBOL + token, scratch[0], midBits);
            long dist = config.encode(distValue, scratch);
            secPush(clusterMap[numDist - 1], (int) dist, scratch[0], (int) (dist >>> 32));
            return;
        }
        codes[clusterMap[ctx]].writeSymbol(out, MIN_SYMBOL + token);
        out.write(scratch[0], midBits);
        long dist = config.encode(distValue, scratch);
        codes[clusterMap[numDist - 1]].writeSymbol(out, (int) dist);
        out.write(scratch[0], (int) (dist >>> 32));
    }

    private void secPush(int cluster, int symbol, int extraVal, int extraBits) {
        if (secN == secTok.length) {
            secTok = java.util.Arrays.copyOf(secTok, secN * 2);
            secVal = java.util.Arrays.copyOf(secVal, secN * 2);
            secBits = java.util.Arrays.copyOf(secBits, secN * 2);
        }
        secTok[secN] = (cluster << 16) | symbol;
        secVal[secN] = extraVal;
        secBits[secN] = (byte) extraBits;
        secN++;
    }

    /**
     * Ends one entropy-coded section. In ANS mode this runs the reverse rANS
     * pass over the section's buffered tokens and writes the stream: the
     * final encoder state first (the decoder's initial 32-bit read), then
     * per token its renormalisation word (if the decoder pulls one) followed
     * by its hybrid-uint extra bits. A section with no tokens still writes
     * the initial state, which the decoder's finish() validates. No-op for
     * prefix coding, where tokens were written directly.
     */
    void finishSection(BitWriter out) {
        if (!useAns) {
            return;
        }
        boolean[] renorm = new boolean[secN];
        int[] words = new int[secN];
        long state = com.ebremer.cygnus.jpegxl.entropy.AnsDecoder.INIT_STATE & 0xffffffffL;
        for (int k = secN - 1; k >= 0; k--) {
            int cl = secTok[k] >>> 16;
            int sym = secTok[k] & 0xffff;
            long f = ansFreq[cl][sym];
            if (state >= (f << 20)) {
                renorm[k] = true;
                words[k] = (int) (state & 0xffff);
                state >>>= 16;
            }
            state = (state / f) * com.ebremer.cygnus.jpegxl.entropy.AnsDecoder.DIST_SUM
                    + ansRev[cl][ansStart[cl][sym] + (int) (state % f)];
        }
        out.write((int) (state & 0xffff), 16);
        out.write((int) (state >>> 16), 16);
        for (int k = 0; k < secN; k++) {
            if (renorm[k]) {
                out.write(words[k], 16);
            }
            if (secBits[k] > 0) {
                out.write(secVal[k], secBits[k]);
            }
        }
        secN = 0;
    }
}
