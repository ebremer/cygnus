package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * The 64-scale transforms on encode — the largest blocks the encoder chooses.
 * In practice this is the 64x32/32x64 rectangular pair (types 19/20): two
 * independent 64x32 halves, each with its own multiplier and chroma correction,
 * beat one rigid 64x64 square (type 18) on any content with the faintest grain
 * of direction, so the square is implemented and decodes correctly but is
 * essentially never the cheapest choice — the same way the rectangular blocks
 * out-compete the square at 16 and 32 scale, one scale up.
 *
 * <p>They are offered only from distance 2 upward. Near lossless a big block is a
 * bad bargain — a gentle gradient's transform decays too slowly to discard, so
 * the block keeps a wide skirt of small coefficients — and the rate estimate,
 * blind to that, would take it anyway and bloat the file; the gate keeps the
 * 64-scale to the coarser quantisers where it plainly pays.
 */
class Dct64Test {

    /** A big, smooth gradient — a very large low-frequency expanse the 64-scale suits. */
    private static int[][] smoothGradient(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = 30 + (x + y) * 180 / (w + h - 2);
                p[0][i] = v;
                p[1][i] = Math.min(255, v + 8);
                p[2][i] = Math.max(0, v - 8);
            }
        }
        return p;
    }

    private static double meanError(int[][] a, int[][] b, int w, int h) {
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(a[c][i] - b[c][i]);
            }
        }
        return sum / (3.0 * w * h);
    }

    private static int[][] deep(int[][] a) {
        int[][] b = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i].clone();
        }
        return b;
    }

    @Test
    void dct64ScaleFiresAndRoundTrips() throws Exception {
        int w = 512;
        int h = 512;
        int[][] src = smoothGradient(w, h);
        long before = VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20);
        byte[] jxl = VarDctEncoder.encode(deep(src), w, h, 3.0f);
        long fired = VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20) - before;

        assertTrue(fired > 0, "a large smooth expanse at distance 3 should draw 64-scale blocks");
        JxlImage img = JxlDecoder.decode(jxl);
        double mean = meanError(src, img.frames.get(0).channels, w, h);
        assertTrue(mean < 6.0, "64-scale round-trip mean error too high: " + mean);
    }

    /**
     * The near-lossless gate holds: at distance 1 the 64-scale is not offered, so a
     * smooth gradient is not bloated by a big block the rate estimate misjudges.
     * Without the gate this same image more than doubles in size.
     */
    @Test
    void nearLosslessGateHolds() throws Exception {
        int w = 512;
        int h = 512;
        int[][] src = smoothGradient(w, h);
        long before = VarDctEncoder.TYPE_HIST.get(18)
                + VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20);
        byte[] jxl = VarDctEncoder.encode(deep(src), w, h, 1.0f);
        long fired = VarDctEncoder.TYPE_HIST.get(18)
                + VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20) - before;
        assertTrue(fired == 0, "distance 1 must not draw any 64-scale block");
        assertTrue(jxl.length < 22000, "near-lossless smooth gradient should stay small: " + jxl.length);
    }

    /**
     * The smoothness gate keeps the 64-scale off busy content even at a coarse
     * distance: texture masks nothing a big block can exploit, so none is drawn,
     * and the image still round-trips.
     */
    @Test
    void busyContentDrawsNo64Scale() throws Exception {
        int w = 320;
        int h = 320;
        int[][] src = new int[3][w * h];
        java.util.Random rnd = new java.util.Random(9);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                src[c][i] = 60 + rnd.nextInt(140);
            }
        }
        long before = VarDctEncoder.TYPE_HIST.get(18)
                + VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20);
        byte[] jxl = VarDctEncoder.encode(deep(src), w, h, 3.0f);
        long fired = VarDctEncoder.TYPE_HIST.get(18)
                + VarDctEncoder.TYPE_HIST.get(19) + VarDctEncoder.TYPE_HIST.get(20) - before;
        assertTrue(fired == 0, "busy content should draw no 64-scale block, drew " + fired);
        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(meanError(src, img.frames.get(0).channels, w, h) < 30.0, "busy round-trip off");
    }
}
