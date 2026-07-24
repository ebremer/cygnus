package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header codec against hand-laid-out bytes.
 *
 * <p>The golden headers here are built by writing each field at the absolute
 * offset the specification gives it, with the offsets as literals rather than
 * as the codec's own constants — otherwise the test would only prove the codec
 * agrees with itself. Every field gets a value distinct from its neighbours of
 * the same width, so a field read one slot along cannot pass.</p>
 */
class NiftiHeaderCodecTest {

    // ---- the values the golden headers carry, in one place ----

    private static final String GOLD_DATA_TYPE = "dt_ANALYZE";   // exactly 10
    private static final String GOLD_DB_NAME = "db_name_field_1234"; // exactly 18
    private static final int GOLD_EXTENTS = 16384;
    private static final short GOLD_SESSION_ERROR = 4660;
    private static final byte GOLD_REGULAR = 'r';
    private static final int GOLD_DIM_INFO = 45;                 // freq 1, phase 3, slice 2
    private static final long[] GOLD_DIM = {4, 11, 13, 17, 19, 1, 1, 1};
    private static final double GOLD_INTENT_P1 = 101.5;
    private static final double GOLD_INTENT_P2 = 102.25;
    private static final double GOLD_INTENT_P3 = 103.125;
    private static final int GOLD_INTENT_CODE = NiftiIntent.ZSCORE;
    private static final int GOLD_DATATYPE = NiftiDataType.INT16.code;
    private static final int GOLD_BITPIX = 16;
    private static final long GOLD_SLICE_START = 2;
    private static final double[] GOLD_PIXDIM = {-1, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5};
    private static final long GOLD_VOX_OFFSET = 352;
    private static final double GOLD_SCL_SLOPE = 0.25;
    private static final double GOLD_SCL_INTER = -8;
    private static final long GOLD_SLICE_END = 15;
    private static final int GOLD_SLICE_CODE = NiftiSlice.ALT_INC;
    private static final int GOLD_XYZT_UNITS = NiftiUnits.MM | NiftiUnits.SEC;
    private static final double GOLD_CAL_MAX = 4095;
    private static final double GOLD_CAL_MIN = -1024;
    private static final double GOLD_SLICE_DURATION = 0.078125;
    private static final double GOLD_TOFFSET = 12.5;
    private static final int GOLD_GLMAX = 32767;
    private static final int GOLD_GLMIN = -32768;
    private static final String GOLD_DESCRIP = "cygnus golden header";
    private static final String GOLD_AUX_FILE = "aux.txt";
    private static final int GOLD_QFORM_CODE = NiftiXform.SCANNER_ANAT;
    private static final int GOLD_SFORM_CODE = NiftiXform.MNI_152;
    private static final double GOLD_QUATERN_B = 0.125;
    private static final double GOLD_QUATERN_C = 0.25;
    private static final double GOLD_QUATERN_D = 0.375;
    private static final double GOLD_QOFFSET_X = -90;
    private static final double GOLD_QOFFSET_Y = -126;
    private static final double GOLD_QOFFSET_Z = -72;
    private static final double[] GOLD_SROW_X = {1.5, 0.0625, 0.125, -90};
    private static final double[] GOLD_SROW_Y = {0.1875, 2.5, 0.25, -126};
    private static final double[] GOLD_SROW_Z = {0.3125, 0.375, 3.5, -72};
    private static final String GOLD_INTENT_NAME = "zscore";
    private static final byte[] GOLD_UNUSED_STR = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    // =====================================================================
    // Golden bytes: the specification's offset tables, written out by hand.
    // =====================================================================

    /** A NIfTI-1 header laid out at the offsets in the published table. */
    private static byte[] goldenV1(ByteOrder order, byte[] magic) {
        ByteBuffer b = ByteBuffer.allocate(348).order(order);
        b.putInt(0, 348);                                 // sizeof_hdr
        b.put(4, ascii(GOLD_DATA_TYPE));                  // data_type[10]
        b.put(14, ascii(GOLD_DB_NAME));                   // db_name[18]
        b.putInt(32, GOLD_EXTENTS);                       // extents
        b.putShort(36, GOLD_SESSION_ERROR);               // session_error
        b.put(38, GOLD_REGULAR);                          // regular
        b.put(39, (byte) GOLD_DIM_INFO);                  // dim_info
        for (int i = 0; i < 8; i++) {
            b.putShort(40 + 2 * i, (short) GOLD_DIM[i]);  // dim[8]
        }
        b.putFloat(56, (float) GOLD_INTENT_P1);           // intent_p1
        b.putFloat(60, (float) GOLD_INTENT_P2);           // intent_p2
        b.putFloat(64, (float) GOLD_INTENT_P3);           // intent_p3
        b.putShort(68, (short) GOLD_INTENT_CODE);         // intent_code
        b.putShort(70, (short) GOLD_DATATYPE);            // datatype
        b.putShort(72, (short) GOLD_BITPIX);              // bitpix
        b.putShort(74, (short) GOLD_SLICE_START);         // slice_start
        for (int i = 0; i < 8; i++) {
            b.putFloat(76 + 4 * i, (float) GOLD_PIXDIM[i]); // pixdim[8]
        }
        b.putFloat(108, GOLD_VOX_OFFSET);                 // vox_offset
        b.putFloat(112, (float) GOLD_SCL_SLOPE);          // scl_slope
        b.putFloat(116, (float) GOLD_SCL_INTER);          // scl_inter
        b.putShort(120, (short) GOLD_SLICE_END);          // slice_end
        b.put(122, (byte) GOLD_SLICE_CODE);               // slice_code
        b.put(123, (byte) GOLD_XYZT_UNITS);               // xyzt_units
        b.putFloat(124, (float) GOLD_CAL_MAX);            // cal_max
        b.putFloat(128, (float) GOLD_CAL_MIN);            // cal_min
        b.putFloat(132, (float) GOLD_SLICE_DURATION);     // slice_duration
        b.putFloat(136, (float) GOLD_TOFFSET);            // toffset
        b.putInt(140, GOLD_GLMAX);                        // glmax
        b.putInt(144, GOLD_GLMIN);                        // glmin
        b.put(148, ascii(GOLD_DESCRIP));                  // descrip[80]
        b.put(228, ascii(GOLD_AUX_FILE));                 // aux_file[24]
        b.putShort(252, (short) GOLD_QFORM_CODE);         // qform_code
        b.putShort(254, (short) GOLD_SFORM_CODE);         // sform_code
        b.putFloat(256, (float) GOLD_QUATERN_B);          // quatern_b
        b.putFloat(260, (float) GOLD_QUATERN_C);          // quatern_c
        b.putFloat(264, (float) GOLD_QUATERN_D);          // quatern_d
        b.putFloat(268, (float) GOLD_QOFFSET_X);          // qoffset_x
        b.putFloat(272, (float) GOLD_QOFFSET_Y);          // qoffset_y
        b.putFloat(276, (float) GOLD_QOFFSET_Z);          // qoffset_z
        for (int i = 0; i < 4; i++) {
            b.putFloat(280 + 4 * i, (float) GOLD_SROW_X[i]);  // srow_x[4]
            b.putFloat(296 + 4 * i, (float) GOLD_SROW_Y[i]);  // srow_y[4]
            b.putFloat(312 + 4 * i, (float) GOLD_SROW_Z[i]);  // srow_z[4]
        }
        b.put(328, ascii(GOLD_INTENT_NAME));              // intent_name[16]
        if (magic != null) {
            b.put(344, magic);                            // magic[4]
        }
        return b.array();
    }

    /** A NIfTI-2 header laid out at the offsets in the published table. */
    private static byte[] goldenV2(ByteOrder order, byte[] magic) {
        ByteBuffer b = ByteBuffer.allocate(540).order(order);
        b.putInt(0, 540);                                 // sizeof_hdr
        b.put(4, magic);                                  // magic[8]
        b.putShort(12, (short) GOLD_DATATYPE);            // datatype
        b.putShort(14, (short) GOLD_BITPIX);              // bitpix
        for (int i = 0; i < 8; i++) {
            b.putLong(16 + 8 * i, GOLD_DIM[i]);           // dim[8]
        }
        b.putDouble(80, GOLD_INTENT_P1);                  // intent_p1
        b.putDouble(88, GOLD_INTENT_P2);                  // intent_p2
        b.putDouble(96, GOLD_INTENT_P3);                  // intent_p3
        for (int i = 0; i < 8; i++) {
            b.putDouble(104 + 8 * i, GOLD_PIXDIM[i]);     // pixdim[8]
        }
        b.putLong(168, GOLD_VOX_OFFSET);                  // vox_offset
        b.putDouble(176, GOLD_SCL_SLOPE);                 // scl_slope
        b.putDouble(184, GOLD_SCL_INTER);                 // scl_inter
        b.putDouble(192, GOLD_CAL_MAX);                   // cal_max
        b.putDouble(200, GOLD_CAL_MIN);                   // cal_min
        b.putDouble(208, GOLD_SLICE_DURATION);            // slice_duration
        b.putDouble(216, GOLD_TOFFSET);                   // toffset
        b.putLong(224, GOLD_SLICE_START);                 // slice_start
        b.putLong(232, GOLD_SLICE_END);                   // slice_end
        b.put(240, ascii(GOLD_DESCRIP));                  // descrip[80]
        b.put(320, ascii(GOLD_AUX_FILE));                 // aux_file[24]
        b.putInt(344, GOLD_QFORM_CODE);                   // qform_code
        b.putInt(348, GOLD_SFORM_CODE);                   // sform_code
        b.putDouble(352, GOLD_QUATERN_B);                 // quatern_b
        b.putDouble(360, GOLD_QUATERN_C);                 // quatern_c
        b.putDouble(368, GOLD_QUATERN_D);                 // quatern_d
        b.putDouble(376, GOLD_QOFFSET_X);                 // qoffset_x
        b.putDouble(384, GOLD_QOFFSET_Y);                 // qoffset_y
        b.putDouble(392, GOLD_QOFFSET_Z);                 // qoffset_z
        for (int i = 0; i < 4; i++) {
            b.putDouble(400 + 8 * i, GOLD_SROW_X[i]);     // srow_x[4]
            b.putDouble(432 + 8 * i, GOLD_SROW_Y[i]);     // srow_y[4]
            b.putDouble(464 + 8 * i, GOLD_SROW_Z[i]);     // srow_z[4]
        }
        b.putInt(496, GOLD_SLICE_CODE);                   // slice_code
        b.putInt(500, GOLD_XYZT_UNITS);                   // xyzt_units
        b.putInt(504, GOLD_INTENT_CODE);                  // intent_code
        b.put(508, ascii(GOLD_INTENT_NAME));              // intent_name[16]
        b.put(524, (byte) GOLD_DIM_INFO);                 // dim_info
        b.put(525, GOLD_UNUSED_STR);                      // unused_str[15]
        return b.array();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // =====================================================================
    // Golden-byte tests: four layouts, read and written back byte for byte.
    // =====================================================================

    @TestFactory
    List<DynamicTest> goldenHeadersDecodeAndReEncode() {
        record Case(String label, byte[] bytes, NiftiVersion version,
                    ByteOrder order, boolean single) {
        }
        List<Case> cases = List.of(
                new Case("v1 LE single", goldenV1(ByteOrder.LITTLE_ENDIAN,
                        NiftiVersion.MAGIC_NIFTI1_SINGLE),
                        NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, true),
                new Case("v1 BE pair", goldenV1(ByteOrder.BIG_ENDIAN,
                        NiftiVersion.MAGIC_NIFTI1_PAIR),
                        NiftiVersion.NIFTI1, ByteOrder.BIG_ENDIAN, false),
                new Case("v2 LE single", goldenV2(ByteOrder.LITTLE_ENDIAN,
                        NiftiVersion.MAGIC_NIFTI2_SINGLE),
                        NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, true),
                new Case("v2 BE pair", goldenV2(ByteOrder.BIG_ENDIAN,
                        NiftiVersion.MAGIC_NIFTI2_PAIR),
                        NiftiVersion.NIFTI2, ByteOrder.BIG_ENDIAN, false));

        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            tests.add(DynamicTest.dynamicTest(c.label(), () -> {
                NiftiHeader h = NiftiHeaderCodec.decode(c.bytes());
                assertEquals(c.version(), h.version, "version");
                assertEquals(c.order(), h.byteOrder, "byte order");
                assertEquals(c.single(), h.singleFile, "single file");
                assertGoldenFields(h, c.version());

                // and back out, byte for byte
                byte[] again = NiftiHeaderCodec.encode(h);
                assertEquals(c.version().headerSize, again.length, "header size");
                assertArrayEquals(c.bytes(), again, "re-encoded bytes differ");
            }));
        }
        return tests;
    }

    /** Every field the golden headers set, checked by value. */
    private static void assertGoldenFields(NiftiHeader h, NiftiVersion version) {
        assertEquals(GOLD_DIM_INFO, h.dimInfo, "dim_info");
        assertEquals(1, h.freqDim(), "freq_dim");
        assertEquals(3, h.phaseDim(), "phase_dim");
        assertEquals(2, h.sliceDim(), "slice_dim");
        assertArrayEquals(GOLD_DIM, h.dim, "dim");
        assertEquals(GOLD_INTENT_P1, h.intentP1, "intent_p1");
        assertEquals(GOLD_INTENT_P2, h.intentP2, "intent_p2");
        assertEquals(GOLD_INTENT_P3, h.intentP3, "intent_p3");
        assertEquals(GOLD_INTENT_CODE, h.intentCode, "intent_code");
        assertEquals(GOLD_DATATYPE, h.datatype, "datatype");
        assertEquals(GOLD_BITPIX, h.bitpix, "bitpix");
        assertEquals(GOLD_SLICE_START, h.sliceStart, "slice_start");
        assertArrayEquals(GOLD_PIXDIM, h.pixdim, "pixdim");
        assertEquals(GOLD_VOX_OFFSET, h.voxOffset, "vox_offset");
        assertEquals(GOLD_SCL_SLOPE, h.sclSlope, "scl_slope");
        assertEquals(GOLD_SCL_INTER, h.sclInter, "scl_inter");
        assertEquals(GOLD_SLICE_END, h.sliceEnd, "slice_end");
        assertEquals(GOLD_SLICE_CODE, h.sliceCode, "slice_code");
        assertEquals(GOLD_XYZT_UNITS, h.xyztUnits, "xyzt_units");
        assertEquals(NiftiUnits.MM, h.spaceUnits(), "space units");
        assertEquals(NiftiUnits.SEC, h.timeUnits(), "time units");
        assertEquals(GOLD_CAL_MAX, h.calMax, "cal_max");
        assertEquals(GOLD_CAL_MIN, h.calMin, "cal_min");
        assertEquals(GOLD_SLICE_DURATION, h.sliceDuration, "slice_duration");
        assertEquals(GOLD_TOFFSET, h.toffset, "toffset");
        assertEquals(GOLD_DESCRIP, h.descrip, "descrip");
        assertEquals(GOLD_AUX_FILE, h.auxFile, "aux_file");
        assertEquals(GOLD_QFORM_CODE, h.qformCode, "qform_code");
        assertEquals(GOLD_SFORM_CODE, h.sformCode, "sform_code");
        assertEquals(GOLD_QUATERN_B, h.quaternB, "quatern_b");
        assertEquals(GOLD_QUATERN_C, h.quaternC, "quatern_c");
        assertEquals(GOLD_QUATERN_D, h.quaternD, "quatern_d");
        assertEquals(GOLD_QOFFSET_X, h.qoffsetX, "qoffset_x");
        assertEquals(GOLD_QOFFSET_Y, h.qoffsetY, "qoffset_y");
        assertEquals(GOLD_QOFFSET_Z, h.qoffsetZ, "qoffset_z");
        assertArrayEquals(GOLD_SROW_X, h.srowX, "srow_x");
        assertArrayEquals(GOLD_SROW_Y, h.srowY, "srow_y");
        assertArrayEquals(GOLD_SROW_Z, h.srowZ, "srow_z");
        assertEquals(GOLD_INTENT_NAME, h.intentName, "intent_name");
        assertEquals(-1, h.qfac(), "qfac from pixdim[0]");

        if (version == NiftiVersion.NIFTI1) {
            assertArrayEquals(ascii(GOLD_DATA_TYPE), h.analyzeDataType, "data_type");
            assertArrayEquals(ascii(GOLD_DB_NAME), h.dbName, "db_name");
            assertEquals(GOLD_EXTENTS, h.extents, "extents");
            assertEquals(GOLD_SESSION_ERROR, h.sessionError, "session_error");
            assertEquals(GOLD_REGULAR, h.regular, "regular");
            assertEquals(GOLD_GLMAX, h.glmax, "glmax");
            assertEquals(GOLD_GLMIN, h.glmin, "glmin");
        } else {
            assertArrayEquals(GOLD_UNUSED_STR, h.unusedStr, "unused_str");
        }
    }

    @Test
    void headerSizesAreTheDeclaredOnes() throws IOException {
        assertEquals(348, NiftiHeaderCodec.encode(
                NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 2, 2)).length);
        assertEquals(540, NiftiHeaderCodec.encode(
                NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.UINT8, 2, 2)).length);
        assertEquals(348, NiftiVersion.NIFTI1.headerSize);
        assertEquals(540, NiftiVersion.NIFTI2.headerSize);
        assertEquals(348, NiftiVersion.ANALYZE75.headerSize);
    }

    @Test
    void sizeofHdrIsWrittenFromTheVersionNotACopy() throws IOException {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.INT16, 3, 3, 3);
        byte[] bytes = NiftiHeaderCodec.encode(h);
        assertEquals(540, ByteBuffer.wrap(bytes).order(h.byteOrder).getInt(0));
        assertArrayEquals(NiftiVersion.MAGIC_NIFTI2_SINGLE,
                Arrays.copyOfRange(bytes, 4, 12), "magic follows version + singleFile");

        h.singleFile = false;
        assertArrayEquals(NiftiVersion.MAGIC_NIFTI2_PAIR,
                Arrays.copyOfRange(NiftiHeaderCodec.encode(h), 4, 12));
    }

    // =====================================================================
    // Detection: version, byte order, and single-vs-pair from the bytes.
    // =====================================================================

    @TestFactory
    List<DynamicTest> detectionMatrix() {
        record Case(String label, byte[] bytes, NiftiVersion version,
                    ByteOrder order, boolean single) {
        }
        ByteOrder le = ByteOrder.LITTLE_ENDIAN;
        ByteOrder be = ByteOrder.BIG_ENDIAN;
        List<Case> cases = List.of(
                new Case("v1 LE n+1", goldenV1(le, NiftiVersion.MAGIC_NIFTI1_SINGLE),
                        NiftiVersion.NIFTI1, le, true),
                new Case("v1 LE ni1", goldenV1(le, NiftiVersion.MAGIC_NIFTI1_PAIR),
                        NiftiVersion.NIFTI1, le, false),
                new Case("v1 BE n+1", goldenV1(be, NiftiVersion.MAGIC_NIFTI1_SINGLE),
                        NiftiVersion.NIFTI1, be, true),
                new Case("v1 BE ni1", goldenV1(be, NiftiVersion.MAGIC_NIFTI1_PAIR),
                        NiftiVersion.NIFTI1, be, false),
                new Case("v2 LE n+2", goldenV2(le, NiftiVersion.MAGIC_NIFTI2_SINGLE),
                        NiftiVersion.NIFTI2, le, true),
                new Case("v2 LE ni2", goldenV2(le, NiftiVersion.MAGIC_NIFTI2_PAIR),
                        NiftiVersion.NIFTI2, le, false),
                new Case("v2 BE n+2", goldenV2(be, NiftiVersion.MAGIC_NIFTI2_SINGLE),
                        NiftiVersion.NIFTI2, be, true),
                new Case("v2 BE ni2", goldenV2(be, NiftiVersion.MAGIC_NIFTI2_PAIR),
                        NiftiVersion.NIFTI2, be, false),
                new Case("ANALYZE LE (no magic)", goldenV1(le, null),
                        NiftiVersion.ANALYZE75, le, false),
                new Case("ANALYZE BE (no magic)", goldenV1(be, null),
                        NiftiVersion.ANALYZE75, be, false),
                new Case("v1 LE, bogus sizeof_hdr, dim[0] fallback",
                        withBogusSizeofHdr(goldenV1(le, NiftiVersion.MAGIC_NIFTI1_SINGLE)),
                        NiftiVersion.NIFTI1, le, true),
                new Case("v1 BE, bogus sizeof_hdr, dim[0] fallback",
                        withBogusSizeofHdr(goldenV1(be, NiftiVersion.MAGIC_NIFTI1_PAIR)),
                        NiftiVersion.NIFTI1, be, false));

        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            tests.add(DynamicTest.dynamicTest(c.label(), () -> {
                NiftiHeaderCodec.Layout got = NiftiHeaderCodec.detect(c.bytes());
                assertEquals(c.version(), got.version(), "version");
                assertEquals(c.order(), got.byteOrder(), "byte order");
                assertEquals(c.single(), got.singleFile(), "single file");
            }));
        }
        return tests;
    }

    /** Corrupts sizeof_hdr so only the magic and dim[0] are left to go on. */
    private static byte[] withBogusSizeofHdr(byte[] header) {
        byte[] copy = header.clone();
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x5A5A5A5A);
        return copy;
    }

    @Test
    void swappedSizeofHdrConstantsAreTheRealThing() {
        // The two values nifti2.h tests for are exactly 348 and 540 byte-swapped.
        assertEquals(NiftiVersion.SWAPPED_348,
                Integer.reverseBytes(NiftiHeaderCodec.NIFTI1_HEADER_SIZE));
        assertEquals(NiftiVersion.SWAPPED_540,
                Integer.reverseBytes(NiftiHeaderCodec.NIFTI2_HEADER_SIZE));
        assertEquals(1543569408, NiftiVersion.SWAPPED_348);
        assertEquals(469893120, NiftiVersion.SWAPPED_540);
    }

    @Test
    void analyzeGeometryIsNotInvented() throws IOException {
        // The golden v1 bytes have a nonzero qform_code at 252, an sform at 254
        // and an scl_slope at 112. In an ANALYZE file those offsets hold the
        // patient-history struct and funused1, so none of it may be decoded.
        NiftiHeader h = NiftiHeaderCodec.decode(goldenV1(ByteOrder.LITTLE_ENDIAN, null));
        assertEquals(NiftiVersion.ANALYZE75, h.version);
        assertEquals(0, h.qformCode, "qform_code must not be read from an ANALYZE header");
        assertEquals(0, h.sformCode, "sform_code must not be read from an ANALYZE header");
        assertEquals(0, h.sclSlope, "scl_slope must not be read from an ANALYZE header");
        assertEquals(0, h.intentCode, "intent_code must not be read from an ANALYZE header");
        assertEquals(0, h.sliceStart, "slice_start must not be read from an ANALYZE header");
        assertEquals(0, h.toffset, "toffset must not be read from an ANALYZE header");
        assertArrayEquals(new double[4], h.srowX, "srow_x must not be read from an ANALYZE header");

        // ...while everything ANALYZE really does share is read.
        assertArrayEquals(GOLD_DIM, h.dim, "dim is shared with ANALYZE");
        assertEquals(GOLD_DATATYPE, h.datatype, "datatype is shared with ANALYZE");
        assertEquals(GOLD_BITPIX, h.bitpix, "bitpix is shared with ANALYZE");
        assertArrayEquals(GOLD_PIXDIM, h.pixdim, "pixdim is shared with ANALYZE");
        assertEquals(GOLD_VOX_OFFSET, h.voxOffset, "vox_offset is shared with ANALYZE");
        assertEquals(GOLD_CAL_MAX, h.calMax, "cal_max is shared with ANALYZE");
        assertEquals(GOLD_CAL_MIN, h.calMin, "cal_min is shared with ANALYZE");
        assertEquals(GOLD_GLMAX, h.glmax, "glmax is shared with ANALYZE");
        assertEquals(GOLD_DESCRIP, h.descrip, "descrip is shared with ANALYZE");
        assertEquals(GOLD_AUX_FILE, h.auxFile, "aux_file is shared with ANALYZE");

        // and the bytes are kept whole rather than half-interpreted
        assertNotNull(h.analyzeRaw, "the raw ANALYZE header is kept");
        assertArrayEquals(goldenV1(ByteOrder.LITTLE_ENDIAN, null), h.analyzeRaw);
    }

    @Test
    void analyzeIsNotWritten() {
        NiftiHeader h = new NiftiHeader();
        h.version = NiftiVersion.ANALYZE75;
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("read-only"), e.getMessage());
        assertFalse(NiftiVersion.ANALYZE75.writable());
        assertFalse(NiftiVersion.ANALYZE75.hasGeometry());
        assertNull(NiftiVersion.ANALYZE75.singleFileMagic());
    }

    // ---- detection failures ----

    @Test
    void a540ByteHeaderWithoutV2MagicIsRejected() {
        byte[] bad = goldenV2(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI2_SINGLE);
        bad[8] = 'X';   // break the \r\n\032\n sentinel, leaving "n+2\0" intact
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(bad));
        assertTrue(e.getMessage().contains("magic"), e.getMessage());
    }

    @Test
    void theSentinelCatchesLineEndingTranslation() {
        // What an ASCII-mode transfer does to a NIfTI-2 file on Windows: every
        // 0x0A grows a 0x0D in front of it. The magic no longer matches, which
        // is the entire point of carrying \r\n\032\n.
        byte[] mangled = goldenV2(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI2_SINGLE);
        mangled[9] = '\r';
        assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(mangled));
    }

    @Test
    void garbageIsRejectedWithTheSizeItSaw() {
        byte[] junk = new byte[400];
        Arrays.fill(junk, (byte) 0x7F);
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(junk));
        assertTrue(e.getMessage().contains("sizeof_hdr"), e.getMessage());
    }

    @Test
    void aShortHeaderIsRejectedRatherThanIndexed() {
        assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(new byte[0]));
        assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(new byte[347]));

        // sizeof_hdr says 540 but only 348 bytes are there
        byte[] truncatedV2 = Arrays.copyOf(
                goldenV2(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI2_SINGLE), 348);
        IOException e = assertThrows(IOException.class,
                () -> NiftiHeaderCodec.detect(truncatedV2));
        assertTrue(e.getMessage().contains("540"), e.getMessage());
    }

    @Test
    void aV1MagicWithNoUsableDimZeroIsRejected() {
        byte[] bad = withBogusSizeofHdr(
                goldenV1(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI1_SINGLE));
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(40, (short) 999);
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.detect(bad));
        assertTrue(e.getMessage().contains("dim[0]"), e.getMessage());
    }

    @Test
    void anUnrepresentableVoxOffsetIsRejectedOnRead() {
        byte[] bad = goldenV1(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI1_SINGLE);
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(108, Float.NaN);
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.decode(bad));
        assertTrue(e.getMessage().contains("vox_offset"), e.getMessage());
    }

    // =====================================================================
    // Round-trips over randomly generated headers.
    // =====================================================================

    @TestFactory
    List<DynamicTest> randomHeadersSurviveARoundTrip() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiVersion v : new NiftiVersion[] {NiftiVersion.NIFTI1, NiftiVersion.NIFTI2}) {
            for (ByteOrder o : new ByteOrder[] {
                    ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                for (boolean single : new boolean[] {true, false}) {
                    String label = v + " " + o + " " + (single ? "single" : "pair");
                    tests.add(DynamicTest.dynamicTest(label, () -> {
                        Random rnd = new Random(0xC0FFEEL + v.ordinal() * 31L
                                + o.hashCode() + (single ? 1 : 0));
                        for (int i = 0; i < 200; i++) {
                            NiftiHeader h = randomHeader(v, o, single, rnd);
                            NiftiHeader back = NiftiHeaderCodec.decode(
                                    NiftiHeaderCodec.encode(h));
                            assertEquals(h, back, "round-trip " + i + ": " + h);
                        }
                    }));
                }
            }
        }
        return tests;
    }

    /**
     * A header whose every field holds a value that version can express, so a
     * failed round-trip means the codec lost something rather than the format
     * having no room for it.
     */
    private static NiftiHeader randomHeader(NiftiVersion v, ByteOrder o,
                                            boolean single, Random rnd) {
        boolean v1 = v == NiftiVersion.NIFTI1;
        NiftiHeader h = new NiftiHeader();
        h.version = v;
        h.byteOrder = o;
        h.singleFile = single;
        h.dimInfo = rnd.nextInt(256);
        h.dim[0] = 1 + rnd.nextInt(7);
        for (int i = 1; i < 8; i++) {
            h.dim[i] = v1 ? rnd.nextInt(Short.MAX_VALUE + 1) : rnd.nextLong(1L << 40);
        }
        h.intentP1 = real(rnd, v1);
        h.intentP2 = real(rnd, v1);
        h.intentP3 = real(rnd, v1);
        h.intentCode = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextInt();
        h.datatype = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextInt(0x10000) - 0x8000;
        h.bitpix = rnd.nextInt(0x10000) - 0x8000;
        h.sliceStart = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextLong();
        for (int i = 0; i < 8; i++) {
            h.pixdim[i] = real(rnd, v1);
        }
        // vox_offset must survive v1's float exactly; multiples of 16 up to 2^24 do
        h.voxOffset = v1 ? 16L * rnd.nextInt(1 << 20) : rnd.nextLong(1L << 62);
        h.sclSlope = real(rnd, v1);
        h.sclInter = real(rnd, v1);
        h.sliceEnd = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextLong();
        h.sliceCode = v1 ? rnd.nextInt(256) : rnd.nextInt();
        h.xyztUnits = v1 ? rnd.nextInt(256) : rnd.nextInt();
        h.calMax = real(rnd, v1);
        h.calMin = real(rnd, v1);
        h.sliceDuration = real(rnd, v1);
        h.toffset = real(rnd, v1);
        h.descrip = text(rnd, 80);
        h.auxFile = text(rnd, 24);
        h.qformCode = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextInt();
        h.sformCode = v1 ? rnd.nextInt(0x10000) - 0x8000 : rnd.nextInt();
        h.quaternB = real(rnd, v1);
        h.quaternC = real(rnd, v1);
        h.quaternD = real(rnd, v1);
        h.qoffsetX = real(rnd, v1);
        h.qoffsetY = real(rnd, v1);
        h.qoffsetZ = real(rnd, v1);
        for (int i = 0; i < 4; i++) {
            h.srowX[i] = real(rnd, v1);
            h.srowY[i] = real(rnd, v1);
            h.srowZ[i] = real(rnd, v1);
        }
        h.intentName = text(rnd, 16);
        if (v1) {
            rnd.nextBytes(h.analyzeDataType);
            rnd.nextBytes(h.dbName);
            h.extents = rnd.nextInt();
            h.sessionError = (short) rnd.nextInt();
            h.regular = (byte) rnd.nextInt();
            h.glmax = rnd.nextInt();
            h.glmin = rnd.nextInt();
        } else {
            rnd.nextBytes(h.unusedStr);
        }
        return h;
    }

    /**
     * A real value the target version stores exactly. NIfTI-1's fields are
     * {@code float}, so a double that is not already one would come back
     * rounded; NaN and both infinities are legal stored values and turn up in
     * real files, so they are in the mix.
     */
    private static double real(Random rnd, boolean asFloat) {
        int pick = rnd.nextInt(20);
        double v = switch (pick) {
            case 0 -> Double.NaN;
            case 1 -> Double.POSITIVE_INFINITY;
            case 2 -> Double.NEGATIVE_INFINITY;
            case 3 -> 0.0;
            case 4 -> -0.0;
            default -> (rnd.nextDouble() - 0.5) * Math.pow(2, rnd.nextInt(40) - 20);
        };
        return asFloat ? (float) v : v;
    }

    /** A string of up to {@code max} bytes, sometimes exactly {@code max} to fill the field. */
    private static String text(Random rnd, int max) {
        int len = rnd.nextInt(4) == 0 ? max : rnd.nextInt(max + 1);
        StringBuilder s = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            // 1..255: never NUL, which is padding and is stripped by design
            s.append((char) (1 + rnd.nextInt(255)));
        }
        return s.toString();
    }

    @Test
    void aFieldFilledToItsFullWidthKeepsItsLastCharacter() throws IOException {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 2, 2);
        h.descrip = "x".repeat(79) + "Z";
        h.auxFile = "y".repeat(23) + "W";
        h.intentName = "z".repeat(15) + "V";
        NiftiHeader back = NiftiHeaderCodec.decode(NiftiHeaderCodec.encode(h));
        assertEquals(80, back.descrip.length(), "descrip fills its field");
        assertEquals('Z', back.descrip.charAt(79));
        assertEquals('W', back.auxFile.charAt(23));
        assertEquals('V', back.intentName.charAt(15));
    }

    @Test
    void junkAfterAStringTerminatorSurvives() throws IOException {
        // Writers that never clear their buffers leave the previous contents
        // after the NUL. Only trailing NULs are padding, so this comes back.
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.UINT8, 2, 2);
        h.descrip = "FSL5.0 leftover";
        NiftiHeader back = NiftiHeaderCodec.decode(NiftiHeaderCodec.encode(h));
        assertEquals(h.descrip, back.descrip);
    }

    @Test
    void aStringTooLongForItsFieldIsRefused() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 2, 2);
        h.descrip = "x".repeat(81);
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("descrip"), e.getMessage());
    }

    // =====================================================================
    // Widening to v2 and narrowing back to v1.
    // =====================================================================

    @Test
    void wideningToV2IsLossless() throws IOException {
        NiftiHeader v1 = NiftiHeaderCodec.decode(
                goldenV1(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI1_SINGLE));
        NiftiHeader v2 = v1.copy();
        v2.version = NiftiVersion.NIFTI2;
        NiftiHeader back = NiftiHeaderCodec.decode(NiftiHeaderCodec.encode(v2));

        assertArrayEquals(v1.dim, back.dim);
        assertArrayEquals(v1.pixdim, back.pixdim);
        assertEquals(v1.voxOffset, back.voxOffset);
        assertEquals(v1.sclSlope, back.sclSlope);
        assertEquals(v1.qformCode, back.qformCode);
        assertEquals(v1.quaternB, back.quaternB);
        assertArrayEquals(v1.srowZ, back.srowZ);
        assertEquals(v1.descrip, back.descrip);
        assertEquals(v1.intentName, back.intentName);
        assertEquals(v1.dimInfo, back.dimInfo);
        assertEquals(v1.xyztUnits, back.xyztUnits);
        assertEquals(v1.sliceCode, back.sliceCode);
    }

    @Test
    void fieldsV2DroppedAreNotAReasonToRefuseTheWrite() throws IOException {
        // data_type, db_name, extents, session_error, regular, glmax and glmin
        // have no NIfTI-2 field. They have been unused since ANALYZE.
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.INT16, 4, 4);
        h.glmax = 4095;
        h.glmin = -1;
        h.extents = 16384;
        NiftiHeader back = NiftiHeaderCodec.decode(NiftiHeaderCodec.encode(h));
        assertEquals(0, back.glmax);
        assertEquals(0, back.extents);
    }

    @Test
    void aDimTooBigForV1NamesTheField() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, 40000, 4, 4);
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("dim[1]"), e.getMessage());
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
        assertTrue(e.getMessage().contains("NIfTI-2"), "say what to do instead: " + e.getMessage());

        // ...and NIfTI-2 takes it without complaint, which is why it exists
        h.version = NiftiVersion.NIFTI2;
        assertEquals(40000, assertDoesNotThrowDecode(h).dim[1]);
    }

    @Test
    void aVoxOffsetPastFloatPrecisionIsRefusedByV1() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);

        h.voxOffset = 1 << 24;            // 16777216: the last exactly-countable offset
        assertEquals(1 << 24, assertDoesNotThrowDecode(h).voxOffset);

        h.voxOffset = (1 << 24) + 1;      // one past it, and a float can no longer say so
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("vox_offset"), e.getMessage());
        assertTrue(e.getMessage().contains("NIfTI-2"), e.getMessage());

        h.version = NiftiVersion.NIFTI2;
        assertEquals((1 << 24) + 1, assertDoesNotThrowDecode(h).voxOffset);
    }

    @Test
    void aFiniteDoubleThatOverflowsFloatIsRefusedByV1() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);
        h.calMax = 1e300;
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("cal_max"), e.getMessage());

        // an infinity, by contrast, is a value a float holds perfectly well
        h.calMax = Double.POSITIVE_INFINITY;
        assertEquals(Double.POSITIVE_INFINITY, assertDoesNotThrowDecode(h).calMax);
    }

    @Test
    void anOversizedByteFieldIsRefusedByV1() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);
        h.xyztUnits = 300;
        IOException e = assertThrows(IOException.class, () -> NiftiHeaderCodec.encode(h));
        assertTrue(e.getMessage().contains("xyzt_units"), e.getMessage());

        h.version = NiftiVersion.NIFTI2;
        assertEquals(300, assertDoesNotThrowDecode(h).xyztUnits);
    }

    private static NiftiHeader assertDoesNotThrowDecode(NiftiHeader h) {
        try {
            return NiftiHeaderCodec.decode(NiftiHeaderCodec.encode(h));
        } catch (IOException e) {
            throw new AssertionError("expected a clean round-trip", e);
        }
    }

    // =====================================================================
    // The derived helpers on the header itself.
    // =====================================================================

    @Test
    void effectiveDimCoercesTheZeroesRealFilesWrite() {
        NiftiHeader h = new NiftiHeader();
        h.dim = new long[] {4, 64, 64, 30, 0, 0, 0, 0};   // dim[0]=4, dim[4]=0
        assertEquals(64, h.nx());
        assertEquals(30, h.nz());
        assertEquals(1, h.nt(), "a declared but zero fourth dimension counts as one");
        assertEquals(1, h.effectiveDim(7), "beyond dim[0] is one");
        assertEquals(0, h.dim[4], "the raw field still says what the file said");
        assertEquals(64L * 64 * 30, h.voxelCount());
        assertEquals(30, h.sliceCount());
    }

    @Test
    void voxelCountSaturatesRatherThanWrapping() {
        NiftiHeader h = new NiftiHeader();
        h.dim = new long[] {7, Long.MAX_VALUE, Long.MAX_VALUE, 2, 2, 2, 2, 2};
        assertEquals(Long.MAX_VALUE, h.voxelCount(), "overflow saturates, never wraps");
        assertTrue(h.voxelCount() > 0, "and stays positive so a caller's check works");
    }

    @Test
    void scalingFollowsTheDocumentedRules() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, 4, 4);
        h.sclSlope = 2;
        h.sclInter = 10;
        assertTrue(h.scalingApplies());
        assertEquals(30.0, h.scaled(10));

        h.sclSlope = 0;
        assertFalse(h.scalingApplies(), "a zero slope is the documented no-scaling spelling");
        assertEquals(10.0, h.scaled(10));

        h.sclSlope = 2;
        h.datatype = NiftiDataType.RGB24.code;
        assertFalse(h.scalingApplies(), "colour components are never scaled");
        assertEquals(10.0, h.scaled(10));

        h.datatype = NiftiDataType.RGBA32.code;
        assertFalse(h.scalingApplies());

        h.datatype = NiftiDataType.FLOAT32.code;
        h.sclSlope = Double.NaN;
        assertFalse(h.scalingApplies(), "a NaN slope cannot scale anything");
    }

    @Test
    void qfacIsMinusOneOrOneAndNeverZero() {
        NiftiHeader h = new NiftiHeader();
        h.pixdim[0] = 0;
        assertEquals(1, h.qfac(), "an unset pixdim[0] means 1, not 0");
        h.pixdim[0] = -1;
        assertEquals(-1, h.qfac());
        h.pixdim[0] = 1;
        assertEquals(1, h.qfac());
    }

    @Test
    void dimInfoPacksAndUnpacks() {
        NiftiHeader h = new NiftiHeader();
        for (int f = 0; f <= 3; f++) {
            for (int p = 0; p <= 3; p++) {
                for (int s = 0; s <= 3; s++) {
                    h.setDimInfo(f, p, s);
                    assertEquals(f, h.freqDim());
                    assertEquals(p, h.phaseDim());
                    assertEquals(s, h.sliceDim());
                    assertTrue(h.dimInfo >= 0 && h.dimInfo <= 0xFF);
                }
            }
        }
    }

    @Test
    void unitsPackTwoFieldsIntoOneByte() {
        NiftiHeader h = new NiftiHeader();
        h.setUnits(NiftiUnits.MICRON, NiftiUnits.USEC);
        assertEquals(NiftiUnits.MICRON, h.spaceUnits());
        assertEquals(NiftiUnits.USEC, h.timeUnits());
        assertEquals(1e-6, NiftiUnits.metersPer(h.spaceUnits()));
        assertEquals(1e-6, NiftiUnits.secondsPer(h.timeUnits()));

        h.setUnits(NiftiUnits.MM, NiftiUnits.HZ);
        assertEquals(NiftiUnits.MM, h.spaceUnits());
        assertEquals(NiftiUnits.HZ, h.timeUnits(), "a spectral axis lives in the time field");
        assertTrue(Double.isNaN(NiftiUnits.secondsPer(h.timeUnits())), "hertz is not a duration");
    }

    @Test
    void copyIsIndependent() throws IOException {
        NiftiHeader h = NiftiHeaderCodec.decode(
                goldenV1(ByteOrder.LITTLE_ENDIAN, NiftiVersion.MAGIC_NIFTI1_SINGLE));
        NiftiHeader c = h.copy();
        assertEquals(h, c);
        c.dim[1] = 999;
        c.srowX[0] = 999;
        c.pixdim[3] = 999;
        c.analyzeDataType[0] = 99;
        assertEquals(11, h.dim[1], "the original's arrays are not shared");
        assertEquals(GOLD_SROW_X[0], h.srowX[0]);
        assertEquals(GOLD_PIXDIM[3], h.pixdim[3]);
        assertEquals('d', h.analyzeDataType[0]);
    }

    @Test
    void dataTypeTableAgreesWithItself() {
        for (NiftiDataType t : NiftiDataType.values()) {
            assertSame(t, NiftiDataType.byCode(t.code), t + " is findable by its code");
            if (t == NiftiDataType.UNKNOWN) {
                continue;
            }
            assertEquals(t.bitpix, t.componentBits() * t.components,
                    t + ": bitpix must be the components' bits");
            assertTrue(t.components >= 1 && t.components <= 4, t + " components");
            if (t != NiftiDataType.BINARY) {
                assertTrue(t.byteAligned(), t + " occupies whole bytes");
                assertEquals(t.bitpix / 8, t.bytesPerVoxel(), t + " bytes per voxel");
            }
        }
        assertNull(NiftiDataType.byCode(1234), "an unknown code has no type");

        // the four this module carries but does not decode, and no others
        List<NiftiDataType> undecoded = Arrays.stream(NiftiDataType.values())
                .filter(t -> !t.supported).toList();
        assertEquals(List.of(NiftiDataType.UNKNOWN, NiftiDataType.BINARY,
                NiftiDataType.FLOAT128, NiftiDataType.COMPLEX256), undecoded);
        assertEquals(14, Arrays.stream(NiftiDataType.values()).filter(t -> t.supported).count());
    }
}
