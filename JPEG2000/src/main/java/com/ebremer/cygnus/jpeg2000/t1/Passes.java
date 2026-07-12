package com.ebremer.cygnus.jpeg2000.t1;

import com.ebremer.cygnus.jpeg2000.codestream.CodingStyle;

/**
 * Coding-pass numbering shared by Tier-1 and Tier-2 (T.800 D.4, B.10.7).
 * Pass 0 is the cleanup pass of the most significant non-zero bit-plane;
 * every following bit-plane contributes significance-propagation,
 * magnitude-refinement and cleanup passes in that order.
 */
public final class Passes {

    public static final int SPP = 0;
    public static final int MRP = 1;
    public static final int CUP = 2;

    private Passes() {
    }

    /** Type of absolute pass {@code p}. */
    public static int type(int p) {
        return p == 0 ? CUP : (p - 1) % 3;
    }

    /** Bit-plane index counted from the first coded plane (0-based). */
    public static int plane(int p) {
        return p == 0 ? 0 : (p + 2) / 3;
    }

    /**
     * True if pass {@code p} is raw-coded under the selective arithmetic
     * coding bypass (D.6): significance and refinement passes from the fifth
     * coded bit-plane onward, i.e. absolute passes 10, 11, 13, 14, ...
     */
    public static boolean raw(int p, int cbStyle) {
        return (cbStyle & CodingStyle.CB_BYPASS) != 0 && p >= 10 && type(p) != CUP;
    }

    /**
     * True if a codeword-segment termination occurs after pass {@code p}
     * (B.10.7.1): always with TERMALL; with BYPASS, around every raw run,
     * i.e. after each cleanup and each refinement pass from pass 9 onward.
     * HT code-blocks (T.814 clause 6) have two codeword segments: the HT
     * Cleanup, and SigProp together with MagRef.
     */
    public static boolean terminatedAfter(int p, int cbStyle) {
        if ((cbStyle & CodingStyle.CB_HT) != 0) {
            return p % 3 != 1; // after Cleanup and after MagRef
        }
        if ((cbStyle & CodingStyle.CB_TERMALL) != 0) {
            return true;
        }
        if ((cbStyle & CodingStyle.CB_BYPASS) != 0 && p >= 9) {
            return type(p) != SPP;
        }
        return false;
    }
}
