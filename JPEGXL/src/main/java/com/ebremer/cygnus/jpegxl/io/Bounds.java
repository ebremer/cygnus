package com.ebremer.cygnus.jpegxl.io;

import java.io.IOException;

/**
 * Bounds checks for values the bitstream turns into allocations or array
 * indices. Counts and sizes in a JPEG XL stream are hybrid-uint or U32 fields
 * that can reach 2^30 — or carry bit 31 and arrive negative — from a few
 * bytes, so every such value is held against a cap <em>before</em> memory is
 * sized from it, and a violation is an {@link IOException} (the file is
 * malformed or hostile), never an {@code OutOfMemoryError} or an unchecked
 * arithmetic surprise.
 *
 * <p>The image-wide ceiling ({@link #maxImagePixels}) applies to every
 * codestream, boxed or bare. A codestream level is a container's promise about
 * its content and is enforced separately ({@code CodestreamLevel}); a bare
 * codestream makes no promise at all, so this ceiling is what stands between
 * its declared dimensions and the canvas allocation. The default is the most
 * a single {@code float[]} plane can hold — beyond that a decode could never
 * finish anyway — and deployments decoding untrusted files can lower it with
 * {@code -Djxl.maxImagePixels}.
 */
public final class Bounds {

    /** System property naming the decode pixel ceiling. */
    public static final String MAX_PIXELS_PROPERTY = "jxl.maxImagePixels";

    private Bounds() {
    }

    /**
     * The decode ceiling on total image pixels (width x height): the
     * {@value #MAX_PIXELS_PROPERTY} system property, clamped to
     * [1, {@link Integer#MAX_VALUE}], defaulting to the top of that range.
     * Read live so a host can adjust it without reloading the class.
     */
    public static long maxImagePixels() {
        Long v = Long.getLong(MAX_PIXELS_PROPERTY);
        if (v == null || v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, v);
    }

    /** Holds declared image dimensions to the pixel ceiling before anything is sized from them. */
    public static void checkImage(long width, long height) throws IOException {
        long cap = maxImagePixels();
        if (width <= 0 || height <= 0 || width * height > cap) {
            throw new IOException("image " + width + "x" + height
                    + " exceeds the decode ceiling of " + cap + " pixels (-D"
                    + MAX_PIXELS_PROPERTY + " raises it)");
        }
    }

    /**
     * The pixel count of a plane about to be allocated: the long-promoted
     * product of two non-negative dimensions, at most
     * {@link Integer#MAX_VALUE} so it can index and size an array.
     */
    public static int area(int width, int height, String what) throws IOException {
        long area = (long) width * height;
        if (width < 0 || height < 0 || area > Integer.MAX_VALUE) {
            throw new IOException(what + " dimensions " + width + "x" + height
                    + " are out of range");
        }
        return (int) area;
    }

    /**
     * A count read from the bitstream, about to size an allocation or bound a
     * loop: non-negative (a symbol with bit 31 set is negative as an int) and
     * no more than {@code cap}.
     */
    public static int count(int value, long cap, String what) throws IOException {
        if (value < 0 || value > cap) {
            throw new IOException(what + " count " + Integer.toUnsignedLong(value)
                    + " exceeds " + cap);
        }
        return value;
    }
}
