package com.ebremer.cygnus.jpegxl.encoder;

import com.ebremer.cygnus.jpegxl.io.BitWriter;

/**
 * Builds valid entropy-coded streams carrying attacker-chosen symbol values,
 * for hostile-input tests: the coded counts a decoder must bound can reach
 * any 32-bit pattern from a few bytes, and this is how the tests reach them.
 * Lives in the encoder package for the package-private {@link EntropyEncoder}.
 */
public final class HostileStreams {

    private HostileStreams() {
    }

    /**
     * An LZ77-enabled stream: one literal, then a 3-symbol copy whose
     * distance value is {@code distValue} — any 32-bit pattern, including
     * bit 31 set.
     */
    public static byte[] lz77CopyStream(int literal, int distValue) {
        BitWriter bw = new BitWriter();
        EntropyEncoder enc = new EntropyEncoder(1, false, true, false);
        enc.count(0, literal);
        enc.countCopy(0, 3, distValue);
        enc.writeSpec(bw);
        enc.write(bw, 0, literal);
        enc.writeCopy(bw, 0, 3, distValue);
        enc.finishSection(bw);
        bw.zeroPadToByte();
        return bw.toByteArray();
    }

    /**
     * An entropy stream over {@code numCtx} contexts holding the given
     * {@code (ctx, value)} pairs in order, as the feature readers
     * (patches, splines, TOC permutation) consume them.
     */
    public static byte[] symbolStream(int numCtx, int[]... ctxValues) {
        BitWriter bw = new BitWriter();
        EntropyEncoder enc = new EntropyEncoder(numCtx, false, false, false);
        for (int[] cv : ctxValues) {
            enc.count(cv[0], cv[1]);
        }
        enc.writeSpec(bw);
        for (int[] cv : ctxValues) {
            enc.write(bw, cv[0], cv[1]);
        }
        enc.finishSection(bw);
        bw.zeroPadToByte();
        return bw.toByteArray();
    }
}
