package com.ebremer.cygnus.jpeg2000.wavelet;

/**
 * Forward discrete wavelet transform (T.800 Annex F analysis): the exact
 * inverse of {@link InverseDWT}, with the 5/3 reversible integer filter or
 * the 9/7 irreversible filter and whole-sample symmetric extension. All
 * indexing follows the absolute tile-component coordinate system, so
 * odd-origin regions get the correct sample parity.
 *
 * <p>One decomposition level analyzes the interleaved array in place
 * (columns first, then rows - the reverse of the synthesis order); the
 * caller deinterleaves the result into the LL/HL/LH/HH subbands.</p>
 */
public final class ForwardDWT {

    // 9/7 lifting parameters (T.800 F.4.8.1)
    private static final float ALPHA = -1.586134342059924f;
    private static final float BETA = -0.052980118572961f;
    private static final float GAMMA = 0.882911075530934f;
    private static final float DELTA = 0.443506852043971f;
    private static final float K = 1.230174104914001f;
    private static final float INV_K = 1.0f / K;

    private ForwardDWT() {
    }

    /** One 2-D analysis level in place: 1-D over columns, then over rows. */
    public static void analyzeInt(int[] a, int wd, int ht, int u0, int v0) {
        for (int x = 0; x < wd; x++) {
            fwd53(a, x, wd, ht, v0);
        }
        for (int y = 0; y < ht; y++) {
            fwd53(a, y * wd, 1, wd, u0);
        }
    }

    /** One 2-D analysis level in place: 1-D over columns, then over rows. */
    public static void analyzeFloat(float[] a, int wd, int ht, int u0, int v0) {
        for (int x = 0; x < wd; x++) {
            fwd97(a, x, wd, ht, v0);
        }
        for (int y = 0; y < ht; y++) {
            fwd97(a, y * wd, 1, wd, u0);
        }
    }

    // ---- 1-D analysis, 5/3 reversible (F.3.8.2 inverted) ----

    /**
     * In-place 1-D 5/3 analysis on {@code t[pos + k*step]} for k in [0, n),
     * where the run covers absolute coordinates [i0, i0+n).
     */
    static void fwd53(int[] t, int pos, int step, int n, int i0) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[pos] <<= 1; // single high-pass sample (F.3.8.2.1.2)
            }
            return;
        }
        int even0 = i0 & 1;            // offset of the first even coordinate
        int odd0 = 1 - even0;          // offset of the first odd coordinate
        // Y(2k+1) = X(2k+1) - floor((X(2k) + X(2k+2)) / 2)
        for (int j = odd0; j < n; j += 2) {
            int lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            int hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] -= (lo + hi) >> 1;
        }
        // Y(2k) = X(2k) + floor((Y(2k-1) + Y(2k+1) + 2) / 4)
        for (int j = even0; j < n; j += 2) {
            int lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            int hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] += (lo + hi + 2) >> 2;
        }
    }

    // ---- 1-D analysis, 9/7 irreversible (F.4.8.2 inverted) ----

    static void fwd97(float[] t, int pos, int step, int n, int i0) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[pos] *= 2.0f;
            }
            return;
        }
        int even0 = i0 + (i0 & 1);       // first even absolute coordinate
        int odd0 = i0 + 1 - (i0 & 1);    // first odd absolute coordinate
        // lifting steps in the reverse order of synthesis, signs flipped
        lift97(t, pos, step, n, odd0 - i0, ALPHA);
        lift97(t, pos, step, n, even0 - i0, BETA);
        lift97(t, pos, step, n, odd0 - i0, GAMMA);
        lift97(t, pos, step, n, even0 - i0, DELTA);
        // scaling: evens (low-pass) by 1/K, odds (high-pass) by K
        for (int i = even0; i < i0 + n; i += 2) {
            t[pos + (i - i0) * step] *= INV_K;
        }
        for (int i = odd0; i < i0 + n; i += 2) {
            t[pos + (i - i0) * step] *= K;
        }
    }

    private static void lift97(float[] t, int pos, int step, int n, int start, float coef) {
        for (int j = start; j < n; j += 2) {
            float lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            float hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] += coef * (lo + hi);
        }
    }
}
