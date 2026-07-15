package com.ebremer.cygnus.jpegxl.color;

/**
 * Bilinear demosaicing of a Bayer colour-filter-array plane — the mosaic a raw
 * sensor produces, one colour measured per pixel, interpolated up to full RGB.
 *
 * <p>Green is measured at half the pixels (a quincunx) and each missing green is
 * the average of its four orthogonal green neighbours; red and blue are measured
 * at a quarter each and interpolated from their nearest same-colour neighbours —
 * horizontal, vertical or diagonal depending on where the pixel sits in the 2×2
 * tile. Edges clamp to the nearest in-bounds sample. Bilinear is the standard
 * baseline: exact where a colour was actually measured, smooth between.
 *
 * <p>JPEG XL tags a plane as {@link com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo#TYPE_CFA
 * colour-filter-array} but does <em>not</em> record which Bayer pattern it is, so
 * the caller supplies it; {@link Pattern#RGGB} is the most common.
 */
public final class CfaDemosaic {

    /** The colour at each position of the 2×2 Bayer tile, named by its top-left ordering. */
    public enum Pattern {
        RGGB(0, 0, 1, 1),
        BGGR(1, 1, 0, 0),
        GRBG(0, 1, 1, 0),
        GBRG(1, 0, 0, 1);

        final int redRow;
        final int redCol;
        final int blueRow;
        final int blueCol;

        Pattern(int redRow, int redCol, int blueRow, int blueCol) {
            this.redRow = redRow;
            this.redCol = redCol;
            this.blueRow = blueRow;
            this.blueCol = blueCol;
        }

        public int redRow() {
            return redRow;
        }

        public int redCol() {
            return redCol;
        }

        public int blueRow() {
            return blueRow;
        }

        public int blueCol() {
            return blueCol;
        }
    }

    private CfaDemosaic() {
    }

    /**
     * Demosaics {@code mosaic} ({@code w}×{@code h}, row-major) into three colour
     * planes {@code [r][g][b]} of the same size. Samples are treated as plain
     * integers, so any bit depth works; interpolated values are rounded.
     */
    public static int[][] demosaic(int[] mosaic, int w, int h, Pattern p) {
        int[] r = new int[w * h];
        int[] g = new int[w * h];
        int[] b = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int row = y & 1;
                int col = x & 1;
                boolean isRed = row == p.redRow && col == p.redCol;
                boolean isBlue = row == p.blueRow && col == p.blueCol;
                if (isRed) {
                    r[i] = mosaic[i];
                    g[i] = cross(mosaic, w, h, x, y);
                    b[i] = diag(mosaic, w, h, x, y);
                } else if (isBlue) {
                    b[i] = mosaic[i];
                    g[i] = cross(mosaic, w, h, x, y);
                    r[i] = diag(mosaic, w, h, x, y);
                } else {
                    g[i] = mosaic[i];
                    // a green site: red neighbours run one way, blue the other
                    if (row == p.redRow) {           // red shares this row
                        r[i] = horiz(mosaic, w, h, x, y);
                        b[i] = vert(mosaic, w, h, x, y);
                    } else {                          // blue shares this row
                        r[i] = vert(mosaic, w, h, x, y);
                        b[i] = horiz(mosaic, w, h, x, y);
                    }
                }
            }
        }
        return new int[][] {r, g, b};
    }

    // The listed neighbours are all one colour (Bayer geometry), so averaging
    // only the in-bounds ones keeps the average pure — clamping an out-of-bounds
    // coordinate would fold in the wrong-colour centre sample at the edges.
    private static int avg(int[] m, int w, int h, int x0, int y0, int x1, int y1) {
        long sum = 0;
        int n = 0;
        if (in(w, h, x0, y0)) {
            sum += m[y0 * w + x0];
            n++;
        }
        if (in(w, h, x1, y1)) {
            sum += m[y1 * w + x1];
            n++;
        }
        return n == 0 ? 0 : (int) ((sum + (n >> 1)) / n);
    }

    private static int avg4(int[] m, int w, int h, int x0, int y0, int x1, int y1,
            int x2, int y2, int x3, int y3) {
        long sum = 0;
        int n = 0;
        int[][] pts = {{x0, y0}, {x1, y1}, {x2, y2}, {x3, y3}};
        for (int[] pt : pts) {
            if (in(w, h, pt[0], pt[1])) {
                sum += m[pt[1] * w + pt[0]];
                n++;
            }
        }
        return n == 0 ? 0 : (int) ((sum + (n >> 1)) / n);
    }

    private static boolean in(int w, int h, int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h;
    }

    private static int horiz(int[] m, int w, int h, int x, int y) {
        return avg(m, w, h, x - 1, y, x + 1, y);
    }

    private static int vert(int[] m, int w, int h, int x, int y) {
        return avg(m, w, h, x, y - 1, x, y + 1);
    }

    private static int cross(int[] m, int w, int h, int x, int y) {
        return avg4(m, w, h, x - 1, y, x + 1, y, x, y - 1, x, y + 1);
    }

    private static int diag(int[] m, int w, int h, int x, int y) {
        return avg4(m, w, h, x - 1, y - 1, x + 1, y - 1, x - 1, y + 1, x + 1, y + 1);
    }
}
