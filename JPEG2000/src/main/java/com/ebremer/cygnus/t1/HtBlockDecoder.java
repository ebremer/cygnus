package com.ebremer.cygnus.t1;

import com.ebremer.cygnus.codestream.CodingStyle;
import com.ebremer.cygnus.t2.Band;
import com.ebremer.cygnus.t2.CodeBlock;
import java.util.List;

/**
 * HTJ2K (ITU-T T.814 / ISO/IEC 15444-15) block decoder: HT Cleanup
 * (MEL + VLC + MagSgn), HT SigProp and HT MagRef passes.
 *
 * <p>This is a Java port of the public HT block decoder written by
 * Aous Naman (ht_dec.c, BSD-2-Clause, as distributed with OpenJPEG),
 * restructured for array indexing instead of pointers. Decoded
 * coefficients use the same convention as the Part-1 decoder: one
 * fractional (bin-center) bit, i.e. values are 2*magnitude+half; the
 * reversible path halves them on storage.</p>
 */
public final class HtBlockDecoder {

    private HtBlockDecoder() {
    }

    // ---- MEL decoder ----

    private static final int[] MEL_EXP = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5};

    private static final class Mel {
        byte[] buf;
        int pos;        // read index
        int size;       // remaining bytes
        long tmp;       // bit buffer, MSB-aligned
        int bits;
        boolean unstuff;
        int k;
        int numRuns;
        long runs;

        void read() {
            if (bits > 32) {
                return;
            }
            int val = 0xFFFFFFFF;
            if (size > 4) {
                val = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
                        | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
                pos += 4;
                size -= 4;
            } else if (size > 0) {
                int i = 0;
                while (size > 1) {
                    int v = buf[pos++] & 0xFF;
                    int m = ~(0xFF << i);
                    val = (val & m) | (v << i);
                    --size;
                    i += 8;
                }
                int v = (buf[pos++] & 0xFF) | 0xF; // MEL and VLC may overlap
                int m = ~(0xFF << i);
                val = (val & m) | (v << i);
                --size;
            }

            int nbits = 32 - (unstuff ? 1 : 0);
            int t = val & 0xFF;
            boolean us = (val & 0xFF) == 0xFF;
            nbits -= us ? 1 : 0;
            t = t << (8 - (us ? 1 : 0));

            t |= (val >> 8) & 0xFF;
            us = ((val >> 8) & 0xFF) == 0xFF;
            nbits -= us ? 1 : 0;
            t = t << (8 - (us ? 1 : 0));

            t |= (val >> 16) & 0xFF;
            us = ((val >> 16) & 0xFF) == 0xFF;
            nbits -= us ? 1 : 0;
            t = t << (8 - (us ? 1 : 0));

            t |= (val >>> 24) & 0xFF;
            unstuff = ((val >>> 24) & 0xFF) == 0xFF;

            tmp |= ((long) t & 0xFFFFFFFFL) << (64 - nbits - bits);
            bits += nbits;
        }

        void decodeRuns() {
            if (bits < 6) {
                read();
            }
            while (bits >= 6 && numRuns < 8) {
                int eval = MEL_EXP[k];
                int run;
                if ((tmp & (1L << 63)) != 0) {
                    run = (1 << eval) - 1;
                    k = k + 1 < 12 ? k + 1 : 12;
                    tmp <<= 1;
                    bits -= 1;
                    run = run << 1;
                } else {
                    run = (int) (tmp >>> (63 - eval)) & ((1 << eval) - 1);
                    k = k - 1 > 0 ? k - 1 : 0;
                    tmp <<= eval + 1;
                    bits -= eval + 1;
                    run = (run << 1) + 1;
                }
                int shift = numRuns * 7;
                runs &= ~(0x3FL << shift);
                runs |= ((long) run) << shift;
                numRuns++;
            }
        }

        boolean init(byte[] data, int lcup, int scup) {
            buf = data;
            pos = lcup - scup;
            bits = 0;
            tmp = 0;
            unstuff = false;
            size = scup - 1;
            k = 0;
            numRuns = 0;
            runs = 0;
            for (int i = 0; i < 4; ++i) { // preload (alignment loop in C)
                if (unstuff && size > 0 && (buf[pos] & 0xFF) > 0x8F) {
                    return false;
                }
                long d = (size > 0) ? (buf[pos] & 0xFF) : 0xFF;
                if (size == 1) {
                    d |= 0xF;
                }
                if (size-- > 0) {
                    pos++;
                }
                int dBits = 8 - (unstuff ? 1 : 0);
                tmp = (tmp << dBits) | d;
                bits += dBits;
                unstuff = (d & 0xFF) == 0xFF;
            }
            tmp <<= (64 - bits);
            return true;
        }

        int getRun() {
            if (numRuns == 0) {
                decodeRuns();
            }
            int t = (int) (runs & 0x7F);
            runs >>>= 7;
            numRuns--;
            return t;
        }
    }

    // ---- backward-growing bit-stream (VLC and MagRef) ----

    private static final class Rev {
        byte[] buf;
        int pos;       // read index, moving backward
        int size;
        long tmp;
        int bits;
        boolean unstuff;

        private int at(int i) {
            return buf[i] & 0xFF;
        }

        void read() {
            if (bits > 32) {
                return;
            }
            int val = 0;
            if (size > 3) {
                val = at(pos - 3) | (at(pos - 2) << 8) | (at(pos - 1) << 16)
                        | (at(pos) << 24);
                pos -= 4;
                size -= 4;
            } else if (size > 0) {
                int i = 24;
                while (size > 0) {
                    val |= at(pos--) << i;
                    --size;
                    i -= 8;
                }
            }
            int t = val >>> 24;
            int nbits = 8 - ((unstuff && (((val >>> 24) & 0x7F) == 0x7F)) ? 1 : 0);
            boolean us = (val >>> 24) > 0x8F;

            t |= ((val >> 16) & 0xFF) << nbits;
            nbits += 8 - ((us && (((val >> 16) & 0x7F) == 0x7F)) ? 1 : 0);
            us = ((val >> 16) & 0xFF) > 0x8F;

            t |= ((val >> 8) & 0xFF) << nbits;
            nbits += 8 - ((us && (((val >> 8) & 0x7F) == 0x7F)) ? 1 : 0);
            us = ((val >> 8) & 0xFF) > 0x8F;

            t |= (val & 0xFF) << nbits;
            nbits += 8 - ((us && ((val & 0x7F) == 0x7F)) ? 1 : 0);
            us = (val & 0xFF) > 0x8F;

            tmp |= ((long) t & 0xFFFFFFFFL) << bits;
            bits += nbits;
            unstuff = us;
        }

        /** VLC variant: skips the 4-bit Scup nibble and preloads. */
        void initVlc(byte[] data, int lcup, int scup) {
            buf = data;
            pos = lcup - 2;
            size = scup - 2;
            int d = at(pos--);
            tmp = d >> 4;
            bits = 4 - (((tmp & 7) == 7) ? 1 : 0);
            unstuff = (d | 0xF) > 0x8F;

            int num = 4;
            int tnum = Math.min(num, size);
            for (int i = 0; i < tnum; ++i) {
                long dd = at(pos--);
                int dBits = 8 - ((unstuff && ((dd & 0x7F) == 0x7F)) ? 1 : 0);
                tmp |= dd << bits;
                bits += dBits;
                unstuff = dd > 0x8F;
            }
            size -= tnum;
            read();
        }

        /** MagRef variant: feeds zeros when exhausted, starts unstuffed. */
        void initMrp(byte[] data, int lcup, int len2) {
            buf = data;
            pos = lcup + len2 - 1;
            size = len2;
            unstuff = true;
            bits = 0;
            tmp = 0;
            for (int i = 0; i < 4; ++i) {
                long d = (size-- > 0) ? at(pos--) : 0;
                int dBits = 8 - ((unstuff && ((d & 0x7F) == 0x7F)) ? 1 : 0);
                tmp |= d << bits;
                bits += dBits;
                unstuff = d > 0x8F;
            }
            readMrp();
        }

        void readMrp() {
            if (bits > 32) {
                return;
            }
            int val = 0;
            if (size > 3) {
                val = at(pos - 3) | (at(pos - 2) << 8) | (at(pos - 1) << 16)
                        | (at(pos) << 24);
                pos -= 4;
                size -= 4;
            } else if (size > 0) {
                int i = 24;
                while (size > 0) {
                    val |= at(pos--) << i;
                    --size;
                    i -= 8;
                }
            }
            int t = val >>> 24;
            int nbits = 8 - ((unstuff && (((val >>> 24) & 0x7F) == 0x7F)) ? 1 : 0);
            boolean us = (val >>> 24) > 0x8F;

            t |= ((val >> 16) & 0xFF) << nbits;
            nbits += 8 - ((us && (((val >> 16) & 0x7F) == 0x7F)) ? 1 : 0);
            us = ((val >> 16) & 0xFF) > 0x8F;

            t |= ((val >> 8) & 0xFF) << nbits;
            nbits += 8 - ((us && (((val >> 8) & 0x7F) == 0x7F)) ? 1 : 0);
            us = ((val >> 8) & 0xFF) > 0x8F;

            t |= (val & 0xFF) << nbits;
            nbits += 8 - ((us && ((val & 0x7F) == 0x7F)) ? 1 : 0);
            us = (val & 0xFF) > 0x8F;

            tmp |= ((long) t & 0xFFFFFFFFL) << bits;
            bits += nbits;
            unstuff = us;
        }

        int fetch() {
            if (bits < 32) {
                read();
                if (bits < 32) {
                    read();
                }
            }
            return (int) tmp;
        }

        int advance(int numBits) {
            tmp >>>= numBits;
            bits -= numBits;
            return (int) tmp;
        }

        int fetchMrp() {
            if (bits < 32) {
                readMrp();
                if (bits < 32) {
                    readMrp();
                }
            }
            return (int) tmp;
        }

        int advanceMrp(int numBits) {
            tmp >>>= numBits;
            bits -= numBits;
            return (int) tmp;
        }
    }

    // ---- forward-growing bit-stream (MagSgn and SigProp) ----

    private static final class Fwd {
        byte[] buf;
        int pos;
        int size;
        long tmp;
        int bits;
        boolean unstuff;
        int x;      // fill byte: 0xFF for MagSgn, 0 for SigProp

        void init(byte[] data, int start, int sz, int fill) {
            buf = data;
            pos = start;
            tmp = 0;
            bits = 0;
            unstuff = false;
            size = sz;
            x = fill;
            for (int i = 0; i < 4; ++i) {
                long d = (size-- > 0) ? (buf[pos++] & 0xFF) : x;
                tmp |= d << bits;
                bits += 8 - (unstuff ? 1 : 0);
                unstuff = (d & 0xFF) == 0xFF;
            }
            read();
        }

        void read() {
            int val;
            if (size > 3) {
                val = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
                        | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
                pos += 4;
                size -= 4;
            } else if (size > 0) {
                int i = 0;
                val = x != 0 ? 0xFFFFFFFF : 0;
                while (size > 0) {
                    int v = buf[pos++] & 0xFF;
                    int m = ~(0xFF << i);
                    val = (val & m) | (v << i);
                    --size;
                    i += 8;
                }
            } else {
                val = x != 0 ? 0xFFFFFFFF : 0;
            }

            int nbits = 8 - (unstuff ? 1 : 0);
            int t = val & 0xFF;
            boolean us = (val & 0xFF) == 0xFF;

            t |= ((val >> 8) & 0xFF) << nbits;
            nbits += 8 - (us ? 1 : 0);
            us = ((val >> 8) & 0xFF) == 0xFF;

            t |= ((val >> 16) & 0xFF) << nbits;
            nbits += 8 - (us ? 1 : 0);
            us = ((val >> 16) & 0xFF) == 0xFF;

            t |= ((val >>> 24) & 0xFF) << nbits;
            nbits += 8 - (us ? 1 : 0);
            unstuff = ((val >>> 24) & 0xFF) == 0xFF;

            tmp |= ((long) t & 0xFFFFFFFFL) << bits;
            bits += nbits;
        }

        void advance(int numBits) {
            tmp >>>= numBits;
            bits -= numBits;
        }

        int fetch() {
            if (bits < 32) {
                read();
                if (bits < 32) {
                    read();
                }
            }
            return (int) tmp;
        }
    }

    // ---- UVLC decoding (T.814 Table 3) ----

    // 2 LSBs: prefix length, next 3: suffix length, 3 MSBs: prefix value
    private static final int[] UVLC_DEC = {
        3 | (5 << 2) | (5 << 5),
        1 | (0 << 2) | (1 << 5),
        2 | (0 << 2) | (2 << 5),
        1 | (0 << 2) | (1 << 5),
        3 | (1 << 2) | (3 << 5),
        1 | (0 << 2) | (1 << 5),
        2 | (0 << 2) | (2 << 5),
        1 | (0 << 2) | (1 << 5)
    };

    private static int decodeInitUvlc(int vlc, int mode, int[] u) {
        int consumed = 0;
        if (mode == 0) {
            u[0] = u[1] = 1;
        } else if (mode <= 2) {
            int d = UVLC_DEC[vlc & 0x7];
            vlc >>>= d & 0x3;
            consumed += d & 0x3;
            int suffixLen = (d >> 2) & 0x7;
            consumed += suffixLen;
            d = (d >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[0] = (mode == 1) ? d + 1 : 1;
            u[1] = (mode == 1) ? 1 : d + 1;
        } else if (mode == 3) {
            int d1 = UVLC_DEC[vlc & 0x7];
            vlc >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            if ((d1 & 0x3) > 2) {
                u[1] = (vlc & 1) + 1 + 1;
                ++consumed;
                vlc >>>= 1;
                int suffixLen = (d1 >> 2) & 0x7;
                consumed += suffixLen;
                d1 = (d1 >> 5) + (vlc & ((1 << suffixLen) - 1));
                u[0] = d1 + 1;
            } else {
                int d2 = UVLC_DEC[vlc & 0x7];
                vlc >>>= d2 & 0x3;
                consumed += d2 & 0x3;
                int suffixLen = (d1 >> 2) & 0x7;
                consumed += suffixLen;
                d1 = (d1 >> 5) + (vlc & ((1 << suffixLen) - 1));
                u[0] = d1 + 1;
                vlc >>>= suffixLen;
                suffixLen = (d2 >> 2) & 0x7;
                consumed += suffixLen;
                d2 = (d2 >> 5) + (vlc & ((1 << suffixLen) - 1));
                u[1] = d2 + 1;
            }
        } else if (mode == 4) {
            int d1 = UVLC_DEC[vlc & 0x7];
            vlc >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            int d2 = UVLC_DEC[vlc & 0x7];
            vlc >>>= d2 & 0x3;
            consumed += d2 & 0x3;
            int suffixLen = (d1 >> 2) & 0x7;
            consumed += suffixLen;
            d1 = (d1 >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[0] = d1 + 3;
            vlc >>>= suffixLen;
            suffixLen = (d2 >> 2) & 0x7;
            consumed += suffixLen;
            d2 = (d2 >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[1] = d2 + 3;
        }
        return consumed;
    }

    private static int decodeNoninitUvlc(int vlc, int mode, int[] u) {
        int consumed = 0;
        if (mode == 0) {
            u[0] = u[1] = 1;
        } else if (mode <= 2) {
            int d = UVLC_DEC[vlc & 0x7];
            vlc >>>= d & 0x3;
            consumed += d & 0x3;
            int suffixLen = (d >> 2) & 0x7;
            consumed += suffixLen;
            d = (d >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[0] = (mode == 1) ? d + 1 : 1;
            u[1] = (mode == 1) ? 1 : d + 1;
        } else if (mode == 3) {
            int d1 = UVLC_DEC[vlc & 0x7];
            vlc >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            int d2 = UVLC_DEC[vlc & 0x7];
            vlc >>>= d2 & 0x3;
            consumed += d2 & 0x3;
            int suffixLen = (d1 >> 2) & 0x7;
            consumed += suffixLen;
            d1 = (d1 >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[0] = d1 + 1;
            vlc >>>= suffixLen;
            suffixLen = (d2 >> 2) & 0x7;
            consumed += suffixLen;
            d2 = (d2 >> 5) + (vlc & ((1 << suffixLen) - 1));
            u[1] = d2 + 1;
        }
        return consumed;
    }

    // ---- main entry ----

    /**
     * Decodes one HT code-block into {@code band.coeffs()}; on malformed
     * data a warning is recorded and the block is left as zeros.
     */
    public static void decode(CodeBlock cb, Band band, int cbStyle, List<String> warnings) {
        int width = cb.width();
        int height = cb.height();
        if (cb.totalPasses <= 0 || width <= 0 || height <= 0 || cb.segments.isEmpty()) {
            return;
        }
        if (band.roiShift != 0) {
            warnings.add("ROI is not supported in HT code-blocks");
            return;
        }
        if (band.numBps > 30) {
            warnings.add("HT code-block with more than 30 bit-planes not supported");
            return;
        }
        int zeroBplanes = cb.zeroBitplanes;
        // p is the bit position of the HT Cleanup's least significant coded
        // plane in the 2*magnitude+half domain; decoded magnitude exponents
        // are bounded by zeroBplanes + 1
        int p = band.numBps - zeroBplanes;
        if (p <= 0) {
            warnings.add("Malformed HT code-block: more zero bit-planes than bit-planes");
            return;
        }

        CodeBlock.Segment seg0 = cb.segments.get(0);
        CodeBlock.Segment seg1 = cb.segments.size() > 1 ? cb.segments.get(1) : null;
        int numPasses = seg0.passes + (seg1 != null ? seg1.passes : 0);
        int lengths1 = seg0.length;
        int lengths2 = seg1 != null ? seg1.length : 0;
        if (numPasses > 1 && lengths2 == 0) {
            numPasses = 1;
        }
        if (numPasses > 3) {
            warnings.add("HT code-block with more than 3 coding passes not supported");
            return;
        }
        if (p == 1 && numPasses > 1) {
            // SigProp/MagRef refine the plane below the cleanup; none exists
            numPasses = 1;
        }
        if (lengths1 < 2) {
            warnings.add("Malformed HT code-block: invalid cleanup length");
            return;
        }

        byte[] coded = new byte[lengths1 + lengths2];
        System.arraycopy(seg0.data, 0, coded, 0, lengths1);
        if (seg1 != null) {
            System.arraycopy(seg1.data, 0, coded, lengths1, lengths2);
        }

        int zeroBplanesP1 = zeroBplanes + 1;

        int lcup = lengths1;
        int scup = ((coded[lcup - 1] & 0xFF) << 4) + (coded[lcup - 2] & 0xF);
        if (scup < 2 || scup > lcup || scup > 4079) {
            warnings.add("Malformed HT code-block: invalid Scup");
            return;
        }

        Mel mel = new Mel();
        if (!mel.init(coded, lcup, scup)) {
            warnings.add("Malformed HT code-block: incorrect MEL segment");
            return;
        }
        Rev vlc = new Rev();
        vlc.initVlc(coded, lcup, scup);
        Fwd magsgn = new Fwd();
        magsgn.init(coded, 0, lcup - scup, 0xFF);
        Fwd sigprop = new Fwd();
        if (numPasses > 1) {
            sigprop.init(coded, lengths1, lengths2, 0);
        }
        Rev magref = new Rev();
        if (numPasses > 2) {
            magref.initMrp(coded, lengths1, lengths2);
        }

        int stride = width;
        int[] decoded = new int[width * height];
        int[] sigma = new int[4 * 132];       // sigma1, sigma2, mbr1, mbr2
        final int sigma1 = 0;
        final int sigma2 = 132;
        final int mbr1 = 264;
        final int mbr2 = 396;
        byte[] lineState = new byte[528];
        boolean stripeCausal = (cbStyle & CodingStyle.CB_VCAUSAL) != 0;

        int[] uq = new int[2];
        int[] qinf = new int[2];

        int sipIdx = sigma1;
        int sipShift = 0;
        int lsp = 0;
        lineState[0] = 0;
        int run = mel.getRun();
        int cQ = 0;
        int sp = 0;

        // initial 2 rows
        for (int x = 0; x < width; x += 4) {
            int vlcVal = vlc.fetch();
            qinf[0] = HtVlcTables.VLC_TBL0[(cQ << 7) | (vlcVal & 0x7F)];
            if (cQ == 0) {
                run -= 2;
                qinf[0] = (run == -1) ? qinf[0] : 0;
                if (run < 0) {
                    run = mel.getRun();
                }
            }
            cQ = ((qinf[0] & 0x10) >> 4) | ((qinf[0] & 0xE0) >> 5);
            vlcVal = vlc.advance(qinf[0] & 0x7);
            sigma[sipIdx] |= (((qinf[0] & 0x30) >> 4) | ((qinf[0] & 0xC0) >> 2)) << sipShift;

            qinf[1] = 0;
            if (x + 2 < width) {
                qinf[1] = HtVlcTables.VLC_TBL0[(cQ << 7) | (vlcVal & 0x7F)];
                if (cQ == 0) {
                    run -= 2;
                    qinf[1] = (run == -1) ? qinf[1] : 0;
                    if (run < 0) {
                        run = mel.getRun();
                    }
                }
                cQ = ((qinf[1] & 0x10) >> 4) | ((qinf[1] & 0xE0) >> 5);
                vlcVal = vlc.advance(qinf[1] & 0x7);
            }
            sigma[sipIdx] |= ((qinf[1] & 0x30) | ((qinf[1] & 0xC0) << 2)) << (4 + sipShift);

            sipIdx += (x & 0x7) != 0 ? 1 : 0;
            sipShift ^= 0x10;

            int uvlcMode = ((qinf[0] & 0x8) >> 3) | ((qinf[1] & 0x8) >> 2);
            if (uvlcMode == 3) {
                run -= 2;
                uvlcMode += (run == -1) ? 1 : 0;
                if (run < 0) {
                    run = mel.getRun();
                }
            }
            int consumed = decodeInitUvlc(vlcVal, uvlcMode, uq);
            if (uq[0] > zeroBplanesP1 || uq[1] > zeroBplanesP1) {
                warnings.add("Malformed HT code-block: U_q exceeds zero bit-planes + 1");
                return;
            }
            vlcVal = vlc.advance(consumed);

            int locs = 0xFF;
            if (x + 4 > width) {
                locs >>= (x + 4 - width) << 1;
            }
            locs = height > 1 ? locs : (locs & 0x55);
            if (((((qinf[0] & 0xF0) >> 4) | (qinf[1] & 0xF0)) & ~locs) != 0) {
                warnings.add("Malformed HT code-block: significance outside block");
                return;
            }

            for (int q = 0; q < 2; q++) {
                int qi = qinf[q];
                int u = uq[q];
                int locShift = q * 4;
                // sample 0: (0,0) of quad
                if ((qi & 0x10) != 0) {
                    int msVal = magsgn.fetch();
                    int mN = u - ((qi >> 12) & 1);
                    magsgn.advance(mN);
                    int val = msVal << 31;
                    int vN = msVal & ((1 << mN) - 1);
                    vN |= ((qi & 0x100) >> 8) << mN;
                    vN |= 1;
                    decoded[sp] = val | ((vN + 2) << (p - 1));
                } else if ((locs & (0x1 << locShift)) != 0) {
                    decoded[sp] = 0;
                }
                // sample 1: (0,1)
                if ((qi & 0x20) != 0) {
                    int msVal = magsgn.fetch();
                    int mN = u - ((qi >> 13) & 1);
                    magsgn.advance(mN);
                    int val = msVal << 31;
                    int vN = msVal & ((1 << mN) - 1);
                    vN |= ((qi & 0x200) >> 9) << mN;
                    vN |= 1;
                    decoded[sp + stride] = val | ((vN + 2) << (p - 1));
                    int t = lineState[lsp] & 0x7F;
                    int e = 32 - Integer.numberOfLeadingZeros(vN);
                    lineState[lsp] = (byte) (0x80 | Math.max(t, e));
                } else if ((locs & (0x2 << locShift)) != 0) {
                    decoded[sp + stride] = 0;
                }
                ++lsp;
                ++sp;
                // sample 2: (1,0)
                if ((qi & 0x40) != 0) {
                    int msVal = magsgn.fetch();
                    int mN = u - ((qi >> 14) & 1);
                    magsgn.advance(mN);
                    int val = msVal << 31;
                    int vN = msVal & ((1 << mN) - 1);
                    vN |= ((qi & 0x400) >> 10) << mN;
                    vN |= 1;
                    decoded[sp] = val | ((vN + 2) << (p - 1));
                } else if ((locs & (0x4 << locShift)) != 0) {
                    decoded[sp] = 0;
                }
                lineState[lsp] = 0;
                // sample 3: (1,1)
                if ((qi & 0x80) != 0) {
                    int msVal = magsgn.fetch();
                    int mN = u - ((qi >> 15) & 1);
                    magsgn.advance(mN);
                    int val = msVal << 31;
                    int vN = msVal & ((1 << mN) - 1);
                    vN |= ((qi & 0x800) >> 11) << mN;
                    vN |= 1;
                    decoded[sp + stride] = val | ((vN + 2) << (p - 1));
                    lineState[lsp] = (byte) (0x80 | (32 - Integer.numberOfLeadingZeros(vN)));
                } else if ((locs & (0x8 << locShift)) != 0) {
                    decoded[sp + stride] = 0;
                }
                ++sp;
            }
        }

        // non-initial rows
        for (int y = 2; y < height; /* incremented in loop */) {
            sipShift ^= 0x2;
            sipShift &= ~0x10;
            sipIdx = (y & 0x4) != 0 ? sigma2 : sigma1;

            lsp = 0;
            int ls0 = lineState[0] & 0xFF;
            lineState[0] = 0;
            sp = y * stride;
            cQ = 0;
            for (int x = 0; x < width; x += 4) {
                cQ |= (ls0 >> 7);
                cQ |= ((lineState[lsp + 1] & 0xFF) >> 5) & 0x4;
                int vlcVal = vlc.fetch();
                qinf[0] = HtVlcTables.VLC_TBL1[(cQ << 7) | (vlcVal & 0x7F)];
                if (cQ == 0) {
                    run -= 2;
                    qinf[0] = (run == -1) ? qinf[0] : 0;
                    if (run < 0) {
                        run = mel.getRun();
                    }
                }
                cQ = ((qinf[0] & 0x40) >> 5) | ((qinf[0] & 0x80) >> 6);
                vlcVal = vlc.advance(qinf[0] & 0x7);
                sigma[sipIdx] |= (((qinf[0] & 0x30) >> 4) | ((qinf[0] & 0xC0) >> 2)) << sipShift;

                qinf[1] = 0;
                if (x + 2 < width) {
                    cQ |= ((lineState[lsp + 1] & 0xFF) >> 7);
                    cQ |= ((lineState[lsp + 2] & 0xFF) >> 5) & 0x4;
                    qinf[1] = HtVlcTables.VLC_TBL1[(cQ << 7) | (vlcVal & 0x7F)];
                    if (cQ == 0) {
                        run -= 2;
                        qinf[1] = (run == -1) ? qinf[1] : 0;
                        if (run < 0) {
                            run = mel.getRun();
                        }
                    }
                    cQ = ((qinf[1] & 0x40) >> 5) | ((qinf[1] & 0x80) >> 6);
                    vlcVal = vlc.advance(qinf[1] & 0x7);
                }
                sigma[sipIdx] |= ((qinf[1] & 0x30) | ((qinf[1] & 0xC0) << 2)) << (4 + sipShift);

                sipIdx += (x & 0x7) != 0 ? 1 : 0;
                sipShift ^= 0x10;

                int uvlcMode = ((qinf[0] & 0x8) >> 3) | ((qinf[1] & 0x8) >> 2);
                int consumed = decodeNoninitUvlc(vlcVal, uvlcMode, uq);
                vlcVal = vlc.advance(consumed);

                if ((qinf[0] & 0xF0 & ((qinf[0] & 0xF0) - 1)) != 0) { // gamma_q
                    int e = ls0 & 0x7F;
                    e = Math.max(e, lineState[lsp + 1] & 0x7F);
                    uq[0] += e > 2 ? e - 2 : 0;
                }
                if ((qinf[1] & 0xF0 & ((qinf[1] & 0xF0) - 1)) != 0) {
                    int e = lineState[lsp + 1] & 0x7F;
                    e = Math.max(e, lineState[lsp + 2] & 0x7F);
                    uq[1] += e > 2 ? e - 2 : 0;
                }
                if (uq[0] > zeroBplanesP1 || uq[1] > zeroBplanesP1) {
                    warnings.add("Malformed HT code-block: U_q exceeds zero bit-planes + 1");
                    return;
                }

                ls0 = lineState[lsp + 2] & 0xFF;
                lineState[lsp + 1] = 0;
                lineState[lsp + 2] = 0;

                int locs = 0xFF;
                if (x + 4 > width) {
                    locs >>= (x + 4 - width) << 1;
                }
                locs = y + 2 <= height ? locs : (locs & 0x55);
                if (((((qinf[0] & 0xF0) >> 4) | (qinf[1] & 0xF0)) & ~locs) != 0) {
                    warnings.add("Malformed HT code-block: significance outside block");
                    return;
                }

                for (int q = 0; q < 2; q++) {
                    int qi = qinf[q];
                    int u = uq[q];
                    int locShift = q * 4;
                    if ((qi & 0x10) != 0) {
                        int msVal = magsgn.fetch();
                        int mN = u - ((qi >> 12) & 1);
                        magsgn.advance(mN);
                        int val = msVal << 31;
                        int vN = msVal & ((1 << mN) - 1);
                        vN |= ((qi & 0x100) >> 8) << mN;
                        vN |= 1;
                        decoded[sp] = val | ((vN + 2) << (p - 1));
                    } else if ((locs & (0x1 << locShift)) != 0) {
                        decoded[sp] = 0;
                    }
                    if ((qi & 0x20) != 0) {
                        int msVal = magsgn.fetch();
                        int mN = u - ((qi >> 13) & 1);
                        magsgn.advance(mN);
                        int val = msVal << 31;
                        int vN = msVal & ((1 << mN) - 1);
                        vN |= ((qi & 0x200) >> 9) << mN;
                        vN |= 1;
                        decoded[sp + stride] = val | ((vN + 2) << (p - 1));
                        int t = lineState[lsp] & 0x7F;
                        int e = 32 - Integer.numberOfLeadingZeros(vN);
                        lineState[lsp] = (byte) (0x80 | Math.max(t, e));
                    } else if ((locs & (0x2 << locShift)) != 0) {
                        decoded[sp + stride] = 0;
                    }
                    ++lsp;
                    ++sp;
                    if ((qi & 0x40) != 0) {
                        int msVal = magsgn.fetch();
                        int mN = u - ((qi >> 14) & 1);
                        magsgn.advance(mN);
                        int val = msVal << 31;
                        int vN = msVal & ((1 << mN) - 1);
                        vN |= ((qi & 0x400) >> 10) << mN;
                        vN |= 1;
                        decoded[sp] = val | ((vN + 2) << (p - 1));
                    } else if ((locs & (0x4 << locShift)) != 0) {
                        decoded[sp] = 0;
                    }
                    if ((qi & 0x80) != 0) {
                        int msVal = magsgn.fetch();
                        int mN = u - ((qi >> 15) & 1);
                        magsgn.advance(mN);
                        int val = msVal << 31;
                        int vN = msVal & ((1 << mN) - 1);
                        vN |= ((qi & 0x800) >> 11) << mN;
                        vN |= 1;
                        decoded[sp + stride] = val | ((vN + 2) << (p - 1));
                        lineState[lsp] = (byte) (0x80
                                | (32 - Integer.numberOfLeadingZeros(vN)));
                    } else if ((locs & (0x8 << locShift)) != 0) {
                        decoded[sp + stride] = 0;
                    }
                    ++sp;
                }
            }

            y += 2;
            if (numPasses > 1 && (y & 3) == 0) {
                if (numPasses > 2) {
                    // MagRef on the completed stripe
                    magRefStripe(magref, decoded, sigma,
                            (y & 0x4) != 0 ? sigma1 : sigma2, (y - 4) * stride,
                            width, stride, p);
                }
                if (y >= 4) {
                    buildMbr(sigma, (y & 0x4) != 0 ? sigma1 : sigma2,
                            (y & 0x4) != 0 ? mbr1 : mbr2, width);
                }
                if (y >= 8) {
                    sigPropStripe(sigprop, decoded, sigma,
                            (y & 0x4) != 0 ? sigma2 : sigma1,
                            (y & 0x4) != 0 ? mbr2 : mbr1,
                            (y & 0x4) != 0 ? sigma1 : sigma2,
                            (y & 0x4) != 0 ? mbr1 : mbr2,
                            (y - 8) * stride, width, stride, p,
                            0xFFFFFFFF, stripeCausal, true);
                    // clear current sigma
                    int cs = (y & 0x4) != 0 ? sigma2 : sigma1;
                    java.util.Arrays.fill(sigma, cs, cs + (((width + 7) >> 3) + 1), 0);
                }
            }
        }

        // terminating: last (possibly incomplete) stripes
        if (numPasses > 1) {
            if (numPasses > 2 && ((height & 3) == 1 || (height & 3) == 2)) {
                magRefStripe(magref, decoded, sigma,
                        (height & 0x4) != 0 ? sigma2 : sigma1,
                        (height & 0xFFFFFC) * stride, width, stride, p);
            }
            if ((height & 3) == 1 || (height & 3) == 2) {
                buildMbr(sigma, (height & 0x4) != 0 ? sigma2 : sigma1,
                        (height & 0x4) != 0 ? mbr2 : mbr1, width);
            }

            int st = height;
            st -= height > 6 ? (((height + 1) & 3) + 3) : height;
            for (int y = st; y < height; y += 4) {
                int pattern = 0xFFFFFFFF;
                if (height - y == 3) {
                    pattern = 0x77777777;
                } else if (height - y == 2) {
                    pattern = 0x33333333;
                } else if (height - y == 1) {
                    pattern = 0x11111111;
                }
                int curSig = (y & 0x4) != 0 ? sigma2 : sigma1;
                int curMbr = (y & 0x4) != 0 ? mbr2 : mbr1;
                int nxtSig = (y & 0x4) != 0 ? sigma1 : sigma2;
                int nxtMbr = (y & 0x4) != 0 ? mbr1 : mbr2;
                sigPropStripe(sigprop, decoded, sigma, curSig, curMbr, nxtSig, nxtMbr,
                        y * stride, width, stride, p, pattern, stripeCausal,
                        height - y > 4);
            }
        }

        // sign-magnitude to signed values
        for (int i = 0; i < decoded.length; i++) {
            int v = decoded[i] & 0x7FFFFFFF;
            decoded[i] = decoded[i] < 0 ? -v : v;
        }

        // store into the band buffer (matching Part-1 conventions)
        int[] coeffs = band.coeffs();
        int bw = band.width();
        int offX = cb.x0 - band.x0;
        int offY = cb.y0 - band.y0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = decoded[y * stride + x];
                coeffs[(offY + y) * bw + (offX + x)] = band.reversible ? v / 2 : v;
            }
        }
    }

    /** HT MagRef for one complete 4-row stripe (T.814 clause 7.5). */
    private static void magRefStripe(Rev magref, int[] decoded, int[] sigma,
                                     int curSig, int dppBase, int width, int stride, int p) {
        int half = 1 << (p - 2);
        for (int i = 0; i < width; i += 8) {
            int cwd = magref.fetchMrp();
            int sig = sigma[curSig++];
            int colMask = 0xF;
            int dp = dppBase + i;
            if (sig != 0) {
                for (int j = 0; j < 8; ++j, dp++) {
                    if ((sig & colMask) != 0) {
                        int sampleMask = 0x11111111 & colMask;
                        for (int r = 0; r < 4; r++) {
                            if ((sig & sampleMask) != 0) {
                                int sym = cwd & 1;
                                decoded[dp + r * stride] ^= (1 - sym) << (p - 1);
                                decoded[dp + r * stride] |= half;
                                cwd >>>= 1;
                            }
                            sampleMask += sampleMask;
                        }
                    }
                    colMask <<= 4;
                }
            }
            magref.advanceMrp(Integer.bitCount(sig));
        }
    }

    /** Builds the "may become significant" map for a completed stripe. */
    private static void buildMbr(int[] sigma, int sigBase, int mbrBase, int width) {
        int prev = 0;
        int sig = sigBase;
        int mbr = mbrBase;
        for (int i = 0; i < width; i += 8, mbr++, sig++) {
            int m = sigma[sig];
            m |= prev >>> 28;
            m |= sigma[sig] << 4;
            m |= sigma[sig] >>> 4;
            m |= sigma[sig + 1] << 28;
            prev = sigma[sig];
            int t = m;
            int z = m;
            z |= (t & 0x77777777) << 1;
            z |= (t & 0xEEEEEEEE) >>> 1;
            sigma[mbr] = z & ~sigma[sig];
        }
    }

    /** HT SigProp for one stripe (T.814 clause 7.4). */
    private static void sigPropStripe(Fwd sigprop, int[] decoded, int[] sigma,
                                      int curSigBase, int curMbrBase, int nxtSigBase,
                                      int nxtMbrBase, int dpBase, int width, int stride,
                                      int p, int pattern, boolean stripeCausal,
                                      boolean addNextStripe) {
        // add membership from the next stripe
        if (addNextStripe) {
            int prev = 0;
            int curSig = curSigBase;
            int curMbr = curMbrBase;
            int nxtSig = nxtSigBase;
            for (int i = 0; i < width; i += 8, curMbr++, curSig++, nxtSig++) {
                int t = sigma[nxtSig];
                t |= prev >>> 28;
                t |= sigma[nxtSig] << 4;
                t |= sigma[nxtSig] >>> 4;
                t |= sigma[nxtSig + 1] << 28;
                prev = sigma[nxtSig];
                if (!stripeCausal) {
                    sigma[curMbr] |= (t & 0x11111111) << 3;
                }
                sigma[curMbr] &= ~sigma[curSig];
            }
        }

        int curSig = curSigBase;
        int curMbr = curMbrBase;
        int nxtSig = nxtSigBase;
        int nxtMbr = nxtMbrBase;
        int val = 3 << (p - 2);
        for (int i = 0; i < width;
                i += 8, curSig++, curMbr++, nxtSig++, nxtMbr++) {
            int mbr = sigma[curMbr] & pattern;
            int newSig = 0;
            if (mbr != 0) {
                for (int n = 0; n < 8; n += 4) {
                    int cwd = sigprop.fetch();
                    int cnt = 0;
                    int dp = dpBase + i + n;
                    int colMask = 0xF << (4 * n);
                    int invSig = ~sigma[curSig] & pattern;
                    int end = n + 4 + i < width ? n + 4 : width - i;
                    for (int j = n; j < end; ++j, ++dp, colMask <<= 4) {
                        if ((colMask & mbr) == 0) {
                            continue;
                        }
                        int sampleMask = 0x11111111 & colMask;
                        // per-row propagation patterns: {0x32, 0x74, 0xE8, 0xC0}
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                int t = 0x32 << (j * 4);
                                mbr |= t & invSig;
                            }
                            cwd >>>= 1;
                            ++cnt;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                int t = 0x74 << (j * 4);
                                mbr |= t & invSig;
                            }
                            cwd >>>= 1;
                            ++cnt;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                int t = 0xE8 << (j * 4);
                                mbr |= t & invSig;
                            }
                            cwd >>>= 1;
                            ++cnt;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                int t = 0xC0 << (j * 4);
                                mbr |= t & invSig;
                            }
                            cwd >>>= 1;
                            ++cnt;
                        }
                    }

                    if ((newSig & (0xFFFF << (4 * n))) != 0) {
                        int dp2 = dpBase + i + n;
                        int colMask2 = 0xF << (4 * n);
                        for (int j = n; j < end; ++j, ++dp2, colMask2 <<= 4) {
                            if ((colMask2 & newSig) == 0) {
                                continue;
                            }
                            int sampleMask = 0x11111111 & colMask2;
                            for (int r = 0; r < 4; r++) {
                                if ((newSig & sampleMask) != 0) {
                                    decoded[dp2 + r * stride] |= ((cwd & 1) << 31) | val;
                                    cwd >>>= 1;
                                    ++cnt;
                                }
                                sampleMask += sampleMask;
                            }
                        }
                    }
                    sigprop.advance(cnt);

                    if (n == 4) {
                        int t = newSig >>> 28;
                        t |= ((t & 0xE) >> 1) | ((t & 7) << 1);
                        sigma[curMbr + 1] |= t & ~sigma[curSig + 1];
                    }
                }
            }
            newSig |= sigma[curSig];
            int ux = (newSig & 0x88888888) >>> 3;
            int tx = ux | (ux << 4) | (ux >>> 4);
            if (i > 0) {
                sigma[nxtMbr - 1] |= (ux << 28) & ~sigma[nxtSig - 1];
            }
            sigma[nxtMbr] |= tx & ~sigma[nxtSig];
            sigma[nxtMbr + 1] |= (ux >>> 28) & ~sigma[nxtSig + 1];
        }
    }
}
