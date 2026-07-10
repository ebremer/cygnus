package com.ebremer.jpegxl.decoder;

/** One decoded (displayable) frame: oriented sample planes plus timing. */
public final class JxlFrame {

    public final int width;
    public final int height;
    /**
     * Integer sample planes, colour channels first, then extra channels.
     * An entry is null when that channel has floating-point samples (see
     * {@link #floatChannels}).
     */
    public final int[][] channels;
    /** Floating-point sample planes; entries are null for integer channels. */
    public final float[][] floatChannels;
    /** Animation duration in ticks (0 for stills). */
    public final long duration;

    public JxlFrame(int width, int height, int[][] channels, long duration) {
        this(width, height, channels, new float[channels.length][], duration);
    }

    public JxlFrame(int width, int height, int[][] channels, float[][] floatChannels,
            long duration) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.floatChannels = floatChannels;
        this.duration = duration;
    }
}
