package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.io.BitWriter;
import java.util.List;

/**
 * Writes the patch dictionary that heads a frame's LfGlobal — the inverse of
 * {@link com.ebremer.cygnus.jpegxl.features.PatchesDictionary}. Each patch names
 * a rectangle in a reference frame and the canvas positions it is stamped at; the
 * blend is always {@code REPLACE} (mode&nbsp;1), which copies the reference pixels
 * over the canvas and needs no alpha or clamp field.
 */
final class PatchDictWriter {

    /** One reference rectangle stamped at each of {@code positions} (x, y pairs). */
    record Patch(int ref, int x0, int y0, int width, int height, int[][] positions) {
    }

    private PatchDictWriter() {
    }

    /** Writes {@code patches} into {@code out} as the entropy-coded dictionary. */
    static void write(BitWriter out, List<Patch> patches, int numExtraChannels) {
        EntropyEncoder enc = new EntropyEncoder(10, false, false, false);
        emit(enc, out, false, patches, numExtraChannels);   // count
        enc.writeSpec(out);
        emit(enc, out, true, patches, numExtraChannels);     // write
        enc.finishSection(out);
    }

    private static void emit(EntropyEncoder enc, BitWriter out, boolean write,
            List<Patch> patches, int numEC) {
        sym(enc, out, write, 0, patches.size());
        for (Patch p : patches) {
            sym(enc, out, write, 1, p.ref());
            sym(enc, out, write, 3, p.x0());
            sym(enc, out, write, 3, p.y0());
            sym(enc, out, write, 2, p.width() - 1);
            sym(enc, out, write, 2, p.height() - 1);
            sym(enc, out, write, 7, p.positions().length - 1);
            int px = 0;
            int py = 0;
            for (int j = 0; j < p.positions().length; j++) {
                int x = p.positions()[j][0];
                int y = p.positions()[j][1];
                if (j == 0) {
                    sym(enc, out, write, 4, x);
                    sym(enc, out, write, 4, y);
                } else {
                    sym(enc, out, write, 6, packSigned(x - px));
                    sym(enc, out, write, 6, packSigned(y - py));
                }
                px = x;
                py = y;
                for (int k = 0; k < numEC + 1; k++) {
                    sym(enc, out, write, 5, 1);   // MODE_REPLACE
                }
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
