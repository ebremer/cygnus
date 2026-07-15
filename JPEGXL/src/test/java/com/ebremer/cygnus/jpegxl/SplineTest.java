package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import com.ebremer.cygnus.jpegxl.features.Splines;
import org.junit.jupiter.api.Test;

/**
 * Splines on encode: caller-supplied curves carried in the frame and drawn over
 * the decoded image. The encoder does not detect them — fitting the curves of a
 * picture is a separate problem — it carries the ones it is given.
 */
class SplineTest {

    /** A curving line with a solid colour and thickness along its length. */
    static Splines curve(int w, int h) {
        Splines s = new Splines();
        s.numSplines = 1;
        s.quantAdjust = 0;
        s.controlX = new int[][] {{w / 6, w / 3, w / 2, 2 * w / 3, 5 * w / 6}};
        s.controlY = new int[][] {{h / 2, h / 3, h / 2, 2 * h / 3, h / 2}};
        s.coeffX = new int[1][32];
        s.coeffY = new int[1][32];
        s.coeffB = new int[1][32];
        s.coeffSigma = new int[1][32];
        s.coeffY[0][0] = 90;      // luma along the whole arc (Fourier DC)
        s.coeffB[0][0] = 40;
        s.coeffSigma[0][0] = 16;  // thickness; must give a positive sigma
        return s;
    }

    private static int[][] flat(int w, int h, int v) {
        int[][] p = new int[3][w * h];
        for (int c = 0; c < 3; c++) {
            java.util.Arrays.fill(p[c], v);
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

    @Test
    void splinesAreDrawnOverTheImage() throws Exception {
        int w = 256;
        int h = 192;
        int[][] base = flat(w, h, 100);
        byte[] without = VarDctEncoder.encode(deep(base), w, h, 1.5f);
        byte[] with = VarDctEncoder.encodeWithSplines(deep(base), w, h, 8, false, 1.5f, curve(w, h));

        int[][] a = JxlDecoder.decode(without).frames.get(0).channels;
        int[][] b = JxlDecoder.decode(with).frames.get(0).channels;
        int maxDiff = 0;
        long changed = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                int d = Math.abs(a[c][i] - b[c][i]);
                maxDiff = Math.max(maxDiff, d);
                if (d > 4) {
                    changed++;
                }
            }
        }
        // the spline visibly marks the image where it runs, and leaves the rest be
        assertTrue(maxDiff > 20, "the spline should visibly draw: max diff " + maxDiff);
        assertTrue(changed > 200, "the spline should cover a line of pixels: " + changed);
        assertTrue(changed < (long) w * h * 3 / 2, "but not the whole image: " + changed);
    }

    @Test
    void greyRejected() {
        int[][] base = flat(64, 64, 100);
        assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeWithSplines(base, 64, 64, 8, true, 1.5f, curve(64, 64)));
    }
}
