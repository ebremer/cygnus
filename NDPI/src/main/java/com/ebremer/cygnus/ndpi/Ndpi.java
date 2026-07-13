package com.ebremer.cygnus.ndpi;

/**
 * The TIFF tags and private Hamamatsu tags an NDPI file is built from.
 *
 * <p>NDPI is a classic TIFF pretending to be a BigTIFF: it uses the classic
 * 32-bit layout, but smuggles the high half of every value and offset into a
 * block of 4-byte extensions at the end of each directory, so that a slide may
 * exceed 4 GiB. {@link NdpiTiff} reads that; a TIFF library will not.</p>
 */
public final class Ndpi {

    private Ndpi() {
    }

    public static final int TAG_IMAGE_WIDTH = 256;
    public static final int TAG_IMAGE_LENGTH = 257;
    public static final int TAG_BITS_PER_SAMPLE = 258;
    public static final int TAG_COMPRESSION = 259;
    public static final int TAG_PHOTOMETRIC_INTERPRETATION = 262;
    public static final int TAG_STRIP_OFFSETS = 273;
    public static final int TAG_SAMPLES_PER_PIXEL = 277;
    public static final int TAG_ROWS_PER_STRIP = 278;
    public static final int TAG_STRIP_BYTE_COUNTS = 279;
    public static final int TAG_X_RESOLUTION = 282;
    public static final int TAG_Y_RESOLUTION = 283;
    public static final int TAG_RESOLUTION_UNIT = 296;

    /** Present in the first directory of every NDPI file, and nothing else. */
    public static final int TAG_FORMAT_FLAG = 65420;

    /**
     * Objective power the directory was captured at. Positive marks a pyramid
     * level; {@code -1} the macro image and {@code -2} the map image.
     */
    public static final int TAG_SOURCE_LENS = 65421;

    /** Stage position of the directory's centre, in nanometres from the slide centre. */
    public static final int TAG_X_OFFSET = 65422;
    public static final int TAG_Y_OFFSET = 65423;

    /** Z-plane. Only plane 0 is the focused image; the rest are a focus stack. */
    public static final int TAG_FOCAL_PLANE = 65424;

    /** Byte offset of each restart interval in the level's JPEG, relative to the strip. */
    public static final int TAG_MCU_STARTS = 65426;

    /** High 32 bits of each {@link #TAG_MCU_STARTS} entry, for slides beyond 4 GiB. */
    public static final int TAG_MCU_STARTS_HIGH = 65432;

    public static final int TAG_REFERENCE = 65427;

    /** ASCII {@code key=value} records separated by CRLF. */
    public static final int TAG_PROPERTY_MAP = 65449;

    public static final int COMPRESSION_NONE = 1;
    public static final int COMPRESSION_JPEG = 7;

    /** Source lens of the macro image — a photograph of the whole slide. */
    public static final double SOURCE_LENS_MACRO = -1;

    /** Source lens of the map image — a low-power overview of the scanned area. */
    public static final double SOURCE_LENS_MAP = -2;

    /** The largest dimension a JPEG's SOF marker can express. */
    public static final int JPEG_MAX_DIMENSION = 65500;

    /** The name an associated image is known by, from its source lens. */
    public static final String associatedImageName(double sourceLens) {
        if (sourceLens == SOURCE_LENS_MACRO) {
            return "macro";
        }
        if (sourceLens == SOURCE_LENS_MAP) {
            return "map";
        }
        return "sourcelens" + (long) sourceLens;
    }
}
