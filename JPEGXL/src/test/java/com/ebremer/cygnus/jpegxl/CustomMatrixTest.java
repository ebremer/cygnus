package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * Custom DCT8 quantisation matrices in the lossy VarDCT path. The format lets a
 * frame carry its own quant tables instead of the built-in defaults; the encoder
 * can now emit one for the 8x8 set (the classic quant table), coded as MODE_DCT
 * distance bands, applied on encode and signalled so any decoder rebuilds with
 * it (see the libjxl interop test).
 *
 * <p>Not wired into the automatic encoders: measured, retuning the DCT8 matrix
 * moves almost nothing — smooth content is carried by the larger transforms, each
 * with its own matrix, and the default 8x8 is already well tuned — so the default
 * is kept and the custom path is an explicit call for callers who need it.
 */
class CustomMatrixTest {

    /** The DCT8 default distance bands (DequantMatrices.DEFAULTS[0]), per channel. */
    private static final float[][] DEFAULT = {
        {3150.0f, 0.0f, -0.4f, -0.4f, -0.4f, -2.0f},
        {560.0f, 0.0f, -0.3f, -0.3f, -0.3f, -0.3f},
        {512.0f, -2.0f, -1.0f, 0.0f, -1.0f, -2.0f},
    };

    /**
     * High frequency everywhere, deterministic: no smooth region for a large block
     * to take, so the 8x8 transform carries the image and its matrix bites.
     */
    private static int[][] detailed(int w, int h, long seed) {
        int phase = (int) seed;
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = 128 + (int) (60 * Math.sin((x + phase) * 1.1) * Math.cos((y + phase) * 0.9)
                        + 30 * Math.sin(x * 0.5 + y * 0.4));
                for (int c = 0; c < 3; c++) {
                    p[c][i] = Math.max(0, Math.min(255, v + 4 * c));
                }
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

    private static double mae(int[][] src, byte[] jxl, int w, int h) throws Exception {
        int[][] o = JxlDecoder.decode(jxl).frames.get(0).channels;
        long s = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                s += Math.abs(o[c][i] - src[c][i]);
            }
        }
        return s / (3.0 * w * h);
    }

    @Test
    void customMatrixRoundTrips() throws Exception {
        int w = 192;
        int h = 160;
        int[][] src = detailed(w, h, 5);
        float[][] matrix = {
            {3150.0f, 0.0f, -0.2f, -0.2f, -0.2f, -1.0f},   // flatter than default
            {560.0f, 0.0f, -0.15f, -0.15f, -0.15f, -0.15f},
            {512.0f, -1.0f, -0.5f, 0.0f, -0.5f, -1.0f},
        };
        byte[] jxl = VarDctEncoder.encodeWithMatrix(deep(src), w, h, 8, false, 1.0f, matrix);
        assertTrue(jxl.length < w * h * 3, "should compress: " + jxl.length);
        assertTrue(mae(src, jxl, w, h) < 3.0, "custom-matrix round-trip off");
    }

    /**
     * Signalling the default bands through the custom path reproduces the default
     * encode's fidelity exactly — the MODE_DCT coding of the default matrix is a
     * faithful identity, only a few dozen header bytes larger.
     */
    @Test
    void defaultBandsAreAnIdentity() throws Exception {
        int w = 192;
        int h = 160;
        int[][] src = detailed(w, h, 6);
        byte[] plain = VarDctEncoder.encode(deep(src), w, h, 1.0f);
        byte[] viaMatrix = VarDctEncoder.encodeWithMatrix(deep(src), w, h, 8, false, 1.0f, DEFAULT);
        assertEquals(mae(src, plain, w, h), mae(src, viaMatrix, w, h), 0.02,
                "default bands via the custom path should match the default encode");
    }

    /** A much coarser matrix genuinely reaches the 8x8 blocks: fidelity drops. */
    @Test
    void matrixIsApplied() throws Exception {
        int w = 192;
        int h = 160;
        int[][] src = detailed(w, h, 7);
        float[][] coarse = new float[3][];
        for (int c = 0; c < 3; c++) {
            coarse[c] = DEFAULT[c].clone();
            coarse[c][0] *= 0.25f;   // quarter the band base -> coarser 8x8 quant
        }
        double fine = mae(src, VarDctEncoder.encodeWithMatrix(deep(src), w, h, 8, false, 1.0f, DEFAULT), w, h);
        double coarseErr = mae(src, VarDctEncoder.encodeWithMatrix(deep(src), w, h, 8, false, 1.0f, coarse), w, h);
        assertTrue(coarseErr > fine + 0.5,
                "a coarser 8x8 matrix should lower fidelity: coarse " + coarseErr + " vs fine " + fine);
    }
}
