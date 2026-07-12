package com.ebremer.cygnus.jpeg2000.t2;

import com.ebremer.cygnus.jpeg2000.codestream.CodingStyle;
import com.ebremer.cygnus.jpeg2000.codestream.Quant;

/** One resolution level of a tile-component (T.800 B.5). */
public final class Resolution {

    public final int r;
    /** Bounds in resolution-level coordinates (B-14). */
    public final int x0, y0, x1, y1;
    /** Precinct size exponents at this resolution. */
    public final int ppx, ppy;
    public final int numPrecWide, numPrecHigh;
    /** LL for r = 0; HL, LH, HH for r &gt; 0. */
    public final Band[] bands;
    /** Precincts in raster order over the precinct grid. */
    public final Precinct[] precincts;

    Resolution(int r, int tcx0, int tcy0, int tcx1, int tcy1,
               CodingStyle style, Quant quant, int roiShift, int compPrecision) {
        this.r = r;
        int nl = style.numLevels;
        int nd = nl - r;
        this.x0 = ceilShift(tcx0, nd);
        this.y0 = ceilShift(tcy0, nd);
        this.x1 = ceilShift(tcx1, nd);
        this.y1 = ceilShift(tcy1, nd);
        this.ppx = style.ppx(r);
        this.ppy = style.ppy(r);

        boolean reversible = style.reversible() && quant.style == Quant.STYLE_NONE;
        if (r == 0) {
            this.bands = new Band[] {
                makeBand(Band.LL, nl, tcx0, tcy0, tcx1, tcy1, style, quant,
                        roiShift, compPrecision, reversible)
            };
        } else {
            int nb = nl - r + 1;
            this.bands = new Band[] {
                makeBand(Band.HL, nb, tcx0, tcy0, tcx1, tcy1, style, quant,
                        roiShift, compPrecision, reversible),
                makeBand(Band.LH, nb, tcx0, tcy0, tcx1, tcy1, style, quant,
                        roiShift, compPrecision, reversible),
                makeBand(Band.HH, nb, tcx0, tcy0, tcx1, tcy1, style, quant,
                        roiShift, compPrecision, reversible)
            };
        }

        if (x1 > x0 && y1 > y0) {
            int gridX0 = x0 >> ppx;
            int gridY0 = y0 >> ppy;
            this.numPrecWide = Precinct.ceilShift(x1, ppx) - gridX0;
            this.numPrecHigh = Precinct.ceilShift(y1, ppy) - gridY0;
            if ((long) numPrecWide * numPrecHigh > (1 << 20)) {
                throw new DecodeLimitException("Precinct grid " + numPrecWide + "x"
                        + numPrecHigh + " exceeds the safety limit");
            }
            this.precincts = new Precinct[numPrecWide * numPrecHigh];
            for (int j = 0; j < numPrecHigh; j++) {
                for (int i = 0; i < numPrecWide; i++) {
                    long ux0 = ((long) (gridX0 + i)) << ppx;
                    long uy0 = ((long) (gridY0 + j)) << ppy;
                    long ux1 = ux0 + (1L << ppx);
                    long uy1 = uy0 + (1L << ppy);
                    precincts[j * numPrecWide + i] = new Precinct(
                            (int) Math.max(x0, ux0), (int) Math.max(y0, uy0),
                            (int) Math.min(x1, ux1), (int) Math.min(y1, uy1),
                            bands, ux0, ux1, uy0, uy1, r > 0);
                }
            }
        } else {
            this.numPrecWide = 0;
            this.numPrecHigh = 0;
            this.precincts = new Precinct[0];
        }
    }

    private Band makeBand(int orient, int nb, int tcx0, int tcy0, int tcx1, int tcy1,
                          CodingStyle style, Quant quant, int roiShift,
                          int compPrecision, boolean reversible) {
        int xob = (orient == Band.HL || orient == Band.HH) ? 1 : 0;
        int yob = (orient == Band.LH || orient == Band.HH) ? 1 : 0;
        int half = 1 << (nb - 1);
        int bx0 = ceilShift(tcx0 - half * xob, nb);
        int by0 = ceilShift(tcy0 - half * yob, nb);
        int bx1 = ceilShift(tcx1 - half * xob, nb);
        int by1 = ceilShift(tcy1 - half * yob, nb);

        int clamp = (orient == Band.LL) ? 0 : 1;
        int xcbExp = Math.max(0, Math.min(style.xcb, ppx - clamp));
        int ycbExp = Math.max(0, Math.min(style.ycb, ppy - clamp));

        int bandIdx = orient == Band.LL ? -1 : orient - 1;
        int eps = quant.exponent(r, Math.max(bandIdx, 0), style.numLevels);
        int numBps = quant.guardBits + eps - 1 + roiShift;

        float step;
        if (reversible) {
            step = 1.0f;
        } else {
            int rb = compPrecision + Band.gainLog2(orient);
            step = (float) (Math.pow(2.0, rb - eps)
                    * (1.0 + quant.mantissa(r, Math.max(bandIdx, 0)) / 2048.0));
        }
        return new Band(orient, bx0, by0, bx1, by1, xcbExp, ycbExp,
                numBps, step, reversible, roiShift);
    }

    /** ceil(x / 2^s) for possibly negative x. */
    static int ceilShift(int x, int s) {
        return (int) Math.ceilDiv(x, 1L << s);
    }
}
