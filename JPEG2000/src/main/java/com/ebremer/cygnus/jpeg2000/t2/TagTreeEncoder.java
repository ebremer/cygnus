package com.ebremer.cygnus.jpeg2000.t2;

/**
 * Tag tree encoder mirroring the decoder protocol in {@link TagTree}
 * (T.800 B.10.2). Leaf values are fixed at construction; the encoder
 * remembers what each query has already revealed, so interleaved queries
 * across leaves and thresholds emit exactly the bits the decoder consumes.
 */
public final class TagTreeEncoder {

    private final int numLevels;
    private final int[] levelWidth;
    private final int[][] trueValue;   // quad-tree minimum reduction
    private final int[][] revealed;    // lower bound revealed so far
    private final boolean[][] known;

    public TagTreeEncoder(int width, int height, int[] leafValues) {
        int w = Math.max(width, 1);
        int h = Math.max(height, 1);
        int levels = 1;
        int ww = w, hh = h;
        while (ww > 1 || hh > 1) {
            ww = (ww + 1) >> 1;
            hh = (hh + 1) >> 1;
            levels++;
        }
        numLevels = levels;
        levelWidth = new int[levels];
        trueValue = new int[levels][];
        revealed = new int[levels][];
        known = new boolean[levels][];
        ww = w;
        hh = h;
        int[] lh = new int[levels];
        for (int l = 0; l < levels; l++) {
            levelWidth[l] = ww;
            lh[l] = hh;
            trueValue[l] = new int[ww * hh];
            revealed[l] = new int[ww * hh];
            known[l] = new boolean[ww * hh];
            ww = (ww + 1) >> 1;
            hh = (hh + 1) >> 1;
        }
        System.arraycopy(leafValues, 0, trueValue[0], 0, w * h);
        for (int l = 1; l < levels; l++) {
            int pw = levelWidth[l - 1];
            int ph = lh[l - 1];
            for (int y = 0; y < lh[l]; y++) {
                for (int x = 0; x < levelWidth[l]; x++) {
                    int min = Integer.MAX_VALUE;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dxx = 0; dxx < 2; dxx++) {
                            int cx = 2 * x + dxx;
                            int cy = 2 * y + dy;
                            if (cx < pw && cy < ph) {
                                min = Math.min(min, trueValue[l - 1][cy * pw + cx]);
                            }
                        }
                    }
                    trueValue[l][y * levelWidth[l] + x] = min;
                }
            }
        }
    }

    /** Emits the bits telling the decoder whether value(x,y) &lt; threshold. */
    public void encode(PacketBitWriter bw, int x, int y, int threshold) {
        int lowerBound = 0;
        for (int l = numLevels - 1; l >= 0; l--) {
            int idx = (y >> l) * levelWidth[l] + (x >> l);
            if (revealed[l][idx] < lowerBound) {
                revealed[l][idx] = lowerBound;
            }
            while (!known[l][idx] && revealed[l][idx] < threshold) {
                if (revealed[l][idx] < trueValue[l][idx]) {
                    bw.bit(0);
                    revealed[l][idx]++;
                } else {
                    bw.bit(1);
                    known[l][idx] = true;
                }
            }
            if (revealed[l][idx] >= threshold) {
                return;
            }
            lowerBound = revealed[l][idx];
        }
    }

    /** Emits bits pinning down the exact value at (x,y) (mirror of decodeValue). */
    public void encodeValue(PacketBitWriter bw, int x, int y) {
        int v = trueValue[0][y * levelWidth[0] + x];
        int t = 1;
        while (true) {
            encode(bw, x, y, t);
            if (v < t) {
                return;
            }
            t++;
        }
    }
}
