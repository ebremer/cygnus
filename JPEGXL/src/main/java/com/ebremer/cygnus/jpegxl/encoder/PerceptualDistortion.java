package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;

/**
 * A perceptual distortion measure between an original image and a decode of it,
 * used to steer the encoder's rate control (see {@code VarDctEncoder.toTarget}).
 *
 * <p><b>What this is.</b> A Butteraugli-<em>inspired</em> metric built from the
 * four load-bearing ideas of libjxl's Butteraugli: (1) work in the opsin/XYB
 * domain the eye responds in, not in sRGB; (2) split the error into a
 * low-frequency band and a high-frequency band with a Gaussian blur, because the
 * eye weighs a slow colour drift and a lost fine detail differently; (3) mask
 * high-frequency error by the local high-frequency energy of the original —
 * error hidden in texture counts for less; (4) pool the per-pixel error with a
 * Minkowski p-norm (p≈3) rather than a mean, so a small artefact concentrated in
 * one place — a banding step, a blocking edge — outweighs the same amount of
 * error smeared thinly across the frame.
 *
 * <p>That fourth point is the whole reason this exists. The old rate control
 * targeted mean absolute error, and MAE is blind in exactly the place the eye is
 * sharp: banding on a smooth gradient barely moves the mean yet is the first
 * thing anyone sees. Operating in opsin space and pooling with a p-norm makes
 * the measure track visibility, so the loop spends bits where they show.
 *
 * <p><b>What this is not.</b> It is not a port of libjxl's {@code butteraugli.cc}
 * and its absolute number is not claimed to equal libjxl's Butteraugli distance.
 * A faithful port would only be worth its ~1500 tuned lines if its number could
 * be pinned to the reference, and no Butteraugli binary (cjxl/djxl reporting a
 * distance, or {@code butteraugli_main}) is available in this environment to
 * cross-validate against — so this deliberately stays a self-consistent proxy
 * with its own calibrated scale. Its <em>behaviour</em> is what is validated:
 * identical images score zero, more distortion scores higher, masked error
 * scores lower, and concentrated error scores higher than spread error
 * (PerceptualDistortionTest).
 */
final class PerceptualDistortion {

    /** Gaussian sigma (pixels) of the low/high frequency split. */
    private static final double SIGMA = 1.6;
    /** Pooling exponent. p>1 is what lifts concentrated error above spread error. */
    private static final double P_NORM = 3.0;
    /** Masking strength: how strongly local original texture forgives HF error. */
    private static final double K_MASK = 14.0;
    /** High-frequency weight relative to low-frequency, per channel. */
    private static final double HF_BALANCE = 1.0;
    /**
     * Per-channel gains {X, Y, B}. X (red-green) swings a fraction of Y's range
     * after the cube root, so it carries a large gain to matter at all; B
     * (blue-yellow) the eye resolves least sharply, so it carries less. These set
     * the measure's internal scale only — the rate-control target curve is
     * calibrated to whatever they produce.
     */
    private static final double GAIN_X = 120.0;
    private static final double GAIN_Y = 1.0;
    private static final double GAIN_B = 0.30;
    /** Overall scale, chosen so a distance-1 photo lands near 1. */
    private static final double SCALE = 30.0;

    private final int width;
    private final int height;
    private final double[] fwd;          // opsin forward matrix, row-major 3x3
    private final double[] bias = new double[3];
    private final double[] cbrtBias = new double[3];

    PerceptualDistortion(int width, int height) {
        this.width = width;
        this.height = height;
        ImageMetadata m = new ImageMetadata();
        double itScale = 255.0 / m.intensityTarget;
        double[] inv = new double[9];
        for (int i = 0; i < 9; i++) {
            inv[i] = m.opsinInverse[i] * itScale;
        }
        fwd = invert3x3(inv);
        for (int c = 0; c < 3; c++) {
            bias[c] = m.opsinBias[c];
            cbrtBias[c] = Math.cbrt(m.opsinBias[c]);
        }
    }

    /**
     * The perceptual distance between two images given as sRGB display planes in
     * [0,1] (values outside are allowed and pass through the sign-mirrored sRGB
     * curve). Grey images pass a single plane; colour passes three. The two must
     * share layout.
     */
    double distance(float[][] orig, float[][] dec, boolean grey) {
        float[][] a = toXyb(orig, grey);
        float[][] b = toXyb(dec, grey);

        // Frequency split: blurred = low band, (image - blurred) = high band.
        float[][] aLo = blur(a);
        float[][] bLo = blur(b);

        // Masking signal: local high-frequency energy of the original luma.
        int n = width * height;
        float[] hfOrigY = new float[n];
        for (int i = 0; i < n; i++) {
            hfOrigY[i] = Math.abs(a[1][i] - aLo[1][i]);
        }
        float[] mask = blurPlane(hfOrigY);

        double[] gain = {GAIN_X, GAIN_Y, GAIN_B};
        double pool = 0;
        for (int i = 0; i < n; i++) {
            double e2 = 0;
            double m = 1.0 + K_MASK * mask[i];
            for (int c = 0; c < 3; c++) {
                double lo = a[c][i] - aLo[c][i] - (b[c][i] - bLo[c][i]); // HF diff
                double hi = aLo[c][i] - bLo[c][i];                      // LF diff
                double maskedHf = lo / m;
                e2 += gain[c] * (hi * hi + HF_BALANCE * maskedHf * maskedHf);
            }
            double e = Math.sqrt(e2);
            pool += Math.pow(e, P_NORM);
        }
        double d = Math.pow(pool / n, 1.0 / P_NORM);
        return d * SCALE;
    }

    // ------------------------------------------------------------- internals

    private float[][] toXyb(float[][] srgb, boolean grey) {
        double[] f = fwd;
        int n = width * height;
        float[][] out = new float[3][n];
        for (int i = 0; i < n; i++) {
            double r = srgbToLinear(srgb[0][i]);
            double g = grey ? r : srgbToLinear(srgb[1][i]);
            double bb = grey ? r : srgbToLinear(srgb[2][i]);
            double mixL = f[0] * r + f[1] * g + f[2] * bb;
            double mixM = f[3] * r + f[4] * g + f[5] * bb;
            double mixS = f[6] * r + f[7] * g + f[8] * bb;
            double gl = Math.cbrt(mixL - bias[0]) + cbrtBias[0];
            double gm = Math.cbrt(mixM - bias[1]) + cbrtBias[1];
            double gs = Math.cbrt(mixS - bias[2]) + cbrtBias[2];
            out[0][i] = (float) ((gl - gm) / 2);
            out[1][i] = (float) ((gl + gm) / 2);
            out[2][i] = (float) gs;
        }
        return out;
    }

    private float[][] blur(float[][] planes) {
        float[][] out = new float[planes.length][];
        for (int c = 0; c < planes.length; c++) {
            out[c] = blurPlane(planes[c]);
        }
        return out;
    }

    /** Separable Gaussian blur with clamped edges. */
    private float[] blurPlane(float[] in) {
        float[] kernel = gaussKernel(SIGMA);
        int r = kernel.length / 2;
        float[] tmp = new float[in.length];
        // horizontal
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float s = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    xx = xx < 0 ? 0 : Math.min(xx, width - 1);
                    s += kernel[k + r] * in[row + xx];
                }
                tmp[row + x] = s;
            }
        }
        // vertical
        float[] out = new float[in.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float s = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    yy = yy < 0 ? 0 : Math.min(yy, height - 1);
                    s += kernel[k + r] * tmp[yy * width + x];
                }
                out[y * width + x] = s;
            }
        }
        return out;
    }

    private static float[] gaussKernel(double sigma) {
        int r = (int) Math.ceil(3 * sigma);
        float[] k = new float[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            double v = Math.exp(-(i * i) / (2 * sigma * sigma));
            k[i + r] = (float) v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        return k;
    }

    private static double srgbToLinear(double v) {
        if (v < 0) {
            return -srgbToLinear(-v);
        }
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double[] invert3x3(double[] m) {
        double a = m[0];
        double b = m[1];
        double c = m[2];
        double d = m[3];
        double e = m[4];
        double f = m[5];
        double g = m[6];
        double h = m[7];
        double i = m[8];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        return new double[] {
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        };
    }
}
