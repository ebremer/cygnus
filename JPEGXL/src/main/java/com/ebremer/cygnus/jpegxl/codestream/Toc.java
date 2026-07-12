package com.ebremer.cygnus.jpegxl.codestream;

import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.IOException;

/**
 * Table of contents: byte sizes of the frame's sections, optionally permuted.
 * Offsets are absolute positions within the codestream array, in logical
 * section order (LfGlobal, LfGroups, HfGlobal, pass groups).
 */
public final class Toc {

    public final int[] offsets;
    public final int[] sizes;
    public final boolean single;
    public final int endOffset;

    private Toc(int[] offsets, int[] sizes, boolean single, int endOffset) {
        this.offsets = offsets;
        this.sizes = sizes;
        this.single = single;
        this.endOffset = endOffset;
    }

    public int lfGlobalIndex() {
        return 0;
    }

    public int lfGroupIndex(int lfGroup) {
        return 1 + lfGroup;
    }

    public int hfGlobalIndex(FrameHeader f) {
        return 1 + f.numLfGroups;
    }

    public int passGroupIndex(FrameHeader f, int pass, int group) {
        return 1 + f.numLfGroups + 1 + pass * f.numGroups + group;
    }

    public static Toc read(Bits in, FrameHeader f) throws IOException {
        int nsections = f.numSections();
        int[] lehmer = null;
        if (in.bool()) { // permuted
            EntropyDecoder code = EntropyDecoder.read(in, 8, true);
            lehmer = readPermutation(in, code, nsections, 0);
            code.finish(in);
        }
        in.zeroPadToByte();

        int[] sizes = new int[nsections];
        for (int i = 0; i < nsections; i++) {
            sizes[i] = in.u32(0, 10, 1024, 14, 17408, 22, 4211712, 30);
        }
        in.zeroPadToByte();

        long base = in.absolutePosition();
        int[] offsets = new int[nsections];
        long off = base;
        for (int i = 0; i < nsections; i++) {
            if (off > Integer.MAX_VALUE) {
                throw new IOException("frame too large");
            }
            offsets[i] = (int) off;
            off += sizes[i];
        }
        if (off > Integer.MAX_VALUE) {
            throw new IOException("frame too large");
        }
        int end = (int) off;

        if (lehmer != null) {
            applyLehmer(offsets, sizes, lehmer);
        }
        return new Toc(offsets, sizes, nsections == 1, end);
    }

    /** Reads a Lehmer-coded permutation (also used for coefficient orders). */
    public static int[] readPermutation(Bits in, EntropyDecoder code, int size, int skip)
            throws IOException {
        int end = code.readSymbol(in, Math.min(7, Bits.ceilLog2(size + 1)));
        if (end > size - skip) {
            throw new IOException("bad permutation length");
        }
        int[] lehmer = new int[end];
        int prev = 0;
        for (int i = 0; i < end; i++) {
            prev = lehmer[i] = code.readSymbol(in, Math.min(7, Bits.ceilLog2(prev + 1)));
            if (prev >= size - (skip + i)) {
                throw new IOException("bad permutation value");
            }
        }
        return lehmer;
    }

    private static void applyLehmer(int[] offsets, int[] sizes, int[] lehmer) {
        int pos = 0;
        for (int l : lehmer) {
            int idx = pos + l;
            int off = offsets[idx];
            int size = sizes[idx];
            System.arraycopy(offsets, pos, offsets, pos + 1, idx - pos);
            System.arraycopy(sizes, pos, sizes, pos + 1, idx - pos);
            offsets[pos] = off;
            sizes[pos] = size;
            pos++;
        }
    }
}
