package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ColourEncoding;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;
import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.entropy.HybridUintConfig;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.modular.ModularStream;
import com.ebremer.cygnus.jpegxl.modular.Transform;
import com.ebremer.cygnus.jpegxl.modular.WpState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless JPEG XL encoder producing modular-mode codestreams. For RGB images
 * it either palettises the colour channels (few-colour images) or picks the
 * cheapest reversible colour transform; every channel then chooses between the
 * gradient and the self-correcting (weighted) predictor, and long runs of
 * identical residuals are collapsed into LZ77 copies. Output decodes
 * bit-exactly with libjxl and with this library's decoder.
 */
public final class JxlEncoder {

    static final int GROUP_DIM = 256;
    private static final int GROUP_SIZE_SHIFT_BITS = 1; // group_size_shift = 7 + 1 -> 256
    private static final int MAX_PALETTE = 1024;
    /** Dequant-matrix streams the decoder counts before the pass groups. */
    private static final int NUM_DCT_PARAMS = 17;

    /**
     * One coded modular channel. The shifts are how far the channel has been
     * halved by {@link Squeeze}: a channel with hshift 2 holds every fourth
     * column of the image, so a group's slice of it starts at
     * {@code left >> 2} and is a quarter as wide. Zero everywhere until a
     * squeeze happens.
     */
    static final class Chan {
        final int w;
        final int h;
        final int hshift;
        final int vshift;
        final int[] px;
        int predictor = 5; // 5 = gradient, 6 = weighted

        Chan(int w, int h, int[] px) {
            this(w, h, 0, 0, px);
        }

        Chan(int w, int h, int hshift, int vshift, int[] px) {
            this.w = w;
            this.h = h;
            this.hshift = hshift;
            this.vshift = vshift;
            this.px = px;
        }

        /** How coarse this channel is: which pass or LF group codes it. */
        int shift() {
            return Math.min(hshift, vshift);
        }
    }

    /** A buffered token stream for one section. */
    static final class TokenBuf {
        int[] ctx = new int[1 << 12];
        int[] val = new int[1 << 12];
        int n;

        // LZ77 segmentation, computed once by findMatches
        boolean matched;
        int[] mPos = new int[0];
        int[] mLen = new int[0];
        int[] mVal = new int[0]; // distance token values
        int nMatches;

        void add(int c, int v) {
            if (n == val.length) {
                ctx = java.util.Arrays.copyOf(ctx, n * 2);
                val = java.util.Arrays.copyOf(val, n * 2);
            }
            ctx[n] = c;
            val[n] = v;
            n++;
        }

        void addMatch(int pos, int len, int distValue) {
            if (nMatches == mPos.length) {
                int cap = Math.max(16, nMatches * 2);
                mPos = java.util.Arrays.copyOf(mPos, cap);
                mLen = java.util.Arrays.copyOf(mLen, cap);
                mVal = java.util.Arrays.copyOf(mVal, cap);
            }
            mPos[nMatches] = pos;
            mLen[nMatches] = len;
            mVal[nMatches] = distValue;
            nMatches++;
        }
    }

    private final int width;
    private final int height;
    private final BitDepth depth;
    private final int bits;   // depth.bitsPerSample, used everywhere it always was
    private final boolean grey;
    private final List<ExtraChannelInfo> extras;
    private final int colourCount;
    private final int[][] input;
    private final int numInput;
    private final boolean progressive;

    // decided by prepare()
    private final List<Chan> chans = new ArrayList<>();
    private int nbMeta;
    private int rctType = -1;    // coded RCT type, or -1 for none
    private int[] paletteData;   // paletteNumC x paletteSize components, or null
    private int paletteSize;
    private int paletteNumC;     // channels folded into the palette
    private boolean squeezed;    // the channel list has been squeezed
    private boolean prepared;
    private FrameParams frameParams; // set when this encoder writes one frame of many
    private List<PatchDictWriter.Patch> patchDict; // set to stamp reference patches over this frame
    private final boolean xyb;   // lossy XYB-modular: colour coded as quantised XYB
    private final float[] lfDequant; // {X,Y,B} DC dequant steps when xyb, else null

    /**
     * Passes a progressive frame is cut into. Squeeze leaves channels at shifts
     * 0, 1, 2 and beyond; anything from 3 up is coarse enough for the LF groups,
     * and the three below get a pass each, coarsest first.
     */
    private static final int PROGRESSIVE_PASSES = 3;

    private JxlEncoder(int[][] planes, int width, int height, BitDepth depth, boolean grey,
            List<ExtraChannelInfo> extras, boolean progressive) {
        this(planes, width, height, depth, grey, extras, progressive, false, null);
    }

    private JxlEncoder(int[][] planes, int width, int height, BitDepth depth, boolean grey,
            List<ExtraChannelInfo> extras, boolean progressive, boolean xyb, float[] lfDequant) {
        this.depth = depth;
        this.progressive = progressive;
        this.width = width;
        this.height = height;
        this.bits = depth.bitsPerSample;
        this.grey = grey;
        this.extras = List.copyOf(extras);
        this.colourCount = grey ? 1 : 3;
        this.numInput = colourCount + this.extras.size();
        this.xyb = xyb;
        this.lfDequant = lfDequant;
        checkPlanes(planes, width, height, grey, this.extras);
        this.input = new int[numInput][];
        for (int i = 0; i < numInput; i++) {
            this.input[i] = planes[i].clone();
        }
    }

    /**
     * The alpha channel an image with one would declare, at the image's own
     * depth — which is what the boolean-alpha entry points are shorthand for.
     */
    static List<ExtraChannelInfo> alphaOnly(BitDepth depth, boolean alpha, boolean associated) {
        return alpha ? List.of(ExtraChannelInfo.alpha(depth, associated)) : List.of();
    }

    /**
     * Width of an extra channel's plane. A channel with {@code dimShift} d holds
     * every 2^d'th sample along each axis, so its plane is that much smaller and
     * the decoder stretches it back out.
     */
    static int ecWidth(ExtraChannelInfo ec, int width) {
        return ec.planeWidth(width);
    }

    static int ecHeight(ExtraChannelInfo ec, int height) {
        return ec.planeHeight(height);
    }

    /** Every plane present and exactly the size its channel asks for. */
    static void checkPlanes(int[][] planes, int width, int height, boolean grey,
            List<ExtraChannelInfo> extras) {
        int colour = grey ? 1 : 3;
        int n = colour + extras.size();
        if (planes.length != n) {
            throw new IllegalArgumentException("expected " + n + " planes, got " + planes.length);
        }
        for (int i = 0; i < n; i++) {
            int w = i < colour ? width : ecWidth(extras.get(i - colour), width);
            int h = i < colour ? height : ecHeight(extras.get(i - colour), height);
            if (planes[i] == null || planes[i].length != w * h) {
                throw new IllegalArgumentException("plane " + i + " should be " + w + "x" + h
                        + " (" + w * h + " samples), got "
                        + (planes[i] == null ? "null" : planes[i].length));
            }
        }
    }

    /**
     * Encodes samples to a bare JPEG XL codestream.
     *
     * @param planes sample planes in [0, 2^bits), colour channels first
     *               (1 for greyscale, 3 for RGB), then an optional alpha plane
     */
    public static byte[] encode(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated) throws IOException {
        return encode(planes, width, height, bits, grey,
                alphaOnly(BitDepth.of(bits), alpha, alphaAssociated));
    }

    /**
     * Encodes samples with any number of extra channels.
     *
     * <p>Alpha is only the extra channel everyone has. The format allows as many
     * as an image needs, of nine kinds — depth, selection mask, CMYK black, CFA,
     * thermal, spot colour, and two flavours of "something the reader may not
     * understand" — each with its own name, its own bit depth, and its own
     * resolution. A microscope slide can carry its fluorescence channels; a
     * render can carry its depth buffer and its object mask; a scientific stack
     * can carry sixteen bands and say what each of them is. All of it rides in
     * the modular stream and comes back exactly.
     *
     * @param planes colour planes first (1 for greyscale, 3 for RGB), then one
     *               plane per entry in {@code extras}, in that order. An extra
     *               channel with a {@code dimShift} is smaller: see
     *               {@link ExtraChannelInfo#step()}.
     * @param extras what each extra channel is; build them with
     *               {@link ExtraChannelInfo#alpha}, {@link ExtraChannelInfo#of},
     *               {@link ExtraChannelInfo#spot} or {@link ExtraChannelInfo#cfa}
     */
    public static byte[] encode(int[][] planes, int width, int height, int bits,
            boolean grey, List<ExtraChannelInfo> extras) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (bits < 1 || bits > 31) {
            throw new IllegalArgumentException("bits per sample must be in 1..31");
        }
        return new JxlEncoder(planes, width, height, BitDepth.of(bits), grey, extras, false).run();
    }

    /**
     * Encodes an RGB image as a <em>lossy</em> modular codestream in XYB colour —
     * the second lossy path beside {@link VarDctEncoder}, using the modular coder
     * rather than the DCT.
     *
     * <p>The colour is turned to XYB, the perceptual space the format quantises
     * in, and each channel is divided by a step before coding: a coarser step
     * throws away more and codes smaller. There is no DCT and no block choice, so
     * a photograph compresses less than through VarDCT at the same fidelity, but
     * the coder is the plain modular one and the loss is a single, legible
     * per-channel quantisation — which is exactly what suits flat, synthetic or
     * near-lossless material. The channels are laid down as libjxl lays them —
     * Y, X, and B carried as {@code B - Y} — and dequantised on the way back by
     * the DC steps written in {@code LfChannelDequantization}.
     *
     * @param distance a fidelity dial: 1.0 is the format's default quantisation,
     *                 larger is coarser (smaller file, more loss), smaller is
     *                 finer. Clamped to a sane range.
     */
    public static byte[] encodeXyb(int[][] planes, int width, int height, int bits,
            float distance) throws IOException {
        return encodeXyb(planes, width, height, bits, distance, List.of());
    }

    /** {@link #encodeXyb} with an optional alpha plane, coded losslessly. */
    public static byte[] encodeXyb(int[][] planes, int width, int height, int bits,
            float distance, boolean alpha, boolean alphaAssociated) throws IOException {
        return encodeXyb(planes, width, height, bits, distance,
                alphaOnly(BitDepth.of(bits), alpha, alphaAssociated));
    }

    /** {@link #encodeXyb} carrying extra channels (alpha and the rest), coded losslessly. */
    public static byte[] encodeXyb(int[][] planes, int width, int height, int bits,
            float distance, List<ExtraChannelInfo> extras) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (bits < 1 || bits > 31) {
            throw new IllegalArgumentException("bits per sample must be in 1..31");
        }
        float[] dq = xybDequant(distance);
        return new JxlEncoder(planes, width, height, BitDepth.of(bits), false, extras,
                false, true, dq).run();
    }

    /**
     * The default XYB DC dequant steps, {@code {X, Y, B}}, scaled by
     * {@code distance}. The defaults match the decoder's
     * {@link com.ebremer.cygnus.jpegxl.decoder.JxlDecoder}
     * {@code LfChannelDequantization} defaults, so distance 1.0 reproduces them
     * and can be signalled with the all-default bit.
     */
    static float[] xybDequant(float distance) {
        float d = Math.max(0.05f, Math.min(distance, 64f));
        return new float[] {d / 4096f, d / 512f, d / 256f};
    }

    /**
     * Encodes a progressive (responsive) lossless codestream: the same pixels,
     * laid out so that a prefix of the bytes is already a picture.
     *
     * <p>{@link Squeeze} rewrites the channels into a small image of the picture
     * followed by the detail that doubles it, repeatedly; the frame is then cut
     * into passes by how coarse each channel is, coarsest first. A reader with
     * the first tenth of the file has the whole image at low resolution rather
     * than a tenth of it at full resolution, and each further pass sharpens what
     * is already there. {@link com.ebremer.cygnus.jpegxl.decoder.JxlDecoder#decodePartial}
     * reads such a prefix.
     *
     * <p>The file is the same size to within a few percent either way — squeeze
     * buys the layout, not the ratio.
     */
    public static byte[] encodeProgressive(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated) throws IOException {
        return encodeProgressive(planes, width, height, bits, grey,
                alphaOnly(BitDepth.of(bits), alpha, alphaAssociated));
    }

    /** {@link #encodeProgressive} with any number of extra channels. */
    public static byte[] encodeProgressive(int[][] planes, int width, int height, int bits,
            boolean grey, List<ExtraChannelInfo> extras) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (bits < 1 || bits > 31) {
            throw new IllegalArgumentException("bits per sample must be in 1..31");
        }
        return new JxlEncoder(planes, width, height, BitDepth.of(bits), grey, extras, true).run();
    }

    /**
     * Encodes floating-point samples losslessly, as {@link BitDepth#float32()}
     * unless a narrower layout is asked for.
     *
     * <p>A float is a sign, an exponent and a mantissa laid end to end, and that
     * is exactly how the format carries one: the modular coder codes the bit
     * pattern as an integer, and the decoder reads the bits back as a float. So
     * this is the ordinary lossless encoder with the samples reinterpreted, and
     * it is bit-exact — including negative zero, the subnormals, the infinities
     * and NaN.
     *
     * <p>What it is <em>not</em> is a compressor of magnitudes. Neighbouring
     * floats have neighbouring bit patterns only while they share an exponent;
     * across a power of two the pattern jumps, and the predictors see a cliff
     * where the picture is smooth. Float images therefore compress a good deal
     * worse than the same picture quantised to integers — that is the format's
     * bargain, not this encoder's.
     *
     * @param planes sample planes, colour channels first (1 for greyscale, 3 for
     *               RGB), then an optional alpha plane, which rides at the same
     *               depth
     * @param depth  a floating-point {@link BitDepth}; anything narrower than
     *               {@link BitDepth#float32()} refuses samples it cannot hold
     *               exactly, rather than rounding them
     */
    public static byte[] encodeFloat(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha, boolean alphaAssociated) throws IOException {
        return encodeFloat(planes, width, height, depth, grey,
                alphaOnly(depth, alpha, alphaAssociated));
    }

    /**
     * {@link #encodeFloat} with any number of extra channels, each carried at the
     * depth it declares. A float image can therefore hold an 8-bit mask without
     * widening it to 32 bits, and a float channel asked for a sample its depth
     * cannot represent exactly refuses it rather than rounding it.
     */
    public static byte[] encodeFloat(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, List<ExtraChannelInfo> extras) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (!depth.floatingPoint) {
            throw new IllegalArgumentException("encodeFloat needs a floating-point depth");
        }
        return encodeSamples(pack(planes, width, height, depth, grey, extras), width, height,
                depth, grey, extras);
    }

    /** Encodes float samples as IEEE binary32, the depth that holds any of them. */
    public static byte[] encodeFloat(float[][] planes, int width, int height,
            boolean grey, boolean alpha, boolean alphaAssociated) throws IOException {
        return encodeFloat(planes, width, height, BitDepth.float32(), grey, alpha,
                alphaAssociated);
    }

    /**
     * Encodes samples already laid out as {@code depth} lays them out — a float
     * sample being an integer bit pattern, this is the same coder either way.
     */
    static byte[] encodeSamples(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha, boolean alphaAssociated) throws IOException {
        return encodeSamples(planes, width, height, depth, grey,
                alphaOnly(depth, alpha, alphaAssociated));
    }

    static byte[] encodeSamples(int[][] planes, int width, int height, BitDepth depth,
            boolean grey, List<ExtraChannelInfo> extras) throws IOException {
        return new JxlEncoder(planes, width, height, depth, grey, extras, false).run();
    }

    /** Lays float planes out as the samples the coder will carry. */
    static int[][] pack(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, boolean alpha) {
        return pack(planes, width, height, depth, grey, alphaOnly(depth, alpha, false));
    }

    /**
     * Lays float planes out as samples, each channel at the depth it declares:
     * the colour planes at the image's, every extra at its own.
     *
     * <p>An extra channel with an <em>integer</em> depth in a float image is not
     * a bit pattern — it is a value in [0, 1], which is how the decoder will read
     * it back (it divides by the channel's full scale). A mask is a mask whatever
     * the colour planes are made of.
     */
    static int[][] pack(float[][] planes, int width, int height, BitDepth depth,
            boolean grey, List<ExtraChannelInfo> extras) {
        if (!depth.floatingPoint) {
            throw new IllegalArgumentException("float samples need a floating-point depth");
        }
        int colour = grey ? 1 : 3;
        int numInput = colour + extras.size();
        if (planes.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got "
                    + planes.length);
        }
        int[][] out = new int[numInput][];
        for (int c = 0; c < numInput; c++) {
            ExtraChannelInfo ec = c < colour ? null : extras.get(c - colour);
            BitDepth d = ec == null ? depth : ec.bitDepth;
            int w = ec == null ? width : ecWidth(ec, width);
            int h = ec == null ? height : ecHeight(ec, height);
            if (planes[c] == null || planes[c].length != w * h) {
                throw new IllegalArgumentException("plane " + c + " should be " + w + "x" + h);
            }
            int[] p = new int[w * h];
            if (d.floatingPoint) {
                for (int i = 0; i < p.length; i++) {
                    p[i] = d.floatToSample(planes[c][i]);
                }
            } else {
                long max = (1L << d.bitsPerSample) - 1;
                for (int i = 0; i < p.length; i++) {
                    p[i] = Math.round(Math.max(0f, Math.min(1f, planes[c][i])) * max);
                }
            }
            out[c] = p;
        }
        return out;
    }

    /**
     * Encodes with an embedded preview image (same channel layout and bit
     * depth as the main image; the preview is at most 4096 pixels per side).
     */
    public static byte[] encodeWithPreview(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated,
            int[][] previewPlanes, int previewWidth, int previewHeight) throws IOException {
        return encodeWithPreview(planes, width, height, bits, grey,
                alphaOnly(BitDepth.of(bits), alpha, alphaAssociated),
                previewPlanes, previewWidth, previewHeight);
    }

    /** {@link #encodeWithPreview} with any number of extra channels. */
    public static byte[] encodeWithPreview(int[][] planes, int width, int height, int bits,
            boolean grey, List<ExtraChannelInfo> extras,
            int[][] previewPlanes, int previewWidth, int previewHeight) throws IOException {
        if (previewWidth <= 0 || previewHeight <= 0
                || previewWidth > 4096 || previewHeight > 4096) {
            throw new IllegalArgumentException("bad preview dimensions");
        }
        BitDepth depth = BitDepth.of(bits);
        JxlEncoder main = new JxlEncoder(planes, width, height, depth, grey, extras, false);
        JxlEncoder preview = new JxlEncoder(previewPlanes, previewWidth, previewHeight,
                depth, grey, extras, false);
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = buildMetadata(depth, grey, extras);
        meta.previewWidth = previewWidth;
        meta.previewHeight = previewHeight;
        meta.write(out);
        preview.writeFrame(out);
        main.writeFrame(out);
        return out.toByteArray();
    }

    private byte[] run() throws IOException {
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        buildMetadata(depth, grey, extras, xyb).write(out);
        writeFrame(out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------- animation

    /**
     * One frame of an animation: its pixels, how long it shows, where it sits on
     * the canvas, and how it combines with what is already there.
     *
     * <p>Most frames are whole pictures shown one after another — {@link #full}.
     * A frame can instead cover only a rectangle of the canvas ({@link #patch},
     * for when little changes between frames) or lay itself over the running
     * canvas through its alpha ({@link #blended}, for a translucent overlay). The
     * encoder keeps whatever earlier frame a later one needs to build on; the
     * caller only describes each frame.
     */
    public static final class AnimationFrame {
        final int[][] planes;
        final int width;
        final int height;
        final int x0;
        final int y0;
        final int durationTicks;
        final int blendMode;
        final int blendAlphaChannel;
        final long timecode;
        final boolean hasTimecode;

        private AnimationFrame(int[][] planes, int width, int height, int x0, int y0,
                int durationTicks, int blendMode, int blendAlphaChannel) {
            this(planes, width, height, x0, y0, durationTicks, blendMode, blendAlphaChannel,
                    0, false);
        }

        private AnimationFrame(int[][] planes, int width, int height, int x0, int y0,
                int durationTicks, int blendMode, int blendAlphaChannel, long timecode,
                boolean hasTimecode) {
            if (durationTicks < 0) {
                throw new IllegalArgumentException("negative frame duration");
            }
            this.planes = planes;
            this.width = width;
            this.height = height;
            this.x0 = x0;
            this.y0 = y0;
            this.durationTicks = durationTicks;
            this.blendMode = blendMode;
            this.blendAlphaChannel = blendAlphaChannel;
            this.timecode = timecode;
            this.hasTimecode = hasTimecode;
        }

        /**
         * A copy of this frame stamped with a SMPTE-packed 32-bit {@code timecode}.
         * Setting a timecode on any frame turns timecodes on for the whole
         * animation; frames left without one carry timecode 0.
         */
        public AnimationFrame withTimecode(long timecode) {
            return new AnimationFrame(planes, width, height, x0, y0, durationTicks, blendMode,
                    blendAlphaChannel, timecode & 0xffffffffL, true);
        }

        /** A whole-canvas frame that replaces everything before it. */
        public static AnimationFrame full(int[][] planes, int width, int height,
                int durationTicks) {
            return new AnimationFrame(planes, width, height, 0, 0, durationTicks,
                    BLEND_REPLACE, 0);
        }

        /**
         * A whole-canvas frame laid over the running canvas through its alpha:
         * where the alpha is opaque the frame shows, where it is clear the frame
         * before it shows through. {@code alphaChannel} is the extra channel that
         * carries it (0 for the first).
         */
        public static AnimationFrame blended(int[][] planes, int width, int height,
                int durationTicks, int alphaChannel) {
            return new AnimationFrame(planes, width, height, 0, 0, durationTicks,
                    BLEND_BLEND, alphaChannel);
        }

        /**
         * A frame covering only the rectangle at {@code (x0, y0)} of the given
         * size; the rest of the canvas keeps the frame before it. What an
         * animation uses when little moves between frames.
         */
        public static AnimationFrame patch(int[][] planes, int width, int height,
                int x0, int y0, int durationTicks) {
            return new AnimationFrame(planes, width, height, x0, y0, durationTicks,
                    BLEND_REPLACE, 0);
        }
    }

    /**
     * Encodes an animation: a sequence of frames shown one after another, at
     * {@code tpsNumerator/tpsDenominator} ticks per second, looping
     * {@code numLoops} times (0 = forever). Each frame carries its own duration
     * in ticks and its own way of combining with the canvas; see
     * {@link AnimationFrame}. Lossless throughout — every frame comes back
     * exactly — with any set of extra channels.
     */
    public static byte[] encodeAnimation(List<AnimationFrame> frames, int width, int height,
            int bits, boolean grey, List<ExtraChannelInfo> extras,
            int tpsNumerator, int tpsDenominator, long numLoops) throws IOException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("an animation needs at least one frame");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (tpsNumerator <= 0 || tpsDenominator <= 0) {
            throw new IllegalArgumentException("ticks per second must be positive");
        }
        BitDepth depth = BitDepth.of(bits);
        ImageMetadata meta = buildMetadata(depth, grey, extras);
        meta.haveAnimation = true;
        meta.animTpsNumerator = tpsNumerator;
        meta.animTpsDenominator = tpsDenominator;
        meta.animNumLoops = numLoops;
        boolean haveTimecodes = frames.stream().anyMatch(f -> f.hasTimecode);
        meta.animHaveTimecodes = haveTimecodes;

        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        meta.write(out);

        int n = frames.size();
        for (int i = 0; i < n; i++) {
            AnimationFrame f = frames.get(i);
            boolean isLast = i == n - 1;
            boolean partial = f.width != width || f.height != height || f.x0 != 0 || f.y0 != 0;
            boolean composite = partial || f.blendMode != BLEND_REPLACE;

            JxlEncoder enc = new JxlEncoder(f.planes, f.width, f.height, depth, grey, extras,
                    false);
            FrameParams p = new FrameParams();
            p.canvasWidth = width;
            p.canvasHeight = height;
            p.frameWidth = f.width;
            p.frameHeight = f.height;
            p.x0 = f.x0;
            p.y0 = f.y0;
            p.haveCrop = partial;
            p.haveAnimation = true;
            p.duration = f.durationTicks;
            p.haveTimecodes = haveTimecodes;
            p.timecode = f.timecode;
            p.blendMode = f.blendMode;
            p.blendAlphaChannel = f.blendAlphaChannel;
            p.isLast = isLast;
            // a compositing frame builds on the canvas kept in slot 1; keep this
            // frame there too when the next one will need it
            if (composite) {
                p.blendSource = 1;
            }
            if (!isLast && buildsOnCanvas(frames.get(i + 1), width, height)) {
                p.saveAsReference = 1;
            }
            enc.frameParams = p;
            enc.writeFrame(out);
        }
        return out.toByteArray();
    }

    // --------------------------------------------------------------- patches

    private static final int PATCH_TILE = 16;   // repeated-tile granularity
    private static final int PATCH_MIN_REPEATS = 3;

    /**
     * Encodes losslessly, coding repeated tiles once. Screenshots and pages of
     * text stamp the same glyph — a letter, a button, an icon — over and over; a
     * tile that recurs is coded once into a reference frame and
     * {@link com.ebremer.cygnus.jpegxl.features.PatchesDictionary REPLACE-stamped}
     * at each site, the main frame carrying only the flattened background. It is
     * lossless (the stamp copies the exact pixels) and it never loses: the plain
     * encode is produced too and the smaller of the two returned, so photographs
     * and other unrepetitive images fall straight through.
     */
    public static byte[] encodeWithPatches(int[][] planes, int width, int height, int bits,
            boolean grey) throws IOException {
        byte[] plain = encode(planes, width, height, bits, grey, false, false);
        try {
            byte[] patched = tryPatches(planes, width, height, bits, grey);
            if (patched != null && patched.length < plain.length) {
                return patched;
            }
        } catch (RuntimeException e) {
            // detection or assembly hit an edge case: the plain encode still stands
        }
        return plain;
    }

    private static byte[] tryPatches(int[][] planes, int width, int height, int bits,
            boolean grey) throws IOException {
        int numColour = grey ? 1 : 3;
        int t = PATCH_TILE;
        int cols = width / t;
        int rows = height / t;
        if (cols == 0 || rows == 0) {
            return null;
        }
        // group whole tiles by their exact contents
        java.util.Map<String, List<int[]>> groups = new java.util.HashMap<>();
        for (int tr = 0; tr < rows; tr++) {
            for (int tc = 0; tc < cols; tc++) {
                String key = tileKey(planes, numColour, width, tc * t, tr * t, t);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {tc, tr});
            }
        }
        // glyphs are the tiles that recur enough to pay; place them in a reference
        // atlas that fills the top of a full-canvas frame, the rest left blank
        int atlasCap = cols * rows;   // how many glyph slots fit in the canvas grid
        int[][] atlas = new int[numColour][width * height];
        int[][] blanked = new int[numColour][];
        for (int c = 0; c < numColour; c++) {
            blanked[c] = planes[c].clone();
        }
        List<PatchDictWriter.Patch> patchList = new ArrayList<>();
        int glyph = 0;
        // per-channel image mean to flatten the blanked tiles into
        int[] mean = new int[numColour];
        for (int c = 0; c < numColour; c++) {
            long sum = 0;
            for (int v : planes[c]) {
                sum += v;
            }
            mean[c] = (int) (sum / planes[c].length);
        }
        for (List<int[]> group : groups.values()) {
            if (group.size() < PATCH_MIN_REPEATS || glyph >= atlasCap) {
                continue;
            }
            int ax = (glyph % cols) * t;
            int ay = (glyph / cols) * t;
            int[] first = group.get(0);
            // copy the glyph into the atlas
            for (int c = 0; c < numColour; c++) {
                for (int y = 0; y < t; y++) {
                    System.arraycopy(planes[c], (first[1] * t + y) * width + first[0] * t,
                            atlas[c], (ay + y) * width + ax, t);
                }
            }
            int[][] positions = new int[group.size()][2];
            for (int i = 0; i < group.size(); i++) {
                int[] tile = group.get(i);
                positions[i][0] = tile[0] * t;
                positions[i][1] = tile[1] * t;
                for (int c = 0; c < numColour; c++) {
                    for (int y = 0; y < t; y++) {
                        java.util.Arrays.fill(blanked[c], (tile[1] * t + y) * width + tile[0] * t,
                                (tile[1] * t + y) * width + tile[0] * t + t, mean[c]);
                    }
                }
            }
            patchList.add(new PatchDictWriter.Patch(0, ax, ay, t, t, positions));
            glyph++;
        }
        if (patchList.isEmpty()) {
            return null;
        }

        BitDepth depth = BitDepth.of(bits);
        ImageMetadata meta = buildMetadata(depth, grey, List.of());
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        meta.write(out);

        // reference frame: the glyph atlas, kept in slot 1 as a pure patch source
        // (reference-only, so it is not composited into the displayed image)
        JxlEncoder ref = new JxlEncoder(atlas, width, height, depth, grey, List.of(), false);
        FrameParams rp = FrameParams.single(0, 1, width, height);
        rp.frameType = 2;        // reference-only, saved to slot 0 (duration 0)
        rp.saveAsReference = 0;
        rp.saveBeforeCT = true;  // patches copy from the pre-colour-transform snapshot
        rp.isLast = false;
        ref.frameParams = rp;
        ref.writeFrame(out);

        // main frame: the flattened background, patches stamping the glyphs back
        JxlEncoder main = new JxlEncoder(blanked, width, height, depth, grey, List.of(), false);
        FrameParams mp = FrameParams.single(0, 1, width, height);
        mp.isLast = true;
        main.frameParams = mp;
        main.patchDict = patchList;
        main.writeFrame(out);
        return out.toByteArray();
    }

    private static String tileKey(int[][] planes, int numColour, int width,
            int x0, int y0, int t) {
        // two chars per sample: a char holds 16 bits, a sample up to 31
        StringBuilder sb = new StringBuilder(2 * numColour * t * t);
        for (int c = 0; c < numColour; c++) {
            for (int y = 0; y < t; y++) {
                int row = (y0 + y) * width + x0;
                for (int x = 0; x < t; x++) {
                    int v = planes[c][row + x];
                    sb.append((char) (v >>> 16)).append((char) v);
                }
            }
        }
        return sb.toString();
    }

    /** Whether a frame needs the running canvas beneath it (it is a patch or a blend). */
    private static boolean buildsOnCanvas(AnimationFrame f, int width, int height) {
        return f.width != width || f.height != height || f.x0 != 0 || f.y0 != 0
                || f.blendMode != BLEND_REPLACE;
    }

    // ------------------------------------------------------------ transforms

    /** Chooses transforms and per-channel predictors, and builds the channel list. */
    private void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        if (xyb) {
            // Colour is already decorrelated by the XYB transform and quantised in
            // place, so there is no palette, no RCT and no squeeze — just the three
            // XYB channels (plus any extra channels) coded as they stand.
            prepareXyb();
            for (int i = 0; i < numInput; i++) {
                chans.add(chanOf(i));
            }
            for (int i = nbMeta; i < chans.size(); i++) {
                choosePredictor(chans.get(i));
            }
            return;
        }
        // A palette turns pixels into indices into a lookup, and an index has no
        // magnitude: the average of index 3 and index 9 is not a colour between
        // them. Squeeze is built on averaging, so the two do not go together.
        paletteNumC = progressive ? 0 : tryPalette();
        if (paletteNumC > 0) {
            // palette meta channel + index channel (+ channels left outside)
            nbMeta = 1;
            int[] index = input[0]; // reused as the index plane by tryPalette
            chans.add(new Chan(paletteSize, paletteNumC, 0, -1, paletteData));
            chans.add(new Chan(width, height, index));
            for (int c = paletteNumC; c < numInput; c++) {
                chans.add(chanOf(c));
            }
        } else {
            if (!grey) {
                int[][] rgb = {input[0], input[1], input[2]};
                rctType = selectRct(rgb, width, height);
                applyForwardRct(rctType, rgb);
                input[0] = rgb[0];
                input[1] = rgb[1];
                input[2] = rgb[2];
                if (rctType == 0) {
                    rctType = -1; // nothing to code
                }
            }
            for (int i = 0; i < numInput; i++) {
                chans.add(chanOf(i));
            }
        }
        if (progressive) {
            // after the colour transform, which is where the decoder reads the
            // squeeze from and so where it will rebuild the same plan
            for (Transform.Squeeze sq : Squeeze.plan(chans, nbMeta)) {
                Squeeze.apply(chans, sq);
            }
            squeezed = true;
        }
        for (int i = nbMeta; i < chans.size(); i++) {
            choosePredictor(chans.get(i));
        }
    }

    /**
     * Turns the RGB colour planes into the three quantised XYB channels the
     * decoder expects — {@code input[0..2]} become Y, X and {@code B - Y} as
     * integers. The forward opsin is the inverse of the decoder's (matrix scaled
     * by {@code 255 / intensityTarget}, then the cube-root non-linearity); each
     * XYB value is divided by its DC step and rounded, and B carries its Y
     * subtracted off exactly as libjxl decorrelates it. The reconstruction is
     * {@code X = step_x * Xi}, {@code Y = step_y * Yi}, {@code B = step_b * (Yi +
     * (Bi - Yi))} — the inverse this rounds against.
     */
    private void prepareXyb() {
        ImageMetadata opsin = new ImageMetadata(); // default opsin matrix and biases
        float itScale = 255f / opsin.intensityTarget;
        double[] inv = new double[9];
        for (int i = 0; i < 9; i++) {
            inv[i] = opsin.opsinInverse[i] * itScale;
        }
        double[] fwd = invert3x3(inv);
        double[] bias = {opsin.opsinBias[0], opsin.opsinBias[1], opsin.opsinBias[2]};
        double[] cbrtBias = {Math.cbrt(bias[0]), Math.cbrt(bias[1]), Math.cbrt(bias[2])};
        double maxVal = (1L << bits) - 1;
        int n = width * height;
        int[] cY = new int[n];
        int[] cX = new int[n];
        int[] cB = new int[n];
        int[] r0 = input[0];
        int[] g0 = input[1];
        int[] b0 = input[2];
        for (int i = 0; i < n; i++) {
            double r = srgbToLinear(sampleColour(r0[i], maxVal));
            double g = srgbToLinear(sampleColour(g0[i], maxVal));
            double b = srgbToLinear(sampleColour(b0[i], maxVal));
            double mixL = fwd[0] * r + fwd[1] * g + fwd[2] * b;
            double mixM = fwd[3] * r + fwd[4] * g + fwd[5] * b;
            double mixS = fwd[6] * r + fwd[7] * g + fwd[8] * b;
            double gl = Math.cbrt(mixL - bias[0]) + cbrtBias[0];
            double gm = Math.cbrt(mixM - bias[1]) + cbrtBias[1];
            double gs = Math.cbrt(mixS - bias[2]) + cbrtBias[2];
            double x = (gl - gm) / 2;
            double y = (gl + gm) / 2;
            int yi = (int) Math.rint(y / lfDequant[1]);
            int xi = (int) Math.rint(x / lfDequant[0]);
            int bi = (int) Math.rint(gs / lfDequant[2]);
            cY[i] = yi;
            cX[i] = xi;
            cB[i] = bi - yi;
        }
        input[0] = cY;
        input[1] = cX;
        input[2] = cB;
    }

    private double sampleColour(int sample, double maxVal) {
        return depth.floatingPoint ? depth.sampleToFloat(sample) : sample / maxVal;
    }

    /** The inverse of the decoder's sign-mirrored sRGB transfer. */
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
     * Input plane {@code i} as a coded channel. Colour planes are the image's
     * size; an extra channel is as big as its {@code dimShift} leaves it, and
     * carries that shift — which is what the decoder gives it, and what puts a
     * quarter-resolution mask in the LF groups rather than the pass groups.
     */
    private Chan chanOf(int i) {
        if (i < colourCount) {
            return new Chan(width, height, input[i]);
        }
        ExtraChannelInfo ec = extras.get(i - colourCount);
        return new Chan(ecWidth(ec, width), ecHeight(ec, height),
                ec.dimShift, ec.dimShift, input[i]);
    }

    /**
     * Builds a global palette when the image has few distinct sample tuples,
     * folding as many channels as pay off: all channels first (so alpha or a
     * grey plane rides along), colour-only as the fallback. Returns the
     * number of folded channels (0 for no palette); on success
     * {@code input[0]} becomes the index plane.
     */
    private int tryPalette() {
        if (bits > 16) {
            return 0; // colour keys pack 16 bits per channel
        }
        // A palette replaces its channels with one index plane, so it can only
        // fold channels of the same shape — and the key packs 16 bits each into
        // a long, so no more than four of them.
        boolean foldable = numInput <= 4;
        for (ExtraChannelInfo ec : extras) {
            foldable &= ec.dimShift == 0 && ec.bitDepth.bitsPerSample <= 16
                    && !ec.bitDepth.floatingPoint;
        }
        if (foldable && tryPalette(numInput)) {
            return numInput;
        }
        if (numInput > colourCount && colourCount <= 4 && tryPalette(colourCount)) {
            return colourCount;
        }
        return 0;
    }

    private boolean tryPalette(int m) {
        Map<Long, Integer> colours = new HashMap<>();
        int n = width * height;
        for (int i = 0; i < n; i++) {
            long key = 0;
            for (int c = 0; c < m; c++) {
                int v = input[c][i];
                // the key packs 16 bits per channel: a sample outside them
                // would fold distinct tuples into one entry
                if (v < 0 || v > 0xffff) {
                    return false;
                }
                key |= (long) v << (16 * c);
            }
            if (colours.size() >= MAX_PALETTE && !colours.containsKey(key)) {
                return false;
            }
            colours.putIfAbsent(key, colours.size());
        }
        if (colours.size() < 2 || colours.size() >= n / 4) {
            return false;
        }
        // sort by luma-ish value so neighbouring indices have similar colours
        int lumaChannels = Math.min(m, 3);
        Long[] sorted = colours.keySet().toArray(Long[]::new);
        java.util.Arrays.sort(sorted, (x, y) -> Long.compare(lumaKey(x, lumaChannels),
                lumaKey(y, lumaChannels)));
        paletteSize = sorted.length;
        paletteData = new int[m * paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            long key = sorted[i];
            for (int c = 0; c < m; c++) {
                paletteData[c * paletteSize + i] = (int) ((key >>> (16 * c)) & 0xffff);
            }
            colours.put(key, i);
        }
        int[] index = input[0];
        for (int i = 0; i < n; i++) {
            long key = 0;
            for (int c = 0; c < m; c++) {
                key |= (long) input[c][i] << (16 * c);
            }
            index[i] = colours.get(key);
        }
        return true;
    }

    private static long lumaKey(long key, int lumaChannels) {
        if (lumaChannels < 3) {
            return key & 0xffff;
        }
        return (key & 0xffff) + 2 * ((key >>> 16) & 0xffff) + ((key >>> 32) & 0xffff);
    }

    /** The decoder's RCT channel permutations, indexed by {@code type / 7}. */
    static final int[][] RCT_PERMUTATIONS = {
        {0, 1, 2}, {1, 2, 0}, {2, 0, 1}, {0, 2, 1}, {1, 0, 2}, {2, 1, 0},
    };

    /**
     * Picks the cheapest RCT type over all permuted variants (0..41) by a
     * row-sampled gradient-cost estimate. Pure permutations (type % 7 == 0,
     * type > 0) are skipped: the per-channel cost sum is permutation-invariant,
     * so they can never beat type 0.
     */
    static int selectRct(int[][] rgb, int width, int height) {
        int stride = height > 512 ? 3 : 1; // sample rows on large images
        int best = 0;
        long bestCost = Long.MAX_VALUE;
        int[][] cur = {new int[width], new int[width], new int[width]};
        int[][] prev = {new int[width], new int[width], new int[width]};
        for (int type = 0; type < 42; type++) {
            if (type > 0 && type % 7 == 0) {
                continue;
            }
            long cost = 0;
            for (int y = 0; y < height; y += stride) {
                transformRow(type, rgb, y, width, cur);
                boolean hasN = y > 0;
                if (hasN) {
                    transformRow(type, rgb, y - 1, width, prev);
                }
                for (int c = 0; c < 3; c++) {
                    cost += rowGradientCost(cur[c], hasN ? prev[c] : null, width);
                }
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = type;
            }
        }
        return best;
    }

    /** Copies row {@code y} of the permuted channels and applies the pointwise RCT. */
    private static void transformRow(int type, int[][] rgb, int y, int width, int[][] out) {
        int[] perm = RCT_PERMUTATIONS[type / 7];
        int off = y * width;
        for (int c = 0; c < 3; c++) {
            System.arraycopy(rgb[perm[c]], off, out[c], 0, width);
        }
        forwardRct(type % 7, out[0], out[1], out[2]);
    }

    private static long rowGradientCost(int[] row, int[] rowN, int width) {
        long cost = 0;
        for (int x = 0; x < width; x++) {
            long vW = x > 0 ? row[x - 1] : (rowN != null ? rowN[x] : 0);
            long vN = rowN != null ? rowN[x] : vW;
            long vNW = (x > 0 && rowN != null) ? rowN[x - 1] : vW;
            long lo = Math.min(vW, vN);
            long hi = Math.max(vW, vN);
            long pred = Math.min(Math.max(lo, vW + vN - vNW), hi);
            int packed = packSigned((int) (row[x] - pred));
            cost += 32 - Integer.numberOfLeadingZeros(packed + 1);
        }
        return cost;
    }

    /**
     * Applies the full coded RCT (permutation then arithmetic) in place:
     * {@code planes[0..2]} become the coded channels in coded order.
     */
    static void applyForwardRct(int type, int[][] planes) {
        int[] perm = RCT_PERMUTATIONS[type / 7];
        int[] a = planes[perm[0]];
        int[] b = planes[perm[1]];
        int[] c = planes[perm[2]];
        forwardRct(type % 7, a, b, c);
        planes[0] = a;
        planes[1] = b;
        planes[2] = c;
    }

    /** Approximate coded size of a channel under the weighted predictor. */
    private static long wpCost(Chan c) {
        WpState wp = new WpState(WpState.WpParams.DEFAULT, c.w);
        long cost = 0;
        int[] px = c.px;
        for (int y = 0; y < c.h; y++) {
            int row = y * c.w;
            int rowN = row - c.w;
            for (int x = 0; x < c.w; x++) {
                long vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                long vN = y > 0 ? px[rowN + x] : vW;
                long vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                long vNE = (x + 1 < c.w && y > 0) ? px[rowN + x + 1] : vN;
                long vNN = y > 1 ? px[row - 2 * c.w + x] : vN;
                wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                int packed = packSigned((int) (px[row + x] - wp.prediction()));
                cost += 32 - Integer.numberOfLeadingZeros(packed + 1);
                wp.afterPredict(x, y, px[row + x]);
            }
        }
        return cost;
    }

    static void choosePredictor(Chan c) {
        long grad = fullGradientCost(c);
        long wp = wpCost(c);
        c.predictor = wp * 20 < grad * 19 ? 6 : 5; // require a 5% win for WP
    }

    static long fullGradientCost(Chan c) {
        long cost = 0;
        int[] px = c.px;
        for (int y = 0; y < c.h; y++) {
            int row = y * c.w;
            int rowN = row - c.w;
            for (int x = 0; x < c.w; x++) {
                long vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                long vN = y > 0 ? px[rowN + x] : vW;
                long vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                long lo = Math.min(vW, vN);
                long hi = Math.max(vW, vN);
                long pred = Math.min(Math.max(lo, vW + vN - vNW), hi);
                int packed = packSigned((int) (px[row + x] - pred));
                cost += 32 - Integer.numberOfLeadingZeros(packed + 1);
            }
        }
        return cost;
    }

    /** Applies the inverse of the decoder's RCT: encodes type {@code t}. */
    static void forwardRct(int t, int[] p0, int[] p1, int[] p2) {
        int n = p0.length;
        switch (t) {
            case 0 -> {
            }
            case 1 -> {
                for (int i = 0; i < n; i++) {
                    p2[i] -= p0[i];
                }
            }
            case 2 -> {
                for (int i = 0; i < n; i++) {
                    p1[i] -= p0[i];
                }
            }
            case 3 -> {
                for (int i = 0; i < n; i++) {
                    p1[i] -= p0[i];
                    p2[i] -= p0[i];
                }
            }
            case 4 -> {
                for (int i = 0; i < n; i++) {
                    p1[i] -= (p0[i] + p2[i]) >> 1;
                }
            }
            case 5 -> {
                for (int i = 0; i < n; i++) {
                    p2[i] -= p0[i];
                    p1[i] -= p0[i] + (p2[i] >> 1);
                }
            }
            case 6 -> { // YCoCg
                for (int i = 0; i < n; i++) {
                    int red = p0[i];
                    int green = p1[i];
                    int blue = p2[i];
                    int co = red - blue;
                    int tmp = blue + (co >> 1);
                    int cg = green - tmp;
                    p0[i] = tmp + (cg >> 1);
                    p1[i] = co;
                    p2[i] = cg;
                }
            }
            default -> throw new IllegalArgumentException("rct " + t);
        }
    }

    // -------------------------------------------------------------- framing

    /** Writes one complete frame (header, TOC, sections) to {@code out}. */
    private void writeFrame(BitWriter out) throws IOException {
        prepare();

        int groupColumns = ceilDiv(width, GROUP_DIM);
        int groupRows = ceilDiv(height, GROUP_DIM);
        int numGroups = groupColumns * groupRows;
        int lfColumns = ceilDiv(width, GROUP_DIM * 8);
        int numLfGroups = lfColumns * ceilDiv(height, GROUP_DIM * 8);
        int numPasses = progressive ? PROGRESSIVE_PASSES : 1;
        boolean single = numPasses == 1 && numGroups == 1;

        // channels coded in the global stream: meta channels plus the prefix of
        // channels no larger than a group (mirrors the decoder's rule)
        int numGlobal = chans.size();
        if (!single) {
            numGlobal = nbMeta;
            for (int i = nbMeta; i < chans.size(); i++) {
                Chan c = chans.get(i);
                if (c.w > GROUP_DIM || c.h > GROUP_DIM) {
                    break;
                }
                numGlobal++;
            }
        }

        // ---- sort the remaining channels into sub-streams by how coarse they
        // are: shift 3 and up is coarse enough for the LF groups, and the rest
        // get a pass each, coarsest first
        List<Integer> lfChans = new ArrayList<>();
        List<List<Integer>> passChans = new ArrayList<>();
        for (int p = 0; p < numPasses; p++) {
            passChans.add(new ArrayList<>());
        }
        int[] passOf = new int[chans.size()];
        java.util.Arrays.fill(passOf, -2); // global
        for (int i = numGlobal; i < chans.size(); i++) {
            int m = chans.get(i).shift();
            if (m >= 3) {
                passOf[i] = -1;
                lfChans.add(i);
            } else {
                int p = numPasses == 1 ? 0 : PROGRESSIVE_PASSES - 1 - m;
                passOf[i] = p;
                passChans.get(p).add(i);
            }
        }

        int[][] groupGrid = new int[numGroups][];
        for (int g = 0; g < numGroups; g++) {
            groupGrid[g] = groupRect(g, groupColumns);
        }
        int[][] lfGrid = new int[numLfGroups][];
        for (int gg = 0; gg < numLfGroups; gg++) {
            lfGrid[gg] = lfGroupRect(gg, lfColumns);
        }

        // ---- a bracket is "ragged" when one of its channels has nothing inside
        // some group. That channel is then absent from that group's stream, which
        // renumbers the ones after it — and the channel number is what the tree's
        // chain switches on, so there a channel is coded with the subtree of
        // whichever channel usually sits at its position. That is only sound if a
        // bracket's subtrees are interchangeable, so make them so: one predictor
        // throughout, and no residual multiplier to divide by (an integer
        // division that only comes out exact for the channel it was learned from).
        // No channel of an unsqueezed image ever vanishes, so nothing here fires
        // unless squeeze is on and the last group is a sliver.
        boolean[] ragged = new boolean[chans.size()];
        for (int b = -1; b < numPasses; b++) {
            List<Integer> bracket = b < 0 ? lfChans : passChans.get(b);
            int[][] grid = b < 0 ? lfGrid : groupGrid;
            boolean anyVanishes = false;
            for (int i : bracket) {
                for (int[] r : grid) {
                    int[] s = slice(chans.get(i), r[0], r[1], r[2], r[3]);
                    anyVanishes |= s[2] <= 0 || s[3] <= 0;
                }
            }
            if (!anyVanishes) {
                continue;
            }
            int weighted = 0;
            for (int i : bracket) {
                weighted += chans.get(i).predictor == 6 ? 1 : -1;
            }
            for (int i : bracket) {
                ragged[i] = true;
                chans.get(i).predictor = weighted > 0 ? 6 : 5;
            }
        }

        // ---- learn per-channel subtrees and build the global tree
        int[] refOf = referenceChannels(numGlobal);
        Map<Chan, TNode> subs = new HashMap<>();
        for (int i = 0; i < chans.size(); i++) {
            Chan c = chans.get(i);
            List<int[]> rects = i < numGlobal
                    ? List.of(new int[] {0, 0, c.w, c.h})
                    : slicesOver(c, passOf[i] == -1 ? lfGrid : groupGrid);
            TNode sub = learnTree(c, refPlane(refOf, i), rects);
            refineLeaves(c, sub, refPlane(refOf, i), rects);
            if (ragged[i]) {
                dropMultipliers(sub);
            }
            subs.put(c, sub);
        }

        // stream indices, which the tree splits on to tell the sub-streams
        // apart; the ranges run in this order and never overlap
        int passBase = 1 + 3 * numLfGroups + NUM_DCT_PARAMS;
        List<Integer> bounds = new ArrayList<>();
        List<TNode> chains = new ArrayList<>();
        if (numGlobal > 0) {
            bounds.add(0);
            chains.add(chainOf(range(0, numGlobal), subs));
        }
        if (!lfChans.isEmpty()) {
            bounds.add(2 * numLfGroups);   // the last LF group's index
            chains.add(chainOf(lfChans, subs));
        }
        for (int p = 0; p < numPasses; p++) {
            if (!passChans.get(p).isEmpty()) {
                bounds.add(passBase + (p + 1) * numGroups - 1);
                chains.add(chainOf(passChans.get(p), subs));
            }
        }
        TNode tree = buildTree(bounds, chains);
        int numCtx = assignCtx(tree);

        // ---- tokenize every sub-stream
        TokenBuf globalBuf = new TokenBuf();
        for (int i = 0; i < numGlobal; i++) {
            Chan c = chans.get(i);
            tokenizeRect(c, subs.get(c), refPlane(refOf, i), 0, 0, c.w, c.h, globalBuf);
        }
        // LZ77 distance multipliers, mirroring the decoder's per-stream rule
        int globalDistMult = 0;
        for (int i = 0; i < chans.size(); i++) {
            Chan c = chans.get(i);
            if (i >= nbMeta && (c.w > GROUP_DIM || c.h > GROUP_DIM)) {
                break;
            }
            if (c.w > 0 && c.h > 0) {
                globalDistMult = Math.max(globalDistMult, c.w);
            }
        }
        globalDistMult = Math.min(globalDistMult, 1 << 21);

        // every sub-stream past the global one, in the order the TOC lists them:
        // the LF groups, then a block of groups per pass
        int numSub = single ? 0 : numLfGroups + numPasses * numGroups;
        TokenBuf[] bufs = new TokenBuf[numSub];
        int[] dist = new int[numSub];
        for (int s = 0; s < numSub; s++) {
            List<Integer> sc = s < numLfGroups
                    ? lfChans
                    : passChans.get((s - numLfGroups) / numGroups);
            int[] rect = s < numLfGroups
                    ? lfGrid[s]
                    : groupGrid[(s - numLfGroups) % numGroups];
            bufs[s] = tokenizeSubStream(sc, rect, subs, refOf);
            dist[s] = distMult(sc, rect);
        }

        // ---- LZ77 match search against literal-only statistics
        EntropyEncoder litProbe = new EntropyEncoder(numCtx, true, true);
        countLiterals(globalBuf, litProbe);
        for (TokenBuf b : bufs) {
            countLiterals(b, litProbe);
        }
        litProbe.prepareCosts();
        EntropyEncoder lit = Boolean.getBoolean("jxl.enc.lz77legacy") ? null : litProbe;
        findMatches(globalBuf, globalDistMult, lit);
        java.util.stream.IntStream.range(0, numSub).parallel()
                .forEach(s -> findMatches(bufs[s], dist[s], lit));

        // ---- per-group local trees where they beat the global code. Only the
        // classic layout, where a group is one sub-stream holding every
        // non-global channel: a progressive frame's channels are spread across
        // passes, so a group is no longer a thing a local tree can be learned on.
        boolean classic = numPasses == 1 && lfChans.isEmpty();
        byte[][] localBytes = new byte[numSub][];
        if (classic && numSub >= 2 && chans.size() > numGlobal
                && !Boolean.getBoolean("jxl.enc.simpletree")) {
            EntropyEncoder probe = new EntropyEncoder(numCtx, true, true);
            emitBuffer(globalBuf, null, probe, globalDistMult);
            for (int s = 0; s < numSub; s++) {
                emitBuffer(bufs[s], null, probe, dist[s]);
            }
            probe.prepareCosts();
            List<Chan> groupChans = List.copyOf(chans.subList(numGlobal, chans.size()));
            int numGlobalF = numGlobal;
            java.util.stream.IntStream.range(numLfGroups, numSub).parallel().forEach(s ->
                    localBytes[s] = tryLocalGroup(s - numLfGroups, groupColumns, numGlobalF,
                            groupChans, refOf, bufs[s], probe, dist[s]));
        }

        // ---- pass 1: histograms (local groups keep their own code)
        EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
        emitTree(tree, null, treeEnc);
        EntropyEncoder dataEnc = new EntropyEncoder(numCtx, true, true, true);
        emitBuffer(globalBuf, null, dataEnc, globalDistMult);
        for (int s = 0; s < numSub; s++) {
            if (localBytes[s] == null) {
                emitBuffer(bufs[s], null, dataEnc, dist[s]);
            }
        }

        // ---- LfGlobal section
        BitWriter lfGlobal = new BitWriter();
        // XYB DC dequant steps: the default set matches the decoder's, so distance
        // 1.0 is signalled with the all-default bit; a scaled set is written out.
        boolean defaultDequant = lfDequant == null
                || (lfDequant[0] == 1f / 4096f && lfDequant[1] == 1f / 512f
                        && lfDequant[2] == 1f / 256f);
        if (patchDict != null) {
            // patches head LfGlobal (before splines, noise and the dequant), and
            // the frame flag is set to match — see writeFrameHeader
            PatchDictWriter.write(lfGlobal, patchDict, extras.size());
        }
        lfGlobal.writeBool(defaultDequant); // LfChannelDequantization.all_default
        if (!defaultDequant) {
            for (int i = 0; i < 3; i++) {
                lfGlobal.writeF16(lfDequant[i] * 128f);
            }
        }
        lfGlobal.writeBool(true); // global tree present
        treeEnc.writeSpec(lfGlobal);
        emitTree(tree, lfGlobal, treeEnc);
        treeEnc.finishSection(lfGlobal);
        dataEnc.writeSpec(lfGlobal);
        writeGroupHeader(lfGlobal);
        emitBuffer(globalBuf, lfGlobal, dataEnc, globalDistMult);
        dataEnc.finishSection(lfGlobal);
        lfGlobal.zeroPadToByte();
        byte[] lfGlobalBytes = lfGlobal.toByteArray();

        // ---- the rest
        byte[][] subBytes = new byte[numSub][];
        for (int s = 0; s < numSub; s++) {
            if (localBytes[s] != null) {
                subBytes[s] = localBytes[s];
                continue;
            }
            List<Integer> sc = s < numLfGroups
                    ? lfChans
                    : passChans.get((s - numLfGroups) / numGroups);
            int[] rect = s < numLfGroups
                    ? lfGrid[s]
                    : groupGrid[(s - numLfGroups) % numGroups];
            if (!hasContent(sc, rect)) {
                // Nothing is coded here — either the bracket is empty, or every
                // channel of it is clipped to nothing by this group. A stream
                // with no channels carries no header either, so the section is
                // not a bare header: it is no bytes at all.
                subBytes[s] = new byte[0];
                continue;
            }
            BitWriter gw = new BitWriter();
            writeGlobalGroupHeader(gw);
            emitBuffer(bufs[s], gw, dataEnc, dist[s]);
            dataEnc.finishSection(gw);
            gw.zeroPadToByte();
            subBytes[s] = gw.toByteArray();
        }

        // ---- assemble the frame
        FrameParams fp = frameParams != null ? frameParams
                : FrameParams.single(extras.size(), numPasses, width, height);
        fp.numExtra = extras.size();
        fp.numPasses = numPasses;
        fp.xyb = xyb;
        fp.patches = patchDict != null;
        writeFrameHeader(out, fp);

        out.writeBool(false); // TOC not permuted
        out.zeroPadToByte();
        writeTocEntry(out, lfGlobalBytes.length);
        if (!single) {
            for (int gg = 0; gg < numLfGroups; gg++) {
                writeTocEntry(out, subBytes[gg].length);
            }
            writeTocEntry(out, 0); // empty HfGlobal section (this is a modular frame)
            for (int s = numLfGroups; s < numSub; s++) {
                writeTocEntry(out, subBytes[s].length);
            }
        }
        out.zeroPadToByte();
        out.writeBytes(lfGlobalBytes);
        for (byte[] b : subBytes) {
            out.writeBytes(b);
        }
        if (LZ_STATS) {
            System.err.printf("[lz] matches=%d copiedTokens=%d estSavedBits=%d%n",
                    statMatches.get(), statCopied.get(), statSaved.get());
        }
    }

    /**
     * Strips a subtree's residual multipliers. The offsets can stay: subtracting
     * one is exact whatever the residual, where dividing by a multiplier only
     * comes out whole for the channel whose residuals it was the common divisor of.
     */
    private static void dropMultipliers(TNode node) {
        if (node.prop >= 0) {
            dropMultipliers(node.left);
            dropMultipliers(node.right);
        } else {
            node.multiplier = 1;
        }
    }

    /** Channel indices {@code [from, to)}, as the tree's chains want them. */
    private static List<Integer> range(int from, int to) {
        List<Integer> list = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * Tokenizes one sub-stream: a set of channels, each sliced to one rectangle.
     *
     * <p>A channel with nothing of it inside the rectangle is left out entirely,
     * which renumbers the ones after it — the channel index is what the tree's
     * chain switches on, so the subtree a channel gets here is the one its
     * <em>position in the surviving list</em> selects, not the one learned from
     * it. Those are the same channel except in the rare group where something
     * ahead of it vanished, and there it costs a little compression and nothing
     * else: the decoder walks to the same subtree, because it renumbers the same
     * way.
     */
    private TokenBuf tokenizeSubStream(List<Integer> chanIdx, int[] rect,
            Map<Chan, TNode> subs, int[] refOf) {
        TokenBuf buf = new TokenBuf();
        int pos = 0;
        for (int i : chanIdx) {
            Chan c = chans.get(i);
            int[] s = slice(c, rect[0], rect[1], rect[2], rect[3]);
            if (s[2] <= 0 || s[3] <= 0) {
                continue;
            }
            TNode sub = subs.get(chans.get(chanIdx.get(pos)));
            tokenizeRect(c, sub, refPlane(refOf, i), s[0], s[1], s[2], s[3], buf);
            pos++;
        }
        return buf;
    }

    /** Whether any of these channels has anything inside the rectangle. */
    private boolean hasContent(List<Integer> chanIdx, int[] rect) {
        for (int i : chanIdx) {
            int[] s = slice(chans.get(i), rect[0], rect[1], rect[2], rect[3]);
            if (s[2] > 0 && s[3] > 0) {
                return true;
            }
        }
        return false;
    }

    /** The LZ77 distance multiplier of a sub-stream: its widest channel slice. */
    private int distMult(List<Integer> chanIdx, int[] rect) {
        int m = 0;
        for (int i : chanIdx) {
            int[] s = slice(chans.get(i), rect[0], rect[1], rect[2], rect[3]);
            if (s[2] > 0 && s[3] > 0) {
                m = Math.max(m, s[2]);
            }
        }
        return Math.min(m, 1 << 21);
    }

    /** GroupHeader of the global modular stream, including the transforms. */
    private void writeGroupHeader(BitWriter w) {
        w.writeBool(true);  // use_global_tree
        w.writeBool(true);  // default weighted predictor parameters
        int nbTransforms = (paletteData != null || rctType >= 0 ? 1 : 0) + (squeezed ? 1 : 0);
        writeTransformCount(w, nbTransforms);
        if (paletteData != null) {
            w.write(1, 2);  // transform id: palette
            w.write(0, 2);  // begin_c selector 0
            w.write(0, 3);  // begin_c = 0
            switch (paletteNumC) { // num_c: U32(1, 3, 4, 1 + u(13))
                case 1 -> w.write(0, 2);
                case 3 -> w.write(1, 2);
                case 4 -> w.write(2, 2);
                default -> {
                    w.write(3, 2);
                    w.write(paletteNumC - 1, 13);
                }
            }
            if (paletteSize < 256) {
                w.write(0, 2);
                w.write(paletteSize, 8);
            } else if (paletteSize < 1280) {
                w.write(1, 2);
                w.write(paletteSize - 256, 10);
            } else {
                w.write(2, 2);
                w.write(paletteSize - 1280, 12);
            }
            w.write(0, 2);  // nb_deltas selector 0 -> 0
            w.write(0, 4);  // d_pred = 0
        } else if (rctType >= 0) {
            writeRctTransform(w, rctType);
        }
        if (squeezed) {
            w.write(2, 2);  // transform id: squeeze
            w.write(0, 2);  // num_sq = 0: the decoder rebuilds the default plan
        }
    }

    /** nb_transforms: U32(0, 1, 2 + u(4), 18 + u(8)). */
    private static void writeTransformCount(BitWriter w, int n) {
        if (n < 2) {
            w.write(n, 2);
        } else if (n < 18) {
            w.write(2, 2);
            w.write(n - 2, 4);
        } else {
            w.write(3, 2);
            w.write(n - 18, 8);
        }
    }

    /** Writes one RCT transform declaration over channels 0..2. */
    static void writeRctTransform(BitWriter w, int rctType) {
        w.write(0, 2);  // transform id: RCT
        w.write(0, 2);  // begin_c selector 0
        w.write(0, 3);  // begin_c = 0
        if (rctType == 6) {
            w.write(0, 2);
        } else if (rctType < 4) {
            w.write(1, 2);
            w.write(rctType, 2);
        } else if (rctType < 18) {
            w.write(2, 2);
            w.write(rctType - 2, 4);
        } else {
            w.write(3, 2);
            w.write(rctType - 10, 6);
        }
    }

    static ImageMetadata buildMetadata(int bits, boolean grey, boolean alpha,
            boolean alphaAssociated) {
        BitDepth depth = BitDepth.of(bits);
        return buildMetadata(depth, grey, alphaOnly(depth, alpha, alphaAssociated));
    }

    static ImageMetadata buildMetadata(BitDepth depth, boolean grey, boolean alpha,
            boolean alphaAssociated) {
        return buildMetadata(depth, grey, alphaOnly(depth, alpha, alphaAssociated));
    }

    static ImageMetadata buildMetadata(BitDepth depth, boolean grey,
            List<ExtraChannelInfo> extras) {
        return buildMetadata(depth, grey, extras, false);
    }

    static ImageMetadata buildMetadata(BitDepth depth, boolean grey,
            List<ExtraChannelInfo> extras, boolean xyb) {
        ImageMetadata meta = new ImageMetadata();
        meta.bitDepth = depth;
        // The buffer-width hint has to cover every channel, not just colour: an
        // 8-bit image carrying a 16-bit depth map still needs the wide buffers.
        boolean narrow = !depth.floatingPoint && depth.bitsPerSample <= 12;
        for (ExtraChannelInfo ec : extras) {
            narrow &= !ec.bitDepth.floatingPoint && ec.bitDepth.bitsPerSample <= 12;
        }
        meta.modular16BitBuffers = narrow;
        meta.xybEncoded = xyb;
        meta.extraChannels.addAll(extras);
        ColourEncoding colour = new ColourEncoding();
        if (grey) {
            colour.allDefault = false;
            colour.colourSpace = ColourEncoding.CS_GREY;
        }
        meta.colourEncoding = colour;
        return meta;
    }

    static final int BLEND_REPLACE = 0;
    static final int BLEND_BLEND = 2;

    /**
     * Everything a frame header carries beyond the pixels: how many extra
     * channels, how many passes, and — for a frame that is one of several — where
     * it sits on the canvas, how it blends, how long it shows, and whether it is
     * kept as a reference for the frames after it. A lone still needs none of
     * this, so {@link #single} fills in the defaults: a full-canvas replace frame
     * that is the last (and only) one.
     */
    static final class FrameParams {
        int numExtra;
        int numPasses = 1;
        int canvasWidth;
        int canvasHeight;
        int frameWidth;      // this frame's coded size (the crop, for a partial frame)
        int frameHeight;
        int x0;              // top-left on the canvas
        int y0;
        boolean haveCrop;    // false for a full-canvas frame
        boolean haveAnimation;
        long duration;       // ticks this frame shows for
        boolean haveTimecodes; // frame headers carry a timecode
        long timecode;       // SMPTE-packed 32-bit timecode
        int blendMode = BLEND_REPLACE;
        int blendSource;     // reference slot a non-replace frame blends over
        int blendAlphaChannel;
        int saveAsReference; // slot to keep this frame in (0 = keep none)
        boolean isLast = true;
        boolean xyb;         // XYB frame: the do_YCbCr bit is absent
        boolean patches;     // frame stamps reference patches (FLAG_PATCHES)
        int frameType;       // 0 regular, 2 reference-only (a pure patch source)
        boolean saveBeforeCT; // save this frame before the colour transform

        static FrameParams single(int numExtra, int numPasses, int w, int h) {
            FrameParams p = new FrameParams();
            p.numExtra = numExtra;
            p.numPasses = numPasses;
            p.canvasWidth = w;
            p.canvasHeight = h;
            p.frameWidth = w;
            p.frameHeight = h;
            return p;
        }
    }

    static void writeFrameHeader(BitWriter out, boolean alpha) {
        writeFrameHeader(out, alpha ? 1 : 0, 1);
    }

    static void writeFrameHeader(BitWriter out, int numExtra, int numPasses) {
        writeFrameHeader(out, FrameParams.single(numExtra, numPasses, 0, 0));
    }

    /**
     * Writes a frame header, mirroring {@link com.ebremer.cygnus.jpegxl.codestream.FrameHeader#read}
     * field for field. Modular, no colour transform, no restoration filter — the
     * lossless frame this encoder writes — but general over the animation and
     * compositing fields, so the same routine serves a lone still, a progressive
     * frame's three passes, and one frame of many.
     *
     * <p>{@code num_passes} of 3 marks a progressive frame: the passes are told
     * apart by the downsampling each is enough for — 4x, then 2x, then full — and
     * the decoder derives a shift range per pass from these markers and takes the
     * channels whose shift falls in it.
     */
    static void writeFrameHeader(BitWriter out, FrameParams p) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(p.frameType, 2);   // frame_type: 0 regular, 2 reference-only
        out.writeBool(true);         // encoding: modular
        out.writeU64(p.patches ? com.ebremer.cygnus.jpegxl.codestream.FrameHeader.FLAG_PATCHES : 0);
        if (!p.xyb) {
            out.writeBool(false);    // do_YCbCr (absent when xyb_encoded)
        }
        out.write(0, 2);             // log upsampling
        for (int i = 0; i < p.numExtra; i++) {
            // 0 here, so the channel's own dim_shift is the whole of its
            // upsampling: the decoder multiplies the two
            out.write(0, 2);         // extra channel upsampling
        }
        out.write(GROUP_SIZE_SHIFT_BITS, 2); // group_size_shift = 8
        if (p.frameType == 2) {
            // a reference-only frame codes no passes info
        } else if (p.numPasses == 1) {
            out.write(0, 2);         // num_passes = 1 (U32 selector 0)
        } else if (p.numPasses == PROGRESSIVE_PASSES) {
            out.write(2, 2);         // num_passes = 3
            out.write(2, 2);         // num_ds = 2
            out.write(0, 2);         // pass 0 coefficient shift (VarDCT only)
            out.write(0, 2);         // pass 1 coefficient shift
            out.write(2, 2);         // downsample[0] = 1 << 2 = 4  -> pass 0 covers shift 2
            out.write(1, 2);         // downsample[1] = 1 << 1 = 2  -> pass 1 covers shift 1
            out.write(0, 2);         // last_pass[0] = 0
            out.write(1, 2);         // last_pass[1] = 1
        } else {
            throw new IllegalArgumentException("unsupported pass count " + p.numPasses);
        }
        writeFrameCompositing(out, p);
        out.write(0, 2);             // name length (U32 selector 0)
        out.writeBool(false);        // RestorationFilter not all_default
        out.writeBool(false);        // gaborish off
        out.write(0, 2);             // epf iterations = 0
        out.writeU64(0);             // restoration filter extensions
        out.writeU64(0);             // frame header extensions
    }

    /**
     * The frame-header middle every encoding writes identically — crop,
     * per-channel blending, animation, {@code is_last} and the reference-frame
     * bookkeeping. The encoding-specific head (modular vs VarDCT: flags,
     * passes, group shift or quant scales) and tail (the restoration filter)
     * stay with the caller. One copy, shared with the VarDCT header, because
     * the two drifting apart is exactly the desync class that has bitten this
     * encoder before.
     */
    static void writeFrameCompositing(BitWriter out, FrameParams p) {
        boolean fullFrame;
        if (p.haveCrop) {
            out.writeBool(true);     // have_crop
            out.writeU32Auto(packSigned(p.x0), 0, 8, 256, 11, 2304, 14, 18688, 30);
            out.writeU32Auto(packSigned(p.y0), 0, 8, 256, 11, 2304, 14, 18688, 30);
            out.writeU32Auto(p.frameWidth, 0, 8, 256, 11, 2304, 14, 18688, 30);
            out.writeU32Auto(p.frameHeight, 0, 8, 256, 11, 2304, 14, 18688, 30);
            fullFrame = p.x0 <= 0 && p.y0 <= 0
                    && p.frameWidth + p.x0 >= p.canvasWidth
                    && p.frameHeight + p.y0 >= p.canvasHeight;
        } else {
            out.writeBool(false);    // have_crop
            fullFrame = true;
        }
        // blend info, animation and is_last belong only to regular (and
        // skip-progressive) frames; a reference-only frame carries none of them
        // and is implicitly not the last
        if (p.frameType == 0 || p.frameType == 3) {
            // per-channel blend info: colour (index -1), then each extra channel
            for (int i = -1; i < p.numExtra; i++) {
                out.writeU32Auto(p.blendMode, 0, 0, 1, 0, 2, 0, 3, 2);
                if (p.numExtra > 0 && p.blendMode == BLEND_BLEND) {
                    out.writeU32Auto(p.blendAlphaChannel, 0, 0, 1, 0, 2, 0, 3, 3);
                    out.write(0, 1);     // clamp = false
                }
                if (!fullFrame || p.blendMode != BLEND_REPLACE) {
                    out.write(p.blendSource, 2);
                }
            }
            if (p.haveAnimation) {
                writeDuration(out, p.duration);
                if (p.haveTimecodes) {
                    out.write((int) p.timecode, 32);
                }
            }
            out.writeBool(p.isLast);
        }
        if (!p.isLast) {
            out.write(p.saveAsReference, 2);
        }
        // save_before_colour_transform is only coded for the frames that could
        // be kept; neither encoder keeps a before-transform copy except where
        // FrameParams says so, matching what the decoder derives for the rest
        boolean codesSaveBefore = p.frameType == 2 || (fullFrame && p.blendMode == BLEND_REPLACE
                && (p.duration == 0 || p.saveAsReference != 0) && !p.isLast);
        if (codesSaveBefore) {
            out.writeBool(p.saveBeforeCT);
        }
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

    static void writeTocEntry(BitWriter out, int size) {
        if (size < 0 || size >= 4211712 + (1 << 30)) {
            // the widest branch holds 30 bits; masking would corrupt the TOC
            throw new IllegalArgumentException("no TOC entry can hold " + size + " bytes");
        }
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

    // ---------------------------------------------------------------- tokens

    /** A node of the encoder's MA tree. */
    static final class TNode {
        int prop = -1;   // -1 for leaves
        int split;
        TNode left;
        TNode right;
        Chan chan;       // leaf channel
        int predictor;   // leaf predictor
        int ctx;         // leaf context id, assigned in BFS order
        int offset;      // leaf residual offset
        int multiplier = 1; // leaf residual multiplier
        LeafStat stat;   // scratch, held only across a leaf-refinement pass
    }

    static TNode leafNode(Chan c) {
        TNode n = new TNode();
        n.chan = c;
        n.predictor = c.predictor;
        return n;
    }

    /** A chain of property-0 splits hanging each channel's learned subtree. */
    static TNode chainNode(List<Chan> list, int k, Map<Chan, TNode> subs) {
        if (k == 0) {
            return subs.get(list.get(0));
        }
        TNode n = new TNode();
        n.prop = 0;
        n.split = k - 1;
        n.left = subs.get(list.get(k));
        n.right = chainNode(list, k - 1, subs);
        return n;
    }

    /**
     * Builds the MA tree.
     *
     * <p>Property 0 is a channel's index <em>within its sub-stream</em>, and it
     * restarts at zero in each: channel 0 of the global stream, of an LF group
     * and of every pass are all different channels. So the tree first sorts the
     * sub-streams apart on property 1, the stream index, whose ranges run in
     * order — the global stream is 0, then the LF groups, then a block per pass
     * — and only then chains through the channels of the one it landed in.
     *
     * @param bounds the largest stream index in each sub-stream kind's range,
     *               in increasing order; {@code chains} holds the matching chain
     */
    private static TNode buildTree(List<Integer> bounds, List<TNode> chains) {
        TNode node = chains.get(chains.size() - 1);
        for (int i = chains.size() - 2; i >= 0; i--) {
            TNode n = new TNode();
            n.prop = 1;
            n.split = bounds.get(i);
            n.left = node;              // a later sub-stream: index above the bound
            n.right = chains.get(i);
            node = n;
        }
        return node;
    }

    /** The chain of per-channel subtrees for one sub-stream, keyed by property 0. */
    private TNode chainOf(List<Integer> chanIdx, Map<Chan, TNode> subs) {
        List<Chan> list = new ArrayList<>(chanIdx.size());
        for (int i : chanIdx) {
            list.add(chans.get(i));
        }
        return chainNode(list, list.size() - 1, subs);
    }

    /**
     * A whole modular sub-stream over a set of channels: the learned tree, its
     * context count, the tokens, and the LZ77 distance multiplier the decoder
     * will use. This is what an extra-channel stream is — the colour encoder has
     * one of these bolted to the side of every VarDCT frame that carries alpha,
     * and it had room for exactly one channel.
     *
     * <p>Property 0 is the channel's index within the sub-stream, so the tree is
     * a chain of subtrees switched on it, exactly as the global stream builds one.
     */
    static final class SubStream {
        TNode tree;
        int numCtx;
        TokenBuf buf;
        int distMult;
    }

    /** Learns and tokenizes one sub-stream over {@code channels}, in that order. */
    static SubStream buildSubStream(List<Chan> channels) {
        Map<Chan, TNode> subs = new java.util.IdentityHashMap<>();
        for (Chan c : channels) {
            choosePredictor(c);
            List<int[]> rect = List.of(new int[] {0, 0, c.w, c.h});
            TNode sub = learnTree(c, null, rect, 1 << 14, 4);
            refineLeaves(c, sub, null, rect);
            subs.put(c, sub);
        }
        SubStream s = new SubStream();
        s.tree = chainNode(channels, channels.size() - 1, subs);
        s.numCtx = assignCtx(s.tree);
        s.buf = new TokenBuf();
        int widest = 0;
        for (Chan c : channels) {
            tokenizeRect(c, subs.get(c), null, 0, 0, c.w, c.h, s.buf);
            widest = Math.max(widest, c.w);
        }
        s.distMult = Math.min(widest, 1 << 21);
        return s;
    }

    /**
     * Writes one fully self-contained modular section — its own learned tree
     * and entropy code behind the standalone GroupHeader. Every stand-alone
     * section any encoder emits (a local pass group, a streamed group, the
     * VarDCT extras) comes through here, so the header and the paired
     * spec-then-emit passes cannot drift apart per path — the divergence that
     * once let a local section disagree with the decoder.
     *
     * @param rct an RCT type to declare as the section's one transform, or -1
     */
    static void writeStandaloneSection(BitWriter gw, TNode tree, int numCtx, TokenBuf buf,
            int distMult, int rct) {
        gw.writeBool(false); // use_global_tree = false: the section is standalone
        gw.writeBool(true);  // default weighted-predictor parameters
        if (rct >= 0) {
            gw.write(1, 2);  // nb_transforms = 1
            writeRctTransform(gw, rct);
        } else {
            gw.write(0, 2);  // nb_transforms = 0
        }
        EntropyEncoder treeEnc = new EntropyEncoder(6, false, false, true);
        emitTree(tree, null, treeEnc);
        treeEnc.writeSpec(gw);
        emitTree(tree, gw, treeEnc);
        treeEnc.finishSection(gw);
        EntropyEncoder lit = new EntropyEncoder(numCtx, true, true);
        countLiterals(buf, lit);
        lit.prepareCosts();
        findMatches(buf, distMult, Boolean.getBoolean("jxl.enc.lz77legacy") ? null : lit);
        EntropyEncoder dataEnc = new EntropyEncoder(numCtx, true, true, true);
        emitBuffer(buf, null, dataEnc, distMult);
        dataEnc.writeSpec(gw);
        emitBuffer(buf, gw, dataEnc, distMult);
        dataEnc.finishSection(gw);
    }

    /** The GroupHeader of a section coded against the global tree and code. */
    static void writeGlobalGroupHeader(BitWriter gw) {
        gw.writeBool(true); // use_global_tree
        gw.writeBool(true); // default weighted-predictor parameters
        gw.write(0, 2);     // nb_transforms = 0
    }

    /** Assigns leaf contexts in the decoder's BFS order; returns the leaf count. */
    static int assignCtx(TNode root) {
        java.util.ArrayDeque<TNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int ctx = 0;
        while (!queue.isEmpty()) {
            TNode n = queue.poll();
            if (n.prop >= 0) {
                queue.add(n.left);
                queue.add(n.right);
            } else {
                n.ctx = ctx++;
            }
        }
        return ctx;
    }

    /**
     * Emits the tree in the decoder's BFS order. With {@code out == null}
     * histograms are updated instead of writing bits.
     */
    static void emitTree(TNode root, BitWriter out, EntropyEncoder enc) {
        java.util.ArrayDeque<TNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            TNode n = queue.poll();
            if (n.prop >= 0) {
                emitToken(out, enc, 1, n.prop + 1);
                emitToken(out, enc, 0, packSigned(n.split));
                queue.add(n.left);
                queue.add(n.right);
            } else {
                emitToken(out, enc, 1, 0); // leaf marker
                emitToken(out, enc, 2, n.predictor);
                emitToken(out, enc, 3, packSigned(n.offset));
                int shift = Integer.numberOfTrailingZeros(n.multiplier);
                emitToken(out, enc, 4, shift);
                emitToken(out, enc, 5, (n.multiplier >>> shift) - 1);
            }
        }
    }

    private static void emitToken(BitWriter out, EntropyEncoder enc, int ctx, int value) {
        if (out == null) {
            enc.count(ctx, value);
        } else {
            enc.write(out, ctx, value);
        }
    }

    /** Counts every token of a buffer as a literal (LZ77 cost probing). */
    static void countLiterals(TokenBuf buf, EntropyEncoder enc) {
        for (int i = 0; i < buf.n; i++) {
            enc.count(buf.ctx[i], buf.val[i]);
        }
    }

    /** Emits a buffered section, replaying its LZ77 segmentation. */
    static void emitBuffer(TokenBuf buf, BitWriter out, EntropyEncoder enc, int distMult) {
        findMatches(buf, distMult, null);
        int m = 0;
        int i = 0;
        while (i < buf.n) {
            if (m < buf.nMatches && buf.mPos[m] == i) {
                if (out == null) {
                    enc.countCopy(buf.ctx[i], buf.mLen[m], buf.mVal[m]);
                } else {
                    enc.writeCopy(out, buf.ctx[i], buf.mLen[m], buf.mVal[m]);
                }
                i += buf.mLen[m];
                m++;
                continue;
            }
            if (out == null) {
                enc.count(buf.ctx[i], buf.val[i]);
            } else {
                enc.write(out, buf.ctx[i], buf.val[i]);
            }
            i++;
        }
    }

    static final boolean LZ_STATS = Boolean.getBoolean("jxl.enc.lzstats");
    static final java.util.concurrent.atomic.AtomicLong statMatches = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong statCopied = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong statSaved = new java.util.concurrent.atomic.AtomicLong();

    private static final int LZ_WINDOW = 1 << 20;
    private static final int LZ_HASH_BITS = 16;
    private static final int LZ_MAX_CHAIN = 48;

    private static int lzHash(int[] v, int i) {
        int h = v[i] * 0x9E3779B1 + v[i + 1] * 0x85EBCA77 + v[i + 2] * 0xC2B2AE3D;
        return h >>> (32 - LZ_HASH_BITS);
    }

    private static int bitLen(int packed) {
        return 32 - Integer.numberOfLeadingZeros(packed + 1);
    }

    /**
     * Greedy hash-chain LZ77 matcher over a section's token values. Matches
     * may overlap their own output (the decoder copies value by value) and
     * cross channel boundaries. A copy is kept only when its cost under
     * {@code costs} (a literal-only histogram probe, so never-seen copy
     * symbols price in their own rarity) undercuts the real entropy cost of
     * the literals it replaces. The segmentation is cached so the counting
     * and writing passes replay identical decisions; with {@code costs} null
     * only conservative same-value runs are emitted.
     */
    static void findMatches(TokenBuf buf, int distMult, EntropyEncoder costs) {
        if (buf.matched) {
            return;
        }
        buf.matched = true;
        int n = buf.n;
        if (n < 8) {
            return;
        }
        int[] val = buf.val;
        if (costs == null) {
            // legacy conservative mode: long same-value runs only
            int i = 1;
            while (i < n) {
                int prev = val[i - 1];
                int r = 0;
                while (i + r < n && val[i + r] == prev) {
                    r++;
                }
                if (r >= 12) {
                    buf.addMatch(i, r, 1);
                    i += r;
                } else {
                    i += Math.max(1, r);
                }
            }
            return;
        }
        int[] head = new int[1 << LZ_HASH_BITS];
        java.util.Arrays.fill(head, -1);
        int[] prev = new int[n];
        var special = EntropyEncoder.specialDistanceValues(distMult);
        int i = 0;
        while (i + 3 <= n) {
            int maxLen = n - i;
            // seed with the same-value run: distance 1 is by far the cheapest
            int bestLen = 0;
            int bestDist = 0;
            if (i > 0 && val[i] == val[i - 1]) {
                int l = 1;
                while (l < maxLen && val[i + l] == val[i - 1]) {
                    l++;
                }
                bestLen = l;
                bestDist = 1;
            }
            // a qualifying run is essentially free to code: skip the search
            int cand = bestDist == 1 && bestLen >= 12 ? -1 : head[lzHash(val, i)];
            int chain = LZ_MAX_CHAIN;
            while (cand >= 0 && chain-- > 0 && i - cand <= LZ_WINDOW) {
                // a distance-1 best costs almost nothing: beat it clearly
                int need = bestLen + (bestDist == 1 ? 3 : 0);
                if (need >= maxLen) {
                    break;
                }
                if (val[cand + need] != val[i + need]) {
                    cand = prev[cand];
                    continue;
                }
                int l = 0;
                while (l < maxLen && val[cand + l] == val[i + l]) {
                    l++;
                }
                if (l > need) {
                    bestLen = l;
                    bestDist = i - cand;
                    if (l == maxLen) {
                        break;
                    }
                }
                cand = prev[cand];
            }
            if (bestLen >= EntropyEncoder.MIN_LENGTH) {
                Integer sv = special.get(bestDist);
                int dv = sv != null ? sv : bestDist + 119;
                // the literal-only probe has an empty distance context, which
                // would underprice the distance token: floor the copy cost
                double copyBits = Math.max(costs.copyCostBits(buf.ctx[i], bestLen, dv), 28);
                double bar = copyBits + 96;
                double litBits = 0;
                for (int k = 0; k < bestLen && litBits <= bar; k++) {
                    litBits += costs.tokenCostBits(buf.ctx[i + k], val[i + k]);
                }
                boolean accept = (bestDist == 1 && bestLen >= 12)
                        || (bestLen >= 48 && litBits > bar);
                // lazy guard: don't let a far match swallow an imminent
                // same-value run, which codes for a fraction of the cost
                if (accept && bestDist != 1 && i + 1 < n && val[i + 1] == val[i]) {
                    int r = 1;
                    int cap = Math.min(maxLen - 1, bestLen);
                    while (r < cap && val[i + 1 + r] == val[i]) {
                        r++;
                    }
                    if (r * 2 >= bestLen) {
                        accept = false;
                    }
                }
                if (accept) {
                    if (LZ_STATS) {
                        statMatches.incrementAndGet();
                        statCopied.addAndGet(bestLen);
                        statSaved.addAndGet((long) (litBits - copyBits));
                    }
                    buf.addMatch(i, bestLen, dv);
                    int matchEnd = i + bestLen;
                    int hashEnd = Math.min(matchEnd, n - 2);
                    while (i < hashEnd) {
                        int h = lzHash(val, i);
                        prev[i] = head[h];
                        head[h] = i;
                        i++;
                    }
                    i = matchEnd;
                    continue;
                }
            }
            int h = lzHash(val, i);
            prev[i] = head[h];
            head[h] = i;
            i++;
        }
    }

    /**
     * Walks one channel rectangle in scan order, running the learned subtree per
     * pixel and buffering packed residuals under the leaf context. The rectangle
     * is its own little image: neighbours never cross it.
     *
     * <p>The tree is walked <em>before</em> the prediction is made, because the
     * leaf is what chooses the predictor — which is how the decoder has always
     * read a tree, and for a long time was not how this wrote one.
     */
    static void tokenizeRect(Chan ch, TNode sub, int[] ref, int x0, int y0, int w, int h,
            TokenBuf buf) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] px = ch.px;
        int stride = ch.w;
        WpState wp = usesWp(sub) ? new WpState(WpState.WpParams.DEFAULT, w) : null;
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * stride + x0;
            int rowN = row - stride;
            for (int x = 0; x < w; x++) {
                int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                int vN = y > 0 ? px[rowN + x] : vW;
                int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                int vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                int vNN = y > 1 ? px[row - 2 * stride + x] : vN;
                int vNEE = (x + 2 < w && y > 0) ? px[rowN + x + 2] : vNE;
                int vWW = x > 1 ? px[row + x - 2] : vW;
                if (wp != null) {
                    wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                }
                TNode n = sub;
                while (n.prop >= 0) {
                    int val = propValue(n.prop, px, ref, row, rowN, x, y,
                            vW, vN, vNW, vNE, vNN, wp);
                    n = val > n.split ? n.left : n.right;
                }
                long pred = predict(n.predictor, wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
                int residual = (int) (px[row + x] - pred) - n.offset;
                if (n.multiplier != 1) {
                    residual /= n.multiplier;
                }
                buf.add(n.ctx, packSigned(residual));
                if (wp != null) {
                    wp.afterPredict(x, y, px[row + x]);
                }
            }
        }
    }

    /** The decoder's predictor, so that what we subtract is what it will add. */
    private static long predict(int p, WpState wp, int vW, int vN, int vNW, int vNE, int vNN,
            int vNEE, int vWW) {
        try {
            return ModularStream.predict(p, wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
        } catch (IOException e) {
            throw new IllegalStateException("no such predictor: " + p, e);
        }
    }

    /**
     * Whether a subtree makes the decoder run the weighted predictor: some leaf
     * predicts with it, or some node splits on its error. The encoder has to run
     * it in exactly the same cases, since its state depends on being fed every
     * pixel from the start of the rectangle.
     */
    private static boolean usesWp(TNode n) {
        if (n.prop >= 0) {
            return n.prop == 15 || usesWp(n.left) || usesWp(n.right);
        }
        return n.predictor == 6;
    }

    /**
     * Decoder-exact MA property evaluation, local to the current rectangle.
     * Properties 0 and 1 never reach this point (they are resolved by the
     * chain structure), and all values wrap to 32 bits like the decoder's.
     */
    private static int propValue(int prop, int[] px, int[] ref, int row, int rowN,
            int x, int y, int vW, int vN, int vNW, int vNE, int vNN, WpState wp) {
        return switch (prop) {
            case 2 -> y;
            case 3 -> x;
            case 4 -> Math.abs(vN);
            case 5 -> Math.abs(vW);
            case 6 -> vN;
            case 7 -> vW;
            case 8 -> {
                if (x > 0) {
                    int pw = x > 1 ? px[row + x - 2] : (y > 0 ? px[rowN + x - 1] : 0);
                    int pn = y > 0 ? px[rowN + x - 1] : pw;
                    int pnw = (x > 1 && y > 0) ? px[rowN + x - 2] : pw;
                    yield vW - (pw + pn - pnw);
                }
                yield vW;
            }
            case 9 -> vW + vN - vNW;
            case 10 -> vW - vNW;
            case 11 -> vNW - vN;
            case 12 -> vN - vNE;
            case 13 -> vN - vNN;
            case 14 -> vW - (x > 1 ? px[row + x - 2] : vW);
            case 15 -> wp != null ? wp.maxError() : 0;
            case 16, 17, 18, 19 -> {
                if (ref == null) {
                    yield 0;
                }
                int rv = ref[row + x];
                if (prop >= 18) {
                    int rw = x > 0 ? ref[row + x - 1] : 0;
                    int rn = y > 0 ? ref[rowN + x] : rw;
                    int rnw = (x > 0 && y > 0) ? ref[rowN + x - 1] : rw;
                    long diff = (long) rv - ModularStream.clampedGradient(rw, rn, rnw);
                    yield prop == 18 ? (int) Math.abs(diff) : (int) diff;
                }
                yield prop == 16 ? (int) Math.abs((long) rv) : rv;
            }
            default -> 0;
        };
    }

    // ------------------------------------------------- leaf offset/multiplier

    /** Residual statistics of one leaf, gathered over every pixel it codes. */
    private static final class LeafStat {
        long count;
        int first;
        long gcd;              // gcd of (residual - first)
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        final int[] sample = new int[512];
        int nSample;

        void add(int r) {
            if (count == 0) {
                first = r;
            } else {
                gcd = gcd(gcd, Math.abs((long) r - first));
            }
            min = Math.min(min, r);
            max = Math.max(max, r);
            if (nSample < sample.length) {
                sample[nSample++] = r;
            } else if ((count & 127) == 0) {
                sample[(int) ((count >>> 7) % sample.length)] = r;
            }
            count++;
        }

        private static long gcd(long a, long b) {
            while (b != 0) {
                long t = a % b;
                a = b;
                b = t;
            }
            return a;
        }
    }

    /**
     * Chooses per-leaf residual offsets and multipliers for one channel's
     * subtree: a full pixel walk (identical to {@link #tokenizeRect})
     * accumulates each leaf's residual gcd and a sampled distribution; leaves
     * whose residuals share a factor divide it out, and biased distributions
     * are re-centred. Must run before tokenization.
     */
    static void refineLeaves(Chan ch, TNode sub, int[] ref, List<int[]> rects) {
        List<TNode> touched = new ArrayList<>();
        int[] px = ch.px;
        int stride = ch.w;
        boolean wpNeeded = usesWp(sub);
        for (int[] r : rects) {
            int x0 = r[0];
            int y0 = r[1];
            int w = r[2];
            int h = r[3];
            WpState wp = wpNeeded ? new WpState(WpState.WpParams.DEFAULT, w) : null;
            for (int y = 0; y < h; y++) {
                int row = (y0 + y) * stride + x0;
                int rowN = row - stride;
                for (int x = 0; x < w; x++) {
                    int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                    int vN = y > 0 ? px[rowN + x] : vW;
                    int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                    int vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                    int vNN = y > 1 ? px[row - 2 * stride + x] : vN;
                    int vNEE = (x + 2 < w && y > 0) ? px[rowN + x + 2] : vNE;
                    int vWW = x > 1 ? px[row + x - 2] : vW;
                    if (wp != null) {
                        wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                    }
                    TNode n = sub;
                    while (n.prop >= 0) {
                        int val = propValue(n.prop, px, ref, row, rowN, x, y,
                                vW, vN, vNW, vNE, vNN, wp);
                        n = val > n.split ? n.left : n.right;
                    }
                    long pred = predict(n.predictor, wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
                    if (n.stat == null) {
                        n.stat = new LeafStat();
                        touched.add(n);
                    }
                    n.stat.add((int) (px[row + x] - pred));
                    if (wp != null) {
                        wp.afterPredict(x, y, px[row + x]);
                    }
                }
            }
        }
        for (TNode leaf : touched) {
            chooseLeafParams(leaf, leaf.stat);
            leaf.stat = null;
        }
    }

    private static void chooseLeafParams(TNode leaf, LeafStat s) {
        if (s.count < 64) {
            return;
        }
        // int arithmetic must stay exact: skip extreme residual ranges where
        // 32-bit wrap could break the divisibility guarantee
        if (s.min < -(1 << 30) || s.max > (1 << 30)) {
            return;
        }
        int mult = 1;
        int off = 0;
        long g = s.gcd;
        if (g > 1 && g <= (1 << 30)) {
            mult = (int) g;
            off = (int) (((long) s.first % mult + mult) % mult);
            if (off * 2 > mult) {
                off -= mult;
            }
        }
        // re-centre by whole multiplier steps against the sampled quotients
        int n = s.nSample;
        long[] q = new long[n];
        for (int i = 0; i < n; i++) {
            q[i] = ((long) s.sample[i] - off) / mult;
        }
        java.util.Arrays.sort(q);
        long med = q[n / 2];
        if (med >= Integer.MIN_VALUE && med <= Integer.MAX_VALUE) {
            long best = sampleCost(q, 0);
            long bestK = 0;
            for (long k = med - 1; k <= med + 1; k++) {
                if (k == 0) {
                    continue;
                }
                long c = sampleCost(q, k) + 8; // emission overhead margin
                if (c < best) {
                    best = c;
                    bestK = k;
                }
            }
            long shifted = off + bestK * mult;
            if (shifted >= -(1 << 30) && shifted <= (1 << 30)) {
                off = (int) shifted;
            }
        }
        if (mult == 1 && off == 0) {
            return;
        }
        leaf.offset = off;
        leaf.multiplier = mult;
    }

    private static long sampleCost(long[] q, long k) {
        long bits = 0;
        for (long v : q) {
            long d = v - k;
            long packed = d >= 0 ? 2 * d : -2 * d - 1;
            bits += 64 - Long.numberOfLeadingZeros(packed + 1);
        }
        return bits;
    }

    // ---------------------------------------------------------- tree learning

    private static final int MAX_SAMPLES = 1 << 15;
    private static final int MIN_LEARN_PIXELS = 4096;
    private static final int MAX_DEPTH = 8;
    private static final int MIN_LEAF_SAMPLES = 64;
    private static final double SPLIT_COST_BITS = 650;
    private static final int TOKEN_ALPHABET = 72;
    private static final HybridUintConfig TOKEN_CFG = new HybridUintConfig(4, 1, 0);
    private static final double LOG2 = Math.log(2);

    /**
     * Raw bits a token carries besides itself — the middle of the hybrid integer,
     * written uncoded.
     *
     * <p>The learner must count them, and for a long time did not have to. While a
     * channel had one predictor, every pixel paid the same raw bits whichever side
     * of a split it fell, so the term cancelled and only token entropy decided
     * anything. It does not cancel between predictors. Predicting an eight-bit
     * photograph with zero leaves every residual near 128 — one magnitude class, a
     * beautifully concentrated token distribution, almost no entropy — and seven
     * raw bits a pixel. Score by entropy alone and that is the predictor you pick.
     */
    private static final double[] TOKEN_EXTRA_BITS = new double[TOKEN_ALPHABET];

    static {
        int split = 1 << TOKEN_CFG.splitExp;
        int inToken = TOKEN_CFG.msbInToken + TOKEN_CFG.lsbInToken;
        for (int t = 0; t < TOKEN_ALPHABET; t++) {
            TOKEN_EXTRA_BITS[t] = t < split ? 0
                    : TOKEN_CFG.splitExp - inToken + ((t - split) >> inToken);
        }
    }

    /**
     * Nearest earlier same-shaped channel in the same sub-stream, or -1.
     *
     * <p>The decoder resolves this itself, and not from these dimensions: it
     * looks at the channels as they appear <em>inside a group</em>, clipped to
     * that group's rectangle. Two channels that differ here by a column — an
     * odd-width squeeze leaves an average one wider than its residual — clip to
     * the same width in most groups, and the decoder would then pair them where
     * this does not. So a reference is only taken when nothing between it and
     * the channel shares the channel's shifts: any nearer candidate the decoder
     * could find must have those shifts, and there is none.
     */
    private int[] referenceChannels(int numGlobal) {
        int[] refOf = new int[chans.size()];
        java.util.Arrays.fill(refOf, -1);
        for (int i = 0; i < chans.size(); i++) {
            Chan c = chans.get(i);
            int lo = i < numGlobal ? 0 : numGlobal;
            for (int j = i - 1; j >= lo; j--) {
                Chan r = chans.get(j);
                if (r.hshift == c.hshift && r.vshift == c.vshift) {
                    // the first channel back that could be paired at all; if it
                    // is not the same shape, the decoder might still pair them
                    // once clipped, so take nothing
                    if (r.w == c.w && r.h == c.h) {
                        refOf[i] = j;
                    }
                    break;
                }
            }
        }
        return refOf;
    }

    private int[] refPlane(int[] refOf, int i) {
        return refOf[i] >= 0 ? chans.get(refOf[i]).px : null;
    }

    /**
     * The part of a channel that an image-space rectangle covers. A channel
     * squeezed to hshift 2 holds every fourth column, so a group starting at
     * image column 512 starts at its column 128 — exactly the arithmetic the
     * decoder does when it carves the same slice out.
     */
    static int[] slice(Chan c, int left, int top, int w, int h) {
        int x0 = left >> c.hshift;
        int y0 = top >> c.vshift;
        int cw = Math.min(ceilDiv(w, 1 << c.hshift), c.w - x0);
        int chh = Math.min(ceilDiv(h, 1 << c.vshift), c.h - y0);
        return new int[] {x0, y0, Math.max(cw, 0), Math.max(chh, 0)};
    }

    /** The image-space rectangle of one pass group. */
    private int[] groupRect(int g, int groupColumns) {
        int left = (g % groupColumns) * GROUP_DIM;
        int top = (g / groupColumns) * GROUP_DIM;
        return new int[] {left, top,
            Math.min(GROUP_DIM, width - left), Math.min(GROUP_DIM, height - top)};
    }

    /** The image-space rectangle of one LF group: eight groups on a side. */
    private int[] lfGroupRect(int gg, int lfColumns) {
        int lfDim = GROUP_DIM * 8;
        int left = (gg % lfColumns) * lfDim;
        int top = (gg / lfColumns) * lfDim;
        return new int[] {left, top,
            Math.min(lfDim, width - left), Math.min(lfDim, height - top)};
    }

    /** One channel's slices over a grid of rectangles, in scan order. */
    private List<int[]> slicesOver(Chan c, int[][] grid) {
        List<int[]> rects = new ArrayList<>();
        for (int[] r : grid) {
            int[] s = slice(c, r[0], r[1], r[2], r[3]);
            if (s[2] > 0 && s[3] > 0) {
                rects.add(s);
            }
        }
        return rects;
    }

    /**
     * Sampled pixels of one channel: the residual each predictor would leave,
     * plus every property value. Every predictor, because the leaf a pixel lands
     * in is what chooses one, and the learner cannot know which leaf that is
     * until it has decided how to split — so it has to keep all the answers.
     */
    private static final class Samples {
        int n;
        long totalPixels;
        byte[][] token;   // [predictor][sample]: hybrid-uint token of the residual
        int[] propIds;
        int[][] prop;     // [column][sample]
    }

    /**
     * The predictors a leaf may choose from, all of which the learner carries
     * through the split search. Narrowing the field first was tried — score them
     * over the channel as a whole, keep the best few — and it cost 0.45% of the
     * density to save 15% of the time, which is the wrong way round for an
     * encoder whose reason to exist is the density.
     */
    private static final int NUM_PREDICTORS = 14;

    /**
     * Properties the learner may split on. 0 and 1 are fixed by the chain
     * structure. 15 is the weighted predictor's own error, and offering it makes
     * the decoder run that predictor — but so does any leaf choosing it, and now
     * every leaf may, so it costs nothing extra to offer.
     */
    private static int[] candidateProps(int[] ref) {
        int n = 14 + (ref != null ? 4 : 0);
        int[] ids = new int[n];
        int k = 0;
        for (int p = 2; p <= 15; p++) {
            ids[k++] = p;
        }
        if (ref != null) {
            ids[k++] = 16;
            ids[k++] = 17;
            ids[k++] = 18;
            ids[k++] = 19;
        }
        return ids;
    }

    /**
     * Walks the rectangles exactly like {@link #tokenizeRect} and records an
     * evenly strided subset of pixels: what every predictor would have left
     * behind, and every candidate property value.
     *
     * <p>The weighted predictor runs over every pixel, sampled or not — its state
     * is a running thing and cannot be picked up halfway.
     */
    private static Samples collectSamples(Chan c, int[] ref, List<int[]> rects, int maxSamples) {
        long total = 0;
        for (int[] r : rects) {
            total += (long) r[2] * r[3];
        }
        int step = (int) Math.max(1, (total + maxSamples - 1) / maxSamples);
        int capacity = (int) (total / step) + rects.size() + 1;
        Samples s = new Samples();
        s.totalPixels = total;
        s.propIds = candidateProps(ref);
        s.token = new byte[NUM_PREDICTORS][capacity];
        s.prop = new int[s.propIds.length][capacity];
        int[] px = c.px;
        int stride = c.w;
        int[] tmp = new int[1];
        int phase = 0;
        for (int[] r : rects) {
            int x0 = r[0];
            int y0 = r[1];
            int w = r[2];
            int h = r[3];
            WpState wp = new WpState(WpState.WpParams.DEFAULT, w);
            for (int y = 0; y < h; y++) {
                int row = (y0 + y) * stride + x0;
                int rowN = row - stride;
                for (int x = 0; x < w; x++) {
                    int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                    int vN = y > 0 ? px[rowN + x] : vW;
                    int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                    int vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                    int vNN = y > 1 ? px[row - 2 * stride + x] : vN;
                    int vNEE = (x + 2 < w && y > 0) ? px[rowN + x + 2] : vNE;
                    int vWW = x > 1 ? px[row + x - 2] : vW;
                    wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                    if (phase++ % step == 0) {
                        for (int p = 0; p < NUM_PREDICTORS; p++) {
                            long pred = predict(p, wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
                            int residual = (int) (px[row + x] - pred);
                            s.token[p][s.n] =
                                    (byte) TOKEN_CFG.encode(packSigned(residual), tmp);
                        }
                        for (int p = 0; p < s.propIds.length; p++) {
                            s.prop[p][s.n] = propValue(s.propIds[p], px, ref, row, rowN,
                                    x, y, vW, vN, vNW, vNE, vNN, wp);
                        }
                        s.n++;
                    }
                    wp.afterPredict(x, y, px[row + x]);
                }
            }
        }
        return s;
    }

    private static TNode learnTree(Chan c, int[] ref, List<int[]> rects) {
        return learnTree(c, ref, rects, MAX_SAMPLES, MAX_DEPTH);
    }

    /**
     * Learns a content-adaptive subtree for one channel: recursive greedy
     * splits on the property that most reduces the residual-token entropy of
     * a sampled pixel subset, stopping when the projected saving no longer
     * covers the tree and histogram overhead.
     */
    static TNode learnTree(Chan c, int[] ref, List<int[]> rects, int maxSamples,
            int maxDepth) {
        if (Boolean.getBoolean("jxl.enc.simpletree")) {
            return leafNode(c); // debug baseline: fixed one-leaf-per-channel tree
        }
        long total = 0;
        for (int[] r : rects) {
            total += (long) r[2] * r[3];
        }
        if (total < MIN_LEARN_PIXELS) {
            return leafNode(c);
        }
        Samples s = collectSamples(c, ref, rects, maxSamples);
        if (s.n < 2 * MIN_LEAF_SAMPLES) {
            return leafNode(c);
        }
        int[] idx = new int[s.n];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        double[] xlogx = new double[s.n + 1];
        for (int i = 2; i <= s.n; i++) {
            xlogx[i] = i * (Math.log(i) / LOG2);
        }
        double upscale = (double) s.totalPixels / s.n;
        return splitNode(c, s, idx, maxDepth, upscale, xlogx);
    }

    /**
     * One greedy split attempt over the node's samples; recurses on success.
     *
     * <p>A split is costed by what each side would come to <em>once it has chosen
     * its own predictor</em>, which is the whole difference. A property that
     * separates the smooth part of a plane from its edges is worth nothing if
     * both halves must go on predicting the same way; it is worth a great deal if
     * one half can switch to west and the other to the weighted predictor. So the
     * cost of a side is the smallest entropy any predictor leaves it with, and
     * both sides are free to answer differently.
     */
    private static TNode splitNode(Chan c, Samples s, int[] idx, int depthLeft, double upscale,
            double[] xlogx) {
        int m = idx.length;
        if (depthLeft <= 0 || m < 2 * MIN_LEAF_SAMPLES) {
            return leafNode(c, s, idx, xlogx);
        }
        int[][] full = new int[NUM_PREDICTORS][TOKEN_ALPHABET];
        for (int p = 0; p < NUM_PREDICTORS; p++) {
            byte[] tok = s.token[p];
            int[] hist = full[p];
            for (int i = 0; i < m; i++) {
                hist[tok[idx[i]]]++;
            }
        }
        // A side costs xlogx[count] - sum(xlogx[hist]) in coded tokens, plus the
        // raw middle bits those tokens carry. Track the two together as a score
        // (lower is better) so the best predictor for a side is just its minimum.
        double[] sumFull = new double[NUM_PREDICTORS];
        double[] extraFull = new double[NUM_PREDICTORS];
        double bestFull = Double.MAX_VALUE;
        for (int p = 0; p < NUM_PREDICTORS; p++) {
            double sum = 0;
            double extra = 0;
            for (int t = 0; t < TOKEN_ALPHABET; t++) {
                sum += xlogx[full[p][t]];
                extra += full[p][t] * TOKEN_EXTRA_BITS[t];
            }
            sumFull[p] = sum;
            extraFull[p] = extra;
            bestFull = Math.min(bestFull, extra - sum);
        }
        double baseCost = xlogx[m] + bestFull;

        int bestCol = -1;
        int bestSplit = 0;
        double bestCost = baseCost;
        long[] keyed = new long[m];
        int[][] histR = new int[NUM_PREDICTORS][TOKEN_ALPHABET];
        double[] scoreR = new double[NUM_PREDICTORS];
        double[] scoreL = new double[NUM_PREDICTORS];
        for (int col = 0; col < s.propIds.length; col++) {
            int[] propCol = s.prop[col];
            for (int i = 0; i < m; i++) {
                keyed[i] = ((long) propCol[idx[i]] << 32) | (idx[i] & 0xFFFFFFFFL);
            }
            java.util.Arrays.sort(keyed);
            if ((int) (keyed[0] >>> 32) == (int) (keyed[m - 1] >>> 32)) {
                continue; // constant property
            }
            for (int p = 0; p < NUM_PREDICTORS; p++) {
                java.util.Arrays.fill(histR[p], 0);
                scoreR[p] = 0;
                scoreL[p] = extraFull[p] - sumFull[p];
            }
            // ascending prefix = right side (values <= split), suffix = left
            for (int i = 0; i < m - 1; i++) {
                int sample = (int) keyed[i];
                for (int p = 0; p < NUM_PREDICTORS; p++) {
                    int t = s.token[p][sample];
                    int[] hist = histR[p];
                    scoreR[p] += TOKEN_EXTRA_BITS[t] - (xlogx[hist[t] + 1] - xlogx[hist[t]]);
                    hist[t]++;
                    int cf = full[p][t] - hist[t];
                    scoreL[p] -= TOKEN_EXTRA_BITS[t] + (xlogx[cf] - xlogx[cf + 1]);
                }
                int key = (int) (keyed[i] >>> 32);
                if (key == (int) (keyed[i + 1] >>> 32)) {
                    continue;
                }
                int nR = i + 1;
                int nL = m - nR;
                if (nR < MIN_LEAF_SAMPLES || nL < MIN_LEAF_SAMPLES) {
                    continue;
                }
                double bestR = scoreR[0];
                double bestL = scoreL[0];
                for (int p = 1; p < NUM_PREDICTORS; p++) {
                    bestR = Math.min(bestR, scoreR[p]);
                    bestL = Math.min(bestL, scoreL[p]);
                }
                double cost = (xlogx[nR] + bestR) + (xlogx[nL] + bestL);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestCol = col;
                    bestSplit = key;
                }
            }
        }
        if (bestCol < 0 || (baseCost - bestCost) * upscale < SPLIT_COST_BITS) {
            return leafNode(c, s, idx, xlogx);
        }
        int[] propCol = s.prop[bestCol];
        int nLeft = 0;
        for (int i = 0; i < m; i++) {
            if (propCol[idx[i]] > bestSplit) {
                nLeft++;
            }
        }
        int[] li = new int[nLeft];
        int[] ri = new int[m - nLeft];
        int a = 0;
        int b = 0;
        for (int i = 0; i < m; i++) {
            if (propCol[idx[i]] > bestSplit) {
                li[a++] = idx[i];
            } else {
                ri[b++] = idx[i];
            }
        }
        TNode n = new TNode();
        n.prop = s.propIds[bestCol];
        n.split = bestSplit;
        n.left = splitNode(c, s, li, depthLeft - 1, upscale, xlogx);
        n.right = splitNode(c, s, ri, depthLeft - 1, upscale, xlogx);
        return n;
    }

    /** A leaf, taking whichever predictor leaves its own pixels cheapest to code. */
    private static TNode leafNode(Chan c, Samples s, int[] idx, double[] xlogx) {
        TNode n = leafNode(c);
        int m = idx.length;
        if (m == 0) {
            return n;
        }
        int[] hist = new int[TOKEN_ALPHABET];
        double best = Double.MAX_VALUE;
        for (int p = 0; p < NUM_PREDICTORS; p++) {
            java.util.Arrays.fill(hist, 0);
            byte[] tok = s.token[p];
            for (int i = 0; i < m; i++) {
                hist[tok[idx[i]]]++;
            }
            double score = 0;
            for (int t = 0; t < TOKEN_ALPHABET; t++) {
                score += hist[t] * TOKEN_EXTRA_BITS[t] - xlogx[hist[t]];
            }
            if (score < best) {
                best = score;
                n.predictor = p;
            }
        }
        return n;
    }

    // ------------------------------------------------------ local group trees

    /**
     * Builds a fully self-contained section for one group (its own learned
     * tree and entropy code) and returns its bytes when they undercut the
     * projected cost of coding the group against the global histograms.
     */
    private byte[] tryLocalGroup(int g, int groupColumns, int numGlobal,
            List<Chan> groupChans, int[] refOf, TokenBuf globalTokens, EntropyEncoder probe,
            int distMult) {
        if (globalTokens.n == 0) {
            return null;
        }
        findMatches(globalTokens, distMult, null); // already cached from writeFrame
        double globalBits = 0;
        int m = 0;
        int i = 0;
        while (i < globalTokens.n) {
            if (m < globalTokens.nMatches && globalTokens.mPos[m] == i) {
                globalBits += probe.copyCostBits(globalTokens.ctx[i],
                        globalTokens.mLen[m], globalTokens.mVal[m]);
                i += globalTokens.mLen[m];
                m++;
                continue;
            }
            globalBits += probe.tokenCostBits(globalTokens.ctx[i], globalTokens.val[i]);
            i++;
        }
        if (globalBits < 12_000) {
            return null; // too small for a private code spec to pay off
        }
        // Slice every channel the way the decoder does: origin and size
        // shifted by the channel's hshift/vshift. A group where some channel
        // has nothing inside is left to the global path — the decoder omits
        // such a channel and renumbers the rest, which only the global
        // subtrees are prepared for (the ragged brackets in writeFrame).
        int[] gr = groupRect(g, groupColumns);
        int[][] rects = new int[groupChans.size()][];
        for (int k = 0; k < groupChans.size(); k++) {
            rects[k] = slice(groupChans.get(k), gr[0], gr[1], gr[2], gr[3]);
            if (rects[k][2] <= 0 || rects[k][3] <= 0) {
                return null;
            }
        }
        Map<Chan, TNode> localSubs = new HashMap<>();
        for (int k = 0; k < groupChans.size(); k++) {
            Chan c = groupChans.get(k);
            List<int[]> rect = List.of(rects[k]);
            TNode sub = learnTree(c, refPlane(refOf, numGlobal + k), rect, 1 << 13, 3);
            refineLeaves(c, sub, refPlane(refOf, numGlobal + k), rect);
            localSubs.put(c, sub);
        }
        TNode localTree = chainNode(groupChans, groupChans.size() - 1, localSubs);
        int numCtxLocal = assignCtx(localTree);
        TokenBuf buf = new TokenBuf();
        for (int k = 0; k < groupChans.size(); k++) {
            Chan c = groupChans.get(k);
            tokenizeRect(c, localSubs.get(c), refPlane(refOf, numGlobal + k),
                    rects[k][0], rects[k][1], rects[k][2], rects[k][3], buf);
        }
        BitWriter gw = new BitWriter();
        writeStandaloneSection(gw, localTree, numCtxLocal, buf, distMult, -1);
        gw.zeroPadToByte();
        byte[] bytes = gw.toByteArray();
        return bytes.length * 8L + 128 < globalBits ? bytes : null;
    }

    private static int packSigned(int v) {
        return v >= 0 ? 2 * v : -2 * v - 1;
    }

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
