package com.ebremer.cygnus.jpeg2000.t2;

import com.ebremer.cygnus.jpeg2000.codestream.CodingStyle;
import com.ebremer.cygnus.jpeg2000.codestream.Quant;

/** One component of a tile with its resolution-level hierarchy (T.800 B.3, B.5). */
public final class TileComponent {

    public final int comp;
    /** Bounds in the component's sample coordinates (B-12). */
    public final int x0, y0, x1, y1;
    public final CodingStyle style;
    public final Quant quant;
    public final int roiShift;
    public final Resolution[] resolutions;

    TileComponent(int comp, int tx0, int ty0, int tx1, int ty1,
                  int xrsiz, int yrsiz, CodingStyle style, Quant quant,
                  int roiShift, int precision) {
        this.comp = comp;
        this.x0 = (int) Math.ceilDiv(tx0, xrsiz);
        this.y0 = (int) Math.ceilDiv(ty0, yrsiz);
        this.x1 = (int) Math.ceilDiv(tx1, xrsiz);
        this.y1 = (int) Math.ceilDiv(ty1, yrsiz);
        this.style = style;
        this.quant = quant;
        this.roiShift = roiShift;
        this.resolutions = new Resolution[style.numLevels + 1];
        for (int r = 0; r <= style.numLevels; r++) {
            resolutions[r] = new Resolution(r, x0, y0, x1, y1, style, quant,
                    roiShift, precision);
        }
    }

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }
}
