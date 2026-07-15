package com.ebremer.cygnus.jpegxl.vardct;

/**
 * Forward transforms for the two special 8×8 varblocks the encoder chooses,
 * DCT2 and DCT4 — the exact inverses of {@link Transforms}' decode paths. They
 * suit a block a plain DCT8 handles badly: DCT2 a piecewise-flat one (a
 * hierarchical 2→4→8 Hadamard, energy staying in a few coefficients across a
 * hard step), DCT4 one detailed at 4×4 (four independent 4×4 DCTs under a 2×2 DC
 * butterfly).
 *
 * <p>Both keep the 8×8 coefficient layout of a DCT8 — position (0,0) the DC, the
 * other 63 the AC — so the quantiser, tokeniser and dequantiser are shared; only
 * the transform and the coefficient order (and the quant weights) differ.
 */
public final class SmallDct {

    private SmallDct() {
    }

    /**
     * DCT2 forward: three inverse-butterfly stages at scales 8, 4, 2 — undoing the
     * decoder's 2, 4, 8. {@code block} and {@code coeffs} are 8×8, row-major.
     */
    public static void forwardDct2(float[] block, float[] coeffs) {
        float[] t1 = new float[64];
        float[] t2 = new float[64];
        invButterfly(block, t1, 8);
        invButterfly(t1, t2, 4);
        invButterfly(t2, coeffs, 2);
    }

    /**
     * DCT4 forward: each 4×4 quadrant is forward-DCT'd (transposed, as the decoder
     * inverts it transposed), its DC feeding a 2×2 inverse butterfly into the
     * coefficient block's corner and its AC laid at the strided positions the
     * decoder reads. {@code s0}/{@code s1} are ≥16-length scratch.
     */
    public static void forwardDct4(float[] block, float[] coeffs, float[] s0, float[] s1) {
        float[] quad = new float[16];
        float[] c4 = new float[16];
        float[] dc = new float[4];   // dc[y*2 + x] = quadrant (y,x) DC
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                for (int iy = 0; iy < 4; iy++) {
                    for (int ix = 0; ix < 4; ix++) {
                        quad[ix * 4 + iy] = block[(y * 4 + iy) * 8 + x * 4 + ix]; // transpose
                    }
                }
                Dct.forward2D(quad, 0, 4, c4, 0, 4, 4, 4, s0, s1);
                dc[y * 2 + x] = c4[0];
                for (int iy = 0; iy < 4; iy++) {
                    for (int ix = 0; ix < 4; ix++) {
                        if (iy == 0 && ix == 0) {
                            continue;
                        }
                        coeffs[(y + iy * 2) * 8 + x + ix * 2] = c4[iy * 4 + ix];
                    }
                }
            }
        }
        // the four quadrant DCs sit at butterflied positions s3[0],s3[1],s3[8],s3[9];
        // invert that 2×2 butterfly into the coefficient corner
        float a = dc[0];
        float b = dc[1];
        float c = dc[2];
        float d = dc[3];
        coeffs[0] = (a + b + c + d) * 0.25f;
        coeffs[1] = (a + b - c - d) * 0.25f;
        coeffs[8] = (a - b + c - d) * 0.25f;
        coeffs[9] = (a - b - c + d) * 0.25f;
    }

    /**
     * DCT4x8 forward (4 tall, 8 wide): two 4x8 halves stacked, each forward-DCT'd,
     * their DCs feeding a 1x2 butterfly into the coefficient corner and their AC
     * laid at the strided rows the decoder reads. {@code s0}/{@code s1} are ≥32.
     */
    public static void forwardDct4x8(float[] block, float[] coeffs, float[] s0, float[] s1) {
        float[] half = new float[32];
        float[] c = new float[32];
        float[] dc = new float[2];
        for (int y = 0; y < 2; y++) {
            for (int r = 0; r < 4; r++) {
                for (int col = 0; col < 8; col++) {
                    half[r * 8 + col] = block[(y * 4 + r) * 8 + col];
                }
            }
            Dct.forward2D(half, 0, 8, c, 0, 8, 4, 8, s0, s1);
            dc[y] = c[0];
            for (int iy = 0; iy < 4; iy++) {
                for (int ix = 0; ix < 8; ix++) {
                    if (iy == 0 && ix == 0) {
                        continue;
                    }
                    coeffs[(y + iy * 2) * 8 + ix] = c[iy * 8 + ix];
                }
            }
        }
        coeffs[0] = (dc[0] + dc[1]) * 0.5f;   // invert the 1x2 DC butterfly
        coeffs[8] = (dc[0] - dc[1]) * 0.5f;
    }

    /**
     * DCT8x4 forward (8 tall, 4 wide): two 8x4 halves side by side, each
     * forward-DCT'd on its transpose (the decoder inverts it transposed), the same
     * 1x2 DC butterfly and strided AC layout as {@link #forwardDct4x8}.
     */
    public static void forwardDct8x4(float[] block, float[] coeffs, float[] s0, float[] s1) {
        float[] half = new float[32];
        float[] c = new float[32];
        float[] dc = new float[2];
        for (int x = 0; x < 2; x++) {
            for (int col = 0; col < 4; col++) {
                for (int r = 0; r < 8; r++) {
                    half[col * 8 + r] = block[r * 8 + x * 4 + col]; // transpose 8x4 -> 4x8
                }
            }
            Dct.forward2D(half, 0, 8, c, 0, 8, 4, 8, s0, s1);
            dc[x] = c[0];
            for (int iy = 0; iy < 4; iy++) {
                for (int ix = 0; ix < 8; ix++) {
                    if (iy == 0 && ix == 0) {
                        continue;
                    }
                    coeffs[(x + iy * 2) * 8 + ix] = c[iy * 8 + ix];
                }
            }
        }
        coeffs[0] = (dc[0] + dc[1]) * 0.5f;
        coeffs[8] = (dc[0] - dc[1]) * 0.5f;
    }

    /**
     * Undoes {@link Transforms}' 2×2 Hadamard butterfly over the {@code s}×{@code
     * s} corner: reads the interleaved output, writes the quadrant input, scaled
     * by 1/4. The rest of the 8×8 passes through.
     */
    private static void invButterfly(float[] src, float[] dst, int s) {
        System.arraycopy(src, 0, dst, 0, 64);
        int num = s / 2;
        for (int iy = 0; iy < num; iy++) {
            for (int ix = 0; ix < num; ix++) {
                float w = src[2 * iy * 8 + 2 * ix];
                float x = src[2 * iy * 8 + 2 * ix + 1];
                float y = src[(2 * iy + 1) * 8 + 2 * ix];
                float z = src[(2 * iy + 1) * 8 + 2 * ix + 1];
                dst[iy * 8 + ix] = (w + x + y + z) * 0.25f;
                dst[iy * 8 + ix + num] = (w + x - y - z) * 0.25f;
                dst[(iy + num) * 8 + ix] = (w - x + y - z) * 0.25f;
                dst[(iy + num) * 8 + ix + num] = (w - x - y + z) * 0.25f;
            }
        }
    }
}
