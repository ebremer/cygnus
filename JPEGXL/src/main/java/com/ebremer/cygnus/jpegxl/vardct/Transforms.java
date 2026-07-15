package com.ebremer.cygnus.jpegxl.vardct;

/** Inverse varblock transforms: plain DCT plus the special 8x8 methods. */
public final class Transforms {

    private static final float[][] AFV_BASIS = {
        {0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f,
         0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f},
        {0.876902929799142f, 0.2206518106944235f, -0.10140050393753763f, -0.1014005039375375f,
         0.2206518106944236f, -0.10140050393753777f, -0.10140050393753772f, -0.10140050393753763f,
         -0.10140050393753758f, -0.10140050393753769f, -0.1014005039375375f, -0.10140050393753768f,
         -0.10140050393753768f, -0.10140050393753759f, -0.10140050393753763f, -0.10140050393753741f},
        {0.0f, 0.0f, 0.40670075830260755f, 0.44444816619734445f, 0.0f, 0.0f, 0.19574399372042936f,
         0.2929100136981264f, -0.40670075830260716f, -0.19574399372042872f, 0.0f, 0.11379074460448091f,
         -0.44444816619734384f, -0.29291001369812636f, -0.1137907446044814f, 0.0f},
        {0.0f, 0.0f, -0.21255748058288748f, 0.3085497062849767f, 0.0f, 0.4706702258572536f,
         -0.1621205195722993f, 0.0f, -0.21255748058287047f, -0.16212051957228327f, -0.47067022585725277f,
         -0.1464291867126764f, 0.3085497062849487f, 0.0f, -0.14642918671266536f, 0.4251149611657548f},
        {0.0f, -0.7071067811865474f, 0.0f, 0.0f, 0.7071067811865476f, 0.0f, 0.0f, 0.0f,
         0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        {-0.4105377591765233f, 0.6235485373547691f, -0.06435071657946274f, -0.06435071657946266f,
         0.6235485373547694f, -0.06435071657946284f, -0.0643507165794628f, -0.06435071657946274f,
         -0.06435071657946272f, -0.06435071657946279f, -0.06435071657946266f, -0.06435071657946277f,
         -0.06435071657946277f, -0.06435071657946273f, -0.06435071657946274f, -0.0643507165794626f},
        {0.0f, 0.0f, -0.4517556589999482f, 0.15854503551840063f, 0.0f, -0.04038515160822202f,
         0.0074182263792423875f, 0.39351034269210167f, -0.45175565899994635f, 0.007418226379244351f,
         0.1107416575309343f, 0.08298163094882051f, 0.15854503551839705f, 0.3935103426921022f,
         0.0829816309488214f, -0.45175565899994796f},
        {0.0f, 0.0f, -0.304684750724869f, 0.5112616136591823f, 0.0f, 0.0f, -0.290480129728998f,
         -0.06578701549142804f, 0.304684750724884f, 0.2904801297290076f, 0.0f, -0.23889773523344604f,
         -0.5112616136592012f, 0.06578701549142545f, 0.23889773523345467f, 0.0f},
        {0.0f, 0.0f, 0.3017929516615495f, 0.25792362796341184f, 0.0f, 0.16272340142866204f,
         0.09520022653475037f, 0.0f, 0.3017929516615503f, 0.09520022653475055f, -0.16272340142866173f,
         -0.35312385449816297f, 0.25792362796341295f, 0.0f, -0.3531238544981624f, -0.6035859033230976f},
        {0.0f, 0.0f, 0.40824829046386274f, 0.0f, 0.0f, 0.0f, 0.0f, -0.4082482904638628f,
         -0.4082482904638635f, 0.0f, 0.0f, -0.40824829046386296f, 0.0f, 0.4082482904638634f,
         0.408248290463863f, 0.0f},
        {0.0f, 0.0f, 0.1747866975480809f, 0.0812611176717539f, 0.0f, 0.0f, -0.3675398009862027f,
         -0.307882213957909f, -0.17478669754808135f, 0.3675398009862011f, 0.0f, 0.4826689115059883f,
         -0.08126111767175039f, 0.30788221395790305f, -0.48266891150598584f, 0.0f},
        {0.0f, 0.0f, -0.21105601049335784f, 0.18567180916109802f, 0.0f, 0.0f, 0.49215859013738733f,
         -0.38525013709251915f, 0.21105601049335806f, -0.49215859013738905f, 0.0f, 0.17419412659916217f,
         -0.18567180916109904f, 0.3852501370925211f, -0.1741941265991621f, 0.0f},
        {0.0f, 0.0f, -0.14266084808807264f, -0.3416446842253372f, 0.0f, 0.7367497537172237f,
         0.24627107722075148f, -0.08574019035519306f, -0.14266084808807344f, 0.24627107722075137f,
         0.14883399227113567f, -0.04768680350229251f, -0.3416446842253373f, -0.08574019035519267f,
         -0.047686803502292804f, -0.14266084808807242f},
        {0.0f, 0.0f, -0.13813540350758585f, 0.3302282550303788f, 0.0f, 0.08755115000587084f,
         -0.07946706605909573f, -0.4613374887461511f, -0.13813540350758294f, -0.07946706605910261f,
         0.49724647109535086f, 0.12538059448563663f, 0.3302282550303805f, -0.4613374887461554f,
         0.12538059448564315f, -0.13813540350758452f},
        {0.0f, 0.0f, -0.17437602599651067f, 0.0702790691196284f, 0.0f, -0.2921026642334881f,
         0.3623817333531167f, 0.0f, -0.1743760259965108f, 0.36238173335311646f, 0.29210266423348785f,
         -0.4326608024727445f, 0.07027906911962818f, 0.0f, -0.4326608024727457f, 0.34875205199302267f},
        {0.0f, 0.0f, 0.11354987314994337f, -0.07417504595810355f, 0.0f, 0.19402893032594343f,
         -0.435190496523228f, 0.21918684838857466f, 0.11354987314994257f, -0.4351904965232251f,
         0.5550443808910661f, -0.25468277124066463f, -0.07417504595810233f, 0.2191868483885728f,
         -0.25468277124066413f, 0.1135498731499429f},
    };

    private Transforms() {
    }

    /**
     * Inverts one varblock. {@code coeffs} is the group coefficient plane
     * with row stride {@code cStride}; the output is written at
     * {@code outOff} with {@code outStride}.
     */
    public static void invert(TransformType tt, float[] coeffs, int cStride, int ppgY, int ppgX,
            float[] out, int outOff, int outStride,
            float[] s0, float[] s1, float[] s2, float[] s3) {
        int cOff = ppgY * cStride + ppgX;
        switch (tt.method) {
            case DCT -> Dct.inverse2D(coeffs, cOff, cStride, out, outOff, outStride,
                    tt.pixelHeight, tt.pixelWidth, s0, s1, false);
            case DCT8_4 -> {
                float coeff0 = coeffs[cOff];
                float coeff1 = coeffs[cOff + cStride];
                float[] lfs = {coeff0 + coeff1, coeff0 - coeff1};
                for (int x = 0; x < 2; x++) {
                    s2[0] = lfs[x];
                    for (int iy = 0; iy < 4; iy++) {
                        for (int ix = (iy == 0 ? 1 : 0); ix < 8; ix++) {
                            s2[iy * 8 + ix] = coeffs[(ppgY + x + iy * 2) * cStride + ppgX + ix];
                        }
                    }
                    // 4x8 coefficients, transposed output: 8 rows x 4 columns
                    Dct.inverse2D(s2, 0, 8, out, outOff + (x << 2), outStride,
                            4, 8, s0, s1, true);
                }
            }
            case DCT4_8 -> {
                float coeff0 = coeffs[cOff];
                float coeff1 = coeffs[cOff + cStride];
                float[] lfs = {coeff0 + coeff1, coeff0 - coeff1};
                for (int y = 0; y < 2; y++) {
                    s2[0] = lfs[y];
                    for (int iy = 0; iy < 4; iy++) {
                        for (int ix = (iy == 0 ? 1 : 0); ix < 8; ix++) {
                            s2[iy * 8 + ix] = coeffs[(ppgY + y + iy * 2) * cStride + ppgX + ix];
                        }
                    }
                    Dct.inverse2D(s2, 0, 8, out, outOff + (y << 2) * outStride, outStride,
                            4, 8, s0, s1, false);
                }
            }
            case AFV -> invertAFV(tt, coeffs, cStride, ppgY, ppgX, out, outOff, outStride,
                    s0, s1, s2, s3);
            case DCT2 -> {
                auxDCT2(coeffs, cOff, cStride, s2, 0, 8, 2);
                auxDCT2(s2, 0, 8, s3, 0, 8, 4);
                auxDCT2Into(s3, 0, 8, out, outOff, outStride, 8);
            }
            case HORNUSS -> {
                auxDCT2(coeffs, cOff, cStride, s3, 0, 8, 2);
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        float blockLF = s3[y * 8 + x];
                        float residual = 0f;
                        for (int iy = 0; iy < 4; iy++) {
                            for (int ix = (iy == 0 ? 1 : 0); ix < 4; ix++) {
                                residual += coeffs[(ppgY + y + iy * 2) * cStride + ppgX + x + ix * 2];
                            }
                        }
                        float center = blockLF - residual * 0.0625f;
                        s2[(4 * y + 1) * 8 + 4 * x + 1] = center;
                        for (int iy = 0; iy < 4; iy++) {
                            for (int ix = 0; ix < 4; ix++) {
                                if (ix == 1 && iy == 1) {
                                    continue;
                                }
                                s2[(y * 4 + iy) * 8 + x * 4 + ix] =
                                        coeffs[(ppgY + y + iy * 2) * cStride + ppgX + x + ix * 2] + center;
                            }
                        }
                        s2[(4 * y) * 8 + 4 * x] =
                                coeffs[(ppgY + y + 2) * cStride + ppgX + x + 2] + center;
                    }
                }
                for (int y = 0; y < 8; y++) {
                    System.arraycopy(s2, y * 8, out, outOff + y * outStride, 8);
                }
            }
            case DCT4 -> {
                auxDCT2(coeffs, cOff, cStride, s3, 0, 8, 2);
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        s2[0] = s3[y * 8 + x];
                        for (int iy = 0; iy < 4; iy++) {
                            for (int ix = (iy == 0 ? 1 : 0); ix < 4; ix++) {
                                s2[iy * 4 + ix] =
                                        coeffs[(ppgY + y + iy * 2) * cStride + ppgX + x + ix * 2];
                            }
                        }
                        Dct.inverse2D(s2, 0, 4, out,
                                outOff + (y << 2) * outStride + (x << 2), outStride,
                                4, 4, s0, s1, true);
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    /** The DCT2 butterfly stage: copies the 8x8 block then rebuilds the s x s corner. */
    private static void auxDCT2(float[] src, int srcOff, int srcStride,
            float[] dst, int dstOff, int dstStride, int s) {
        if (dst == null) {
            return;
        }
        for (int y = 0; y < 8; y++) {
            System.arraycopy(src, srcOff + y * srcStride, dst, dstOff + y * dstStride, 8);
        }
        butterfly(src, srcOff, srcStride, dst, dstOff, dstStride, s);
    }

    private static void auxDCT2Into(float[] src, int srcOff, int srcStride,
            float[] dst, int dstOff, int dstStride, int s) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                dst[dstOff + y * dstStride + x] = src[srcOff + y * srcStride + x];
            }
        }
        butterfly(src, srcOff, srcStride, dst, dstOff, dstStride, s);
    }

    private static void butterfly(float[] src, int srcOff, int srcStride,
            float[] dst, int dstOff, int dstStride, int s) {
        int num = s / 2;
        for (int iy = 0; iy < num; iy++) {
            for (int ix = 0; ix < num; ix++) {
                float c00 = src[srcOff + iy * srcStride + ix];
                float c01 = src[srcOff + iy * srcStride + ix + num];
                float c10 = src[srcOff + (iy + num) * srcStride + ix];
                float c11 = src[srcOff + (iy + num) * srcStride + ix + num];
                dst[dstOff + (iy * 2) * dstStride + ix * 2] = c00 + c01 + c10 + c11;
                dst[dstOff + (iy * 2) * dstStride + ix * 2 + 1] = c00 + c01 - c10 - c11;
                dst[dstOff + (iy * 2 + 1) * dstStride + ix * 2] = c00 - c01 + c10 - c11;
                dst[dstOff + (iy * 2 + 1) * dstStride + ix * 2 + 1] = c00 - c01 - c10 + c11;
            }
        }
    }

    private static void invertAFV(TransformType tt, float[] coeffs, int cStride,
            int ppgY, int ppgX, float[] out, int outOff, int outStride,
            float[] s0, float[] s1, float[] s2, float[] s3) {
        int cOff = ppgY * cStride + ppgX;
        int flipY = tt == TransformType.AFV2 || tt == TransformType.AFV3 ? 1 : 0;
        int flipX = tt == TransformType.AFV1 || tt == TransformType.AFV3 ? 1 : 0;

        // AFV corner: 4x4 from the 16-basis expansion
        float[] block = s2; // 4x4 input in [0..15]
        block[0] = (coeffs[cOff] + coeffs[cOff + cStride] + coeffs[cOff + 1]) * 4f;
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = (iy == 0 ? 1 : 0); ix < 4; ix++) {
                block[iy * 4 + ix] = coeffs[(ppgY + iy * 2) * cStride + ppgX + ix * 2];
            }
        }
        float[] samples = s3;
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                float sample = 0f;
                for (int j = 0; j < 16; j++) {
                    sample += block[j] * AFV_BASIS[j][iy * 4 + ix];
                }
                samples[iy * 4 + ix] = sample;
            }
        }
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                out[outOff + (flipY * 4 + iy) * outStride + flipX * 4 + ix] =
                        samples[(flipY == 1 ? 3 - iy : iy) * 4 + (flipX == 1 ? 3 - ix : ix)];
            }
        }

        // 4x4 DCT corner
        block[0] = coeffs[cOff] + coeffs[cOff + cStride] - coeffs[cOff + 1];
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = (iy == 0 ? 1 : 0); ix < 4; ix++) {
                block[iy * 4 + ix] = coeffs[(ppgY + iy * 2) * cStride + ppgX + ix * 2 + 1];
            }
        }
        float[] idct = samples;
        Dct.inverse2D(block, 0, 4, idct, 0, 4, 4, 4, s0, s1, false);
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                // transposed intentionally
                out[outOff + (flipY * 4 + iy) * outStride + (flipX == 1 ? 0 : 4) + ix] =
                        idct[ix * 4 + iy];
            }
        }

        // 4x8 DCT bottom/top half
        float[] block48 = new float[32];
        block48[0] = coeffs[cOff] - coeffs[cOff + cStride];
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = (iy == 0 ? 1 : 0); ix < 8; ix++) {
                block48[iy * 8 + ix] = coeffs[(ppgY + 1 + iy * 2) * cStride + ppgX + ix];
            }
        }
        float[] idct48 = new float[32];
        Dct.inverse2D(block48, 0, 8, idct48, 0, 8, 4, 8, s0, s1, false);
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 8; ix++) {
                out[outOff + ((flipY == 1 ? 0 : 4) + iy) * outStride + ix] = idct48[iy * 8 + ix];
            }
        }
    }

    /**
     * HORNUSS forward: the exact inverse of the decode path — a 2x2 grid of 4x4
     * cells, each modelled as a centre value with the rest as residuals off it.
     * {@code block} and {@code coeffs} are 8x8, row-major.
     */
    public static void forwardHornuss(float[] block, float[] coeffs) {
        float[] blockLf = new float[4];   // per cell (Y,X) -> Y*2 + X
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                float centre = block[(4 * y + 1) * 8 + 4 * x + 1];
                float residual = 0f;
                for (int iy = 0; iy < 4; iy++) {
                    for (int ix = 0; ix < 4; ix++) {
                        if (iy == 0 && ix == 0) {
                            continue;   // the cell's DC, carried by blockLf below
                        }
                        // the (0,0) pixel carries the (1,1) coefficient; the rest map straight
                        float v = (iy == 1 && ix == 1)
                                ? block[4 * y * 8 + 4 * x] - centre
                                : block[(4 * y + iy) * 8 + 4 * x + ix] - centre;
                        coeffs[(y + iy * 2) * 8 + x + ix * 2] = v;
                        residual += v;
                    }
                }
                blockLf[y * 2 + x] = centre + residual * 0.0625f;
            }
        }
        // invert the 2x2 Hadamard butterfly of the DC corner
        float a = blockLf[0];
        float b = blockLf[1];
        float c = blockLf[2];
        float d = blockLf[3];
        coeffs[0] = (a + b + c + d) * 0.25f;
        coeffs[1] = (a + b - c - d) * 0.25f;
        coeffs[8] = (a - b + c - d) * 0.25f;
        coeffs[9] = (a - b - c + d) * 0.25f;
    }

    /**
     * AFV forward for one of the four variants: the exact inverse of {@link
     * #invertAFV}. The block is the AFV 4x4 corner (projected onto the orthonormal
     * basis), a 4x4 DCT corner and a 4x8 DCT half; their three DCs are a coupled
     * combination of {@code coeffs[0], coeffs[8], coeffs[1]}, solved back here.
     * {@code s0}/{@code s1} are ≥32 scratch.
     */
    public static void forwardAfv(TransformType tt, float[] block, float[] coeffs,
            float[] s0, float[] s1) {
        int flipY = tt == TransformType.AFV2 || tt == TransformType.AFV3 ? 1 : 0;
        int flipX = tt == TransformType.AFV1 || tt == TransformType.AFV3 ? 1 : 0;

        // AFV 4x4 corner: undo the sample flips, project onto the basis
        float[] samples = new float[16];
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                samples[(flipY == 1 ? 3 - iy : iy) * 4 + (flipX == 1 ? 3 - ix : ix)] =
                        block[(flipY * 4 + iy) * 8 + flipX * 4 + ix];
            }
        }
        float[] afv = new float[16];
        for (int j = 0; j < 16; j++) {
            float sum = 0f;
            for (int k = 0; k < 16; k++) {
                sum += AFV_BASIS[j][k] * samples[k];
            }
            afv[j] = sum;
        }
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                if (iy == 0 && ix == 0) {
                    continue;
                }
                coeffs[iy * 2 * 8 + ix * 2] = afv[iy * 4 + ix];
            }
        }

        // 4x4 DCT corner: undo the transpose, forward-transform
        float[] tq = new float[16];
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                tq[ix * 4 + iy] = block[(flipY * 4 + iy) * 8 + (flipX == 1 ? 0 : 4) + ix];
            }
        }
        float[] c44 = new float[16];
        Dct.forward2D(tq, 0, 4, c44, 0, 4, 4, 4, s0, s1);
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 4; ix++) {
                if (iy == 0 && ix == 0) {
                    continue;
                }
                coeffs[iy * 2 * 8 + ix * 2 + 1] = c44[iy * 4 + ix];
            }
        }

        // 4x8 DCT half
        float[] r48 = new float[32];
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 8; ix++) {
                r48[iy * 8 + ix] = block[((flipY == 1 ? 0 : 4) + iy) * 8 + ix];
            }
        }
        float[] c48 = new float[32];
        Dct.forward2D(r48, 0, 8, c48, 0, 8, 4, 8, s0, s1);
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 8; ix++) {
                if (iy == 0 && ix == 0) {
                    continue;
                }
                coeffs[(1 + iy * 2) * 8 + ix] = c48[iy * 8 + ix];
            }
        }

        // solve the coupled DCs: afv/4 = c0+c8+c1, dc44 = c0+c8-c1, dc48 = c0-c8
        float sum08 = (afv[0] / 4f + c44[0]) * 0.5f;
        coeffs[1] = (afv[0] / 4f - c44[0]) * 0.5f;
        coeffs[0] = (sum08 + c48[0]) * 0.5f;
        coeffs[8] = (sum08 - c48[0]) * 0.5f;
    }
}
