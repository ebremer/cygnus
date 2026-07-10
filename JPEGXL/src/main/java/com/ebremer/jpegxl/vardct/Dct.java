package com.ebremer.jpegxl.vardct;

/**
 * Scaled DCT-II/III used by VarDCT, operating on flat row-major buffers.
 * The 1-D kernels match 18181-1 annex E: the inverse includes the sqrt(2)
 * scaling for non-DC bases and the forward divides by the length.
 */
public final class Dct {

    /** LUT[l][n][k] = sqrt(2) * cos(pi * (n+1) * (k+0.5) / 2^l). */
    private static final float[][][] LUT = new float[9][][];

    static {
        for (int l = 0; l < 9; l++) {
            int s = 1 << l;
            LUT[l] = new float[Math.max(s - 1, 0)][s];
            for (int n = 0; n + 1 < s; n++) {
                for (int k = 0; k < s; k++) {
                    LUT[l][n][k] = (float) (Math.sqrt(2) * Math.cos(Math.PI * (n + 1) * (k + 0.5) / s));
                }
            }
        }
    }

    private Dct() {
    }

    static void inverse1D(float[] src, int srcOff, float[] dst, int dstOff, int logLength) {
        int length = 1 << logLength;
        float dc = src[srcOff];
        for (int k = 0; k < length; k++) {
            dst[dstOff + k] = dc;
        }
        float[][] lut = LUT[logLength];
        for (int n = 1; n < length; n++) {
            float s = src[srcOff + n];
            float[] row = lut[n - 1];
            for (int k = 0; k < length; k++) {
                dst[dstOff + k] += s * row[k];
            }
        }
    }

    static void forward1D(float[] src, int srcOff, float[] dst, int dstOff, int logLength) {
        int length = 1 << logLength;
        float inv = 1f / length;
        float sum = 0;
        for (int x = 0; x < length; x++) {
            sum += src[srcOff + x];
        }
        dst[dstOff] = sum * inv;
        float[][] lut = LUT[logLength];
        for (int k = 1; k < length; k++) {
            float[] row = lut[k - 1];
            float d = 0;
            for (int n = 0; n < length; n++) {
                d += src[srcOff + n] * row[n];
            }
            dst[dstOff + k] = d * inv;
        }
    }

    /** dst (w x h, tight) = transpose of src (h x w, tight). */
    private static void transposeTight(float[] src, int h, int w, float[] dst) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = src[y * w + x];
            }
        }
    }

    /**
     * 2-D inverse DCT of an {@code height x width} coefficient block. Without
     * {@code transposed} the output is {@code height x width}; with it, the
     * output is {@code width x height} (rows and columns swapped).
     */
    public static void inverse2D(float[] src, int srcOff, int srcStride,
            float[] dst, int dstOff, int dstStride,
            int height, int width, float[] scratch0, float[] scratch1, boolean transposed) {
        int logH = log2(height);
        int logW = log2(width);
        if (transposed) {
            for (int y = 0; y < height; y++) {
                inverse1D(src, srcOff + y * srcStride, scratch1, y * width, logW);
            }
            transposeTight(scratch1, height, width, scratch0); // now width x height
            for (int x = 0; x < width; x++) {
                inverse1D(scratch0, x * height, dst, dstOff + x * dstStride, logH);
            }
        } else {
            for (int y = 0; y < height; y++) {
                System.arraycopy(src, srcOff + y * srcStride, scratch1, y * width, width);
            }
            transposeTight(scratch1, height, width, scratch0); // width x height
            for (int x = 0; x < width; x++) {
                inverse1D(scratch0, x * height, scratch1, x * height, logH);
            }
            transposeTight(scratch1, width, height, scratch0); // height x width
            for (int y = 0; y < height; y++) {
                inverse1D(scratch0, y * width, dst, dstOff + y * dstStride, logW);
            }
        }
    }

    /** 2-D forward DCT of an {@code height x width} spatial region. */
    public static void forward2D(float[] src, int srcOff, int srcStride,
            float[] dst, int dstOff, int dstStride,
            int height, int width, float[] scratch0, float[] scratch1) {
        int logH = log2(height);
        int logW = log2(width);
        for (int y = 0; y < height; y++) {
            forward1D(src, srcOff + y * srcStride, scratch0, y * width, logW);
        }
        transposeTight(scratch0, height, width, scratch1); // width x height
        for (int x = 0; x < width; x++) {
            forward1D(scratch1, x * height, scratch0, x * height, logH);
        }
        // scratch0 is width x height; transpose into dst
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                dst[dstOff + y * dstStride + x] = scratch0[x * height + y];
            }
        }
    }

    static int log2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }
}
