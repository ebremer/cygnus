package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * The 128 and 256 scales. The block-choice machinery is generalised to produce
 * them and libjxl reads them back (see the opt-in interop test), but they are
 * <b>off by default</b>: the token-count rate estimate cannot price a block that
 * large, so wherever it picks one it bloats — six times on a smooth ramp at
 * distance 2 — and wherever a big block might help it is not picked. So the
 * default encoder never offers them; {@code -Djxl.enc.dct128}/{@code dct256} turn
 * them on for anyone wiring a better estimate.
 */
class Dct256Test {

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

    private static int[][] deep(int[][] a) {
        int[][] b = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i].clone();
        }
        return b;
    }

    private static long fired128to256(long[] before) {
        long f = 0;
        for (int t = 21; t <= 26; t++) {
            f += VarDctEncoder.TYPE_HIST.get(t) - before[t];
        }
        return f;
    }

    private static long[] snapshot() {
        long[] before = new long[27];
        for (int t = 0; t < 27; t++) {
            before[t] = VarDctEncoder.TYPE_HIST.get(t);
        }
        return before;
    }

    /** The default encoder never draws a 128/256 block, even on ideal content. */
    @Test
    void offByDefault() throws Exception {
        assumeTrue(!Boolean.getBoolean("jxl.enc.dct128") && !Boolean.getBoolean("jxl.enc.dct256"),
                "scales opted in for this run");
        int w = 640;
        int h = 640;
        long[] before = snapshot();
        for (float d : new float[] {2f, 3f, 4f}) {
            VarDctEncoder.encode(deep(smoothGradient(w, h)), w, h, d);
        }
        assertTrue(fired128to256(before) == 0, "128/256 must be off by default");
    }

    /** When opted in, the scales draw blocks and round-trip through our own decoder. */
    @Test
    void optInFiresAndRoundTrips() throws Exception {
        assumeTrue(Boolean.getBoolean("jxl.enc.dct128") && Boolean.getBoolean("jxl.enc.dct256"),
                "128/256 scales not opted in");
        int w = 640;
        int h = 640;
        int[][] src = smoothGradient(w, h);
        long[] before = snapshot();
        byte[] jxl = VarDctEncoder.encode(deep(src), w, h, 2.0f);
        assertTrue(fired128to256(before) > 0, "opted-in run should draw some 128/256 block");
        int[][] dec = JxlDecoder.decode(jxl).frames.get(0).channels;
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(dec[c][i] - src[c][i]);
            }
        }
        assertTrue(sum / (3.0 * w * h) < 4.0, "128/256 round-trip off");
    }
}
