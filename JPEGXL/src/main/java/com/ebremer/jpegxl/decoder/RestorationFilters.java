package com.ebremer.jpegxl.decoder;

import com.ebremer.jpegxl.codestream.RestorationFilter;

/** Gaborish smoothing and the edge-preserving filter, applied on colour planes. */
final class RestorationFilters {

    private RestorationFilters() {
    }

    /**
     * 3x3 gaborish convolution with normalized weights and edge clamping.
     * Only rows [yFrom, yTo) are produced; a region decode leaves the rest
     * of the output zero (those rows are never emitted). Neighbours clamp at
     * the visible frame bounds ({@code mirrorW} x {@code mirrorH}), not the
     * padded plane edge, matching libjxl's border handling.
     */
    static void gaborish(RestorationFilter rf, float[][] planes, int width, int height,
            int mirrorW, int mirrorH, int yFrom, int yTo) throws java.io.IOException {
        int lastX = mirrorW - 1;
        int yEnd = Math.min(yTo, mirrorH);
        for (int c = 0; c < planes.length; c++) {
            float w1 = rf.gab1Weights[c];
            float w2 = rf.gab2Weights[c];
            float mult = 1f / (1f + 4f * (w1 + w2));
            float base = mult;
            float adjW = w1 * mult;
            float diagW = w2 * mult;
            float[] in = planes[c];
            float[] out = new float[in.length];
            JxlDecoder.parallelFor(Math.max(0, yEnd - yFrom), yRel -> {
                int y = yFrom + yRel;
                int row = y * width;
                int rowN = (y == 0 ? y : y - 1) * width;
                int rowS = (y + 1 >= mirrorH ? y : y + 1) * width;
                for (int x = 0; x < mirrorW; x++) {
                    int w = x == 0 ? x : x - 1;
                    int e = x == lastX ? x : x + 1;
                    float adj = in[row + w] + in[row + e] + in[rowN + x] + in[rowS + x];
                    float diag = in[rowN + w] + in[rowN + e] + in[rowS + w] + in[rowS + e];
                    out[row + x] = base * in[row + x] + adjW * adj + diagW * diag;
                }
            });
            planes[c] = out;
        }
    }

    private static final int[][] CROSS = {
        {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1},
    };
    private static final int[][] DOUBLE_CROSS = {
        {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1},
        {1, -1}, {1, 1}, {-1, 1}, {-1, -1},
        {-2, 0}, {2, 0}, {0, -2}, {0, 2},
    };

    /**
     * Edge-preserving filter. {@code inverseSigma} is per 8x8 block for VarDCT
     * frames, or null for modular frames (a constant sigma is used instead).
     * Only rows [yFrom, yTo) are produced; with three iterations and a kernel
     * reach of at most 3 rows, pixels further than 9 rows inside the band are
     * identical to a full-frame run.
     */
    static void epf(RestorationFilter rf, float[][] planes, int width, int height,
            int mirrorW, int mirrorH, float[] inverseSigmaPerBlock, int blockStride,
            float sigmaForModular, int yFrom, int yTo) throws java.io.IOException {
        float stepMultiplier = (float) (1.65 * 4 * (1 - Math.sqrt(0.5)));
        float invModularSigma = 1f / sigmaForModular;
        int colors = planes.length;
        int yEndAll = Math.min(yTo, mirrorH);

        float[][] input = planes;
        float[][] output = new float[colors][];
        for (int c = 0; c < colors; c++) {
            output[c] = new float[width * height];
        }

        for (int iter = 0; iter < 3; iter++) {
            if (iter == 0 && rf.epfIterations < 3) {
                continue;
            }
            if (iter == 2 && rf.epfIterations < 2) {
                break;
            }
            float sigmaScale0 = stepMultiplier;
            if (iter == 0) {
                sigmaScale0 *= rf.epfPass0SigmaScale;
            } else if (iter == 2) {
                sigmaScale0 *= rf.epfPass2SigmaScale;
            }
            float sigmaScale = sigmaScale0;
            int[][] crossList = iter == 0 ? DOUBLE_CROSS : CROSS;
            int iterF = iter;
            float[][] src = input;
            float[][] dst = output;
            JxlDecoder.parallelFor(Math.max(0, yEndAll - yFrom), yRel -> {
                int y = yFrom + yRel;
                float[] sums = new float[colors];
                for (int x = 0; x < mirrorW; x++) {
                    float invSigma = inverseSigmaPerBlock != null
                            ? inverseSigmaPerBlock[(y >> 3) * blockStride + (x >> 3)]
                            : invModularSigma;
                    int idx = y * width + x;
                    if (invSigma != invSigma || invSigma > (1f / 0.3f)) {
                        for (int c = 0; c < colors; c++) {
                            dst[c][idx] = src[c][idx];
                        }
                        continue;
                    }
                    float sumWeights = 0f;
                    java.util.Arrays.fill(sums, 0f);
                    for (int[] cross : crossList) {
                        float dist = iterF == 2
                                ? distance2(src, colors, rf, y, x, cross, width, mirrorW, mirrorH)
                                : distance1(src, colors, rf, y, x, cross, width, mirrorW, mirrorH);
                        float weight = weight(rf, sigmaScale, dist, invSigma, y, x);
                        sumWeights += weight;
                        int my = mirror(y + cross[0], mirrorH);
                        int mx = mirror(x + cross[1], mirrorW);
                        for (int c = 0; c < colors; c++) {
                            sums[c] += src[c][my * width + mx] * weight;
                        }
                    }
                    for (int c = 0; c < colors; c++) {
                        dst[c][idx] = sums[c] / sumWeights;
                    }
                }
            });
            for (int c = 0; c < colors; c++) {
                float[] tmp = input[c];
                input[c] = output[c];
                output[c] = tmp;
            }
        }
        System.arraycopy(input, 0, planes, 0, colors);
    }

    private static float distance1(float[][] buf, int colors, RestorationFilter rf,
            int by, int bx, int[] dCross, int width, int mirrorW, int mirrorH) {
        float dist = 0f;
        for (int c = 0; c < 3; c++) {
            float[] p = buf[colors == 1 ? 0 : c];
            float scale = rf.epfChannelScale[c];
            for (int[] cross : CROSS) {
                int py = mirror(by + cross[0], mirrorH);
                int px = mirror(bx + cross[1], mirrorW);
                int dy = mirror(by + dCross[0] + cross[0], mirrorH);
                int dx = mirror(bx + dCross[1] + cross[1], mirrorW);
                dist += Math.abs(p[py * width + px] - p[dy * width + dx]) * scale;
            }
        }
        return dist;
    }

    private static float distance2(float[][] buf, int colors, RestorationFilter rf,
            int by, int bx, int[] cross, int width, int mirrorW, int mirrorH) {
        float dist = 0f;
        for (int c = 0; c < 3; c++) {
            float[] p = buf[colors == 1 ? 0 : c];
            int dy = mirror(by + cross[0], mirrorH);
            int dx = mirror(bx + cross[1], mirrorW);
            dist += Math.abs(p[by * width + bx] - p[dy * width + dx]) * rf.epfChannelScale[c];
        }
        return dist;
    }

    private static float weight(RestorationFilter rf, float sigmaScale, float distance,
            float inverseSigma, int y, int x) {
        int modY = y & 7;
        int modX = x & 7;
        if (modY == 0 || modY == 7 || modX == 0 || modX == 7) {
            distance *= rf.epfBorderSadMul;
        }
        float v = 1f - distance * sigmaScale * inverseSigma;
        return v < 0f ? 0f : v;
    }

    static int mirror(int v, int size) {
        while (v < 0 || v >= size) {
            if (v < 0) {
                v = -v - 1;
            } else {
                v = 2 * size - 1 - v;
            }
        }
        return v;
    }
}
