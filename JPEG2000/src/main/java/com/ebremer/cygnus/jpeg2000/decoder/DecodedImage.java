package com.ebremer.cygnus.jpeg2000.decoder;

/**
 * A decoded image or image region: one integer sample array per channel,
 * after inverse component transform, DC level shift, palette expansion and
 * clamping.
 *
 * <p>{@code width x height} describe the decoded region in image-region
 * coordinates; for a full decode the region is the whole image and
 * {@code regionX = regionY = 0}. Channel {@code c} holds the component
 * samples covering the region: a plane of {@code chanWidth[c] x
 * chanHeight[c]} samples on a grid subsampled by {@code dx[c]/dy[c]},
 * whose first sample has absolute component coordinate
 * ({@code chanX0[c]}, {@code chanY0[c]}).</p>
 */
public final class DecodedImage {

    public enum ColourSpace { GREY, SRGB, SYCC, ICC, UNKNOWN }

    /** Decoded region size in (possibly reduced) image-region units. */
    public final int width;
    public final int height;
    /** DWT levels discarded; 0 = full resolution. All coordinates below are
     * on the reduced grid (reference grid ceil-divided by 2^reduction). */
    public int reduction;
    /** Origin of the decoded region within the image region (0-based). */
    public int regionX, regionY;
    /** Reduced-grid coordinate of the decoded region origin. */
    public int gridX0, gridY0;

    public final int numChannels;
    /** Per-channel samples, row-major; null on a metadata-only shape. */
    public final int[][] samples;
    public final int[] chanWidth;
    public final int[] chanHeight;
    /** Absolute component-grid coordinate of each channel plane's origin. */
    public final int[] chanX0, chanY0;
    public final int[] dx, dy;           // channel subsampling factors
    public final int[] depth;            // bits per channel
    public final boolean[] signed;

    // full-image structure (same for every region)
    /** Full image-region size. */
    public int imageWidth, imageHeight;
    /** Tile size on the reference grid. */
    public int tileWidth, tileHeight;
    /** Tile grid origin relative to the image region (usually &lt;= 0). */
    public int tileGridXOff, tileGridYOff;
    public int numXTiles, numYTiles;

    public ColourSpace colourSpace = ColourSpace.UNKNOWN;
    public byte[] iccProfile;
    /** Index of the opacity channel, or -1; set from cdef. */
    public int alphaChannel = -1;
    /** True if the alpha channel is premultiplied. */
    public boolean alphaPremultiplied;
    /** Maps colour position (0-based) to channel index; identity by default. */
    public int[] colourChannels;

    public DecodedImage(int width, int height, int numChannels) {
        this.width = width;
        this.height = height;
        this.numChannels = numChannels;
        this.samples = new int[numChannels][];
        this.chanWidth = new int[numChannels];
        this.chanHeight = new int[numChannels];
        this.chanX0 = new int[numChannels];
        this.chanY0 = new int[numChannels];
        this.dx = new int[numChannels];
        this.dy = new int[numChannels];
        this.depth = new int[numChannels];
        this.signed = new boolean[numChannels];
    }

    /** Maximum channel depth. */
    public int maxDepth() {
        int m = 1;
        for (int d : depth) {
            m = Math.max(m, d);
        }
        return m;
    }

    /**
     * Sample of channel {@code c} covering image-region position (x, y),
     * given in absolute image coordinates (not region-relative), replicating
     * subsampled channels onto the reference grid. The position should lie
     * within the decoded region; outside it the nearest decoded sample is
     * returned.
     */
    public int sampleAt(int c, int x, int y) {
        int refX = gridX0 - regionX + x;
        int refY = gridY0 - regionY + y;
        int cx = Math.floorDiv(refX, dx[c]) - chanX0[c];
        int cy = Math.floorDiv(refY, dy[c]) - chanY0[c];
        cx = Math.max(0, Math.min(chanWidth[c] - 1, cx));
        cy = Math.max(0, Math.min(chanHeight[c] - 1, cy));
        return samples[c][cy * chanWidth[c] + cx];
    }
}
