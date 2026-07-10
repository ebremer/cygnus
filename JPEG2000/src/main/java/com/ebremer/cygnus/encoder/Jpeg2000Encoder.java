package com.ebremer.cygnus.encoder;

import com.ebremer.cygnus.codestream.Marker;
import com.ebremer.cygnus.t1.BlockEncoder;
import com.ebremer.cygnus.t2.PacketBitWriter;
import com.ebremer.cygnus.t2.TagTreeEncoder;
import com.ebremer.cygnus.wavelet.ForwardDWT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JPEG 2000 Part 1 (ISO/IEC 15444-1 / ITU-T T.800) codestream encoder, the
 * counterpart of {@link com.ebremer.cygnus.decoder.Jpeg2000Decoder}.
 *
 * <p>Produces a single-layer LRCP codestream with default (maximal)
 * precincts and one tile-part per tile. Two pipelines are supported:</p>
 * <ul>
 *   <li><b>Reversible</b> (default): RCT + 5/3 integer wavelet, no
 *       quantization - decoding reproduces the input samples exactly.</li>
 *   <li><b>Irreversible</b>: ICT + 9/7 wavelet with scalar-expounded
 *       dead-zone quantization; {@link Params#quality} scales the step
 *       sizes (1.0 = finest steps, near-lossless; smaller = coarser steps,
 *       stronger compression).</li>
 * </ul>
 *
 * <p>Tiling, component subsampling, bit depths up to 16, SOP/EPH markers
 * and images wider than one precinct (32768) per resolution are supported.
 * Code-blocks are entropy-encoded on worker threads (see
 * {@link #setParallelism}); tiles are streamed to the output one at a
 * time, so peak memory scales with the tile size, not the image size.</p>
 */
public final class Jpeg2000Encoder {

    /** Return false from {@link #tileEncoded} to abort encoding. */
    public interface ProgressListener {
        boolean tileEncoded(int tilesDone, int tileTotal);
    }

    /** Encoding parameters; plain fields with sensible defaults. */
    public static final class Params {
        /** Image size on the reference grid (offsets are always zero). */
        public int width, height;
        /** Nominal tile size; 0 = one tile covering the whole image. */
        public int tileWidth, tileHeight;
        /** Decomposition levels; -1 picks min(5, floor(log2(min tile dim))). */
        public int levels = -1;
        /** Code-block size exponents (T.800 limits: 2..10, sum &lt;= 12). */
        public int xcb = 6, ycb = 6;
        /** True for the exact 5/3 path, false for the quantized 9/7 path. */
        public boolean reversible = true;
        /** Step-size scale for the irreversible path, in (0, 1]. */
        public float quality = 1.0f;
        /** Component transform (RCT/ICT on components 0-2); null = auto. */
        public Boolean mct;
        /** Emit SOP marker segments before each packet. */
        public boolean sop;
        /** Emit EPH markers after each packet header. */
        public boolean eph;
        /** Quantizer guard bits (1..7). */
        public int guardBits = 2;
        /** Bit depth per component (unsigned samples), 1..26. */
        public int[] precision;
        /** Per-component subsampling factors; null = no subsampling. */
        public int[] xr, yr;
    }

    private static final int PP = 15;   // maximal precinct exponent (default)

    /**
     * L2 norms of the 9/7 synthesis basis vectors per decomposition depth
     * (the OpenJPEG weighting table), used to shape per-band step sizes.
     * Indexed [orientation][level] with orientation 0=LL, 1=HL, 2=LH, 3=HH.
     */
    private static final double[][] NORMS_97 = {
        {1.000, 1.965, 4.177, 8.403, 16.90, 33.84, 67.69, 135.3, 270.6, 540.9},
        {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0, 1098.0},
        {2.022, 3.989, 8.355, 17.04, 34.27, 68.63, 137.3, 274.6, 549.0, 1098.0},
        {2.080, 3.865, 8.307, 17.18, 34.71, 69.59, 139.3, 278.6, 557.2, 1114.0}
    };

    private final int width, height;
    private final int xtsiz, ytsiz;
    private final int levels;
    private final int xcb, ycb;
    private final boolean reversible;
    private final boolean mct;
    private final boolean sop, eph;
    private final int guard;
    private final int nc;
    private final int[] precision;
    private final int[] xr, yr;
    /** Per linear subband index (0..3*levels): quantization exponent/mantissa. */
    private final int[] epsLin, muLin;

    private ProgressListener listener;
    private int parallelism = Runtime.getRuntime().availableProcessors();

    public Jpeg2000Encoder(Params p) {
        if (p.width <= 0 || p.height <= 0) {
            throw new IllegalArgumentException("Empty image: "
                    + p.width + "x" + p.height);
        }
        if (p.precision == null || p.precision.length == 0) {
            throw new IllegalArgumentException("No components");
        }
        this.nc = p.precision.length;
        if (nc > 16384) {
            throw new IllegalArgumentException("Too many components: " + nc);
        }
        this.width = p.width;
        this.height = p.height;
        this.xtsiz = p.tileWidth > 0 ? p.tileWidth : p.width;
        this.ytsiz = p.tileHeight > 0 ? p.tileHeight : p.height;
        long tiles = (long) ceilDiv(width, xtsiz) * ceilDiv(height, ytsiz);
        if (tiles > 65535) {
            throw new IllegalArgumentException("Tile grid of " + tiles
                    + " tiles exceeds the 65535 the SOT marker can index");
        }
        if (p.xcb < 2 || p.xcb > 10 || p.ycb < 2 || p.ycb > 10
                || p.xcb + p.ycb > 12) {
            throw new IllegalArgumentException("Invalid code-block size exponents "
                    + p.xcb + "/" + p.ycb);
        }
        this.xcb = p.xcb;
        this.ycb = p.ycb;
        if (p.guardBits < 1 || p.guardBits > 7) {
            throw new IllegalArgumentException("Guard bits out of range: "
                    + p.guardBits);
        }
        this.guard = p.guardBits;
        this.reversible = p.reversible;
        this.sop = p.sop;
        this.eph = p.eph;
        this.precision = p.precision.clone();
        this.xr = p.xr != null ? p.xr.clone() : ones(nc);
        this.yr = p.yr != null ? p.yr.clone() : ones(nc);
        if (this.xr.length != nc || this.yr.length != nc) {
            throw new IllegalArgumentException("Subsampling arrays must match components");
        }
        for (int c = 0; c < nc; c++) {
            if (this.xr[c] < 1 || this.xr[c] > 255 || this.yr[c] < 1 || this.yr[c] > 255) {
                throw new IllegalArgumentException("Subsampling factor out of range");
            }
        }

        boolean canMct = nc >= 3
                && this.xr[0] == this.xr[1] && this.xr[0] == this.xr[2]
                && this.yr[0] == this.yr[1] && this.yr[0] == this.yr[2];
        if (p.mct != null && p.mct && !canMct) {
            throw new IllegalArgumentException(
                    "Component transform needs 3 equally sampled components");
        }
        this.mct = p.mct != null ? p.mct : canMct;

        int maxPrec = 0;
        for (int c = 0; c < nc; c++) {
            if (precision[c] < 1 || precision[c] > 26) {
                throw new IllegalArgumentException("Component precision "
                        + precision[c] + " out of the supported 1..26 range");
            }
            maxPrec = Math.max(maxPrec, precision[c]);
        }

        if (p.levels >= 0) {
            if (p.levels > 32) {
                throw new IllegalArgumentException("More than 32 decomposition levels");
            }
            this.levels = p.levels;
        } else {
            int minDim = Integer.MAX_VALUE;
            for (int c = 0; c < nc; c++) {
                minDim = Math.min(minDim, ceilDiv(Math.min(xtsiz, width), this.xr[c]));
                minDim = Math.min(minDim, ceilDiv(Math.min(ytsiz, height), this.yr[c]));
            }
            this.levels = Math.max(0, Math.min(5,
                    31 - Integer.numberOfLeadingZeros(Math.max(1, minDim))));
        }

        // quantization exponents/mantissas per subband
        int numBands = 3 * levels + 1;
        this.epsLin = new int[numBands];
        this.muLin = new int[numBands];
        if (reversible) {
            int basePrec = maxPrec + (mct ? 1 : 0);
            int epsMax = basePrec + 2 + 1;
            if (guard + epsMax - 1 > 31) {
                throw new IllegalArgumentException("Precision " + maxPrec
                        + " with " + guard + " guard bits exceeds 31 bit-planes");
            }
            for (int b = 0; b < numBands; b++) {
                epsLin[b] = basePrec + gainOf(b) + 1;
            }
        } else {
            float q = Math.max(0.0f, Math.min(1.0f, p.quality));
            double scale = Math.pow(2.0, 7.0 * (1.0 - q) - 10.0);
            for (int b = 0; b < numBands; b++) {
                int r = resOf(b);
                int orient = orientOf(b);
                int gain = gainOf(b);
                int level = Math.min(9, r == 0 ? levels : levels - r);
                double norm = NORMS_97[orient][level];
                double step = scale * Math.pow(2.0, maxPrec + gain) / norm;
                // express as 2^(Rb - eps) * (1 + mu/2048) with Rb = maxPrec + gain
                int e = (int) Math.floor(Math.log(step) / Math.log(2.0));
                int eps = maxPrec + gain - e;
                int mu = (int) Math.round((step / Math.pow(2.0, e) - 1.0) * 2048.0);
                if (mu == 2048) {
                    mu = 0;
                    eps--;
                }
                eps = Math.max(0, Math.min(Math.min(31, 32 - guard), eps));
                epsLin[b] = eps;
                muLin[b] = Math.max(0, Math.min(2047, mu));
            }
        }
    }

    private static int[] ones(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, 1);
        return a;
    }

    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }

    /**
     * Maximum worker threads used while encoding (Tier-1 code-block coding
     * and per-component wavelet analysis fan out onto the common ForkJoin
     * pool). Default: the number of available processors; 1 encodes on the
     * calling thread only. Encoded output is identical at any setting.
     */
    public void setParallelism(int threads) {
        this.parallelism = Math.max(1, threads);
    }

    /** Linear subband index -> owning resolution level. */
    private static int resOf(int b) {
        return b == 0 ? 0 : (b - 1) / 3 + 1;
    }

    /** Linear subband index -> orientation (0 LL, 1 HL, 2 LH, 3 HH). */
    private static int orientOf(int b) {
        return b == 0 ? 0 : (b - 1) % 3 + 1;
    }

    /** Linear subband index -> log2 gain (E-3). */
    private static int gainOf(int b) {
        return b == 0 ? 0 : ((b - 1) % 3 == 2 ? 2 : 1);
    }

    private static int ceilDiv(int a, int b) {
        return (int) Math.ceilDiv((long) a, (long) b);
    }

    private static int ceilShift(int x, int s) {
        return (int) Math.ceilDiv(x, 1L << s);
    }

    /**
     * Encodes per-component sample planes (unsigned values, row-major, sized
     * {@code ceil(width/xr) x ceil(height/yr)}) into a raw JPEG 2000
     * codestream on {@code out}.
     *
     * @return false if a progress listener aborted the encode (the output
     *         ends after the last completed tile and is not a valid stream)
     */
    public boolean encode(int[][] comps, OutputStream out) throws IOException {
        if (comps.length != nc) {
            throw new IllegalArgumentException("Expected " + nc
                    + " component planes, got " + comps.length);
        }
        for (int c = 0; c < nc; c++) {
            long expect = (long) ceilDiv(width, xr[c]) * ceilDiv(height, yr[c]);
            if (comps[c].length != expect) {
                throw new IllegalArgumentException("Component " + c + " plane has "
                        + comps[c].length + " samples, expected " + expect);
            }
        }
        w16(out, Marker.SOC);
        writeSiz(out);
        writeCod(out);
        writeQcd(out);
        int ntx = ceilDiv(width, xtsiz);
        int nty = ceilDiv(height, ytsiz);
        int total = ntx * nty;
        for (int t = 0; t < total; t++) {
            byte[] body = encodeTile(comps, t, ntx);
            w16(out, Marker.SOT);
            w16(out, 10);
            w16(out, t);
            w32(out, 12 + 2 + body.length); // Psot
            w8(out, 0);                     // TPsot
            w8(out, 1);                     // TNsot
            w16(out, Marker.SOD);
            out.write(body);
            if (listener != null && !listener.tileEncoded(t + 1, total)) {
                return false;
            }
        }
        w16(out, Marker.EOC);
        return true;
    }

    // ---- main header ----

    private void writeSiz(OutputStream out) throws IOException {
        w16(out, Marker.SIZ);
        w16(out, 38 + 3 * nc);
        w16(out, 0);            // Rsiz: full Part 1 profile
        w32(out, width);
        w32(out, height);
        w32(out, 0);            // XOsiz
        w32(out, 0);            // YOsiz
        w32(out, xtsiz);
        w32(out, ytsiz);
        w32(out, 0);            // XTOsiz
        w32(out, 0);            // YTOsiz
        w16(out, nc);
        for (int c = 0; c < nc; c++) {
            w8(out, precision[c] - 1);  // unsigned
            w8(out, xr[c]);
            w8(out, yr[c]);
        }
    }

    private void writeCod(OutputStream out) throws IOException {
        w16(out, Marker.COD);
        w16(out, 12);
        w8(out, (sop ? 0x02 : 0) | (eph ? 0x04 : 0)); // Scod
        w8(out, 0);                    // LRCP
        w16(out, 1);                   // one quality layer
        w8(out, mct ? 1 : 0);
        w8(out, levels);
        w8(out, xcb - 2);
        w8(out, ycb - 2);
        w8(out, 0);                    // code-block style
        w8(out, reversible ? 1 : 0);   // wavelet: 1 = 5/3, 0 = 9/7
    }

    private void writeQcd(OutputStream out) throws IOException {
        int numBands = 3 * levels + 1;
        w16(out, Marker.QCD);
        if (reversible) {
            w16(out, 3 + numBands);
            w8(out, guard << 5);       // style 0: no quantization
            for (int b = 0; b < numBands; b++) {
                w8(out, epsLin[b] << 3);
            }
        } else {
            w16(out, 3 + 2 * numBands);
            w8(out, 0x02 | (guard << 5)); // scalar expounded
            for (int b = 0; b < numBands; b++) {
                w16(out, (epsLin[b] << 11) | muLin[b]);
            }
        }
    }

    // ---- per tile ----

    /** One subband's coefficients (quantizer indices on the 9/7 path). */
    private record Bnd(int orient, int x0, int y0, int x1, int y1, int[] data) {
        int w() {
            return x1 - x0;
        }
    }

    /** Code-block grid of one precinct-band with its encoded blocks. */
    private static final class PrecBand {
        int nw, nh;
        BlockEncoder.EncodedBlock[] blocks;
    }

    private byte[] encodeTile(int[][] comps, int t, int ntx) {
        int tp = t % ntx;
        int tq = t / ntx;
        int tx0 = tp * xtsiz;
        int ty0 = tq * ytsiz;
        int tx1 = Math.min(tx0 + xtsiz, width);
        int ty1 = Math.min(ty0 + ytsiz, height);

        int[] tcx0 = new int[nc], tcy0 = new int[nc], tcx1 = new int[nc], tcy1 = new int[nc];
        for (int c = 0; c < nc; c++) {
            tcx0[c] = ceilDiv(tx0, xr[c]);
            tcy0[c] = ceilDiv(ty0, yr[c]);
            tcx1[c] = ceilDiv(tx1, xr[c]);
            tcy1[c] = ceilDiv(ty1, yr[c]);
        }

        // extract + DC shift + component transform + DWT (+ quantization)
        Bnd[][][] bands = new Bnd[nc][][];
        if (reversible) {
            int[][] tc = new int[nc][];
            for (int c = 0; c < nc; c++) {
                tc[c] = extractInt(comps[c], c, tcx0[c], tcy0[c], tcx1[c], tcy1[c]);
            }
            if (mct) {
                forwardRct(tc[0], tc[1], tc[2]);
            }
            List<Runnable> dwt = new ArrayList<>(nc);
            for (int c = 0; c < nc; c++) {
                final int fc = c;
                dwt.add(() -> bands[fc] = decomposeInt(
                        tc[fc], tcx0[fc], tcy0[fc], tcx1[fc], tcy1[fc]));
            }
            runTasks(dwt);
        } else {
            float[][] tc = new float[nc][];
            for (int c = 0; c < nc; c++) {
                tc[c] = extractFloat(comps[c], c, tcx0[c], tcy0[c], tcx1[c], tcy1[c]);
            }
            if (mct) {
                forwardIct(tc[0], tc[1], tc[2]);
            }
            List<Runnable> dwt = new ArrayList<>(nc);
            for (int c = 0; c < nc; c++) {
                final int fc = c;
                dwt.add(() -> bands[fc] = decomposeFloat(
                        tc[fc], fc, tcx0[fc], tcy0[fc], tcx1[fc], tcy1[fc]));
            }
            runTasks(dwt);
        }

        // build the precinct plans and encode all code-blocks
        PrecBand[][][][] plans = new PrecBand[levels + 1][nc][][];
        List<Runnable> blockTasks = new ArrayList<>();
        for (int r = 0; r <= levels; r++) {
            for (int c = 0; c < nc; c++) {
                plans[r][c] = planResolution(bands[c][r], r,
                        tcx0[c], tcy0[c], tcx1[c], tcy1[c], blockTasks);
            }
        }
        runTasks(blockTasks);

        // serialize the packets in LRCP order
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int sopCounter = 0;
        for (int r = 0; r <= levels; r++) {
            for (int c = 0; c < nc; c++) {
                PrecBand[][] precs = plans[r][c];
                for (PrecBand[] prec : precs) {
                    writePacket(body, prec, sopCounter++ & 0xFFFF);
                }
            }
        }
        return body.toByteArray();
    }

    /** Tile-component samples as DC-shifted ints. */
    private int[] extractInt(int[] plane, int c, int x0, int y0, int x1, int y1) {
        int tw = Math.max(0, x1 - x0);
        int th = Math.max(0, y1 - y0);
        int cw = ceilDiv(width, xr[c]);
        int shift = 1 << (precision[c] - 1);
        int[] s = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int src = (y0 + y) * cw + x0;
            int dst = y * tw;
            for (int x = 0; x < tw; x++) {
                s[dst + x] = plane[src + x] - shift;
            }
        }
        return s;
    }

    /** Tile-component samples as DC-shifted floats. */
    private float[] extractFloat(int[] plane, int c, int x0, int y0, int x1, int y1) {
        int tw = Math.max(0, x1 - x0);
        int th = Math.max(0, y1 - y0);
        int cw = ceilDiv(width, xr[c]);
        int shift = 1 << (precision[c] - 1);
        float[] s = new float[tw * th];
        for (int y = 0; y < th; y++) {
            int src = (y0 + y) * cw + x0;
            int dst = y * tw;
            for (int x = 0; x < tw; x++) {
                s[dst + x] = plane[src + x] - shift;
            }
        }
        return s;
    }

    /** Forward reversible component transform (G-1/G-2). */
    private static void forwardRct(int[] r, int[] g, int[] b) {
        for (int i = 0; i < r.length; i++) {
            int y0 = (r[i] + 2 * g[i] + b[i]) >> 2;
            int y1 = b[i] - g[i];
            int y2 = r[i] - g[i];
            r[i] = y0;
            g[i] = y1;
            b[i] = y2;
        }
    }

    /** Forward irreversible component transform (G-5). */
    private static void forwardIct(float[] r, float[] g, float[] b) {
        for (int i = 0; i < r.length; i++) {
            float rv = r[i], gv = g[i], bv = b[i];
            r[i] = 0.299f * rv + 0.587f * gv + 0.114f * bv;
            g[i] = -0.168736f * rv - 0.331264f * gv + 0.5f * bv;
            b[i] = 0.5f * rv - 0.418688f * gv - 0.081312f * bv;
        }
    }

    // ---- forward wavelet + band extraction ----

    private Bnd[][] decomposeInt(int[] samples, int tcx0, int tcy0, int tcx1, int tcy1) {
        Bnd[][] result = new Bnd[levels + 1][];
        int[] cur = samples;
        int cx0 = tcx0, cy0 = tcy0, cx1 = tcx1, cy1 = tcy1;
        for (int r = levels; r >= 1; r--) {
            int wd = cx1 - cx0;
            int ht = cy1 - cy0;
            ForwardDWT.analyzeInt(cur, wd, ht, cx0, cy0);
            int nb = levels - r + 1;
            int px0 = ceilShift(cx0, 1), py0 = ceilShift(cy0, 1);
            int px1 = ceilShift(cx1, 1), py1 = ceilShift(cy1, 1);
            int[] ll = new int[Math.max(0, px1 - px0) * Math.max(0, py1 - py0)];
            Bnd hl = makeBnd(1, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd lh = makeBnd(2, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd hh = makeBnd(3, nb, tcx0, tcy0, tcx1, tcy1);
            int lw = px1 - px0;
            for (int v = py0; v < py1; v++) {
                for (int u = px0; u < px1; u++) {
                    ll[(v - py0) * lw + (u - px0)] = cur[(2 * v - cy0) * wd + (2 * u - cx0)];
                }
            }
            fillInt(hl, cur, wd, cx0, cy0, 1, 0);
            fillInt(lh, cur, wd, cx0, cy0, 0, 1);
            fillInt(hh, cur, wd, cx0, cy0, 1, 1);
            result[r] = new Bnd[] {hl, lh, hh};
            cur = ll;
            cx0 = px0;
            cy0 = py0;
            cx1 = px1;
            cy1 = py1;
        }
        result[0] = new Bnd[] {new Bnd(0, cx0, cy0, cx1, cy1, cur)};
        return result;
    }

    private Bnd[][] decomposeFloat(float[] samples, int comp,
                                   int tcx0, int tcy0, int tcx1, int tcy1) {
        Bnd[][] result = new Bnd[levels + 1][];
        float[] cur = samples;
        int cx0 = tcx0, cy0 = tcy0, cx1 = tcx1, cy1 = tcy1;
        for (int r = levels; r >= 1; r--) {
            int wd = cx1 - cx0;
            int ht = cy1 - cy0;
            ForwardDWT.analyzeFloat(cur, wd, ht, cx0, cy0);
            int nb = levels - r + 1;
            int px0 = ceilShift(cx0, 1), py0 = ceilShift(cy0, 1);
            int px1 = ceilShift(cx1, 1), py1 = ceilShift(cy1, 1);
            float[] ll = new float[Math.max(0, px1 - px0) * Math.max(0, py1 - py0)];
            Bnd hl = makeBnd(1, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd lh = makeBnd(2, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd hh = makeBnd(3, nb, tcx0, tcy0, tcx1, tcy1);
            int lw = px1 - px0;
            for (int v = py0; v < py1; v++) {
                for (int u = px0; u < px1; u++) {
                    ll[(v - py0) * lw + (u - px0)] = cur[(2 * v - cy0) * wd + (2 * u - cx0)];
                }
            }
            quantize(hl, cur, wd, cx0, cy0, 1, 0, comp, r);
            quantize(lh, cur, wd, cx0, cy0, 0, 1, comp, r);
            quantize(hh, cur, wd, cx0, cy0, 1, 1, comp, r);
            result[r] = new Bnd[] {hl, lh, hh};
            cur = ll;
            cx0 = px0;
            cy0 = py0;
            cx1 = px1;
            cy1 = py1;
        }
        Bnd llBnd = new Bnd(0, cx0, cy0, cx1, cy1,
                new int[Math.max(0, cx1 - cx0) * Math.max(0, cy1 - cy0)]);
        quantizeLl(llBnd, cur, comp);
        result[0] = new Bnd[] {llBnd};
        return result;
    }

    private Bnd makeBnd(int orient, int nb, int tcx0, int tcy0, int tcx1, int tcy1) {
        int xob = (orient == 1 || orient == 3) ? 1 : 0;
        int yob = (orient == 2 || orient == 3) ? 1 : 0;
        int half = 1 << (nb - 1);
        int x0 = ceilShift(tcx0 - half * xob, nb);
        int y0 = ceilShift(tcy0 - half * yob, nb);
        int x1 = ceilShift(tcx1 - half * xob, nb);
        int y1 = ceilShift(tcy1 - half * yob, nb);
        return new Bnd(orient, x0, y0, x1, y1,
                new int[Math.max(0, x1 - x0) * Math.max(0, y1 - y0)]);
    }

    private void fillInt(Bnd b, int[] a, int wd, int u0, int v0, int xo, int yo) {
        for (int v = b.y0; v < b.y1; v++) {
            for (int u = b.x0; u < b.x1; u++) {
                b.data[(v - b.y0) * b.w() + (u - b.x0)]
                        = a[(2 * v + yo - v0) * wd + (2 * u + xo - u0)];
            }
        }
    }

    /**
     * Absolute quantization step for a subband and component, derived from
     * the coded (eps, mu) exactly as the decoder derives it (E-3).
     */
    private float stepFor(int comp, int r, int orient) {
        int idx = r == 0 ? 0 : 3 * (r - 1) + orient;
        int rb = precision[comp] + gainOf(idx);
        return (float) (Math.pow(2.0, rb - epsLin[idx])
                * (1.0 + muLin[idx] / 2048.0));
    }

    /** Dead-zone quantization of one high band from the interleaved array. */
    private void quantize(Bnd b, float[] a, int wd, int u0, int v0, int xo, int yo,
                          int comp, int r) {
        int idx = 3 * (r - 1) + b.orient;
        float inv = 1.0f / stepFor(comp, r, b.orient);
        int limit = (int) ((1L << (guard + epsLin[idx] - 1)) - 1);
        for (int v = b.y0; v < b.y1; v++) {
            for (int u = b.x0; u < b.x1; u++) {
                float c = a[(2 * v + yo - v0) * wd + (2 * u + xo - u0)];
                int q = (int) Math.min(limit, (long) (Math.abs(c) * inv));
                b.data[(v - b.y0) * b.w() + (u - b.x0)] = c < 0 ? -q : q;
            }
        }
    }

    /** Dead-zone quantization of the final LL band. */
    private void quantizeLl(Bnd b, float[] a, int comp) {
        float inv = 1.0f / stepFor(comp, 0, 0);
        int limit = (int) ((1L << (guard + epsLin[0] - 1)) - 1);
        for (int i = 0; i < b.data.length; i++) {
            float c = a[i];
            int q = (int) Math.min(limit, (long) (Math.abs(c) * inv));
            b.data[i] = c < 0 ? -q : q;
        }
    }

    // ---- precinct planning and Tier-1 ----

    /**
     * Builds the precinct grid of one tile-component resolution (mirroring
     * the decoder's Resolution/Precinct construction with maximal precinct
     * sizes) and queues one Tier-1 encoding task per code-block.
     *
     * @return precincts in raster order; each holds per-band block grids
     */
    private PrecBand[][] planResolution(Bnd[] bnds, int r,
                                        int tcx0, int tcy0, int tcx1, int tcy1,
                                        List<Runnable> tasks) {
        int nd = levels - r;
        int trx0 = ceilShift(tcx0, nd);
        int try0 = ceilShift(tcy0, nd);
        int trx1 = ceilShift(tcx1, nd);
        int try1 = ceilShift(tcy1, nd);
        if (trx1 <= trx0 || try1 <= try0) {
            return new PrecBand[0][]; // empty resolution: no precincts, no packets
        }
        int gridX0 = trx0 >> PP;
        int gridY0 = try0 >> PP;
        int npw = ceilShift(trx1, PP) - gridX0;
        int nph = ceilShift(try1, PP) - gridY0;
        int shift = r > 0 ? 1 : 0;
        PrecBand[][] precs = new PrecBand[npw * nph][];
        for (int j = 0; j < nph; j++) {
            for (int i = 0; i < npw; i++) {
                long ux0 = ((long) (gridX0 + i)) << PP;
                long uy0 = ((long) (gridY0 + j)) << PP;
                long ux1 = ux0 + (1L << PP);
                long uy1 = uy0 + (1L << PP);
                PrecBand[] perBand = new PrecBand[bnds.length];
                for (int bi = 0; bi < bnds.length; bi++) {
                    Bnd band = bnds[bi];
                    int pbx0 = (int) Math.max(band.x0, ux0 >> shift);
                    int pbx1 = (int) Math.min(band.x1, ux1 >> shift);
                    int pby0 = (int) Math.max(band.y0, uy0 >> shift);
                    int pby1 = (int) Math.min(band.y1, uy1 >> shift);
                    int cbx = Math.max(0, Math.min(xcb, PP - shift));
                    int cby = Math.max(0, Math.min(ycb, PP - shift));
                    PrecBand pb = new PrecBand();
                    pb.nw = (pbx1 > pbx0) ? ceilShift(pbx1, cbx) - (pbx0 >> cbx) : 0;
                    pb.nh = (pby1 > pby0) ? ceilShift(pby1, cby) - (pby0 >> cby) : 0;
                    pb.blocks = new BlockEncoder.EncodedBlock[pb.nw * pb.nh];
                    int idx = r == 0 ? 0 : 3 * (r - 1) + band.orient;
                    int mb = guard + epsLin[idx] - 1;
                    for (int by = 0; by < pb.nh; by++) {
                        for (int bx = 0; bx < pb.nw; bx++) {
                            int gx = (pbx0 >> cbx) + bx;
                            int gy = (pby0 >> cby) + by;
                            int bx0 = Math.max(pbx0, gx << cbx);
                            int bx1 = Math.min(pbx1, (gx + 1) << cbx);
                            int by0 = Math.max(pby0, gy << cby);
                            int by1 = Math.min(pby1, (gy + 1) << cby);
                            int slot = by * pb.nw + bx;
                            tasks.add(() -> pb.blocks[slot] = BlockEncoder.encode(
                                    band.data, band.w(), bx0 - band.x0, by0 - band.y0,
                                    bx1 - bx0, by1 - by0, band.orient, mb));
                        }
                    }
                    perBand[bi] = pb;
                }
                precs[j * npw + i] = perBand;
            }
        }
        return precs;
    }

    /** Runs independent encode tasks, honoring {@link #setParallelism}. */
    private void runTasks(List<Runnable> tasks) {
        int n = Math.min(parallelism, tasks.size());
        if (n <= 1) {
            for (Runnable t : tasks) {
                t.run();
            }
            return;
        }
        java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger();
        Runnable worker = () -> {
            int i;
            while ((i = next.getAndIncrement()) < tasks.size()) {
                tasks.get(i).run();
            }
        };
        List<java.util.concurrent.ForkJoinTask<?>> spawned = new ArrayList<>(n - 1);
        for (int k = 1; k < n; k++) {
            spawned.add(java.util.concurrent.ForkJoinPool.commonPool().submit(worker));
        }
        worker.run();
        for (java.util.concurrent.ForkJoinTask<?> f : spawned) {
            f.join();
        }
    }

    // ---- packet serialization (T.800 B.10) ----

    private void writePacket(ByteArrayOutputStream body, PrecBand[] prec, int sopIndex) {
        boolean anyIncluded = false;
        for (PrecBand pb : prec) {
            for (BlockEncoder.EncodedBlock blk : pb.blocks) {
                if (blk != null) {
                    anyIncluded = true;
                    break;
                }
            }
        }
        if (sop) {
            w16(body, Marker.SOP);
            w16(body, 4);
            w16(body, sopIndex);
        }
        PacketBitWriter hw = new PacketBitWriter();
        if (!anyIncluded) {
            hw.bit(0);
        } else {
            hw.bit(1);
            for (PrecBand pb : prec) {
                if (pb.nw * pb.nh == 0) {
                    continue;
                }
                int[] inclValues = new int[pb.nw * pb.nh];
                int[] zbValues = new int[pb.nw * pb.nh];
                for (int k = 0; k < pb.blocks.length; k++) {
                    inclValues[k] = pb.blocks[k] != null ? 0 : 1;
                    zbValues[k] = pb.blocks[k] != null ? pb.blocks[k].zeroBitplanes() : 255;
                }
                TagTreeEncoder incl = new TagTreeEncoder(pb.nw, pb.nh, inclValues);
                TagTreeEncoder msb = new TagTreeEncoder(pb.nw, pb.nh, zbValues);
                for (int j = 0; j < pb.nh; j++) {
                    for (int i = 0; i < pb.nw; i++) {
                        BlockEncoder.EncodedBlock blk = pb.blocks[j * pb.nw + i];
                        incl.encode(hw, i, j, 1);
                        if (blk == null) {
                            continue;
                        }
                        msb.encodeValue(hw, i, j);
                        encodePassCount(hw, blk.passes());
                        int lenBits = 3 + (31 - Integer.numberOfLeadingZeros(blk.passes()));
                        while (blk.data().length >= (1 << lenBits)) {
                            hw.bit(1);
                            lenBits++;
                        }
                        hw.bit(0);
                        hw.bits(blk.data().length, lenBits);
                    }
                }
            }
        }
        body.writeBytes(hw.finish());
        if (eph) {
            w16(body, Marker.EPH);
        }
        for (PrecBand pb : prec) {
            for (BlockEncoder.EncodedBlock blk : pb.blocks) {
                if (blk != null) {
                    body.writeBytes(blk.data());
                }
            }
        }
    }

    /** Number-of-coding-passes code, T.800 Table B.4. */
    private static void encodePassCount(PacketBitWriter hw, int n) {
        if (n == 1) {
            hw.bit(0);
        } else if (n == 2) {
            hw.bits(0b10, 2);
        } else if (n <= 5) {
            hw.bits(0b11, 2);
            hw.bits(n - 3, 2);
        } else if (n <= 36) {
            hw.bits(0b1111, 4);
            hw.bits(n - 6, 5);
        } else {
            hw.bits(0b1111, 4);
            hw.bits(31, 5);
            hw.bits(n - 37, 7);
        }
    }

    // ---- byte helpers ----

    private static void w8(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
    }

    private static void w8(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
    }

    private static void w16(OutputStream o, int v) throws IOException {
        w8(o, v >> 8);
        w8(o, v);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        w8(o, v >> 8);
        w8(o, v);
    }

    private static void w32(OutputStream o, long v) throws IOException {
        w16(o, (int) (v >> 16));
        w16(o, (int) v);
    }
}
