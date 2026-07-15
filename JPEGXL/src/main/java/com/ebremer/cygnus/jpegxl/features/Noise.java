package com.ebremer.cygnus.jpegxl.features;

/** Photon noise synthesis (18181-1 annex G.6): XorShift128+ field, laplacian
 * convolution, and intensity-dependent application in XYB space. */
public final class Noise {

    private static final float[][] LAPLACIAN = new float[5][5];

    static {
        for (float[] row : LAPLACIAN) {
            java.util.Arrays.fill(row, 0.16f);
        }
        LAPLACIAN[2][2] = -3.84f;
    }

    private Noise() {
    }

    /** XYB opsin absorbance bias (18181-1); the encoder's inverse of it is negative. */
    private static final double OPSIN_ABSORBANCE_BIAS = 0.0037930732552754493;

    // Photon-noise model constants (libjxl SimulatePhotonNoise): a daylight
    // spectrum, a 2010s camera's quantum efficiency and read noise, a 35mm sensor.
    private static final double PHOTONS_PER_LXS_PER_UM2 = 11260;
    private static final double EFFECTIVE_QUANTUM_EFFICIENCY = 0.20;
    private static final double PHOTO_RESPONSE_NON_UNIFORMITY = 0.005;
    private static final double INPUT_REFERRED_READ_NOISE = 3;
    private static final double SENSOR_AREA_UM2 = 36000.0 * 24000;

    /**
     * The 8-point noise strength table a photon-noise level produces, computed the
     * way {@code cjxl --photon_noise_iso} does (libjxl's {@code SimulatePhotonNoise}):
     * a physical shot-noise model — read noise, photon shot noise growing with the
     * square root of the signal, and photo-response non-uniformity — carried
     * through the opsin curve into XYB, where the decoder's synthesis adds it. The
     * table is what the frame carries; feeding it and the same {@code iso} to
     * libjxl yields the same grain, because the synthesis is normative.
     *
     * @param iso the photon-noise level; higher is grainier (as a camera's ISO)
     */
    public static float[] photonNoiseLut(long xsize, long ysize, double iso) {
        double biasCbrt = Math.cbrt(OPSIN_ABSORBANCE_BIAS);
        double h18 = 10 / iso;                                 // ISO = 10 lx*s / H
        double pixelAreaUm2 = SENSOR_AREA_UM2 / ((double) xsize * ysize);
        double electronsPer18 = EFFECTIVE_QUANTUM_EFFICIENCY * PHOTONS_PER_LXS_PER_UM2
                * h18 * pixelAreaUm2;
        float[] lut = new float[8];
        for (int i = 0; i < 8; i++) {
            double scaledIndex = i / 6.0;                      // (kNumNoisePoints - 2)
            double y = 2 * scaledIndex;                        // XYB Y at this point
            double diff = y - biasCbrt;
            double linear = Math.max(0.0, diff * diff * diff + OPSIN_ABSORBANCE_BIAS);
            double electrons = electronsPer18 * (linear / 0.18);
            // quadrature sum of read noise, shot noise (sqrt(S), so unsquared), PRNU
            double noise = Math.sqrt(INPUT_REFERRED_READ_NOISE * INPUT_REFERRED_READ_NOISE
                    + electrons
                    + Math.pow(PHOTO_RESPONSE_NON_UNIFORMITY * electrons, 2));
            double linearNoise = noise * (0.18 / electronsPer18);
            double root = Math.cbrt(linear - OPSIN_ABSORBANCE_BIAS);
            double opsinDerivative = (1.0 / 3) / (root * root);
            double opsinNoise = linearNoise * opsinDerivative;
            // norm_const * (red+green) * per-plane stddev of the generated field
            double v = opsinNoise / (0.22 * Math.sqrt(2.0) * 1.13);
            lut[i] = (float) Math.min(Math.max(v, 0.0), 1.0);
        }
        return lut;
    }

    /** Generates the convolved noise field for a frame. */
    public static float[][] initialize(long seed0, int width, int height,
            int groupDim, int groupColumns, int numGroups) {
        float[][] raw = new float[3][width * height];
        for (int group = 0; group < numGroups; group++) {
            int y0 = (group / groupColumns) * groupDim;
            int x0 = (group % groupColumns) * groupDim;
            long seed1 = (((long) x0 & 0xFFFFFFFFL) << 32) | ((long) y0 & 0xFFFFFFFFL);
            int ySize = Math.min(groupDim, height - y0);
            int xSize = Math.min(groupDim, width - x0);
            XorShift128 rng = new XorShift128(seed0, seed1);
            int[] bits = new int[16];
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < ySize; y++) {
                    for (int x = 0; x < xSize; x += 16) {
                        rng.fill(bits);
                        for (int i = 0; i < 16 && x + i < xSize; i++) {
                            int f = (bits[i] >>> 9) | 0x3f_80_00_00;
                            raw[c][(y0 + y) * width + x0 + x + i] = Float.intBitsToFloat(f);
                        }
                    }
                }
            }
        }
        float[][] noise = new float[3][width * height];
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float sum = 0f;
                    for (int iy = 0; iy < 5; iy++) {
                        int cy = mirror(y + iy - 2, height);
                        for (int ix = 0; ix < 5; ix++) {
                            int cx = mirror(x + ix - 2, width);
                            sum += raw[c][cy * width + cx] * LAPLACIAN[iy][ix];
                        }
                    }
                    noise[c][y * width + x] = sum;
                }
            }
        }
        return noise;
    }

    /** Adds the noise to the XYB colour planes. */
    public static void synthesize(float[][] planes, float[][] noise, int width, int height,
            float[] lut, float baseCorrelationX, float baseCorrelationB) {
        float[] xp = planes[0];
        float[] yp = planes[1];
        float[] bp = planes[2];
        for (int i = 0; i < width * height; i++) {
            float inScaledR = yp[i] + xp[i];
            inScaledR = inScaledR < 0f ? 0f : 3f * inScaledR;
            float inScaledG = yp[i] - xp[i];
            inScaledG = inScaledG < 0f ? 0f : 3f * inScaledG;
            int intInR;
            float fracInR;
            if (inScaledR >= 7f) {
                intInR = 6;
                fracInR = 1f;
            } else {
                intInR = (int) inScaledR;
                fracInR = inScaledR - intInR;
            }
            int intInG;
            float fracInG;
            if (inScaledG >= 7f) {
                intInG = 6;
                fracInG = 1f;
            } else {
                intInG = (int) inScaledG;
                fracInG = inScaledG - intInG;
            }
            float sr = (lut[intInR + 1] - lut[intInR]) * fracInR + lut[intInR];
            float sg = (lut[intInG + 1] - lut[intInG]) * fracInG + lut[intInG];
            sr = Math.min(Math.max(sr, 0f), 1f);
            sg = Math.min(Math.max(sg, 0f), 1f);
            float nr = sr * (0.00171875f * noise[0][i] + 0.21828125f * noise[2][i]);
            float ng = sg * (0.00171875f * noise[1][i] + 0.21828125f * noise[2][i]);
            float nrg = nr + ng;
            yp[i] += nrg;
            xp[i] += baseCorrelationX * nrg + nr - ng;
            bp[i] += baseCorrelationB * nrg;
        }
    }

    private static int mirror(int v, int size) {
        while (v < 0 || v >= size) {
            v = v < 0 ? -v - 1 : 2 * size - 1 - v;
        }
        return v;
    }

    /** The XorShift128+ generator of 18181-1 annex G.6.2 (8 parallel lanes). */
    static final class XorShift128 {
        private final long[] state0 = new long[8];
        private final long[] state1 = new long[8];
        private final int[] batch = new int[16];
        private int batchPos = 16;

        XorShift128(long seed0, long seed1) {
            state0[0] = splitMix64(seed0 + 0x9e3779b97f4a7c15L);
            state1[0] = splitMix64(seed1 + 0x9e3779b97f4a7c15L);
            for (int i = 1; i < 8; i++) {
                state0[i] = splitMix64(state0[i - 1]);
                state1[i] = splitMix64(state1[i - 1]);
            }
        }

        void fill(int[] bits) {
            for (int i = 0; i < bits.length; i++) {
                if (batchPos >= 16) {
                    fillBatch();
                }
                bits[i] = batch[batchPos++];
            }
        }

        private void fillBatch() {
            for (int i = 0; i < 8; i++) {
                long a = state1[i];
                long b = state0[i];
                long c = a + b;
                state0[i] = a;
                b ^= b << 23;
                state1[i] = b ^ a ^ (b >>> 18) ^ (a >>> 5);
                batch[2 * i] = (int) c;
                batch[2 * i + 1] = (int) (c >>> 32);
            }
            batchPos = 0;
        }

        private static long splitMix64(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
    }
}
