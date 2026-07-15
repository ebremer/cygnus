package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * The rectangular (8x16 / 16x8) VarDCT transforms. They pay on content that runs
 * one way — smooth along the block's long axis, detailed across it — a horizon
 * or a wall's edge, where a square block would either smear the detail or spend a
 * DC on every 8x8 of the smooth run.
 */
class RectangularDctTest {

    /**
     * Strongly directional: the left half varies only down the image (bands that
     * a wide 8x16 covers), the right half only across it (a tall 16x8).
     */
    private static int[][] directional(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = x < w / 2
                        ? clamp((int) (128 + 120 * Math.sin(y * 0.20)))
                        : clamp((int) (128 + 120 * Math.sin(x * 0.20)));
                p[0][i] = v;
                p[1][i] = clamp(v + 20);
                p[2][i] = clamp(255 - v);
            }
        }
        return p;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
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

    /** Wide bands (~64px) so a 32-long block covers a smooth run a 16 would split. */
    private static int[][] wideDirectional(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = x < w / 2
                        ? clamp((int) (128 + 110 * Math.sin(y * 0.09)))
                        : clamp((int) (128 + 110 * Math.sin(x * 0.09)));
                p[0][i] = v;
                p[1][i] = clamp(v + 15);
                p[2][i] = clamp(255 - v);
            }
        }
        return p;
    }

    @Test
    void largerRectanglesFireOnWideDirectionalContent() throws Exception {
        int w = 384;
        int h = 320;
        int[][] src = wideDirectional(w, h);
        long tall = VarDctEncoder.TYPE_HIST.get(10);   // 32x16
        long wide = VarDctEncoder.TYPE_HIST.get(11);   // 16x32
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.5f);
        long firedTall = VarDctEncoder.TYPE_HIST.get(10) - tall;
        long firedWide = VarDctEncoder.TYPE_HIST.get(11) - wide;

        assertTrue(firedTall + firedWide > 0,
                "wide directional content should draw 32-scale rectangular blocks");
        JxlImage img = JxlDecoder.decode(jxl);
        double mean = meanError(src, img.frames.get(0).channels, w, h);
        assertTrue(mean < 3.5, "round-trip mean error too high: " + mean);
    }

    @Test
    void rectangularBlocksFireOnDirectionalContent() throws Exception {
        int w = 256;
        int h = 192;
        int[][] src = directional(w, h);
        long before = VarDctEncoder.RECT_BLOCKS.get();
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.0f);
        long fired = VarDctEncoder.RECT_BLOCKS.get() - before;

        assertTrue(fired > 0, "directional content should draw rectangular blocks");
        JxlImage img = JxlDecoder.decode(jxl);
        double mean = meanError(src, img.frames.get(0).channels, w, h);
        assertTrue(mean < 3.0, "round-trip mean error too high: " + mean);
    }

    @Test
    void faithfulAcrossDistances() throws Exception {
        int w = 192;
        int h = 160;
        int[][] src = directional(w, h);
        double previous = -1;
        for (float d : new float[] {1.0f, 2.0f, 4.0f}) {
            byte[] jxl = VarDctEncoder.encode(src, w, h, d);
            JxlImage img = JxlDecoder.decode(jxl);
            double mean = meanError(src, img.frames.get(0).channels, w, h);
            // coarser distance is allowed more error, but it stays sane
            assertTrue(mean < 8.0, "mean error " + mean + " at distance " + d);
            if (previous >= 0) {
                assertTrue(mean >= previous - 0.5,
                        "error should not drop sharply as the quantiser loosens");
            }
            previous = mean;
        }
    }

    /**
     * A plain smooth gradient still encodes fine with the rectangular path in
     * play — the choice never breaks an image it does not help.
     */
    @Test
    void nonDirectionalStillEncodesWell() throws Exception {
        int w = 128;
        int h = 128;
        int[][] src = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                src[0][i] = (x * 255) / (w - 1);
                src[1][i] = (y * 255) / (h - 1);
                src[2][i] = ((x + y) * 255) / (w + h - 2);
            }
        }
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.0f);
        JxlImage img = JxlDecoder.decode(jxl);
        double mean = meanError(src, img.frames.get(0).channels, w, h);
        assertTrue(mean < 1.5, "smooth gradient round-trip mean error " + mean);
    }
}
