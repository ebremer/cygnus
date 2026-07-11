package com.ebremer.jpegxl.encoder;

import com.ebremer.jpegxl.codestream.BitDepth;
import com.ebremer.jpegxl.codestream.ColourEncoding;
import com.ebremer.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.jpegxl.codestream.ImageMetadata;
import com.ebremer.jpegxl.codestream.SizeHeader;
import com.ebremer.jpegxl.entropy.HybridUintConfig;
import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.modular.ModularStream;
import com.ebremer.jpegxl.modular.WpState;
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
    private static final int MIN_RUN = 12;

    /** One coded modular channel. */
    static final class Chan {
        final int w;
        final int h;
        final int[] px;
        int predictor = 5; // 5 = gradient, 6 = weighted

        Chan(int w, int h, int[] px) {
            this.w = w;
            this.h = h;
            this.px = px;
        }
    }

    /** A buffered token stream for one section. */
    static final class TokenBuf {
        int[] ctx = new int[1 << 12];
        int[] val = new int[1 << 12];
        int n;

        void add(int c, int v) {
            if (n == val.length) {
                ctx = java.util.Arrays.copyOf(ctx, n * 2);
                val = java.util.Arrays.copyOf(val, n * 2);
            }
            ctx[n] = c;
            val[n] = v;
            n++;
        }
    }

    private final int width;
    private final int height;
    private final int bits;
    private final boolean grey;
    private final boolean alpha;
    private final boolean alphaAssociated;
    private final int[][] input;
    private final int numInput;

    // decided by prepare()
    private final List<Chan> chans = new ArrayList<>();
    private int nbMeta;
    private int rctType = -1;    // coded RCT type, or -1 for none
    private int[] paletteData;   // 3 x paletteSize colour components, or null
    private int paletteSize;
    private boolean prepared;

    private JxlEncoder(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated) {
        this.width = width;
        this.height = height;
        this.bits = bits;
        this.grey = grey;
        this.alpha = alpha;
        this.alphaAssociated = alphaAssociated;
        this.numInput = (grey ? 1 : 3) + (alpha ? 1 : 0);
        if (planes.length != numInput) {
            throw new IllegalArgumentException("expected " + numInput + " planes, got " + planes.length);
        }
        this.input = new int[numInput][];
        for (int i = 0; i < numInput; i++) {
            if (planes[i].length != width * height) {
                throw new IllegalArgumentException("plane " + i + " has wrong size");
            }
            this.input[i] = planes[i].clone();
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
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad dimensions " + width + "x" + height);
        }
        if (bits < 1 || bits > 31) {
            throw new IllegalArgumentException("bits per sample must be in 1..31");
        }
        return new JxlEncoder(planes, width, height, bits, grey, alpha, alphaAssociated).run();
    }

    /**
     * Encodes with an embedded preview image (same channel layout and bit
     * depth as the main image; the preview is at most 4096 pixels per side).
     */
    public static byte[] encodeWithPreview(int[][] planes, int width, int height, int bits,
            boolean grey, boolean alpha, boolean alphaAssociated,
            int[][] previewPlanes, int previewWidth, int previewHeight) throws IOException {
        if (previewWidth <= 0 || previewHeight <= 0
                || previewWidth > 4096 || previewHeight > 4096) {
            throw new IllegalArgumentException("bad preview dimensions");
        }
        JxlEncoder main = new JxlEncoder(planes, width, height, bits, grey, alpha,
                alphaAssociated);
        JxlEncoder preview = new JxlEncoder(previewPlanes, previewWidth, previewHeight,
                bits, grey, alpha, alphaAssociated);
        BitWriter out = new BitWriter();
        out.write(0xff, 8);
        out.write(0x0a, 8);
        new SizeHeader(width, height).write(out);
        ImageMetadata meta = buildMetadata(bits, grey, alpha, alphaAssociated);
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
        buildMetadata(bits, grey, alpha, alphaAssociated).write(out);
        writeFrame(out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ transforms

    /** Chooses transforms and per-channel predictors, and builds the channel list. */
    private void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        if (!grey && tryPalette()) {
            // palette meta channel + index channel (+ alpha)
            nbMeta = 1;
            int[] index = input[0]; // reused as the index plane by tryPalette
            chans.add(new Chan(paletteSize, 3, paletteData));
            chans.add(new Chan(width, height, index));
            if (alpha) {
                chans.add(new Chan(width, height, input[3]));
            }
        } else {
            if (!grey) {
                rctType = selectRct();
                forwardRct(rctType, input[0], input[1], input[2]);
                if (rctType == 0) {
                    rctType = -1; // nothing to code
                }
            }
            for (int i = 0; i < numInput; i++) {
                chans.add(new Chan(width, height, input[i]));
            }
        }
        for (int i = nbMeta; i < chans.size(); i++) {
            choosePredictor(chans.get(i));
        }
    }

    /**
     * Builds a global palette when the image has few distinct colours.
     * On success {@code input[0]} becomes the index plane.
     */
    private boolean tryPalette() {
        Map<Long, Integer> colours = new HashMap<>();
        int[] r = input[0];
        int[] g = input[1];
        int[] b = input[2];
        for (int i = 0; i < r.length; i++) {
            long key = ((long) r[i] << 32) | ((long) g[i] << 16) | b[i];
            if (colours.size() >= MAX_PALETTE && !colours.containsKey(key)) {
                return false;
            }
            colours.putIfAbsent(key, colours.size());
        }
        if (colours.size() < 2 || colours.size() >= r.length / 4) {
            return false;
        }
        // sort by luma-ish value so neighbouring indices have similar colours
        Long[] sorted = colours.keySet().toArray(Long[]::new);
        java.util.Arrays.sort(sorted, (x, y) -> {
            long lx = 2 * ((x >>> 16) & 0xffff) + (x >>> 32) + (x & 0xffff);
            long ly = 2 * ((y >>> 16) & 0xffff) + (y >>> 32) + (y & 0xffff);
            return Long.compare(lx, ly);
        });
        paletteSize = sorted.length;
        paletteData = new int[3 * paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            long key = sorted[i];
            paletteData[i] = (int) (key >>> 32);
            paletteData[paletteSize + i] = (int) ((key >>> 16) & 0xffff);
            paletteData[2 * paletteSize + i] = (int) (key & 0xffff);
            colours.put(key, i);
        }
        for (int i = 0; i < r.length; i++) {
            long key = ((long) r[i] << 32) | ((long) g[i] << 16) | b[i];
            r[i] = colours.get(key);
        }
        return true;
    }

    /** Picks the cheapest RCT type among the unpermuted ones (0..6). */
    private int selectRct() {
        int best = 0;
        long bestCost = Long.MAX_VALUE;
        int[] c0 = new int[width * height];
        int[] c1 = new int[width * height];
        int[] c2 = new int[width * height];
        for (int type = 0; type <= 6; type++) {
            System.arraycopy(input[0], 0, c0, 0, c0.length);
            System.arraycopy(input[1], 0, c1, 0, c1.length);
            System.arraycopy(input[2], 0, c2, 0, c2.length);
            forwardRct(type, c0, c1, c2);
            long cost = gradientCost(c0) + gradientCost(c1) + gradientCost(c2);
            if (cost < bestCost) {
                bestCost = cost;
                best = type;
            }
        }
        return best;
    }

    /** Approximate coded size of a plane under the gradient predictor. */
    private long gradientCost(int[] px) {
        long cost = 0;
        int stride = height > 512 ? 3 : 1; // sample rows on large images
        for (int y = 0; y < height; y += stride) {
            int row = y * width;
            int rowN = row - width;
            for (int x = 0; x < width; x++) {
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
        int numLfGroups = ceilDiv(width, GROUP_DIM * 8) * ceilDiv(height, GROUP_DIM * 8);
        boolean single = numGroups == 1;

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

        // ---- learn per-channel subtrees and build the global tree
        int[] refOf = referenceChannels(numGlobal);
        Map<Chan, TNode> subs = new HashMap<>();
        for (int i = 0; i < chans.size(); i++) {
            Chan c = chans.get(i);
            List<int[]> rects = i < numGlobal
                    ? List.of(new int[] {0, 0, c.w, c.h})
                    : groupRects(c, groupColumns, numGroups);
            subs.put(c, learnTree(c, refPlane(refOf, i), rects));
        }
        TNode tree = buildTree(numGlobal, subs);
        int numCtx = assignCtx(tree);

        // ---- tokenize all sections
        TokenBuf globalBuf = new TokenBuf();
        for (int i = 0; i < numGlobal; i++) {
            Chan c = chans.get(i);
            tokenizeRect(c, subs.get(c), refPlane(refOf, i), 0, 0, c.w, c.h, globalBuf);
        }
        TokenBuf[] groupBufs = new TokenBuf[single ? 0 : numGroups];
        for (int g = 0; g < groupBufs.length; g++) {
            TokenBuf buf = new TokenBuf();
            int gx = (g % groupColumns) * GROUP_DIM;
            int gy = (g / groupColumns) * GROUP_DIM;
            for (int i = numGlobal; i < chans.size(); i++) {
                Chan c = chans.get(i);
                tokenizeRect(c, subs.get(c), refPlane(refOf, i), gx, gy,
                        Math.min(GROUP_DIM, c.w - gx), Math.min(GROUP_DIM, c.h - gy), buf);
            }
            groupBufs[g] = buf;
        }

        // ---- per-group local trees where they beat the global code
        byte[][] localBytes = new byte[groupBufs.length][];
        if (groupBufs.length >= 2 && chans.size() > numGlobal
                && !Boolean.getBoolean("jxl.enc.simpletree")) {
            EntropyEncoder probe = new EntropyEncoder(numCtx, true, true);
            emitBuffer(globalBuf, null, probe);
            for (TokenBuf buf : groupBufs) {
                emitBuffer(buf, null, probe);
            }
            probe.prepareCosts();
            List<Chan> groupChans = List.copyOf(chans.subList(numGlobal, chans.size()));
            int numGlobalF = numGlobal;
            java.util.stream.IntStream.range(0, groupBufs.length).parallel().forEach(g ->
                    localBytes[g] = tryLocalGroup(g, groupColumns, numGlobalF, groupChans,
                            refOf, groupBufs[g], probe));
        }

        // ---- pass 1: histograms (local groups keep their own code)
        EntropyEncoder treeEnc = new EntropyEncoder(6, false);
        emitTree(tree, null, treeEnc);
        EntropyEncoder dataEnc = new EntropyEncoder(numCtx, true, true);
        emitBuffer(globalBuf, null, dataEnc);
        for (int g = 0; g < groupBufs.length; g++) {
            if (localBytes[g] == null) {
                emitBuffer(groupBufs[g], null, dataEnc);
            }
        }

        // ---- LfGlobal section
        BitWriter lfGlobal = new BitWriter();
        lfGlobal.writeBool(true); // LfChannelDequantization.all_default
        lfGlobal.writeBool(true); // global tree present
        treeEnc.writeSpec(lfGlobal);
        emitTree(tree, lfGlobal, treeEnc);
        dataEnc.writeSpec(lfGlobal);
        writeGroupHeader(lfGlobal);
        emitBuffer(globalBuf, lfGlobal, dataEnc);
        lfGlobal.zeroPadToByte();
        byte[] lfGlobalBytes = lfGlobal.toByteArray();

        // ---- pass group sections
        byte[][] groupBytes = new byte[groupBufs.length][];
        for (int g = 0; g < groupBufs.length; g++) {
            if (localBytes[g] != null) {
                groupBytes[g] = localBytes[g];
                continue;
            }
            BitWriter gw = new BitWriter();
            gw.writeBool(true); // use_global_tree
            gw.writeBool(true); // default wp
            gw.write(0, 2);     // nb_transforms = 0
            emitBuffer(groupBufs[g], gw, dataEnc);
            gw.zeroPadToByte();
            groupBytes[g] = gw.toByteArray();
        }

        // ---- assemble the frame
        writeFrameHeader(out, alpha);

        out.writeBool(false); // TOC not permuted
        out.zeroPadToByte();
        writeTocEntry(out, lfGlobalBytes.length);
        if (!single) {
            for (int i = 0; i < numLfGroups; i++) {
                writeTocEntry(out, 0); // empty LfGroup sections
            }
            writeTocEntry(out, 0); // empty HfGlobal section
            for (byte[] g : groupBytes) {
                writeTocEntry(out, g.length);
            }
        }
        out.zeroPadToByte();
        out.writeBytes(lfGlobalBytes);
        for (byte[] g : groupBytes) {
            out.writeBytes(g);
        }
    }

    /** GroupHeader of the global modular stream, including the transforms. */
    private void writeGroupHeader(BitWriter w) {
        w.writeBool(true);  // use_global_tree
        w.writeBool(true);  // default weighted predictor parameters
        if (paletteData != null) {
            w.write(1, 2);  // nb_transforms = 1
            w.write(1, 2);  // transform id: palette
            w.write(0, 2);  // begin_c selector 0
            w.write(0, 3);  // begin_c = 0
            w.write(1, 2);  // num_c selector 1 -> 3
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
            w.write(1, 2);  // nb_transforms = 1
            writeRctTransform(w, rctType);
        } else {
            w.write(0, 2);  // nb_transforms = 0
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
        } else {
            w.write(2, 2);
            w.write(rctType - 2, 4);
        }
    }

    static ImageMetadata buildMetadata(int bits, boolean grey, boolean alpha,
            boolean alphaAssociated) {
        ImageMetadata meta = new ImageMetadata();
        meta.bitDepth = BitDepth.of(bits);
        meta.modular16BitBuffers = bits <= 12;
        meta.xybEncoded = false;
        if (alpha) {
            ExtraChannelInfo ec = new ExtraChannelInfo();
            ec.type = ExtraChannelInfo.TYPE_ALPHA;
            ec.bitDepth = BitDepth.of(bits);
            ec.alphaAssociated = alphaAssociated;
            meta.extraChannels.add(ec);
        }
        ColourEncoding colour = new ColourEncoding();
        if (grey) {
            colour.allDefault = false;
            colour.colourSpace = ColourEncoding.CS_GREY;
        }
        meta.colourEncoding = colour;
        return meta;
    }

    static void writeFrameHeader(BitWriter out, boolean alpha) {
        out.zeroPadToByte();
        out.writeBool(false);        // !all_default
        out.write(0, 2);             // frame_type: regular
        out.writeBool(true);         // encoding: modular
        out.writeU64(0);             // flags
        out.writeBool(false);        // do_YCbCr (xyb_encoded is false)
        out.write(0, 2);             // log upsampling
        if (alpha) {
            out.write(0, 2);         // extra channel upsampling
        }
        out.write(GROUP_SIZE_SHIFT_BITS, 2); // group_size_shift = 8
        out.write(0, 2);             // num_passes = 1 (U32 selector 0)
        out.writeBool(false);        // have_crop
        int blendEntries = 1 + (alpha ? 1 : 0);
        for (int i = 0; i < blendEntries; i++) {
            out.write(0, 2);         // blend mode: replace (full frame -> no source)
        }
        out.writeBool(true);         // is_last
        out.write(0, 2);             // name length (U32 selector 0)
        out.writeBool(false);        // RestorationFilter not all_default
        out.writeBool(false);        // gaborish off
        out.write(0, 2);             // epf iterations = 0
        out.writeU64(0);             // restoration filter extensions
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
     * Builds the MA tree. Channels coded in the global stream and channels
     * coded per group both see property 0 restart at zero, so when both kinds
     * exist the tree first splits on property 1 (the stream index: 0 for the
     * global stream, larger for group streams).
     */
    private TNode buildTree(int numGlobal, Map<Chan, TNode> subs) {
        List<Chan> globals = chans.subList(0, numGlobal);
        List<Chan> groups = chans.subList(numGlobal, chans.size());
        if (globals.isEmpty()) {
            return chainNode(groups, groups.size() - 1, subs);
        }
        if (groups.isEmpty()) {
            return chainNode(globals, globals.size() - 1, subs);
        }
        TNode n = new TNode();
        n.prop = 1;
        n.split = 0;
        n.left = chainNode(groups, groups.size() - 1, subs);
        n.right = chainNode(globals, globals.size() - 1, subs);
        return n;
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
                emitToken(out, enc, 3, 0); // offset
                emitToken(out, enc, 4, 0); // multiplier log
                emitToken(out, enc, 5, 0); // multiplier bits
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

    /** Emits a buffered section, collapsing runs into LZ77 copies. */
    static void emitBuffer(TokenBuf buf, BitWriter out, EntropyEncoder enc) {
        int i = 0;
        while (i < buf.n) {
            if (i > 0) {
                int prev = buf.val[i - 1];
                int r = 0;
                while (i + r < buf.n && buf.val[i + r] == prev) {
                    r++;
                }
                if (r >= MIN_RUN) {
                    if (out == null) {
                        enc.countCopy(buf.ctx[i], r);
                    } else {
                        enc.writeCopy(out, buf.ctx[i], r);
                    }
                    i += r;
                    continue;
                }
            }
            if (out == null) {
                enc.count(buf.ctx[i], buf.val[i]);
            } else {
                enc.write(out, buf.ctx[i], buf.val[i]);
            }
            i++;
        }
    }

    /**
     * Walks one channel rectangle in scan order, running the channel's learned
     * subtree per pixel and buffering packed residuals under the leaf context.
     * The rectangle is its own little image: neighbours never cross it.
     */
    static void tokenizeRect(Chan ch, TNode sub, int[] ref, int x0, int y0, int w, int h,
            TokenBuf buf) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] px = ch.px;
        int stride = ch.w;
        WpState wp = ch.predictor == 6 ? new WpState(WpState.WpParams.DEFAULT, w) : null;
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * stride + x0;
            int rowN = row - stride;
            for (int x = 0; x < w; x++) {
                int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                int vN = y > 0 ? px[rowN + x] : vW;
                int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                int vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                int vNN = y > 1 ? px[row - 2 * stride + x] : vN;
                long pred;
                if (wp != null) {
                    wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                    pred = wp.prediction();
                } else {
                    pred = ModularStream.clampedGradient(vW, vN, vNW);
                }
                TNode n = sub;
                while (n.prop >= 0) {
                    int val = propValue(n.prop, px, ref, row, rowN, x, y,
                            vW, vN, vNW, vNE, vNN, wp);
                    n = val > n.split ? n.left : n.right;
                }
                int residual = (int) (px[row + x] - pred);
                buf.add(n.ctx, packSigned(residual));
                if (wp != null) {
                    wp.afterPredict(x, y, px[row + x]);
                }
            }
        }
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

    // ---------------------------------------------------------- tree learning

    private static final int MAX_SAMPLES = 1 << 15;
    private static final int MIN_LEARN_PIXELS = 4096;
    private static final int MAX_DEPTH = 4;
    private static final int MIN_LEAF_SAMPLES = 64;
    private static final double SPLIT_COST_BITS = 650; // node overhead plus margin
    private static final int TOKEN_ALPHABET = 72;
    private static final HybridUintConfig TOKEN_CFG = new HybridUintConfig(4, 1, 0);
    private static final double LOG2 = Math.log(2);

    /** Nearest earlier same-sized channel in the same substream, or -1. */
    private int[] referenceChannels(int numGlobal) {
        int[] refOf = new int[chans.size()];
        java.util.Arrays.fill(refOf, -1);
        for (int i = 0; i < chans.size(); i++) {
            Chan c = chans.get(i);
            int lo = i < numGlobal ? 0 : numGlobal;
            for (int j = i - 1; j >= lo; j--) {
                Chan r = chans.get(j);
                if (r.w == c.w && r.h == c.h) {
                    refOf[i] = j;
                    break;
                }
            }
        }
        return refOf;
    }

    private int[] refPlane(int[] refOf, int i) {
        return refOf[i] >= 0 ? chans.get(refOf[i]).px : null;
    }

    /** The group grid rectangles clipped to one channel, in group scan order. */
    private static List<int[]> groupRects(Chan c, int groupColumns, int numGroups) {
        List<int[]> rects = new ArrayList<>();
        for (int g = 0; g < numGroups; g++) {
            int gx = (g % groupColumns) * GROUP_DIM;
            int gy = (g / groupColumns) * GROUP_DIM;
            int w = Math.min(GROUP_DIM, c.w - gx);
            int h = Math.min(GROUP_DIM, c.h - gy);
            if (w > 0 && h > 0) {
                rects.add(new int[] {gx, gy, w, h});
            }
        }
        return rects;
    }

    /** Sampled pixels of one channel: residual tokens plus property values. */
    private static final class Samples {
        int n;
        long totalPixels;
        byte[] token;
        int[] propIds;
        int[][] prop; // [column][sample]
    }

    /**
     * Properties the learner may split on. 0 and 1 are fixed by the chain
     * structure; 15 forces the decoder to run the weighted predictor, so it is
     * only offered on channels that pay that cost anyway.
     */
    private static int[] candidateProps(Chan c, int[] ref) {
        int n = 13 + (c.predictor == 6 ? 1 : 0) + (ref != null ? 4 : 0);
        int[] ids = new int[n];
        int k = 0;
        for (int p = 2; p <= 14; p++) {
            ids[k++] = p;
        }
        if (c.predictor == 6) {
            ids[k++] = 15;
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
     * evenly strided subset of pixels: the residual's hybrid-uint token and
     * every candidate property value.
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
        s.propIds = candidateProps(c, ref);
        s.token = new byte[capacity];
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
            WpState wp = c.predictor == 6 ? new WpState(WpState.WpParams.DEFAULT, w) : null;
            for (int y = 0; y < h; y++) {
                int row = (y0 + y) * stride + x0;
                int rowN = row - stride;
                for (int x = 0; x < w; x++) {
                    int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                    int vN = y > 0 ? px[rowN + x] : vW;
                    int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                    int vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                    int vNN = y > 1 ? px[row - 2 * stride + x] : vN;
                    if (wp != null) {
                        wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                    }
                    if (phase++ % step == 0) {
                        long pred = wp != null ? wp.prediction()
                                : ModularStream.clampedGradient(vW, vN, vNW);
                        int residual = (int) (px[row + x] - pred);
                        s.token[s.n] = (byte) TOKEN_CFG.encode(packSigned(residual), tmp);
                        for (int p = 0; p < s.propIds.length; p++) {
                            s.prop[p][s.n] = propValue(s.propIds[p], px, ref, row, rowN,
                                    x, y, vW, vN, vNW, vNE, vNN, wp);
                        }
                        s.n++;
                    }
                    if (wp != null) {
                        wp.afterPredict(x, y, px[row + x]);
                    }
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

    /** One greedy split attempt over the node's samples; recurses on success. */
    private static TNode splitNode(Chan c, Samples s, int[] idx, int depthLeft, double upscale,
            double[] xlogx) {
        int m = idx.length;
        if (depthLeft <= 0 || m < 2 * MIN_LEAF_SAMPLES) {
            return leafNode(c);
        }
        int[] full = new int[TOKEN_ALPHABET];
        for (int i = 0; i < m; i++) {
            full[s.token[idx[i]]]++;
        }
        double sumFull = 0;
        for (int t = 0; t < TOKEN_ALPHABET; t++) {
            sumFull += xlogx[full[t]];
        }
        double baseCost = xlogx[m] - sumFull;

        int bestCol = -1;
        int bestSplit = 0;
        double bestCost = baseCost;
        long[] keyed = new long[m];
        int[] histR = new int[TOKEN_ALPHABET];
        for (int col = 0; col < s.propIds.length; col++) {
            int[] propCol = s.prop[col];
            for (int i = 0; i < m; i++) {
                keyed[i] = ((long) propCol[idx[i]] << 32) | (idx[i] & 0xFFFFFFFFL);
            }
            java.util.Arrays.sort(keyed);
            if ((int) (keyed[0] >>> 32) == (int) (keyed[m - 1] >>> 32)) {
                continue; // constant property
            }
            java.util.Arrays.fill(histR, 0);
            double sumR = 0;
            double sumL = sumFull;
            // ascending prefix = right side (values <= split), suffix = left
            for (int i = 0; i < m - 1; i++) {
                int t = s.token[(int) keyed[i]];
                sumR += xlogx[histR[t] + 1] - xlogx[histR[t]];
                histR[t]++;
                int cf = full[t] - histR[t];
                sumL += xlogx[cf] - xlogx[cf + 1];
                int key = (int) (keyed[i] >>> 32);
                if (key == (int) (keyed[i + 1] >>> 32)) {
                    continue;
                }
                int nR = i + 1;
                int nL = m - nR;
                if (nR < MIN_LEAF_SAMPLES || nL < MIN_LEAF_SAMPLES) {
                    continue;
                }
                double cost = (xlogx[nR] - sumR) + (xlogx[nL] - sumL);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestCol = col;
                    bestSplit = key;
                }
            }
        }
        if (bestCol < 0 || (baseCost - bestCost) * upscale < SPLIT_COST_BITS) {
            return leafNode(c);
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

    // ------------------------------------------------------ local group trees

    /**
     * Builds a fully self-contained section for one group (its own learned
     * tree and entropy code) and returns its bytes when they undercut the
     * projected cost of coding the group against the global histograms.
     */
    private byte[] tryLocalGroup(int g, int groupColumns, int numGlobal,
            List<Chan> groupChans, int[] refOf, TokenBuf globalTokens, EntropyEncoder probe) {
        if (globalTokens.n == 0) {
            return null;
        }
        double globalBits = 0;
        int i = 0;
        while (i < globalTokens.n) {
            if (i > 0) {
                int prev = globalTokens.val[i - 1];
                int r = 0;
                while (i + r < globalTokens.n && globalTokens.val[i + r] == prev) {
                    r++;
                }
                if (r >= MIN_RUN) {
                    globalBits += probe.copyCostBits(globalTokens.ctx[i], r);
                    i += r;
                    continue;
                }
            }
            globalBits += probe.tokenCostBits(globalTokens.ctx[i], globalTokens.val[i]);
            i++;
        }
        if (globalBits < 12_000) {
            return null; // too small for a private code spec to pay off
        }
        int gx = (g % groupColumns) * GROUP_DIM;
        int gy = (g / groupColumns) * GROUP_DIM;
        Map<Chan, TNode> localSubs = new HashMap<>();
        for (int k = 0; k < groupChans.size(); k++) {
            Chan c = groupChans.get(k);
            int w = Math.min(GROUP_DIM, c.w - gx);
            int h = Math.min(GROUP_DIM, c.h - gy);
            List<int[]> rect = w > 0 && h > 0
                    ? List.of(new int[] {gx, gy, w, h}) : List.of();
            localSubs.put(c, learnTree(c, refPlane(refOf, numGlobal + k), rect, 1 << 13, 3));
        }
        TNode localTree = chainNode(groupChans, groupChans.size() - 1, localSubs);
        int numCtxLocal = assignCtx(localTree);
        TokenBuf buf = new TokenBuf();
        for (int k = 0; k < groupChans.size(); k++) {
            Chan c = groupChans.get(k);
            tokenizeRect(c, localSubs.get(c), refPlane(refOf, numGlobal + k), gx, gy,
                    Math.min(GROUP_DIM, c.w - gx), Math.min(GROUP_DIM, c.h - gy), buf);
        }
        BitWriter gw = new BitWriter();
        gw.writeBool(false); // use_global_tree = false: a local tree follows
        gw.writeBool(true);  // default wp
        gw.write(0, 2);      // nb_transforms = 0
        EntropyEncoder treeEnc = new EntropyEncoder(6, false);
        emitTree(localTree, null, treeEnc);
        treeEnc.writeSpec(gw);
        emitTree(localTree, gw, treeEnc);
        EntropyEncoder dataEnc = new EntropyEncoder(numCtxLocal, true, true);
        emitBuffer(buf, null, dataEnc);
        dataEnc.writeSpec(gw);
        emitBuffer(buf, gw, dataEnc);
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
