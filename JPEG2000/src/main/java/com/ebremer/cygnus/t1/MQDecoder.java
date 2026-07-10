package com.ebremer.cygnus.t1;

/**
 * MQ arithmetic decoder as specified in ITU-T T.800 / ISO/IEC 15444-1 Annex C
 * ("software conventions" variant, Figures C.15 - C.20).
 *
 * <p>One instance decodes a single terminated segment of an EBCOT code-block
 * bit stream. Reading past the end of the segment feeds the decoder the
 * mandated 0xFF/1-bit fill, so truncated streams decode gracefully.</p>
 */
public final class MQDecoder {

    /** Probability state table, T.800 Table C.2: {Qe, NMPS, NLPS, SWITCH}. */
    private static final int[] QE = {
        0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401,
        0x4801, 0x3801, 0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401,
        0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401, 0x2201,
        0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101, 0x0AC1, 0x09C1,
        0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141, 0x0111, 0x0085,
        0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601
    };
    private static final int[] NMPS = {
        1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46
    };
    private static final int[] NLPS = {
        1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14,
        15, 16, 17, 18, 19, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46
    };
    private static final int[] SWITCH = {
        1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private byte[] data;
    private int end;         // exclusive segment end
    private int bp;          // current byte position
    private int c;           // code register (uint32 semantics)
    private int a;           // interval register
    private int ct;          // bit counter until next BYTEIN

    /** Per-context probability state index and MPS bit. */
    private final int[] icx;
    private final int[] mps;

    public MQDecoder(byte[] data, int start, int end, int numContexts) {
        this.icx = new int[numContexts];
        this.mps = new int[numContexts];
        startSegment(data, start, end);
    }

    /**
     * Re-initializes the decoder on a new terminated codeword segment
     * (INITDEC, Figure C.19) while keeping all context states.
     */
    public void startSegment(byte[] data, int start, int end) {
        this.data = data;
        this.end = Math.min(end, data.length);
        this.bp = start;
        this.ct = 0;
        this.c = b(bp) << 16;
        byteIn();
        this.c <<= 7;
        this.ct -= 7;
        this.a = 0x8000;
    }

    /** Byte at position p, or 0xFF beyond the segment (spec-mandated fill). */
    private int b(int p) {
        return (p < end && p >= 0) ? (data[p] & 0xFF) : 0xFF;
    }

    /** Sets context {@code cx} to state {@code state} with MPS 0. */
    public void setContext(int cx, int state) {
        icx[cx] = state;
        mps[cx] = 0;
    }

    /** Decodes one binary decision in context {@code cx} (T.800 Figure C.20). */
    public int decode(int cx) {
        int i = icx[cx];
        int qe = QE[i];
        int d;
        a -= qe;
        if (((c >>> 16) & 0xFFFF) < qe) {
            // LPS exchange, Figure C.17
            if (a < qe) {
                d = mps[cx];
                icx[cx] = NMPS[i];
            } else {
                d = 1 - mps[cx];
                if (SWITCH[i] == 1) {
                    mps[cx] = 1 - mps[cx];
                }
                icx[cx] = NLPS[i];
            }
            a = qe;
            renormD();
        } else {
            c -= qe << 16;
            if ((a & 0x8000) == 0) {
                // MPS exchange, Figure C.16
                if (a < qe) {
                    d = 1 - mps[cx];
                    if (SWITCH[i] == 1) {
                        mps[cx] = 1 - mps[cx];
                    }
                    icx[cx] = NLPS[i];
                } else {
                    d = mps[cx];
                    icx[cx] = NMPS[i];
                }
                renormD();
            } else {
                d = mps[cx];
            }
        }
        return d;
    }

    private void renormD() {
        do {
            if (ct == 0) {
                byteIn();
            }
            a <<= 1;
            c <<= 1;
            ct--;
        } while ((a & 0x8000) == 0);
    }

    private void byteIn() {
        if (b(bp) == 0xFF) {
            if (b(bp + 1) > 0x8F) {
                c += 0xFF00;
                ct = 8;
            } else {
                bp++;
                c += b(bp) << 9;
                ct = 7;
            }
        } else {
            bp++;
            c += b(bp) << 8;
            ct = 8;
        }
    }
}
