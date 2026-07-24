package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.NiftiDataType;
import com.ebremer.cygnus.nifti.NiftiHeader;
import com.ebremer.cygnus.nifti.NiftiVersion;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The voxel codec: widths, signedness, byte order, and component interleaving. */
class VoxelCodecTest {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
    private static final ByteOrder BE = ByteOrder.BIG_ENDIAN;

    /** The types this module decodes. */
    private static List<NiftiDataType> decodable() {
        return Arrays.stream(NiftiDataType.values()).filter(t -> t.supported).toList();
    }

    /**
     * An array of the type's natural kind holding the values most likely to
     * expose a width or signedness mistake: both extremes, both signs of zero,
     * and the bit patterns that read as negative in a signed carrier.
     *
     * <p>Lengths are a whole number of voxels for every type sharing the
     * carrier — twelve for the byte types, which have to divide by three for
     * RGB24 and by four for RGBA32; eight elsewhere, where a type has one
     * component or two.</p>
     */
    private static Object extremes(NiftiDataType type) {
        return switch (type.carrier) {
            case BYTE -> new byte[] {
                0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0x80,
                (byte) 0xFF, 42, (byte) 0x7F, (byte) 0xAA, (byte) 0x55, 3};
            case SHORT -> new short[] {
                0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE,
                (short) 0x8000, (short) 0xFFFF, (short) 40000};
            case INT -> new int[] {
                0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x80000000, -2, 123456};
            case LONG -> new long[] {
                0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x8000000000000000L, -2, 1L << 40};
            case FLOAT -> new float[] {
                0f, -0f, 1f, -1f, Float.MIN_VALUE, Float.MAX_VALUE,
                Float.NaN, Float.POSITIVE_INFINITY};
            case DOUBLE -> new double[] {
                0d, -0d, 1d, -1d, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.NaN, Double.NEGATIVE_INFINITY};
            default -> throw new IllegalArgumentException(type.toString());
        };
    }

    private static int length(Object array) {
        return java.lang.reflect.Array.getLength(array);
    }

    private static void assertArraysEqual(Object expected, Object actual, String message) {
        switch (expected) {
            case byte[] a -> assertArrayEquals(a, (byte[]) actual, message);
            case short[] a -> assertArrayEquals(a, (short[]) actual, message);
            case int[] a -> assertArrayEquals(a, (int[]) actual, message);
            case long[] a -> assertArrayEquals(a, (long[]) actual, message);
            case float[] a -> assertArrayEquals(a, (float[]) actual, message);
            case double[] a -> assertArrayEquals(a, (double[]) actual, message);
            default -> throw new IllegalArgumentException(expected.getClass().toString());
        }
    }

    // =====================================================================
    // Round-trips, one dynamic test per type per byte order.
    // =====================================================================

    @TestFactory
    List<DynamicTest> everyDecodableTypeRoundTripsItsExtremes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : decodable()) {
            for (ByteOrder order : new ByteOrder[] {LE, BE}) {
                tests.add(DynamicTest.dynamicTest(type + " " + order, () -> {
                    Object in = extremes(type);
                    int voxels = length(in) / type.components;
                    assertEquals(length(in), voxels * type.components,
                            "the fixture must be a whole number of voxels");
                    byte[] bytes = VoxelCodec.encode(in, type, order, voxels);
                    assertEquals((long) voxels * type.bytesPerVoxel(), bytes.length,
                            "encoded length");
                    assertEquals(VoxelCodec.byteLength(type, voxels), bytes.length);

                    Object back = VoxelCodec.decode(bytes, 0, type, order, voxels);
                    assertArraysEqual(in, back, type + " " + order + " round-trip");
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> decodingAtAnOffsetReadsTheSameValues() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : decodable()) {
            tests.add(DynamicTest.dynamicTest(type.toString(), () -> {
                Object in = extremes(type);
                int voxels = length(in) / type.components;
                byte[] payload = VoxelCodec.encode(in, type, BE, voxels);

                // the same bytes 37 into a longer buffer, as a file read gives them
                byte[] framed = new byte[37 + payload.length + 11];
                Arrays.fill(framed, (byte) 0xA5);
                System.arraycopy(payload, 0, framed, 37, payload.length);

                Object dst = VoxelCodec.allocate(type, voxels);
                VoxelCodec.decode(framed, 37, type, BE, dst, 0, voxels);
                assertArraysEqual(in, dst, type + " decoded at an offset");
            }));
        }
        return tests;
    }

    @Test
    void aShortSourceIsRejectedRatherThanIndexedPastTheEnd() {
        byte[] src = new byte[10];
        IOException e = assertThrows(IOException.class,
                () -> VoxelCodec.decode(src, 0, NiftiDataType.INT32, LE, 4));
        assertTrue(e.getMessage().contains("16 bytes"), e.getMessage());

        assertThrows(IOException.class,
                () -> VoxelCodec.decode(src, 8, NiftiDataType.INT16, LE, 4));
        assertThrows(IOException.class,
                () -> VoxelCodec.decode(src, -1, NiftiDataType.INT16, LE, 1));
        assertThrows(IOException.class, () -> VoxelCodec.encode(
                new short[4], 0, NiftiDataType.INT16, LE, new byte[4], 0, 4));
    }

    // =====================================================================
    // Byte layout: width and order, asserted against bytes written by hand.
    // =====================================================================

    @Test
    void widthAndOrderAreWhatTheTypeSays() throws IOException {
        assertArrayEquals(new byte[] {0x02, 0x01},
                VoxelCodec.encode(new short[] {0x0102}, NiftiDataType.INT16, LE, 1));
        assertArrayEquals(new byte[] {0x01, 0x02},
                VoxelCodec.encode(new short[] {0x0102}, NiftiDataType.INT16, BE, 1));
        assertArrayEquals(new byte[] {0x04, 0x03, 0x02, 0x01},
                VoxelCodec.encode(new int[] {0x01020304}, NiftiDataType.INT32, LE, 1));
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04},
                VoxelCodec.encode(new int[] {0x01020304}, NiftiDataType.INT32, BE, 1));
        assertArrayEquals(new byte[] {8, 7, 6, 5, 4, 3, 2, 1},
                VoxelCodec.encode(new long[] {0x0102030405060708L},
                        NiftiDataType.INT64, LE, 1));

        // an 8-bit type has no byte order to get wrong
        assertArrayEquals(new byte[] {(byte) 0xFE},
                VoxelCodec.encode(new byte[] {(byte) 0xFE}, NiftiDataType.UINT8, LE, 1));
        assertArrayEquals(new byte[] {(byte) 0xFE},
                VoxelCodec.encode(new byte[] {(byte) 0xFE}, NiftiDataType.UINT8, BE, 1));
    }

    @Test
    void complexComponentsAreInterleavedRealThenImaginary() throws IOException {
        // two COMPLEX64 voxels: (1, 2) and (3, 4)
        float[] in = {1f, 2f, 3f, 4f};
        byte[] bytes = VoxelCodec.encode(in, NiftiDataType.COMPLEX64, BE, 2);
        assertEquals(16, bytes.length, "two voxels of 8 bytes");

        assertArrayEquals(floatBytesBE(1f), Arrays.copyOfRange(bytes, 0, 4), "voxel 0 real");
        assertArrayEquals(floatBytesBE(2f), Arrays.copyOfRange(bytes, 4, 8), "voxel 0 imaginary");
        assertArrayEquals(floatBytesBE(3f), Arrays.copyOfRange(bytes, 8, 12), "voxel 1 real");
        assertArrayEquals(floatBytesBE(4f), Arrays.copyOfRange(bytes, 12, 16), "voxel 1 imaginary");

        assertArrayEquals(in, (float[]) VoxelCodec.decode(bytes, 0,
                NiftiDataType.COMPLEX64, BE, 2));

        // COMPLEX128 is the same shape at double width
        double[] in128 = {1, 2, 3, 4};
        assertEquals(32, VoxelCodec.encode(in128, NiftiDataType.COMPLEX128, LE, 2).length);
    }

    private static byte[] floatBytesBE(float f) {
        int bits = Float.floatToRawIntBits(f);
        return new byte[] {
            (byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits};
    }

    @Test
    void colourComponentsAreInterleavedPerVoxelNotPlanar() throws IOException {
        // two RGB24 voxels: (10,20,30) and (40,50,60)
        byte[] rgb = {10, 20, 30, 40, 50, 60};
        byte[] bytes = VoxelCodec.encode(rgb, NiftiDataType.RGB24, LE, 2);
        assertArrayEquals(rgb, bytes, "r,g,b then r,g,b -- not all r, then all g");
        assertEquals(3, NiftiDataType.RGB24.bytesPerVoxel());
        assertEquals(3, NiftiDataType.RGB24.components);

        byte[] rgba = {10, 20, 30, (byte) 255, 40, 50, 60, 0};
        assertArrayEquals(rgba, VoxelCodec.encode(rgba, NiftiDataType.RGBA32, BE, 2));
        assertEquals(4, NiftiDataType.RGBA32.bytesPerVoxel());

        // and 255 is 255, not -1, when it is widened
        assertEquals(255.0, VoxelCodec.toDouble(rgba, 3, NiftiDataType.RGBA32));
    }

    // =====================================================================
    // Unsigned widening: the classic way to read a scanner's output negative.
    // =====================================================================

    @Test
    void unsignedTypesWidenAsUnsigned() {
        byte[] b = {(byte) 0xFF, (byte) 0x80};
        assertEquals(255.0, VoxelCodec.toDouble(b, 0, NiftiDataType.UINT8));
        assertEquals(128.0, VoxelCodec.toDouble(b, 1, NiftiDataType.UINT8));
        assertEquals(-1.0, VoxelCodec.toDouble(b, 0, NiftiDataType.INT8));
        assertEquals(-128.0, VoxelCodec.toDouble(b, 1, NiftiDataType.INT8));

        short[] s = {(short) 40000, (short) 0xFFFF};
        assertEquals(40000.0, VoxelCodec.toDouble(s, 0, NiftiDataType.UINT16),
                "a UINT16 of 40000 is 40000, not -25536");
        assertEquals(65535.0, VoxelCodec.toDouble(s, 1, NiftiDataType.UINT16));
        assertEquals(-25536.0, VoxelCodec.toDouble(s, 0, NiftiDataType.INT16));

        int[] i = {0x80000000, -1};
        assertEquals(2147483648.0, VoxelCodec.toDouble(i, 0, NiftiDataType.UINT32));
        assertEquals(4294967295.0, VoxelCodec.toDouble(i, 1, NiftiDataType.UINT32));
        assertEquals(-2147483648.0, VoxelCodec.toDouble(i, 0, NiftiDataType.INT32));

        long[] l = {Long.MIN_VALUE, -1L, Long.MAX_VALUE, 0};
        assertEquals(9.223372036854776E18, VoxelCodec.toDouble(l, 0, NiftiDataType.UINT64));
        assertEquals(1.8446744073709552E19, VoxelCodec.toDouble(l, 1, NiftiDataType.UINT64));
        assertEquals(9.223372036854776E18, VoxelCodec.toDouble(l, 2, NiftiDataType.UINT64));
        assertEquals(0.0, VoxelCodec.toDouble(l, 3, NiftiDataType.UINT64));
        assertEquals(-9.223372036854776E18, VoxelCodec.toDouble(l, 0, NiftiDataType.INT64));
    }

    @Test
    void unsignedToDoubleMatchesTheUnsignedDecimalValue() {
        for (long v : new long[] {0, 1, 42, Long.MAX_VALUE, Long.MIN_VALUE, -1, -2,
                                  0xFFFFFFFFL, 1L << 62}) {
            double got = VoxelCodec.unsignedToDouble(v);
            double want = Double.parseDouble(Long.toUnsignedString(v));
            assertEquals(want, got, Math.ulp(want), "unsigned " + Long.toUnsignedString(v));
            assertTrue(got >= 0, "an unsigned value is never negative");
        }
    }

    @Test
    void fromDoubleClampsRatherThanWrapping() {
        byte[] b = new byte[4];
        VoxelCodec.fromDouble(b, 0, 300, NiftiDataType.UINT8);
        VoxelCodec.fromDouble(b, 1, -5, NiftiDataType.UINT8);
        VoxelCodec.fromDouble(b, 2, 300, NiftiDataType.INT8);
        VoxelCodec.fromDouble(b, 3, Double.NaN, NiftiDataType.UINT8);
        assertEquals(255.0, VoxelCodec.toDouble(b, 0, NiftiDataType.UINT8), "clamped, not wrapped to 44");
        assertEquals(0.0, VoxelCodec.toDouble(b, 1, NiftiDataType.UINT8));
        assertEquals(127.0, VoxelCodec.toDouble(b, 2, NiftiDataType.INT8));
        assertEquals(0.0, VoxelCodec.toDouble(b, 3, NiftiDataType.UINT8));

        long[] l = new long[3];
        VoxelCodec.fromDouble(l, 0, 1.8446744073709552E19, NiftiDataType.UINT64);
        VoxelCodec.fromDouble(l, 1, 1e30, NiftiDataType.UINT64);
        VoxelCodec.fromDouble(l, 2, 1e19, NiftiDataType.UINT64);
        assertEquals(-1L, l[0], "2^64 saturates to every bit set");
        assertEquals(-1L, l[1]);
        assertTrue(VoxelCodec.toDouble(l, 2, NiftiDataType.UINT64) > 9.9e18,
                "a value above 2^63 stays above it");
    }

    @TestFactory
    List<DynamicTest> everyIntegerTypeSurvivesADoubleRoundTripInItsRange() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : decodable()) {
            if (type.carrier == NiftiDataType.Carrier.FLOAT
                    || type.carrier == NiftiDataType.Carrier.DOUBLE) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(type.toString(), () -> {
                Object a = VoxelCodec.allocate(type, 8 / type.components * type.components);
                double[] values = {0, 1, 2, 100, 127};
                for (int i = 0; i < values.length; i++) {
                    VoxelCodec.fromDouble(a, i, values[i], type);
                    assertEquals(values[i], VoxelCodec.toDouble(a, i, type),
                            type + " value " + values[i]);
                }
            }));
        }
        return tests;
    }

    // =====================================================================
    // datatype and bitpix.
    // =====================================================================

    private static NiftiHeader header(int datatype, int bitpix) {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);
        h.datatype = datatype;
        h.bitpix = bitpix;
        return h;
    }

    @Test
    void bitpixMustAgreeWithDatatype() throws IOException {
        assertSame(NiftiDataType.INT16, VoxelCodec.resolve(header(4, 16)));

        IOException e = assertThrows(IOException.class,
                () -> VoxelCodec.resolve(header(4, 8)));
        assertTrue(e.getMessage().contains("bitpix 8"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot both be right"), e.getMessage());

        assertThrows(IOException.class, () -> VoxelCodec.resolve(header(16, 64)));
    }

    @Test
    void aZeroBitpixIsAnOmissionNotAContradiction() throws IOException {
        // Some writers never set bitpix. Zero cannot be a real claim about any
        // type, so it is taken from the datatype rather than fought over.
        assertSame(NiftiDataType.FLOAT64, VoxelCodec.resolve(header(64, 0)));
        assertSame(NiftiDataType.RGB24, VoxelCodec.resolve(header(128, 0)));
    }

    @Test
    void anUnknownDatatypeIsRejected() {
        IOException e = assertThrows(IOException.class,
                () -> VoxelCodec.resolve(header(1234, 16)));
        assertTrue(e.getMessage().contains("1234"), e.getMessage());
        assertThrows(IOException.class, () -> VoxelCodec.resolve(header(0, 0)));
    }

    @TestFactory
    List<DynamicTest> theUndecodedTypesParseAndThenSayWhy() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : Arrays.stream(NiftiDataType.values())
                .filter(t -> !t.supported && t != NiftiDataType.UNKNOWN).toList()) {
            tests.add(DynamicTest.dynamicTest(type.toString(), () -> {
                // the header still parses: the type is known, it is just not decoded
                assertSame(type, VoxelCodec.resolve(header(type.code, type.bitpix)));
                assertSame(type, NiftiDataType.byCode(type.code));

                IOException e = assertThrows(IOException.class,
                        () -> VoxelCodec.resolveDecodable(header(type.code, type.bitpix)));
                assertTrue(e.getMessage().contains(type.name()),
                        "the message names the type: " + e.getMessage());
                assertTrue(e.getMessage().contains("raw bytes"),
                        "and says what is still possible: " + e.getMessage());

                assertThrows(IOException.class, () -> VoxelCodec.allocate(type, 4));
                assertThrows(IOException.class,
                        () -> VoxelCodec.decode(new byte[1024], 0, type, LE, 4));
            }));
        }
        return tests;
    }

    @Test
    void binarySaysWhatIsWrongWithIt() {
        IOException e = assertThrows(IOException.class,
                () -> VoxelCodec.requireDecodable(NiftiDataType.BINARY));
        assertTrue(e.getMessage().contains("one bit per voxel"), e.getMessage());
        assertTrue(e.getMessage().contains("packing"), e.getMessage());

        e = assertThrows(IOException.class,
                () -> VoxelCodec.requireDecodable(NiftiDataType.FLOAT128));
        assertTrue(e.getMessage().contains("long double"), e.getMessage());
    }

    // =====================================================================
    // Scaling.
    // =====================================================================

    @Test
    void scalingIsAppliedSkippedForColourAndSkippedForAZeroSlope() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, 4, 4);
        h.sclSlope = 0.5;
        h.sclInter = -100;
        short[] raw = {0, 200, -200, 32767};

        double[] scaled = VoxelCodec.toScaledDoubles(raw, 4, NiftiDataType.INT16, h);
        assertArrayEquals(new double[] {-100, 0, -200, 16283.5}, scaled);

        h.sclSlope = 0;
        assertArrayEquals(new double[] {0, 200, -200, 32767},
                VoxelCodec.toScaledDoubles(raw, 4, NiftiDataType.INT16, h),
                "a zero slope means no scaling, not a multiply by zero");

        // colour is never scaled, whatever the slope says
        h.sclSlope = 2;
        h.datatype = NiftiDataType.RGB24.code;
        byte[] rgb = {10, 20, 30};
        assertArrayEquals(new double[] {10, 20, 30},
                VoxelCodec.toScaledDoubles(rgb, 3, NiftiDataType.RGB24, h));
    }

    @Test
    void scalingWidensAnUnsignedTypeBeforeItScalesIt() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT16, 4, 4);
        h.datatype = NiftiDataType.UINT16.code;
        h.sclSlope = 1;
        h.sclInter = 0;
        short[] raw = {(short) 40000};
        assertArrayEquals(new double[] {40000},
                VoxelCodec.toScaledDoubles(raw, 1, NiftiDataType.UINT16, h),
                "widen first, then scale");
    }

    @Test
    void aNonFiniteSlopeScalesNothing() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.FLOAT32, 4, 4);
        h.datatype = NiftiDataType.FLOAT32.code;
        float[] raw = {1, 2, 3};

        h.sclSlope = Double.NaN;
        assertArrayEquals(new double[] {1, 2, 3},
                VoxelCodec.toScaledDoubles(raw, 3, NiftiDataType.FLOAT32, h));

        h.sclSlope = Double.POSITIVE_INFINITY;
        assertArrayEquals(new double[] {1, 2, 3},
                VoxelCodec.toScaledDoubles(raw, 3, NiftiDataType.FLOAT32, h));

        h.sclSlope = 1;
        h.sclInter = Double.NaN;
        assertArrayEquals(new double[] {1, 2, 3},
                VoxelCodec.toScaledDoubles(raw, 3, NiftiDataType.FLOAT32, h),
                "a NaN intercept would make every voxel NaN");
    }

    @Test
    void allocateSizesForComponentsNotVoxels() throws IOException {
        assertEquals(6, ((byte[]) VoxelCodec.allocate(NiftiDataType.RGB24, 2)).length);
        assertEquals(8, ((byte[]) VoxelCodec.allocate(NiftiDataType.RGBA32, 2)).length);
        assertEquals(4, ((float[]) VoxelCodec.allocate(NiftiDataType.COMPLEX64, 2)).length);
        assertEquals(4, ((double[]) VoxelCodec.allocate(NiftiDataType.COMPLEX128, 2)).length);
        assertEquals(2, ((short[]) VoxelCodec.allocate(NiftiDataType.UINT16, 2)).length);
        assertEquals(2, ((long[]) VoxelCodec.allocate(NiftiDataType.UINT64, 2)).length);
    }

    @Test
    void byteLengthIsComputedInLong() {
        // 2^30 voxels of an 8-byte type is 2^33 bytes: an int would have wrapped
        assertEquals(1L << 33, VoxelCodec.byteLength(NiftiDataType.FLOAT64, 1L << 30));
        assertEquals(3L * (1L << 31), VoxelCodec.byteLength(NiftiDataType.RGB24, 1L << 31));
        assertTrue(VoxelCodec.byteLength(NiftiDataType.COMPLEX128, 1L << 40) > 0);
    }
}
