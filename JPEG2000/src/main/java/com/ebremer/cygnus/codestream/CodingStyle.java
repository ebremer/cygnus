package com.ebremer.cygnus.codestream;

/**
 * Coding style parameters, the merge of COD/COC marker segments as they apply
 * to one tile-component (T.800 A.6.1, A.6.2).
 */
public final class CodingStyle implements Cloneable {

    // Progression orders (SGcod)
    public static final int LRCP = 0;
    public static final int RLCP = 1;
    public static final int RPCL = 2;
    public static final int PCRL = 3;
    public static final int CPRL = 4;

    // Code-block style flags (SPcod/SPcoc)
    public static final int CB_BYPASS = 0x01;       // selective arithmetic bypass
    public static final int CB_RESET = 0x02;        // reset context probabilities
    public static final int CB_TERMALL = 0x04;      // terminate each coding pass
    public static final int CB_VCAUSAL = 0x08;      // vertically stripe-causal contexts
    public static final int CB_PREDTERM = 0x10;     // predictable termination
    public static final int CB_SEGSYM = 0x20;       // segmentation symbols
    public static final int CB_HT = 0x40;           // HT code-blocks (T.814)

    // Tile-wide parameters (COD only)
    public int progression = LRCP;
    public int numLayers = 1;
    public int mct = 0;                  // 0 = none, 1 = component transform used
    public boolean useSop = false;       // Scod bit 1
    public boolean useEph = false;       // Scod bit 2

    // Per-component parameters (COD default, COC override)
    public int numLevels = 5;            // number of decomposition levels NL
    public int xcb = 6;                  // code-block width exponent (2^xcb)
    public int ycb = 6;                  // code-block height exponent
    public int cbStyle = 0;
    public int wavelet = 0;              // 0 = 9/7 irreversible, 1 = 5/3 reversible
    /** Precinct size exponents indexed by resolution level 0..NL; {PPx,PPy} packed as (PPy<<4)|PPx. */
    public int[] precinctSizes = null;   // null = maximal (PPx = PPy = 15)

    public int ppx(int resolution) {
        if (precinctSizes == null) {
            return 15;
        }
        return precinctSizes[Math.min(resolution, precinctSizes.length - 1)] & 0x0F;
    }

    public int ppy(int resolution) {
        if (precinctSizes == null) {
            return 15;
        }
        return (precinctSizes[Math.min(resolution, precinctSizes.length - 1)] >> 4) & 0x0F;
    }

    public boolean reversible() {
        return wavelet == 1;
    }

    @Override
    public CodingStyle clone() {
        try {
            CodingStyle c = (CodingStyle) super.clone();
            if (precinctSizes != null) {
                c.precinctSizes = precinctSizes.clone();
            }
            return c;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
