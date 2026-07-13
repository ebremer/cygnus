package com.ebremer.cygnus.svs;

/**
 * The TIFF tags and compression codes an Aperio SVS file is built from.
 *
 * <p>SVS is a plain (or Big) TIFF whose directories hold the pyramid levels
 * and the associated label/macro/thumbnail images. Beyond the baseline tags,
 * Aperio uses two private compression codes for JPEG 2000 tiles.</p>
 */
public final class Tiff {

    private Tiff() {
    }

    public static final int TAG_IMAGE_WIDTH = 256;
    public static final int TAG_IMAGE_LENGTH = 257;
    public static final int TAG_COMPRESSION = 259;
    public static final int TAG_PHOTOMETRIC_INTERPRETATION = 262;
    public static final int TAG_IMAGE_DESCRIPTION = 270;
    public static final int TAG_SAMPLES_PER_PIXEL = 277;
    public static final int TAG_TILE_WIDTH = 322;
    public static final int TAG_TILE_LENGTH = 323;
    public static final int TAG_TILE_OFFSETS = 324;
    public static final int TAG_TILE_BYTE_COUNTS = 325;

    public static final int COMPRESSION_NONE = 1;
    public static final int COMPRESSION_LZW = 5;
    public static final int COMPRESSION_JPEG = 7;

    /**
     * Aperio JPEG 2000 tiles whose three codestream components are Y, Cb and
     * Cr rather than R, G and B. The codestream itself carries no colour
     * signalling, so this tag is the only thing that says so.
     */
    public static final int COMPRESSION_APERIO_JP2K_YCBCR = 33003;

    /** Aperio JPEG 2000 tiles whose codestream components are already RGB. */
    public static final int COMPRESSION_APERIO_JP2K_RGB = 33005;

    /** Whether {@code compression} is one of Aperio's JPEG 2000 tile codes. */
    public static boolean isAperioJpeg2000(int compression) {
        return compression == COMPRESSION_APERIO_JP2K_YCBCR
                || compression == COMPRESSION_APERIO_JP2K_RGB;
    }

    /** Human-readable name for a compression code, for metadata and messages. */
    public static String compressionName(int compression) {
        return switch (compression) {
            case COMPRESSION_NONE -> "None";
            case COMPRESSION_LZW -> "LZW";
            case COMPRESSION_JPEG -> "JPEG";
            case COMPRESSION_APERIO_JP2K_YCBCR -> "JPEG 2000 (YCbCr)";
            case COMPRESSION_APERIO_JP2K_RGB -> "JPEG 2000 (RGB)";
            default -> "Compression " + compression;
        };
    }
}
