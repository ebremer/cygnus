package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.features.Splines;
import com.ebremer.cygnus.jpegxl.io.BitWriter;

/**
 * Writes the spline dictionary that a frame's LfGlobal carries when it has the
 * splines flag — the inverse of {@link Splines#read}. A spline is a
 * centripetal Catmull-Rom curve with per-arc colour and thickness (32 Fourier
 * coefficients each), the whole dictionary entropy-coded over six contexts.
 */
final class SplineWriter {

    private SplineWriter() {
    }

    /** Writes {@code s} into {@code out} as the entropy-coded spline dictionary. */
    static void write(BitWriter out, Splines s) {
        EntropyEncoder enc = new EntropyEncoder(6, false, false, false);
        emit(enc, out, false, s);   // count
        enc.writeSpec(out);
        emit(enc, out, true, s);    // write
        enc.finishSection(out);
    }

    private static void emit(EntropyEncoder enc, BitWriter out, boolean write, Splines s) {
        // numSplines = 1 + symbol(ctx 2)
        sym(enc, out, write, 2, s.numSplines - 1);
        // starting positions: the first is absolute, the rest signed deltas (ctx 1)
        int prevX = 0;
        int prevY = 0;
        for (int i = 0; i < s.numSplines; i++) {
            int x = s.controlX[i][0];
            int y = s.controlY[i][0];
            if (i == 0) {
                sym(enc, out, write, 1, x);
                sym(enc, out, write, 1, y);
            } else {
                sym(enc, out, write, 1, packSigned(x - prevX));
                sym(enc, out, write, 1, packSigned(y - prevY));
            }
            prevX = x;
            prevY = y;
        }
        // quantAdjust = unpackSigned(symbol(ctx 0))
        sym(enc, out, write, 0, packSigned(s.quantAdjust));
        for (int i = 0; i < s.numSplines; i++) {
            int[] cx = s.controlX[i];
            int[] cy = s.controlY[i];
            int count = cx.length;
            sym(enc, out, write, 3, count - 1);
            // the decoder rebuilds control points by integrating the deltas twice,
            // so the coded delta is the second difference of the positions (the
            // first delta being the first difference)
            int dxPrev = 0;
            int dyPrev = 0;
            for (int j = 1; j < count; j++) {
                int dx = cx[j] - cx[j - 1];
                int dy = cy[j] - cy[j - 1];
                sym(enc, out, write, 4, packSigned(dx - dxPrev));
                sym(enc, out, write, 4, packSigned(dy - dyPrev));
                dxPrev = dx;
                dyPrev = dy;
            }
            for (int j = 0; j < 32; j++) {
                sym(enc, out, write, 5, packSigned(s.coeffX[i][j]));
            }
            for (int j = 0; j < 32; j++) {
                sym(enc, out, write, 5, packSigned(s.coeffY[i][j]));
            }
            for (int j = 0; j < 32; j++) {
                sym(enc, out, write, 5, packSigned(s.coeffB[i][j]));
            }
            for (int j = 0; j < 32; j++) {
                sym(enc, out, write, 5, packSigned(s.coeffSigma[i][j]));
            }
        }
    }

    private static void sym(EntropyEncoder enc, BitWriter out, boolean write, int ctx, int value) {
        if (write) {
            enc.write(out, ctx, value);
        } else {
            enc.count(ctx, value);
        }
    }

    private static int packSigned(int v) {
        return v >= 0 ? 2 * v : -2 * v - 1;
    }
}
