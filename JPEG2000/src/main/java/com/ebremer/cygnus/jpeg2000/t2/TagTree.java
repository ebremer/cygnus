package com.ebremer.cygnus.jpeg2000.t2;

/**
 * Tag tree decoder (T.800 B.10.2). A tag tree codes a 2-D array of
 * non-negative integers by quad-tree reduction, where each parent holds the
 * minimum of its children. Values are refined incrementally as successive
 * packets supply more bits; node state persists across calls.
 *
 * <p>Protocol per node, given the lower bound inherited from its parent:
 * questions "is the value equal to q?" are answered for q counting up from
 * the current lower bound. A 0 bit means "greater" (bound increases), a
 * 1 bit reveals the exact value. Questions below the inherited bound are
 * implied and consume no bits.</p>
 */
public final class TagTree {

    private final int numLevels;
    private final int[] levelWidth;
    private final int[][] value;      // current lower bound (exact once known)
    private final boolean[][] known;  // true once the node value is revealed

    public TagTree(int width, int height) {
        int w = Math.max(width, 1);
        int h = Math.max(height, 1);
        int levels = 1;
        int ww = w, hh = h;
        while (ww > 1 || hh > 1) {
            ww = (ww + 1) >> 1;
            hh = (hh + 1) >> 1;
            levels++;
        }
        this.numLevels = levels;
        this.levelWidth = new int[levels];
        this.value = new int[levels][];
        this.known = new boolean[levels][];
        ww = w;
        hh = h;
        for (int l = 0; l < levels; l++) {
            levelWidth[l] = ww;
            value[l] = new int[ww * hh];
            known[l] = new boolean[ww * hh];
            ww = (ww + 1) >> 1;
            hh = (hh + 1) >> 1;
        }
    }

    /**
     * Decodes whether the value at leaf (x, y) is less than {@code threshold},
     * consuming bits from {@code br} as needed.
     */
    public boolean decode(PacketBitReader br, int x, int y, int threshold) {
        int lowerBound = 0;
        for (int l = numLevels - 1; l >= 0; l--) {
            int idx = (y >> l) * levelWidth[l] + (x >> l);
            if (value[l][idx] < lowerBound) {
                value[l][idx] = lowerBound;
            }
            while (!known[l][idx] && value[l][idx] < threshold) {
                if (br.readBit() == 1) {
                    known[l][idx] = true;
                } else {
                    value[l][idx]++;
                }
            }
            if (value[l][idx] >= threshold) {
                return false;
            }
            lowerBound = value[l][idx];
        }
        return true;
    }

    /**
     * Fully resolves the value at leaf (x, y) by raising the threshold until
     * the value is pinned down (used for the zero-bit-planes tree, B.10.5).
     */
    public int decodeValue(PacketBitReader br, int x, int y) {
        int t = 1;
        while (!decode(br, x, y, t)) {
            t++;
            if (t > 4096) {
                // corrupt or truncated data; a huge value disables the block
                return t;
            }
        }
        return t - 1;
    }
}
