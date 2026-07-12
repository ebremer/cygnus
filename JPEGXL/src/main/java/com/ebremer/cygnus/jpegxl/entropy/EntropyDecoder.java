package com.ebremer.cygnus.jpegxl.entropy;

import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/**
 * A complete entropy-coded stream as defined by 18181-1 annex C: LZ77
 * parameters, a context cluster map, and per-cluster symbol codes (either
 * prefix codes or ANS) with hybrid integer configurations.
 *
 * <p>Instances carry mutable decoding state (ANS state, LZ77 window) and are
 * created per section via {@link #read}.
 */
public final class EntropyDecoder {

    private static final int WINDOW_SIZE = 1 << 20;
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;

    private final int numDist;
    private final boolean lz77Enabled;
    private final int minSymbol;
    private final int minLength;
    private final HybridUintConfig lzLenConfig;
    private final int[] clusterMap;
    private final boolean usePrefixCodes;
    private final HybridUintConfig[] configs; // per cluster
    private final PrefixCode[] prefixCodes;   // per cluster, if prefix
    private final AnsDecoder[] ansCodes;      // per cluster, if ANS

    // decoding state
    private final int[] ansState = new int[1];
    private int[] window;
    private int numToCopy;
    private int copyPos;
    private int numDecoded;

    private EntropyDecoder(int numDist, boolean lz77Enabled, int minSymbol, int minLength,
            HybridUintConfig lzLenConfig, int[] clusterMap, boolean usePrefixCodes,
            HybridUintConfig[] configs, PrefixCode[] prefixCodes, AnsDecoder[] ansCodes) {
        this.numDist = numDist;
        this.lz77Enabled = lz77Enabled;
        this.minSymbol = minSymbol;
        this.minLength = minLength;
        this.lzLenConfig = lzLenConfig;
        this.clusterMap = clusterMap;
        this.usePrefixCodes = usePrefixCodes;
        this.configs = configs;
        this.prefixCodes = prefixCodes;
        this.ansCodes = ansCodes;
    }

    public static EntropyDecoder read(Bits in, int numDist, boolean allowLZ77) throws IOException {
        if (numDist <= 0) {
            throw new IllegalArgumentException("numDist " + numDist);
        }
        boolean lz77 = in.bool();
        int minSymbol = Integer.MAX_VALUE;
        int minLength = Integer.MAX_VALUE;
        HybridUintConfig lzLenConfig = null;
        if (lz77) {
            if (!allowLZ77) {
                throw new IOException("LZ77 not allowed in this stream");
            }
            minSymbol = in.u32(224, 0, 512, 0, 4096, 0, 8, 15);
            minLength = in.u32(3, 0, 4, 0, 5, 2, 9, 8);
            lzLenConfig = HybridUintConfig.read(in, 8);
            numDist++;
        }

        int[] clusterMap = readClusterMap(in, numDist, 256);
        int numClusters = 0;
        for (int c : clusterMap) {
            numClusters = Math.max(numClusters, c + 1);
        }

        boolean usePrefix = in.bool();
        HybridUintConfig[] configs = new HybridUintConfig[numClusters];
        PrefixCode[] prefixCodes = null;
        AnsDecoder[] ansCodes = null;
        if (usePrefix) {
            for (int i = 0; i < numClusters; i++) {
                configs[i] = HybridUintConfig.read(in, 15);
            }
            int[] counts = new int[numClusters];
            for (int i = 0; i < numClusters; i++) {
                if (in.bool()) {
                    int n = in.u(4);
                    counts[i] = 1 + (1 << n) + in.u(n);
                    if (counts[i] > (1 << 15)) {
                        throw new IOException("prefix alphabet too large");
                    }
                } else {
                    counts[i] = 1;
                }
            }
            prefixCodes = new PrefixCode[numClusters];
            for (int i = 0; i < numClusters; i++) {
                prefixCodes[i] = PrefixCode.read(in, counts[i]);
            }
        } else {
            int logAlphaSize = 5 + in.u(2);
            for (int i = 0; i < numClusters; i++) {
                configs[i] = HybridUintConfig.read(in, logAlphaSize);
            }
            ansCodes = new AnsDecoder[numClusters];
            for (int i = 0; i < numClusters; i++) {
                ansCodes[i] = AnsDecoder.readDistribution(in, logAlphaSize);
            }
        }
        return new EntropyDecoder(numDist, lz77, minSymbol, minLength, lzLenConfig,
                clusterMap, usePrefix, configs, prefixCodes, ansCodes);
    }

    /** Standalone cluster map, as used by the HF block context map. */
    public static int[] readClusterMapPublic(Bits in, int numDist, int maxAllowed) throws IOException {
        return readClusterMap(in, numDist, maxAllowed);
    }

    static int[] readClusterMap(Bits in, int numDist, int maxAllowed) throws IOException {
        maxAllowed = Math.min(maxAllowed, numDist);
        int[] map = new int[numDist];
        if (numDist == 1) {
            return map;
        }
        if (in.bool()) { // is_simple
            int nbits = in.u(2);
            for (int i = 0; i < numDist; i++) {
                map[i] = in.u(nbits);
                if (map[i] >= maxAllowed) {
                    throw new IOException("cluster index out of range");
                }
            }
        } else {
            boolean useMtf = in.bool();
            EntropyDecoder nested = EntropyDecoder.read(in, 1, numDist > 2);
            for (int i = 0; i < numDist; i++) {
                int index = nested.readSymbol(in, 0);
                if (index >= maxAllowed) {
                    throw new IOException("cluster index out of range");
                }
                map[i] = index;
            }
            nested.finish(in);
            if (useMtf) {
                int[] mtf = new int[256];
                for (int i = 0; i < 256; i++) {
                    mtf[i] = i;
                }
                for (int i = 0; i < numDist; i++) {
                    int j = map[i];
                    int moved = mtf[j];
                    map[i] = moved;
                    System.arraycopy(mtf, 0, mtf, 1, j);
                    mtf[0] = moved;
                }
            }
        }
        // clusters must be used contiguously from zero
        boolean[] seen = new boolean[256];
        int max = 0;
        for (int c : map) {
            seen[c] = true;
            max = Math.max(max, c);
        }
        for (int i = 0; i <= max; i++) {
            if (!seen[i]) {
                throw new IOException("cluster map skips cluster " + i);
            }
        }
        return map;
    }

    /** Reads one value in context {@code ctx} (no special LZ77 distances). */
    public int readSymbol(Bits in, int ctx) throws IOException {
        return readSymbol(in, ctx, 0);
    }

    /** Debug: when positive, traces this many symbol reads to stderr. */
    public static int trace;

    /** Reads one value; {@code distMult} enables 2-D special distances for LZ77. */
    public int readSymbol(Bits in, int ctx, int distMult) throws IOException {
        if (numToCopy > 0) {
            numToCopy--;
            int v = window[copyPos++ & WINDOW_MASK];
            window[numDecoded++ & WINDOW_MASK] = v;
            if (trace > 0) {
                trace--;
                System.err.printf("[trace] copy -> %08x%n", v);
            }
            return v;
        }
        if (ctx >= numDist) {
            throw new IOException("context " + ctx + " out of range " + numDist);
        }
        int cluster = clusterMap[ctx];
        int token = decodeToken(in, cluster);
        if (token >= minSymbol) { // LZ77 copy
            int lzCluster = clusterMap[numDist - 1];
            int num = lzLenConfig.decode(in, token - minSymbol) + minLength;
            int distToken = decodeToken(in, lzCluster);
            int distance = configs[lzCluster].decode(in, distToken);
            if (trace > 0) {
                trace--;
                System.err.printf(
                        "[trace] ctx=%d cluster=%d token=%d LZ77 num=%d distToken=%d rawDist=%d distMult=%d numDecoded=%d%n",
                        ctx, cluster, token, num, distToken, distance, distMult, numDecoded);
            }
            if (distMult == 0) {
                distance++;
            } else if (distance >= 120) {
                distance -= 119;
            } else {
                int special = SPECIAL_DISTANCES[distance];
                distance = Math.max(1, ((special >> 4) - 7) + distMult * (special & 7));
            }
            distance = Math.min(Math.min(distance, numDecoded), WINDOW_SIZE);
            if (window == null) {
                window = new int[WINDOW_SIZE];
            }
            copyPos = numDecoded - distance;
            numToCopy = num - 1;
            int v = window[copyPos++ & WINDOW_MASK];
            window[numDecoded++ & WINDOW_MASK] = v;
            return v;
        }
        int value = configs[cluster].decode(in, token);
        if (trace > 0) {
            trace--;
            System.err.printf("[trace] ctx=%d cluster=%d token=%d -> value=%08x (config %s)%n",
                    ctx, cluster, token, value, configs[cluster]);
        }
        if (lz77Enabled) {
            if (window == null) {
                window = new int[WINDOW_SIZE];
            }
            window[numDecoded++ & WINDOW_MASK] = value;
        }
        return value;
    }

    /** Debug summary of the LZ77 parameters and cluster map shape. */
    public String describe() {
        return "numDist=" + numDist + " lz77=" + lz77Enabled + " minSymbol=" + minSymbol
                + " minLength=" + minLength + " prefix=" + usePrefixCodes
                + " clusters=" + (usePrefixCodes ? prefixCodes.length : ansCodes.length);
    }

    private int decodeToken(Bits in, int cluster) throws IOException {
        return usePrefixCodes
                ? prefixCodes[cluster].decode(in)
                : ansCodes[cluster].decode(in, ansState);
    }

    private static final boolean LENIENT = Boolean.getBoolean("jxl.lenient");

    /** Validates the final ANS state; must be called at the end of each stream. */
    public void finish(Bits in) throws IOException {
        if (!usePrefixCodes) {
            if (ansState[0] != 0) {
                if (ansState[0] != AnsDecoder.INIT_STATE && !LENIENT) {
                    throw new IOException("ANS stream final state mismatch");
                }
            } else {
                if ((in.u(16) != (AnsDecoder.INIT_STATE & 0xffff)
                        || in.u(16) != (AnsDecoder.INIT_STATE >>> 16)) && !LENIENT) {
                    throw new IOException("ANS stream final state mismatch");
                }
            }
        }
    }

    /** Resets per-stream decoding state so the spec can be reused for another section. */
    public EntropyDecoder freshState() {
        return new EntropyDecoder(numDist, lz77Enabled, minSymbol, minLength, lzLenConfig,
                clusterMap, usePrefixCodes, configs, prefixCodes, ansCodes);
    }

    /**
     * {a,b} pairs encoded as (a+7)*16 + b, per the special distance table:
     * distance token value {@code v < 120} decodes to
     * {@code max(1, a + distMult * b)}. Public so the encoder can build the
     * reverse mapping from the same table.
     */
    public static final int[] SPECIAL_DISTANCES = {
        0x71, 0x80, 0x81, 0x61, 0x72, 0x90, 0x82, 0x62, 0x91, 0x51, 0x92, 0x52,
        0x73, 0xa0, 0x83, 0x63, 0xa1, 0x41, 0x93, 0x53, 0xa2, 0x42, 0x74, 0xb0,
        0x84, 0x64, 0xb1, 0x31, 0xa3, 0x43, 0x94, 0x54, 0xb2, 0x32, 0x75, 0xa4,
        0x44, 0xb3, 0x33, 0xc0, 0x85, 0x65, 0xc1, 0x21, 0x95, 0x55, 0xc2, 0x22,
        0xb4, 0x34, 0xa5, 0x45, 0xc3, 0x23, 0x76, 0xd0, 0x86, 0x66, 0xd1, 0x11,
        0x96, 0x56, 0xd2, 0x12, 0xb5, 0x35, 0xc4, 0x24, 0xa6, 0x46, 0xd3, 0x13,
        0x77, 0xe0, 0x87, 0x67, 0xc5, 0x25, 0xe1, 0x01, 0xb6, 0x36, 0xd4, 0x14,
        0x97, 0x57, 0xe2, 0x02, 0xa7, 0x47, 0xe3, 0x03, 0xc6, 0x26, 0xd5, 0x15,
        0xf0, 0xb7, 0x37, 0xe4, 0x04, 0xf1, 0xf2, 0xd6, 0x16, 0xf3, 0xc7, 0x27,
        0xe5, 0x05, 0xf4, 0xd7, 0x17, 0xe6, 0x06, 0xf5, 0xe7, 0x07, 0xf6, 0xf7,
    };
}
