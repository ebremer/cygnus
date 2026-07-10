package com.ebremer.cygnus.wavelet;

import com.ebremer.cygnus.t2.Band;
import com.ebremer.cygnus.t2.Resolution;
import com.ebremer.cygnus.t2.TileComponent;

/**
 * Inverse discrete wavelet transform (T.800 Annex F): multi-level 2-D
 * synthesis with the 5/3 reversible integer filter or the 9/7 irreversible
 * filter, using whole-sample symmetric extension. All indexing follows the
 * absolute tile-component coordinate system, so odd-origin regions get the
 * correct sample parity.
 */
public final class InverseDWT {

    // 9/7 lifting parameters (T.800 F.4.8.1)
    private static final float ALPHA = -1.586134342059924f;
    private static final float BETA = -0.052980118572961f;
    private static final float GAMMA = 0.882911075530934f;
    private static final float DELTA = 0.443506852043971f;
    private static final float K = 1.230174104914001f;
    private static final float INV_K = 1.0f / K;

    private InverseDWT() {
    }

    /**
     * Reconstructs the tile-component samples from its decoded subband
     * coefficients (reversible 5/3 path, exact integer arithmetic).
     *
     * @return row-major samples over the tile-component bounds
     */
    public static int[] reconstructInt(TileComponent tc) {
        return reconstructInt(tc, tc.resolutions.length - 1);
    }

    /**
     * Reconstructs up to resolution level {@code maxRes} only, yielding the
     * tile-component at a reduced size (its bounds are those of
     * {@code tc.resolutions[maxRes]}).
     */
    public static int[] reconstructInt(TileComponent tc, int maxRes) {
        Resolution r0 = tc.resolutions[0];
        int[] cur = bandInt(r0.bands[0]);
        for (int r = 1; r <= maxRes; r++) {
            Resolution res = tc.resolutions[r];
            Resolution prev = tc.resolutions[r - 1];
            int u0 = res.x0, u1 = res.x1, v0 = res.y0, v1 = res.y1;
            int wd = u1 - u0, ht = v1 - v0;
            int[] a = new int[Math.max(0, wd) * Math.max(0, ht)];
            interleaveInt(a, wd, u0, v0, cur, prev, res.bands);
            liftRowsInt(a, wd, ht, u0);
            liftColsInt(a, wd, ht, v0);
            cur = a;
        }
        return cur;
    }

    /**
     * Reconstructs the tile-component samples on the irreversible 9/7 path;
     * subband coefficients are dequantized by each band's step size.
     */
    public static float[] reconstructFloat(TileComponent tc) {
        return reconstructFloat(tc, tc.resolutions.length - 1);
    }

    /** Bounded-resolution variant of {@link #reconstructFloat(TileComponent)}. */
    public static float[] reconstructFloat(TileComponent tc, int maxRes) {
        Resolution r0 = tc.resolutions[0];
        float[] cur = bandFloat(r0.bands[0]);
        for (int r = 1; r <= maxRes; r++) {
            Resolution res = tc.resolutions[r];
            Resolution prev = tc.resolutions[r - 1];
            int u0 = res.x0, u1 = res.x1, v0 = res.y0, v1 = res.y1;
            int wd = u1 - u0, ht = v1 - v0;
            float[] a = new float[Math.max(0, wd) * Math.max(0, ht)];
            interleaveFloat(a, wd, u0, v0, cur, prev, res.bands);
            liftRowsFloat(a, wd, ht, u0);
            liftColsFloat(a, wd, ht, v0);
            cur = a;
        }
        return cur;
    }

    private static int[] bandInt(Band b) {
        return b.coeffs();
    }

    private static float[] bandFloat(Band b) {
        int[] q = b.coeffs();
        float[] out = new float[q.length];
        float step = b.stepSize * 0.5f; // coefficients carry one fractional bit
        for (int i = 0; i < q.length; i++) {
            out[i] = q[i] * step;
        }
        return out;
    }

    // ---- 2D_INTERLEAVE (F.3.3): band samples to absolute even/odd positions ----

    private static void interleaveInt(int[] a, int wd, int u0, int v0,
                                      int[] ll, Resolution prev, Band[] bands) {
        int pw = prev.x1 - prev.x0;
        for (int v = prev.y0; v < prev.y1; v++) {
            for (int u = prev.x0; u < prev.x1; u++) {
                a[(2 * v - v0) * wd + (2 * u - u0)] = ll[(v - prev.y0) * pw + (u - prev.x0)];
            }
        }
        placeInt(a, wd, u0, v0, bands[0], 1, 0); // HL
        placeInt(a, wd, u0, v0, bands[1], 0, 1); // LH
        placeInt(a, wd, u0, v0, bands[2], 1, 1); // HH
    }

    private static void placeInt(int[] a, int wd, int u0, int v0, Band b, int xo, int yo) {
        int[] q = b.coeffs();
        int bw = b.width();
        for (int v = b.y0; v < b.y1; v++) {
            for (int u = b.x0; u < b.x1; u++) {
                a[(2 * v + yo - v0) * wd + (2 * u + xo - u0)]
                        = q[(v - b.y0) * bw + (u - b.x0)];
            }
        }
    }

    private static void interleaveFloat(float[] a, int wd, int u0, int v0,
                                        float[] ll, Resolution prev, Band[] bands) {
        int pw = prev.x1 - prev.x0;
        for (int v = prev.y0; v < prev.y1; v++) {
            for (int u = prev.x0; u < prev.x1; u++) {
                a[(2 * v - v0) * wd + (2 * u - u0)] = ll[(v - prev.y0) * pw + (u - prev.x0)];
            }
        }
        placeFloat(a, wd, u0, v0, bands[0], 1, 0);
        placeFloat(a, wd, u0, v0, bands[1], 0, 1);
        placeFloat(a, wd, u0, v0, bands[2], 1, 1);
    }

    private static void placeFloat(float[] a, int wd, int u0, int v0, Band b, int xo, int yo) {
        int[] q = b.coeffs();
        int bw = b.width();
        float step = b.stepSize * 0.5f; // coefficients carry one fractional bit
        for (int v = b.y0; v < b.y1; v++) {
            for (int u = b.x0; u < b.x1; u++) {
                a[(2 * v + yo - v0) * wd + (2 * u + xo - u0)]
                        = q[(v - b.y0) * bw + (u - b.x0)] * step;
            }
        }
    }

    // ---- 1-D synthesis, 5/3 reversible (F.3.8.2) ----

    /** Mirrored (whole-sample symmetric) index into [0, n). */
    static int mirror(int j, int n) {
        if (n == 1) {
            return 0;
        }
        int period = 2 * (n - 1);
        j = Math.floorMod(j, period);
        return j < n ? j : period - j;
    }

    /**
     * In-place 1-D 5/3 synthesis on {@code t[pos + k*step]} for k in [0, n),
     * where the run covers absolute coordinates [i0, i0+n).
     *
     * <p>For runs longer than one sample, whole-sample symmetric extension
     * only ever reflects one step (j-1 &lt; 0 mirrors to 1; j+1 &gt;= n
     * mirrors to n-2), so the boundary samples are peeled and the interior
     * loops use direct indexing.</p>
     */
    static void inv53(int[] t, int pos, int step, int n, int i0) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[pos] >>= 1; // single high-pass sample (F.3.8.2.1.2)
            }
            return;
        }
        int even0 = i0 + (i0 & 1) - i0;     // first even offset: 0 or 1
        int odd0 = 1 - even0;               // first odd offset
        // X(2k) = Y(2k) - floor((Y(2k-1) + Y(2k+1) + 2) / 4)
        for (int j = even0; j < n; j += 2) {
            int lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            int hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] -= (lo + hi + 2) >> 2;
        }
        // X(2k+1) = Y(2k+1) + floor((X(2k) + X(2k+2)) / 2)
        for (int j = odd0; j < n; j += 2) {
            int lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            int hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] += (lo + hi) >> 1;
        }
    }

    private static void liftRowsInt(int[] a, int wd, int ht, int u0) {
        for (int y = 0; y < ht; y++) {
            inv53(a, y * wd, 1, wd, u0);
        }
    }

    private static void liftColsInt(int[] a, int wd, int ht, int v0) {
        for (int x = 0; x < wd; x++) {
            inv53(a, x, wd, ht, v0);
        }
    }

    // ---- 1-D synthesis, 9/7 irreversible (F.4.8.2) ----

    static void inv97(float[] t, int pos, int step, int n, int i0) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[pos] *= 0.5f;
            }
            return;
        }
        int even0 = i0 + (i0 & 1);       // first even absolute coordinate
        int odd0 = i0 + 1 - (i0 & 1);    // first odd absolute coordinate
        // step 1/2: undo the scaling (analysis scaled evens by 1/K, odds by K)
        for (int i = even0; i < i0 + n; i += 2) {
            t[pos + (i - i0) * step] *= K;
        }
        for (int i = odd0; i < i0 + n; i += 2) {
            t[pos + (i - i0) * step] *= INV_K;
        }
        // step 3: X(2k) -= delta * (X(2k-1) + X(2k+1))
        lift97(t, pos, step, n, even0 - i0, DELTA);
        // step 4: X(2k+1) -= gamma * (X(2k) + X(2k+2))
        lift97(t, pos, step, n, odd0 - i0, GAMMA);
        // step 5: X(2k) -= beta * (X(2k-1) + X(2k+1))
        lift97(t, pos, step, n, even0 - i0, BETA);
        // step 6: X(2k+1) -= alpha * (X(2k) + X(2k+2))
        lift97(t, pos, step, n, odd0 - i0, ALPHA);
    }

    private static void lift97(float[] t, int pos, int step, int n, int start, float coef) {
        for (int j = start; j < n; j += 2) {
            float lo = t[pos + (j == 0 ? 1 : j - 1) * step];
            float hi = t[pos + (j == n - 1 ? n - 2 : j + 1) * step];
            t[pos + j * step] -= coef * (lo + hi);
        }
    }

    private static void liftRowsFloat(float[] a, int wd, int ht, int u0) {
        for (int y = 0; y < ht; y++) {
            inv97(a, y * wd, 1, wd, u0);
        }
    }

    private static void liftColsFloat(float[] a, int wd, int ht, int v0) {
        for (int x = 0; x < wd; x++) {
            inv97(a, x, wd, ht, v0);
        }
    }
}
