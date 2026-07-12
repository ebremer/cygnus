package com.ebremer.cygnus.jpeg2000.codestream;

/**
 * Image and tile size parameters from the SIZ marker segment (T.800 A.5.1).
 */
public final class Siz {

    public final int capabilities;          // Rsiz
    public final int xsiz, ysiz;            // reference grid extent
    public final int xosiz, yosiz;          // image offset on the reference grid
    public final int xtsiz, ytsiz;          // nominal tile size
    public final int xtosiz, ytosiz;        // tile grid offset
    public final int numComponents;         // Csiz
    public final int[] precision;           // bit depth per component (1..38)
    public final boolean[] signed;          // per component signedness
    public final int[] xrsiz, yrsiz;        // per component subsampling

    public Siz(int capabilities, int xsiz, int ysiz, int xosiz, int yosiz,
               int xtsiz, int ytsiz, int xtosiz, int ytosiz,
               int[] precision, boolean[] signed, int[] xrsiz, int[] yrsiz) {
        this.capabilities = capabilities;
        this.xsiz = xsiz;
        this.ysiz = ysiz;
        this.xosiz = xosiz;
        this.yosiz = yosiz;
        this.xtsiz = xtsiz;
        this.ytsiz = ytsiz;
        this.xtosiz = xtosiz;
        this.ytosiz = ytosiz;
        this.numComponents = precision.length;
        this.precision = precision;
        this.signed = signed;
        this.xrsiz = xrsiz;
        this.yrsiz = yrsiz;
    }

    public static int ceilDiv(int a, int b) {
        return (int) Math.ceilDiv((long) a, (long) b);
    }

    public int numXTiles() {
        return ceilDiv(xsiz - xtosiz, xtsiz);
    }

    public int numYTiles() {
        return ceilDiv(ysiz - ytosiz, ytsiz);
    }

    public int numTiles() {
        return numXTiles() * numYTiles();
    }

    /** Tile bounds on the reference grid (T.800 B-7): {tx0, ty0, tx1, ty1}. */
    public int[] tileBounds(int tileIndex) {
        int p = tileIndex % numXTiles();
        int q = tileIndex / numXTiles();
        int tx0 = Math.max(xtosiz + p * xtsiz, xosiz);
        int ty0 = Math.max(ytosiz + q * ytsiz, yosiz);
        int tx1 = Math.min(xtosiz + (p + 1) * xtsiz, xsiz);
        int ty1 = Math.min(ytosiz + (q + 1) * ytsiz, ysiz);
        return new int[] {tx0, ty0, tx1, ty1};
    }

    /** Width of component {@code c} over the whole image region. */
    public int compWidth(int c) {
        return ceilDiv(xsiz, xrsiz[c]) - ceilDiv(xosiz, xrsiz[c]);
    }

    /** Height of component {@code c} over the whole image region. */
    public int compHeight(int c) {
        return ceilDiv(ysiz, yrsiz[c]) - ceilDiv(yosiz, yrsiz[c]);
    }

    /** Image region width on the reference grid. */
    public int width() {
        return xsiz - xosiz;
    }

    /** Image region height on the reference grid. */
    public int height() {
        return ysiz - yosiz;
    }
}
