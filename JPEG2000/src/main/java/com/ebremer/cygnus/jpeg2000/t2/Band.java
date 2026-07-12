package com.ebremer.cygnus.jpeg2000.t2;

/** One subband of a resolution level (T.800 B.5). */
public final class Band {

    public static final int LL = 0;
    public static final int HL = 1;
    public static final int LH = 2;
    public static final int HH = 3;

    /** Orientation: one of {@link #LL}, {@link #HL}, {@link #LH}, {@link #HH}. */
    public final int orient;
    /** Bounds in subband coordinates (B-15). */
    public final int x0, y0, x1, y1;
    /** Effective code-block size exponents (clamped by the precinct size). */
    public final int xcbExp, ycbExp;
    /** Magnitude bit-planes Mb = G + epsilon_b - 1 (+ ROI shift), Annex E. */
    public final int numBps;
    /** Quantization step (1.0 for reversible), Annex E. */
    public final float stepSize;
    /** True on the reversible (5/3, unquantized) path. */
    public final boolean reversible;
    /** ROI max-shift for this tile-component (0 = none). */
    public final int roiShift;
    /**
     * Decoded coefficients, row-major over the band bounds; allocated lazily.
     * Reversible bands hold exact integers; irreversible bands hold
     * quantizer indices scaled by 2 with a half-unit reconstruction offset
     * (dequantization multiplies by stepSize/2).
     */
    public int[] coeffs;

    public Band(int orient, int x0, int y0, int x1, int y1,
                int xcbExp, int ycbExp, int numBps, float stepSize,
                boolean reversible, int roiShift) {
        this.orient = orient;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.xcbExp = xcbExp;
        this.ycbExp = ycbExp;
        this.numBps = numBps;
        this.stepSize = stepSize;
        this.reversible = reversible;
        this.roiShift = roiShift;
    }

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }

    /** Subband gain log2 for nominal dynamic range (E-3): LL 0, HL/LH 1, HH 2. */
    public static int gainLog2(int orient) {
        return switch (orient) {
            case LL -> 0;
            case HL, LH -> 1;
            case HH -> 2;
            default -> throw new IllegalArgumentException();
        };
    }

    public int[] coeffs() {
        if (coeffs == null) {
            coeffs = new int[Math.max(0, width()) * Math.max(0, height())];
        }
        return coeffs;
    }
}
