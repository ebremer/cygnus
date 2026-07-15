package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * The DCT2 and DCT4 varblocks on encode. They pay on piecewise-flat content — a
 * hard edge between two flat regions, a text stroke — where a plain DCT8 rings.
 */
class SmallTransformEncodeTest {

    /** Large flat regions with hard step edges off the 8-pixel grid: the edge blocks are piecewise-flat. */
    private static int[][] stepEdges(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = x < w * 43 / 100 || y < h * 37 / 100 ? 235 : 25;
                if (((x / 24) + (y / 24)) % 3 == 0) {
                    v = 255 - v;
                }
                if (x % 37 < 3 || y % 41 < 3) {
                    v = 128;   // sharp thin lines
                }
                p[0][i] = v;
                p[1][i] = v;
                p[2][i] = v;
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

    @Test
    void smallTransformsFireOnHardEdges() throws Exception {
        int w = 256;
        int h = 192;
        int[][] src = stepEdges(w, h);
        long before = VarDctEncoder.TYPE_HIST.get(2) + VarDctEncoder.TYPE_HIST.get(3);
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.5f);
        long fired = VarDctEncoder.TYPE_HIST.get(2) + VarDctEncoder.TYPE_HIST.get(3) - before;

        assertTrue(fired > 0, "hard-edged content should draw DCT2/DCT4 blocks");
        JxlImage img = JxlDecoder.decode(jxl);
        double mean = meanError(src, img.frames.get(0).channels, w, h);
        assertTrue(mean < 6.0, "round-trip mean error too high: " + mean);
    }

    /** Blocks split one way with the other axis smooth over the full width favour DCT4x8/8x4. */
    private static int[][] splitBlocks(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int i = 0; i < w * h; i++) {
            p[0][i] = p[1][i] = p[2][i] = 128;
        }
        for (int by = 0; by < h / 8; by++) {
            for (int bx = 0; bx < w / 8; bx++) {
                if (((bx * 5 + by * 11) % 6) != 0) {
                    continue;
                }
                boolean t48 = ((bx + by) & 1) == 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int v = t48
                                ? (y < 4 ? clamp(30 + x * 24) : clamp(230 - x * 24))
                                : (x < 4 ? clamp(30 + y * 24) : clamp(230 - y * 24));
                        int i = (by * 8 + y) * w + bx * 8 + x;
                        p[0][i] = p[1][i] = p[2][i] = v;
                    }
                }
            }
        }
        return p;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    @Test
    void rectangularSmallTransformsFire() throws Exception {
        int w = 256;
        int h = 192;
        int[][] src = splitBlocks(w, h);
        long before = VarDctEncoder.TYPE_HIST.get(12) + VarDctEncoder.TYPE_HIST.get(13);
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.0f);
        long fired = VarDctEncoder.TYPE_HIST.get(12) + VarDctEncoder.TYPE_HIST.get(13) - before;

        assertTrue(fired > 0, "split blocks with perpendicular smoothness should draw DCT4x8/DCT8x4");
        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(meanError(src, img.frames.get(0).channels, w, h) < 6.0, "round-trip off");
    }

    /** The choice never breaks an image it does not help: a smooth gradient still round-trips. */
    @Test
    void smoothImageUnharmed() throws Exception {
        int w = 128;
        int h = 128;
        int[][] src = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                src[0][i] = x * 255 / (w - 1);
                src[1][i] = y * 255 / (h - 1);
                src[2][i] = (x + y) * 255 / (w + h - 2);
            }
        }
        byte[] jxl = VarDctEncoder.encode(src, w, h, 1.0f);
        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(meanError(src, img.frames.get(0).channels, w, h) < 1.5, "smooth gradient off");
    }
}
