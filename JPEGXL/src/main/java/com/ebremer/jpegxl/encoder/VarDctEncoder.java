package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.codestream.ImageMetadata;
import com.ebremer.jpegxl.codestream.SizeHeader;
import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
import com.ebremer.jpegxl.vardct.Dct;
import com.ebremer.jpegxl.vardct.DequantMatrices;
import com.ebremer.jpegxl.vardct.HfPass;
import com.ebremer.jpegxl.vardct.TransformType;
import com.ebremer.jpegxl.vardct.VarDctState;
import java.io.IOException;

/**
 * Lossy (VarDCT) encoder: XYB colour, 8x8 and 16x16 DCT blocks chosen by a
 * rate estimate, activity-masked adaptive quantisation, default
 * dequantisation tables and a single pass. The {@code distance} parameter
 * follows cjxl's Butteraugli-distance convention loosely (1 is visually
 * lossless-ish, larger is smaller/worse).
 */
public final class VarDctEncoder {

    private static final int GROUP_DIM = 256;

    /** The default HF block context map (readHfBlockContext's default). */
    private static final int[] DEFAULT_CTX_MAP = {
        0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
    };
    private static final int NUM_BLOCK_CLUSTERS = 15;
    private static final int[] C_MAP = {1, 0, 2};

    private static final TransformType DCT8 = TransformType.byType(0);
    private static final TransformType DCT16 = TransformType.byType(4);

    // adaptive quantisation limits (relative to the base multiplier)
    private static final float AQ_MIN = 0.75f;
    private static final float AQ_MAX = 1.35f;

    private final int width;
    private final int height;
    private final int paddedWidth;
    private final int paddedHeight;
    private final int bits;
    private final boolean grey;
    private final int[] alphaPlane; // null without alpha; coded losslessly
    private final int globalScale;
    private final int hfMul;
    private final float[][] xyb = new float[3][];   // padded planes
    private final float[] scaledDequant = new float[3];
    private final float[] baseSfc = new float[3];   // before the block multiplier
    private final float[][][] weightsOf;            // [parameterIndex][channel]

    // decided by quantiseAll()
    private byte[] blockType;   // per 8x8 cell: transform type at origins, -1 covered
    private int[] blockMul;     // per cell: the block's quantisation multiplier
    private int[][] dcQuant;
    private int[][][] hfQuant;  // [channel][origin cell] -> natural coefficients

    private VarDctEncoder(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, float distance) throws IOException {
        this.width = width;
        this.height = height;
        this.paddedWidth = (width + 7) & ~7;
        this.paddedHeight = (height + 7) & ~7;
        this.bits = bits;
        this.grey = grey;
        this.alphaPlane = alpha ? planes[grey ? 1 : 3] : null;
        float d = Math.max(0.1f, distance);
        this.globalScale = Math.max(1, Math.min(65535, Math.round(4096f / d)));
        this.hfMul = Math.max(1, Math.min(200, Math.round(32f / d)));

        ImageMetadata meta = new ImageMetadata(); // defaults: opsin, quant biases
        float[] lfDequant = {1f / 4096f, 1f / 512f, 1f / 256f};
        int quantLf = 16;
        for (int i = 0; i < 3; i++) {
            scaledDequant[i] = (1 << 16) * lfDequant[i] / (globalScale * quantLf);
            baseSfc[i] = 65536.0f / globalScale; // qm scales are neutral (2)
        }
        DequantMatrices dequant = new DequantMatrices(
                new Bits(new byte[] {0x01}), 1, 1, null); // all defaults
        weightsOf = dequant.weights;

        toXyb(planes, meta);
    }

    /** Encodes 8-bit sRGB planes lossily; grey images pass the same plane thrice. */
    public static byte[] encode(int[][] rgb, int width, int height, float distance)
            throws IOException {
        return encode(rgb, width, height, 8, false, false, false, distance);
    }

    /**
     * Encodes samples lossily through the VarDCT path. Plane layout matches
     * {@link JxlEncoder#encode}: colour channels first (1 for greyscale, 3
     * for RGB), then an optional alpha plane; sRGB samples in
     * [0, 2^bits). The colour channels go through XYB quantisation; alpha is
     * carried losslessly as a modular extra channel.
     */
    public static byte[] encode(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions");
        }
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("lossy bits per sample must be in 1..16");
        }
        int numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        if (planes.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got "
                    + planes.length);
        }
        VarDctEncoder enc = new VarDctEncoder(planes, width, height, bits, grey, alpha, distance);
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = JxlEncoder.buildMetadata(bits, grey, alpha, alphaAssociated);
        meta.xybEncoded = true;
        meta.write(out);
        enc.writeFrame(out);
        return out.toByteArray();
    }

    /**
     * Encodes with a simplified perceptual rate-control loop: the image is
     * encoded, decoded again, and the global quantisation is refined so the
     * achieved error matches what the requested distance is expected to give.
     * Costlier than {@link #encode} (up to three encode/decode rounds) but the
     * quality tracks the request much more consistently across content.
     */
    public static byte[] encodeToTarget(int[][] rgb, int width, int height, float distance)
            throws IOException {
        return encodeToTarget(rgb, width, height, 8, false, false, false, distance);
    }

    /** {@link #encodeToTarget} over the full input space of {@link #encode}. */
    public static byte[] encodeToTarget(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        float d = Math.max(0.1f, distance);
        // expected weighted mean absolute sRGB error at this distance
        double target = 0.9 * Math.pow(d, 0.9);
        byte[] best = null;
        double bestMiss = Double.MAX_VALUE;
        float tryD = d;
        for (int round = 0; round < 3; round++) {
            byte[] jxl = encode(planes, width, height, bits, grey, alpha, alphaAssociated, tryD);
            double err = measureError(planes, width, height, bits, grey, jxl);
            double miss = Math.abs(Math.log(Math.max(err, 1e-3) / target));
            if (miss < bestMiss) {
                bestMiss = miss;
                best = jxl;
            }
            if (err > target * 0.85 && err < target * 1.18) {
                break; // close enough
            }
            float next = (float) (tryD * Math.pow(target / Math.max(err, 1e-3), 0.85));
            next = Math.max(d / 3, Math.min(d * 3, next));
            if (Math.abs(next - tryD) < 0.01f) {
                break;
            }
            tryD = next;
        }
        return best;
    }

    /**
     * Weighted mean absolute colour error (G counted twice) after a
     * self-decode, normalised to the 8-bit scale the target model uses.
     */
    private static double measureError(int[][] planes, int width, int height, int bits,
            boolean grey, byte[] jxl) throws IOException {
        com.ebremer.jpegxl.decoder.JxlImage image =
                com.ebremer.jpegxl.decoder.JxlDecoder.decode(jxl);
        int[][] out = image.frames.get(0).channels;
        long sum = 0;
        int n = width * height;
        if (grey) {
            for (int i = 0; i < n; i++) {
                sum += 4L * Math.abs(out[0][i] - planes[0][i]);
            }
        } else {
            for (int i = 0; i < n; i++) {
                sum += Math.abs(out[0][i] - planes[0][i])
                        + 2L * Math.abs(out[1][i] - planes[1][i])
                        + Math.abs(out[2][i] - planes[2][i]);
            }
        }
        return sum / (4.0 * n) * (255.0 / ((1 << bits) - 1));
    }

    // --------------------------------------------------------------- colour

    private void toXyb(int[][] planes, ImageMetadata meta) {
        // forward opsin: invert the decoder's (matrix * itScale)
        float itScale = 255f / meta.intensityTarget;
        double[] inv = new double[9];
        for (int i = 0; i < 9; i++) {
            inv[i] = meta.opsinInverse[i] * itScale;
        }
        double[] fwd = invert3x3(inv);
        double[] bias = {meta.opsinBias[0], meta.opsinBias[1], meta.opsinBias[2]};
        double[] cbrtBias = {Math.cbrt(bias[0]), Math.cbrt(bias[1]), Math.cbrt(bias[2])};

        double maxVal = (1 << bits) - 1;
        for (int c = 0; c < 3; c++) {
            xyb[c] = new float[paddedWidth * paddedHeight];
        }
        for (int y = 0; y < paddedHeight; y++) {
            int sy = Math.min(y, height - 1);
            for (int x = 0; x < paddedWidth; x++) {
                int sx = Math.min(x, width - 1);
                int i = sy * width + sx;
                double r = srgbToLinear(planes[0][i] / maxVal);
                double g = grey ? r : srgbToLinear(planes[1][i] / maxVal);
                double b = grey ? r : srgbToLinear(planes[2][i] / maxVal);
                double mixL = fwd[0] * r + fwd[1] * g + fwd[2] * b;
                double mixM = fwd[3] * r + fwd[4] * g + fwd[5] * b;
                double mixS = fwd[6] * r + fwd[7] * g + fwd[8] * b;
                double gl = Math.cbrt(mixL - bias[0]) + cbrtBias[0];
                double gm = Math.cbrt(mixM - bias[1]) + cbrtBias[1];
                double gs = Math.cbrt(mixS - bias[2]) + cbrtBias[2];
                int o = y * paddedWidth + x;
                xyb[0][o] = (float) ((gl - gm) / 2);   // X
                xyb[1][o] = (float) ((gl + gm) / 2);   // Y
                xyb[2][o] = (float) (gs);              // B
            }
        }
    }

    private static double srgbToLinear(double v) {
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

    // --------------------------------------------------- adaptive quantisation

    /**
     * Per-cell activity masking: smooth cells get a finer quantiser (visible
     * banding), busy cells a coarser one (texture masks the error). Returns
     * the per-cell multiplier factor relative to the base multiplier.
     */
    private float[] activityFactors(int w8, int h8) {
        float[] act = new float[w8 * h8];
        float[] luma = xyb[1];
        for (int by = 0; by < h8; by++) {
            for (int bx = 0; bx < w8; bx++) {
                double a = 0;
                for (int y = by * 8; y < by * 8 + 8; y++) {
                    int yn = Math.max(0, y - 1);
                    int ys = Math.min(paddedHeight - 1, y + 1);
                    for (int x = bx * 8; x < bx * 8 + 8; x++) {
                        int xw = Math.max(0, x - 1);
                        int xe = Math.min(paddedWidth - 1, x + 1);
                        a += Math.abs(4 * luma[y * paddedWidth + x]
                                - luma[yn * paddedWidth + x] - luma[ys * paddedWidth + x]
                                - luma[y * paddedWidth + xw] - luma[y * paddedWidth + xe]);
                    }
                }
                act[by * w8 + bx] = (float) (a / 64.0);
            }
        }
        double mean = 0;
        for (float a : act) {
            mean += a;
        }
        mean /= act.length;
        float[] factor = new float[act.length];
        if (mean < 1e-7) {
            java.util.Arrays.fill(factor, 1f);
            return factor;
        }
        for (int i = 0; i < act.length; i++) {
            double f = Math.pow(mean / (act[i] + 1e-7), 0.20);
            factor[i] = (float) Math.max(AQ_MIN, Math.min(AQ_MAX, f));
        }
        return factor;
    }

    // ---------------------------------------------------------- quantisation

    /** Chooses transforms and multipliers and quantises every block. */
    private void quantiseAll(int w8, int h8) {
        blockType = new byte[w8 * h8];
        java.util.Arrays.fill(blockType, (byte) -1);
        blockMul = new int[w8 * h8];
        dcQuant = new int[3][w8 * h8];
        hfQuant = new int[3][w8 * h8][];
        float[] factor = activityFactors(w8, h8);

        float[] block = new float[256];
        float[][] c8 = new float[4][64];
        float[] c16 = new float[256];
        float[] s0 = new float[256];
        float[] s1 = new float[256];
        float[] llf = new float[4];
        float[] dcT = new float[4];

        for (int by = 0; by < h8; by++) {
            for (int bx = 0; bx < w8; bx++) {
                if (blockType[by * w8 + bx] != -1) {
                    continue; // already decided (or covered by a 16x16)
                }
                boolean try16 = !Boolean.getBoolean("jxl.enc.nodct16")
                        && (by & 1) == 0 && (bx & 1) == 0
                        && by + 1 < h8 && bx + 1 < w8
                        && blockType[by * w8 + bx + 1] == -1
                        && blockType[(by + 1) * w8 + bx] == -1
                        && blockType[(by + 1) * w8 + bx + 1] == -1;
                if (try16 && chooseDct16(by, bx, w8, factor, block, c8, c16, s0, s1, llf, dcT)) {
                    continue;
                }
                quantiseDct8(by, bx, w8, factor, block, c8[0], s0, s1);
            }
        }
    }

    /** Quantises one 8x8 block in every channel. */
    private void quantiseDct8(int by, int bx, int w8, float[] factor,
            float[] block, float[] coeffs, float[] s0, float[] s1) {
        int k = by * w8 + bx;
        int mul = clampMul(Math.round(hfMul * factor[k]));
        blockType[k] = 0;
        blockMul[k] = mul;
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < 8; y++) {
                System.arraycopy(xyb[c], (by * 8 + y) * paddedWidth + bx * 8, block, y * 8, 8);
            }
            Dct.forward2D(block, 0, 8, coeffs, 0, 8, 8, 8, s0, s1);
            dcQuant[c][k] = Math.round(coeffs[0] / scaledDequant[c]);
            float sfc = baseSfc[c] / mul;
            int[] q = new int[64];
            float[] wc = weightsOf[DCT8.parameterIndex][c];
            for (int j = 1; j < 64; j++) {
                // natural coefficient (y,x) pairs with the transposed weight
                int wIdx = (j & 7) * 8 + (j >> 3);
                q[j] = Math.round(coeffs[j] / (sfc * wc[wIdx]));
            }
            hfQuant[c][k] = q;
        }
    }

    /**
     * Quantises the aligned 16x16 area both ways and keeps the DCT16 version
     * when its estimated rate is clearly lower. Returns true when chosen.
     */
    private boolean chooseDct16(int by, int bx, int w8, float[] factor,
            float[] block, float[][] c8, float[] c16, float[] s0, float[] s1,
            float[] llf, float[] dcT) {
        int[] cells = {by * w8 + bx, by * w8 + bx + 1, (by + 1) * w8 + bx, (by + 1) * w8 + bx + 1};
        float f4 = (factor[cells[0]] + factor[cells[1]] + factor[cells[2]] + factor[cells[3]]) / 4;
        if (f4 < 0.95f) {
            return false; // busy area: 8x8 blocks localise the error better
        }
        int mul16 = clampMul(Math.round(hfMul * f4));
        int[] mul8 = new int[4];
        for (int i = 0; i < 4; i++) {
            mul8[i] = clampMul(Math.round(hfMul * factor[cells[i]]));
        }

        double cost16 = 8;
        double cost8 = 32;
        int[][] q16 = new int[3][];
        int[][][] q8 = new int[3][4][];
        int[][] dc8 = new int[3][4];
        int[][] dc16 = new int[3][4];
        for (int c = 0; c < 3; c++) {
            // 16x16 candidate
            for (int y = 0; y < 16; y++) {
                System.arraycopy(xyb[c], (by * 8 + y) * paddedWidth + bx * 8, block, y * 16, 16);
            }
            Dct.forward2D(block, 0, 16, c16, 0, 16, 16, 16, s0, s1);
            float sfc16 = baseSfc[c] / mul16;
            float[] w16 = weightsOf[DCT16.parameterIndex][c];
            int[] q = new int[256];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (y < 2 && x < 2) {
                        continue; // LLF comes from the DC image
                    }
                    int j = y * 16 + x;
                    q[j] = Math.round(c16[j] / (sfc16 * w16[x * 16 + y]));
                    cost16 += tokenCost(q[j]);
                }
            }
            q16[c] = q;
            // derive the DC entries that reproduce the true LLF coefficients
            for (int i = 0; i < 4; i++) {
                llf[i] = c16[(i >> 1) * 16 + (i & 1)] / DCT16.llfScale[i];
            }
            Dct.inverse2D(llf, 0, 2, dcT, 0, 2, 2, 2, s0, s1, false);
            for (int i = 0; i < 4; i++) {
                dc16[c][i] = Math.round(dcT[i] / scaledDequant[c]);
            }

            // the four 8x8 alternatives
            float[] wc = weightsOf[DCT8.parameterIndex][c];
            for (int i = 0; i < 4; i++) {
                int cy = by + (i >> 1);
                int cx = bx + (i & 1);
                for (int y = 0; y < 8; y++) {
                    System.arraycopy(xyb[c], (cy * 8 + y) * paddedWidth + cx * 8,
                            block, y * 8, 8);
                }
                Dct.forward2D(block, 0, 8, c8[i], 0, 8, 8, 8, s0, s1);
                dc8[c][i] = Math.round(c8[i][0] / scaledDequant[c]);
                float sfc = baseSfc[c] / mul8[i];
                int[] qq = new int[64];
                for (int j = 1; j < 64; j++) {
                    int wIdx = (j & 7) * 8 + (j >> 3);
                    qq[j] = Math.round(c8[i][j] / (sfc * wc[wIdx]));
                    cost8 += tokenCost(qq[j]);
                }
                q8[c][i] = qq;
            }
        }

        if (cost16 >= 0.94 * cost8) {
            // keep the 8x8 blocks (reuse the already-quantised data)
            for (int i = 0; i < 4; i++) {
                int k = cells[i];
                blockType[k] = 0;
                blockMul[k] = mul8[i];
                for (int c = 0; c < 3; c++) {
                    dcQuant[c][k] = dc8[c][i];
                    hfQuant[c][k] = q8[c][i];
                }
            }
            return true; // handled either way
        }
        for (int i = 0; i < 4; i++) {
            int k = cells[i];
            // -2 marks a covered cell, distinct from -1 "not yet decided"
            blockType[k] = (byte) (i == 0 ? DCT16.type : -2);
            blockMul[k] = mul16;
            for (int c = 0; c < 3; c++) {
                dcQuant[c][k] = dc16[c][i];
            }
        }
        for (int c = 0; c < 3; c++) {
            hfQuant[c][cells[0]] = q16[c];
        }
        return true;
    }

    /** Crude token-rate estimate for one quantised coefficient. */
    private static double tokenCost(int q) {
        if (q == 0) {
            return 0.08;
        }
        int a = Math.abs(q);
        return 2 + 2 * (31 - Integer.numberOfLeadingZeros(a + 1));
    }

    private static int clampMul(int mul) {
        return Math.max(1, Math.min(255, mul));
    }

    // --------------------------------------------------------------- frame

    private void writeFrame(BitWriter out) throws IOException {
        int w8 = paddedWidth >> 3;
        int h8 = paddedHeight >> 3;
        int groupColumns = ceilDiv(width, GROUP_DIM);
        int groupRows = ceilDiv(height, GROUP_DIM);
        int numGroups = groupColumns * groupRows;
        int lfDim = GROUP_DIM * 8;
        int lfCols = ceilDiv(width, lfDim);
        int lfRows = ceilDiv(height, lfDim);
        int numLfGroups = lfCols * lfRows;
        boolean single = numGroups == 1;

        quantiseAll(w8, h8);

        // ---- per-group coefficient tokens
        EntropyEncoder hfEnc = new EntropyEncoder(495 * NUM_BLOCK_CLUSTERS, false);
        int[][] groupCtx = new int[numGroups][];
        int[][] groupVal = new int[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            java.util.ArrayList<int[]> tokens = new java.util.ArrayList<>();
            tokenizeGroup(g, groupColumns, w8, h8, tokens);
            int[] tc = new int[tokens.size()];
            int[] tv = new int[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                tc[i] = tokens.get(i)[0];
                tv[i] = tokens.get(i)[1];
            }
            groupCtx[g] = tc;
            groupVal[g] = tv;
            for (int i = 0; i < tc.length; i++) {
                hfEnc.count(tc[i], tv[i]);
            }
        }

        // ---- sections (bit-contiguous in the single-section case)
        if (single) {
            BitWriter one = new BitWriter();
            writeLfGlobalBits(one, true);
            writeLfGroupBits(one, 0, lfCols, w8, h8);
            writeHfGlobalBits(one, hfEnc, numGroups);
            for (int i = 0; i < groupCtx[0].length; i++) {
                hfEnc.write(one, groupCtx[0][i], groupVal[0][i]);
            }
            one.zeroPadToByte();
            byte[] payload = one.toByteArray();
            writeFrameHeader(out);
            out.writeBool(false); // TOC not permuted
            out.zeroPadToByte();
            writeTocEntry(out, payload.length);
            out.zeroPadToByte();
            out.writeBytes(payload);
            return;
        }

        BitWriter lfg = new BitWriter();
        writeLfGlobalBits(lfg, false);
        lfg.zeroPadToByte();
        byte[] lfGlobalBytes = lfg.toByteArray();
        byte[][] lfGroupBytes = new byte[numLfGroups][];
        for (int gg = 0; gg < numLfGroups; gg++) {
            BitWriter w = new BitWriter();
            writeLfGroupBits(w, gg, lfCols, w8, h8);
            w.zeroPadToByte();
            lfGroupBytes[gg] = w.toByteArray();
        }
        BitWriter hfg = new BitWriter();
        writeHfGlobalBits(hfg, hfEnc, numGroups);
        hfg.zeroPadToByte();
        byte[] hfGlobalBytes = hfg.toByteArray();
        byte[][] passBytes = new byte[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            BitWriter gw = new BitWriter();
            // no hf preset bits with a single preset
            for (int i = 0; i < groupCtx[g].length; i++) {
                hfEnc.write(gw, groupCtx[g][i], groupVal[g][i]);
            }
            if (alphaPlane != null) {
                writeGroupAlpha(gw, g, groupColumns);
            }
            gw.zeroPadToByte();
            passBytes[g] = gw.toByteArray();
        }

        // ---- header + TOC + payload
        writeFrameHeader(out);
        out.writeBool(false); // TOC not permuted
        out.zeroPadToByte();
        writeTocEntry(out, lfGlobalBytes.length);
        for (byte[] b : lfGroupBytes) {
            writeTocEntry(out, b.length);
        }
        writeTocEntry(out, hfGlobalBytes.length);
        for (byte[] b : passBytes) {
            writeTocEntry(out, b.length);
        }
        out.zeroPadToByte();
        out.writeBytes(lfGlobalBytes);
        for (byte[] b : lfGroupBytes) {
            out.writeBytes(b);
        }
        out.writeBytes(hfGlobalBytes);
        for (byte[] b : passBytes) {
            out.writeBytes(b);
        }
    }

    /** The default block context of visual channel {@code c} for an order id. */
    private static int blockCtxOf(int c, int orderId) {
        return DEFAULT_CTX_MAP[(c < 2 ? 1 - c : c) * 13 + orderId];
    }

    private void tokenizeGroup(int g, int groupColumns, int w8, int h8,
            java.util.List<int[]> tokens) {
        int gRow = g / groupColumns;
        int gCol = g % groupColumns;
        int by0 = gRow << 5;
        int bx0 = gCol << 5;
        int byN = Math.min(by0 + 32, h8);
        int bxN = Math.min(bx0 + 32, w8);
        int[][] nonZeroes = new int[3][32 * 32];
        for (int by = by0; by < byN; by++) {
            for (int bx = bx0; bx < bxN; bx++) {
                int k = by * w8 + bx;
                if (blockType[k] < 0) {
                    continue; // covered cell
                }
                TransformType tt = blockType[k] == 0 ? DCT8 : DCT16;
                int numBlocks = tt.blockHeight * tt.blockWidth;
                int[] order = HfPass.naturalOrder(tt.orderId);
                int orderSize = order.length;
                int pw = tt.pixelWidth;
                for (int ci = 0; ci < 3; ci++) {
                    int c = C_MAP[ci];
                    int groupY = by - by0;
                    int groupX = bx - bx0;
                    int[] q = hfQuant[c][k];
                    int nonZero = 0;
                    for (int y = 0; y < tt.pixelHeight; y++) {
                        for (int x = 0; x < pw; x++) {
                            if (y < tt.blockHeight && x < tt.blockWidth) {
                                continue;
                            }
                            if (q[y * pw + x] != 0) {
                                nonZero++;
                            }
                        }
                    }
                    int predicted = VarDctState.predictNonZeroes(nonZeroes[c], groupY, groupX);
                    int blockCtx = blockCtxOf(c, tt.orderId);
                    int nonZeroCtx = VarDctState.nonZeroContext(predicted, blockCtx,
                            NUM_BLOCK_CLUSTERS);
                    tokens.add(new int[] {nonZeroCtx, nonZero});
                    int perCell = (nonZero + numBlocks - 1) / numBlocks;
                    for (int iy = 0; iy < tt.blockHeight; iy++) {
                        for (int ix = 0; ix < tt.blockWidth; ix++) {
                            nonZeroes[c][(groupY + iy) * 32 + groupX + ix] = perCell;
                        }
                    }
                    if (nonZero == 0) {
                        continue;
                    }
                    int histCtx = 458 * blockCtx + 37 * NUM_BLOCK_CLUSTERS;
                    int remaining = nonZero;
                    int prevToken = -1;
                    for (int j = numBlocks; j < orderSize && remaining > 0; j++) {
                        int o = order[j];
                        int oy = o >> 16;
                        int ox = o & 0xffff;
                        // the decoder writes this token at the flipped grid
                        // position (ox, oy)
                        int v = q[ox * pw + oy];
                        int prev = j == numBlocks
                                ? (nonZero > orderSize / 16 ? 0 : 1)
                                : (prevToken != 0 ? 1 : 0);
                        int ctx = histCtx
                                + VarDctState.coefficientContext(j, remaining, numBlocks, prev);
                        int packed = packSigned(v);
                        tokens.add(new int[] {ctx, packed});
                        prevToken = packed;
                        if (v != 0) {
                            remaining--;
                        }
                    }
                }
            }
        }
    }

    private void writeLfGlobalBits(BitWriter w, boolean single) {
        w.writeBool(true);   // LfChannelDequantization.all_default
        if (globalScale <= 2048) {
            w.write(0, 2);
            w.write(globalScale - 1, 11);
        } else if (globalScale <= 4096) {
            w.write(1, 2);
            w.write(globalScale - 2049, 11);
        } else if (globalScale <= 8192) {
            w.write(2, 2);
            w.write(globalScale - 4097, 12);
        } else {
            w.write(3, 2);
            w.write(globalScale - 8193, 16);
        }
        w.write(0, 2);       // quantLf selector 0 -> 16
        w.writeBool(true);   // HFBlockContext all_default
        w.writeBool(false);  // LfChannelCorrelation not all_default
        w.write(0, 2);       // colour factor selector 0 -> 84
        w.write(0, 16);      // base correlation X = 0 (f16)
        w.write(0, 16);      // base correlation B = 0 (no implicit Y contribution)
        w.write(128, 8);     // x factor LF
        w.write(128, 8);     // b factor LF
        if (alphaPlane == null) {
            w.writeBool(false); // no global MA tree; no modular channels either
            return;
        }
        w.writeBool(true);   // global tree present
        if (single) {
            // the alpha channel fits the global stream: a learned tree and
            // real code here, the tokens right after the stream header
            AlphaCode a = buildAlphaCode(alphaPlane, width, height);
            EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
            JxlEncoder.emitTree(a.tree, null, treeEnc);
            EntropyEncoder litProbe = new EntropyEncoder(a.numCtx, true, true);
            JxlEncoder.countLiterals(a.buf, litProbe);
            litProbe.prepareCosts();
            JxlEncoder.findMatches(a.buf, width, litProbe);
            EntropyEncoder dataEnc = new EntropyEncoder(a.numCtx, true, true, true);
            JxlEncoder.emitBuffer(a.buf, null, dataEnc, width);
            treeEnc.writeSpec(w);
            JxlEncoder.emitTree(a.tree, w, treeEnc);
            treeEnc.finishSection(w);
            dataEnc.writeSpec(w);
            w.writeBool(true);  // use_global_tree
            w.writeBool(true);  // default weighted-predictor parameters
            w.write(0, 2);      // nb_transforms = 0
            JxlEncoder.emitBuffer(a.buf, w, dataEnc, width);
            dataEnc.finishSection(w);
        } else {
            // alpha is bigger than a group: nothing is coded globally, so a
            // trivial one-leaf tree and an empty code satisfy the reader
            JxlEncoder.TNode leaf = new JxlEncoder.TNode();
            leaf.predictor = 5; // gradient
            EntropyEncoder treeEnc = new EntropyEncoder(6, false);
            JxlEncoder.emitTree(leaf, null, treeEnc);
            treeEnc.writeSpec(w);
            JxlEncoder.emitTree(leaf, w, treeEnc);
            EntropyEncoder dataEnc = new EntropyEncoder(1, true, true);
            dataEnc.writeSpec(w); // nothing is coded against it
            w.writeBool(true);  // use_global_tree
            w.writeBool(true);  // default weighted-predictor parameters
            w.write(0, 2);      // nb_transforms = 0
        }
    }

    /** A learned modular code for one alpha rectangle. */
    private static final class AlphaCode {
        JxlEncoder.TNode tree;
        int numCtx;
        JxlEncoder.TokenBuf buf;
    }

    private static AlphaCode buildAlphaCode(int[] px, int aw, int ah) {
        JxlEncoder.Chan c = new JxlEncoder.Chan(aw, ah, px);
        JxlEncoder.choosePredictor(c);
        java.util.List<int[]> rect = java.util.List.of(new int[] {0, 0, aw, ah});
        JxlEncoder.TNode sub = JxlEncoder.learnTree(c, null, rect, 1 << 14, 4);
        JxlEncoder.refineLeaves(c, sub, null, rect);
        AlphaCode a = new AlphaCode();
        a.tree = sub;
        a.numCtx = JxlEncoder.assignCtx(sub);
        a.buf = new JxlEncoder.TokenBuf();
        JxlEncoder.tokenizeRect(c, sub, null, 0, 0, aw, ah, a.buf);
        return a;
    }

    /** Writes one group's alpha crop as a self-contained modular sub-stream. */
    private void writeGroupAlpha(BitWriter gw, int g, int groupColumns) {
        int x0 = (g % groupColumns) * GROUP_DIM;
        int y0 = (g / groupColumns) * GROUP_DIM;
        int w = Math.min(GROUP_DIM, width - x0);
        int h = Math.min(GROUP_DIM, height - y0);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(alphaPlane, (y0 + y) * width + x0, px, y * w, w);
        }
        AlphaCode a = buildAlphaCode(px, w, h);
        gw.writeBool(false); // use_global_tree = false: the section is standalone
        gw.writeBool(true);  // default weighted-predictor parameters
        gw.write(0, 2);      // nb_transforms = 0
        EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
        JxlEncoder.emitTree(a.tree, null, treeEnc);
        treeEnc.writeSpec(gw);
        JxlEncoder.emitTree(a.tree, gw, treeEnc);
        treeEnc.finishSection(gw);
        EntropyEncoder litProbe = new EntropyEncoder(a.numCtx, true, true);
        JxlEncoder.countLiterals(a.buf, litProbe);
        litProbe.prepareCosts();
        JxlEncoder.findMatches(a.buf, w, litProbe);
        EntropyEncoder dataEnc = new EntropyEncoder(a.numCtx, true, true, true);
        JxlEncoder.emitBuffer(a.buf, null, dataEnc, w);
        dataEnc.writeSpec(gw);
        JxlEncoder.emitBuffer(a.buf, gw, dataEnc, w);
        dataEnc.finishSection(gw);
    }

    private void writeLfGroupBits(BitWriter w, int gg, int lfCols, int w8, int h8) {
        int lfBlockDim = GROUP_DIM; // blocks per LF group side (256 blocks = 2048 px)
        int row = gg / lfCols;
        int col = gg % lfCols;
        int bw = Math.min(lfBlockDim, w8 - col * lfBlockDim);
        int bh = Math.min(lfBlockDim, h8 - row * lfBlockDim);

        w.write(0, 2); // extra precision = 0
        // LF quants as a modular image in stream order (Y, X, B)
        int[][] lf = new int[3][bw * bh];
        for (int i = 0; i < 3; i++) {
            int c = C_MAP[i]; // stream channel i is visual channel C_MAP[i]
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    lf[i][y * bw + x] =
                            dcQuant[c][(row * lfBlockDim + y) * w8 + col * lfBlockDim + x];
                }
            }
        }
        ModularSub.write(w, lf, new int[] {bw, bw, bw}, new int[] {bh, bh, bh});

        // HF metadata: varblock list in placement (raster origin) order
        java.util.ArrayList<Integer> types = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> muls = new java.util.ArrayList<>();
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int k = (row * lfBlockDim + y) * w8 + col * lfBlockDim + x;
                if (blockType[k] >= 0) {
                    types.add((int) blockType[k]);
                    muls.add(blockMul[k] - 1);
                }
            }
        }
        int nbBlocks = types.size();
        int n = ceilLog2(bw * bh);
        w.write(nbBlocks - 1, n);
        int tileW = ceilDiv(bw, 8);
        int tileH = ceilDiv(bh, 8);
        int[][] metaPx = {
            new int[tileW * tileH],      // x from y factors
            new int[tileW * tileH],      // b from y factors
            new int[2 * nbBlocks],       // block info: row 0 types, row 1 multipliers
            new int[bw * bh],            // sharpness
        };
        for (int i = 0; i < nbBlocks; i++) {
            metaPx[2][i] = types.get(i);
            metaPx[2][nbBlocks + i] = muls.get(i);
        }
        ModularSub.write(w, metaPx,
                new int[] {tileW, tileW, nbBlocks, bw},
                new int[] {tileH, tileH, 2, bh});
    }

    private void writeHfGlobalBits(BitWriter w, EntropyEncoder hfEnc, int numGroups) {
        w.writeBool(true); // dequant matrices all default
        int bits = numGroups > 1 ? 32 - Integer.numberOfLeadingZeros(numGroups - 1) : 0;
        w.write(0, bits);  // num_hf_presets = 1
        // HfPass 0: no coded orders, then the coefficient code spec
        w.write(2, 2);     // used_orders selector 2 -> 0
        hfEnc.writeSpec(w);
    }

    private void writeFrameHeader(BitWriter out) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(0, 2);             // frame_type: regular
        out.writeBool(false);        // encoding: VarDCT
        out.writeU64(128);           // flags: skip adaptive LF smoothing
        // xyb encoded => no do_YCbCr bit
        out.write(0, 2);             // log upsampling
        if (alphaPlane != null) {
            out.write(0, 2);         // extra channel upsampling
        }
        out.write(2, 3);             // x_qm_scale = 2
        out.write(2, 3);             // b_qm_scale = 2
        out.write(0, 2);             // num_passes = 1
        out.writeBool(false);        // have_crop
        int blendEntries = 1 + (alphaPlane != null ? 1 : 0);
        for (int i = 0; i < blendEntries; i++) {
            out.write(0, 2);         // blend mode: replace (full frame -> no source)
        }
        out.writeBool(true);         // is_last
        out.write(0, 2);             // name length
        out.writeBool(false);        // RestorationFilter not all_default
        out.writeBool(false);        // gaborish off
        out.write(0, 2);             // epf iterations = 0
        out.writeU64(0);             // restoration filter extensions
        out.writeU64(0);             // frame header extensions
    }

    private static void writeTocEntry(BitWriter out, int size) {
        if (size < 1024) {
            out.write(0, 2);
            out.write(size, 10);
        } else if (size < 17408) {
            out.write(1, 2);
            out.write(size - 1024, 14);
        } else if (size < 4211712) {
            out.write(2, 2);
            out.write(size - 17408, 22);
        } else {
            out.write(3, 2);
            out.write(size - 4211712, 30);
        }
    }

    private static int packSigned(int v) {
        return v >= 0 ? 2 * v : -2 * v - 1;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }
}
