package com.ebremer.cygnus.jpeg2000.codestream;

/**
 * Quantization parameters from a QCD/QCC marker segment as they apply to one
 * tile-component (T.800 A.6.4, A.6.5, Annex E).
 */
public final class Quant {

    public static final int STYLE_NONE = 0;      // reversible, exponents only
    public static final int STYLE_DERIVED = 1;   // scalar derived (values for LL only)
    public static final int STYLE_EXPOUNDED = 2; // scalar expounded (values per subband)

    public final int style;
    public final int guardBits;
    /** Exponents; for STYLE_DERIVED only index 0 is present. */
    public final int[] exponents;
    /** 11-bit mantissas; empty for STYLE_NONE. */
    public final int[] mantissas;

    public Quant(int style, int guardBits, int[] exponents, int[] mantissas) {
        this.style = style;
        this.guardBits = guardBits;
        this.exponents = exponents;
        this.mantissas = mantissas;
    }

    /**
     * Linear subband index for band {@code band} of resolution {@code r}:
     * 0 for the LL band (r=0); for r&gt;=1 bands HL,LH,HH of resolution r are
     * 3(r-1)+1 .. 3(r-1)+3.
     */
    public static int subbandIndex(int r, int band) {
        return r == 0 ? 0 : 3 * (r - 1) + 1 + band;
    }

    /** Exponent epsilon_b for the given subband (with derived-style scaling per E.1.1). */
    public int exponent(int r, int band, int numLevels) {
        if (style == STYLE_DERIVED) {
            // epsilon_b = epsilon_0 - NL + n_b where n_b is the decomposition
            // level of the band (LL has n_b = NL, resolution r>=1 has NL-r+1)
            int nb = (r == 0) ? numLevels : numLevels - r + 1;
            return exponents[0] - numLevels + nb;
        }
        int idx = subbandIndex(r, band);
        return exponents[Math.min(idx, exponents.length - 1)];
    }

    /** 11-bit mantissa mu_b for the given subband. */
    public int mantissa(int r, int band) {
        if (mantissas.length == 0) {
            return 0;
        }
        if (style == STYLE_DERIVED) {
            return mantissas[0];
        }
        int idx = subbandIndex(r, band);
        return mantissas[Math.min(idx, mantissas.length - 1)];
    }
}
