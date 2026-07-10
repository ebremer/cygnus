package com.ebremer.cygnus.t2;

/**
 * One precinct of a resolution level: for each subband, the grid of
 * code-blocks it covers plus the inclusion and zero-bit-planes tag trees
 * (T.800 B.10.2).
 */
public final class Precinct {

    /** Bounds in resolution-level coordinates, clipped to the resolution area. */
    public final int x0, y0, x1, y1;
    /** Per band of the owning resolution: code-blocks in raster order. */
    public final CodeBlock[][] blocks;
    /** Code-block grid dimensions per band. */
    public final int[] cbWide, cbHigh;
    public final TagTree[] inclusionTree;
    public final TagTree[] msbTree;

    /**
     * @param halveForBands true for resolutions &gt; 0, whose subband
     *        coordinates are half the resolution-level coordinates
     */
    Precinct(int x0, int y0, int x1, int y1, Band[] bands,
             long unclippedX0, long unclippedX1, long unclippedY0, long unclippedY1,
             boolean halveForBands) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        int nb = bands.length;
        this.blocks = new CodeBlock[nb][];
        this.cbWide = new int[nb];
        this.cbHigh = new int[nb];
        this.inclusionTree = new TagTree[nb];
        this.msbTree = new TagTree[nb];
        int shift = halveForBands ? 1 : 0;
        for (int b = 0; b < nb; b++) {
            Band band = bands[b];
            int pbx0 = (int) Math.max(band.x0, unclippedX0 >> shift);
            int pbx1 = (int) Math.min(band.x1, unclippedX1 >> shift);
            int pby0 = (int) Math.max(band.y0, unclippedY0 >> shift);
            int pby1 = (int) Math.min(band.y1, unclippedY1 >> shift);
            int cbx = band.xcbExp;
            int cby = band.ycbExp;
            int nw = (pbx1 > pbx0) ? ceilShift(pbx1, cbx) - (pbx0 >> cbx) : 0;
            int nh = (pby1 > pby0) ? ceilShift(pby1, cby) - (pby0 >> cby) : 0;
            cbWide[b] = nw;
            cbHigh[b] = nh;
            blocks[b] = new CodeBlock[nw * nh];
            for (int j = 0; j < nh; j++) {
                for (int i = 0; i < nw; i++) {
                    int gx = (pbx0 >> cbx) + i;
                    int gy = (pby0 >> cby) + j;
                    int bx0 = Math.max(pbx0, gx << cbx);
                    int bx1 = Math.min(pbx1, (gx + 1) << cbx);
                    int by0 = Math.max(pby0, gy << cby);
                    int by1 = Math.min(pby1, (gy + 1) << cby);
                    blocks[b][j * nw + i] = new CodeBlock(bx0, by0, bx1, by1);
                }
            }
            inclusionTree[b] = new TagTree(nw, nh);
            msbTree[b] = new TagTree(nw, nh);
        }
    }

    static int ceilShift(int v, int shift) {
        return (v + (1 << shift) - 1) >> shift;
    }
}
