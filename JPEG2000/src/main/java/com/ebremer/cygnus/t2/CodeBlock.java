package com.ebremer.cygnus.t2;

import java.util.ArrayList;
import java.util.List;

/**
 * One code-block: its geometry within a subband plus the Tier-2 decoding
 * state that persists across packets (T.800 B.10).
 */
public final class CodeBlock {

    /** Bounds in subband coordinates. */
    public final int x0, y0, x1, y1;

    // ---- Tier-2 state ----
    /** True once the code-block has been included in some packet. */
    public boolean included;
    /** Code-word length indicator, starts at 3 (B.10.7.1). */
    public int lblock = 3;
    /** Coding passes accumulated so far. */
    public int totalPasses;
    /** Missing (all-zero) most significant bit-planes, from the MSB tag tree. */
    public int zeroBitplanes;
    /** Terminated code-word segments accumulated so far. */
    public final List<Segment> segments = new ArrayList<>();
    /** True while the trailing segment has not been terminated. */
    public boolean segmentOpen;

    public CodeBlock(int x0, int y0, int x1, int y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }

    /** A code-word segment: contiguous MQ- or raw-coded bytes for some passes. */
    public static final class Segment {
        public int passes;
        public byte[] data = new byte[0];
        public int length;

        void append(byte[] src, int off, int n) {
            if (length + n > data.length) {
                int cap = Math.max(length + n, data.length * 2);
                byte[] grown = new byte[cap];
                System.arraycopy(data, 0, grown, 0, length);
                data = grown;
            }
            System.arraycopy(src, off, data, length, n);
            length += n;
        }
    }

    /**
     * Appends {@code passes}/{@code bytes} to the open segment, opening one
     * if needed. With {@code keepData} false the bytes are not stored (used
     * when the resolution is being discarded), but pass/segment accounting
     * still advances so later packet headers parse correctly.
     */
    public void contribute(int passes, byte[] src, int off, int n,
                           boolean terminatedAfter, boolean keepData) {
        Segment seg;
        if (segmentOpen) {
            seg = segments.getLast();
        } else {
            seg = new Segment();
            segments.add(seg);
        }
        seg.passes += passes;
        if (keepData) {
            seg.append(src, off, n);
        }
        segmentOpen = !terminatedAfter;
        totalPasses += passes;
    }
}
