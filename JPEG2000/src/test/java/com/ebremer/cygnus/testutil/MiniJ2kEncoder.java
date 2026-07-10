package com.ebremer.cygnus.testutil;

import com.ebremer.cygnus.t1.BlockDecoder;
import com.ebremer.cygnus.t1.MQEncoder;
import com.ebremer.cygnus.t1.Passes;
import com.ebremer.cygnus.t2.PacketBitWriter;
import com.ebremer.cygnus.t2.TagTreeEncoder;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-scope JPEG 2000 encoder: reversible 5/3 path, single quality layer,
 * LRCP progression, default (maximal) precincts, one tile-part per tile.
 * Supports tiling, image/tile grid offsets, component subsampling, RCT and
 * SOP/EPH markers - enough surface to exercise the decoder end to end with
 * exact lossless round-trips.
 */
public final class MiniJ2kEncoder {

    public static final class Config {
        public int width, height;            // image region size
        public int xosiz, yosiz;             // image offset on the reference grid
        public int xtsiz, ytsiz;             // 0 = one tile covering the image
        public int xtosiz, ytosiz;
        public int levels = 3;
        public int xcb = 6, ycb = 6;         // code-block size exponents
        public boolean rct;
        public boolean sopEph;
        public int[] precision;              // per component
        public int[] xr, yr;                 // per component subsampling
        public int guard = 2;
    }

    private final Config cfg;
    private final int nc;
    private final int xsiz, ysiz, xtsiz, ytsiz;
    private final int[] eps = new int[4];    // exponent per gain class 0..2 (index by gain)
    private int sopCounter;

    private MiniJ2kEncoder(Config cfg) {
        this.cfg = cfg;
        this.nc = cfg.precision.length;
        this.xsiz = cfg.xosiz + cfg.width;
        this.ysiz = cfg.yosiz + cfg.height;
        this.xtsiz = cfg.xtsiz > 0 ? cfg.xtsiz : xsiz - cfg.xtosiz;
        this.ytsiz = cfg.ytsiz > 0 ? cfg.ytsiz : ysiz - cfg.ytosiz;
        int maxPrec = 0;
        for (int p : cfg.precision) {
            maxPrec = Math.max(maxPrec, p);
        }
        if (cfg.rct) {
            maxPrec++;
        }
        for (int gain = 0; gain <= 2; gain++) {
            eps[gain] = maxPrec + gain + 1;
        }
    }

    /** Encodes image-region component planes (unsigned samples) to a codestream. */
    public static byte[] encode(int[][] comps, Config cfg) {
        return new MiniJ2kEncoder(cfg).run(comps);
    }

    // ---- helpers ----

    private static int ceilDiv(int a, int b) {
        return (int) Math.ceilDiv((long) a, (long) b);
    }

    private static int ceilShift(int x, int s) {
        return (int) Math.ceilDiv(x, 1L << s);
    }

    private static void w8(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, long v) {
        w16(o, (int) (v >> 16));
        w16(o, (int) v);
    }

    private static int mirror(int j, int n) {
        if (n == 1) {
            return 0;
        }
        int period = 2 * (n - 1);
        j = Math.floorMod(j, period);
        return j < n ? j : period - j;
    }

    // ---- top level ----

    private byte[] run(int[][] comps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w16(out, 0xFF4F); // SOC
        writeSiz(out);
        writeCod(out);
        writeQcd(out);
        int ntx = ceilDiv(xsiz - cfg.xtosiz, xtsiz);
        int nty = ceilDiv(ysiz - cfg.ytosiz, ytsiz);
        for (int t = 0; t < ntx * nty; t++) {
            byte[] tileData = encodeTile(comps, t, ntx);
            w16(out, 0xFF90); // SOT
            w16(out, 10);
            w16(out, t);
            w32(out, 12 + 2 + tileData.length);
            w8(out, 0);  // TPsot
            w8(out, 1);  // TNsot
            w16(out, 0xFF93); // SOD
            out.writeBytes(tileData);
        }
        w16(out, 0xFFD9); // EOC
        return out.toByteArray();
    }

    private void writeSiz(ByteArrayOutputStream out) {
        w16(out, 0xFF51);
        w16(out, 38 + 3 * nc);
        w16(out, 0); // Rsiz
        w32(out, xsiz);
        w32(out, ysiz);
        w32(out, cfg.xosiz);
        w32(out, cfg.yosiz);
        w32(out, xtsiz);
        w32(out, ytsiz);
        w32(out, cfg.xtosiz);
        w32(out, cfg.ytosiz);
        w16(out, nc);
        for (int c = 0; c < nc; c++) {
            w8(out, cfg.precision[c] - 1); // unsigned
            w8(out, cfg.xr[c]);
            w8(out, cfg.yr[c]);
        }
    }

    private void writeCod(ByteArrayOutputStream out) {
        w16(out, 0xFF52);
        w16(out, 12);
        w8(out, cfg.sopEph ? 0x06 : 0x00); // Scod: SOP | EPH
        w8(out, 0);                        // LRCP
        w16(out, 1);                       // layers
        w8(out, cfg.rct ? 1 : 0);          // MCT
        w8(out, cfg.levels);
        w8(out, cfg.xcb - 2);
        w8(out, cfg.ycb - 2);
        w8(out, 0);                        // code-block style
        w8(out, 1);                        // 5/3 reversible
    }

    private void writeQcd(ByteArrayOutputStream out) {
        int numBands = 3 * cfg.levels + 1;
        w16(out, 0xFF5C);
        w16(out, 3 + numBands);
        w8(out, cfg.guard << 5); // style 0: no quantization
        for (int b = 0; b < numBands; b++) {
            int gain = b == 0 ? 0 : switch ((b - 1) % 3) {
                case 0, 1 -> 1; // HL, LH
                default -> 2;   // HH
            };
            w8(out, eps[gain] << 3);
        }
    }

    // ---- per tile ----

    private record Bnd(int orient, int x0, int y0, int x1, int y1, int[] data) {
        int w() {
            return x1 - x0;
        }
    }

    private byte[] encodeTile(int[][] comps, int t, int ntx) {
        int p = t % ntx;
        int q = t / ntx;
        int tx0 = Math.max(cfg.xtosiz + p * xtsiz, cfg.xosiz);
        int ty0 = Math.max(cfg.ytosiz + q * ytsiz, cfg.yosiz);
        int tx1 = Math.min(cfg.xtosiz + (p + 1) * xtsiz, xsiz);
        int ty1 = Math.min(cfg.ytosiz + (q + 1) * ytsiz, ysiz);

        // extract tile-component samples, DC shift
        int[][] tcSamples = new int[nc][];
        int[] tcx0 = new int[nc], tcy0 = new int[nc], tcx1 = new int[nc], tcy1 = new int[nc];
        for (int c = 0; c < nc; c++) {
            tcx0[c] = ceilDiv(tx0, cfg.xr[c]);
            tcy0[c] = ceilDiv(ty0, cfg.yr[c]);
            tcx1[c] = ceilDiv(tx1, cfg.xr[c]);
            tcy1[c] = ceilDiv(ty1, cfg.yr[c]);
            int tw = tcx1[c] - tcx0[c];
            int th = tcy1[c] - tcy0[c];
            int cx0 = ceilDiv(cfg.xosiz, cfg.xr[c]);
            int cy0 = ceilDiv(cfg.yosiz, cfg.yr[c]);
            int cw = ceilDiv(xsiz, cfg.xr[c]) - cx0;
            int shift = 1 << (cfg.precision[c] - 1);
            int[] s = new int[Math.max(0, tw) * Math.max(0, th)];
            for (int y = 0; y < th; y++) {
                for (int x = 0; x < tw; x++) {
                    s[y * tw + x] = comps[c][(tcy0[c] + y - cy0) * cw + (tcx0[c] + x - cx0)] - shift;
                }
            }
            tcSamples[c] = s;
        }
        if (cfg.rct) {
            int[] r = tcSamples[0], g = tcSamples[1], b = tcSamples[2];
            for (int i = 0; i < r.length; i++) {
                int y0v = (r[i] + 2 * g[i] + b[i]) >> 2;
                int y1v = b[i] - g[i];
                int y2v = r[i] - g[i];
                r[i] = y0v;
                g[i] = y1v;
                b[i] = y2v;
            }
        }

        // forward DWT: bands[c][r] = subbands of resolution r
        int nl = cfg.levels;
        Bnd[][][] bands = new Bnd[nc][nl + 1][];
        for (int c = 0; c < nc; c++) {
            bands[c] = decompose(tcSamples[c], tcx0[c], tcy0[c], tcx1[c], tcy1[c], nl);
        }

        // encode all code-blocks, assemble packets in LRCP order
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int r = 0; r <= nl; r++) {
            for (int c = 0; c < nc; c++) {
                int nd = nl - r;
                int trxa = ceilShift(tcx0[c], nd);
                int trya = ceilShift(tcy0[c], nd);
                int trxb = ceilShift(tcx1[c], nd);
                int tryb = ceilShift(tcy1[c], nd);
                if (trxb <= trxa || tryb <= trya) {
                    continue; // empty resolution: no precinct, no packet
                }
                if ((trxa >> 15) != ((trxb - 1) >> 15) || (trya >> 15) != ((tryb - 1) >> 15)) {
                    throw new IllegalStateException("test encoder assumes a single precinct");
                }
                writePacket(body, bands[c][r], r);
            }
        }
        return body.toByteArray();
    }

    // ---- forward 5/3 wavelet ----

    private Bnd[][] decompose(int[] samples, int tcx0, int tcy0, int tcx1, int tcy1, int nl) {
        Bnd[][] result = new Bnd[nl + 1][];
        int[] cur = samples;
        int cx0 = tcx0, cy0 = tcy0, cx1 = tcx1, cy1 = tcy1; // current resolution rect
        for (int r = nl; r >= 1; r--) {
            int wd = cx1 - cx0;
            int ht = cy1 - cy0;
            // forward lifting: columns then rows (inverse of synthesis order)
            for (int x = 0; x < wd; x++) {
                fwd53(cur, x, wd, ht, cy0);
            }
            for (int y = 0; y < ht; y++) {
                fwd53(cur, y * wd, 1, wd, cx0);
            }
            // deinterleave
            int nb = nl - r + 1;
            int px0 = ceilShift(cx0, 1), py0 = ceilShift(cy0, 1);
            int px1 = ceilShift(cx1, 1), py1 = ceilShift(cy1, 1);
            int[] ll = new int[Math.max(0, px1 - px0) * Math.max(0, py1 - py0)];
            Bnd hl = makeBnd(1, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd lh = makeBnd(2, nb, tcx0, tcy0, tcx1, tcy1);
            Bnd hh = makeBnd(3, nb, tcx0, tcy0, tcx1, tcy1);
            for (int v = py0; v < py1; v++) {
                for (int u = px0; u < px1; u++) {
                    ll[(v - py0) * (px1 - px0) + (u - px0)] = cur[(2 * v - cy0) * wd + (2 * u - cx0)];
                }
            }
            fill(hl, cur, wd, cx0, cy0, 1, 0);
            fill(lh, cur, wd, cx0, cy0, 0, 1);
            fill(hh, cur, wd, cx0, cy0, 1, 1);
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

    private void fill(Bnd b, int[] a, int wd, int u0, int v0, int xo, int yo) {
        for (int v = b.y0; v < b.y1; v++) {
            for (int u = b.x0; u < b.x1; u++) {
                b.data[(v - b.y0) * b.w() + (u - b.x0)] = a[(2 * v + yo - v0) * wd + (2 * u + xo - u0)];
            }
        }
    }

    /** Forward 1-D 5/3 on t[pos + k*step], run of n covering coords [i0, i0+n). */
    private static void fwd53(int[] t, int pos, int step, int n, int i0) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[pos] <<= 1;
            }
            return;
        }
        for (int i = i0 + 1 - (i0 & 1); i < i0 + n; i += 2) { // odd coordinates
            int j = i - i0;
            int lo = t[pos + mirror(j - 1, n) * step];
            int hi = t[pos + mirror(j + 1, n) * step];
            t[pos + j * step] -= (lo + hi) >> 1;
        }
        for (int i = i0 + (i0 & 1); i < i0 + n; i += 2) {     // even coordinates
            int j = i - i0;
            int lo = t[pos + mirror(j - 1, n) * step];
            int hi = t[pos + mirror(j + 1, n) * step];
            t[pos + j * step] += (lo + hi + 2) >> 2;
        }
    }

    // ---- packet writing ----

    private record CbInfo(int passes, int zb, byte[] data) {
    }

    private void writePacket(ByteArrayOutputStream body, Bnd[] bnds, int r) {
        List<CbInfo[]> perBand = new ArrayList<>();
        List<int[]> gridDims = new ArrayList<>();
        boolean anyIncluded = false;
        for (Bnd b : bnds) {
            int cbx = b.orient == 0 ? Math.min(cfg.xcb, 15) : Math.min(cfg.xcb, 14);
            int cby = b.orient == 0 ? Math.min(cfg.ycb, 15) : Math.min(cfg.ycb, 14);
            int nw = (b.x1 > b.x0) ? ceilShift(b.x1, cbx) - (b.x0 >> cbx) : 0;
            int nh = (b.y1 > b.y0) ? ceilShift(b.y1, cby) - (b.y0 >> cby) : 0;
            CbInfo[] cbs = new CbInfo[nw * nh];
            int gain = b.orient == 0 ? 0 : (b.orient == 3 ? 2 : 1);
            int mb = cfg.guard + eps[gain] - 1;
            for (int j = 0; j < nh; j++) {
                for (int i = 0; i < nw; i++) {
                    int gx = (b.x0 >> cbx) + i;
                    int gy = (b.y0 >> cby) + j;
                    int bx0 = Math.max(b.x0, gx << cbx);
                    int bx1 = Math.min(b.x1, (gx + 1) << cbx);
                    int by0 = Math.max(b.y0, gy << cby);
                    int by1 = Math.min(b.y1, (gy + 1) << cby);
                    cbs[j * nw + i] = encodeBlock(b, bx0, by0, bx1, by1, mb);
                    if (cbs[j * nw + i] != null) {
                        anyIncluded = true;
                    }
                }
            }
            perBand.add(cbs);
            gridDims.add(new int[] {nw, nh});
        }

        if (cfg.sopEph) {
            w16(body, 0xFF91);
            w16(body, 4);
            w16(body, sopCounter++ & 0xFFFF);
        }
        PacketBitWriter hw = new PacketBitWriter();
        if (!anyIncluded) {
            hw.bit(0);
        } else {
            hw.bit(1);
            for (int bi = 0; bi < bnds.length; bi++) {
                CbInfo[] cbs = perBand.get(bi);
                int nw = gridDims.get(bi)[0];
                int nh = gridDims.get(bi)[1];
                if (nw * nh == 0) {
                    continue;
                }
                int[] inclValues = new int[nw * nh];
                int[] zbValues = new int[nw * nh];
                for (int k = 0; k < cbs.length; k++) {
                    inclValues[k] = cbs[k] != null ? 0 : 1;
                    zbValues[k] = cbs[k] != null ? cbs[k].zb : 255;
                }
                TagTreeEncoder incl = new TagTreeEncoder(nw, nh, inclValues);
                TagTreeEncoder msb = new TagTreeEncoder(nw, nh, zbValues);
                for (int j = 0; j < nh; j++) {
                    for (int i = 0; i < nw; i++) {
                        CbInfo cb = cbs[j * nw + i];
                        incl.encode(hw, i, j, 1);
                        if (cb == null) {
                            continue;
                        }
                        msb.encodeValue(hw, i, j);
                        encodePassCount(hw, cb.passes);
                        int lblock = 3;
                        int lenBits = lblock + (31 - Integer.numberOfLeadingZeros(cb.passes));
                        while (cb.data.length >= (1 << lenBits)) {
                            hw.bit(1);
                            lenBits++;
                        }
                        hw.bit(0);
                        hw.bits(cb.data.length, lenBits);
                    }
                }
            }
        }
        body.writeBytes(hw.finish());
        if (cfg.sopEph) {
            w16(body, 0xFF92);
        }
        for (CbInfo[] cbs : perBand) {
            for (CbInfo cb : cbs) {
                if (cb != null) {
                    body.writeBytes(cb.data);
                }
            }
        }
    }

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

    // ---- Tier-1 encoder (mirror of BlockDecoder) ----

    private static final int SIG = 1, NEG = 2, VIS = 4, REF = 8;
    private static final int CTX_RL = 17, CTX_UNI = 18;

    private CbInfo encodeBlock(Bnd band, int bx0, int by0, int bx1, int by1, int mb) {
        int w = bx1 - bx0;
        int h = by1 - by0;
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] mag = new int[w * h];
        int stride = w + 2;
        byte[] flags = new byte[stride * (h + 2)];
        int maxMag = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = band.data[(by0 - band.y0 + y) * band.w() + (bx0 - band.x0 + x)];
                int m = Math.abs(v);
                mag[y * w + x] = m;
                maxMag |= m;
                if (v < 0) {
                    flags[(y + 1) * stride + (x + 1)] |= NEG;
                }
            }
        }
        if (maxMag == 0) {
            return null;
        }
        int magBits = 32 - Integer.numberOfLeadingZeros(maxMag);
        if (magBits > mb) {
            throw new IllegalStateException("Insufficient bit-plane headroom: "
                    + magBits + " > " + mb);
        }
        int numPlanes = magBits;
        int passes = 3 * numPlanes - 2;
        MQEncoder mq = new MQEncoder(19);
        mq.setContext(0, 4);
        mq.setContext(CTX_RL, 3);
        mq.setContext(CTX_UNI, 46);

        for (int p = 0; p < passes; p++) {
            int bp = numPlanes - 1 - Passes.plane(p);
            switch (Passes.type(p)) {
                case Passes.SPP -> encSpp(mq, band.orient, flags, mag, w, h, stride, bp);
                case Passes.MRP -> encMrp(mq, flags, mag, w, h, stride, bp);
                default -> encCup(mq, band.orient, flags, mag, w, h, stride, bp);
            }
        }
        return new CbInfo(passes, mb - magBits, mq.flush());
    }

    private static int sigCtx(int orient, byte[] flags, int stride, int px, int py) {
        int up = (py - 1) * stride + px;
        int mid = py * stride + px;
        int dn = (py + 1) * stride + px;
        int hn = (flags[mid - 1] & SIG) + (flags[mid + 1] & SIG);
        int vn = (flags[up] & SIG) + (flags[dn] & SIG);
        int dg = (flags[up - 1] & SIG) + (flags[up + 1] & SIG)
                + (flags[dn - 1] & SIG) + (flags[dn + 1] & SIG);
        return BlockDecoder.sigContext(orient, hn, vn, dg);
    }

    private static int contrib(int f) {
        return (f & SIG) == 0 ? 0 : ((f & NEG) != 0 ? -1 : 1);
    }

    private static void encSign(MQEncoder mq, byte[] flags, int stride, int px, int py,
                                boolean negative) {
        int mid = py * stride + px;
        int hc = Math.max(-1, Math.min(1, contrib(flags[mid - 1]) + contrib(flags[mid + 1])));
        int vc = Math.max(-1, Math.min(1,
                contrib(flags[mid - stride]) + contrib(flags[mid + stride])));
        int packed = BlockDecoder.signContext(hc, vc);
        mq.encode(packed >> 1, (negative ? 1 : 0) ^ (packed & 1));
    }

    private static void encSpp(MQEncoder mq, int orient, byte[] flags, int[] mag,
                               int w, int h, int stride, int bp) {
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = s; y < yEnd; y++) {
                    int px = x + 1, py = y + 1;
                    int fi = py * stride + px;
                    if ((flags[fi] & SIG) != 0) {
                        continue;
                    }
                    int ctx = sigCtx(orient, flags, stride, px, py);
                    if (ctx == 0) {
                        continue;
                    }
                    int bit = (mag[y * w + x] >> bp) & 1;
                    mq.encode(ctx, bit);
                    flags[fi] |= VIS;
                    if (bit != 0) {
                        encSign(mq, flags, stride, px, py, (flags[fi] & NEG) != 0);
                        flags[fi] |= SIG;
                    }
                }
            }
        }
    }

    private static void encMrp(MQEncoder mq, byte[] flags, int[] mag,
                               int w, int h, int stride, int bp) {
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = s; y < yEnd; y++) {
                    int fi = (y + 1) * stride + (x + 1);
                    if ((flags[fi] & (SIG | VIS)) != SIG) {
                        continue;
                    }
                    int ctx;
                    if ((flags[fi] & REF) != 0) {
                        ctx = 16;
                    } else {
                        int up = fi - stride, dn = fi + stride;
                        int any = (flags[fi - 1] & SIG) + (flags[fi + 1] & SIG)
                                + (flags[up] & SIG) + (flags[up - 1] & SIG) + (flags[up + 1] & SIG)
                                + (flags[dn] & SIG) + (flags[dn - 1] & SIG) + (flags[dn + 1] & SIG);
                        ctx = any > 0 ? 15 : 14;
                    }
                    mq.encode(ctx, (mag[y * w + x] >> bp) & 1);
                    flags[fi] |= REF;
                }
            }
        }
    }

    private static void encCup(MQEncoder mq, int orient, byte[] flags, int[] mag,
                               int w, int h, int stride, int bp) {
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                int px = x + 1;
                int y = s;
                boolean rl = s + 4 <= h;
                if (rl) {
                    for (int yy = s; yy < s + 4 && rl; yy++) {
                        int fi = (yy + 1) * stride + px;
                        if ((flags[fi] & (SIG | VIS)) != 0
                                || sigCtx(orient, flags, stride, px, yy + 1) != 0) {
                            rl = false;
                        }
                    }
                }
                if (rl) {
                    int r = -1;
                    for (int yy = s; yy < s + 4; yy++) {
                        if (((mag[yy * w + x] >> bp) & 1) != 0) {
                            r = yy - s;
                            break;
                        }
                    }
                    if (r < 0) {
                        mq.encode(CTX_RL, 0);
                        continue;
                    }
                    mq.encode(CTX_RL, 1);
                    mq.encode(CTX_UNI, (r >> 1) & 1);
                    mq.encode(CTX_UNI, r & 1);
                    y = s + r;
                    int fi = (y + 1) * stride + px;
                    encSign(mq, flags, stride, px, y + 1, (flags[fi] & NEG) != 0);
                    flags[fi] |= SIG;
                    y++;
                }
                for (; y < yEnd; y++) {
                    int py = y + 1;
                    int fi = py * stride + px;
                    if ((flags[fi] & (SIG | VIS)) != 0) {
                        continue;
                    }
                    int ctx = sigCtx(orient, flags, stride, px, py);
                    int bit = (mag[y * w + x] >> bp) & 1;
                    mq.encode(ctx, bit);
                    if (bit != 0) {
                        encSign(mq, flags, stride, px, py, (flags[fi] & NEG) != 0);
                        flags[fi] |= SIG;
                    }
                }
            }
        }
        for (int i = 0; i < flags.length; i++) {
            flags[i] &= ~VIS;
        }
    }
}
