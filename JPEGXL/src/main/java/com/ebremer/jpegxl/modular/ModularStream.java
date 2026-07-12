package com.ebremer.jpegxl.modular;

import com.ebremer.jpegxl.entropy.EntropyDecoder;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One modular sub-bitstream (18181-1 annex H): a GroupHeader (weighted
 * predictor parameters and transforms), an MA tree (own or global), and
 * entropy-coded channel data.
 */
public final class ModularStream {

    public static final boolean DEBUG = Boolean.getBoolean("jxl.debug");

    private final List<ModularChannel> channels;
    private int nbMetaChannels;
    /**
     * Set on the frame-global stream when only the groups covering a decode
     * region were decoded; inverse transforms whose output would depend on the
     * undecoded (zero) areas then abort with a
     * {@link com.ebremer.jpegxl.io.RegionUnsupportedException}.
     */
    public boolean regionMode;
    private final List<Transform> transforms = new ArrayList<>();
    private final WpState.WpParams wpParams;
    private final MaTree tree;
    private final EntropyDecoder code;
    private final int distMult;
    private final int paletteBpp;

    private ModularStream(List<ModularChannel> channels, int nbMetaChannels,
            List<Transform> transforms, WpState.WpParams wpParams,
            MaTree tree, EntropyDecoder code, int distMult, int paletteBpp) {
        this.channels = channels;
        this.nbMetaChannels = nbMetaChannels;
        this.transforms.addAll(transforms);
        this.wpParams = wpParams;
        this.tree = tree;
        this.code = code;
        this.distMult = distMult;
        this.paletteBpp = paletteBpp;
    }

    public List<ModularChannel> channels() {
        return channels;
    }

    public int nbMetaChannels() {
        return nbMetaChannels;
    }

    List<Transform> transforms() {
        return transforms;
    }

    WpState.WpParams wpParams() {
        return wpParams;
    }

    int paletteBpp() {
        return paletteBpp;
    }

    void setNbMetaChannels(int n) {
        nbMetaChannels = n;
    }

    /**
     * Reads a GroupHeader for a stream over the given channels (whose list is
     * modified in place by meta transforms).
     *
     * @param globalTree the frame-global MA tree, or null
     * @param globalCode the entropy code belonging to the global tree
     * @param paletteBpp the image bit depth, used by palette reconstruction
     */
    public static ModularStream readHeader(Bits in, List<ModularChannel> channels,
            MaTree globalTree, EntropyDecoder globalCode, int paletteBpp) throws IOException {
        return readHeader(in, channels, globalTree, globalCode, paletteBpp, Integer.MAX_VALUE);
    }

    /**
     * @param maxChanSize the size limit above which a non-meta channel no
     *        longer belongs to this stream (the frame group dimension for the
     *        frame-global stream)
     */
    public static ModularStream readHeader(Bits in, List<ModularChannel> channels,
            MaTree globalTree, EntropyDecoder globalCode, int paletteBpp, int maxChanSize)
            throws IOException {
        boolean useGlobalTree = in.bool();
        if (useGlobalTree && globalTree == null) {
            throw new IOException("stream requires a global MA tree but none was coded");
        }
        WpState.WpParams wpParams = readWpHeader(in);
        if (DEBUG) {
            System.err.println("[jxl] stream header: useGlobalTree=" + useGlobalTree
                    + " wpDefault=" + (wpParams == WpState.WpParams.DEFAULT)
                    + " channels=" + channels.size());
        }

        List<Transform> transforms = new ArrayList<>();
        int nbMeta = 0;
        int nbTransforms = in.u32(0, 0, 1, 0, 2, 4, 18, 8);
        if (DEBUG) {
            System.err.println("[jxl] nbTransforms=" + nbTransforms);
        }
        for (int t = 0; t < nbTransforms; t++) {
            int id = in.u(2);
            switch (id) {
                case 0 -> { // RCT
                    int beginC = in.u32(0, 3, 8, 6, 72, 10, 1096, 13);
                    int type = in.u32(6, 0, 0, 2, 2, 4, 10, 6);
                    if (type >= 42) {
                        throw new IOException("bad RCT type " + type);
                    }
                    if (beginC + 3 > channels.size()
                            || (beginC < nbMeta && beginC + 3 > nbMeta)) {
                        throw new IOException("bad RCT channel range");
                    }
                    requireEqualSized(channels, beginC, beginC + 3);
                    if (DEBUG) {
                        System.err.println("[jxl] rct beginC=" + beginC + " type=" + type);
                    }
                    transforms.add(new Transform.Rct(beginC, type));
                }
                case 1 -> { // Palette
                    int beginC = in.u32(0, 3, 8, 6, 72, 10, 1096, 13);
                    int numC = in.u32(1, 0, 3, 0, 4, 0, 1, 13);
                    int nbColours = in.u32(0, 8, 256, 10, 1280, 12, 5376, 16);
                    int nbDeltas = in.u32(0, 0, 1, 8, 257, 10, 1281, 16);
                    int dPred = in.u(4);
                    if (dPred >= 14) {
                        throw new IOException("bad palette predictor " + dPred);
                    }
                    int endC = beginC + numC;
                    if (endC > channels.size()) {
                        throw new IOException("bad palette channel range");
                    }
                    if (beginC < nbMeta) {
                        if (endC > nbMeta) {
                            throw new IOException("palette straddles meta channels");
                        }
                        nbMeta += 2 - numC;
                    } else {
                        nbMeta += 1;
                    }
                    if (DEBUG) {
                        System.err.println("[jxl] palette beginC=" + beginC + " numC=" + numC
                                + " colours=" + nbColours + " deltas=" + nbDeltas + " dPred=" + dPred);
                    }
                    requireEqualSized(channels, beginC, endC);
                    // restructure: palette meta channel at 0, index channel at beginC+1
                    ModularChannel input = channels.get(beginC);
                    List<ModularChannel> rebuilt = new ArrayList<>(channels.size() + 1);
                    ModularChannel palette = new ModularChannel(nbColours, numC, 0, -1);
                    rebuilt.add(palette);
                    rebuilt.addAll(channels.subList(0, beginC));
                    rebuilt.add(new ModularChannel(input.width, input.height, input.hshift, input.vshift));
                    rebuilt.addAll(channels.subList(endC, channels.size()));
                    channels.clear();
                    channels.addAll(rebuilt);
                    transforms.add(new Transform.Palette(beginC, numC, nbColours, nbDeltas, dPred));
                }
                case 2 -> { // Squeeze
                    int numSq = in.u32(0, 0, 1, 4, 9, 6, 41, 8);
                    List<Transform.Squeeze> steps = new ArrayList<>();
                    if (numSq == 0) {
                        steps.addAll(defaultSqueezeSteps(channels, nbMeta));
                    } else {
                        for (int i = 0; i < numSq; i++) {
                            boolean horizontal = in.bool();
                            boolean inPlace = in.bool();
                            int beginC = in.u32(0, 3, 8, 6, 72, 10, 1096, 13);
                            int numC = in.u32(1, 0, 2, 0, 3, 0, 4, 4);
                            steps.add(new Transform.Squeeze(horizontal, inPlace, beginC, numC));
                        }
                    }
                    if (DEBUG) {
                        System.err.println("[jxl] squeeze steps=" + steps);
                    }
                    for (Transform.Squeeze sq : steps) {
                        nbMeta = applyMetaSqueeze(channels, nbMeta, sq);
                        transforms.add(sq);
                    }
                }
                default -> throw new IOException("unknown transform " + id);
            }
        }
        if (channels.size() > (1 << 16)) {
            throw new IOException("too many channels after transforms");
        }

        MaTree tree;
        EntropyDecoder code;
        if (useGlobalTree) {
            tree = globalTree;
            code = globalCode.freshState();
        } else {
            long maxTreeSize = 1024;
            for (ModularChannel c : channels) {
                maxTreeSize += (long) c.width * c.height;
            }
            maxTreeSize = Math.min(1 << 20, maxTreeSize);
            MaTree.WithCode wc = MaTree.read(in, (int) maxTreeSize);
            tree = wc.tree();
            code = wc.code();
        }

        // libjxl takes the max width over all channels (including meta) up to
        // the first non-meta channel that is too large to belong to the stream
        int distMult = 0;
        for (int i = 0; i < channels.size(); i++) {
            ModularChannel ch = channels.get(i);
            if (i >= nbMeta && (ch.width > maxChanSize || ch.height > maxChanSize)) {
                break;
            }
            if (ch.isEmpty()) {
                continue;
            }
            distMult = Math.max(distMult, ch.width);
        }
        distMult = Math.min(distMult, 1 << 21);

        ModularStream m = new ModularStream(channels, nbMeta, transforms, wpParams,
                tree, code, distMult, paletteBpp);
        return m;
    }

    private static void requireEqualSized(List<ModularChannel> channels, int from, int to) throws IOException {
        ModularChannel c0 = channels.get(from);
        for (int i = from + 1; i < to; i++) {
            ModularChannel c = channels.get(i);
            if (c.width != c0.width || c.height != c0.height) {
                throw new IOException("transform requires equally sized channels");
            }
        }
    }

    private static WpState.WpParams readWpHeader(Bits in) throws IOException {
        if (in.bool()) {
            return WpState.WpParams.DEFAULT;
        }
        int p1 = in.u(5);
        int p2 = in.u(5);
        int[] p3 = new int[5];
        for (int i = 0; i < 5; i++) {
            p3[i] = in.u(5);
        }
        int[] w = new int[4];
        for (int i = 0; i < 4; i++) {
            w[i] = in.u(4);
        }
        return new WpState.WpParams(p1, p2, p3, w);
    }

    static List<Transform.Squeeze> defaultSqueezeSteps(List<ModularChannel> channels, int nbMeta) {
        List<Transform.Squeeze> steps = new ArrayList<>();
        int first = nbMeta;
        int count = channels.size() - nbMeta;
        if (count < 1) {
            return steps;
        }
        int w = channels.get(first).width;
        int h = channels.get(first).height;
        if (count > 2 && channels.get(first + 1).width == w && channels.get(first + 1).height == h) {
            steps.add(new Transform.Squeeze(true, false, first + 1, 2));
            steps.add(new Transform.Squeeze(false, false, first + 1, 2));
        }
        // horizontal first on wide images; vertical first on tall AND square
        // ones (libjxl DefaultSqueezeParameters: wide = w > h)
        if (w <= h && h > 8) {
            steps.add(new Transform.Squeeze(false, true, first, count));
            h = (h + 1) / 2;
        }
        while (w > 8 || h > 8) {
            if (w > 8) {
                steps.add(new Transform.Squeeze(true, true, first, count));
                w = (w + 1) / 2;
            }
            if (h > 8) {
                steps.add(new Transform.Squeeze(false, true, first, count));
                h = (h + 1) / 2;
            }
        }
        return steps;
    }

    private static int applyMetaSqueeze(List<ModularChannel> channels, int nbMeta,
            Transform.Squeeze sq) throws IOException {
        int endC = sq.beginC() + sq.numC();
        if (sq.beginC() < 0 || sq.numC() < 1 || endC > channels.size()) {
            throw new IOException("bad squeeze channel range");
        }
        if (sq.beginC() < nbMeta) {
            if (!sq.inPlace() || endC > nbMeta) {
                throw new IOException("bad squeeze of meta channels");
            }
            nbMeta += sq.numC();
        }
        int offset = sq.inPlace() ? endC : channels.size();
        for (int c = sq.beginC(); c < endC; c++) {
            ModularChannel ch = channels.get(c);
            if (ch.width == 0 && ch.height == 0) {
                throw new IOException("squeeze of an empty channel");
            }
            ModularChannel residual;
            if (sq.horizontal()) {
                int w = ch.width;
                ch.width = (w + 1) / 2;
                if (ch.hshift >= 0) {
                    ch.hshift++;
                }
                residual = new ModularChannel(w - ch.width, ch.height, ch.hshift, ch.vshift);
            } else {
                int h = ch.height;
                ch.height = (h + 1) / 2;
                if (ch.vshift >= 0) {
                    ch.vshift++;
                }
                residual = new ModularChannel(ch.width, h - ch.height, ch.hshift, ch.vshift);
            }
            channels.add(offset + (c - sq.beginC()), residual);
        }
        return nbMeta;
    }

    public void allocate() {
        for (ModularChannel c : channels) {
            c.allocate();
        }
    }

    /** Decodes channel {@code cidx}; {@code sidx} is the stream index (MA property 1). */
    public void decodeChannel(Bits in, int cidx, int sidx) throws IOException {
        ModularChannel c = channels.get(cidx);
        if (DEBUG) {
            System.err.println("[jxl] decode channel " + cidx + ": " + c.width + "x" + c.height
                    + " shift=(" + c.hshift + "," + c.vshift + ") sidx=" + sidx);
        }
        if (c.isEmpty()) {
            return;
        }
        c.allocate();
        int w = c.width;
        int h = c.height;
        int[] px = c.pixels;

        // resolve the static properties (channel and stream index) up front
        FilteredTree ft = filterTree(cidx, sidx);
        WpState wp = ft.usesWp ? new WpState(wpParams, w) : null;

        // channels usable for the "previous channel" properties
        int[] refIdx = new int[cidx];
        int nRef = 0;
        for (int i = cidx - 1; i >= 0; i--) {
            ModularChannel rc = channels.get(i);
            if (rc.width == c.width && rc.height == c.height
                    && rc.hshift == c.hshift && rc.vshift == c.vshift) {
                refIdx[nRef++] = i;
            }
        }

        if (ft.property.length == 1 && wp == null && ft.multiplier[0] == 1
                && ft.offset[0] == 0 && ft.predictor[0] == 5) {
            // common fast path: one gradient leaf, no walk, no weighted state
            int ctx = ft.context[0];
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int rowN = row - w;
                for (int x = 0; x < w; x++) {
                    int vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                    int vN = y > 0 ? px[rowN + x] : vW;
                    int vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                    int raw = code.readSymbol(in, ctx, distMult);
                    px[row + x] = Bits.unpackSigned(raw) + clampedGradient(vW, vN, vNW);
                }
            }
            return;
        }

        int[] property = ft.property;
        int[] splitValue = ft.splitValue;
        int[] left = ft.left;
        int[] right = ft.right;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rowN = row - w;
            for (int x = 0; x < w; x++) {
                long vW = x > 0 ? px[row + x - 1] : (y > 0 ? px[rowN + x] : 0);
                long vN = y > 0 ? px[rowN + x] : vW;
                long vNW = (x > 0 && y > 0) ? px[rowN + x - 1] : vW;
                long vNE = (x + 1 < w && y > 0) ? px[rowN + x + 1] : vN;
                long vNN = y > 1 ? px[row - 2 * w + x] : vN;
                long vNEE = (x + 2 < w && y > 0) ? px[rowN + x + 2] : vNE;
                long vWW = x > 1 ? px[row + x - 2] : vW;

                if (wp != null) {
                    wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                }
                int node = 0;
                int prop;
                while ((prop = property[node]) >= 0) {
                    // property values wrap to 32 bits (libjxl PropertyVal)
                    int val;
                    switch (prop) {
                        case 0 -> val = cidx;
                        case 1 -> val = sidx;
                        case 2 -> val = y;
                        case 3 -> val = x;
                        case 4 -> val = (int) Math.abs(vN);
                        case 5 -> val = (int) Math.abs(vW);
                        case 6 -> val = (int) vN;
                        case 7 -> val = (int) vW;
                        case 8 -> {
                            // W minus the gradient prediction at the W position,
                            // with neighbour fallbacks evaluated at (x-1, y)
                            if (x > 0) {
                                long pw = x > 1 ? px[row + x - 2] : (y > 0 ? px[rowN + x - 1] : 0);
                                long pn = y > 0 ? px[rowN + x - 1] : pw;
                                long pnw = (x > 1 && y > 0) ? px[rowN + x - 2] : pw;
                                val = (int) (vW - (pw + pn - pnw));
                            } else {
                                val = (int) vW;
                            }
                        }
                        case 9 -> val = (int) (vW + vN - vNW);
                        case 10 -> val = (int) (vW - vNW);
                        case 11 -> val = (int) (vNW - vN);
                        case 12 -> val = (int) (vN - vNE);
                        case 13 -> val = (int) (vN - vNN);
                        case 14 -> val = (int) (vW - vWW);
                        case 15 -> val = wp != null ? wp.maxError() : 0;
                        default -> {
                            int k = (prop - 16) >> 2;
                            if (k >= nRef) {
                                val = 0; // property refers to a channel that does not exist here
                            } else {
                                int[] rpx = channels.get(refIdx[k]).pixels;
                                int rv = rpx[row + x];
                                if ((prop & 2) != 0) {
                                    int rw = x > 0 ? rpx[row + x - 1] : 0;
                                    int rn = y > 0 ? rpx[rowN + x] : rw;
                                    int rnw = (x > 0 && y > 0) ? rpx[rowN + x - 1] : rw;
                                    long diff = (long) rv - clampedGradient(rw, rn, rnw);
                                    val = (prop & 1) == 0 ? (int) Math.abs(diff) : (int) diff;
                                } else {
                                    val = (prop & 1) == 0 ? (int) Math.abs((long) rv) : rv;
                                }
                            }
                        }
                    }
                    node = val > splitValue[node] ? left[node] : right[node];
                }

                int raw = code.readSymbol(in, ft.context[node], distMult);
                long v = (long) Bits.unpackSigned(raw) * ft.multiplier[node] + ft.offset[node];
                v += predict(ft.predictor[node], wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
                px[row + x] = (int) v;
                if (wp != null) {
                    // the error state tracks the stored (wrapped) pixel, not
                    // the unwrapped prediction sum
                    wp.afterPredict(x, y, px[row + x]);
                }
            }
        }
    }

    /** A per-channel view of the MA tree with static properties resolved. */
    private static final class FilteredTree {
        int[] property;
        int[] splitValue;
        int[] left;
        int[] right;
        int[] context;
        int[] predictor;
        int[] offset;
        int[] multiplier;
        boolean usesWp;
    }

    /**
     * Copies the tree, following branches on properties 0 (channel index) and
     * 1 (stream index) immediately since their values are fixed for the whole
     * channel. Leaves keep their original context ids.
     */
    private FilteredTree filterTree(int cidx, int sidx) {
        int[] map = new int[tree.property.length];
        java.util.Arrays.fill(map, -1);
        int[] stack = new int[2 * tree.property.length + 2];
        int sp = 0;
        stack[sp++] = resolveStatic(0, cidx, sidx);
        int count = 0;
        int[] order = new int[tree.property.length];
        while (sp > 0) {
            int node = stack[--sp];
            if (map[node] >= 0) {
                continue;
            }
            map[node] = count;
            order[count++] = node;
            if (tree.property[node] >= 0) {
                stack[sp++] = resolveStatic(tree.left[node], cidx, sidx);
                stack[sp++] = resolveStatic(tree.right[node], cidx, sidx);
            }
        }
        FilteredTree ft = new FilteredTree();
        ft.property = new int[count];
        ft.splitValue = new int[count];
        ft.left = new int[count];
        ft.right = new int[count];
        ft.context = new int[count];
        ft.predictor = new int[count];
        ft.offset = new int[count];
        ft.multiplier = new int[count];
        for (int i = 0; i < count; i++) {
            int node = order[i];
            int prop = tree.property[node];
            ft.property[i] = prop;
            if (prop >= 0) {
                ft.splitValue[i] = tree.splitValue[node];
                ft.left[i] = map[resolveStatic(tree.left[node], cidx, sidx)];
                ft.right[i] = map[resolveStatic(tree.right[node], cidx, sidx)];
                ft.usesWp |= prop == 15;
            } else {
                ft.context[i] = tree.context[node];
                ft.predictor[i] = tree.predictor[node];
                ft.offset[i] = tree.offset[node];
                ft.multiplier[i] = tree.multiplier[node];
                ft.usesWp |= tree.predictor[node] == 6;
            }
        }
        return ft;
    }

    /** Follows branches whose property is 0 or 1 using the fixed values. */
    private int resolveStatic(int node, int cidx, int sidx) {
        while (true) {
            int prop = tree.property[node];
            if (prop != 0 && prop != 1) {
                return node;
            }
            int val = prop == 0 ? cidx : sidx;
            node = val > tree.splitValue[node] ? tree.left[node] : tree.right[node];
        }
    }

    public void decodeAllChannels(Bits in, int sidx) throws IOException {
        for (int i = 0; i < channels.size(); i++) {
            decodeChannel(in, i, sidx);
        }
    }

    public void finish(Bits in) throws IOException {
        code.finish(in);
    }

    /** Applies the inverse of every transform, in reverse order. */
    public void applyInverseTransforms() throws IOException {
        InverseTransforms.apply(this);
    }

    static long predict(int p, WpState wp, long w, long n, long nw, long ne,
            long nn, long nee, long ww) throws IOException {
        return switch (p) {
            case 0 -> 0;
            case 1 -> w;
            case 2 -> n;
            case 3 -> (w + n) / 2;
            case 4 -> Math.abs(n - nw) < Math.abs(w - nw) ? w : n;
            case 5 -> clampedGradient((int) w, (int) n, (int) nw);
            case 6 -> wp.prediction();
            case 7 -> ne;
            case 8 -> nw;
            case 9 -> ww;
            case 10 -> (w + nw) / 2;
            case 11 -> (n + nw) / 2;
            case 12 -> (n + ne) / 2;
            case 13 -> (6 * n - 2 * nn + 7 * w + ww + nee + 3 * ne + 8) / 16;
            default -> throw new IOException("bad predictor " + p);
        };
    }

    /**
     * Gradient clamped to the neighbour range; the intermediate sum wraps to
     * 32 bits (libjxl ClampedGradient), which is observable for 32-bit samples.
     */
    public static int clampedGradient(int n, int w, int l) {
        int lo = Math.min(n, w);
        int hi = Math.max(n, w);
        int grad = n + w - l;
        return l < lo ? hi : (l > hi ? lo : grad);
    }
}
