package com.ebremer.jpegxl.vardct;

import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/**
 * HfGlobal: the 17 dequantisation parameter sets and their expanded weight
 * matrices (18181-1 annex E.3). {@code weights[param][channel]} is a flat
 * {@code matrixHeight x matrixWidth} array of dequant multipliers.
 */
public final class DequantMatrices {

    public static final int MODE_LIBRARY = 0;
    public static final int MODE_HORNUSS = 1;
    public static final int MODE_DCT2 = 2;
    public static final int MODE_DCT4 = 3;
    public static final int MODE_DCT4_8 = 4;
    public static final int MODE_AFV = 5;
    public static final int MODE_DCT = 6;
    public static final int MODE_RAW = 7;

    private static final float[] AFV_FREQS = {
        0, 0, 0.8517778890324296f, 5.37778436506804f,
        0, 0, 4.734747904497923f, 5.449245381693219f,
        1.6598270267479331f, 4f, 7.275749096817861f, 10.423227632456525f,
        2.662932286148962f, 7.630657783650829f, 8.962388608184032f, 12.97166202570235f,
    };

    /** One parameter set: mode plus the various parameter vectors. */
    static final class Params {
        final int mode;
        final float[][] dctParam;  // distance bands for DCT-style weights
        final float[][] param;     // mode-specific extra parameters
        final float[][] params4x4; // AFV/DCT4 secondary bands
        final float denominator;
        int[][] raw;               // RAW mode integer matrices (JPEG quant tables)

        Params(int mode, float[][] dctParam, float[][] param, float[][] params4x4, float denominator) {
            this.mode = mode;
            this.dctParam = dctParam;
            this.param = param;
            this.params4x4 = params4x4;
            this.denominator = denominator;
        }
    }

    public final float[][][] weights = new float[17][3][];
    /** RAW-mode integer matrices per parameter set (null when not raw-coded). */
    public final int[][][] rawQuant = new int[17][][];
    public final int numHfPresets;

    public interface RawWeightReader {
        /** Decodes a 3-channel modular image of the given size for RAW matrices. */
        int[][] read(Bits in, int streamIndex, int height, int width) throws IOException;
    }

    public DequantMatrices(Bits in, int numGroups, int numLfGroups, RawWeightReader rawReader)
            throws IOException {
        Params[] params = new Params[17];
        boolean allDefault = in.bool();
        for (int i = 0; i < 17; i++) {
            if (allDefault) {
                params[i] = DEFAULTS[i];
            } else {
                params[i] = readParams(in, i, numLfGroups, rawReader);
            }
        }
        for (int i = 0; i < 17; i++) {
            generateWeights(params[i], i);
            rawQuant[i] = params[i].raw;
        }
        int bits = numGroups > 1 ? 32 - Integer.numberOfLeadingZeros(numGroups - 1) : 0;
        numHfPresets = 1 + in.u(bits);
    }

    private static Params readParams(Bits in, int index, int numLfGroups, RawWeightReader rawReader)
            throws IOException {
        int mode = in.u(3);
        boolean smallBlock = index >= 0 && index <= 3 || index == 9 || index == 10;
        if (mode != MODE_LIBRARY && mode != MODE_DCT && mode != MODE_RAW && !smallBlock) {
            throw new IOException("invalid dequant encoding mode " + mode + " for parameter " + index);
        }
        switch (mode) {
            case MODE_LIBRARY -> {
                return DEFAULTS[index];
            }
            case MODE_HORNUSS -> {
                float[][] m = read3xN(in, 3, 64f);
                return new Params(mode, null, m, null, 1f);
            }
            case MODE_DCT2 -> {
                float[][] m = read3xN(in, 6, 64f);
                return new Params(mode, null, m, null, 1f);
            }
            case MODE_DCT4 -> {
                float[][] m = read3xN(in, 2, 64f);
                return new Params(mode, readDctParams(in), m, null, 1f);
            }
            case MODE_DCT -> {
                return new Params(mode, readDctParams(in), null, null, 1f);
            }
            case MODE_RAW -> {
                float den = in.f16();
                TransformType tt = TransformType.byParameterIndex(index);
                int[][] planes = rawReader.read(in, 1 + 3 * numLfGroups + index,
                        tt.matrixHeight, tt.matrixWidth);
                float[][] m = new float[3][tt.matrixHeight * tt.matrixWidth];
                for (int c = 0; c < 3; c++) {
                    for (int i = 0; i < m[c].length; i++) {
                        m[c][i] = planes[c][i];
                    }
                }
                Params p = new Params(mode, null, m, null, den);
                p.raw = planes;
                return p;
            }
            case MODE_DCT4_8 -> {
                float[][] m = read3xN(in, 1, 1f);
                return new Params(mode, readDctParams(in), m, null, 1f);
            }
            case MODE_AFV -> {
                float[][] m = new float[3][9];
                for (int c = 0; c < 3; c++) {
                    for (int i = 0; i < 9; i++) {
                        m[c][i] = in.f16();
                        if (i < 6) {
                            m[c][i] *= 64f;
                        }
                    }
                }
                float[][] d = readDctParams(in);
                float[][] f = readDctParams(in);
                return new Params(mode, d, m, f, 1f);
            }
            default -> throw new IOException("bad dequant mode");
        }
    }

    private static float[][] read3xN(Bits in, int n, float scale) throws IOException {
        float[][] m = new float[3][n];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                m[c][i] = scale * in.f16();
            }
        }
        return m;
    }

    private static float[][] readDctParams(Bits in) throws IOException {
        int numParams = 1 + in.u(4);
        float[][] vals = new float[3][numParams];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < numParams; i++) {
                vals[c][i] = in.f16();
            }
            vals[c][0] *= 64f;
        }
        return vals;
    }

    private static float quantMult(float v) {
        return v >= 0 ? 1f + v : 1f / (1f - v);
    }

    private static float interpolate(float scaledPos, float[] bands) {
        int len = bands.length - 1;
        if (len == 0) {
            return bands[0];
        }
        int idx = (int) scaledPos;
        if (idx + 1 > len) {
            return bands[len];
        }
        float frac = scaledPos - idx;
        float a = bands[idx];
        float b = bands[idx + 1];
        return a * (float) Math.pow(b / a, frac);
    }

    private static float[] dctQuantWeights(int height, int width, float[] params) {
        float[] bands = new float[params.length];
        bands[0] = params[0];
        for (int i = 1; i < bands.length; i++) {
            bands[i] = bands[i - 1] * quantMult(params[i]);
        }
        float[] weights = new float[height * width];
        float scale = (bands.length - 1) / ((float) Math.sqrt(2) + 1e-6f);
        for (int y = 0; y < height; y++) {
            float dy = y * scale / (height - 1);
            float dy2 = dy * dy;
            for (int x = 0; x < width; x++) {
                float dx = x * scale / (width - 1);
                float dist = (float) Math.sqrt(dx * dx + dy2);
                weights[y * width + x] = interpolate(dist, bands);
            }
        }
        return weights;
    }

    private void generateWeights(Params p, int index) throws IOException {
        TransformType tt = TransformType.byParameterIndex(index);
        int h = tt.matrixHeight;
        int w = tt.matrixWidth;
        for (int c = 0; c < 3; c++) {
            float[] weight;
            switch (p.mode) {
                case MODE_DCT -> weight = dctQuantWeights(h, w, p.dctParam[c]);
                case MODE_DCT4 -> {
                    weight = new float[64];
                    float[] w4 = dctQuantWeights(4, 4, p.dctParam[c]);
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            weight[y * 8 + x] = w4[(y / 2) * 4 + x / 2];
                        }
                    }
                    weight[8] /= p.param[c][0];      // (1,0)
                    weight[1] /= p.param[c][0];      // (0,1)
                    weight[9] /= p.param[c][1];      // (1,1)
                }
                case MODE_DCT2 -> {
                    weight = new float[64];
                    weight[0] = 1f;
                    weight[1] = weight[8] = p.param[c][0];
                    weight[9] = p.param[c][1];
                    for (int y = 0; y < 2; y++) {
                        for (int x = 0; x < 2; x++) {
                            weight[y * 8 + x + 2] = p.param[c][2];
                            weight[(x + 2) * 8 + y] = p.param[c][2];
                            weight[(y + 2) * 8 + (x + 2)] = p.param[c][3];
                        }
                    }
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            weight[y * 8 + x + 4] = p.param[c][4];
                            weight[(x + 4) * 8 + y] = p.param[c][4];
                            weight[(y + 4) * 8 + (x + 4)] = p.param[c][5];
                        }
                    }
                }
                case MODE_HORNUSS -> {
                    weight = new float[64];
                    java.util.Arrays.fill(weight, p.param[c][0]);
                    weight[9] = p.param[c][2];
                    weight[1] = weight[8] = p.param[c][1];
                    weight[0] = 1f;
                }
                case MODE_DCT4_8 -> {
                    weight = new float[64];
                    float[] w48 = dctQuantWeights(4, 8, p.dctParam[c]);
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            weight[y * 8 + x] = w48[(y / 2) * 8 + x];
                        }
                    }
                    weight[8] /= p.param[c][0]; // (1,0)
                }
                case MODE_AFV -> weight = afvWeights(p, c);
                case MODE_RAW -> {
                    weight = new float[h * w];
                    for (int i = 0; i < weight.length; i++) {
                        weight[i] = p.param[c][i] * p.denominator;
                    }
                }
                default -> throw new IOException("bad dequant mode");
            }
            if (p.mode != MODE_RAW) {
                for (int i = 0; i < weight.length; i++) {
                    if (!(weight[i] > 0f) || !Float.isFinite(weight[i])) {
                        throw new IOException("non-positive dequant weight");
                    }
                    weight[i] = 1f / weight[i];
                }
            }
            weights[index][c] = weight;
        }
    }

    private static float[] afvWeights(Params p, int c) throws IOException {
        float[] w48 = dctQuantWeights(4, 8, p.dctParam[c]);
        float[] w44 = dctQuantWeights(4, 4, p.params4x4[c]);
        float low = 0.8517778890324296f;
        float high = 12.97166202570235f;
        float[] bands = new float[4];
        bands[0] = p.param[c][5];
        if (bands[0] < 0) {
            throw new IOException("negative AFV band");
        }
        for (int i = 1; i < 4; i++) {
            bands[i] = bands[i - 1] * quantMult(p.param[c][i + 5]);
            if (bands[i] < 0) {
                throw new IOException("negative AFV band");
            }
        }
        float[] weight = new float[64];
        weight[0] = 1f;
        weight[1 * 8] = p.param[c][0];
        weight[1] = p.param[c][1];
        weight[2 * 8] = p.param[c][2];
        weight[2] = p.param[c][3];
        weight[2 * 8 + 2] = p.param[c][4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (x < 2 && y < 2) {
                    continue;
                }
                float pos = (AFV_FREQS[y * 4 + x] - low) / (high - low);
                weight[(2 * y) * 8 + 2 * x] = interpolate(pos, bands);
            }
            for (int x = 0; x < 8; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                weight[(2 * y + 1) * 8 + x] = w48[y * 8 + x];
            }
            for (int x = 0; x < 4; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                weight[(2 * y) * 8 + (2 * x + 1)] = w44[y * 4 + x];
            }
        }
        return weight;
    }

    // ------------------------------------------------------------ defaults

    private static float[] seq(float first, float... rest) {
        float[] arr = new float[rest.length + 1];
        arr[0] = first;
        System.arraycopy(rest, 0, arr, 1, rest.length);
        return arr;
    }

    private static final float[] SEQ_A = {-1.025f, -0.78f, -0.65012f, -0.19041574084286472f,
        -0.20819395464f, -0.421064f, -0.32733845535848671f};
    private static final float[] SEQ_B = {-0.3041958212306401f, -0.3633036457487539f, -0.35660379990111464f,
        -0.3443074455424403f, -0.33699592683512467f, -0.30180866526242109f, -0.27321683125358037f};
    private static final float[] SEQ_C = {-1.2f, -1.2f, -0.8f, -0.7f, -0.7f, -0.4f, -0.5f};

    private static final float[][] DCT4X4_PARAMS = {
        {2200f, 0.0f, 0.0f, 0.0f},
        {392f, 0.0f, 0.0f, 0.0f},
        {112f, -0.25f, -0.25f, -0.5f},
    };
    private static final float[][] DCT4X8_PARAMS = {
        {2198.050556016380522f, -0.96269623020744692f, -0.76194253026666783f, -0.6551140670773547f},
        {764.3655248643528689f, -0.92630200888366945f, -0.9675229603596517f, -0.27845290869168118f},
        {527.107573587542228f, -1.4594385811273854f, -1.450082094097871593f, -1.5843722511996204f},
    };

    private static final Params[] DEFAULTS = new Params[17];

    static {
        DEFAULTS[0] = new Params(MODE_DCT, new float[][] {
            {3150.0f, 0.0f, -0.4f, -0.4f, -0.4f, -2.0f},
            {560.0f, 0.0f, -0.3f, -0.3f, -0.3f, -0.3f},
            {512.0f, -2.0f, -1.0f, 0.0f, -1.0f, -2.0f},
        }, null, null, 1f);
        DEFAULTS[1] = new Params(MODE_HORNUSS, null, new float[][] {
            {280.0f, 3160.0f, 3160.0f},
            {60.0f, 864.0f, 864.0f},
            {18.0f, 200.0f, 200.0f},
        }, null, 1f);
        DEFAULTS[2] = new Params(MODE_DCT2, null, new float[][] {
            {3840.0f, 2560.0f, 1280.0f, 640.0f, 480.0f, 300.0f},
            {960.0f, 640.0f, 320.0f, 180.0f, 140.0f, 120.0f},
            {640.0f, 320.0f, 128.0f, 64.0f, 32.0f, 16.0f},
        }, null, 1f);
        DEFAULTS[3] = new Params(MODE_DCT4, DCT4X4_PARAMS, new float[][] {
            {1.0f, 1.0f}, {1.0f, 1.0f}, {1.0f, 1.0f},
        }, null, 1f);
        DEFAULTS[4] = new Params(MODE_DCT, new float[][] {
            {8996.8725711814115328f, -1.3000777393353804f, -0.49424529824571225f, -0.439093774457103443f,
                -0.6350101832695744f, -0.90177264050827612f, -1.6162099239887414f},
            {3191.48366296844234752f, -0.67424582104194355f, -0.80745813428471001f, -0.44925837484843441f,
                -0.35865440981033403f, -0.31322389111877305f, -0.37615025315725483f},
            {1157.50408145487200256f, -2.0531423165804414f, -1.4f, -0.50687130033378396f,
                -0.42708730624733904f, -1.4856834539296244f, -4.9209142884401604f},
        }, null, null, 1f);
        DEFAULTS[5] = new Params(MODE_DCT, new float[][] {
            {15718.40830982518931456f, -1.025f, -0.98f, -0.9012f, -0.4f, -0.48819395464f, -0.421064f, -0.27f},
            {7305.7636810695983104f, -0.8041958212306401f, -0.7633036457487539f, -0.55660379990111464f,
                -0.49785304658857626f, -0.43699592683512467f, -0.40180866526242109f, -0.27321683125358037f},
            {3803.53173721215041536f, -3.060733579805728f, -2.0413270132490346f, -2.0235650159727417f,
                -0.5495389509954993f, -0.4f, -0.4f, -0.3f},
        }, null, null, 1f);
        DEFAULTS[6] = new Params(MODE_DCT, new float[][] {
            {7240.7734393502f, -0.7f, -0.7f, -0.2f, -0.2f, -0.2f, -0.5f},
            {1448.15468787004f, -0.5f, -0.5f, -0.5f, -0.2f, -0.2f, -0.2f},
            {506.854140754517f, -1.4f, -0.2f, -0.5f, -0.5f, -1.5f, -3.6f},
        }, null, null, 1f);
        DEFAULTS[7] = new Params(MODE_DCT, new float[][] {
            {16283.2494710648897f, -1.7812845336559429f, -1.6309059012653515f,
                -1.0382179034313539f, -0.85f, -0.7f, -0.9f, -1.2360638576849587f},
            {5089.15750884921511936f, -0.320049391452786891f, -0.35362849922161446f,
                -0.30340000000000003f, -0.61f, -0.5f, -0.5f, -0.6f},
            {3397.77603275308720128f, -0.321327362693153371f, -0.34507619223117997f,
                -0.70340000000000003f, -0.9f, -1.0f, -1.0f, -1.1754605576265209f},
        }, null, null, 1f);
        DEFAULTS[8] = new Params(MODE_DCT, new float[][] {
            {13844.97076442300573f, -0.97113799999999995f, -0.658f, -0.42026f, -0.22712f, -0.2206f, -0.226f, -0.6f},
            {4798.964084220744293f, -0.61125308982767057f, -0.83770786552491361f, -0.79014862079498627f,
                -0.2692727459704829f, -0.38272769465388551f, -0.22924222653091453f, -0.20719098826199578f},
            {1807.236946760964614f, -1.2f, -1.2f, -0.7f, -0.7f, -0.7f, -0.4f, -0.5f},
        }, null, null, 1f);
        DEFAULTS[9] = new Params(MODE_DCT4_8, DCT4X8_PARAMS,
                new float[][] {{1.0f}, {1.0f}, {1.0f}}, null, 1f);
        DEFAULTS[10] = new Params(MODE_AFV, DCT4X8_PARAMS, new float[][] {
            {3072f, 3072f, 256f, 256f, 256f, 414f, 0.0f, 0.0f, 0.0f},
            {1024f, 1024f, 50.0f, 50.0f, 50.0f, 58f, 0.0f, 0.0f, 0.0f},
            {384f, 384f, 12.0f, 12.0f, 12.0f, 22f, -0.25f, -0.25f, -0.25f},
        }, DCT4X4_PARAMS, 1f);
        DEFAULTS[11] = new Params(MODE_DCT, new float[][] {
            seq(23966.1665298448605f, SEQ_A), seq(8380.19148390090414f, SEQ_B), seq(4493.02378009847706f, SEQ_C),
        }, null, null, 1f);
        DEFAULTS[12] = new Params(MODE_DCT, new float[][] {
            seq(15358.89804933239925f, SEQ_A), seq(5597.360516150652990f, SEQ_B), seq(2919.961618960011210f, SEQ_C),
        }, null, null, 1f);
        DEFAULTS[13] = new Params(MODE_DCT, new float[][] {
            seq(47932.3330596897210f, SEQ_A), seq(16760.38296780180828f, SEQ_B), seq(8986.04756019695412f, SEQ_C),
        }, null, null, 1f);
        DEFAULTS[14] = new Params(MODE_DCT, new float[][] {
            seq(30717.796098664792f, SEQ_A), seq(11194.72103230130598f, SEQ_B), seq(5839.92323792002242f, SEQ_C),
        }, null, null, 1f);
        DEFAULTS[15] = new Params(MODE_DCT, new float[][] {
            seq(95864.6661193794420f, SEQ_A), seq(33520.76593560361656f, SEQ_B), seq(17972.09512039390824f, SEQ_C),
        }, null, null, 1f);
        DEFAULTS[16] = new Params(MODE_DCT, new float[][] {
            seq(61435.5921973295970f, SEQ_A), seq(24209.44206460261196f, SEQ_B), seq(12979.84647584004484f, SEQ_C),
        }, null, null, 1f);
    }
}
