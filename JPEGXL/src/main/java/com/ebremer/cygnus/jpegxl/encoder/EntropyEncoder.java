package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.entropy.HybridUintConfig;
import com.ebremer.cygnus.jpegxl.io.BitWriter;

/**
 * Entropy-coded stream writer using prefix or rANS codes, with optional LZ77
 * copies. Values are first counted per context, contexts with similar
 * statistics are merged into at most {@value #MAX_CLUSTERS} clusters, and the
 * values are then emitted in a second pass.
 *
 * <p>An encoder that cannot see all its values before it must start writing
 * them takes the other constructor and gives the clusters explicitly, settling
 * each one's counts as they become known; see {@link #EntropyEncoder(int[], int)}.
 */
final class EntropyEncoder {

    static final int MIN_SYMBOL = 224;
    static final int MIN_LENGTH = 3;
    private static final int MAX_CLUSTERS = 64;
    static final int ALPHABET = MIN_SYMBOL + 40;
    private static final HybridUintConfig VALUE_CONFIG = new HybridUintConfig(4, 1, 0);

    private final int numDist;      // contexts including the LZ77 distance context
    private final boolean lz77;
    private final boolean perContext;
    private final boolean allowAns;
    private final boolean explicit;    // clusters are given, not learned
    private final HybridUintConfig config = VALUE_CONFIG;
    private final HybridUintConfig lenConfig = new HybridUintConfig(0, 0, 0);
    private final long[][] histograms; // per context, per cluster, or one shared
    private final int[] clusterMap;
    private PrefixEncoder[] codes;
    private final int[] scratch = new int[1];

    /** Largest token a value can turn into, so a histogram can cover them all. */
    static final int MAX_TOKEN = VALUE_CONFIG.maxToken;

    /**
     * Counts {@code value}'s symbol into a histogram held outside a coder — how
     * a caller measures what a code would have to carry before deciding which
     * code to use. {@code scratch} is a one-element int array the caller owns.
     */
    static void tally(long[] histogram, int value, int[] scratch) {
        histogram[(int) VALUE_CONFIG.encode(value, scratch)]++;
    }

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
     *        merged into at most {@value #MAX_CLUSTERS} clusters; otherwise all
     *        share one code
     * @param lz77 enable LZ77 copies (adds the distance context)
     * @param allowAns permit rANS symbol coding when it beats prefix codes;
     *        every section must then end with {@link #finishSection}
     */
    EntropyEncoder(int numCtx, boolean perContextClusters, boolean lz77, boolean allowAns) {
        this.lz77 = lz77;
        this.perContext = perContextClusters;
        this.allowAns = allowAns;
        this.explicit = false;
        this.numDist = numCtx + (lz77 ? 1 : 0);
        this.histograms = new long[perContext ? numDist : 1][ALPHABET];
        this.clusterMap = new int[numDist];
    }

    /**
     * Given clusters: {@code map} says which cluster each context is coded
     * with, rather than the clusters being discovered by merging. Each
     * cluster's histogram is settled by {@link #freezeCluster} the moment its
     * content is known, and the values may then be emitted straight away —
     * long before {@link #writeSpec} runs. That inversion is what lets an
     * encoder that never sees the whole image still produce one code spec at
     * the end: prefix codes, unlike rANS, are per-cluster independent, so a
     * cluster settled early is not disturbed by one settled later.
     */
    EntropyEncoder(int[] map, int numClusters) {
        this.lz77 = false;
        this.perContext = true;
        this.allowAns = false;
        this.explicit = true;
        this.numDist = map.length;
        this.histograms = new long[numClusters][ALPHABET];
        this.clusterMap = map;
        this.codes = new PrefixEncoder[numClusters];
    }

    /**
     * Settles one cluster's histogram and builds its code, so values in that
     * cluster's contexts can be written before the spec is. {@link #writeSpec}
     * rebuilds the identical code from the same frozen counts.
     */
    void freezeCluster(int cluster, long[] histogram) {
        System.arraycopy(histogram, 0, histograms[cluster], 0, ALPHABET);
        codes[cluster] = new PrefixEncoder(histograms[cluster], alphabetSize(histograms[cluster]));
    }

    /** Cost in bits of coding {@code histogram}'s values with a frozen cluster's code. */
    long costWith(int cluster, long[] histogram) {
        return codes[cluster].costBits(histogram);
    }

    private static int alphabetSize(long[] histogram) {
        int max = 0;
        for (int t = 0; t < ALPHABET; t++) {
            if (histogram[t] > 0) {
                max = t;
            }
        }
        return max + 1;
    }

    EntropyEncoder(int numCtx, boolean perContextClusters, boolean lz77) {
        this(numCtx, perContextClusters, lz77, false);
    }

    EntropyEncoder(int numCtx, boolean perContextClusters) {
        this(numCtx, perContextClusters, false, false);
    }

    /** Which histogram a context's values are counted into. */
    private int slot(int ctx) {
        return explicit ? clusterMap[ctx] : perContext ? ctx : 0;
    }

    /** Pass 1: account for {@code value} in context {@code ctx}. */
    void count(int ctx, int value) {
        histograms[slot(ctx)][(int) config.encode(value, scratch)]++;
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
            writeClusterMap(out, numClusters);
        }
        long[][] clustered = new long[numClusters][ALPHABET];
        int[] maxToken = new int[numClusters];
        for (int i = 0; i < histograms.length; i++) {
            // in explicit mode the histograms are already the clusters
            int cl = explicit ? i : perContext ? clusterMap[i] : 0;
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

    /**
     * Writes the context-to-cluster map, in whichever of the format's two
     * encodings comes out shorter. The flat one spends a fixed index width on
     * every context, which is fine for a handful of them and ruinous for the
     * tens of thousands a multi-preset VarDCT code has — there the map is one
     * long run per preset, and the entropy-coded form (move-to-front, so a run
     * becomes a repeat of symbol zero) shrinks it to a few dozen bytes.
     */
    private void writeClusterMap(BitWriter out, int numClusters) {
        int nbits = numClusters > 1 ? 32 - Integer.numberOfLeadingZeros(numClusters - 1) : 0;
        BitWriter flat = null;
        if (nbits <= 3) {   // the flat map's index width is a two-bit field
            flat = new BitWriter();
            flat.writeBool(true);   // is_simple
            flat.write(nbits, 2);
            for (int i = 0; i < numDist; i++) {
                flat.write(clusterMap[i], nbits);
            }
        }
        // only worth building the alternative once the flat map is big enough
        // for its own overhead (a nested code spec and an rANS flush) to lose
        BitWriter coded = null;
        if (flat == null || flat.bitLength() > 256) {
            coded = new BitWriter();
            coded.writeBool(false);  // !is_simple
            coded.writeBool(true);   // move-to-front
            int[] symbols = moveToFront(clusterMap);
            EntropyEncoder nested = new EntropyEncoder(1, false, false, true);
            for (int s : symbols) {
                nested.count(0, s);
            }
            nested.writeSpec(coded);
            for (int s : symbols) {
                nested.write(coded, 0, s);
            }
            nested.finishSection(coded);
        }
        if (flat == null || (coded != null && coded.bitLength() < flat.bitLength())) {
            out.writeBits(coded);
        } else {
            out.writeBits(flat);
        }
    }

    /**
     * Move-to-front over the cluster indices: each is replaced by its position
     * in a list that then promotes it to the head, so a repeated index codes as
     * zero. Mirrors the decoder's inverse transform.
     */
    private static int[] moveToFront(int[] values) {
        int[] list = new int[256];
        for (int i = 0; i < 256; i++) {
            list[i] = i;
        }
        int[] symbols = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            int j = 0;
            while (list[j] != v) {
                j++;
            }
            symbols[i] = j;
            System.arraycopy(list, 0, list, 1, j);
            list[0] = v;
        }
        return symbols;
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
     *
     * <p>Every pair's merge cost is held in a matrix rather than recomputed each
     * round: absorbing {@code j} into {@code i} only changes the costs against
     * {@code i}, so a round costs one row of entropies, not the whole triangle.
     */
    private void cluster() {
        if (!perContext || explicit) {
            return; // already assigned: one shared cluster, or a given map
        }
        int n = numDist;
        long[][] hist = new long[n][];
        int[] owner = new int[n];
        int[] active = new int[n];
        double[] self = new double[n];
        for (int i = 0; i < n; i++) {
            hist[i] = histograms[i].clone();
            owner[i] = i;
            active[i] = i;
            self[i] = entropy(hist[i], null);
        }
        int live = n;
        double[][] cost = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                cost[a][b] = cost[b][a] = pairCost(hist, self, a, b);
            }
        }
        while (live > MAX_CLUSTERS) {
            double best = Double.MAX_VALUE;
            int bi = -1;
            int bj = -1;
            for (int a = 0; a < live; a++) {
                double[] row = cost[active[a]];
                for (int b = a + 1; b < live; b++) {
                    if (row[active[b]] < best) {
                        best = row[active[b]];
                        bi = a;
                        bj = b;
                    }
                }
            }
            int i = active[bi];
            int j = active[bj];
            for (int t = 0; t < ALPHABET; t++) {
                hist[i][t] += hist[j][t];
            }
            for (int k = 0; k < n; k++) {
                if (owner[k] == j) {
                    owner[k] = i;
                }
            }
            active[bj] = active[--live];
            self[i] = entropy(hist[i], null);
            for (int a = 0; a < live; a++) {
                int o = active[a];
                if (o != i) {
                    cost[i][o] = cost[o][i] = pairCost(hist, self, i, o);
                }
            }
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

    /** Bits lost by coding clusters {@code a} and {@code b} from one histogram. */
    private static double pairCost(long[][] hist, double[] self, int a, int b) {
        return entropy(hist[a], hist[b]) - self[a] - self[b];
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
        double logN = Math.log(n);
        for (int t = 0; t < a.length; t++) {
            long c = a[t] + (b != null ? b[t] : 0);
            if (c > 0) {
                bits += c * (logN - Math.log(c));
            }
        }
        return bits / LOG2;
    }

    private static final double LOG2 = Math.log(2);

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

    /**
     * Pass 2, from several threads at once: emits into the caller's own writer
     * with the caller's own scratch, touching nothing shared but the frozen
     * codes. Prefix codes only — rANS threads a running state through the whole
     * section and cannot be split this way.
     */
    void writeShared(BitWriter out, int ctx, int value, int[] scratch) {
        long enc = config.encode(value, scratch);
        codes[clusterMap[ctx]].writeSymbol(out, (int) enc);
        out.write(scratch[0], (int) (enc >>> 32));
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
