package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.entropy.HybridUintConfig;
import com.ebremer.jpegxl.io.BitWriter;

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
    private final HybridUintConfig config = new HybridUintConfig(4, 1, 0);
    private final HybridUintConfig lenConfig = new HybridUintConfig(0, 0, 0);
    private final long[][] histograms; // per context (or one shared histogram)
    private final int[] clusterMap;
    private PrefixEncoder[] codes;
    private final int[] scratch = new int[1];

    /**
     * @param numCtx number of value contexts
     * @param perContextClusters when true, contexts are counted separately and
     *        merged into at most eight clusters; otherwise all share one code
     * @param lz77 enable LZ77 copies (adds the distance context)
     */
    EntropyEncoder(int numCtx, boolean perContextClusters, boolean lz77) {
        this.lz77 = lz77;
        this.perContext = perContextClusters;
        this.numDist = numCtx + (lz77 ? 1 : 0);
        this.histograms = new long[perContext ? numDist : 1][ALPHABET];
        this.clusterMap = new int[numDist];
    }

    EntropyEncoder(int numCtx, boolean perContextClusters) {
        this(numCtx, perContextClusters, false);
    }

    /** Pass 1: account for {@code value} in context {@code ctx}. */
    void count(int ctx, int value) {
        histograms[perContext ? ctx : 0][(int) config.encode(value, scratch)]++;
    }

    /** Pass 1: account for a copy of {@code len} previous values. */
    void countCopy(int ctx, int len) {
        histograms[perContext ? ctx : 0]
                [MIN_SYMBOL + (int) lenConfig.encode(len - MIN_LENGTH, scratch)]++;
        // distance value 1 -> special distance {1, 0}: the previous value
        histograms[perContext ? numDist - 1 : 0][(int) config.encode(1, scratch)]++;
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
    double copyCostBits(int ctx, int len) {
        int[] tmp = new int[1];
        long enc = lenConfig.encode(len - MIN_LENGTH, tmp);
        double bits = symbolCostBits(ctx, MIN_SYMBOL + (int) enc) + (int) (enc >>> 32);
        long dist = config.encode(1, tmp);
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
        out.writeBool(true); // use_prefix_code
        for (int i = 0; i < numClusters; i++) {
            // hybrid config with log_alpha_size = 15: split_exp(4) = 4 bits,
            // msb(1) in ceil(log2(5)) = 3 bits, lsb(0) in ceil(log2(4)) = 2 bits
            out.write(config.splitExp, 4);
            out.write(config.msbInToken, 3);
            out.write(config.lsbInToken, 2);
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
        codes = new PrefixEncoder[numClusters];
        for (int i = 0; i < numClusters; i++) {
            codes[i] = new PrefixEncoder(clustered[i], maxToken[i] + 1);
            codes[i].writeHeader(out);
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
        codes[clusterMap[ctx]].writeSymbol(out, token);
        out.write(scratch[0], midBits);
    }

    /** Pass 2: emit a copy of {@code len} previous values. */
    void writeCopy(BitWriter out, int ctx, int len) {
        long enc = lenConfig.encode(len - MIN_LENGTH, scratch);
        int token = (int) enc;
        int midBits = (int) (enc >>> 32);
        codes[clusterMap[ctx]].writeSymbol(out, MIN_SYMBOL + token);
        out.write(scratch[0], midBits);
        long dist = config.encode(1, scratch);
        codes[clusterMap[numDist - 1]].writeSymbol(out, (int) dist);
        out.write(scratch[0], (int) (dist >>> 32));
    }
}
