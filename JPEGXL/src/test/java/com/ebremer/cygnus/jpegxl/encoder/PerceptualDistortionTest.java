package com.ebremer.cygnus.jpegxl.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The {@link PerceptualDistortion} proxy and the rate control it steers. There
 * is no Butteraugli oracle in this environment to pin the absolute number to, so
 * these check the four <em>behaviours</em> that make the measure worth having —
 * it is zero on a match, grows with distortion, forgives error hidden in
 * texture, and (the point) weighs a concentrated artefact above the same error
 * spread thin — and then that the loop it drives bands a gradient less than the
 * mean-absolute-error loop it replaces.
 */
class PerceptualDistortionTest {

    private static float[][] flat(int n, float v) {
        float[][] p = new float[3][n];
        for (int c = 0; c < 3; c++) {
            java.util.Arrays.fill(p[c], v);
        }
        return p;
    }

    private static float[][] copy(float[][] a) {
        float[][] b = new float[a.length][];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i].clone();
        }
        return b;
    }

    @Test
    void identicalImagesScoreZero() {
        int w = 40;
        int h = 40;
        float[][] img = flat(w * h, 0.5f);
        double d = new PerceptualDistortion(w, h).distance(img, copy(img), false);
        assertEquals(0.0, d, 1e-9, "a perfect match is zero distortion");
    }

    @Test
    void moreDistortionScoresHigher() {
        int w = 40;
        int h = 40;
        float[][] orig = flat(w * h, 0.5f);
        float[][] small = flat(w * h, 0.52f);
        float[][] big = flat(w * h, 0.58f);
        PerceptualDistortion m = new PerceptualDistortion(w, h);
        double ds = m.distance(orig, small, false);
        double db = m.distance(orig, big, false);
        assertTrue(ds > 0, "any error is non-zero");
        assertTrue(db > ds, "more error scores higher: " + db + " vs " + ds);
    }

    /**
     * The same high-frequency error costs less on busy content than on flat
     * content, because the eye — and this measure — cannot see it through the
     * texture. Masking keys off the original's local activity, so the busy
     * <em>original</em> is what forgives.
     */
    @Test
    void maskingForgivesBusyBackground() {
        int w = 48;
        int h = 48;
        int n = w * h;
        Random rnd = new Random(7);
        float[][] busy = new float[3][n];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                busy[c][i] = 0.3f + rnd.nextFloat() * 0.4f;
            }
        }
        float[][] flat = flat(n, 0.5f);

        // Identical high-frequency error added to each: a ±0.06 checkerboard.
        float[][] busyDec = copy(busy);
        float[][] flatDec = copy(flat);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float e = ((x + y) & 1) == 0 ? 0.06f : -0.06f;
                for (int c = 0; c < 3; c++) {
                    busyDec[c][i] += e;
                    flatDec[c][i] += e;
                }
            }
        }
        PerceptualDistortion m = new PerceptualDistortion(w, h);
        double onFlat = m.distance(flat, flatDec, false);
        double onBusy = m.distance(busy, busyDec, false);
        assertTrue(onFlat > onBusy,
                "texture should mask the error: flat " + onFlat + " vs busy " + onBusy);
    }

    /**
     * The measure that mean-absolute-error cannot be: the same total error is
     * worse when concentrated in one place than smeared across the frame. This is
     * the p-norm pooling, and it is why the loop refuses to let a smooth region
     * band.
     */
    @Test
    void concentratedErrorBeatsSpread() {
        int w = 64;
        int h = 64;
        int n = w * h;
        float[][] orig = flat(n, 0.5f);

        // Concentrated: +0.15 over an 8x8 patch. Total absolute error = 0.15*64.
        float[][] conc = copy(orig);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int i = y * w + x;
                for (int c = 0; c < 3; c++) {
                    conc[c][i] += 0.15f;
                }
            }
        }
        // Spread: the same total, thinned across every pixel.
        float spread = 0.15f * 64 / n;
        float[][] spr = copy(orig);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                spr[c][i] += spread;
            }
        }
        PerceptualDistortion m = new PerceptualDistortion(w, h);
        double dConc = m.distance(orig, conc, false);
        double dSpread = m.distance(orig, spr, false);
        assertTrue(dConc > dSpread,
                "concentrated error must outweigh spread: " + dConc + " vs " + dSpread);
    }

    // --------------------------------------------------- rate control behaviour

    private static int[][] gradient(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = 40 + (x * 120) / (w - 1) + (y * 40) / (h - 1);
                p[0][i] = v;
                p[1][i] = v;
                p[2][i] = Math.min(255, v + 10);
            }
        }
        return p;
    }

    /** Mean per-row maximum adjacent jump — banding turns a ramp into big steps. */
    private static double banding(int[] luma, int w, int h) {
        double sum = 0;
        for (int y = 0; y < h; y++) {
            int max = 0;
            for (int x = 1; x < w; x++) {
                int i = y * w + x;
                max = Math.max(max, Math.abs(luma[i] - luma[i - 1]));
            }
            sum += max;
        }
        return sum / h;
    }

    private static int[][] deep(int[][] a) {
        int[][] b = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i].clone();
        }
        return b;
    }

    /**
     * At the same requested distance and near the same byte cost, the perceptual
     * loop keeps a smooth gradient far smoother than the mean-absolute-error loop,
     * which coarsens until it bands. This is the whole reason the perceptual
     * metric drives the loop.
     */
    @Test
    void perceptualLoopBandsLessThanMae() throws Exception {
        int w = 512;
        int h = 320;
        int[][] grad = gradient(w, h);
        float d = 2.0f;

        System.clearProperty("jxl.enc.maeRate");
        byte[] perc = VarDctEncoder.encodeToTarget(deep(grad), w, h, d);
        double percBand = banding(JxlDecoder.decode(perc).frames.get(0).channels[1], w, h);

        System.setProperty("jxl.enc.maeRate", "true");
        byte[] mae;
        try {
            mae = VarDctEncoder.encodeToTarget(deep(grad), w, h, d);
        } finally {
            System.clearProperty("jxl.enc.maeRate");
        }
        double maeBand = banding(JxlDecoder.decode(mae).frames.get(0).channels[1], w, h);

        assertTrue(percBand < maeBand * 0.75,
                "perceptual should band less: perceptual " + percBand + " vs mae " + maeBand);
        // and not by simply spending a lot more: within 25% of the MAE size.
        assertTrue(perc.length < mae.length * 1.25,
                "perceptual size " + perc.length + " vs mae " + mae.length);
    }
}
