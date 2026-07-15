package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.color.CfaDemosaic;
import com.ebremer.cygnus.jpegxl.color.CfaDemosaic.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Bilinear Bayer demosaicing. There is no format reference to match — JPEG XL
 * carries no Bayer pattern — so this validates the algorithm on its own terms:
 * a mosaic built from a known image demosaics back to it, exactly where a colour
 * was measured and closely between.
 */
class CfaDemosaicTest {

    /** Samples one colour per pixel, the one the pattern measures there. */
    private static int[] mosaic(int[][] rgb, int w, int h, Pattern p) {
        int[] m = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int row = y & 1;
                int col = x & 1;
                int c;
                if (row == p.redRow() && col == p.redCol()) {
                    c = 0;
                } else if (row == p.blueRow() && col == p.blueCol()) {
                    c = 2;
                } else {
                    c = 1;
                }
                m[i] = rgb[c][i];
            }
        }
        return m;
    }

    private static int measured(int x, int y, Pattern p) {
        int row = y & 1;
        int col = x & 1;
        if (row == p.redRow() && col == p.redCol()) {
            return 0;
        }
        if (row == p.blueRow() && col == p.blueCol()) {
            return 2;
        }
        return 1;
    }

    @Test
    void flatImageIsReconstructedExactly() {
        int w = 32;
        int h = 24;
        for (Pattern p : Pattern.values()) {
            int[][] rgb = {fill(w * h, 40), fill(w * h, 130), fill(w * h, 210)};
            int[] m = mosaic(rgb, w, h, p);
            int[][] out = CfaDemosaic.demosaic(m, w, h, p);
            // every interpolation of a constant field is that constant
            for (int i = 0; i < w * h; i++) {
                assertEquals(40, out[0][i], "R at " + i + " " + p);
                assertEquals(130, out[1][i], "G at " + i + " " + p);
                assertEquals(210, out[2][i], "B at " + i + " " + p);
            }
        }
    }

    @Test
    void measuredColourSurvivesExactly() {
        int w = 40;
        int h = 40;
        int[][] rgb = gradient(w, h);
        for (Pattern p : Pattern.values()) {
            int[] m = mosaic(rgb, w, h, p);
            int[][] out = CfaDemosaic.demosaic(m, w, h, p);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int c = measured(x, y, p);
                    // the colour actually sampled here comes back untouched
                    assertEquals(rgb[c][i], out[c][i],
                            "measured colour " + c + " at (" + x + "," + y + ") " + p);
                }
            }
        }
    }

    @Test
    void smoothGradientRoundTripsClosely() {
        int w = 64;
        int h = 64;
        int[][] rgb = gradient(w, h);
        for (Pattern p : Pattern.values()) {
            int[] m = mosaic(rgb, w, h, p);
            int[][] out = CfaDemosaic.demosaic(m, w, h, p);
            long sum = 0;
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < w * h; i++) {
                    sum += Math.abs(out[c][i] - rgb[c][i]);
                }
            }
            double mean = sum / (3.0 * w * h);
            assertTrue(mean < 1.5, "smooth gradient demosaic mean error " + mean + " " + p);
        }
    }

    private static int[] fill(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static int[][] gradient(int w, int h) {
        int[][] rgb = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                rgb[0][i] = x * 255 / (w - 1);
                rgb[1][i] = y * 255 / (h - 1);
                rgb[2][i] = (x + y) * 255 / (w + h - 2);
            }
        }
        return rgb;
    }
}
