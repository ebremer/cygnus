package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.NiftiDataType;
import com.ebremer.cygnus.nifti.NiftiHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Voxel bytes to Java arrays and back.
 *
 * <p>A NIfTI voxel array is raw: no compression, no framing, nothing between
 * one voxel and the next. All this has to get right is width, signedness,
 * byte order, and — for the types with more than one component — the order the
 * components are interleaved in.</p>
 *
 * <h2>Arrays are the natural type, not the widest one</h2>
 *
 * <p>Decoding produces the Java array a component actually fits in:
 * {@code short[]} for INT16 <em>and</em> UINT16, {@code long[]} for INT64 and
 * UINT64. An unsigned type comes back in a signed carrier, which is the only
 * choice Java offers, and {@link #toDouble} is what interprets it — a UINT16
 * of 40000 is a {@code short} of -25536 until something widens it, and
 * widening it wrong is the classic way to read a scanner's output as
 * negative.</p>
 *
 * <h2>Interleaving</h2>
 *
 * <p>The multi-component types store their components together, per voxel,
 * not as planes: a COMPLEX64 voxel is a real then an imaginary
 * {@code float}, an RGB24 voxel is r then g then b. Decoding {@code n} voxels
 * of a {@code c}-component type therefore gives {@code n * c} array elements
 * in that same interleaved order.</p>
 */
public final class VoxelCodec {

    private VoxelCodec() {
    }

    /**
     * The type a header's {@code datatype} names, with {@code bitpix} checked
     * against it.
     *
     * <p>{@code datatype} decides the layout, so a {@code bitpix} that
     * contradicts it leaves two claims and no way to choose: a file saying
     * INT16 with 8 bits per voxel is wrong whichever field is believed, and
     * believing the wrong one shifts every voxel after the first. That is an
     * {@link IOException}. A {@code bitpix} of zero is not a contradiction but
     * an omission — some writers never set it — and is taken from the type.</p>
     */
    public static NiftiDataType resolve(NiftiHeader header) throws IOException {
        NiftiDataType type = NiftiDataType.byCode(header.datatype);
        if (type == null) {
            throw new IOException("datatype " + header.datatype + " is not a NIfTI type");
        }
        if (type == NiftiDataType.UNKNOWN) {
            throw new IOException("the header states no datatype");
        }
        if (header.bitpix != 0 && header.bitpix != type.bitpix) {
            throw new IOException("the header says datatype " + type + " (" + type.bitpix
                    + " bits per voxel) and bitpix " + header.bitpix
                    + "; they cannot both be right");
        }
        return type;
    }

    /**
     * The type a header names, required to be one whose pixels this module
     * decodes. See {@link NiftiDataType} for why four are not.
     */
    public static NiftiDataType resolveDecodable(NiftiHeader header) throws IOException {
        NiftiDataType type = resolve(header);
        requireDecodable(type);
        return type;
    }

    /** Throws unless {@code type} is one whose pixels this module decodes. */
    public static void requireDecodable(NiftiDataType type) throws IOException {
        if (!type.supported) {
            throw new IOException("NIFTI_TYPE_" + type + " (datatype " + type.code
                    + ") is not decoded by this module: " + why(type)
                    + "; the raw bytes are still readable");
        }
    }

    private static String why(NiftiDataType type) {
        return switch (type) {
            case UNKNOWN -> "it names no type";
            case BINARY -> "one bit per voxel with a packing the specification never fixed";
            case FLOAT128, COMPLEX256 ->
                    "it is C long double, which no Java primitive holds";
            default -> "unsupported";
        };
    }

    /** A fresh array of the natural type, long enough for {@code voxels} voxels of {@code type}. */
    public static Object allocate(NiftiDataType type, int voxels) throws IOException {
        requireDecodable(type);
        int n = Math.multiplyExact(voxels, type.components);
        return switch (type.carrier) {
            case BYTE -> new byte[n];
            case SHORT -> new short[n];
            case INT -> new int[n];
            case LONG -> new long[n];
            case FLOAT -> new float[n];
            case DOUBLE -> new double[n];
            default -> throw new IOException("no Java array holds " + type);
        };
    }

    /** How many bytes {@code voxels} voxels of {@code type} occupy. */
    public static long byteLength(NiftiDataType type, long voxels) {
        return voxels * type.bytesPerVoxel();
    }

    /**
     * Decodes {@code voxels} voxels from {@code src} into {@code dst}, which
     * must be the array {@link #allocate} would give.
     *
     * @param srcOff where the voxels start in {@code src}
     * @param dstOff which component of {@code dst} to write first
     */
    public static void decode(byte[] src, int srcOff, NiftiDataType type, ByteOrder order,
                              Object dst, int dstOff, int voxels) throws IOException {
        requireDecodable(type);
        int components = Math.multiplyExact(voxels, type.components);
        int bytes = Math.multiplyExact(voxels, type.bytesPerVoxel());
        if (srcOff < 0 || bytes > src.length - srcOff) {
            throw new IOException("decoding " + voxels + " " + type + " voxels needs "
                    + bytes + " bytes at offset " + srcOff + "; " + src.length + " are there");
        }
        ByteBuffer b = ByteBuffer.wrap(src, srcOff, bytes).order(order);
        switch (type.carrier) {
            case BYTE -> b.get((byte[]) dst, dstOff, components);
            case SHORT -> b.asShortBuffer().get((short[]) dst, dstOff, components);
            case INT -> b.asIntBuffer().get((int[]) dst, dstOff, components);
            case LONG -> b.asLongBuffer().get((long[]) dst, dstOff, components);
            case FLOAT -> b.asFloatBuffer().get((float[]) dst, dstOff, components);
            case DOUBLE -> b.asDoubleBuffer().get((double[]) dst, dstOff, components);
            default -> throw new IOException("no Java array holds " + type);
        }
    }

    /** Decodes {@code voxels} voxels into a fresh array of the natural type. */
    public static Object decode(byte[] src, int srcOff, NiftiDataType type,
                                ByteOrder order, int voxels) throws IOException {
        Object dst = allocate(type, voxels);
        decode(src, srcOff, type, order, dst, 0, voxels);
        return dst;
    }

    /**
     * Encodes {@code voxels} voxels from {@code src} — an array of the natural
     * type — into {@code dst}.
     */
    public static void encode(Object src, int srcOff, NiftiDataType type, ByteOrder order,
                              byte[] dst, int dstOff, int voxels) throws IOException {
        requireDecodable(type);
        int components = Math.multiplyExact(voxels, type.components);
        int bytes = Math.multiplyExact(voxels, type.bytesPerVoxel());
        if (dstOff < 0 || bytes > dst.length - dstOff) {
            throw new IOException("encoding " + voxels + " " + type + " voxels needs "
                    + bytes + " bytes at offset " + dstOff + "; " + dst.length + " are there");
        }
        ByteBuffer b = ByteBuffer.wrap(dst, dstOff, bytes).order(order);
        switch (type.carrier) {
            case BYTE -> b.put((byte[]) src, srcOff, components);
            case SHORT -> b.asShortBuffer().put((short[]) src, srcOff, components);
            case INT -> b.asIntBuffer().put((int[]) src, srcOff, components);
            case LONG -> b.asLongBuffer().put((long[]) src, srcOff, components);
            case FLOAT -> b.asFloatBuffer().put((float[]) src, srcOff, components);
            case DOUBLE -> b.asDoubleBuffer().put((double[]) src, srcOff, components);
            default -> throw new IOException("no Java array holds " + type);
        }
    }

    /** Encodes {@code voxels} voxels into a fresh byte array. */
    public static byte[] encode(Object src, NiftiDataType type, ByteOrder order, int voxels)
            throws IOException {
        byte[] dst = new byte[Math.multiplyExact(voxels, type.bytesPerVoxel())];
        encode(src, 0, type, order, dst, 0, voxels);
        return dst;
    }

    // ---- widening one component ----

    /**
     * Component {@code i} of {@code array} as a {@code double}, with the
     * unsigned types widened as unsigned.
     *
     * <p>This is where UINT16's 40000 stops being a {@code short} of -25536.
     * The 64-bit types lose their low bits past 2^53, which is what a
     * {@code double} is; nothing in Java holds them exactly.</p>
     */
    public static double toDouble(Object array, int i, NiftiDataType type) {
        return switch (type.carrier) {
            case BYTE -> type.signedType
                    ? ((byte[]) array)[i] : ((byte[]) array)[i] & 0xFF;
            case SHORT -> type.signedType
                    ? ((short[]) array)[i] : ((short[]) array)[i] & 0xFFFF;
            case INT -> type.signedType
                    ? ((int[]) array)[i] : ((int[]) array)[i] & 0xFFFFFFFFL;
            case LONG -> type.signedType
                    ? (double) ((long[]) array)[i] : unsignedToDouble(((long[]) array)[i]);
            case FLOAT -> ((float[]) array)[i];
            case DOUBLE -> ((double[]) array)[i];
            default -> throw new IllegalArgumentException("no components in " + type);
        };
    }

    /**
     * An unsigned 64-bit value as a {@code double}. Java's widening conversion
     * is signed, so anything with bit 63 set would come back negative; halving
     * first keeps it in range and doubling afterwards restores it, at the cost
     * of the low bit — which a {@code double} could not have held anyway.
     */
    public static double unsignedToDouble(long v) {
        if (v >= 0) {
            return v;
        }
        return (double) (v >>> 1) * 2.0 + (v & 1);
    }

    /**
     * Sets component {@code i} of {@code array} from a {@code double}, rounding
     * to nearest for the integer types and clamping to what the type holds.
     *
     * <p>Clamping rather than wrapping: a value past the top of the range came
     * from somewhere, and saturating keeps it the largest value the file can
     * express instead of turning it into a small or negative one.</p>
     */
    public static void fromDouble(Object array, int i, double v, NiftiDataType type) {
        switch (type.carrier) {
            case BYTE -> ((byte[]) array)[i] = (byte) (type.signedType
                    ? clamp(v, Byte.MIN_VALUE, Byte.MAX_VALUE)
                    : clamp(v, 0, 0xFF));
            case SHORT -> ((short[]) array)[i] = (short) (type.signedType
                    ? clamp(v, Short.MIN_VALUE, Short.MAX_VALUE)
                    : clamp(v, 0, 0xFFFF));
            case INT -> ((int[]) array)[i] = (int) (type.signedType
                    ? clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE)
                    : clamp(v, 0, 0xFFFFFFFFL));
            case LONG -> ((long[]) array)[i] = type.signedType
                    ? clamp(v, Long.MIN_VALUE, Long.MAX_VALUE)
                    : doubleToUnsignedLong(v);
            case FLOAT -> ((float[]) array)[i] = (float) v;
            case DOUBLE -> ((double[]) array)[i] = v;
            default -> throw new IllegalArgumentException("no components in " + type);
        }
    }

    private static long clamp(double v, long lo, long hi) {
        if (Double.isNaN(v)) {
            return 0;
        }
        if (v <= lo) {
            return lo;
        }
        if (v >= hi) {
            return hi;
        }
        return Math.round(v);
    }

    /** A {@code double} into an unsigned 64-bit field, clamped to [0, 2^64). */
    private static long doubleToUnsignedLong(double v) {
        if (Double.isNaN(v) || v <= 0) {
            return 0;
        }
        if (v >= 1.8446744073709552E19) {   // 2^64
            return -1L;                     // every bit set: the largest unsigned value
        }
        if (v < 9.223372036854776E18) {     // 2^63: fits signed
            return Math.round(v);
        }
        return Math.round(v - 9.223372036854776E18) + Long.MIN_VALUE;
    }

    /**
     * Every component of {@code array} widened to {@code double} and put
     * through the header's scaling — the values the file is a record of, rather
     * than the numbers it stores.
     *
     * <p>{@code scl_slope}/{@code scl_inter} apply when the header says they
     * do, which is never for the colour types and never when the slope is zero.
     * See {@link NiftiHeader#scalingApplies()}.</p>
     */
    public static double[] toScaledDoubles(Object array, int components,
                                           NiftiDataType type, NiftiHeader header) {
        double[] out = new double[components];
        boolean scale = header.scalingApplies();
        double slope = header.sclSlope;
        double inter = header.sclInter;
        for (int i = 0; i < components; i++) {
            double v = toDouble(array, i, type);
            out[i] = scale ? slope * v + inter : v;
        }
        return out;
    }
}
