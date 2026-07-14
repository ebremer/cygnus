package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.RestorationFilter;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.decoder.RestorationFilters;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import com.ebremer.cygnus.jpegxl.vardct.Dct;
import com.ebremer.cygnus.jpegxl.vardct.DequantMatrices;
import com.ebremer.cygnus.jpegxl.vardct.HfPass;
import com.ebremer.cygnus.jpegxl.vardct.TransformType;
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
    private static final TransformType DCT16 = TransformType.byType(4);

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
    private final boolean hasAlpha;
    private final int globalScale;
    private int hfMul;
    private final float[] scaledDequant = new float[3];
    private final float[] baseSfc = new float[3];   // before the block multiplier
    private final float[][][] weightsOf;            // [parameterIndex][channel]
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
        this(width, height, BitDepth.of(bits), grey, alpha, distance);
    }

    VarDctEncoder(int width, int height, BitDepth depth, boolean grey, boolean alpha,
            float distance) {
        this.width = width;
        this.height = height;
        this.paddedWidth = (width + 7) & ~7;
        this.paddedHeight = (height + 7) & ~7;
        this.depth = depth;
        this.bits = depth.bitsPerSample;
        this.maxVal = depth.floatingPoint ? 1 : (1 << depth.bitsPerSample) - 1;
        this.grey = grey;
        this.hasAlpha = alpha;
        float d = Math.max(0.1f, distance);
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

        // Two cell rows at a time: a 16x16 block starts only on an even row and
        // covers the row below, so a pair of rows is the smallest unit that
        // never reaches outside itself — and pairs can therefore run in any
        // order, or at once.
        sweep(ceilDiv(h8, 2), strip -> {
            float[] block = new float[256];
            float[][] c8 = new float[4][64];
            float[] c16 = new float[256];
            float[] s0 = new float[256];
            float[] s1 = new float[256];
            float[] llf = new float[4];
            float[] dcT = new float[4];
            int byN = Math.min(strip * 2 + 2, h8);
            for (int by = strip * 2; by < byN; by++) {
                for (int bx = 0; bx < w8; bx++) {
                    if (blockType[by * w8 + bx] != -1) {
                        continue; // already decided (or covered by a 16x16)
                    }
                    boolean try16 = !no16
                            && (by & 1) == 0 && (bx & 1) == 0
                            && by + 1 < h8 && bx + 1 < w8
                            && blockType[by * w8 + bx + 1] == -1
                            && blockType[(by + 1) * w8 + bx] == -1
                            && blockType[(by + 1) * w8 + bx + 1] == -1;
                    if (try16 && chooseDct16(by, bx, factor, block, c8, c16, s0, s1, llf, dcT)) {
                        continue;
                    }
                    quantiseDct8(by, bx, factor, block, c8[0], s0, s1);
                }
            }
        });
        chooseSharpness();
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

    /** Quantises one 8x8 block in every channel, luma first for CfL. */
    private void quantiseDct8(int by, int bx, float[] factor,
            float[] block, float[] coeffs, float[] s0, float[] s1) {
        int k = by * w8 + bx;
        int mul = clampMul(Math.round(hfMul * factor[k]));
        blockType[k] = 0;
        blockMul[k] = mul;
        float[] dqY = new float[64];
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            for (int y = 0; y < 8; y++) {
                System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8, block, y * 8, 8);
            }
            Dct.forward2D(block, 0, 8, coeffs, 0, 8, 8, 8, s0, s1);
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
            }
            hfQuant[c][k] = q;
        }
    }

    /**
     * Quantises the aligned 16x16 area both ways and keeps the DCT16 version
     * when its estimated rate is clearly lower. Returns true when chosen.
     */
    private boolean chooseDct16(int by, int bx, float[] factor,
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
        float[] dqY16 = new float[256];
        float[][] dqY8 = new float[4][64];
        for (int ci = 0; ci < 3; ci++) {
            int c = Y_FIRST[ci];
            float cfl = cflFactor(c, by, bx);
            // 16x16 candidate
            for (int y = 0; y < 16; y++) {
                System.arraycopy(xyb[c], (y0 + by * 8 + y) * paddedWidth + bx * 8, block, y * 16, 16);
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
                    float step = sfc16 * w16[x * 16 + y];
                    q[j] = Math.round((c16[j] - cfl * dqY16[j]) / step);
                    if (c == 1) {
                        dqY16[j] = q[j] * step;
                    }
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
                    System.arraycopy(xyb[c], (y0 + cy * 8 + y) * paddedWidth + cx * 8,
                            block, y * 8, 8);
                }
                Dct.forward2D(block, 0, 8, c8[i], 0, 8, 8, 8, s0, s1);
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
        sweep(h8, by -> {
            float[] deq = new float[256];
            float[] dqY = new float[256];
            float[] dcT = new float[4];
            float[] llf = new float[4];
            float[] s0 = new float[256];
            float[] s1 = new float[256];
            for (int bx = 0; bx < w8; bx++) {
                int k = by * w8 + bx;
                byte type = blockType[k];
                if (type < 0) {
                    continue; // covered by the 16x16 block that owns the cell
                }
                TransformType tt = type == DCT16.type ? DCT16 : DCT8;
                int dim = type == DCT16.type ? 16 : 8;
                int llfDim = dim >> 3;
                for (int ci = 0; ci < 3; ci++) {
                    int c = Y_FIRST[ci];
                    float cfl = cflFactor(c, by, bx);
                    float sfc = baseSfc[c] / blockMul[k];
                    float[] wc = weightsOf[tt.parameterIndex][c];
                    int[] q = hfQuant[c][k];
                    for (int y = 0; y < dim; y++) {
                        for (int x = 0; x < dim; x++) {
                            if (y < llfDim && x < llfDim) {
                                continue; // LLF comes from the DC image, below
                            }
                            int j = y * dim + x;
                            float hf = q[j] * sfc * wc[x * dim + y];
                            deq[j] = hf + cfl * dqY[j];
                            if (c == 1) {
                                dqY[j] = hf;
                            }
                        }
                    }
                    // The DC entries were derived from the LLF coefficients by an
                    // llfDim-square inverse DCT; run it forwards to get them back.
                    if (llfDim == 1) {
                        deq[0] = dcQuant[c][k] * scaledDequant[c];
                    } else {
                        for (int i = 0; i < 4; i++) {
                            int cell = (by + (i >> 1)) * w8 + bx + (i & 1);
                            dcT[i] = dcQuant[c][cell] * scaledDequant[c];
                        }
                        Dct.forward2D(dcT, 0, 2, llf, 0, 2, 2, 2, s0, s1);
                        for (int i = 0; i < 4; i++) {
                            deq[(i >> 1) * dim + (i & 1)] = llf[i] * tt.llfScale[i];
                        }
                    }
                    Dct.inverse2D(deq, 0, dim, out[c], by * 8 * paddedWidth + bx * 8,
                            paddedWidth, dim, dim, s0, s1, false);
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
        return encodeSamples(pack(planes, width, height, depth, grey, alpha), width, height,
                depth, grey, alpha, alphaAssociated, distance);
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
        checkInput(planes, width, height, depth, grey, alpha);
        VarDctEncoder enc = new VarDctEncoder(width, height, depth, grey, alpha, distance);
        enc.loadWhole(planes);
        enc.quantiseWindow(Double.NaN);
        return enc.standalone(alpha ? planes[grey ? 1 : 3] : null, alphaAssociated);
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
    byte[] standalone(int[] alphaPlane, boolean alphaAssociated) throws IOException {
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = JxlEncoder.buildMetadata(depth, grey, hasAlpha, alphaAssociated);
        meta.xybEncoded = true;
        meta.write(out);
        writeFrame(out, alphaPlane);
        return out.toByteArray();
    }

    static void checkInput(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions");
        }
        if (!depth.floatingPoint && (depth.bitsPerSample < 1 || depth.bitsPerSample > 16)) {
            throw new IllegalArgumentException("lossy integer samples must be 1..16 bits");
        }
        int numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        if (planes.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got "
                    + planes.length);
        }
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
        float d = Math.max(0.1f, distance);
        double target = targetError(d);
        byte[] best = null;
        double bestMiss = Double.MAX_VALUE;
        float tryD = d;
        for (int round = 0; round < 3; round++) {
            byte[] jxl = encodeSamples(planes, width, height, depth, grey, alpha,
                    alphaAssociated, tryD);
            double err = measureError(planes, width, height, depth, grey, jxl);
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

    // --------------------------------------------------------------- frame

    private void writeFrame(BitWriter out, int[] alphaPlane) throws IOException {
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
            writeLfGlobalBits(one, true, alphaPlane);
            writeLfGroupBits(one, cells, 0, lfCols);
            writeHfGlobalBits(one, hfEnc, numGroups, 1);
            for (int i = 0; i < groupCtx[0].length; i++) {
                hfEnc.write(one, groupCtx[0][i], groupVal[0][i]);
            }
            one.zeroPadToByte();
            byte[] payload = one.toByteArray();
            writeFrameHeader(out, hasAlpha);
            out.writeBool(false); // TOC not permuted
            out.zeroPadToByte();
            writeTocEntry(out, payload.length);
            out.zeroPadToByte();
            out.writeBytes(payload);
            return;
        }

        BitWriter lfg = new BitWriter();
        writeLfGlobalBits(lfg, false, alphaPlane);
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
            if (alphaPlane != null) {
                int x0 = (g % groupColumns) * GROUP_DIM;
                int gy = (g / groupColumns) * GROUP_DIM;
                writeGroupAlpha(gw, alphaPlane, 0, x0, gy,
                        Math.min(GROUP_DIM, width - x0), Math.min(GROUP_DIM, height - gy));
            }
            gw.zeroPadToByte();
            passBytes[g] = gw.toByteArray();
        }

        // ---- header + TOC + payload
        writeFrameHeader(out, hasAlpha);
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

    /**
     * Tokenizes one group of the loaded window. {@code gRow} is the group's row
     * in the image; the window must hold it.
     */
    void tokenizeGroup(int gRow, int gCol, Tokens tokens) {
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
                        // the decoder writes this token at the flipped grid
                        // position (ox, oy)
                        int v = q[ox * pw + oy];
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

    void writeLfGlobalBits(BitWriter w, boolean single, int[] alphaPlane) {
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
        if (!hasAlpha) {
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

    /**
     * Writes one group's alpha crop as a self-contained modular sub-stream.
     * {@code alpha} holds image rows from {@code srcY0} at the image's width.
     */
    void writeGroupAlpha(BitWriter gw, int[] alpha, int srcY0, int x0, int gy, int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(alpha, (gy - srcY0 + y) * width + x0, px, y * w, w);
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
        w.writeBool(true); // dequant matrices all default
        int bits = numGroups > 1 ? 32 - Integer.numberOfLeadingZeros(numGroups - 1) : 0;
        w.write(numHfPresets - 1, bits);
        // HfPass 0: no coded orders, then the coefficient code spec
        w.write(2, 2);     // used_orders selector 2 -> 0
        hfEnc.writeSpec(w);
    }

    static void writeFrameHeader(BitWriter out, boolean alpha) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(0, 2);             // frame_type: regular
        out.writeBool(false);        // encoding: VarDCT
        out.writeU64(128);           // flags: skip adaptive LF smoothing
        // xyb encoded => no do_YCbCr bit
        out.write(0, 2);             // log upsampling
        if (alpha) {
            out.write(0, 2);         // extra channel upsampling
        }
        out.write(2, 3);             // x_qm_scale = 2
        out.write(2, 3);             // b_qm_scale = 2
        out.write(0, 2);             // num_passes = 1
        out.writeBool(false);        // have_crop
        int blendEntries = 1 + (alpha ? 1 : 0);
        for (int i = 0; i < blendEntries; i++) {
            out.write(0, 2);         // blend mode: replace (full frame -> no source)
        }
        out.writeBool(true);         // is_last
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
