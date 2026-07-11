package com.ebremer.jpegxl.vardct;

import com.ebremer.jpegxl.codestream.FrameHeader;
import com.ebremer.jpegxl.codestream.ImageMetadata;
import com.ebremer.jpegxl.entropy.EntropyDecoder;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/**
 * All VarDCT-specific state for one frame: quantiser globals, HF block
 * contexts, per-LF-group metadata, per-group quantised coefficients, and the
 * final reconstruction into the frame's float planes.
 */
public final class VarDctState {

    /** Y, X, B iteration order used by coefficient decoding. */
    private static final int[] C_MAP = {1, 0, 2};

    private static final int[] COEFF_FREQ_CTX = {
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        15, 15, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22, 22,
        23, 23, 23, 23, 24, 24, 24, 24, 25, 25, 25, 25, 26, 26, 26, 26,
        27, 27, 27, 27, 28, 28, 28, 28, 29, 29, 29, 29, 30, 30, 30, 30,
    };

    private static final int[] COEFF_NUM_NONZERO_CTX = {
        -1, 0, 31, 62, 62, 93, 93, 93, 93, 123, 123, 123, 123, 152, 152,
        152, 152, 152, 152, 152, 152, 180, 180, 180, 180, 180, 180, 180,
        180, 180, 180, 180, 180, 206, 206, 206, 206, 206, 206, 206, 206,
        206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
        206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
    };

    private final FrameHeader fh;
    private final ImageMetadata meta;

    // LfGlobal
    public int globalScale;
    public int quantLf;
    public final float[] scaledDequant = new float[3];
    public int[] blockCtxMap;
    public int numBlockClusters;
    public int[][] lfThresholds = new int[3][0];
    public int[] qfThresholds = new int[0];
    public int numLfContexts = 1;
    public int colorFactor = 84;
    public float baseCorrelationX = 0f;
    public float baseCorrelationB = 1f;
    public int xFactorLf = 128;
    public int bFactorLf = 128;

    // HfGlobal
    public DequantMatrices dequant;
    public HfPass[] hfPasses;

    // per LF group
    public final LfGroupData[] lfGroups;

    // per group: accumulated quantised coefficients [3][256*256]
    private final int[][][] groupCoeffs;

    /** Raw (quantised) HF coefficients of one group; JPEG reconstruction input. */
    public int[][] rawCoefficients(int group) {
        return groupCoeffs[group];
    }

    public static final class LfGroupData {
        public int width8;   // blocks
        public int height8;
        public final int[] cWidth8 = new int[3];  // per-channel (subsampled) block dims
        public final int[] cHeight8 = new int[3];
        public float[][] lfDeq = new float[3][]; // dequantised LF, [c][cH8*cW8]
        public int[][] lfQuantRaw;               // raw LF quants (JPEG DC), visual order
        public int lfExtraPrecision;
        public int[] lfIndex;
        public byte[] blockType;   // per block: transform type or -1
        public boolean[] blockOrigin;
        public int[] hfMul;
        public int[] sharpness;
        public int[] xFromY;       // per 64x64 tile
        public int[] bFromY;
        public int tileStride;
    }

    public VarDctState(FrameHeader fh, ImageMetadata meta) {
        this.fh = fh;
        this.meta = meta;
        this.lfGroups = new LfGroupData[fh.numLfGroups];
        this.groupCoeffs = new int[fh.numGroups][][];
    }

    // ---------------------------------------------------------- LfGlobal

    public void readLfGlobal(Bits in, float[] lfDequant) throws IOException {
        globalScale = in.u32(1, 11, 2049, 11, 4097, 12, 8193, 16);
        quantLf = in.u32(16, 0, 1, 5, 1, 8, 1, 16);
        for (int i = 0; i < 3; i++) {
            scaledDequant[i] = (1 << 16) * lfDequant[i] / (globalScale * quantLf);
        }
        readHfBlockContext(in);
        if (!in.bool()) { // LfChannelCorrelation not all_default
            colorFactor = in.u32(84, 0, 256, 0, 2, 8, 258, 16);
            baseCorrelationX = in.f16();
            baseCorrelationB = in.f16();
            xFactorLf = in.u(8);
            bFactorLf = in.u(8);
        }
    }

    private void readHfBlockContext(Bits in) throws IOException {
        if (in.bool()) { // use default
            blockCtxMap = new int[] {
                0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6,
                7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
                7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
            };
            numBlockClusters = 15;
            return;
        }
        int lfCtx = 1;
        for (int i = 0; i < 3; i++) {
            int nb = in.u(4);
            lfCtx *= nb + 1;
            lfThresholds[i] = new int[nb];
            for (int j = 0; j < nb; j++) {
                lfThresholds[i][j] = Bits.unpackSigned(in.u32(0, 4, 16, 8, 272, 16, 65808, 32));
            }
        }
        numLfContexts = lfCtx;
        int nbQf = in.u(4);
        qfThresholds = new int[nbQf];
        for (int i = 0; i < nbQf; i++) {
            qfThresholds[i] = 1 + in.u32(0, 2, 4, 3, 12, 5, 44, 8);
        }
        int bSize = 39 * (nbQf + 1) * lfCtx;
        if (bSize > 39 * 64) {
            throw new IOException("HF block context size too large");
        }
        blockCtxMap = EntropyDecoder.readClusterMapPublic(in, bSize, 16);
        int max = 0;
        for (int c : blockCtxMap) {
            max = Math.max(max, c + 1);
        }
        numBlockClusters = max;
    }

    // ---------------------------------------------------------- HfGlobal

    public void readHfGlobal(Bits in, DequantMatrices.RawWeightReader rawReader) throws IOException {
        dequant = new DequantMatrices(in, fh.numGroups, fh.numLfGroups, rawReader);
        hfPasses = new HfPass[fh.passes.numPasses];
        for (int p = 0; p < fh.passes.numPasses; p++) {
            hfPasses[p] = new HfPass(in, dequant.numHfPresets, numBlockClusters);
        }
    }

    // ---------------------------------------------------------- LfGroup

    /** Reads the LF coefficients (before the modular LF-group sub-stream). */
    public void readLfCoefficients(Bits in, int ggIdx, ModularReader modularReader,
            float[][] lfFramePlanes, int lfFrameWidth) throws IOException {
        LfGroupData gg = new LfGroupData();
        int lfDim = fh.groupDim * 8;
        int row = ggIdx / fh.lfGroupColumns;
        int col = ggIdx % fh.lfGroupColumns;
        int w = Math.min(lfDim, fh.paddedWidth - col * lfDim);
        int h = Math.min(lfDim, fh.paddedHeight - row * lfDim);
        gg.width8 = w >> 3;
        gg.height8 = h >> 3;
        for (int c = 0; c < 3; c++) {
            gg.cWidth8[c] = gg.width8 >> fh.jpegShiftX[c];
            gg.cHeight8[c] = gg.height8 >> fh.jpegShiftY[c];
        }
        lfGroups[ggIdx] = gg;

        if ((fh.flags & FrameHeader.FLAG_USE_LF_FRAME) != 0) {
            // the LF image comes from a previously decoded LF frame
            int pY = row << 8;
            int pX = col << 8;
            for (int c = 0; c < 3; c++) {
                float[] dq = new float[gg.cHeight8[c] * gg.cWidth8[c]];
                for (int y = 0; y < gg.cHeight8[c]; y++) {
                    System.arraycopy(lfFramePlanes[c], (pY + y) * lfFrameWidth + pX,
                            dq, y * gg.cWidth8[c], gg.cWidth8[c]);
                }
                gg.lfDeq[c] = dq;
            }
            gg.lfIndex = new int[gg.height8 * gg.width8];
            return;
        }

        boolean adaptiveSmoothing =
                (fh.flags & FrameHeader.FLAG_SKIP_ADAPTIVE_LF_SMOOTHING) == 0;
        if (adaptiveSmoothing && fh.isSubsampled) {
            throw new IOException("adaptive LF smoothing is incompatible with chroma subsampling");
        }
        int extraPrecision = in.u(2);
        // channels in Y, X, B order, each at its own (subsampled) size
        int[][] shapes = new int[3][];
        for (int k = 0; k < 3; k++) {
            int v = C_MAP[k];
            shapes[k] = new int[] {gg.cHeight8[v], gg.cWidth8[v], fh.jpegShiftY[v], fh.jpegShiftX[v]};
        }
        int[][] lfQuant = modularReader.readFixedModular(in, 1 + ggIdx, shapes);
        gg.lfExtraPrecision = extraPrecision;
        gg.lfQuantRaw = new int[][] {lfQuant[C_MAP[0]], lfQuant[C_MAP[1]], lfQuant[C_MAP[2]]};
        String dump = System.getProperty("jxl.lfdump");
        if (dump != null) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                sb.append("c=").append(c).append(" w=").append(gg.cWidth8[c])
                        .append(" h=").append(gg.cHeight8[c]).append('\n');
                for (int y = 0; y < gg.cHeight8[c]; y++) {
                    for (int x = 0; x < gg.cWidth8[c]; x++) {
                        sb.append(gg.lfQuantRaw[c][y * gg.cWidth8[c] + x]).append(' ');
                    }
                    sb.append('\n');
                }
            }
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(dump + "-gg" + ggIdx + ".txt"), sb.toString());
            } catch (IOException e) {
                // debugging aid only
            }
        }
        for (int i = 0; i < 3; i++) {
            float sd = scaledDequant[i] / (1 << extraPrecision);
            float[] dq = new float[gg.cHeight8[i] * gg.cWidth8[i]];
            int[] q = lfQuant[C_MAP[i]];
            for (int k = 0; k < dq.length; k++) {
                dq[k] = q[k] * sd;
            }
            gg.lfDeq[i] = dq;
        }
        if (!fh.isSubsampled) {
            // chroma from luma at LF
            float kX = baseCorrelationX + (xFactorLf - 128f) / colorFactor;
            float kB = baseCorrelationB + (bFactorLf - 128f) / colorFactor;
            for (int k = 0; k < gg.lfDeq[0].length; k++) {
                float y = gg.lfDeq[1][k];
                gg.lfDeq[0][k] += kX * y;
                gg.lfDeq[2][k] += kB * y;
            }
        }
        if (adaptiveSmoothing) {
            adaptiveSmooth(gg);
        }
        // LF context index per block
        gg.lfIndex = new int[gg.height8 * gg.width8];
        for (int by = 0; by < gg.height8; by++) {
            for (int bx = 0; bx < gg.width8; bx++) {
                int[] index = new int[3];
                for (int i = 0; i < 3; i++) {
                    int q = lfQuant[C_MAP[i]][
                            (by >> fh.jpegShiftY[i]) * gg.cWidth8[i] + (bx >> fh.jpegShiftX[i])];
                    for (int t : lfThresholds[i]) {
                        if (q > t) {
                            index[i]++;
                        }
                    }
                }
                int lfIndex = index[0];
                lfIndex *= lfThresholds[2].length + 1;
                lfIndex += index[2];
                lfIndex *= lfThresholds[1].length + 1;
                lfIndex += index[1];
                gg.lfIndex[by * gg.width8 + bx] = lfIndex;
            }
        }
    }

    private void adaptiveSmooth(LfGroupData gg) {
        int w = gg.width8;
        int h = gg.height8;
        float[][] weighted = new float[3][w * h];
        float[] gap = new float[w * h];
        java.util.Arrays.fill(gap, 0.5f);
        for (int i = 0; i < 3; i++) {
            float[] co = gg.lfDeq[i];
            float sd = scaledDequant[i];
            float[] wi = weighted[i];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int k = y * w + x;
                    float sample = co[k];
                    float adjacent = co[k - 1] + co[k + 1] + co[k - w] + co[k + w];
                    float diag = co[k - w - 1] + co[k - w + 1] + co[k + w - 1] + co[k + w + 1];
                    wi[k] = 0.05226273532324128f * sample + 0.20345139757231578f * adjacent
                            + 0.0334829185968739f * diag;
                    float g = Math.abs(sample - wi[k]) * sd;
                    if (g > gap[k]) {
                        gap[k] = g;
                    }
                }
            }
        }
        for (int k = 0; k < gap.length; k++) {
            gap[k] = Math.max(0f, 3f - 4f * gap[k]);
        }
        for (int i = 0; i < 3; i++) {
            float[] co = gg.lfDeq[i];
            float[] wi = weighted[i];
            float[] out = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int k = y * w + x;
                    if (y == 0 || y + 1 == h || x == 0 || x + 1 == w) {
                        out[k] = co[k];
                    } else {
                        out[k] = (co[k] - wi[k]) * gap[k] + wi[k];
                    }
                }
            }
            gg.lfDeq[i] = out;
        }
    }

    /** Reads HF metadata (after the modular LF-group sub-stream). */
    public void readHfMetadata(Bits in, int ggIdx, ModularReader modularReader) throws IOException {
        LfGroupData gg = lfGroups[ggIdx];
        int n = ceilLog2(gg.height8 * gg.width8);
        int nbBlocks = 1 + in.u(n);
        int tileH = (gg.height8 + 7) / 8;
        int tileW = (gg.width8 + 7) / 8;
        int[][] planes = modularReader.readFixedModular(in, 1 + 2 * fh.numLfGroups + ggIdx,
                new int[][] {{tileH, tileW}, {tileH, tileW}, {2, nbBlocks}, {gg.height8, gg.width8}});
        gg.xFromY = planes[0];
        gg.bFromY = planes[1];
        gg.tileStride = tileW;
        int[] blockInfo = planes[2];
        gg.sharpness = planes[3];
        gg.blockType = new byte[gg.height8 * gg.width8];
        java.util.Arrays.fill(gg.blockType, (byte) -1);
        gg.blockOrigin = new boolean[gg.height8 * gg.width8];
        gg.hfMul = new int[gg.height8 * gg.width8];
        int lastY = 0;
        int lastX = 0;
        for (int i = 0; i < nbBlocks; i++) {
            int type = blockInfo[i];
            if (type < 0 || type > 26) {
                throw new IOException("invalid transform type " + type);
            }
            TransformType tt = TransformType.byType(type);
            int mul = 1 + blockInfo[nbBlocks + i];
            // place at the first free position at or after (lastY, lastX) in raster order
            int y = lastY;
            int x = lastX;
            placement:
            while (true) {
                for (; y < gg.height8; y++, x = 0) {
                    for (; x < gg.width8; x++) {
                        if (tt.blockWidth + x > gg.width8) {
                            break; // next row
                        }
                        boolean occupied = false;
                        for (int ix = 0; ix < tt.blockWidth; ix++) {
                            byte cur = gg.blockType[y * gg.width8 + x + ix];
                            if (cur >= 0) {
                                x += TransformType.byType(cur).blockWidth - 1;
                                occupied = true;
                                break;
                            }
                        }
                        if (occupied) {
                            continue;
                        }
                        if (y + tt.blockHeight > gg.height8) {
                            throw new IOException("block does not fit vertically");
                        }
                        break placement;
                    }
                }
                throw new IOException("no room for varblock");
            }
            for (int iy = 0; iy < tt.blockHeight; iy++) {
                for (int ix = 0; ix < tt.blockWidth; ix++) {
                    int k = (y + iy) * gg.width8 + x + ix;
                    gg.blockType[k] = (byte) type;
                    gg.hfMul[k] = mul;
                }
            }
            gg.blockOrigin[y * gg.width8 + x] = true;
            lastY = y;
            lastX = x;
        }
    }

    // ---------------------------------------------------------- PassGroup

    /** Decodes the HF coefficients of one group in one pass, accumulating. */
    public void readHfCoefficients(Bits in, int pass, int group) throws IOException {
        HfPass hfPass = hfPasses[pass];
        int presetBits = dequant.numHfPresets > 1
                ? 32 - Integer.numberOfLeadingZeros(dequant.numHfPresets - 1) : 0;
        int hfPreset = in.u(presetBits);
        int offset = 495 * numBlockClusters * hfPreset;
        int shift = fh.passes.shift[pass];

        int gRow = group / fh.groupColumns;
        int gCol = group % fh.groupColumns;
        LfGroupData gg = lfGroups[(gRow >> 3) * fh.lfGroupColumns + (gCol >> 3)];
        int groupPosY = (gRow & 7) << 5; // in blocks within the LF group
        int groupPosX = (gCol & 7) << 5;

        int[][] coeffs = groupCoeffs[group];
        if (coeffs == null) {
            coeffs = new int[3][];
            for (int c = 0; c < 3; c++) {
                coeffs[c] = new int[(256 >> fh.jpegShiftY[c]) * (256 >> fh.jpegShiftX[c])];
            }
            groupCoeffs[group] = coeffs;
        }
        int[][] nonZeroes = new int[3][32 * 32];
        EntropyDecoder stream = hfPass.contextStream.freshState();

        for (int by = 0; by < gg.height8; by++) {
            for (int bx = 0; bx < gg.width8; bx++) {
                int posK = by * gg.width8 + bx;
                if (!gg.blockOrigin[posK]) {
                    continue;
                }
                int groupY = by - groupPosY;
                int groupX = bx - groupPosX;
                if (groupY < 0 || groupX < 0 || groupY >= 32 || groupX >= 32) {
                    continue;
                }
                TransformType tt = TransformType.byType(gg.blockType[posK]);
                boolean flip = tt.flip();
                int hfMult = gg.hfMul[posK];
                int lfIndex = gg.lfIndex[posK];
                int numBlocks = tt.blockHeight * tt.blockWidth;
                for (int ci = 0; ci < 3; ci++) {
                    int c = C_MAP[ci];
                    int sGroupY = groupY >> fh.jpegShiftY[c];
                    int sGroupX = groupX >> fh.jpegShiftX[c];
                    if (groupY != sGroupY << fh.jpegShiftY[c]
                            || groupX != sGroupX << fh.jpegShiftX[c]) {
                        continue; // block not on this channel's subsampled grid
                    }
                    int cStride = 256 >> fh.jpegShiftX[c];
                    int pixelGroupY = sGroupY << 3;
                    int pixelGroupX = sGroupX << 3;
                    int predicted = predictNonZeroes(nonZeroes[c], sGroupY, sGroupX);
                    int blockCtx = blockContext(c, tt.orderId, hfMult, lfIndex);
                    int nonZeroCtx = offset + nonZeroContext(predicted, blockCtx);
                    int nonZero = stream.readSymbol(in, nonZeroCtx);
                    for (int iy = 0; iy < tt.blockHeight; iy++) {
                        for (int ix = 0; ix < tt.blockWidth; ix++) {
                            nonZeroes[c][(sGroupY + iy) * 32 + sGroupX + ix] =
                                    (nonZero + numBlocks - 1) / numBlocks;
                        }
                    }
                    if (nonZero <= 0) {
                        continue;
                    }
                    int[] order = hfPass.order[tt.orderId][c];
                    int orderSize = order.length;
                    if (nonZero > orderSize - numBlocks) {
                        throw new IOException("non-zero count too large");
                    }
                    int histCtx = offset + 458 * blockCtx + 37 * numBlockClusters;
                    int prevUcoeff = -1;
                    for (int k = 0; k < orderSize - numBlocks; k++) {
                        int prev = k == 0
                                ? (nonZero > orderSize / 16 ? 0 : 1)
                                : (prevUcoeff != 0 ? 1 : 0);
                        int ctx = histCtx + coefficientContext(k + numBlocks, nonZero, numBlocks, prev);
                        int ucoeff = stream.readSymbol(in, ctx);
                        prevUcoeff = ucoeff;
                        int o = order[k + numBlocks];
                        int oy = o >> 16;
                        int ox = o & 0xffff;
                        int posY = (flip ? ox : oy) + pixelGroupY;
                        int posX = (flip ? oy : ox) + pixelGroupX;
                        coeffs[c][posY * cStride + posX] += Bits.unpackSigned(ucoeff) << shift;
                        if (ucoeff != 0 && --nonZero == 0) {
                            break;
                        }
                    }
                    if (nonZero != 0) {
                        throw new IOException("mismatched non-zero count in group " + group);
                    }
                }
            }
        }
        stream.finish(in);
    }

    public static int predictNonZeroes(int[] nz, int y, int x) {
        if (x == 0 && y == 0) {
            return 32;
        }
        if (x == 0) {
            return nz[(y - 1) * 32];
        }
        if (y == 0) {
            return nz[x - 1];
        }
        return (nz[(y - 1) * 32 + x] + nz[y * 32 + x - 1] + 1) >> 1;
    }

    private int blockContext(int c, int orderId, int hfMult, int lfIndex) {
        int idx = (c < 2 ? 1 - c : c) * 13 + orderId;
        idx *= qfThresholds.length + 1;
        for (int t : qfThresholds) {
            if (hfMult > t) {
                idx++;
            }
        }
        idx *= numLfContexts;
        return blockCtxMap[idx + lfIndex];
    }

    private int nonZeroContext(int predicted, int ctx) {
        return nonZeroContext(predicted, ctx, numBlockClusters);
    }

    public static int nonZeroContext(int predicted, int ctx, int numClusters) {
        if (predicted > 64) {
            predicted = 64;
        }
        if (predicted < 8) {
            return ctx + numClusters * predicted;
        }
        return ctx + numClusters * (4 + predicted / 2);
    }

    public static int coefficientContext(int k, int nonZeroes, int numBlocks, int prev) {
        nonZeroes = (nonZeroes + numBlocks - 1) / numBlocks;
        k /= numBlocks;
        return (COEFF_NUM_NONZERO_CTX[nonZeroes] + COEFF_FREQ_CTX[k]) * 2 + prev;
    }

    // ------------------------------------------------------ reconstruction

    /**
     * Dequantises, applies chroma-from-luma and LLF, and inverts all groups.
     * Each frame plane has its own (possibly chroma-subsampled) dimensions.
     */
    public void reconstruct(float[][] framePlanes) throws IOException {
        float globalScaleF = 65536.0f / globalScale;
        float[] scaleFactor = {
            globalScaleF * (float) Math.pow(0.8, fh.xqmScale - 2.0),
            globalScaleF,
            globalScaleF * (float) Math.pow(0.8, fh.bqmScale - 2.0),
        };
        float[][] qbclut = {
            {-meta.quantBias[0], 0f, meta.quantBias[0]},
            {-meta.quantBias[1], 0f, meta.quantBias[1]},
            {-meta.quantBias[2], 0f, meta.quantBias[2]},
        };
        int[] shiftY = fh.jpegShiftY;
        int[] shiftX = fh.jpegShiftX;
        int[] cStride = new int[3];
        for (int c = 0; c < 3; c++) {
            cStride[c] = 256 >> shiftX[c];
        }

        com.ebremer.jpegxl.decoder.JxlDecoder.parallelFor(fh.numGroups, group -> {
            int[][] coeffs = groupCoeffs[group];
            if (coeffs == null) {
                return;
            }
            float[][] dq = new float[3][];
            for (int c = 0; c < 3; c++) {
                dq[c] = new float[(256 >> shiftY[c]) * cStride[c]];
            }
            float[] scratch0 = new float[256 * 256];
            float[] scratch1 = new float[256 * 256];
            float[] scratch2 = new float[64];
            float[] scratch3 = new float[64];
            int gRow = group / fh.groupColumns;
            int gCol = group % fh.groupColumns;
            LfGroupData gg = lfGroups[(gRow >> 3) * fh.lfGroupColumns + (gCol >> 3)];
            int groupPosY = (gRow & 7) << 5;
            int groupPosX = (gCol & 7) << 5;

            for (float[] p : dq) {
                java.util.Arrays.fill(p, 0f);
            }

            for (int by = 0; by < gg.height8; by++) {
                for (int bx = 0; bx < gg.width8; bx++) {
                    int posK = by * gg.width8 + bx;
                    if (!gg.blockOrigin[posK]) {
                        continue;
                    }
                    int groupY = by - groupPosY;
                    int groupX = bx - groupPosX;
                    if (groupY < 0 || groupX < 0 || groupY >= 32 || groupX >= 32) {
                        continue;
                    }
                    TransformType tt = TransformType.byType(gg.blockType[posK]);
                    boolean flip = tt.flip();
                    float[][] w2 = dequant.weights[tt.parameterIndex];
                    int matrixWidth = tt.matrixWidth;
                    // dequantise
                    for (int c = 0; c < 3; c++) {
                        int sGroupY = groupY >> shiftY[c];
                        int sGroupX = groupX >> shiftX[c];
                        if (groupY != sGroupY << shiftY[c] || groupX != sGroupX << shiftX[c]) {
                            continue;
                        }
                        float sfc = scaleFactor[c] / gg.hfMul[posK];
                        int pixelGroupY = sGroupY << 3;
                        int pixelGroupX = sGroupX << 3;
                        float[] qbc = qbclut[c];
                        float[] w3 = w2[c];
                        for (int y = 0; y < tt.pixelHeight; y++) {
                            for (int x = 0; x < tt.pixelWidth; x++) {
                                if (y < tt.blockHeight && x < tt.blockWidth) {
                                    continue;
                                }
                                int pY = pixelGroupY + y;
                                int pX = pixelGroupX + x;
                                int coeff = coeffs[c][pY * cStride[c] + pX];
                                float quant = (coeff > -2 && coeff < 2)
                                        ? qbc[coeff + 1]
                                        : coeff - meta.quantBiasNumerator / coeff;
                                int wy = flip ? x : y;
                                int wx = x ^ y ^ wy;
                                dq[c][pY * cStride[c] + pX] = quant * sfc * w3[wy * matrixWidth + wx];
                            }
                        }
                    }
                }
            }

            // chroma from luma (per 64x64 tile factors); never with subsampling
            if (!fh.isSubsampled) {
                for (int by = 0; by < gg.height8; by++) {
                    for (int bx = 0; bx < gg.width8; bx++) {
                        int posK = by * gg.width8 + bx;
                        if (!gg.blockOrigin[posK]) {
                            continue;
                        }
                        int groupY = by - groupPosY;
                        int groupX = bx - groupPosX;
                        if (groupY < 0 || groupX < 0 || groupY >= 32 || groupX >= 32) {
                            continue;
                        }
                        TransformType tt = TransformType.byType(gg.blockType[posK]);
                        int pPosY = by << 3; // within the LF group, in pixels
                        int pPosX = bx << 3;
                        for (int iy = 0; iy < tt.pixelHeight; iy++) {
                            int y = pPosY + iy;
                            int fy = y >> 6;
                            for (int ix = 0; ix < tt.pixelWidth; ix++) {
                                int x = pPosX + ix;
                                int fx = x >> 6;
                                float kX = baseCorrelationX
                                        + gg.xFromY[fy * gg.tileStride + fx] / (float) colorFactor;
                                float kB = baseCorrelationB
                                        + gg.bFromY[fy * gg.tileStride + fx] / (float) colorFactor;
                                int idx = (y & 0xFF) * 256 + (x & 0xFF);
                                float dequantY = dq[1][idx];
                                dq[0][idx] += kX * dequantY;
                                dq[2][idx] += kB * dequantY;
                            }
                        }
                    }
                }
            }

            // LLF: forward-DCT the dequantised LF into the corner coefficients
            for (int by = 0; by < gg.height8; by++) {
                for (int bx = 0; bx < gg.width8; bx++) {
                    int posK = by * gg.width8 + bx;
                    if (!gg.blockOrigin[posK]) {
                        continue;
                    }
                    int groupY = by - groupPosY;
                    int groupX = bx - groupPosX;
                    if (groupY < 0 || groupX < 0 || groupY >= 32 || groupX >= 32) {
                        continue;
                    }
                    TransformType tt = TransformType.byType(gg.blockType[posK]);
                    for (int c = 0; c < 3; c++) {
                        int sGroupY = groupY >> shiftY[c];
                        int sGroupX = groupX >> shiftX[c];
                        if (groupY != sGroupY << shiftY[c] || groupX != sGroupX << shiftX[c]) {
                            continue;
                        }
                        int pixelGroupY = sGroupY << 3;
                        int pixelGroupX = sGroupX << 3;
                        int sBy = by >> shiftY[c];
                        int sBx = bx >> shiftX[c];
                        if (tt.blockHeight == 1 && tt.blockWidth == 1) {
                            dq[c][pixelGroupY * cStride[c] + pixelGroupX] =
                                    gg.lfDeq[c][sBy * gg.cWidth8[c] + sBx];
                        } else {
                            Dct.forward2D(gg.lfDeq[c], sBy * gg.cWidth8[c] + sBx, gg.cWidth8[c],
                                    dq[c], pixelGroupY * cStride[c] + pixelGroupX, cStride[c],
                                    tt.blockHeight, tt.blockWidth, scratch0, scratch1);
                            for (int y = 0; y < tt.blockHeight; y++) {
                                for (int x = 0; x < tt.blockWidth; x++) {
                                    dq[c][(pixelGroupY + y) * cStride[c] + pixelGroupX + x] *=
                                            tt.llfScale[y * tt.blockWidth + x];
                                }
                            }
                        }
                    }
                }
            }

            // inverse transforms into the frame planes
            int frameY0 = gRow << 8;
            int frameX0 = gCol << 8;
            for (int by = 0; by < gg.height8; by++) {
                for (int bx = 0; bx < gg.width8; bx++) {
                    int posK = by * gg.width8 + bx;
                    if (!gg.blockOrigin[posK]) {
                        continue;
                    }
                    int groupY = by - groupPosY;
                    int groupX = bx - groupPosX;
                    if (groupY < 0 || groupX < 0 || groupY >= 32 || groupX >= 32) {
                        continue;
                    }
                    TransformType tt = TransformType.byType(gg.blockType[posK]);
                    for (int c = 0; c < 3; c++) {
                        int sGroupY = groupY >> shiftY[c];
                        int sGroupX = groupX >> shiftX[c];
                        if (groupY != sGroupY << shiftY[c] || groupX != sGroupX << shiftX[c]) {
                            continue;
                        }
                        int stride = fh.paddedWidth >> shiftX[c];
                        int ppgY = sGroupY << 3;
                        int ppgX = sGroupX << 3;
                        int ppfY = ppgY + (frameY0 >> shiftY[c]);
                        int ppfX = ppgX + (frameX0 >> shiftX[c]);
                        Transforms.invert(tt, dq[c], cStride[c], ppgY, ppgX,
                                framePlanes[c], ppfY * stride + ppfX, stride,
                                scratch0, scratch1, scratch2, scratch3);
                    }
                }
            }
        });
    }

    /** Per-8x8-block inverse EPF sigma over the padded frame. */
    public float[] epfInverseSigma(com.ebremer.jpegxl.codestream.RestorationFilter rf) {
        int bw = fh.paddedWidth >> 3;
        int bh = fh.paddedHeight >> 3;
        float[] inverseSigma = new float[bw * bh];
        float globalScaleF = 65536.0f / globalScale;
        for (int y = 0; y < bh; y++) {
            int lfY = y >> 8;
            int bY = y & 0xFF;
            for (int x = 0; x < bw; x++) {
                int lfX = x >> 8;
                int bX = x & 0xFF;
                LfGroupData gg = lfGroups[lfY * fh.lfGroupColumns + lfX];
                if (gg == null) { // LF group skipped by a region decode
                    inverseSigma[y * bw + x] = 1f;
                    continue;
                }
                int k = bY * gg.width8 + bX;
                int sharpness = gg.sharpness[k];
                float sigma = globalScaleF * rf.epfSharpLut[Math.min(Math.max(sharpness, 0), 7)]
                        / gg.hfMul[k];
                inverseSigma[y * bw + x] = 1f / sigma;
            }
        }
        return inverseSigma;
    }

    /** Callback for decoding fixed-shape modular sub-streams. */
    public interface ModularReader {
        /** Decodes channels of the given (height, width) shapes; returns their pixels. */
        int[][] readFixedModular(Bits in, int streamIndex, int[][] shapes) throws IOException;
    }

    private static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }
}
