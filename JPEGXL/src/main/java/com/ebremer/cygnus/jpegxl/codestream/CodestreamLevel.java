package com.ebremer.cygnus.jpegxl.codestream;

import java.io.IOException;

/**
 * JPEG XL codestream levels (ISO/IEC 18181-2). A file may declare, in its
 * {@code jxll} container box, the level a decoder needs to read it: <b>5</b>,
 * the widely-supported baseline, or <b>10</b>, which lifts most of the limits.
 * The declaration is a promise about the content, and this is where the promise
 * is checked — a file that says level 5 but carries level-10 content is
 * malformed, and a conformant reader rejects it.
 *
 * <p>The boundaries follow the reference encoder's {@code VerifyLevelSettings}
 * (libjxl {@code encode.cc}): a metadata field over a level-5 limit forces
 * level 10, and a field over a level-10 limit is invalid outright. The one place
 * the wording here follows the specification rather than the reference code is
 * bit depth: the spec and libjxl's own documentation put the level-5 ceiling at
 * 16 bits a sample, while libjxl's <em>encoder</em> declares level 10 above 12
 * — a conservative choice about its internal buffers, not about what the level
 * permits. A decoder validating a file uses the real ceiling, so a 16-bit
 * level-5 image is accepted.
 *
 * <p>A bare codestream carries no {@code jxll} box and so makes no promise; only
 * a container declares a level, and only a container is checked against one.
 */
public final class CodestreamLevel {

    /** The level assumed when no {@code jxll} box is present. */
    public static final int DEFAULT = 5;

    /** Returned by {@link #required} for content no level can carry. */
    public static final int INVALID = -1;

    // Level 5 ceilings.
    private static final long L5_MAX_DIM = 1L << 18;      // 262144
    private static final long L5_MAX_PIXELS = 1L << 28;   // 268435456
    private static final long L5_MAX_ICC = 1L << 22;      // 4 MiB
    private static final int L5_MAX_EXTRA = 4;
    private static final int L5_MAX_BITS = 16;

    // Level 10 ceilings; beyond these, no level applies.
    private static final long L10_MAX_DIM = 1L << 30;
    private static final long L10_MAX_PIXELS = 1L << 40;
    private static final long L10_MAX_ICC = 1L << 28;
    private static final int L10_MAX_EXTRA = 256;
    private static final int L10_MAX_BITS = 32;

    private CodestreamLevel() {
    }

    /**
     * The lowest level that can carry this image: {@link #DEFAULT} (5) when it
     * fits the baseline, 10 when it needs the extended limits, or
     * {@link #INVALID} when it exceeds even those. The dimensions are the raw
     * canvas size; the checks are symmetric in width and height, so image
     * orientation does not change the answer.
     */
    public static int required(ImageMetadata meta, long width, long height) {
        if (reason(INVALID, meta, width, height) != null) {
            return INVALID;
        }
        return reason(DEFAULT, meta, width, height) == null ? DEFAULT : 10;
    }

    /**
     * Verifies the content against a declared level, throwing when the file's
     * own promise is broken — the content needs a higher level than it claims,
     * or exceeds every level. A {@code declaredLevel} of 10 accepts anything
     * valid, since level 10 is a superset of level 5.
     */
    public static void enforce(int declaredLevel, ImageMetadata meta, long width, long height)
            throws IOException {
        if (declaredLevel != 5 && declaredLevel != 10) {
            throw new IOException("invalid codestream level " + declaredLevel
                    + " (only 5 and 10 are defined)");
        }
        String tooBig = reason(INVALID, meta, width, height);
        if (tooBig != null) {
            throw new IOException("codestream exceeds every level: " + tooBig);
        }
        String beyond = reason(declaredLevel, meta, width, height);
        if (beyond != null) {
            throw new IOException("codestream declares level " + declaredLevel
                    + " but " + beyond + ", which needs level "
                    + required(meta, width, height));
        }
    }

    /**
     * Why the content does not fit {@code level}, or null when it does.
     * {@code level} is 5, 10, or {@link #INVALID} to test the absolute
     * (level-10) ceilings — the shared body of {@link #required} and
     * {@link #enforce}, so the message a caller sees names the exact limit hit.
     */
    private static String reason(int level, ImageMetadata meta, long width, long height) {
        boolean absolute = level == INVALID;
        long maxDim = absolute ? L10_MAX_DIM : L5_MAX_DIM;
        long maxPixels = absolute ? L10_MAX_PIXELS : L5_MAX_PIXELS;
        long maxIcc = absolute ? L10_MAX_ICC : L5_MAX_ICC;
        int maxExtra = absolute ? L10_MAX_EXTRA : L5_MAX_EXTRA;
        int maxBits = absolute ? L10_MAX_BITS : L5_MAX_BITS;
        if (level == 10) {
            return null; // level 10 carries anything that is valid at all
        }

        if (width > maxDim || height > maxDim) {
            return "a dimension exceeds " + maxDim;
        }
        if (width * height > maxPixels) {
            return "the pixel count exceeds " + maxPixels;
        }
        if (meta.colourEncoding != null && meta.colourEncoding.wantIcc
                && meta.iccProfile != null && meta.iccProfile.length > maxIcc) {
            return "the ICC profile exceeds " + maxIcc + " bytes";
        }
        if (meta.numExtraChannels() > maxExtra) {
            return "it has more than " + maxExtra + " extra channels";
        }
        if (meta.bitDepth.bitsPerSample > maxBits) {
            return "its samples are deeper than " + maxBits + " bits";
        }
        for (ExtraChannelInfo ec : meta.extraChannels) {
            if (ec.bitDepth.bitsPerSample > maxBits) {
                return "extra channel \"" + ec.name + "\" is deeper than " + maxBits + " bits";
            }
            if (!absolute && ec.type == ExtraChannelInfo.TYPE_BLACK) {
                return "it carries a CMYK black channel";
            }
        }
        return null;
    }
}
