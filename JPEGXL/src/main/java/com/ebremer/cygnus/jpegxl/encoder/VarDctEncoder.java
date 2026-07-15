package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.RestorationFilter;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.decoder.RestorationFilters;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import com.ebremer.cygnus.jpegxl.vardct.Dct;
import com.ebremer.cygnus.jpegxl.vardct.DequantMatrices;
import com.ebremer.cygnus.jpegxl.vardct.HfPass;
import com.ebremer.cygnus.jpegxl.vardct.SmallDct;
import com.ebremer.cygnus.jpegxl.vardct.TransformType;
import com.ebremer.cygnus.jpegxl.vardct.Transforms;
import com.ebremer.cygnus.jpegxl.vardct.VarDctState;
import java.io.IOException;

/**
 * Lossy (VarDCT) encoder: XYB colour, 8x8 and 16x16 DCT blocks chosen by a
 * rate estimate, activity-masked adaptive quantisation, default
 * dequantisation tables and a single pass. The {@code distance} parameter
 * follows cjxl's Butteraugli-distance convention loosely (1 is visually
 * lossless-ish, larger is smaller/worse).
 *
 * <p>The pixel work is done through a <em>window</em>: a horizontal slice of
 * the image plus the halo rows the gaborish pre-sharpening reaches into.
 * {@link #encode} loads the whole image as one window; {@link JxlStreamingEncoder}
 * loads one 256-row band at a time. Both drive the same quantiser, so a band
 * is coded exactly as it would have been inside the whole image.
 */
public final class VarDctEncoder {

    static final int GROUP_DIM = 256;

    /**
     * Steps of the gaborish pre-sharpening's fixed-point iteration.
     *
     * <p>Gaborish is a symmetric blur whose frequency response runs from 1 at DC
     * down to 0.443 at the worst corner, so inverting it is a well-conditioned
     * solve (condition number 2.26) — but a solve, and the iteration has to be
     * run out. Under-relaxed at {@link #GABORISH_OMEGA} = 1 it contracts by only
     * 0.557 a step at that corner, and the three steps this used to take left a
     * sixth of the high-frequency error standing. That residual does not shrink
     * with the quantiser, so it became the floor under every lossy encode: a
     * greyscale image at distance 0.1 came back with a mean error of 1.24 out of
     * 255 no matter how many bits it was given.
     */
    private static final int GABORISH_STEPS = 8;

    /**
     * Relaxation for that iteration: {@code 2 / (lambdaMin + lambdaMax)}, the
     * factor that minimises the worst-case contraction over gaborish's spectrum.
     * It takes the per-step contraction from 0.557 to 0.386, which is most of the
     * reason eight steps are enough where twelve would otherwise be needed — and
     * every step is a row of halo.
     */
    private static final float GABORISH_OMEGA = 1.3863f;

    /**
     * Rows of context the gaborish pre-sharpening needs on each side of a
     * window. Each step is one 3x3 convolution, so contamination from the
     * buffer's edge clamp creeps exactly one row inward per step: give the
     * window that many rows and its own rows come out as they would have in the
     * whole image. Rows nearer the buffer edge than that are halo, not output.
     */
    static final int GABORISH_HALO = GABORISH_STEPS;

    /** The default HF block context map (readHfBlockContext's default). */
    private static final int[] DEFAULT_CTX_MAP = {
        0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
        7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
    };
    static final int NUM_BLOCK_CLUSTERS = 15;
    /** Coefficient contexts per HF preset. */
    static final int CONTEXTS_PER_PRESET = 495 * NUM_BLOCK_CLUSTERS;
    private static final int[] C_MAP = {1, 0, 2};

    private static final boolean NO_FILTERS = Boolean.getBoolean("jxl.enc.nofilters");
    private static final boolean NO_CFL = Boolean.getBoolean("jxl.enc.nocfl");

    private static final TransformType DCT8 = TransformType.byType(0);
    private static final TransformType DCT2 = TransformType.byType(2);   // hierarchical 2/4/8
    private static final TransformType DCT4 = TransformType.byType(3);   // four 4x4 quadrants
    private static final TransformType DCT4_8 = TransformType.byType(12); // two 4x8 halves
    private static final TransformType DCT8_4 = TransformType.byType(13); // two 8x4 halves
    private static final TransformType HORNUSS = TransformType.byType(1);  // spike model
    private static final TransformType AFV0 = TransformType.byType(14);    // asymmetric corner
    private static final TransformType AFV1 = TransformType.byType(15);
    private static final TransformType AFV2 = TransformType.byType(16);
    private static final TransformType AFV3 = TransformType.byType(17);
    private static final TransformType DCT16 = TransformType.byType(4);
    private static final TransformType DCT32 = TransformType.byType(5);
    private static final TransformType DCT8_16 = TransformType.byType(7);  // 8 tall, 16 wide
    private static final TransformType DCT16_8 = TransformType.byType(6);  // 16 tall, 8 wide
    private static final TransformType DCT32_16 = TransformType.byType(10); // 32 tall, 16 wide
    private static final TransformType DCT16_32 = TransformType.byType(11); // 16 tall, 32 wide
    private static final TransformType DCT64 = TransformType.byType(18);
    private static final TransformType DCT64_32 = TransformType.byType(19); // 64 tall, 32 wide
    private static final TransformType DCT32_64 = TransformType.byType(20); // 32 tall, 64 wide
    private static final TransformType DCT128 = TransformType.byType(21);
    private static final TransformType DCT128_64 = TransformType.byType(22); // 128 tall, 64 wide
    private static final TransformType DCT64_128 = TransformType.byType(23); // 64 tall, 128 wide
    private static final TransformType DCT256 = TransformType.byType(24);
    private static final TransformType DCT256_128 = TransformType.byType(25); // 256 tall, 128 wide
    private static final TransformType DCT128_256 = TransformType.byType(26); // 128 tall, 256 wide
    // The 128 and 256 scales are OPT-IN (off by default): measured, the token-count
    // rate estimate cannot price a block that large — when it picks one it bloats
    // (6x on a smooth ramp at distance 2), and where a big block might help it is
    // not picked. The machinery is generalised to produce them for whoever wires a
    // better estimate, but the default encoder does not offer them.
    private static final boolean DCT128_ON = Boolean.getBoolean("jxl.enc.dct128");
    private static final boolean DCT256_ON = Boolean.getBoolean("jxl.enc.dct256");
    /**
     * The largest transform this encoder chooses, and its LLF (side/8) squared.
     * Sized to the enabled scales so the sweep scratch stays small in the common
     * case: 64 by default, up to 256 only when the big scales are opted in.
     */
    private static final int MAX_DIM = DCT256_ON ? 256 : DCT128_ON ? 128 : 64;
    private static final int MAX_LLF = (MAX_DIM / 8) * (MAX_DIM / 8);
    private static final boolean NO_DCT32 = Boolean.getBoolean("jxl.enc.nodct32");
    private static final boolean NO_DCT64 = Boolean.getBoolean("jxl.enc.nodct64");
    private static final boolean NO_RECT = Boolean.getBoolean("jxl.enc.norect");
    private static final boolean NO_SMALL = Boolean.getBoolean("jxl.enc.nosmall");
    /**
     * How much cheaper DCT2/DCT4 must estimate before replacing a plain DCT8. The
     * margin does the discriminating: on hard edges and text — the piecewise-flat
     * blocks these transforms are for — they win by a wide margin, while on
     * photographic texture the coarse rate proxy at most calls it a hair cheaper
     * when it is really a hair dearer, so requiring a clear 20% win takes the
     * former and leaves the latter (a photograph comes out unchanged; a graphic a
     * couple of percent smaller).
     */
    private static final double SMALL_GAIN = 0.80;
    /**
     * How much cheaper a rectangular transform must estimate before it replaces
     * the four 8x8s it covers. Like {@link #DCT32_GAIN}, below 1 by a margin: the
     * rate estimate is blind to the DC image a bigger block rearranges, so a
     * rectangular block is taken only where it clearly pays — content smooth
     * along one axis and detailed across the other, a horizon or a wall's edge.
     */
    private static final double RECT_GAIN = 0.90;
    /** Count of rectangular blocks committed, for tests to confirm the path fires. */
    public static final java.util.concurrent.atomic.AtomicLong RECT_BLOCKS =
            new java.util.concurrent.atomic.AtomicLong();
    /** Per-transform-type origin counts across encodes, for tests to confirm which fire. */
    public static final java.util.concurrent.atomic.AtomicLongArray TYPE_HIST =
            new java.util.concurrent.atomic.AtomicLongArray(27);
    /**
     * How much cheaper a square transform (32x32 up to 256x256) must estimate
     * before it replaces the four-quadrant subdivision under it. Below 1 by a
     * margin because the rate estimate is a coarse token count that does not see
     * the DC image, which a big block shifts around: requiring a clear win keeps it
     * to the smooth regions where it plainly helps (a large photo's sky and road
     * come out ~5% smaller at 32, more at the larger scales) and away from the ones
     * where the estimate would misjudge it. The same margin serves every scale.
     */
    private static final double SQUARE_GAIN = 0.85;
    /**
     * The distance below which the big scales (64x64 and up) are not offered at
     * all. Their rate estimate — a token count blind to the entropy coder and the
     * DC image — badly misjudges a big block near lossless, where a gentle
     * gradient's DCT decays too slowly to throw away and the block keeps a wide
     * skirt of small coefficients the estimate underprices; measured, it can bloat
     * a smooth image by more than half at distance 1. A big block only earns its
     * place once the quantiser is coarse enough to clear that skirt, which is
     * distance 2 and up (5-12% smaller on a large smooth image); below it the 32x32
     * hierarchy is left to decide. This mirrors how libjxl reserves its largest
     * transforms for lower qualities.
     */
    private static final float BIG_MIN_DISTANCE = 2.0f;

    // adaptive quantisation limits (relative to the base multiplier)
    private static final float AQ_MIN = 0.75f;
    private static final float AQ_MAX = 1.35f;

    // ------------------------------------------------- whole-image parameters

    private final int width;
    private final int height;
    private final int paddedWidth;
    private final int paddedHeight;
    private final BitDepth depth;
    private final int bits;
    private final double maxVal;   // integer samples only
    private final boolean grey;
    private final java.util.List<ExtraChannelInfo> extras;
    private float[] noiseLut;      // 8-point photon-noise strengths, or null for no noise
    private boolean animated;      // this frame is one of an animation (carries a duration)
    private long animDuration;     // ticks this frame shows for
    private boolean animLast = true; // whether this is the animation's final frame
    private final int globalScale;
    private final float distance;   // the requested distance, gates the largest blocks
    private int hfMul;
    private final float[] scaledDequant = new float[3];
    private final float[] baseSfc = new float[3];   // before the block multiplier
    private float[][][] weightsOf;                  // [parameterIndex][channel]
    private float[][] customDct8Params;             // MODE_DCT bands for the DCT8 set, or null = default
    private final ImageMetadata opsin;              // defaults: opsin matrix, quant biases
    private final RestorationFilter filter = RestorationFilter.defaults();
    final int w8;                                   // cell columns, whole image
    final int h8Total;                              // cell rows, whole image
    private final int tilesX;                       // CfL tile columns, whole image

    // ------------------------------------------------------- the loaded window

    private final float[][] xyb = new float[3][];   // [channel][paddedWidth * bufRows]
    private int bufRows;         // rows in the xyb buffer, halo included
    private int y0;              // buffer row holding the window's first pixel row
    private int cellRow0;        // image cell row of the window's first cell row
    private int h8;              // cell rows in the window
    private int tileRow0;        // image CfL tile row of the window's first tile row
    private int tilesY;          // CfL tile rows in the window
    private int[] xFromY;
    private int[] bFromY;
    private float[] activity;    // per cell, before normalisation
    private byte[] blockType;    // per cell: transform type at origins, negative if covered
    private int[] blockMul;      // per cell: the block's quantisation multiplier
    private byte[] sharp;        // per cell: EPF sharpness, 0 = leave the block alone
    private int[][] dcQuant;
    private int[][][] hfQuant;   // [channel][origin cell] -> natural coefficients

    /**
     * One group's coefficient tokens — context and value side by side, because
     * a token is two ints and boxing each pair costs more in object header than
     * in payload, on tens of millions of them.
     */
    static final class Tokens {
        int[] ctx = new int[1 << 12];
        int[] val = new int[1 << 12];
        int n;

        void add(int context, int value) {
            if (n == ctx.length) {
                ctx = java.util.Arrays.copyOf(ctx, n * 2);
                val = java.util.Arrays.copyOf(val, n * 2);
            }
            ctx[n] = context;
            val[n] = value;
            n++;
        }
    }

    /**
     * The per-cell decisions an LF group writes. They outlive the band that
     * made them: an LF group is 2048 pixels tall, eight bands' worth.
     */
    static final class Cells {
        final int row0;     // image cell row of the first row held
        final byte[] type;
        final int[] mul;
        final byte[] sharp;
        final int[][] dc;
        final int[] xFromY;
        final int[] bFromY;

        Cells(int w8, int tilesX, int row0, int rows) {
            this.row0 = row0;
            this.type = new byte[w8 * rows];
            this.mul = new int[w8 * rows];
            this.sharp = new byte[w8 * rows];
            this.dc = new int[3][w8 * rows];
            this.xFromY = new int[tilesX * ceilDiv(rows, 8)];
            this.bFromY = new int[tilesX * ceilDiv(rows, 8)];
        }
    }

    // ---------------------------------------------------------- construction

    /**
     * Parameters only, no pixels: the caller supplies the image a window at a
     * time through {@link #loadWindow}.
     */
    VarDctEncoder(int width, int height, int bits, boolean grey, boolean alpha, float distance) {
        this(width, height, BitDepth.of(bits), grey,
                JxlEncoder.alphaOnly(BitDepth.of(bits), alpha, false), distance);
    }

    VarDctEncoder(int width, int height, BitDepth depth, boolean grey,
            java.util.List<ExtraChannelInfo> extras, float distance) {
        this.width = width;
        this.height = height;
        this.paddedWidth = (width + 7) & ~7;
        this.paddedHeight = (height + 7) & ~7;
        this.depth = depth;
        this.bits = depth.bitsPerSample;
        this.maxVal = depth.floatingPoint ? 1 : (1 << depth.bitsPerSample) - 1;
        this.grey = grey;
        this.extras = java.util.List.copyOf(extras);
        float d = Math.max(0.1f, distance);
        this.distance = d;
        this.globalScale = Math.max(1, Math.min(65535, Math.round(4096f / d)));
        this.hfMul = baseHfMul(d);
        this.w8 = paddedWidth >> 3;
        this.h8Total = paddedHeight >> 3;
        this.tilesX = ceilDiv(paddedWidth, 64);

        this.opsin = new ImageMetadata();
        float[] lfDequant = {1f / 4096f, 1f / 512f, 1f / 256f};
        int quantLf = 16;
        for (int i = 0; i < 3; i++) {
            scaledDequant[i] = (1 << 16) * lfDequant[i] / (globalScale * quantLf);
            baseSfc[i] = 65536.0f / globalScale; // qm scales are neutral (2)
        }
        DequantMatrices dequant;
        try {
            dequant = new DequantMatrices(new Bits(new byte[] {0x01}), 1, 1, null); // all defaults
        } catch (IOException e) {
            throw new IllegalStateException("default dequant matrices", e);
        }
        weightsOf = dequant.weights;
    }

    /** The HF block multiplier a distance asks for, before activity masking. */
    static int baseHfMul(float distance) {
        return Math.max(1, Math.min(200, Math.round(32f / Math.max(0.1f, distance))));
    }

    /** Overrides the HF multiplier; the DC scale stays where the distance put it. */
    void setHfMul(int mul) {
        this.hfMul = Math.max(1, Math.min(200, mul));
    }

    // ---------------------------------------------------------------- window

    /**
     * Loads padded rows {@code [top, top + rows)} as the current window, taking
     * the pixels from {@code src}, which holds image rows
     * {@code [srcY0, srcY0 + srcRows)} row-major at the image's full width.
     * {@code halo} rows on each side are read as well where the image has them,
     * converted to XYB and pre-sharpened along with the window, then dropped —
     * they exist so the window's own rows are exact.
     */
    void loadWindow(int[][] src, int srcY0, int srcRows, int top, int rows, int halo) {
        int bufTop = Math.max(0, top - halo);
        int bufEnd = Math.min(paddedHeight, top + rows + halo);
        this.bufRows = bufEnd - bufTop;
        this.y0 = top - bufTop;
        this.cellRow0 = top >> 3;
        this.h8 = rows >> 3;
        this.tileRow0 = top >> 6;
        this.tilesY = ceilDiv(rows, 64);
        this.activity = null;
        toXyb(src, srcY0, srcRows, bufTop);
        inverseGaborish();
    }

    /** The whole image as one window. */
    private void loadWhole(int[][] planes) {
        loadWindow(planes, 0, height, 0, paddedHeight, 0);
    }

    // ------------------------------------------------------------ parallelism

    /**
     * Runs {@code body} over {@code [0, n)}, in parallel once the window is big
     * enough to pay for it. Every use below is over disjoint output slices with
     * no accumulation across them, so the result does not depend on the order —
     * a band comes out the same however many threads touched it.
     */
    private void sweep(int n, java.util.function.IntConsumer body) {
        if (n < 4 || (long) paddedWidth * bufRows < (1 << 16)
                || Runtime.getRuntime().availableProcessors() < 2) {
            for (int i = 0; i < n; i++) {
                body.accept(i);
            }
            return;
        }
        java.util.stream.IntStream.range(0, n).parallel().forEach(body);
    }

    // --------------------------------------------------------------- colour

    private void toXyb(int[][] src, int srcY0, int srcRows, int bufTop) {
        // forward opsin: invert the decoder's (matrix * itScale)
        float itScale = 255f / opsin.intensityTarget;
        double[] inv = new double[9];
        for (int i = 0; i < 9; i++) {
            inv[i] = opsin.opsinInverse[i] * itScale;
        }
        double[] fwd = invert3x3(inv);
        double[] bias = {opsin.opsinBias[0], opsin.opsinBias[1], opsin.opsinBias[2]};
        double[] cbrtBias = {Math.cbrt(bias[0]), Math.cbrt(bias[1]), Math.cbrt(bias[2])};

        for (int c = 0; c < 3; c++) {
            xyb[c] = new float[paddedWidth * bufRows];
        }
        int lastRow = Math.min(bufTop + bufRows, height) - 1;
        if (bufTop < srcY0 || lastRow - srcY0 >= srcRows) {
            throw new IllegalArgumentException("window rows " + bufTop + ".." + lastRow
                    + " outside the supplied rows");
        }
        sweep(bufRows, by -> {
            // rows past the image's bottom edge repeat its last row
            int sy = Math.min(bufTop + by, height - 1) - srcY0;
            for (int x = 0; x < paddedWidth; x++) {
                int sx = Math.min(x, width - 1);
                int i = sy * width + sx;
                double r = srgbToLinear(colour(src[0][i]));
                double g = grey ? r : srgbToLinear(colour(src[1][i]));
                double b = grey ? r : srgbToLinear(colour(src[2][i]));
                double mixL = fwd[0] * r + fwd[1] * g + fwd[2] * b;
                double mixM = fwd[3] * r + fwd[4] * g + fwd[5] * b;
                double mixS = fwd[6] * r + fwd[7] * g + fwd[8] * b;
                double gl = Math.cbrt(mixL - bias[0]) + cbrtBias[0];
                double gm = Math.cbrt(mixM - bias[1]) + cbrtBias[1];
                double gs = Math.cbrt(mixS - bias[2]) + cbrtBias[2];
                int o = by * paddedWidth + x;
                xyb[0][o] = (float) ((gl - gm) / 2);   // X
                xyb[1][o] = (float) ((gl + gm) / 2);   // Y
                xyb[2][o] = (float) (gs);              // B
            }
        });
    }

    /**
     * The colour value a coded sample stands for. An integer sample is a
     * fraction of full scale; a float sample already <em>is</em> the value, which
     * is the whole point of coding one — no quantisation grid stands between the
     * caller's number and the colour it meant.
     */
    private double colour(int sample) {
        return depth.floatingPoint ? depth.sampleToFloat(sample) : sample / maxVal;
    }

    /**
     * The inverse of the decoder's {@code Transfer.srgbFromLinear}, mirror and
     * all. Integer samples are never negative so the mirror never mattered, but
     * float samples are — the conformance corpus's own float image runs from -2
     * to 2 — and the decoder encodes a negative through the sign-mirrored curve,
     * so this has to undo exactly that. Neither side clamps above 1 either, which
     * is what lets a float carry more than the display range.
     */
    private static double srgbToLinear(double v) {
        if (v < 0) {
            return -srgbToLinear(-v);
        }
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

    /**
     * Pre-sharpens the XYB buffer so the decoder's gaborish convolution lands
     * back on the source: x is refined until gab(x) = original, by
     * {@link #GABORISH_STEPS} relaxed fixed-point steps of
     * {@code x += omega * (original - gab(x))}. Kernel and edge clamping mirror
     * the decoder's, and contamination from the buffer's own edges creeps inward
     * one row per step — which is what {@link #GABORISH_HALO} pays for.
     */
    private void inverseGaborish() {
        if (NO_FILTERS) {
            return;
        }
        float w1 = 0.115169525f;
        float w2 = 0.061248592f;
        float mult = 1f / (1f + 4f * (w1 + w2));
        float base = mult;
        float adjW = w1 * mult;
        float diagW = w2 * mult;
        float omega = GABORISH_OMEGA;
        int w = paddedWidth;
        int h = bufRows;
        int lastX = w - 1;
        float[] g = new float[w * h];
        for (int c = 0; c < 3; c++) {
            float[] target = xyb[c];
            float[] x = target.clone();
            for (int iter = 0; iter < GABORISH_STEPS; iter++) {
                sweep(h, y -> {
                    int row = y * w;
                    int rowN = (y == 0 ? y : y - 1) * w;
                    int rowS = (y + 1 >= h ? y : y + 1) * w;
                    for (int px = 0; px < w; px++) {
                        int wx = px == 0 ? px : px - 1;
                        int ex = px == lastX ? px : px + 1;
                        float adj = x[row + wx] + x[row + ex] + x[rowN + px] + x[rowS + px];
                        float diag = x[rowN + wx] + x[rowN + ex] + x[rowS + wx] + x[rowS + ex];
                        g[row + px] = base * x[row + px] + adjW * adj + diagW * diag;
                    }
                });
                sweep(h, y -> {
                    for (int i = y * w; i < y * w + w; i++) {
                        x[i] += omega * (target[i] - g[i]);
                    }
                });
            }
            xyb[c] = x;
        }
    }

    /**
     * Per-64x64-tile chroma-from-luma factors from pixel-domain covariance
     * (the DCT is linear, so the coefficient-domain relation matches): the
     * least-squares k of chroma = k * luma with tile means removed, on the
     * decoder's /84 scale. Tiles do not straddle bands, so a window's tiles
     * are the image's tiles.
     */
    private void computeCfl() {
        xFromY = new int[tilesY * tilesX];
        bFromY = new int[tilesY * tilesX];
        if (NO_CFL) {
            return;
        }
        int windowRows = h8 << 3;
        sweep(tilesY, ty -> {
            for (int tx = 0; tx < tilesX; tx++) {
                int y1 = Math.min((ty + 1) * 64, windowRows);
                int x1 = Math.min((tx + 1) * 64, paddedWidth);
                double sy = 0;
                double sx = 0;
                double sb = 0;
                double syy = 0;
                double sxy = 0;
                double syb = 0;
                int n = 0;
                for (int y = ty * 64; y < y1; y++) {
                    int row = (y0 + y) * paddedWidth;
                    for (int x = tx * 64; x < x1; x++) {
                        double vy = xyb[1][row + x];
                        double vx = xyb[0][row + x];
                        double vb = xyb[2][row + x];
                        sy += vy;
                        sx += vx;
                        sb += vb;
                        syy += vy * vy;
                        sxy += vy * vx;
                        syb += vy * vb;
                        n++;
                    }
                }
                double varY = syy - sy * sy / n;
                int t = ty * tilesX + tx;
                if (varY > 1e-9) {
                    double kx = (sxy - sy * sx / n) / varY;
                    double kb = (syb - sy * sb / n) / varY;
                    xFromY[t] = Math.max(-128, Math.min(127, (int) Math.round(kx * 84)));
                    bFromY[t] = Math.max(-128, Math.min(127, (int) Math.round(kb * 84)));
                }
            }
        });
    }

    // --------------------------------------------------- adaptive quantisation

    /**
     * Per-cell activity: a local Laplacian of luma. Smooth cells will want a
     * finer quantiser (banding shows), busy cells a coarser one (texture masks
     * the error) — but "smooth" and "busy" only mean anything against a
     * reference level, which {@link #maskingFactors} supplies.
     */
    private float[] measureActivity() {
        float[] act = new float[w8 * h8];
        float[] luma = xyb[1];
        sweep(h8, by -> {
            for (int bx = 0; bx < w8; bx++) {
                double a = 0;
                for (int y = y0 + by * 8; y < y0 + by * 8 + 8; y++) {
                    int yn = Math.max(0, y - 1);
                    int ys = Math.min(bufRows - 1, y + 1);
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
        });
        return act;
    }

    /** The mean activity of the loaded window, and how many cells it covers. */
    double activitySum() {
        double sum = 0;
        for (float a : activity) {
            sum += a;
        }
        return sum;
    }

    int activityCells() {
        return activity.length;
    }

    /**
     * Mean activity of one group column of the loaded window — how the
     * streaming rate control finds the part of a band worth measuring. A crop
     * of blank glass says nothing about whether the quantiser is too coarse.
     */
    double groupActivity(int gCol) {
        int bx0 = gCol << 5;
        int bxN = Math.min(bx0 + 32, w8);
        double sum = 0;
        int n = 0;
        for (int by = 0; by < h8; by++) {
            for (int bx = bx0; bx < bxN; bx++) {
                sum += activity[by * w8 + bx];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Masking factors relative to {@code mean}, the reference activity level. */
    private float[] maskingFactors(double mean) {
        float[] factor = new float[activity.length];
        if (mean < 1e-7) {
            java.util.Arrays.fill(factor, 1f);
            return factor;
        }
        for (int i = 0; i < activity.length; i++) {
            double f = Math.pow(mean / (activity[i] + 1e-7), 0.20);
            factor[i] = (float) Math.max(AQ_MIN, Math.min(AQ_MAX, f));
        }
        return factor;
    }

    // ---------------------------------------------------------- quantisation

    /** Measures the loaded window's activity, ahead of {@link #quantiseWindow}. */
    void measureWindow() {
        activity = measureActivity();
    }

    /**
     * Chooses transforms and multipliers and quantises every block of the
     * loaded window, masking against {@code meanActivity}. Pass
     * {@link Double#NaN} to use the window's own mean — which, for a window
     * that is the whole image, is the whole image's mean.
     */
    void quantiseWindow(double meanActivity) throws IOException {
        if (activity == null) {
            measureWindow();
        }
        double mean = Double.isNaN(meanActivity) ? activitySum() / activityCells() : meanActivity;
        computeCfl();
        blockType = new byte[w8 * h8];
        java.util.Arrays.fill(blockType, (byte) -1);
        blockMul = new int[w8 * h8];
        dcQuant = new int[3][w8 * h8];
        hfQuant = new int[3][w8 * h8][];
        float[] factor = maskingFactors(mean);
        boolean no16 = Boolean.getBoolean("jxl.enc.nodct16");

        // A strip is as tall as the largest block that can be attempted, so the
        // block never reaches outside its strip and the strips run at once. Without
        // the big scales that is the 64x64 (eight cell rows) once past
        // BIG_MIN_DISTANCE, the 32x32 (four) below it, and every smaller block nests
        // inside. The 128/256 scales, when opted in, widen it to sixteen or
        // thirty-two rows. The block choice is a hierarchy from the top: the largest
        // block that pays, else down to the 16x16-or-four-8x8 decision, and down.
        boolean big = distance >= BIG_MIN_DISTANCE;
        boolean use256 = big && DCT256_ON;
        boolean use128 = big && DCT128_ON;
        int stripCells = use256 ? 32 : use128 ? 16 : big && !NO_DCT64 ? 8 : 4;
        sweep(ceilDiv(h8, stripCells), strip -> {
            Scratch s = new Scratch();
            int byTop = strip * stripCells;
            int byN = Math.min(byTop + stripCells, h8);
            for (int by = byTop; by < byN; by++) {
                for (int bx = 0; bx < w8; bx++) {
                    if (blockType[by * w8 + bx] != -1) {
                        continue; // already decided (or covered by a larger block)
                    }
                    if (use256 && (by & 31) == 0 && (bx & 31) == 0
                            && by + 32 <= h8 && bx + 32 <= w8 && allClear(by, bx, 32)) {
                        chooseBlock(by, bx, 32, factor, s);
                        continue;
                    }
                    if (use128 && (by & 15) == 0 && (bx & 15) == 0
                            && by + 16 <= h8 && bx + 16 <= w8 && allClear(by, bx, 16)) {
                        chooseBlock(by, bx, 16, factor, s);
                        continue;
                    }
                    if (big && !NO_DCT64 && (by & 7) == 0 && (bx & 7) == 0
                            && by + 8 <= h8 && bx + 8 <= w8 && allClear(by, bx, 8)) {
                        chooseBlock(by, bx, 8, factor, s);
                        continue;
                    }
                    if (!NO_DCT32 && (by & 3) == 0 && (bx & 3) == 0
                            && by + 4 <= h8 && bx + 4 <= w8 && allClear(by, bx, 4)) {
                        chooseBlock(by, bx, 4, factor, s);
                        continue;
                    }
                    if (!no16 && (by & 1) == 0 && (bx & 1) == 0
                            && by + 2 <= h8 && bx + 2 <= w8 && allClear(by, bx, 2)) {
                        chooseDct16(by, bx, factor, s);
                        continue;
                    }
                    quantiseDct8(by, bx, factor, s);
                }
            }
        });
        if (!NO_SMALL) {
            postPassSmall();
        }
        chooseSharpness();
        // count the rectangular blocks (any of the six types, 6..11) that survived
        // into the final layout — a committed one can still be overwritten by a
        // larger block; tests read this.
        long rect = 0;
        for (byte t : blockType) {
            if (t >= 0) {
                TYPE_HIST.incrementAndGet(t);
                if (t >= DCT16_8.type && t <= DCT16_32.type) {
                    rect++;
                }
            }
        }
        RECT_BLOCKS.addAndGet(rect);
    }

    private static final int[] Y_FIRST = {1, 0, 2};

    /** The chroma-from-luma factor of visual channel {@code c} at a window cell. */
    private float cflFactor(int c, int by, int bx) {
        if (c == 1) {
            return 0f;
        }
        int t = (by >> 3) * tilesX + (bx >> 3);
        return (c == 0 ? xFromY[t] : bFromY[t]) / 84f;
    }

    /** Per-thread working buffers for the block-choice sweep. */
    private static final class Scratch {
        final float[] block = new float[MAX_DIM * MAX_DIM];
        final float[][] c8 = new float[4][64];
        final float[] c16 = new float[256];
        final float[] c32 = new float[MAX_DIM * MAX_DIM];
        final float[] s0 = new float[MAX_DIM * MAX_DIM];
        final float[] s1 = new float[MAX_DIM * MAX_DIM];
        final float[] llf = new float[MAX_LLF];
        final float[] dcT = new float[MAX_LLF];
        final float[] dqY = new float[MAX_DIM * MAX_DIM];
        final int[][] q32 = new int[3][MAX_DIM * MAX_DIM];
        final int[][] dc32 = new int[3][MAX_LLF];
    }

    /** True when every cell of the {@code cells}x{@code cells} square is still undecided. */
    private boolean allClear(int by, int bx, int cells) {
        for (int iy = 0; iy < cells; iy++) {
            for (int ix = 0; ix < cells; ix++) {
                if (blockType[(by + iy) * w8 + bx + ix] != -1) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Quantises one 8x8 block in every channel, luma first for CfL. Returns its rate estimate. */
    private double quantiseDct8(int by, int bx, float[] factor, Scratch s) {
        int k = by * w8 + bx;
        int mul = clampMul(Math.round(hfMul * factor[k]));
        blockType[k] = 0;
        blockMul[k] = mul;
        float[] block = s.block;
        float[] coeffs = s.c8[0];
        float[] dqY = s.dqY;
        double cost = 1;
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            for (int y = 0; y < 8; y++) {
                System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8, block, y * 8, 8);
            }
            Dct.forward2D(block, 0, 8, coeffs, 0, 8, 8, 8, s.s0, s.s1);
            dcQuant[c][k] = Math.round(coeffs[0] / scaledDequant[c]);
            float sfc = baseSfc[c] / mul;
            float cfl = cflFactor(c, by, bx);
            int[] q = new int[64];
            float[] wc = weightsOf[DCT8.parameterIndex][c];
            for (int j = 1; j < 64; j++) {
                // natural coefficient (y,x) pairs with the transposed weight
                int wIdx = (j & 7) * 8 + (j >> 3);
                float step = sfc * wc[wIdx];
                q[j] = Math.round((coeffs[j] - cfl * dqY[j]) / step);
                if (c == 1) {
                    dqY[j] = q[j] * step;
                }
                cost += tokenCost(q[j]);
            }
            hfQuant[c][k] = q;
        }
        return cost;
    }

    /**
     * Quantises one 8x8 cell with a chosen transform — DCT8, DCT2 or DCT4 — into
     * {@code qOut}/{@code dcOut} in every channel, luma first for chroma-from-luma.
     * All three keep the DCT8 layout (position 0 the DC, the rest AC on the same
     * scale), so only the forward transform, its weights and — via {@code flip} —
     * the coefficient placement differ. Non-committing; returns the rate estimate.
     */
    private double quantiseCell(int by, int bx, TransformType tt, int mul,
            Scratch s, int[][] qOut, int[] dcOut) {
        float[] block = s.block;
        float[] coeffs = s.c8[0];
        float[] dqY = s.dqY;
        boolean flip = tt.flip();
        float[][] w = weightsOf[tt.parameterIndex];
        double cost = 1;
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            for (int y = 0; y < 8; y++) {
                System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8, block, y * 8, 8);
            }
            switch (tt.method) {
                case DCT2 -> SmallDct.forwardDct2(block, coeffs);
                case DCT4 -> SmallDct.forwardDct4(block, coeffs, s.s0, s.s1);
                case DCT4_8 -> SmallDct.forwardDct4x8(block, coeffs, s.s0, s.s1);
                case DCT8_4 -> SmallDct.forwardDct8x4(block, coeffs, s.s0, s.s1);
                case HORNUSS -> Transforms.forwardHornuss(block, coeffs);
                case AFV -> Transforms.forwardAfv(tt, block, coeffs, s.s0, s.s1);
                default -> Dct.forward2D(block, 0, 8, coeffs, 0, 8, 8, 8, s.s0, s.s1);
            }
            dcOut[c] = Math.round(coeffs[0] / scaledDequant[c]);
            float sfc = baseSfc[c] / mul;
            float cfl = cflFactor(c, by, bx);
            int[] q = new int[64];
            float[] wc = w[c];
            for (int j = 1; j < 64; j++) {
                int wIdx = flip ? (j & 7) * 8 + (j >> 3) : j;
                float step = sfc * wc[wIdx];
                q[j] = Math.round((coeffs[j] - cfl * dqY[j]) / step);
                if (c == 1) {
                    dqY[j] = q[j] * step;
                }
                cost += tokenCost(q[j]);
            }
            qOut[c] = q;
        }
        return cost;
    }

    /**
     * After the block-choice sweep, retries each plain DCT8 block on busy content
     * as DCT2 and DCT4, replacing it where one clearly codes smaller — the small
     * transforms earn their keep on hard edges and text, not the smooth blocks the
     * DCT8 (or a larger block) already handles. Runs before sharpness, so the EPF
     * estimate sees the final types.
     */
    // HORNUSS is implemented (SmallDct/Transforms) and reads back, but is left out
    // of the tried set: DCT2's hierarchical Hadamard beats it on the dot/spike
    // patterns it targets, so it never won a block across the test content.
    private static final TransformType[] SMALL_TYPES =
            {DCT2, DCT4, DCT4_8, DCT8_4, AFV0, AFV1, AFV2, AFV3};

    private void postPassSmall() {
        sweep(h8, by -> {
            Scratch s = new Scratch();
            int[][] q = new int[3][];
            int[] dc = new int[3];
            int[][] bestQ = new int[3][];
            int[] bestDc = new int[3];
            for (int bx = 0; bx < w8; bx++) {
                int k = by * w8 + bx;
                if (blockType[k] != DCT8.type) {
                    continue;   // only plain 8x8 blocks; the margin does the rest
                }
                // the committed DCT8's rate, from its stored coefficients — no need
                // to transform it again, only the small candidates are new work here
                double cost8 = 1;
                for (int c = 0; c < 3; c++) {
                    int[] qc = hfQuant[c][k];
                    for (int j = 1; j < 64; j++) {
                        cost8 += tokenCost(qc[j]);
                    }
                }
                int mul = blockMul[k];
                double best = SMALL_GAIN * cost8;   // an alternative must beat this
                TransformType bestTt = null;
                for (TransformType tt : SMALL_TYPES) {
                    double cost = quantiseCell(by, bx, tt, mul, s, q, dc);
                    if (cost < best) {
                        best = cost;
                        bestTt = tt;
                        for (int c = 0; c < 3; c++) {
                            bestQ[c] = q[c];
                            bestDc[c] = dc[c];
                        }
                    }
                }
                if (bestTt != null) {
                    blockType[k] = (byte) bestTt.type;
                    for (int c = 0; c < 3; c++) {
                        hfQuant[c][k] = bestQ[c];
                        dcQuant[c][k] = bestDc[c];
                    }
                }
            }
        });
    }

    /**
     * Quantises one rectangular block (8x16 or 16x8) in every channel, luma
     * first for chroma-from-luma, into {@code qOut} (coefficients in block-local
     * layout) and {@code dcOut} (the DC entries reproducing its low-frequency
     * corner, one per covered cell). Mirrors {@link #quantiseDct8} at the
     * rectangular scale; returns its high-frequency rate estimate. Nothing is
     * committed — the caller compares it against the alternatives first.
     */
    private double quantiseRect(int cellBy, int cellBx, TransformType tt, int mul,
            Scratch s, int[][] qOut, int[][] dcOut) {
        int ph = tt.pixelHeight;
        int pw = tt.pixelWidth;
        int bH = tt.blockHeight;
        int bW = tt.blockWidth;
        int mw = tt.matrixWidth;
        boolean flip = tt.flip();
        float[] block = s.block;
        float[] coef = s.c32;   // >= 256, holds the ph x pw forward transform
        float[] dqY = s.dqY;
        double cost = 0;
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            float cfl = cflFactor(c, cellBy, cellBx);
            for (int y = 0; y < ph; y++) {
                System.arraycopy(xyb[c], (y0 + cellBy * 8 + y) * paddedWidth + cellBx * 8,
                        block, y * pw, pw);
            }
            Dct.forward2D(block, 0, pw, coef, 0, pw, ph, pw, s.s0, s.s1);
            float sfc = baseSfc[c] / mul;
            float[] wc = weightsOf[tt.parameterIndex][c];
            int[] q = new int[ph * pw];
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    if (y < bH && x < bW) {
                        continue; // LLF comes from the DC image
                    }
                    int j = y * pw + x;
                    int wy = flip ? x : y;
                    int wx = flip ? y : x;
                    float step = sfc * wc[wy * mw + wx];
                    q[j] = Math.round((coef[j] - cfl * dqY[j]) / step);
                    if (c == 1) {
                        dqY[j] = q[j] * step;
                    }
                    cost += tokenCost(q[j]);
                }
            }
            qOut[c] = q;
            // the DC entries that reproduce the true low-frequency corner
            for (int i = 0; i < bH * bW; i++) {
                s.llf[i] = coef[(i / bW) * pw + i % bW] / tt.llfScale[i];
            }
            Dct.inverse2D(s.llf, 0, bW, s.dcT, 0, bW, bH, bW, s.s0, s.s1, false);
            for (int i = 0; i < bH * bW; i++) {
                dcOut[c][i] = Math.round(s.dcT[i] / scaledDequant[c]);
            }
        }
        return cost;
    }

    /** A rectangular tiling of a square cell region: one transform, its blocks' data and rate. */
    private static final class RectTiling {
        final TransformType tt;
        final int[] mul;         // per block
        final int[][][] q;       // [block][channel] coefficients
        final int[][][] dc;      // [block][channel][covered cell]
        final double cost;

        RectTiling(TransformType tt, int[] mul, int[][][] q, int[][][] dc, double cost) {
            this.tt = tt;
            this.mul = mul;
            this.q = q;
            this.dc = dc;
            this.cost = cost;
        }
    }

    /**
     * The cheapest rectangular tiling of an {@code s}x{@code s} cell region among
     * {@code options}. Each option tiles the region with blocks {@code blockHeight
     * x blockWidth} cells in size, laid out {@code s/blockHeight} by
     * {@code s/blockWidth}; nothing is committed. Returns {@code null} only for an
     * empty option list.
     */
    private RectTiling bestRectTiling(int regionBy, int regionBx, int s,
            TransformType[] options, float[] factor, Scratch sc) {
        RectTiling best = null;
        for (TransformType tt : options) {
            int bH = tt.blockHeight;
            int bW = tt.blockWidth;
            int rows = s / bH;
            int cols = s / bW;
            int n = rows * cols;
            int[] mul = new int[n];
            int[][][] q = new int[n][3][];
            int[][][] dc = new int[n][3][bH * bW];
            double cost = 8.0 * n;   // a per-block base, matching the square arms
            int b = 0;
            for (int br = 0; br < rows; br++) {
                for (int bc = 0; bc < cols; bc++) {
                    int cBy = regionBy + br * bH;
                    int cBx = regionBx + bc * bW;
                    float f = 0;
                    for (int iy = 0; iy < bH; iy++) {
                        for (int ix = 0; ix < bW; ix++) {
                            f += factor[(cBy + iy) * w8 + cBx + ix];
                        }
                    }
                    mul[b] = clampMul(Math.round(hfMul * f / (bH * bW)));
                    cost += quantiseRect(cBy, cBx, tt, mul[b], sc, q[b], dc[b]);
                    b++;
                }
            }
            if (best == null || cost < best.cost) {
                best = new RectTiling(tt, mul, q, dc, cost);
            }
        }
        return best;
    }

    /** Commits a rectangular tiling over its region: origin cells carry the type, the rest are covered. */
    private void commitRectTiling(int regionBy, int regionBx, int s, RectTiling t) {
        int bH = t.tt.blockHeight;
        int bW = t.tt.blockWidth;
        int rows = s / bH;
        int cols = s / bW;
        int b = 0;
        for (int br = 0; br < rows; br++) {
            for (int bc = 0; bc < cols; bc++) {
                int oBy = regionBy + br * bH;
                int oBx = regionBx + bc * bW;
                int origin = oBy * w8 + oBx;
                for (int c = 0; c < 3; c++) {
                    hfQuant[c][origin] = t.q[b][c];
                }
                for (int iy = 0; iy < bH; iy++) {
                    for (int ix = 0; ix < bW; ix++) {
                        int cell = (oBy + iy) * w8 + oBx + ix;
                        blockType[cell] = (iy == 0 && ix == 0) ? (byte) t.tt.type : -2;
                        blockMul[cell] = t.mul[b];
                        for (int c = 0; c < 3; c++) {
                            dcQuant[c][cell] = t.dc[b][c][iy * bW + ix];
                        }
                    }
                }
                b++;
            }
        }
    }

    /**
     * Decides the aligned 16x16 area. The four 8x8s are the baseline; the 16x16
     * replaces them where the area is smooth and its rate is clearly lower, and
     * failing that a rectangular pair (two 16x8 or two 8x16) replaces them where
     * the content runs one way — smooth along the block's long axis, detailed
     * across it. Always commits, and returns the rate estimate of whatever it
     * chose.
     */
    private double chooseDct16(int by, int bx, float[] factor, Scratch s) {
        int[] cells = {by * w8 + bx, by * w8 + bx + 1, (by + 1) * w8 + bx, (by + 1) * w8 + bx + 1};
        float f4 = (factor[cells[0]] + factor[cells[1]] + factor[cells[2]] + factor[cells[3]]) / 4;

        // ---- 8x8 baseline: always quantised into temp, committed only if it wins
        int[] mul8 = new int[4];
        for (int i = 0; i < 4; i++) {
            mul8[i] = clampMul(Math.round(hfMul * factor[cells[i]]));
        }
        int[][][] q8c = new int[3][4][];
        int[][] dc8 = new int[3][4];
        double cost8 = 32;
        {
            float[] block = s.block;
            float[][] c8 = s.c8;
            float[][] dqY8 = new float[4][64];
            for (int ci = 0; ci < 3; ci++) {
                int c = Y_FIRST[ci];
                float cfl = cflFactor(c, by, bx);
                float[] wc = weightsOf[DCT8.parameterIndex][c];
                for (int i = 0; i < 4; i++) {
                    int cy = by + (i >> 1);
                    int cx = bx + (i & 1);
                    for (int y = 0; y < 8; y++) {
                        System.arraycopy(xyb[c], (y0 + cy * 8 + y) * paddedWidth + cx * 8,
                                block, y * 8, 8);
                    }
                    Dct.forward2D(block, 0, 8, c8[i], 0, 8, 8, 8, s.s0, s.s1);
                    dc8[c][i] = Math.round(c8[i][0] / scaledDequant[c]);
                    float sfc = baseSfc[c] / mul8[i];
                    int[] qq = new int[64];
                    for (int j = 1; j < 64; j++) {
                        int wIdx = (j & 7) * 8 + (j >> 3);
                        float step = sfc * wc[wIdx];
                        qq[j] = Math.round((c8[i][j] - cfl * dqY8[i][j]) / step);
                        if (c == 1) {
                            dqY8[i][j] = qq[j] * step;
                        }
                        cost8 += tokenCost(qq[j]);
                    }
                    q8c[c][i] = qq;
                }
            }
        }

        // ---- 16x16 where the area is smooth; a clear win takes it outright
        if (f4 >= 0.95f) {
            int mul16 = clampMul(Math.round(hfMul * f4));
            int[][] q16 = new int[3][];
            int[][] dc16 = new int[3][4];
            double cost16 = 8;
            float[] block = s.block;
            float[] c16 = s.c16;
            float[] dqY16 = new float[256];
            for (int ci = 0; ci < 3; ci++) {
                int c = Y_FIRST[ci];
                float cfl = cflFactor(c, by, bx);
                for (int y = 0; y < 16; y++) {
                    System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8,
                            block, y * 16, 16);
                }
                Dct.forward2D(block, 0, 16, c16, 0, 16, 16, 16, s.s0, s.s1);
                float sfc16 = baseSfc[c] / mul16;
                float[] w16 = weightsOf[DCT16.parameterIndex][c];
                int[] q = new int[256];
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        if (y < 2 && x < 2) {
                            continue; // LLF comes from the DC image
                        }
                        int j = y * 16 + x;
                        float step = sfc16 * w16[x * 16 + y];
                        q[j] = Math.round((c16[j] - cfl * dqY16[j]) / step);
                        if (c == 1) {
                            dqY16[j] = q[j] * step;
                        }
                        cost16 += tokenCost(q[j]);
                    }
                }
                q16[c] = q;
                for (int i = 0; i < 4; i++) {
                    s.llf[i] = c16[(i >> 1) * 16 + (i & 1)] / DCT16.llfScale[i];
                }
                Dct.inverse2D(s.llf, 0, 2, s.dcT, 0, 2, 2, 2, s.s0, s.s1, false);
                for (int i = 0; i < 4; i++) {
                    dc16[c][i] = Math.round(s.dcT[i] / scaledDequant[c]);
                }
            }
            if (cost16 < 0.94 * cost8) {
                for (int i = 0; i < 4; i++) {
                    int k = cells[i];
                    blockType[k] = (byte) (i == 0 ? DCT16.type : -2);
                    blockMul[k] = mul16;
                    for (int c = 0; c < 3; c++) {
                        dcQuant[c][k] = dc16[c][i];
                    }
                }
                for (int c = 0; c < 3; c++) {
                    hfQuant[c][cells[0]] = q16[c];
                }
                return cost16;
            }
        }

        // ---- rectangular pair (two 16x8 or two 8x16) where it clearly beats the 8x8s
        if (!NO_RECT) {
            RectTiling rect = bestRectTiling(by, bx, 2,
                    new TransformType[] {DCT16_8, DCT8_16}, factor, s);
            if (rect.cost < RECT_GAIN * cost8) {
                commitRectTiling(by, bx, 2, rect);
                return rect.cost;
            }
        }

        // ---- 8x8 fallback
        for (int i = 0; i < 4; i++) {
            int k = cells[i];
            blockType[k] = 0;
            blockMul[k] = mul8[i];
            for (int c = 0; c < 3; c++) {
                dcQuant[c][k] = dc8[c][i];
                hfQuant[c][k] = q8c[c][i];
            }
        }
        return cost8;
    }

    /**
     * Decides an aligned square region of {@code cells}x{@code cells} 8x8 cells —
     * 32x32 (four cells a side) up through 256x256 (thirty-two). The subdivision
     * into four quadrants of the next scale down is quantised and committed first,
     * and the one big square (or a rectangular pair of half-blocks) replaces it
     * only when its rate is clearly lower. A big block covers a smooth region in
     * one transform where the smaller ones would each carry their own DC and
     * high-frequency tail, so it pays on skies and flat backgrounds and nowhere
     * busy — which the smoothness gate keeps it away from. Recursive: each quadrant
     * is this same decision one scale down, until {@code cells == 2} hands off to
     * {@link #chooseDct16}. Returns the rate estimate of whatever it committed.
     */
    private double chooseBlock(int by, int bx, int cells, float[] factor, Scratch s) {
        if (cells == 2) {
            return chooseDct16(by, bx, factor, s);
        }
        int half = cells / 2;
        double costSub = 0;
        for (int qy = 0; qy < cells; qy += half) {
            for (int qx = 0; qx < cells; qx += half) {
                costSub += chooseBlock(by + qy, bx + qx, half, factor, s);
            }
        }
        float f = 0;
        for (int iy = 0; iy < cells; iy++) {
            for (int ix = 0; ix < cells; ix++) {
                f += factor[(by + iy) * w8 + bx + ix];
            }
        }
        f /= cells * cells;

        // The square (kept to smooth regions — its rate estimate misjudges busy
        // ones) and the two half-block rectangular tilings (for content that runs
        // one way, often the busy areas the square avoids), each needing a clear
        // win over the subdivision the recursion already committed.
        double bestCost = Double.MAX_VALUE;
        int mul = 0;
        RectTiling bestRect = null;
        if (f >= 0.95f) {
            mul = clampMul(Math.round(hfMul * f));
            double costSq = quantiseSquareBig(by, bx, squareFor(cells), mul, s);
            if (costSq < SQUARE_GAIN * costSub) {
                bestCost = costSq;
            }
        }
        if (!NO_RECT) {
            RectTiling rect = bestRectTiling(by, bx, cells, rectFor(cells), factor, s);
            if (rect.cost < RECT_GAIN * costSub && rect.cost < bestCost) {
                bestCost = rect.cost;
                bestRect = rect;
            }
        }
        if (bestRect != null) {
            commitRectTiling(by, bx, cells, bestRect);
            return bestRect.cost;
        } else if (bestCost < Double.MAX_VALUE) {
            commitSquareBig(by, bx, squareFor(cells), mul, s);
            return bestCost;
        }
        // else keep the subdivision the recursion already committed
        return costSub;
    }

    /** The square DCT covering a {@code cells}x{@code cells} region: 4→32, 8→64, 16→128, 32→256. */
    private static TransformType squareFor(int cells) {
        return switch (cells) {
            case 4 -> DCT32;
            case 8 -> DCT64;
            case 16 -> DCT128;
            default -> DCT256;
        };
    }

    /** The two half-block rectangular tilings of a {@code cells}x{@code cells} region. */
    private static TransformType[] rectFor(int cells) {
        return switch (cells) {
            case 4 -> new TransformType[] {DCT32_16, DCT16_32};
            case 8 -> new TransformType[] {DCT64_32, DCT32_64};
            case 16 -> new TransformType[] {DCT128_64, DCT64_128};
            default -> new TransformType[] {DCT256_128, DCT128_256};
        };
    }

    /**
     * Quantises one square transform ({@code tt}, side 32–256 px) in every channel
     * into {@code s.q32}/{@code s.dc32} — both sized for the largest transform —
     * and returns its rate estimate. A (side/8)² low-frequency corner is drawn from
     * the DC image, the rest coded as high frequency. General over the scale: the
     * 32x32 arm is the 256x256 arm, four cells a side rather than thirty-two.
     */
    private double quantiseSquareBig(int by, int bx, TransformType tt, int mul, Scratch s) {
        int px = tt.pixelWidth;         // square, so == pixelHeight
        int side = tt.blockWidth;       // px/8: the LLF side and cells covered per side
        int shift = Integer.numberOfTrailingZeros(side);
        int mask = side - 1;
        double cost = side * side;      // the LLF cell count
        float[] coef = s.c32;
        float[] dqY = s.dqY;
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            float cfl = cflFactor(c, by, bx);
            for (int y = 0; y < px; y++) {
                System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8,
                        s.block, y * px, px);
            }
            Dct.forward2D(s.block, 0, px, coef, 0, px, px, px, s.s0, s.s1);
            float sfc = baseSfc[c] / mul;
            float[] wc = weightsOf[tt.parameterIndex][c];
            int[] q = s.q32[c];
            for (int y = 0; y < px; y++) {
                for (int x = 0; x < px; x++) {
                    if (y < side && x < side) {
                        continue; // LLF comes from the DC image
                    }
                    int j = y * px + x;
                    float step = sfc * wc[x * px + y];
                    q[j] = Math.round((coef[j] - cfl * dqY[j]) / step);
                    if (c == 1) {
                        dqY[j] = q[j] * step;
                    }
                    cost += tokenCost(q[j]);
                }
            }
            // the DC entries that reproduce the true LLF corner
            for (int i = 0; i < side * side; i++) {
                s.llf[i] = coef[(i >> shift) * px + (i & mask)] / tt.llfScale[i];
            }
            Dct.inverse2D(s.llf, 0, side, s.dcT, 0, side, side, side, s.s0, s.s1, false);
            for (int i = 0; i < side * side; i++) {
                s.dc32[c][i] = Math.round(s.dcT[i] / scaledDequant[c]);
            }
        }
        return cost;
    }

    /** Writes a decided square block over the {@code (side)²} cells it covers. */
    private void commitSquareBig(int by, int bx, TransformType tt, int mul, Scratch s) {
        int side = tt.blockWidth;
        int shift = Integer.numberOfTrailingZeros(side);
        int mask = side - 1;
        int origin = by * w8 + bx;
        for (int i = 0; i < side * side; i++) {
            int k = (by + (i >> shift)) * w8 + bx + (i & mask);
            blockType[k] = (byte) (i == 0 ? tt.type : -2);
            blockMul[k] = mul;
            for (int c = 0; c < 3; c++) {
                dcQuant[c][k] = s.dc32[c][i];
            }
        }
        for (int c = 0; c < 3; c++) {
            hfQuant[c][origin] = s.q32[c].clone();
        }
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

    // ------------------------------------------------------- EPF sharpness

    /**
     * Sharpness the encoder offers a block it wants filtered. The decoder's
     * sigma is proportional to {@code epfSharpLut[sharpness]}, whose zeroth entry
     * is zero — so sharpness 0 drives sigma to zero, trips the decoder's
     * {@code sigma < 0.3} skip, and leaves the block exactly as it found it. The
     * choice is therefore between "filter this block as hard as the format
     * allows" and "do not touch it", which is what the blocks themselves ask for:
     * given the whole ladder to choose from, they overwhelmingly pick an end.
     */
    private static final int SHARP_ON = 7;

    /** Below this the decoder skips a block outright; see {@code RestorationFilters.epf}. */
    private static final float MIN_SIGMA = 0.3f;

    /**
     * Picks each block's EPF sharpness by running the decoder's own filter over
     * the encoder's own reconstruction and keeping whichever came out closer to
     * the source.
     *
     * <p>This is the same trick as the gaborish pre-sharpening, played the other
     * way round. Gaborish is linear, so the encoder can invert it and hand the
     * decoder something that comes back right. EPF is not — it is a bilateral
     * filter whose weights depend on the decoded pixels — so there is nothing to
     * invert. But the encoder can predict it exactly, because it knows what the
     * decoder will reconstruct, and the format lets it say per block whether the
     * filter should run at all. So: reconstruct, filter, measure, choose.
     *
     * <p>What the decoder does is R -> gaborish -> EPF. The encoder holds the
     * pre-sharpened target x, and by construction gab(x) is the source — so it
     * recovers the source with one forward convolution rather than keeping a
     * second copy of it around.
     */
    private void chooseSharpness() throws IOException {
        sharp = new byte[w8 * h8];
        if (NO_FILTERS) {
            return;
        }
        int cells = w8 * h8;
        float[] sigma = new float[cells];
        float[] invSigma = new float[cells];
        boolean live = false;
        float lut = filter.epfSharpLut[SHARP_ON];
        for (int k = 0; k < cells; k++) {
            sigma[k] = (65536f / globalScale) * lut / blockMul[k];
            invSigma[k] = 1f / sigma[k];
            live |= sigma[k] >= MIN_SIGMA;
        }
        if (!live) {
            // Even at full strength the decoder would skip every block, so say
            // sharpness 0 and be done: no simulation to run, and a flat plane
            // codes for nothing where a flat 4 was costing a bit a block to ask
            // for a filter that never ran.
            return;
        }
        int rows = h8 << 3;
        float[][] recon = reconstructWindow();
        float[][] source = new float[3][];
        for (int c = 0; c < 3; c++) {
            source[c] = java.util.Arrays.copyOfRange(xyb[c], y0 * paddedWidth,
                    (y0 + rows) * paddedWidth);
        }
        // The decoder runs gaborish and then EPF. gab(x) is the source, by the
        // construction of x; gab(R) is what it will hand to EPF.
        RestorationFilters.gaborish(filter, source, paddedWidth, rows, paddedWidth, rows, 0, rows);
        RestorationFilters.gaborish(filter, recon, paddedWidth, rows, paddedWidth, rows, 0, rows);
        double[] unfiltered = cellError(recon, source, rows);
        RestorationFilters.epf(filter, recon, paddedWidth, rows, paddedWidth, rows,
                invSigma, w8, filter.epfSigmaForModular, 0, rows);
        double[] filtered = cellError(recon, source, rows);
        for (int k = 0; k < cells; k++) {
            if (sigma[k] >= MIN_SIGMA && filtered[k] < unfiltered[k]) {
                sharp[k] = SHARP_ON;
            }
        }
    }

    /**
     * Absolute XYB error of every cell, the channels weighted the way the filter
     * itself weights them — the format's own statement of what a difference in
     * each is worth.
     */
    private double[] cellError(float[][] got, float[][] want, int rows) {
        double[] err = new double[w8 * h8];
        sweep(h8, by -> {
            for (int y = by * 8; y < by * 8 + 8 && y < rows; y++) {
                for (int x = 0; x < paddedWidth; x++) {
                    int i = y * paddedWidth + x;
                    double e = 0;
                    for (int c = 0; c < 3; c++) {
                        e += filter.epfChannelScale[c] * Math.abs(got[c][i] - want[c][i]);
                    }
                    err[by * w8 + (x >> 3)] += e;
                }
            }
        });
        return err;
    }

    /**
     * The window's pixels as the decoder will rebuild them: every block's
     * coefficients dequantised, chroma-from-luma added back and inverse
     * transformed. Exact, because the frame header turns off adaptive DC
     * smoothing — the one part of the decoder's LF path the encoder does not
     * model.
     */
    private float[][] reconstructWindow() {
        int rows = h8 << 3;
        float[][] out = new float[3][paddedWidth * rows];
        // Size the per-row scratch to the largest block actually present, not to
        // MAX_DIM: with the 64–256 scales in the type table MAX_DIM is 256, and a
        // per-row 256x256 buffer allocated for every row of an image that holds no
        // block bigger than 32 would be pure churn.
        int maxDim = 8;
        for (byte t : blockType) {
            if (t >= 0) {
                TransformType tt = TransformType.byType(t);
                maxDim = Math.max(maxDim, Math.max(tt.pixelHeight, tt.pixelWidth));
            }
        }
        int maxN = maxDim * maxDim;
        sweep(h8, by -> {
            float[] deq = new float[maxN];
            float[] dqY = new float[maxN];
            float[] dcT = new float[MAX_LLF];
            float[] llf = new float[MAX_LLF];
            float[] s0 = new float[maxN];
            float[] s1 = new float[maxN];
            float[] s2 = new float[64];
            float[] s3 = new float[64];
            for (int bx = 0; bx < w8; bx++) {
                int k = by * w8 + bx;
                byte type = blockType[k];
                if (type < 0) {
                    continue; // covered by the larger block that owns the cell
                }
                TransformType tt = TransformType.byType(type);
                int ph = tt.pixelHeight;
                int pw = tt.pixelWidth;
                int bH = tt.blockHeight;
                int bW = tt.blockWidth;
                int mw = tt.matrixWidth;
                boolean flip = tt.flip();
                for (int ci = 0; ci < 3; ci++) {
                    int c = Y_FIRST[ci];
                    float cfl = cflFactor(c, by, bx);
                    float sfc = baseSfc[c] / blockMul[k];
                    float[] wc = weightsOf[tt.parameterIndex][c];
                    int[] q = hfQuant[c][k];
                    for (int y = 0; y < ph; y++) {
                        for (int x = 0; x < pw; x++) {
                            if (y < bH && x < bW) {
                                continue; // LLF comes from the DC image, below
                            }
                            int j = y * pw + x;
                            int wy = flip ? x : y;
                            int wx = flip ? y : x;
                            float hf = q[j] * sfc * wc[wy * mw + wx];
                            deq[j] = hf + cfl * dqY[j];
                            if (c == 1) {
                                dqY[j] = hf;
                            }
                        }
                    }
                    // The DC entries were derived from the LLF coefficients by a
                    // blockHeight x blockWidth inverse DCT; run it forwards again.
                    if (bH == 1 && bW == 1) {
                        deq[0] = dcQuant[c][k] * scaledDequant[c];
                    } else {
                        for (int i = 0; i < bH * bW; i++) {
                            int cell = (by + i / bW) * w8 + bx + i % bW;
                            dcT[i] = dcQuant[c][cell] * scaledDequant[c];
                        }
                        Dct.forward2D(dcT, 0, bW, llf, 0, bW, bH, bW, s0, s1);
                        for (int i = 0; i < bH * bW; i++) {
                            deq[(i / bW) * pw + i % bW] = llf[i] * tt.llfScale[i];
                        }
                    }
                    int off = by * 8 * paddedWidth + bx * 8;
                    if (tt.method == TransformType.Method.DCT) {
                        Dct.inverse2D(deq, 0, pw, out[c], off, paddedWidth, ph, pw, s0, s1, false);
                    } else {
                        // DCT2/DCT4 and the other special 8x8 methods
                        Transforms.invert(tt, deq, pw, 0, 0, out[c], off, paddedWidth,
                                s0, s1, s2, s3);
                    }
                }
            }
        });
        return out;
    }

    /** Copies the quantised window's per-cell decisions into an LF group's store. */
    void storeCells(Cells cells) {
        int off = (cellRow0 - cells.row0) * w8;
        System.arraycopy(blockType, 0, cells.type, off, w8 * h8);
        System.arraycopy(blockMul, 0, cells.mul, off, w8 * h8);
        System.arraycopy(sharp, 0, cells.sharp, off, w8 * h8);
        for (int c = 0; c < 3; c++) {
            System.arraycopy(dcQuant[c], 0, cells.dc[c], off, w8 * h8);
        }
        int tileOff = (tileRow0 - cells.row0 / 8) * tilesX;
        System.arraycopy(xFromY, 0, cells.xFromY, tileOff, tilesX * tilesY);
        System.arraycopy(bFromY, 0, cells.bFromY, tileOff, tilesX * tilesY);
    }

    /** The whole window's cells, for the path that holds the whole image. */
    private Cells windowCells() {
        Cells cells = new Cells(w8, tilesX, cellRow0, h8);
        storeCells(cells);
        return cells;
    }

    // --------------------------------------------------------------- entry

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
        return encodeSamples(planes, width, height, BitDepth.of(bits), grey, alpha,
                alphaAssociated, distance);
    }

    /**
     * Lossy colour with any number of extra channels, each carried losslessly.
     *
     * <p>Only the colour goes through XYB and the quantiser. Every extra channel
     * rides in a modular sub-stream beside it and comes back exactly as it went
     * in — which is what you want: a lossy render whose depth buffer is still
     * exact, a lossy micrograph whose segmentation mask has not been smeared. It
     * is also what the format expects; an extra channel has no perceptual model
     * to be quantised against.
     */
    public static byte[] encode(int[][] planes, int width, int height, int bits,
            boolean grey, java.util.List<ExtraChannelInfo> extras, float distance)
            throws IOException {
        return encodeSamples(planes, width, height, BitDepth.of(bits), grey, extras, distance);
    }

    /**
     * Lossy colour with synthetic photon noise: the frame carries a noise model
     * (see {@link com.ebremer.cygnus.jpegxl.features.Noise#photonNoiseLut}) and
     * the decoder synthesizes grain, rather than the encoder spending bits coding
     * it. What {@code cjxl --photon_noise_iso} does — and because the synthesis is
     * normative, the same {@code iso} through libjxl produces the same grain.
     *
     * <p>It is a perceptual trade, not a fidelity one: the synthesized grain is
     * not the image's own, so a pixel metric sees the added noise as error even
     * where a viewer sees a more natural picture. It pays on film and high-ISO
     * photography, where a smooth lossy render looks plasticky; give it the
     * quantiser (a higher {@code distance}) to smooth the real grain away and let
     * the model put grain back.
     *
     * @param iso the photon-noise level, higher being grainier; 0 disables it
     */
    public static byte[] encodeWithPhotonNoise(int[][] planes, int width, int height, int bits,
            boolean grey, java.util.List<ExtraChannelInfo> extras, float distance, double iso)
            throws IOException {
        BitDepth depth = BitDepth.of(bits);
        checkInput(planes, width, height, depth, grey, extras);
        VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, extras, distance);
        if (iso > 0) {
            enc.noiseLut = com.ebremer.cygnus.jpegxl.features.Noise.photonNoiseLut(
                    width, height, iso);
        }
        int colour = grey ? 1 : 3;
        enc.loadWhole(java.util.Arrays.copyOf(planes, colour));
        enc.quantiseWindow(Double.NaN);
        return enc.standalone(java.util.Arrays.copyOfRange(planes, colour, planes.length));
    }

    /**
     * Lossy colour quantised with a custom DCT8 matrix ({@code dct8Params} the
     * per-channel MODE_DCT distance bands, {@link #setCustomDct8}). The header
     * carries the matrix, so any decoder — ours or libjxl — rebuilds the image
     * with it. Used to exercise the custom-matrix path; the automatic encoders
     * keep the default matrix unless a tuned one is proven to pay.
     */
    public static byte[] encodeWithMatrix(int[][] planes, int width, int height, int bits,
            boolean grey, float distance, float[][] dct8Params) throws IOException {
        BitDepth depth = BitDepth.of(bits);
        java.util.List<ExtraChannelInfo> extras = JxlEncoder.alphaOnly(depth, false, false);
        checkInput(planes, width, height, depth, grey, extras);
        VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, extras, distance);
        enc.setCustomDct8(dct8Params);
        int colour = grey ? 1 : 3;
        enc.loadWhole(java.util.Arrays.copyOf(planes, colour));
        enc.quantiseWindow(Double.NaN);
        return enc.standalone(java.util.Arrays.copyOfRange(planes, colour, planes.length));
    }

    /**
     * Progressive lossy colour: the same quantised image, but the AC coefficients
     * are split across {@code shifts.length} passes so a decoder can show a coarse
     * version and refine it as more of the stream arrives. {@code shifts} is the
     * per-pass coefficient shift, strictly decreasing and ending at zero — e.g.
     * {@code {1,0}} for a two-pass encode where the first pass drops each
     * coefficient's low bit. A full decode is bit-identical to
     * {@link #encode}; an early-terminated one is a coarser but valid picture. No
     * extra channels (colour only).
     */
    public static byte[] encodeProgressive(int[][] planes, int width, int height, int bits,
            boolean grey, float distance, int[] shifts) throws IOException {
        if (shifts.length < 2 || shifts[shifts.length - 1] != 0) {
            throw new IllegalArgumentException("progressive needs >= 2 passes, shifts ending at 0");
        }
        for (int i = 1; i < shifts.length; i++) {
            if (shifts[i] >= shifts[i - 1]) {
                throw new IllegalArgumentException("shifts must strictly decrease");
            }
        }
        BitDepth depth = BitDepth.of(bits);
        java.util.List<ExtraChannelInfo> extras = JxlEncoder.alphaOnly(depth, false, false);
        checkInput(planes, width, height, depth, grey, extras);
        VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, extras, distance);
        int colour = grey ? 1 : 3;
        enc.loadWhole(java.util.Arrays.copyOf(planes, colour));
        enc.quantiseWindow(Double.NaN);
        return enc.standaloneProgressive(shifts);
    }

    /**
     * A lossy (VarDCT) animation: a sequence of full-canvas frames, each coded
     * through the quantiser and shown for its own duration, at {@code
     * tpsNumerator/tpsDenominator} ticks per second, looping {@code numLoops} times
     * (0 = forever). Where {@link JxlEncoder#encodeAnimation} keeps every frame
     * lossless, this trades exactness for size — the natural choice for video-like
     * content. Only full-frame {@code REPLACE} frames (each an independent picture);
     * the crop and blend compositing the lossless path offers, and extra channels,
     * are not carried here.
     */
    public static byte[] encodeVarDctAnimation(java.util.List<JxlEncoder.AnimationFrame> frames,
            int width, int height, int bits, boolean grey, float distance,
            int tpsNumerator, int tpsDenominator, long numLoops) throws IOException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("an animation needs at least one frame");
        }
        if (tpsNumerator <= 0 || tpsDenominator <= 0) {
            throw new IllegalArgumentException("ticks per second must be positive");
        }
        BitDepth depth = BitDepth.of(bits);
        java.util.List<ExtraChannelInfo> extras = JxlEncoder.alphaOnly(depth, false, false);
        ImageMetadata meta = JxlEncoder.buildMetadata(depth, grey, extras);
        meta.xybEncoded = true;
        meta.haveAnimation = true;
        meta.animTpsNumerator = tpsNumerator;
        meta.animTpsDenominator = tpsDenominator;
        meta.animNumLoops = numLoops;

        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        meta.write(out);

        int colour = grey ? 1 : 3;
        int n = frames.size();
        for (int i = 0; i < n; i++) {
            JxlEncoder.AnimationFrame f = frames.get(i);
            if (f.width != width || f.height != height || f.x0 != 0 || f.y0 != 0
                    || f.blendMode != JxlEncoder.BLEND_REPLACE) {
                throw new IllegalArgumentException(
                        "lossy animation supports only full-frame REPLACE frames");
            }
            checkInput(f.planes, width, height, depth, grey, extras);
            VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, extras, distance);
            enc.animated = true;
            enc.animDuration = f.durationTicks;
            enc.animLast = i == n - 1;
            enc.loadWhole(java.util.Arrays.copyOf(f.planes, colour));
            enc.quantiseWindow(Double.NaN);
            enc.writeFrame(out, new int[0][]);
        }
        return out.toByteArray();
    }

    /**
     * Encodes floating-point samples lossily.
     *
     * <p>The float goes straight into the XYB conversion, and that is the whole
     * point: feeding a float image through the integer path means quantising it
     * first, which throws away exactly the precision it was kept in float to
     * hold — and then quantising it again, in XYB, where the throwing-away
     * actually buys something.
     *
     * <p>Nothing clamps to the display range. The transfer curve extends above 1
     * and mirrors below 0, and so does the decoder's, so a sample outside [0, 1]
     * survives — and survives well, because the quantiser works in XYB, on the
     * far side of a curve that is already perceptual, so its steps scale with the
     * value rather than with full scale. On a field running to 400, distance 1
     * holds the mean error to 0.2% of each sample. Where the difference really
     * tells is below zero: quantising to integers first has nowhere to put a
     * negative sample, and on a field from -2 to 2 that costs a factor of
     * seventy in error against coding the floats directly.
     */
    public static byte[] encodeFloat(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        return encodeFloat(planes, width, height, depth, grey,
                JxlEncoder.alphaOnly(depth, alpha, alphaAssociated), distance);
    }

    /** {@link #encodeFloat} with any number of extra channels. */
    public static byte[] encodeFloat(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, java.util.List<ExtraChannelInfo> extras, float distance)
            throws IOException {
        return encodeSamples(JxlEncoder.pack(planes, width, height, depth, grey, extras),
                width, height, depth, grey, extras, distance);
    }

    /** {@link #encodeFloat} at IEEE binary32, the depth that holds any float. */
    public static byte[] encodeFloat(float[][] planes, int width, int height, boolean grey,
            boolean alpha, boolean alphaAssociated, float distance) throws IOException {
        return encodeFloat(planes, width, height, BitDepth.float32(), grey, alpha,
                alphaAssociated, distance);
    }

    /** Encodes samples already laid out as {@code depth} lays them out. */
    static byte[] encodeSamples(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        return encodeSamples(planes, width, height, depth, grey,
                JxlEncoder.alphaOnly(depth, alpha, alphaAssociated), distance);
    }

    static byte[] encodeSamples(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, java.util.List<ExtraChannelInfo> extras, float distance)
            throws IOException {
        checkInput(planes, width, height, depth, grey, extras);
        VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, extras, distance);
        int colour = grey ? 1 : 3;
        enc.loadWhole(java.util.Arrays.copyOf(planes, colour));
        enc.quantiseWindow(Double.NaN);
        return enc.standalone(
                java.util.Arrays.copyOfRange(planes, colour, planes.length));
    }

    /** Lays float planes out as the samples the coder will carry. */
    static int[][] pack(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha) {
        if (!depth.floatingPoint) {
            throw new IllegalArgumentException("encodeFloat needs a floating-point depth");
        }
        int numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        if (planes.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got "
                    + planes.length);
        }
        int[][] out = new int[numInput][];
        for (int c = 0; c < numInput; c++) {
            if (planes[c].length != width * height) {
                throw new IllegalArgumentException("plane " + c + " has wrong size");
            }
            int[] p = new int[width * height];
            for (int i = 0; i < p.length; i++) {
                p[i] = depth.floatToSample(planes[c][i]);
            }
            out[c] = p;
        }
        return out;
    }

    /**
     * The loaded, quantised window as a complete image of its own. The whole
     * image goes out this way; so does the crop the streaming rate control
     * measures, which is why the window's quantisation — masking reference and
     * all — is left exactly as the caller set it.
     */
    byte[] standalone(int[][] extraPlanes) throws IOException {
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = JxlEncoder.buildMetadata(depth, grey, extras);
        meta.xybEncoded = true;
        meta.write(out);
        writeFrame(out, extraPlanes);
        return out.toByteArray();
    }

    static void checkInput(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha) {
        checkInput(planes, width, height, depth, grey,
                JxlEncoder.alphaOnly(depth, alpha, false));
    }

    static void checkInput(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, java.util.List<ExtraChannelInfo> extras) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions");
        }
        if (!depth.floatingPoint && (depth.bitsPerSample < 1 || depth.bitsPerSample > 16)) {
            throw new IllegalArgumentException("lossy integer samples must be 1..16 bits");
        }
        JxlEncoder.checkPlanes(planes, width, height, grey, extras);
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
        return toTarget(planes, width, height, BitDepth.of(bits), grey, alpha, alphaAssociated,
                distance);
    }

    /** {@link #encodeToTarget} for float samples. */
    public static byte[] encodeFloatToTarget(float[][] planes, int width, int height,
            BitDepth depth, boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        return toTarget(pack(planes, width, height, depth, grey, alpha), width, height, depth,
                grey, alpha, alphaAssociated, distance);
    }

    static byte[] toTarget(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha, boolean alphaAssociated, float distance)
            throws IOException {
        return toTarget(planes, width, height, depth, grey,
                JxlEncoder.alphaOnly(depth, alpha, alphaAssociated), distance);
    }

    static byte[] toTarget(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, java.util.List<ExtraChannelInfo> extras, float distance)
            throws IOException {
        float d = Math.max(0.1f, distance);
        // Read dynamically (not a static-final flag) so tests can toggle the
        // metric between encodes in one JVM, as the other -Djxl flags do.
        boolean perceptual = !Boolean.getBoolean("jxl.enc.maeRate");
        double target = perceptual ? perceptualTarget(d) : targetError(d);
        byte[] best = null;
        double bestMiss = Double.MAX_VALUE;
        float tryD = d;
        for (int round = 0; round < 3; round++) {
            byte[] jxl = encodeSamples(planes, width, height, depth, grey, extras, tryD);
            double err = perceptual
                    ? measurePerceptual(planes, width, height, depth, grey, jxl)
                    : measureError(planes, width, height, depth, grey, jxl);
            double miss = Math.abs(Math.log(Math.max(err, 1e-3) / target));
            if (miss < bestMiss) {
                bestMiss = miss;
                best = jxl;
            }
            if (err > target * 0.85 && err < target * 1.18) {
                break; // close enough
            }
            float next = nextDistance(tryD, err, target, d);
            if (Math.abs(next - tryD) < 0.01f) {
                break;
            }
            tryD = next;
        }
        return best;
    }

    /** The weighted mean absolute sRGB error a distance is expected to produce. */
    static double targetError(float distance) {
        return 0.9 * Math.pow(distance, 0.9);
    }

    /**
     * The {@link PerceptualDistortion} a distance is meant to achieve — the
     * curve the perceptual rate control steers toward. Calibrated so that a
     * typical photograph coded at the requested distance already lands near its
     * target (little iteration needed), leaving the loop to correct the content
     * the base quantiser misjudges: smooth gradients it bands, busy frames it
     * over-spends on. The metric's own scale is arbitrary, so this curve — not
     * any libjxl number — is the anchor.
     */
    static double perceptualTarget(float distance) {
        return 0.056 * Math.pow(distance, 0.73);
    }

    /** The distance to try next, given what the last one achieved. */
    static float nextDistance(float tried, double err, double target, float requested) {
        float next = (float) (tried * Math.pow(target / Math.max(err, 1e-3), 0.85));
        return Math.max(requested / 3, Math.min(requested * 3, next));
    }

    /**
     * The HF multiplier to try next, given what the last one achieved. The
     * coefficient step goes as 1/multiplier, so the correction is the error
     * ratio itself — where {@link #nextDistance} damps, because a distance moves
     * the step twice over, through the global scale as well. The ceiling is
     * nine-fold for the same reason: shifting a distance by three shifts the
     * step by nine, and control that can only reach for the multiplier needs
     * the same reach.
     *
     * <p>The floor, though, is the distance's own multiplier: this may refine,
     * never coarsen. Coarsening is what a whole-image loop does to an image it
     * is coding better than asked, and applied to a single band it is a bad
     * bargain twice over. The bands that come out better than asked are the
     * smooth ones, which were nearly free to begin with — so little is saved by
     * coarsening them — and smooth is exactly where a mean-absolute-error target
     * lies to you, because what a coarse quantiser does to a gradient is banding,
     * which barely moves the mean and is the first thing an eye finds.
     */
    static int nextHfMul(int tried, double err, double target, int base) {
        int next = (int) Math.round(tried * (Math.max(err, 1e-3) / target));
        return Math.max(base, Math.min(Math.min(200, base * 9), next));
    }

    /**
     * Weighted mean absolute colour error (G counted twice) after a
     * self-decode, normalised to the 8-bit scale the target model uses. Float
     * samples are already colour values, so full scale for them is 1.
     */
    static double measureError(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, byte[] jxl) throws IOException {
        com.ebremer.cygnus.jpegxl.decoder.JxlFrame frame =
                com.ebremer.cygnus.jpegxl.decoder.JxlDecoder.decode(jxl).frames.get(0);
        int n = width * height;
        double sum = 0;
        if (depth.floatingPoint) {
            float[][] out = frame.floatChannels;
            for (int i = 0; i < n; i++) {
                if (grey) {
                    sum += 4.0 * Math.abs(out[0][i] - depth.sampleToFloat(planes[0][i]));
                } else {
                    sum += Math.abs(out[0][i] - depth.sampleToFloat(planes[0][i]))
                            + 2.0 * Math.abs(out[1][i] - depth.sampleToFloat(planes[1][i]))
                            + Math.abs(out[2][i] - depth.sampleToFloat(planes[2][i]));
                }
            }
            return sum / (4.0 * n) * 255.0;
        }
        int[][] out = frame.channels;
        if (grey) {
            for (int i = 0; i < n; i++) {
                sum += 4.0 * Math.abs(out[0][i] - planes[0][i]);
            }
        } else {
            for (int i = 0; i < n; i++) {
                sum += Math.abs(out[0][i] - planes[0][i])
                        + 2.0 * Math.abs(out[1][i] - planes[1][i])
                        + Math.abs(out[2][i] - planes[2][i]);
            }
        }
        return sum / (4.0 * n) * (255.0 / ((1 << depth.bitsPerSample) - 1));
    }

    /**
     * {@link PerceptualDistortion} between the input and a self-decode, in the
     * sRGB display space both share. The perceptual rate control drives on this
     * in place of {@link #measureError}; it is what lets the loop see banding an
     * absolute-error target averages away.
     */
    static double measurePerceptual(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, byte[] jxl) throws IOException {
        com.ebremer.cygnus.jpegxl.decoder.JxlFrame frame =
                com.ebremer.cygnus.jpegxl.decoder.JxlDecoder.decode(jxl).frames.get(0);
        int n = width * height;
        int cc = grey ? 1 : 3;
        float[][] orig = new float[cc][n];
        float[][] dec = new float[cc][n];
        double scale = depth.floatingPoint ? 1.0 : 1.0 / ((1 << depth.bitsPerSample) - 1);
        for (int c = 0; c < cc; c++) {
            for (int i = 0; i < n; i++) {
                orig[c][i] = depth.floatingPoint
                        ? depth.sampleToFloat(planes[c][i])
                        : (float) (planes[c][i] * scale);
                dec[c][i] = depth.floatingPoint
                        ? frame.floatChannels[c][i]
                        : (float) (frame.channels[c][i] * scale);
            }
        }
        return new PerceptualDistortion(width, height).distance(orig, dec, grey);
    }

    // --------------------------------------------------------------- frame

    private void writeFrame(BitWriter out, int[][] extraPlanes) throws IOException {
        int groupColumns = ceilDiv(width, GROUP_DIM);
        int groupRows = ceilDiv(height, GROUP_DIM);
        int numGroups = groupColumns * groupRows;
        int lfDim = GROUP_DIM * 8;
        int lfCols = ceilDiv(width, lfDim);
        int lfRows = ceilDiv(height, lfDim);
        int numLfGroups = lfCols * lfRows;
        boolean single = numGroups == 1;

        Cells cells = windowCells();

        // ---- per-group coefficient tokens
        EntropyEncoder hfEnc = new EntropyEncoder(CONTEXTS_PER_PRESET, false);
        int[][] groupCtx = new int[numGroups][];
        int[][] groupVal = new int[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            Tokens tokens = new Tokens();
            tokenizeGroup(g / groupColumns, g % groupColumns, tokens);
            groupCtx[g] = java.util.Arrays.copyOf(tokens.ctx, tokens.n);
            groupVal[g] = java.util.Arrays.copyOf(tokens.val, tokens.n);
            for (int i = 0; i < tokens.n; i++) {
                hfEnc.count(tokens.ctx[i], tokens.val[i]);
            }
        }

        // ---- sections (bit-contiguous in the single-section case)
        if (single) {
            BitWriter one = new BitWriter();
            writeLfGlobalBits(one, true, extraPlanes);
            writeLfGroupBits(one, cells, 0, lfCols);
            writeHfGlobalBits(one, hfEnc, numGroups, 1);
            for (int i = 0; i < groupCtx[0].length; i++) {
                hfEnc.write(one, groupCtx[0][i], groupVal[0][i]);
            }
            one.zeroPadToByte();
            byte[] payload = one.toByteArray();
            writeFrameHeader(out, extras.size(), 128 | (noiseLut != null ? 1 : 0), null,
                    animated, animDuration, animLast);
            out.writeBool(false); // TOC not permuted
            out.zeroPadToByte();
            writeTocEntry(out, payload.length);
            out.zeroPadToByte();
            out.writeBytes(payload);
            return;
        }

        BitWriter lfg = new BitWriter();
        writeLfGlobalBits(lfg, false, extraPlanes);
        lfg.zeroPadToByte();
        byte[] lfGlobalBytes = lfg.toByteArray();
        byte[][] lfGroupBytes = new byte[numLfGroups][];
        for (int gg = 0; gg < numLfGroups; gg++) {
            BitWriter w = new BitWriter();
            writeLfGroupBits(w, cells, gg, lfCols);
            w.zeroPadToByte();
            lfGroupBytes[gg] = w.toByteArray();
        }
        BitWriter hfg = new BitWriter();
        writeHfGlobalBits(hfg, hfEnc, numGroups, 1);
        hfg.zeroPadToByte();
        byte[] hfGlobalBytes = hfg.toByteArray();
        byte[][] passBytes = new byte[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            BitWriter gw = new BitWriter();
            // no hf preset bits with a single preset
            for (int i = 0; i < groupCtx[g].length; i++) {
                hfEnc.write(gw, groupCtx[g][i], groupVal[g][i]);
            }
            if (!extras.isEmpty()) {
                int x0 = (g % groupColumns) * GROUP_DIM;
                int gy = (g / groupColumns) * GROUP_DIM;
                writeGroupExtras(gw, extraPlanes, 0, x0, gy,
                        Math.min(GROUP_DIM, width - x0), Math.min(GROUP_DIM, height - gy));
            }
            gw.zeroPadToByte();
            passBytes[g] = gw.toByteArray();
        }

        // ---- header + TOC + payload
        writeFrameHeader(out, extras.size(), 128 | (noiseLut != null ? 1 : 0), null,
                animated, animDuration, animLast);
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

    /**
     * A progressive (multi-pass) VarDCT frame. The quantised coefficients are the
     * same as a single-pass encode; each pass carries a shifted slice of them
     * ({@link #passSplit}), and the slices sum back on decode, so a full decode is
     * the single-pass image while a decode that stops after an early pass is a
     * coarser but valid picture. The DC/LF is not split — it stays whole in its own
     * sections — so an LfGroup already gives the 1:8 preview; the passes refine the
     * AC on top. Always multi-section (a pass is a section of its own); no extra
     * channels.
     */
    private void writeProgressiveFrame(BitWriter out, int[] shifts) throws IOException {
        int numPasses = shifts.length;
        int groupColumns = ceilDiv(width, GROUP_DIM);
        int numGroups = groupColumns * ceilDiv(height, GROUP_DIM);
        int lfDim = GROUP_DIM * 8;
        int lfCols = ceilDiv(width, lfDim);
        int numLfGroups = lfCols * ceilDiv(height, lfDim);

        Cells cells = windowCells();

        // tokenize every group for every pass; one entropy code per pass
        EntropyEncoder[] passEnc = new EntropyEncoder[numPasses];
        int[][][] passCtx = new int[numPasses][numGroups][];
        int[][][] passVal = new int[numPasses][numGroups][];
        for (int p = 0; p < numPasses; p++) {
            passEnc[p] = new EntropyEncoder(CONTEXTS_PER_PRESET, false);
            for (int g = 0; g < numGroups; g++) {
                Tokens t = new Tokens();
                tokenizeGroup(g / groupColumns, g % groupColumns, t, p, shifts);
                passCtx[p][g] = java.util.Arrays.copyOf(t.ctx, t.n);
                passVal[p][g] = java.util.Arrays.copyOf(t.val, t.n);
                for (int i = 0; i < t.n; i++) {
                    passEnc[p].count(t.ctx[i], t.val[i]);
                }
            }
        }

        BitWriter lfg = new BitWriter();
        writeLfGlobalBits(lfg, false, new int[0][]);
        lfg.zeroPadToByte();
        byte[] lfGlobalBytes = lfg.toByteArray();
        byte[][] lfGroupBytes = new byte[numLfGroups][];
        for (int gg = 0; gg < numLfGroups; gg++) {
            BitWriter w = new BitWriter();
            writeLfGroupBits(w, cells, gg, lfCols);
            w.zeroPadToByte();
            lfGroupBytes[gg] = w.toByteArray();
        }
        BitWriter hfg = new BitWriter();
        writeHfGlobalBits(hfg, passEnc, numGroups, 1);
        hfg.zeroPadToByte();
        byte[] hfGlobalBytes = hfg.toByteArray();

        // pass-group sections, pass-major to match Toc.passGroupIndex
        byte[][] passGroupBytes = new byte[numPasses * numGroups][];
        for (int p = 0; p < numPasses; p++) {
            for (int g = 0; g < numGroups; g++) {
                BitWriter gw = new BitWriter();
                for (int i = 0; i < passCtx[p][g].length; i++) {
                    passEnc[p].write(gw, passCtx[p][g][i], passVal[p][g][i]);
                }
                gw.zeroPadToByte();
                passGroupBytes[p * numGroups + g] = gw.toByteArray();
            }
        }

        writeFrameHeader(out, 0, 128 | (noiseLut != null ? 1 : 0), shifts);
        out.writeBool(false); // TOC not permuted
        out.zeroPadToByte();
        writeTocEntry(out, lfGlobalBytes.length);
        for (byte[] b : lfGroupBytes) {
            writeTocEntry(out, b.length);
        }
        writeTocEntry(out, hfGlobalBytes.length);
        for (byte[] b : passGroupBytes) {
            writeTocEntry(out, b.length);
        }
        out.zeroPadToByte();
        out.writeBytes(lfGlobalBytes);
        for (byte[] b : lfGroupBytes) {
            out.writeBytes(b);
        }
        out.writeBytes(hfGlobalBytes);
        for (byte[] b : passGroupBytes) {
            out.writeBytes(b);
        }
    }

    /** {@link #standalone} for a progressive frame. */
    byte[] standaloneProgressive(int[] shifts) throws IOException {
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = JxlEncoder.buildMetadata(depth, grey, extras);
        meta.xybEncoded = true;
        meta.write(out);
        writeProgressiveFrame(out, shifts);
        return out.toByteArray();
    }

    /** The default block context of visual channel {@code c} for an order id. */
    private static int blockCtxOf(int c, int orderId) {
        return DEFAULT_CTX_MAP[(c < 2 ? 1 - c : c) * 13 + orderId];
    }

    /** One pass, shift zero: the whole coefficient in a single pass. */
    private static final int[] SINGLE_SHIFT = {0};

    /**
     * The value pass {@code p} codes for a quantised coefficient {@code q}, so the
     * passes sum back to it — the decoder does {@code coeffs += value << shift[p]}.
     * With shifts strictly decreasing to zero, pass p carries q's bits in
     * [shift[p], shift[p-1]); pass 0 the top bits, the last pass the remainder. A
     * single pass (shift 0) returns q unchanged.
     */
    static int passSplit(int q, int pass, int[] shifts) {
        int s = shifts[pass];
        if (pass == 0) {
            return q >> s;
        }
        int prev = shifts[pass - 1];
        return (q >> s) - ((q >> prev) << (prev - s));
    }

    /**
     * Tokenizes one group of the loaded window. {@code gRow} is the group's row
     * in the image; the window must hold it.
     */
    void tokenizeGroup(int gRow, int gCol, Tokens tokens) {
        tokenizeGroup(gRow, gCol, tokens, 0, SINGLE_SHIFT);
    }

    /** Tokenizes one group for one progressive pass ({@link #passSplit}). */
    void tokenizeGroup(int gRow, int gCol, Tokens tokens, int pass, int[] shifts) {
        int by0 = (gRow << 5) - cellRow0;
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
                TransformType tt = TransformType.byType(blockType[k]);
                int numBlocks = tt.blockHeight * tt.blockWidth;
                int[] order = HfPass.naturalOrder(tt.orderId);
                int orderSize = order.length;
                int pw = tt.pixelWidth;
                boolean flip = tt.flip();
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
                            if (passSplit(q[y * pw + x], pass, shifts) != 0) {
                                nonZero++;
                            }
                        }
                    }
                    int predicted = VarDctState.predictNonZeroes(nonZeroes[c], groupY, groupX);
                    int blockCtx = blockCtxOf(c, tt.orderId);
                    int nonZeroCtx = VarDctState.nonZeroContext(predicted, blockCtx,
                            NUM_BLOCK_CLUSTERS);
                    tokens.add(nonZeroCtx, nonZero);
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
                        // the decoder writes this token at the plane position the
                        // flip maps the natural order slot to: (ox, oy) for a
                        // flipped (square or vertical) block, (oy, ox) otherwise.
                        // hfQuant is held in that plane layout.
                        int v = passSplit(q[(flip ? ox : oy) * pw + (flip ? oy : ox)], pass, shifts);
                        int prev = j == numBlocks
                                ? (nonZero > orderSize / 16 ? 0 : 1)
                                : (prevToken != 0 ? 1 : 0);
                        int ctx = histCtx
                                + VarDctState.coefficientContext(j, remaining, numBlocks, prev);
                        int packed = packSigned(v);
                        tokens.add(ctx, packed);
                        prevToken = packed;
                        if (v != 0) {
                            remaining--;
                        }
                    }
                }
            }
        }
    }

    void writeLfGlobalBits(BitWriter w, boolean single, int[][] extraPlanes) {
        // patches and splines are off (their flags are clear); the noise table,
        // if any, is the first thing the LfGlobal section carries
        if (noiseLut != null) {
            for (int i = 0; i < 8; i++) {
                w.write(Math.min(1023, Math.round(noiseLut[i] * 1024f)), 10);
            }
        }
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
        if (extras.isEmpty()) {
            w.writeBool(false); // no global MA tree; no modular channels either
            return;
        }
        w.writeBool(true);   // global tree present
        if (single) {
            // the extra channels fit the global stream: a learned tree and a real
            // code here, the tokens right after the stream header
            JxlEncoder.SubStream s = JxlEncoder.buildSubStream(
                    extraCrops(extraPlanes, 0, 0, 0, width, height));
            EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
            JxlEncoder.emitTree(s.tree, null, treeEnc);
            EntropyEncoder litProbe = new EntropyEncoder(s.numCtx, true, true);
            JxlEncoder.countLiterals(s.buf, litProbe);
            litProbe.prepareCosts();
            JxlEncoder.findMatches(s.buf, s.distMult, litProbe);
            EntropyEncoder dataEnc = new EntropyEncoder(s.numCtx, true, true, true);
            JxlEncoder.emitBuffer(s.buf, null, dataEnc, s.distMult);
            treeEnc.writeSpec(w);
            JxlEncoder.emitTree(s.tree, w, treeEnc);
            treeEnc.finishSection(w);
            dataEnc.writeSpec(w);
            w.writeBool(true);  // use_global_tree
            w.writeBool(true);  // default weighted-predictor parameters
            w.write(0, 2);      // nb_transforms = 0
            JxlEncoder.emitBuffer(s.buf, w, dataEnc, s.distMult);
            dataEnc.finishSection(w);
        } else {
            // the extras are bigger than a group: nothing is coded globally, so a
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

    /**
     * The extra channels cropped to one image rectangle, as coded channels.
     *
     * <p>An extra channel with a {@code dimShift} holds every 2^d'th sample, so
     * its slice of a group is that much smaller — and a slice that comes out
     * empty is not coded at all, which is what the decoder expects (it renumbers
     * the channels after it, and the channel number is a tree property).
     *
     * @param planes one plane per extra channel, holding image rows from
     *               {@code srcY0} at that channel's own width
     */
    private java.util.List<JxlEncoder.Chan> extraCrops(int[][] planes, int srcY0,
            int left, int top, int w, int h) {
        java.util.List<JxlEncoder.Chan> out = new java.util.ArrayList<>();
        for (int i = 0; i < extras.size(); i++) {
            ExtraChannelInfo ec = extras.get(i);
            int ecW = ec.planeWidth(width);
            int ecH = ec.planeHeight(height);
            JxlEncoder.Chan whole = new JxlEncoder.Chan(ecW, ecH, ec.dimShift, ec.dimShift,
                    planes[i]);
            int[] s = JxlEncoder.slice(whole, left, top, w, h);
            if (s[2] <= 0 || s[3] <= 0) {
                continue;
            }
            int row0 = s[1] - (srcY0 >> ec.dimShift);
            int[] px = new int[s[2] * s[3]];
            for (int y = 0; y < s[3]; y++) {
                System.arraycopy(planes[i], (row0 + y) * ecW + s[0], px, y * s[2], s[2]);
            }
            out.add(new JxlEncoder.Chan(s[2], s[3], px));
        }
        return out;
    }

    /**
     * Writes one group's extra channels as a self-contained modular sub-stream.
     * {@code planes} hold image rows from {@code srcY0}.
     */
    void writeGroupExtras(BitWriter gw, int[][] planes, int srcY0, int x0, int gy,
            int w, int h) {
        java.util.List<JxlEncoder.Chan> crops = extraCrops(planes, srcY0, x0, gy, w, h);
        if (crops.isEmpty()) {
            return; // a stream with no channels reads no header at all
        }
        JxlEncoder.SubStream s = JxlEncoder.buildSubStream(crops);
        gw.writeBool(false); // use_global_tree = false: the section is standalone
        gw.writeBool(true);  // default weighted-predictor parameters
        gw.write(0, 2);      // nb_transforms = 0
        EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
        JxlEncoder.emitTree(s.tree, null, treeEnc);
        treeEnc.writeSpec(gw);
        JxlEncoder.emitTree(s.tree, gw, treeEnc);
        treeEnc.finishSection(gw);
        EntropyEncoder litProbe = new EntropyEncoder(s.numCtx, true, true);
        JxlEncoder.countLiterals(s.buf, litProbe);
        litProbe.prepareCosts();
        JxlEncoder.findMatches(s.buf, s.distMult, litProbe);
        EntropyEncoder dataEnc = new EntropyEncoder(s.numCtx, true, true, true);
        JxlEncoder.emitBuffer(s.buf, null, dataEnc, s.distMult);
        dataEnc.writeSpec(gw);
        JxlEncoder.emitBuffer(s.buf, gw, dataEnc, s.distMult);
        dataEnc.finishSection(gw);
    }

    /** Writes LF group {@code gg}; {@code cells} must hold its rows. */
    void writeLfGroupBits(BitWriter w, Cells cells, int gg, int lfCols) {
        int lfBlockDim = GROUP_DIM; // blocks per LF group side (256 blocks = 2048 px)
        int row = gg / lfCols;
        int col = gg % lfCols;
        int bw = Math.min(lfBlockDim, w8 - col * lfBlockDim);
        int bh = Math.min(lfBlockDim, h8Total - row * lfBlockDim);
        int cellY0 = row * lfBlockDim - cells.row0;      // first cell row within the store
        int tileY0 = row * 32 - cells.row0 / 8;          // first tile row within the store

        w.write(0, 2); // extra precision = 0
        // LF quants as a modular image in stream order (Y, X, B)
        int[][] lf = new int[3][bw * bh];
        for (int i = 0; i < 3; i++) {
            int c = C_MAP[i]; // stream channel i is visual channel C_MAP[i]
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    lf[i][y * bw + x] =
                            cells.dc[c][(cellY0 + y) * w8 + col * lfBlockDim + x];
                }
            }
        }
        ModularSub.write(w, lf, new int[] {bw, bw, bw}, new int[] {bh, bh, bh});

        // HF metadata: varblock list in placement (raster origin) order
        java.util.ArrayList<Integer> types = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> muls = new java.util.ArrayList<>();
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int k = (cellY0 + y) * w8 + col * lfBlockDim + x;
                if (cells.type[k] >= 0) {
                    types.add((int) cells.type[k]);
                    muls.add(cells.mul[k] - 1);
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
        for (int ty = 0; ty < tileH; ty++) {
            for (int tx = 0; tx < tileW; tx++) {
                int gt = (tileY0 + ty) * tilesX + col * 32 + tx;
                metaPx[0][ty * tileW + tx] = cells.xFromY[gt];
                metaPx[1][ty * tileW + tx] = cells.bFromY[gt];
            }
        }
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                metaPx[3][y * bw + x] =
                        cells.sharp[(cellY0 + y) * w8 + col * lfBlockDim + x];
            }
        }
        ModularSub.write(w, metaPx,
                new int[] {tileW, tileW, nbBlocks, bw},
                new int[] {tileH, tileH, 2, bh});
    }

    void writeHfGlobalBits(BitWriter w, EntropyEncoder hfEnc, int numGroups, int numHfPresets) {
        writeHfGlobalBits(w, new EntropyEncoder[] {hfEnc}, numGroups, numHfPresets);
    }

    /** HfGlobal for {@code passEnc.length} progressive passes: one HfPass spec each. */
    void writeHfGlobalBits(BitWriter w, EntropyEncoder[] passEnc, int numGroups, int numHfPresets) {
        writeDequantHeader(w);
        int bits = numGroups > 1 ? 32 - Integer.numberOfLeadingZeros(numGroups - 1) : 0;
        w.write(numHfPresets - 1, bits);
        // one HfPass per pass: no coded orders, then that pass's coefficient code spec
        for (EntropyEncoder e : passEnc) {
            w.write(2, 2);     // used_orders selector 2 -> 0
            e.writeSpec(w);
        }
    }

    /**
     * The DequantMatrices header: {@code all_default} in the common case, or —
     * when a custom DCT8 matrix has been set — every set the library default
     * except the DCT8 one, which is coded as MODE_DCT distance bands. The same
     * bits {@link #setCustomDct8} reads back to derive the encoder's own weights,
     * so what is signalled and what was quantised with are one and the same.
     */
    private void writeDequantHeader(BitWriter w) {
        if (customDct8Params == null) {
            w.writeBool(true); // all default
        } else {
            writeCustomDequant(w, customDct8Params);
        }
    }

    /** {@code !all_default}, MODE_DCT bands for the DCT8 set, library default for the rest. */
    private static void writeCustomDequant(BitWriter w, float[][] params) {
        w.writeBool(false);
        for (int i = 0; i < 17; i++) {
            if (i != 0) {
                w.write(0, 3); // MODE_LIBRARY
                continue;
            }
            w.write(DequantMatrices.MODE_DCT, 3);
            int n = params[0].length;
            w.write(n - 1, 4);
            for (int c = 0; c < 3; c++) {
                // readDctParams multiplies value[0] by 64, so pre-divide it
                w.writeF16(params[c][0] / 64f);
                for (int k = 1; k < n; k++) {
                    w.writeF16(params[c][k]);
                }
            }
        }
    }

    /**
     * Quantise with a custom DCT8 matrix — {@code params[channel]} the MODE_DCT
     * distance bands (band 0 is the DC weight, the rest the log-ratios the format
     * calls {@code params}). The weights the encoder quantises with are read back
     * from the very bits that will be written, through the real
     * {@link DequantMatrices}, so an f16 rounding in the header cannot desync the
     * two sides. Only the DCT8 set changes; every other transform keeps its default.
     */
    void setCustomDct8(float[][] params) {
        try {
            BitWriter tmp = new BitWriter();
            writeCustomDequant(tmp, params);
            // numHfPresets: 0 bits for a single group, so nothing more to write
            tmp.zeroPadToByte();
            DequantMatrices dm = new DequantMatrices(new Bits(tmp.toByteArray()), 1, 1, null);
            this.weightsOf = dm.weights;
            this.customDct8Params = params;
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid custom DCT8 matrix", e);
        }
    }

    static void writeFrameHeader(BitWriter out, boolean alpha) {
        writeFrameHeader(out, alpha ? 1 : 0, 128);
    }

    static void writeFrameHeader(BitWriter out, int numExtra) {
        writeFrameHeader(out, numExtra, 128);
    }

    static void writeFrameHeader(BitWriter out, int numExtra, long flags) {
        writeFrameHeader(out, numExtra, flags, null);
    }

    /**
     * {@code shifts} non-null and longer than one selects progressive: {@code shifts.length}
     * passes (the last shift zero, not written) with no downsampling.
     */
    static void writeFrameHeader(BitWriter out, int numExtra, long flags, int[] shifts) {
        writeFrameHeader(out, numExtra, flags, shifts, false, 0, true);
    }

    /**
     * The full-frame REPLACE header, still or animated. Animated frames carry a
     * {@code duration} and a real {@code isLast}; a still frame is the {@code
     * animated=false, isLast=true} case, which writes exactly the bits the still
     * path always did.
     */
    static void writeFrameHeader(BitWriter out, int numExtra, long flags, int[] shifts,
            boolean animated, long duration, boolean isLast) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(0, 2);             // frame_type: regular
        out.writeBool(false);        // encoding: VarDCT
        out.writeU64(flags);         // 128 = skip adaptive LF smoothing (+1 for noise)
        // xyb encoded => no do_YCbCr bit
        out.write(0, 2);             // log upsampling
        for (int i = 0; i < numExtra; i++) {
            out.write(0, 2);         // extra channel upsampling
        }
        out.write(2, 3);             // x_qm_scale = 2
        out.write(2, 3);             // b_qm_scale = 2
        writePasses(out, shifts);
        out.writeBool(false);        // have_crop (full frame)
        int blendEntries = 1 + numExtra;
        for (int i = 0; i < blendEntries; i++) {
            out.write(0, 2);         // blend mode: replace (full frame -> no source)
        }
        if (animated) {
            writeDuration(out, duration);
        }
        out.writeBool(isLast);
        // full-frame REPLACE frames are independent — none is kept as a reference,
        // so save_as_reference is 0 (written for every non-last frame). The
        // before-colour-transform copy is coded only for a frame that could be kept,
        // which a positive-duration REPLACE frame with save_as_reference 0 is not.
        if (!isLast) {
            out.write(0, 2);         // save_as_reference = 0
        }
        if (!isLast && duration == 0) {
            out.writeBool(false);    // save_before_colour_transform
        }
        out.write(0, 2);             // name length
        if (NO_FILTERS) {
            out.writeBool(false);    // RestorationFilter not all_default
            out.writeBool(false);    // gaborish off
            out.write(0, 2);         // epf iterations = 0
            out.writeU64(0);         // restoration filter extensions
        } else {
            out.writeBool(true);     // RestorationFilter all_default: gaborish + EPF
        }
        out.writeU64(0);             // frame header extensions
    }

    /** duration: sel 0 -> 0, sel 1 -> 1, sel 2 -> u(8), sel 3 -> u(32). */
    private static void writeDuration(BitWriter out, long duration) {
        if (duration == 0) {
            out.write(0, 2);
        } else if (duration == 1) {
            out.write(1, 2);
        } else if (duration < 256) {
            out.write(2, 2);
            out.write((int) duration, 8);
        } else {
            out.write(3, 2);
            out.write((int) duration, 32);
        }
    }

    /** The Passes bundle: {@code num_passes}, {@code num_ds}=0, then each pass's shift. */
    private static void writePasses(BitWriter out, int[] shifts) {
        int n = shifts == null ? 1 : shifts.length;
        if (n == 1) {
            out.write(0, 2);         // num_passes = 1 (u32 selector 0)
            return;
        }
        if (n == 2) {
            out.write(1, 2);
        } else if (n == 3) {
            out.write(2, 2);
        } else {
            out.write(3, 2);
            out.write(n - 4, 3);
        }
        out.write(0, 2);             // num_ds = 0 (no downsampling)
        for (int i = 0; i < n - 1; i++) {
            out.write(shifts[i], 2); // last pass's shift is zero, not written
        }
    }

    static void writeTocEntry(BitWriter out, int size) {
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

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int ceilLog2(int x) {
        return x > 1 ? 32 - Integer.numberOfLeadingZeros(x - 1) : 0;
    }
}
