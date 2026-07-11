package com.ebremer.jpegxl.modular;

import java.io.IOException;
import java.util.List;

/** Inverse RCT / palette / squeeze transforms (18181-1 annex H.6). */
final class InverseTransforms {

    private InverseTransforms() {
    }

    static void apply(ModularStream m) throws IOException {
        List<Transform> transforms = m.transforms();
        for (int i = transforms.size() - 1; i >= 0; i--) {
            switch (transforms.get(i)) {
                case Transform.Rct rct -> inverseRct(m.channels(), rct);
                case Transform.Palette pal -> inversePalette(m, pal);
                case Transform.Squeeze sq -> {
                    if (m.regionMode) {
                        // the smooth-tendency chain runs along whole rows, so a
                        // partially decoded channel cannot be unsqueezed soundly
                        throw new com.ebremer.jpegxl.io.RegionUnsupportedException(
                                "frame-global squeeze");
                    }
                    inverseSqueeze(m.channels(), sq);
                }
            }
        }
    }

    // ------------------------------------------------------------------ RCT

    private static final int[][] RCT_PERMUTATIONS = {
        {0, 1, 2}, {1, 2, 0}, {2, 0, 1}, {0, 2, 1}, {1, 0, 2}, {2, 1, 0},
    };

    private static void inverseRct(List<ModularChannel> channels, Transform.Rct tr) {
        ModularChannel c0 = channels.get(tr.beginC());
        ModularChannel c1 = channels.get(tr.beginC() + 1);
        ModularChannel c2 = channels.get(tr.beginC() + 2);
        if (!c0.isEmpty()) {
            int n = c0.width * c0.height;
            int[] p0 = c0.pixels;
            int[] p1 = c1.pixels;
            int[] p2 = c2.pixels;
            switch (tr.type() % 7) {
                case 0 -> {
                }
                case 1 -> {
                    for (int i = 0; i < n; i++) {
                        p2[i] += p0[i];
                    }
                }
                case 2 -> {
                    for (int i = 0; i < n; i++) {
                        p1[i] += p0[i];
                    }
                }
                case 3 -> {
                    for (int i = 0; i < n; i++) {
                        p1[i] += p0[i];
                        p2[i] += p0[i];
                    }
                }
                case 4 -> {
                    for (int i = 0; i < n; i++) {
                        p1[i] += (p0[i] + p2[i]) >> 1;
                    }
                }
                case 5 -> {
                    for (int i = 0; i < n; i++) {
                        p1[i] = p1[i] + p0[i] + (p2[i] >> 1);
                        p2[i] += p0[i];
                    }
                }
                case 6 -> { // YCgCo
                    for (int i = 0; i < n; i++) {
                        int tmp = p0[i] - (p2[i] >> 1);
                        int v1 = p2[i] + tmp;
                        int v2 = tmp - (p1[i] >> 1);
                        p0[i] = v2 + p1[i];
                        p1[i] = v1;
                        p2[i] = v2;
                    }
                }
                default -> throw new AssertionError();
            }
        }
        int[] perm = RCT_PERMUTATIONS[tr.type() / 7];
        ModularChannel[] cs = {c0, c1, c2};
        for (int i = 0; i < 3; i++) {
            channels.set(tr.beginC() + perm[i], cs[i]);
        }
    }

    // -------------------------------------------------------------- palette

    /**
     * The 72 base delta-palette entries; each is used with both signs, and
     * entry 0 is a duplicate that is skipped by indexing.
     */
    private static final short[][] PALETTE_DELTA_BASE = {
        {0, 0, 0}, {4, 4, 4}, {11, 0, 0}, {0, 0, -13}, {0, -12, 0}, {-10, -10, -10},
        {-18, -18, -18}, {-27, -27, -27}, {-18, -18, 0}, {0, 0, -32}, {-32, 0, 0}, {-37, -37, -37},
        {0, -32, -32}, {24, 24, 45}, {50, 50, 50}, {-45, -24, -24}, {-24, -45, -45}, {0, -24, -24},
        {-34, -34, 0}, {-24, 0, -24}, {-45, -45, -24}, {64, 64, 64}, {-32, 0, -32}, {0, -32, 0},
        {-32, 0, 32}, {-24, -45, -24}, {45, 24, 45}, {24, -24, -45}, {-45, -24, 24}, {80, 80, 80},
        {64, 0, 0}, {0, 0, -64}, {0, -64, -64}, {-24, -24, 45}, {96, 96, 96}, {64, 64, 0},
        {45, -24, -24}, {34, -34, 0}, {112, 112, 112}, {24, -45, -45}, {45, 45, -24}, {0, -32, 32},
        {24, -24, 45}, {0, 96, 96}, {45, -24, 24}, {24, -45, -24}, {-24, -45, 24}, {0, -64, 0},
        {96, 0, 0}, {128, 128, 128}, {64, 0, 64}, {144, 144, 144}, {96, 96, 0}, {-36, -36, 36},
        {45, -24, -45}, {45, -45, -24}, {0, 0, -96}, {0, 128, 128}, {0, 96, 0}, {45, 24, -45},
        {-128, 0, 0}, {24, -45, 24}, {-45, 24, -45}, {64, 0, -64}, {64, -64, -64}, {96, 0, 96},
        {45, -45, 24}, {24, 45, -45}, {64, 64, -64}, {128, 128, 0}, {0, 0, -128}, {-24, 45, -45},
    };

    private static int paletteDelta(int index, int channel) {
        // index is in [0, 143]; even = positive entry, odd = negated
        int base = PALETTE_DELTA_BASE[index >> 1][channel];
        return (index & 1) != 0 ? -base : base;
    }

    private static final int DBG_X = Integer.getInteger("jxl.palX", -1);
    private static final int DBG_Y = Integer.getInteger("jxl.palY", -1);

    private static void inversePalette(ModularStream m, Transform.Palette tr) throws IOException {
        List<ModularChannel> channels = m.channels();
        int first = tr.beginC() + 1;
        int last = tr.beginC() + tr.numC();
        int bpp = m.paletteBpp();

        ModularChannel indexChannel = channels.get(first);
        // insert numC-1 fresh output channels before the index channel
        for (int i = first; i < last; i++) {
            channels.add(first, new ModularChannel(indexChannel.width, indexChannel.height,
                    indexChannel.hshift, indexChannel.vshift));
        }
        ModularChannel idxc = channels.get(last);
        int width = idxc.width;
        int height = idxc.height;

        for (int i = first; i < last; i++) {
            ModularChannel c = channels.get(i);
            if (!idxc.isEmpty()) {
                c.allocate();
            }
        }

        // negative indices are always deltas, so prediction may be needed even
        // when nbDeltas is zero
        boolean useWp = tr.dPred() == 6;
        WpState wp = useWp && !idxc.isEmpty() ? new WpState(m.wpParams(), width) : null;
        ModularChannel palette = channels.get(0);

        // single-channel palettes without deltas and with the Zero predictor
        // clamp the index instead of using the delta/implicit extensions
        boolean clampIndex = tr.numC() == 1 && tr.nbDeltas() == 0 && tr.dPred() == 0;

        for (int i = 0; i < tr.numC() && !idxc.isEmpty(); i++) {
            ModularChannel c = channels.get(first + i);
            int[] out = c.pixels;
            int[] idxPixels = idxc.pixels;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = idxPixels[row + x];
                    if (clampIndex) {
                        idx = Math.max(0, Math.min(idx, tr.nbColours() - 1));
                    }
                    boolean isDelta = idx < tr.nbDeltas();
                    long val;
                    if (idx < 0) { // hard-coded delta palette
                        if (i < 3) {
                            int k = (-idx - 1) % 143;
                            val = paletteDelta(k + 1, i);
                            if (bpp > 8) {
                                val <<= Math.min(bpp, 24) - 8;
                            }
                        } else {
                            val = 0;
                        }
                    } else if (idx < tr.nbColours()) {
                        val = palette.pixels[i * palette.width + idx];
                    } else if (i >= 3) {
                        val = 0; // implicit palette only defines colour channels
                    } else { // implicit palette
                        int v = idx - tr.nbColours();
                        if (v < 64) {
                            val = ((v >> (2 * i)) % 4) * ((1L << bpp) - 1) / 4
                                    + (1L << Math.max(0, bpp - 3));
                        } else {
                            v -= 64;
                            for (int j = 0; j < i; j++) {
                                v /= 5;
                            }
                            val = (v % 5) * ((1L << bpp) - 1) / 4;
                        }
                    }
                    if (m.regionMode && isDelta) {
                        // delta entries predict from previously reconstructed
                        // pixels, a chain that may cross undecoded areas
                        throw new com.ebremer.jpegxl.io.RegionUnsupportedException(
                                "delta palette entry used");
                    }
                    long vW = x > 0 ? out[row + x - 1] : (y > 0 ? out[row - width + x] : 0);
                    long vN = y > 0 ? out[row - width + x] : vW;
                    long vNW = (x > 0 && y > 0) ? out[row - width + x - 1] : vW;
                    long vNE = (x + 1 < width && y > 0) ? out[row - width + x + 1] : vN;
                    long vNN = y > 1 ? out[row - 2 * width + x] : vN;
                    long vNEE = (x + 2 < width && y > 0) ? out[row - width + x + 2] : vNE;
                    long vWW = x > 1 ? out[row + x - 2] : vW;
                    if (wp != null) {
                        wp.beforePredict(x, y, vW, vN, vNW, vNE, vNN);
                    }
                    if (isDelta) {
                        val += ModularStream.predict(tr.dPred(), wp, vW, vN, vNW, vNE, vNN, vNEE, vWW);
                    }
                    if (wp != null) {
                        wp.afterPredict(x, y, val);
                    }
                    if (x == DBG_X && y == DBG_Y) {
                        System.err.printf(
                                "[pal] beginC=%d numC=%d colours=%d i=%d idx=%d -> val=%d%n",
                                tr.beginC(), tr.numC(), tr.nbColours(), i, idx, val);
                    }
                    out[row + x] = (int) val;
                }
            }
            if (wp != null) {
                wp.reset();
            }
        }

        channels.remove(0); // drop the palette meta channel
    }

    // -------------------------------------------------------------- squeeze

    private static void inverseSqueeze(List<ModularChannel> channels, Transform.Squeeze sq) throws IOException {
        int numC = sq.numC();
        int resBase = sq.inPlace() ? sq.beginC() + numC : channels.size() - numC;
        for (int c = 0; c < numC; c++) {
            ModularChannel avg = channels.get(sq.beginC() + c);
            ModularChannel res = channels.get(resBase + c);
            ModularChannel out = sq.horizontal()
                    ? unsqueezeHorizontal(avg, res)
                    : unsqueezeVertical(avg, res);
            channels.set(sq.beginC() + c, out);
        }
        for (int c = 0; c < numC; c++) {
            channels.remove(resBase);
        }
    }

    private static ModularChannel unsqueezeHorizontal(ModularChannel avg, ModularChannel res) throws IOException {
        int w = avg.width + res.width;
        ModularChannel out = new ModularChannel(w, avg.height,
                avg.hshift > 0 ? avg.hshift - 1 : avg.hshift, avg.vshift);
        if (res.width != w / 2 || avg.width != w - w / 2 || res.height != avg.height) {
            throw new IOException("mismatched squeeze channel dimensions");
        }
        out.allocate();
        if (out.isEmpty()) {
            return out;
        }
        for (int y = 0; y < out.height; y++) {
            int rowA = y * avg.width;
            int rowR = y * res.width;
            int rowO = y * w;
            for (int x = 0; x < res.width; x++) {
                long diffMinusTendency = res.pixels[rowR + x];
                long a = avg.pixels[rowA + x];
                long nextAvg = x + 1 < avg.width ? avg.pixels[rowA + x + 1] : a;
                long left = x > 0 ? out.pixels[rowO + 2 * x - 1] : a;
                long diff = diffMinusTendency + smoothTendency(left, a, nextAvg);
                long first = a + diff / 2;
                out.pixels[rowO + 2 * x] = (int) first;
                out.pixels[rowO + 2 * x + 1] = (int) (first - diff);
            }
            if (avg.width > res.width) {
                out.pixels[rowO + 2 * res.width] = avg.pixels[rowA + res.width];
            }
        }
        return out;
    }

    private static ModularChannel unsqueezeVertical(ModularChannel avg, ModularChannel res) throws IOException {
        int h = avg.height + res.height;
        ModularChannel out = new ModularChannel(avg.width, h,
                avg.hshift, avg.vshift > 0 ? avg.vshift - 1 : avg.vshift);
        if (res.height != h / 2 || avg.height != h - h / 2 || res.width != avg.width) {
            throw new IOException("mismatched squeeze channel dimensions");
        }
        out.allocate();
        if (out.isEmpty()) {
            return out;
        }
        int w = out.width;
        for (int y = 0; y < res.height; y++) {
            for (int x = 0; x < w; x++) {
                long diffMinusTendency = res.pixels[y * w + x];
                long a = avg.pixels[y * w + x];
                long nextAvg = y + 1 < avg.height ? avg.pixels[(y + 1) * w + x] : a;
                long top = y > 0 ? out.pixels[(2 * y - 1) * w + x] : a;
                long diff = diffMinusTendency + smoothTendency(top, a, nextAvg);
                long first = a + diff / 2;
                out.pixels[2 * y * w + x] = (int) first;
                out.pixels[(2 * y + 1) * w + x] = (int) (first - diff);
            }
        }
        if (avg.height > res.height) {
            System.arraycopy(avg.pixels, res.height * w, out.pixels, 2 * res.height * w, w);
        }
        return out;
    }

    private static long smoothTendency(long b, long a, long n) {
        long diff = 0;
        if (b >= a && a >= n) {
            diff = (4 * b - 3 * n - a + 6) / 12;
            if (diff - (diff & 1) > 2 * (b - a)) {
                diff = 2 * (b - a) + 1;
            }
            if (diff + (diff & 1) > 2 * (a - n)) {
                diff = 2 * (a - n);
            }
        } else if (b <= a && a <= n) {
            diff = (4 * b - 3 * n - a - 6) / 12;
            if (diff + (diff & 1) < 2 * (b - a)) {
                diff = 2 * (b - a) - 1;
            }
            if (diff - (diff & 1) < 2 * (a - n)) {
                diff = 2 * (a - n);
            }
        }
        return diff;
    }
}
