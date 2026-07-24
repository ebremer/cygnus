package com.ebremer.cygnus.nifti;

/**
 * {@code slice_code} values: the order in which the slices along
 * {@link NiftiHeader#sliceDim()} were acquired, between
 * {@link NiftiHeader#sliceStart} and {@link NiftiHeader#sliceEnd}, each taking
 * {@link NiftiHeader#sliceDuration}. Analysis that corrects for slice timing
 * needs this; decoding does not.
 */
public final class NiftiSlice {

    /** Acquisition order not recorded. */
    public static final int UNKNOWN = 0;

    /** Sequential, increasing: 0, 1, 2, ... */
    public static final int SEQ_INC = 1;

    /** Sequential, decreasing: n, n-1, n-2, ... */
    public static final int SEQ_DEC = 2;

    /** Interleaved, increasing, odd slices first: 0, 2, 4, ... then 1, 3, 5, ... */
    public static final int ALT_INC = 3;

    /** Interleaved, decreasing, from the top. */
    public static final int ALT_DEC = 4;

    /** Interleaved, increasing, even slices first: 1, 3, 5, ... then 0, 2, 4, ... */
    public static final int ALT_INC2 = 5;

    /** Interleaved, decreasing, from the top, even slices first. */
    public static final int ALT_DEC2 = 6;

    private NiftiSlice() {
    }

    /** A readable name for {@code code}. */
    public static String name(int code) {
        return switch (code) {
            case UNKNOWN -> "UNKNOWN";
            case SEQ_INC -> "SEQ_INC";
            case SEQ_DEC -> "SEQ_DEC";
            case ALT_INC -> "ALT_INC";
            case ALT_DEC -> "ALT_DEC";
            case ALT_INC2 -> "ALT_INC2";
            case ALT_DEC2 -> "ALT_DEC2";
            default -> "slice order " + code;
        };
    }
}
