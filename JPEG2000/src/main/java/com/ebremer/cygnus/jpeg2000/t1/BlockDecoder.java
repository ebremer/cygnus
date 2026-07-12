package com.ebremer.cygnus.jpeg2000.t1;

import com.ebremer.cygnus.jpeg2000.codestream.CodingStyle;
import com.ebremer.cygnus.jpeg2000.t2.Band;
import com.ebremer.cygnus.jpeg2000.t2.CodeBlock;
import java.util.List;

/**
 * EBCOT Tier-1 code-block decoder (T.800 Annex D): significance propagation,
 * magnitude refinement and cleanup passes over stripe-oriented scans, with
 * support for the selective arithmetic coding bypass, context reset,
 * pass termination, vertically causal contexts and segmentation symbols.
 *
 * <p>Context formation is table-driven: each sample carries an incrementally
 * maintained byte of neighbor significance and a byte of horizontal/vertical
 * sign information, so the per-sample work in each pass is a single load and
 * LUT lookup instead of a 3x3 neighborhood scan. The tables are generated
 * from the spec-table functions {@link #sigContext} and
 * {@link #signContext}.</p>
 *
 * <p>Decoded coefficients (sign-magnitude, with ROI unshifting and bin-center
 * reconstruction applied) are written into the band coefficient buffer.</p>
 */
public final class BlockDecoder {

    // sample state flags (kept in a border-padded grid)
    private static final int SIG = 1;   // significant
    private static final int NEG = 2;   // sign of significant sample
    private static final int VIS = 4;   // coded in the current significance pass
    private static final int REF = 8;   // refined at least once

    // neighbor-significance byte: one bit per direction
    private static final int N_W = 1;
    private static final int N_E = 2;
    private static final int N_N = 4;
    private static final int N_S = 8;
    private static final int N_NW = 16;
    private static final int N_NE = 32;
    private static final int N_SW = 64;
    private static final int N_SE = 128;
    /** Mask removing southern neighbors (vertically causal mode, D.7). */
    private static final int CAUSAL_NBR_MASK = 0xFF & ~(N_S | N_SW | N_SE);

    // sign-info byte: significance and sign of the four cross neighbors
    private static final int H_WSIG = 1;
    private static final int H_ESIG = 2;
    private static final int H_NSIG = 4;
    private static final int H_SSIG = 8;
    private static final int H_WNEG = 16;
    private static final int H_ENEG = 32;
    private static final int H_NNEG = 64;
    private static final int H_SNEG = 128;
    private static final int CAUSAL_SIGN_MASK = 0xFF & ~(H_SSIG | H_SNEG);

    // context indices: 0-8 significance, 9-13 sign, 14-16 refinement
    private static final int CTX_RL = 17;
    private static final int CTX_UNI = 18;
    private static final int NUM_CTX = 19;

    // sign context/xor lookup indexed by (H+1)*3+(V+1), T.800 Table D.3
    private static final int[] SIGN_CTX = {13, 12, 11, 10, 9, 10, 11, 12, 13};
    private static final int[] SIGN_XOR = {1, 1, 1, 1, 0, 0, 0, 0, 0};

    /** Significance context per orientation and neighbor byte. */
    private static final byte[][] SIG_LUT = new byte[4][256];
    /** Packed (context << 1 | xorBit) per sign-info byte. */
    private static final byte[] SIGN_LUT = new byte[256];

    static {
        for (int orient = 0; orient < 4; orient++) {
            for (int b = 0; b < 256; b++) {
                int h = (b & N_W) != 0 ? 1 : 0;
                h += (b & N_E) != 0 ? 1 : 0;
                int v = (b & N_N) != 0 ? 1 : 0;
                v += (b & N_S) != 0 ? 1 : 0;
                int d = Integer.bitCount(b >> 4);
                SIG_LUT[orient][b] = (byte) sigContext(orient, h, v, d);
            }
        }
        for (int b = 0; b < 256; b++) {
            int hc = contribution(b, H_WSIG, H_WNEG) + contribution(b, H_ESIG, H_ENEG);
            int vc = contribution(b, H_NSIG, H_NNEG) + contribution(b, H_SSIG, H_SNEG);
            hc = Math.max(-1, Math.min(1, hc));
            vc = Math.max(-1, Math.min(1, vc));
            SIGN_LUT[b] = (byte) signContext(hc, vc);
        }
    }

    private static int contribution(int b, int sigBit, int negBit) {
        if ((b & sigBit) == 0) {
            return 0;
        }
        return (b & negBit) != 0 ? -1 : 1;
    }

    private final int w, h, stride, orient;
    private final byte[] flags;      // (w+2) x (h+2), border always zero
    private final byte[] nbr;        // neighbor significance per sample
    private final byte[] hv;         // cross-neighbor sign info per sample
    private final int[] mag;         // w x h magnitudes
    private final byte[] lastPlane;  // per sample: plane of its last update
    private final boolean vcausal;
    private MQDecoder mq;
    private RawBitReader raw;
    private boolean rawPass;

    private BlockDecoder(int w, int h, int orient, boolean vcausal) {
        this.w = w;
        this.h = h;
        this.stride = w + 2;
        this.orient = orient;
        this.vcausal = vcausal;
        this.flags = new byte[stride * (h + 2)];
        this.nbr = new byte[stride * (h + 2)];
        this.hv = new byte[stride * (h + 2)];
        this.mag = new int[w * h];
        this.lastPlane = new byte[w * h];
    }

    /**
     * Decodes one code-block and stores its coefficients into
     * {@code band.coeffs()}.
     */
    public static void decode(CodeBlock cb, Band band, int cbStyle, List<String> warnings) {
        if ((cbStyle & CodingStyle.CB_HT) != 0) {
            HtBlockDecoder.decode(cb, band, cbStyle, warnings);
            return;
        }
        int wdt = cb.width();
        int hgt = cb.height();
        if (cb.totalPasses <= 0 || wdt <= 0 || hgt <= 0) {
            return;
        }
        int numPlanes = band.numBps - cb.zeroBitplanes;
        if (numPlanes <= 0) {
            return;
        }
        if (numPlanes > 31) {
            warnings.add("Code-block with " + numPlanes + " bit-planes not supported");
            return;
        }
        BlockDecoder dec = new BlockDecoder(wdt, hgt, band.orient,
                (cbStyle & CodingStyle.CB_VCAUSAL) != 0);
        dec.runPasses(cb, cbStyle, numPlanes, warnings);
        dec.store(cb, band);
    }

    /** Executes all coding passes. */
    private void runPasses(CodeBlock cb, int cbStyle, int numPlanes, List<String> warnings) {
        boolean reset = (cbStyle & CodingStyle.CB_RESET) != 0;
        boolean segsym = (cbStyle & CodingStyle.CB_SEGSYM) != 0;
        int total = Math.min(cb.totalPasses, 3 * numPlanes - 2);
        int passIdx = 0;

        outer:
        for (CodeBlock.Segment seg : cb.segments) {
            rawPass = Passes.raw(passIdx, cbStyle);
            if (rawPass) {
                raw = new RawBitReader(seg.data, 0, seg.length);
            } else if (mq == null) {
                mq = new MQDecoder(seg.data, 0, seg.length, NUM_CTX);
                resetContexts();
            } else {
                mq.startSegment(seg.data, 0, seg.length);
            }
            for (int k = 0; k < seg.passes; k++) {
                if (passIdx >= total) {
                    break outer;
                }
                int bp = numPlanes - 1 - Passes.plane(passIdx);
                switch (Passes.type(passIdx)) {
                    case Passes.SPP -> significancePass(bp);
                    case Passes.MRP -> refinementPass(bp);
                    default -> {
                        cleanupPass(bp);
                        if (segsym) {
                            int sym = (mq.decode(CTX_UNI) << 3) | (mq.decode(CTX_UNI) << 2)
                                    | (mq.decode(CTX_UNI) << 1) | mq.decode(CTX_UNI);
                            if (sym != 0xA && warnings != null) {
                                warnings.add("Segmentation symbol mismatch (corrupt data?)");
                            }
                        }
                        clearVisited();
                    }
                }
                if (reset && mq != null) {
                    resetContexts();
                }
                passIdx++;
            }
        }
    }

    private void resetContexts() {
        for (int i = 0; i < NUM_CTX; i++) {
            mq.setContext(i, 0);
        }
        mq.setContext(0, 4);
        mq.setContext(CTX_RL, 3);
        mq.setContext(CTX_UNI, 46);
    }

    private void clearVisited() {
        for (int i = 0; i < flags.length; i++) {
            flags[i] &= ~VIS;
        }
    }

    // ---- context formation ----

    /** T.800 Table D.1: significance context from neighbor counts. */
    public static int sigContext(int orient, int hn, int vn, int dg) {
        if (orient == Band.HH) {
            int hv = hn + vn;
            if (dg >= 3) {
                return 8;
            }
            if (dg == 2) {
                return hv >= 1 ? 7 : 6;
            }
            if (dg == 1) {
                return hv >= 2 ? 5 : (hv == 1 ? 4 : 3);
            }
            return hv >= 2 ? 2 : hv;
        }
        if (orient == Band.HL) {
            int t = hn;
            hn = vn;
            vn = t;
        }
        if (hn == 2) {
            return 8;
        }
        if (hn == 1) {
            return vn >= 1 ? 7 : (dg >= 1 ? 6 : 5);
        }
        if (vn == 2) {
            return 4;
        }
        if (vn == 1) {
            return 3;
        }
        return dg >= 2 ? 2 : dg;
    }

    /**
     * Sign-coding context and XOR bit (T.800 Table D.3) for clamped
     * horizontal/vertical sign contributions in [-1, 1].
     * Returns (context &lt;&lt; 1) | xorBit.
     */
    public static int signContext(int hc, int vc) {
        int idx = (hc + 1) * 3 + (vc + 1);
        return (SIGN_CTX[idx] << 1) | SIGN_XOR[idx];
    }

    /**
     * Marks (fi) significant with the given sign and pushes the fact into
     * its neighbors' incremental context bytes.
     */
    private void setSignificant(int fi, int negative) {
        flags[fi] |= SIG | (negative != 0 ? NEG : 0);
        nbr[fi - 1] |= N_E;
        nbr[fi + 1] |= N_W;
        nbr[fi - stride] |= N_S;
        nbr[fi + stride] |= N_N;
        nbr[fi - stride - 1] |= N_SE;
        nbr[fi - stride + 1] |= N_SW;
        nbr[fi + stride - 1] |= N_NE;
        nbr[fi + stride + 1] |= N_NW;
        if (negative != 0) {
            hv[fi - 1] |= H_ESIG | H_ENEG;
            hv[fi + 1] |= H_WSIG | H_WNEG;
            hv[fi - stride] |= H_SSIG | H_SNEG;
            hv[fi + stride] |= H_NSIG | H_NNEG;
        } else {
            hv[fi - 1] |= H_ESIG;
            hv[fi + 1] |= H_WSIG;
            hv[fi - stride] |= H_SSIG;
            hv[fi + stride] |= H_NSIG;
        }
    }

    /** Decodes a sign bit; returns nonzero for negative (T.800 D.3.2). */
    private int decodeSign(int fi, int signMask) {
        if (rawPass) {
            return raw.readBit();
        }
        int packed = SIGN_LUT[hv[fi] & signMask];
        return mq.decode(packed >> 1) ^ (packed & 1);
    }

    // ---- coding passes ----

    private void significancePass(int bp) {
        byte[] sigLut = SIG_LUT[orient];
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = s; y < yEnd; y++) {
                    int fi = (y + 1) * stride + (x + 1);
                    if ((flags[fi] & SIG) != 0) {
                        continue;
                    }
                    boolean noSouth = vcausal && (y & 3) == 3;
                    int nb = nbr[fi] & 0xFF;
                    if (noSouth) {
                        nb &= CAUSAL_NBR_MASK;
                    }
                    if (nb == 0) {
                        continue;
                    }
                    int bit = rawPass ? raw.readBit() : mq.decode(sigLut[nb]);
                    flags[fi] |= VIS;
                    if (bit != 0) {
                        int sign = decodeSign(fi, noSouth ? CAUSAL_SIGN_MASK : 0xFF);
                        setSignificant(fi, sign);
                        mag[y * w + x] |= 1 << bp;
                        lastPlane[y * w + x] = (byte) bp;
                    }
                }
            }
        }
    }

    private void refinementPass(int bp) {
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = s; y < yEnd; y++) {
                    int fi = (y + 1) * stride + (x + 1);
                    if ((flags[fi] & (SIG | VIS)) != SIG) {
                        continue;
                    }
                    int bit;
                    if (rawPass) {
                        bit = raw.readBit();
                    } else {
                        int ctx;
                        if ((flags[fi] & REF) != 0) {
                            ctx = 16;
                        } else {
                            int nb = nbr[fi] & 0xFF;
                            if (vcausal && (y & 3) == 3) {
                                nb &= CAUSAL_NBR_MASK;
                            }
                            ctx = nb != 0 ? 15 : 14;
                        }
                        bit = mq.decode(ctx);
                    }
                    mag[y * w + x] |= bit << bp;
                    lastPlane[y * w + x] = (byte) bp;
                    flags[fi] |= REF;
                }
            }
        }
    }

    private void cleanupPass(int bp) {
        byte[] sigLut = SIG_LUT[orient];
        for (int s = 0; s < h; s += 4) {
            int yEnd = Math.min(s + 4, h);
            for (int x = 0; x < w; x++) {
                int px = x + 1;
                int y = s;

                // run-length mode: full stripe column, all four samples
                // insignificant, unvisited and with zero context (D.4.2)
                if (s + 4 <= h && runLengthApplies(px, s)) {
                    if (mq.decode(CTX_RL) == 0) {
                        continue;
                    }
                    int r = (mq.decode(CTX_UNI) << 1) | mq.decode(CTX_UNI);
                    y = s + r;
                    int fi = (y + 1) * stride + px;
                    boolean noSouth = vcausal && (y & 3) == 3;
                    int sign = decodeSign(fi, noSouth ? CAUSAL_SIGN_MASK : 0xFF);
                    setSignificant(fi, sign);
                    mag[y * w + x] |= 1 << bp;
                    lastPlane[y * w + x] = (byte) bp;
                    y++;
                }

                for (; y < yEnd; y++) {
                    int fi = (y + 1) * stride + px;
                    if ((flags[fi] & (SIG | VIS)) != 0) {
                        continue;
                    }
                    boolean noSouth = vcausal && (y & 3) == 3;
                    int nb = nbr[fi] & 0xFF;
                    if (noSouth) {
                        nb &= CAUSAL_NBR_MASK;
                    }
                    if (mq.decode(sigLut[nb]) != 0) {
                        int sign = decodeSign(fi, noSouth ? CAUSAL_SIGN_MASK : 0xFF);
                        setSignificant(fi, sign);
                        mag[y * w + x] |= 1 << bp;
                        lastPlane[y * w + x] = (byte) bp;
                    }
                }
            }
        }
    }

    private boolean runLengthApplies(int px, int s) {
        int fi = (s + 1) * stride + px;
        // rows 0..2 of the stripe use their full neighborhoods
        if ((flags[fi] & (SIG | VIS)) != 0 || nbr[fi] != 0) {
            return false;
        }
        fi += stride;
        if ((flags[fi] & (SIG | VIS)) != 0 || nbr[fi] != 0) {
            return false;
        }
        fi += stride;
        if ((flags[fi] & (SIG | VIS)) != 0 || nbr[fi] != 0) {
            return false;
        }
        fi += stride;
        int nb = nbr[fi] & 0xFF;
        if (vcausal) {
            nb &= CAUSAL_NBR_MASK;
        }
        return (flags[fi] & (SIG | VIS)) == 0 && nb == 0;
    }

    // ---- reconstruction ----

    /**
     * Applies ROI unshifting (Annex H) and bin-center reconstruction
     * (E.1.1.2, r = 0.5), then writes signed values into the band buffer.
     *
     * <p>Each sample's uncertainty bin follows from its own last coded
     * plane. On the reversible path the value is the exact integer once all
     * planes are decoded. On the irreversible path the dead-zone quantizer
     * bin [q*delta, (q+1)*delta) never collapses, so a half step is always
     * added; values are stored scaled by 2 so the half is representable
     * (dequantization multiplies by stepSize/2).</p>
     */
    private void store(CodeBlock cb, Band band) {
        int[] coeffs = band.coeffs();
        int bw = band.width();
        int roi = band.roiShift;
        long roiThreshold = roi > 0 ? 1L << Math.min(roi, 62) : 0;
        int offX = cb.x0 - band.x0;
        int offY = cb.y0 - band.y0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long m = mag[y * w + x];
                if (m == 0) {
                    continue;
                }
                int eff = lastPlane[y * w + x];
                if (roi > 0 && m >= roiThreshold) {
                    m >>= roi;
                    eff = Math.max(0, eff - roi);
                }
                if (band.reversible) {
                    if (eff >= 1) {
                        m += 1L << (eff - 1);
                    }
                } else {
                    m = (m << 1) + (1L << eff); // (q + 0.5 * 2^eff) in half-units
                }
                int mi = (int) Math.min(m, Integer.MAX_VALUE);
                int fi = (y + 1) * stride + (x + 1);
                int v = (flags[fi] & NEG) != 0 ? -mi : mi;
                coeffs[(offY + y) * bw + (offX + x)] = v;
            }
        }
    }
}
