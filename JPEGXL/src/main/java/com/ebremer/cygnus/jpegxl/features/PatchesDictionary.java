package com.ebremer.cygnus.jpegxl.features;

import com.ebremer.cygnus.jpegxl.entropy.EntropyDecoder;
import com.ebremer.cygnus.jpegxl.io.Bits;
import com.ebremer.cygnus.jpegxl.io.Bounds;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The patch dictionary from LfGlobal (18181-1 annex G.3). */
public final class PatchesDictionary {

    /** Patch blend modes. */
    public static final int MODE_NONE = 0;
    public static final int MODE_REPLACE = 1;
    public static final int MODE_ADD = 2;
    public static final int MODE_MUL = 3;
    public static final int MODE_BLEND_ABOVE = 4;
    public static final int MODE_BLEND_BELOW = 5;
    public static final int MODE_MULADD_ABOVE = 6;
    public static final int MODE_MULADD_BELOW = 7;

    public record Blending(int mode, int alphaChannel, boolean clamp) {
    }

    public record Patch(int ref, int x0, int y0, int width, int height,
                        int[][] positions, Blending[][] blendings) {
    }

    public final List<Patch> patches = new ArrayList<>();

    public static PatchesDictionary read(Bits in, int numExtraChannels, int numAlphaChannels,
            long framePixels) throws IOException {
        PatchesDictionary dict = new PatchesDictionary();
        EntropyDecoder stream = EntropyDecoder.read(in, 10, true);
        // Every count here is a hybrid-uint that can reach 2^31 from a few
        // bytes, and each one sizes an allocation. A patch stamps at least one
        // pixel per position, so the dictionary cannot meaningfully name more
        // positions than the frame has pixels (1 << 24 is libjxl's absolute
        // kMaxPatches); the blending table multiplies positions by the extra
        // channels and gets its own ceiling.
        long cap = Math.min(1 << 24, Math.max(1024, framePixels));
        int numPatches = Bounds.count(stream.readSymbol(in, 0), cap, "patch");
        long totalPositions = 0;
        for (int i = 0; i < numPatches; i++) {
            int ref = stream.readSymbol(in, 1);
            if (ref < 0 || ref > 3) {
                throw new IOException("patch reference frame out of range");
            }
            int x0 = stream.readSymbol(in, 3);
            int y0 = stream.readSymbol(in, 3);
            if ((x0 | y0) < 0) {
                throw new IOException("negative patch origin");
            }
            int width = 1 + stream.readSymbol(in, 2);
            int height = 1 + stream.readSymbol(in, 2);
            if (width <= 0 || height <= 0) {
                throw new IOException("bad patch size");
            }
            int count = 1 + Bounds.count(stream.readSymbol(in, 7), cap - 1, "patch position");
            totalPositions += count;
            if (totalPositions > cap
                    || totalPositions * (numExtraChannels + 1L) > (1 << 24)) {
                throw new IOException("too many patch positions (" + totalPositions
                        + " for " + framePixels + " frame pixels)");
            }
            int[][] positions = new int[count][2];
            Blending[][] blendings = new Blending[count][numExtraChannels + 1];
            for (int j = 0; j < count; j++) {
                int x;
                int y;
                if (j == 0) {
                    x = stream.readSymbol(in, 4);
                    y = stream.readSymbol(in, 4);
                } else {
                    x = Bits.unpackSigned(stream.readSymbol(in, 6)) + positions[j - 1][0];
                    y = Bits.unpackSigned(stream.readSymbol(in, 6)) + positions[j - 1][1];
                }
                positions[j][0] = x;
                positions[j][1] = y;
                for (int k = 0; k < numExtraChannels + 1; k++) {
                    int mode = stream.readSymbol(in, 5);
                    if (mode >= 8) {
                        throw new IOException("bad patch blend mode " + mode);
                    }
                    int alpha = 0;
                    boolean clamp = false;
                    if (mode > 3 && numAlphaChannels > 1) {
                        alpha = stream.readSymbol(in, 8);
                        if (alpha >= numExtraChannels) {
                            throw new IOException("patch alpha channel out of range");
                        }
                    }
                    if (mode > 2) {
                        clamp = stream.readSymbol(in, 9) != 0;
                    }
                    blendings[j][k] = new Blending(mode, alpha, clamp);
                }
            }
            dict.patches.add(new Patch(ref, x0, y0, width, height, positions, blendings));
        }
        stream.finish(in);
        if (Boolean.getBoolean("jxl.debug")) {
            int positionCount = 0;
            StringBuilder sb = new StringBuilder();
            for (PatchesDictionary.Patch p : dict.patches) {
                positionCount += p.positions.length;
            }
            for (int k = 0; k <= numExtraChannels; k++) {
                int[] modes = new int[8];
                for (Patch p : dict.patches) {
                    for (Blending[] bs : p.blendings) {
                        modes[bs[k].mode()]++;
                    }
                }
                sb.append(" ch").append(k).append('=').append(java.util.Arrays.toString(modes));
            }
            System.err.println("[jxl] patches: " + dict.patches.size() + " entries, "
                    + positionCount + " positions, per-channel modes:" + sb);
        }
        return dict;
    }
}
