package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.io.BitWriter;

/**
 * Writes a self-contained modular sub-stream (local tree, one context per
 * channel, gradient predictor) as used for the VarDCT helper images
 * (LF quants, HF metadata).
 */
final class ModularSub {

    private ModularSub() {
    }

    /**
     * @param px each channel's pixels, row-major
     * @param ws channel widths
     * @param hs channel heights
     */
    static void write(BitWriter out, int[][] px, int[] ws, int[] hs) {
        int n = px.length;
        out.writeBool(false); // use_global_tree
        out.writeBool(true);  // default weighted predictor parameters
        out.write(0, 2);      // nb_transforms = 0

        // local MA tree: chain on property 0
        EntropyEncoder treeEnc = new EntropyEncoder(6, false);
        emitTree(null, treeEnc, n);
        EntropyEncoder dataEnc = new EntropyEncoder(n, true);
        for (int c = 0; c < n; c++) {
            tokenize(c, px[c], ws[c], hs[c], n, null, dataEnc);
        }
        treeEnc.writeSpec(out);
        emitTree(out, treeEnc, n);
        dataEnc.writeSpec(out);
        for (int c = 0; c < n; c++) {
            tokenize(c, px[c], ws[c], hs[c], n, out, dataEnc);
        }
    }

    private static void emitTree(BitWriter out, EntropyEncoder enc, int n) {
        for (int j = 0; j < n - 1; j++) {
            token(out, enc, 1, 1);                     // branch on property 0
            token(out, enc, 0, packSigned(n - 2 - j)); // split value
            leaf(out, enc);
        }
        leaf(out, enc);
    }

    private static void leaf(BitWriter out, EntropyEncoder enc) {
        token(out, enc, 1, 0); // leaf marker
        token(out, enc, 2, 5); // gradient predictor
        token(out, enc, 3, 0); // offset
        token(out, enc, 4, 0); // multiplier log
        token(out, enc, 5, 0); // multiplier bits
    }

    private static void token(BitWriter out, EntropyEncoder enc, int ctx, int value) {
        if (out == null) {
            enc.count(ctx, value);
        } else {
            enc.write(out, ctx, value);
        }
    }

    private static void tokenize(int c, int[] px, int w, int h, int numChannels,
            BitWriter out, EntropyEncoder enc) {
        int ctx = numChannels - 1 - c;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rowN = row - w;
            for (int x = 0; x < w; x++) {
                long vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                long vN = y > 0 ? px[rowN + x] : vW;
                long vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                long lo = Math.min(vW, vN);
                long hi = Math.max(vW, vN);
                long pred = Math.min(Math.max(lo, vW + vN - vNW), hi);
                token(out, enc, ctx, packSigned((int) (px[row + x] - pred)));
            }
        }
    }

    private static int packSigned(int v) {
        return v >= 0 ? 2 * v : -2 * v - 1;
    }
}
