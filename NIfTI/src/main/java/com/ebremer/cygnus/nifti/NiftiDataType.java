package com.ebremer.cygnus.nifti;

/**
 * The voxel types NIfTI defines, their {@code datatype} codes, and what each
 * maps to in Java.
 *
 * <p>Fourteen of the eighteen are decodable here. The four that are not are
 * not omissions:</p>
 *
 * <ul>
 *   <li>{@link #UNKNOWN} is the absence of a type.</li>
 *   <li>{@link #BINARY} is one bit per voxel with a packing the specification
 *       never pinned down — where a row ends, whether it pads — so any reading
 *       of it is a guess. The reference Python implementation refuses it
 *       outright.</li>
 *   <li>{@link #FLOAT128} and {@link #COMPLEX256} are C {@code long double},
 *       which is 80-bit x87 on some platforms and quad precision on others,
 *       and which no JVM primitive holds either way.</li>
 * </ul>
 *
 * <p>All four parse in a header and report themselves; a pixel read of one
 * throws rather than returning a plausible wrong number. Their bytes are still
 * reachable through the reader's raw-byte accessor.</p>
 */
public enum NiftiDataType {

    /** No type. */
    UNKNOWN(0, 0, 0, Carrier.NONE, false, false, false),

    /** One bit per voxel, packing unspecified. Not decoded — see the class note. */
    BINARY(1, 1, 1, Carrier.BIT, false, false, false),

    /** Unsigned 8-bit. */
    UINT8(2, 8, 1, Carrier.BYTE, false, true, true),

    /** Signed 16-bit — the commonest type in clinical MR. */
    INT16(4, 16, 1, Carrier.SHORT, true, true, true),

    /** Signed 32-bit. */
    INT32(8, 32, 1, Carrier.INT, true, true, true),

    /** IEEE 754 single precision. */
    FLOAT32(16, 32, 1, Carrier.FLOAT, true, true, true),

    /** Two {@link #FLOAT32}s per voxel, real then imaginary. */
    COMPLEX64(32, 64, 2, Carrier.FLOAT, true, true, true),

    /** IEEE 754 double precision. */
    FLOAT64(64, 64, 1, Carrier.DOUBLE, true, true, true),

    /** Three unsigned bytes per voxel, r/g/b interleaved. Never scaled. */
    RGB24(128, 24, 3, Carrier.BYTE, false, false, true),

    /** Signed 8-bit. */
    INT8(256, 8, 1, Carrier.BYTE, true, true, true),

    /** Unsigned 16-bit. */
    UINT16(512, 16, 1, Carrier.SHORT, false, true, true),

    /** Unsigned 32-bit. */
    UINT32(768, 32, 1, Carrier.INT, false, true, true),

    /** Signed 64-bit. */
    INT64(1024, 64, 1, Carrier.LONG, true, true, true),

    /** Unsigned 64-bit. */
    UINT64(1280, 64, 1, Carrier.LONG, false, true, true),

    /** C {@code long double}. Not decoded — see the class note. */
    FLOAT128(1536, 128, 1, Carrier.LONG_DOUBLE, true, true, false),

    /** Two {@link #FLOAT64}s per voxel, real then imaginary. */
    COMPLEX128(1792, 128, 2, Carrier.DOUBLE, true, true, true),

    /** Two C {@code long double}s per voxel. Not decoded — see the class note. */
    COMPLEX256(2048, 256, 2, Carrier.LONG_DOUBLE, true, true, false),

    /** Four unsigned bytes per voxel, r/g/b/a interleaved. Never scaled. */
    RGBA32(2304, 32, 4, Carrier.BYTE, false, false, true);

    /** The Java primitive one component of a voxel maps to. */
    public enum Carrier {
        /** No components. */
        NONE,
        /** A single bit. */
        BIT,
        /** {@code byte}. */
        BYTE,
        /** {@code short}. */
        SHORT,
        /** {@code int}. */
        INT,
        /** {@code long}. */
        LONG,
        /** {@code float}. */
        FLOAT,
        /** {@code double}. */
        DOUBLE,
        /** C {@code long double}, which Java has no equivalent of. */
        LONG_DOUBLE
    }

    /** The {@code datatype} field's value. */
    public final int code;

    /** Bits per voxel — all components together. This is the header's {@code bitpix}. */
    public final int bitpix;

    /** Components per voxel: 1, 2 for complex, 3 for RGB, 4 for RGBA. */
    public final int components;

    /** What one component maps to in Java. */
    public final Carrier carrier;

    /** Whether components are signed. Governs how they widen. */
    public final boolean signedType;

    /** Whether {@code scl_slope}/{@code scl_inter} apply — they never do to RGB. */
    public final boolean scalable;

    /** Whether this module decodes pixels of this type. */
    public final boolean supported;

    NiftiDataType(int code, int bitpix, int components, Carrier carrier,
                  boolean signedType, boolean scalable, boolean supported) {
        this.code = code;
        this.bitpix = bitpix;
        this.components = components;
        this.carrier = carrier;
        this.signedType = signedType;
        this.scalable = scalable;
        this.supported = supported;
    }

    /** Bits per component: {@link #bitpix} divided among the {@link #components}. */
    public int componentBits() {
        return components == 0 ? 0 : bitpix / components;
    }

    /** Bytes per component, or 0 for {@link #BINARY} which is sub-byte. */
    public int componentBytes() {
        return componentBits() / 8;
    }

    /** Bytes per voxel, or 0 for {@link #BINARY}. */
    public int bytesPerVoxel() {
        return bitpix / 8;
    }

    /** Whether a voxel occupies a whole number of bytes — false only for {@link #BINARY}. */
    public boolean byteAligned() {
        return bitpix >= 8 && bitpix % 8 == 0;
    }

    /** Whether this is a complex type, whose components are a real/imaginary pair. */
    public boolean isComplex() {
        return this == COMPLEX64 || this == COMPLEX128 || this == COMPLEX256;
    }

    /** Whether this is a colour type, whose components are interleaved channels. */
    public boolean isColor() {
        return this == RGB24 || this == RGBA32;
    }

    /** The type with the given {@code datatype} code, or null if there is none. */
    public static NiftiDataType byCode(int code) {
        for (NiftiDataType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
