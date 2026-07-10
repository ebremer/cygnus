package com.ebremer.cygnus.t1;

import java.io.ByteArrayOutputStream;

/**
 * MQ arithmetic encoder (T.800 Annex C encoder flowcharts), the counterpart
 * of {@link MQDecoder}. Produces codeword segments that decode back to the
 * exact decision sequence, terminated with the FLUSH procedure so a decoder
 * reading past the data (implicit 1-fill) reproduces the committed value.
 */
public final class MQEncoder {

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

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int a = 0x8000;
    private int c = 0;
    private int ct = 12;
    private int b = 0;                // pending byte (carry may still reach it)
    private boolean havePending = false;

    private final int[] icx;
    private final int[] mps;

    public MQEncoder(int numContexts) {
        icx = new int[numContexts];
        mps = new int[numContexts];
    }

    public void setContext(int cx, int state) {
        icx[cx] = state;
        mps[cx] = 0;
    }

    public void encode(int cx, int d) {
        if (d == mps[cx]) {
            codeMps(cx);
        } else {
            codeLps(cx);
        }
    }

    private void codeMps(int cx) {
        int i = icx[cx];
        int qe = QE[i];
        a -= qe;
        if ((a & 0x8000) == 0) {
            if (a < qe) {
                a = qe;
            } else {
                c += qe;
            }
            icx[cx] = NMPS[i];
            renorm();
        } else {
            c += qe;
        }
    }

    private void codeLps(int cx) {
        int i = icx[cx];
        int qe = QE[i];
        a -= qe;
        if (a < qe) {
            c += qe;
        } else {
            a = qe;
        }
        if (SWITCH[i] == 1) {
            mps[cx] = 1 - mps[cx];
        }
        icx[cx] = NLPS[i];
        renorm();
    }

    private void renorm() {
        do {
            if (ct == 0) {
                byteOut();
            }
            a <<= 1;
            c <<= 1;
            ct--;
        } while ((a & 0x8000) == 0);
    }

    private void emitPending() {
        if (havePending) {
            out.write(b);
        }
        havePending = true;
    }

    private void byteOut() {
        if (havePending && b == 0xFF) {
            emitPending();
            b = c >>> 20;
            c &= 0xFFFFF;
            ct = 7;
        } else if (c < 0x8000000) {
            emitPending();
            b = c >>> 19;
            c &= 0x7FFFF;
            ct = 8;
        } else {
            b++; // carry into the pending byte
            if (b == 0xFF) {
                c &= 0x7FFFFFF;
                emitPending();
                b = c >>> 20;
                c &= 0xFFFFF;
                ct = 7;
            } else {
                emitPending();
                b = c >>> 19;
                c &= 0x7FFFF;
                ct = 8;
            }
        }
    }

    /** Terminates the codeword (FLUSH, Figure C.9) and returns its bytes. */
    public byte[] flush() {
        // SETBITS: give the codeword all-ones trailing bits, so the decoder's
        // implicit 1-fill past the data reproduces the committed value exactly
        int upper = c + a;
        c |= 0xFFFF;
        if (c >= upper) {
            c -= 0x8000;
        }
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if (havePending && b != 0xFF) {
            out.write(b); // a trailing 0xFF is dropped per C.2.9
        }
        havePending = false;
        return out.toByteArray();
    }
}
