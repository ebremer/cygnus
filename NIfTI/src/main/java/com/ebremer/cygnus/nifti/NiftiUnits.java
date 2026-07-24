package com.ebremer.cygnus.nifti;

/**
 * {@code xyzt_units} values: what {@code pixdim} is measured in. One byte holds
 * two units — the spatial one for {@code pixdim[1..3]} in bits 0-2, the
 * temporal one for {@code pixdim[4]} in bits 3-5 — which is why the temporal
 * codes are multiples of 8 and the two never collide. See
 * {@link NiftiHeader#spaceUnits()} and {@link NiftiHeader#setUnits(int, int)}.
 *
 * <p>The spectral codes occupy the temporal field: a 5-D spectroscopy image
 * measures its fourth axis in hertz or ppm rather than seconds.</p>
 */
public final class NiftiUnits {

    /** Unit not recorded. */
    public static final int UNKNOWN = 0;

    /** Metres. */
    public static final int METER = 1;
    /** Millimetres — what all but a handful of files use. */
    public static final int MM = 2;
    /** Micrometres. */
    public static final int MICRON = 3;

    /** Seconds. */
    public static final int SEC = 8;
    /** Milliseconds. */
    public static final int MSEC = 16;
    /** Microseconds. */
    public static final int USEC = 24;

    /** Hertz — a spectral axis, in the temporal field. */
    public static final int HZ = 32;
    /** Parts per million — a spectral axis, in the temporal field. */
    public static final int PPM = 40;
    /** Radians per second — a spectral axis, in the temporal field. */
    public static final int RADS = 48;

    /** Mask for the spatial unit. */
    public static final int SPACE_MASK = 0x07;

    /** Mask for the temporal (or spectral) unit. */
    public static final int TIME_MASK = 0x38;

    private NiftiUnits() {
    }

    /**
     * How many metres one unit of {@code code} is, or {@link Double#NaN} when
     * the code is unknown or is not a length. Multiplying {@code pixdim[1..3]}
     * by this gives voxel dimensions in metres.
     */
    public static double metersPer(int code) {
        return switch (code & SPACE_MASK) {
            case METER -> 1.0;
            case MM -> 1e-3;
            case MICRON -> 1e-6;
            default -> Double.NaN;
        };
    }

    /**
     * How many seconds one unit of {@code code} is, or {@link Double#NaN} when
     * the code is unknown or is spectral rather than temporal.
     */
    public static double secondsPer(int code) {
        return switch (code & TIME_MASK) {
            case SEC -> 1.0;
            case MSEC -> 1e-3;
            case USEC -> 1e-6;
            default -> Double.NaN;
        };
    }

    /** A readable name for {@code code}, which may name either field. */
    public static String name(int code) {
        return switch (code) {
            case UNKNOWN -> "UNKNOWN";
            case METER -> "METER";
            case MM -> "MM";
            case MICRON -> "MICRON";
            case SEC -> "SEC";
            case MSEC -> "MSEC";
            case USEC -> "USEC";
            case HZ -> "HZ";
            case PPM -> "PPM";
            case RADS -> "RADS";
            default -> "unit " + code;
        };
    }
}
