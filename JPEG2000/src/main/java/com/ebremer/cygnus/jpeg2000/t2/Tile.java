package com.ebremer.cygnus.jpeg2000.t2;

import com.ebremer.cygnus.jpeg2000.codestream.Codestream;
import com.ebremer.cygnus.jpeg2000.codestream.Siz;

/** One tile: reference-grid bounds and per-component structure. */
public final class Tile {

    public final int index;
    /** Bounds on the reference grid (B-7). */
    public final int x0, y0, x1, y1;
    public final TileComponent[] comps;

    private Tile(int index, int x0, int y0, int x1, int y1, TileComponent[] comps) {
        this.index = index;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.comps = comps;
    }

    public static Tile build(Codestream cs, int tileIndex) {
        Siz siz = cs.siz;
        int[] b = siz.tileBounds(tileIndex);
        TileComponent[] comps = new TileComponent[siz.numComponents];
        for (int c = 0; c < siz.numComponents; c++) {
            comps[c] = new TileComponent(c, b[0], b[1], b[2], b[3],
                    siz.xrsiz[c], siz.yrsiz[c],
                    cs.style(tileIndex, c), cs.quant(tileIndex, c),
                    cs.rgnShift(tileIndex, c), siz.precision[c]);
        }
        return new Tile(tileIndex, b[0], b[1], b[2], b[3], comps);
    }
}
