package com.ebremer.cygnus.t1;

/**
 * EBCOT Tier-1 code-block encoder (T.800 Annex D): encodes the sign-magnitude
 * bit-planes of one code-block with significance-propagation, magnitude-
 * refinement and cleanup passes over stripe-oriented scans, producing a
 * single MQ codeword segment (no bypass, no restarts) that
 * {@link BlockDecoder} reproduces exactly.
 *
 * <p>Context formation delegates to the spec-table functions
 * {@link BlockDecoder#sigContext} and {@link BlockDecoder#signContext} so
 * encoder and decoder can never disagree.</p>
 */
public final class BlockEncoder {

    // sample state flags (kept in a border-padded grid, mirror of BlockDecoder)
    private static final int SIG = 1;
    private static final int NEG = 2;
    private static final int VIS = 4;
    private static final int REF = 8;

    private static final int CTX_RL = 17;
    private static final int CTX_UNI = 18;
    private static final int NUM_CTX = 19;

    /** One encoded code-block: coding passes, missing MSBs and codeword bytes. */
    public record EncodedBlock(int passes, int zeroBitplanes, byte[] data) {
    }

    private BlockEncoder() {
    }

    /**
     * Encodes the block covering {@code w x h} coefficients at offset
     * ({@code offX}, {@code offY}) of a band buffer with row length
     * {@code stride}. Coefficients are signed integers (reversible values or
     * quantizer indices) whose magnitudes must fit in {@code mb} bit-planes.
     *
     * @return the encoded block, or null when all coefficients are zero (the
     *         block is then simply never included in any packet)
     */
    public static EncodedBlock encode(int[] coeffs, int stride, int offX, int offY,
                                      int w, int h, int orient, int mb) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] mag = new int[w * h];
        int fstride = w + 2;
        byte[] flags = new byte[fstride * (h + 2)];
        int maxMag = 0;
        for (int y = 0; y < h; y++) {
            int src = (offY + y) * stride + offX;
            for (int x = 0; x < w; x++) {
                int v = coeffs[src + x];
                int m = Math.abs(v);
                mag[y * w + x] = m;
                maxMag |= m;
                if (v < 0) {
                    flags[(y + 1) * fstride + (x + 1)] |= NEG;
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
        int passes = 3 * magBits - 2;
        MQEncoder mq = new MQEncoder(NUM_CTX);
        mq.setContext(0, 4);
        mq.setContext(CTX_RL, 3);
        mq.setContext(CTX_UNI, 46);

        for (int p = 0; p < passes; p++) {
            int bp = magBits - 1 - Passes.plane(p);
            switch (Passes.type(p)) {
                case Passes.SPP -> encSpp(mq, orient, flags, mag, w, h, fstride, bp);
                case Passes.MRP -> encMrp(mq, flags, mag, w, h, fstride, bp);
                default -> encCup(mq, orient, flags, mag, w, h, fstride, bp);
            }
        }
        return new EncodedBlock(passes, mb - magBits, mq.flush());
    }

    // ---- context formation ----

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

    // ---- coding passes ----

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
