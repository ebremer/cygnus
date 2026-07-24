package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads and writes the header bytes: NIfTI-1's 348, NIfTI-2's 540, and
 * ANALYZE 7.5's 348.
 *
 * <p>Every field is addressed by its absolute offset rather than by advancing
 * a cursor. The layouts are fixed and published, a cursor buys nothing, and a
 * cursor is how one mistaken field width silently shifts every field after it
 * — which in a format with no internal redundancy produces a header that looks
 * plausible and is wrong. The offsets below are the specification's tables
 * transcribed, and the golden-byte tests assert each one.</p>
 *
 * <h2>Detecting what a file is</h2>
 *
 * <p>There is no byte-order mark. What there is instead is {@code sizeof_hdr},
 * a 32-bit integer whose value is known in advance — 348 or 540 — so reading
 * it both ways identifies the version and the order at once. Its byte-swapped
 * forms, {@value NiftiVersion#SWAPPED_348} and
 * {@value NiftiVersion#SWAPPED_540}, are unmistakable.</p>
 *
 * <p>What {@code sizeof_hdr} cannot say is whether a 348-byte header is
 * NIfTI-1 or the ANALYZE 7.5 it was laid over. The magic at offset 344 says
 * that: {@code "n+1\0"} or {@code "ni1\0"} for NIfTI-1, anything else — most
 * often eight zero bytes — for ANALYZE.</p>
 */
public final class NiftiHeaderCodec {

    /** Bytes in a NIfTI-1 or ANALYZE 7.5 header. */
    public static final int NIFTI1_HEADER_SIZE = 348;

    /** Bytes in a NIfTI-2 header. */
    public static final int NIFTI2_HEADER_SIZE = 540;

    // ---- NIfTI-1 / ANALYZE 7.5 field offsets (348 bytes) ----
    private static final int V1_SIZEOF_HDR = 0;
    private static final int V1_DATA_TYPE = 4;
    private static final int V1_DB_NAME = 14;
    private static final int V1_EXTENTS = 32;
    private static final int V1_SESSION_ERROR = 36;
    private static final int V1_REGULAR = 38;
    private static final int V1_DIM_INFO = 39;
    private static final int V1_DIM = 40;
    private static final int V1_INTENT_P1 = 56;
    private static final int V1_INTENT_P2 = 60;
    private static final int V1_INTENT_P3 = 64;
    private static final int V1_INTENT_CODE = 68;
    private static final int V1_DATATYPE = 70;
    private static final int V1_BITPIX = 72;
    private static final int V1_SLICE_START = 74;
    private static final int V1_PIXDIM = 76;
    private static final int V1_VOX_OFFSET = 108;
    private static final int V1_SCL_SLOPE = 112;
    private static final int V1_SCL_INTER = 116;
    private static final int V1_SLICE_END = 120;
    private static final int V1_SLICE_CODE = 122;
    private static final int V1_XYZT_UNITS = 123;
    private static final int V1_CAL_MAX = 124;
    private static final int V1_CAL_MIN = 128;
    private static final int V1_SLICE_DURATION = 132;
    private static final int V1_TOFFSET = 136;
    private static final int V1_GLMAX = 140;
    private static final int V1_GLMIN = 144;
    private static final int V1_DESCRIP = 148;
    private static final int V1_AUX_FILE = 228;
    private static final int V1_QFORM_CODE = 252;
    private static final int V1_SFORM_CODE = 254;
    private static final int V1_QUATERN_B = 256;
    private static final int V1_QUATERN_C = 260;
    private static final int V1_QUATERN_D = 264;
    private static final int V1_QOFFSET_X = 268;
    private static final int V1_QOFFSET_Y = 272;
    private static final int V1_QOFFSET_Z = 276;
    private static final int V1_SROW_X = 280;
    private static final int V1_SROW_Y = 296;
    private static final int V1_SROW_Z = 312;
    private static final int V1_INTENT_NAME = 328;
    private static final int V1_MAGIC = 344;

    // ---- NIfTI-2 field offsets (540 bytes) ----
    private static final int V2_SIZEOF_HDR = 0;
    private static final int V2_MAGIC = 4;
    private static final int V2_DATATYPE = 12;
    private static final int V2_BITPIX = 14;
    private static final int V2_DIM = 16;
    private static final int V2_INTENT_P1 = 80;
    private static final int V2_INTENT_P2 = 88;
    private static final int V2_INTENT_P3 = 96;
    private static final int V2_PIXDIM = 104;
    private static final int V2_VOX_OFFSET = 168;
    private static final int V2_SCL_SLOPE = 176;
    private static final int V2_SCL_INTER = 184;
    private static final int V2_CAL_MAX = 192;
    private static final int V2_CAL_MIN = 200;
    private static final int V2_SLICE_DURATION = 208;
    private static final int V2_TOFFSET = 216;
    private static final int V2_SLICE_START = 224;
    private static final int V2_SLICE_END = 232;
    private static final int V2_DESCRIP = 240;
    private static final int V2_AUX_FILE = 320;
    private static final int V2_QFORM_CODE = 344;
    private static final int V2_SFORM_CODE = 348;
    private static final int V2_QUATERN_B = 352;
    private static final int V2_QUATERN_C = 360;
    private static final int V2_QUATERN_D = 368;
    private static final int V2_QOFFSET_X = 376;
    private static final int V2_QOFFSET_Y = 384;
    private static final int V2_QOFFSET_Z = 392;
    private static final int V2_SROW_X = 400;
    private static final int V2_SROW_Y = 432;
    private static final int V2_SROW_Z = 464;
    private static final int V2_SLICE_CODE = 496;
    private static final int V2_XYZT_UNITS = 500;
    private static final int V2_INTENT_CODE = 504;
    private static final int V2_INTENT_NAME = 508;
    private static final int V2_DIM_INFO = 524;
    private static final int V2_UNUSED_STR = 525;

    private static final int DESCRIP_LEN = 80;
    private static final int AUX_FILE_LEN = 24;
    private static final int INTENT_NAME_LEN = 16;

    private NiftiHeaderCodec() {
    }

    /** What a header's opening bytes say it is. */
    public record Layout(NiftiVersion version, ByteOrder byteOrder, boolean singleFile) {

        /** How many bytes the header occupies. */
        public int headerSize() {
            return version.headerSize;
        }
    }

    /**
     * Identifies the header at the start of {@code head}: which version, which
     * byte order, and whether the voxels follow it or live in a separate
     * {@code .img}.
     *
     * @param head at least {@link #NIFTI1_HEADER_SIZE} bytes, and at least
     *             {@link #NIFTI2_HEADER_SIZE} if it is a NIfTI-2 header
     * @throws IOException if these are not the opening bytes of any layout
     *                     this module reads
     */
    public static Layout detect(byte[] head) throws IOException {
        if (head.length < NIFTI1_HEADER_SIZE) {
            throw new IOException("a NIfTI header needs at least " + NIFTI1_HEADER_SIZE
                    + " bytes, got " + head.length);
        }
        int le = intAt(head, V1_SIZEOF_HDR, ByteOrder.LITTLE_ENDIAN);
        int be = intAt(head, V1_SIZEOF_HDR, ByteOrder.BIG_ENDIAN);

        if (le == NIFTI2_HEADER_SIZE || be == NIFTI2_HEADER_SIZE) {
            ByteOrder order = le == NIFTI2_HEADER_SIZE
                    ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            if (head.length < NIFTI2_HEADER_SIZE) {
                throw new IOException("a NIfTI-2 header needs " + NIFTI2_HEADER_SIZE
                        + " bytes, got " + head.length);
            }
            if (matches(head, V2_MAGIC, NiftiVersion.MAGIC_NIFTI2_SINGLE)) {
                return new Layout(NiftiVersion.NIFTI2, order, true);
            }
            if (matches(head, V2_MAGIC, NiftiVersion.MAGIC_NIFTI2_PAIR)) {
                return new Layout(NiftiVersion.NIFTI2, order, false);
            }
            // No ANALYZE fallback at 540 bytes: nothing else has ever been
            // written at that size, so a bad magic here means a damaged file
            // -- very likely one whose \r\n\032\n sentinel did its job.
            throw new IOException("header says 540 bytes but the magic at offset "
                    + V2_MAGIC + " is " + describe(head, V2_MAGIC, 8)
                    + ", not \"n+2\" or \"ni2\"");
        }

        if (le == NIFTI1_HEADER_SIZE || be == NIFTI1_HEADER_SIZE) {
            ByteOrder order = le == NIFTI1_HEADER_SIZE
                    ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            return v1Layout(head, order);
        }

        // sizeof_hdr is neither size in either order. A NIfTI-1 magic is still
        // conclusive, and NIfTI-1 publishes a fallback for exactly this: dim[0]
        // is 1..7, so whichever order reads it in range is the file's order.
        Boolean single = v1MagicSingle(head);
        if (single != null) {
            short d0le = shortAt(head, V1_DIM, ByteOrder.LITTLE_ENDIAN);
            short d0be = shortAt(head, V1_DIM, ByteOrder.BIG_ENDIAN);
            if (d0le >= 1 && d0le <= 7) {
                return new Layout(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, single);
            }
            if (d0be >= 1 && d0be <= 7) {
                return new Layout(NiftiVersion.NIFTI1, ByteOrder.BIG_ENDIAN, single);
            }
            throw new IOException("NIfTI-1 magic but sizeof_hdr is " + le
                    + " and dim[0] is out of 1..7 in both byte orders");
        }
        throw new IOException("not a NIfTI or ANALYZE header: sizeof_hdr is " + le
                + " (" + be + " byte-swapped), expected " + NIFTI1_HEADER_SIZE
                + " or " + NIFTI2_HEADER_SIZE);
    }

    private static Layout v1Layout(byte[] head, ByteOrder order) {
        Boolean single = v1MagicSingle(head);
        if (single == null) {
            // 348 bytes and no NIfTI magic: ANALYZE 7.5, which is always a pair.
            return new Layout(NiftiVersion.ANALYZE75, order, false);
        }
        return new Layout(NiftiVersion.NIFTI1, order, single);
    }

    /** True/false for a single-file/pair NIfTI-1 magic, null when there is no NIfTI-1 magic. */
    private static Boolean v1MagicSingle(byte[] head) {
        if (matches(head, V1_MAGIC, NiftiVersion.MAGIC_NIFTI1_SINGLE)) {
            return Boolean.TRUE;
        }
        if (matches(head, V1_MAGIC, NiftiVersion.MAGIC_NIFTI1_PAIR)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Detects and decodes in one step. */
    public static NiftiHeader decode(byte[] head) throws IOException {
        return decode(head, detect(head));
    }

    /**
     * Decodes {@code head} as the given layout.
     *
     * <p>Fields are transcribed, not judged: whether the dimensions are
     * possible, whether {@code vox_offset} is inside the file, whether
     * {@code bitpix} agrees with {@code datatype} are all questions for the
     * bounds layer, which can see the file. What is rejected here is only what
     * cannot be represented at all — a {@code vox_offset} that is NaN or
     * infinite, which a NIfTI-1 header can spell because the field is a
     * {@code float}.</p>
     */
    public static NiftiHeader decode(byte[] head, Layout layout) throws IOException {
        int need = layout.headerSize();
        if (head.length < need) {
            throw new IOException(layout.version() + " needs " + need
                    + " header bytes, got " + head.length);
        }
        ByteBuffer b = ByteBuffer.wrap(head).order(layout.byteOrder());
        NiftiHeader h = new NiftiHeader();
        h.version = layout.version();
        h.byteOrder = layout.byteOrder();
        h.singleFile = layout.singleFile();
        if (layout.version() == NiftiVersion.NIFTI2) {
            decodeV2(b, h);
        } else {
            decodeV1(b, h, layout.version() == NiftiVersion.ANALYZE75);
        }
        return h;
    }

    private static void decodeV1(ByteBuffer b, NiftiHeader h, boolean analyze) throws IOException {
        // Shared with ANALYZE 7.5, byte for byte.
        h.analyzeDataType = bytes(b, V1_DATA_TYPE, 10);
        h.dbName = bytes(b, V1_DB_NAME, 18);
        h.extents = b.getInt(V1_EXTENTS);
        h.sessionError = b.getShort(V1_SESSION_ERROR);
        h.regular = b.get(V1_REGULAR);
        for (int i = 0; i < 8; i++) {
            h.dim[i] = b.getShort(V1_DIM + 2 * i);
        }
        h.datatype = b.getShort(V1_DATATYPE);
        h.bitpix = b.getShort(V1_BITPIX);
        for (int i = 0; i < 8; i++) {
            h.pixdim[i] = b.getFloat(V1_PIXDIM + 4 * i);
        }
        h.voxOffset = offsetFromFloat(b.getFloat(V1_VOX_OFFSET));
        h.calMax = b.getFloat(V1_CAL_MAX);
        h.calMin = b.getFloat(V1_CAL_MIN);
        h.glmax = b.getInt(V1_GLMAX);
        h.glmin = b.getInt(V1_GLMIN);
        h.descrip = string(b, V1_DESCRIP, DESCRIP_LEN);
        h.auxFile = string(b, V1_AUX_FILE, AUX_FILE_LEN);

        if (analyze) {
            // Everything else at these offsets is a different field in ANALYZE:
            // vox_units/cal_units where intent_p1..p3 are, funused1..3 where
            // scl_slope/scl_inter/slice_end are, and the patient-history struct
            // where qform and sform are. Decoding them as NIfTI is how a reader
            // invents geometry, so they are kept raw and left alone.
            h.analyzeRaw = bytes(b, 0, NIFTI1_HEADER_SIZE);
            return;
        }

        h.dimInfo = b.get(V1_DIM_INFO) & 0xFF;
        h.intentP1 = b.getFloat(V1_INTENT_P1);
        h.intentP2 = b.getFloat(V1_INTENT_P2);
        h.intentP3 = b.getFloat(V1_INTENT_P3);
        h.intentCode = b.getShort(V1_INTENT_CODE);
        h.sliceStart = b.getShort(V1_SLICE_START);
        h.sclSlope = b.getFloat(V1_SCL_SLOPE);
        h.sclInter = b.getFloat(V1_SCL_INTER);
        h.sliceEnd = b.getShort(V1_SLICE_END);
        h.sliceCode = b.get(V1_SLICE_CODE) & 0xFF;
        h.xyztUnits = b.get(V1_XYZT_UNITS) & 0xFF;
        h.sliceDuration = b.getFloat(V1_SLICE_DURATION);
        h.toffset = b.getFloat(V1_TOFFSET);
        h.qformCode = b.getShort(V1_QFORM_CODE);
        h.sformCode = b.getShort(V1_SFORM_CODE);
        h.quaternB = b.getFloat(V1_QUATERN_B);
        h.quaternC = b.getFloat(V1_QUATERN_C);
        h.quaternD = b.getFloat(V1_QUATERN_D);
        h.qoffsetX = b.getFloat(V1_QOFFSET_X);
        h.qoffsetY = b.getFloat(V1_QOFFSET_Y);
        h.qoffsetZ = b.getFloat(V1_QOFFSET_Z);
        for (int i = 0; i < 4; i++) {
            h.srowX[i] = b.getFloat(V1_SROW_X + 4 * i);
            h.srowY[i] = b.getFloat(V1_SROW_Y + 4 * i);
            h.srowZ[i] = b.getFloat(V1_SROW_Z + 4 * i);
        }
        h.intentName = string(b, V1_INTENT_NAME, INTENT_NAME_LEN);
    }

    private static void decodeV2(ByteBuffer b, NiftiHeader h) {
        h.datatype = b.getShort(V2_DATATYPE);
        h.bitpix = b.getShort(V2_BITPIX);
        for (int i = 0; i < 8; i++) {
            h.dim[i] = b.getLong(V2_DIM + 8 * i);
        }
        h.intentP1 = b.getDouble(V2_INTENT_P1);
        h.intentP2 = b.getDouble(V2_INTENT_P2);
        h.intentP3 = b.getDouble(V2_INTENT_P3);
        for (int i = 0; i < 8; i++) {
            h.pixdim[i] = b.getDouble(V2_PIXDIM + 8 * i);
        }
        h.voxOffset = b.getLong(V2_VOX_OFFSET);
        h.sclSlope = b.getDouble(V2_SCL_SLOPE);
        h.sclInter = b.getDouble(V2_SCL_INTER);
        h.calMax = b.getDouble(V2_CAL_MAX);
        h.calMin = b.getDouble(V2_CAL_MIN);
        h.sliceDuration = b.getDouble(V2_SLICE_DURATION);
        h.toffset = b.getDouble(V2_TOFFSET);
        h.sliceStart = b.getLong(V2_SLICE_START);
        h.sliceEnd = b.getLong(V2_SLICE_END);
        h.descrip = string(b, V2_DESCRIP, DESCRIP_LEN);
        h.auxFile = string(b, V2_AUX_FILE, AUX_FILE_LEN);
        h.qformCode = b.getInt(V2_QFORM_CODE);
        h.sformCode = b.getInt(V2_SFORM_CODE);
        h.quaternB = b.getDouble(V2_QUATERN_B);
        h.quaternC = b.getDouble(V2_QUATERN_C);
        h.quaternD = b.getDouble(V2_QUATERN_D);
        h.qoffsetX = b.getDouble(V2_QOFFSET_X);
        h.qoffsetY = b.getDouble(V2_QOFFSET_Y);
        h.qoffsetZ = b.getDouble(V2_QOFFSET_Z);
        for (int i = 0; i < 4; i++) {
            h.srowX[i] = b.getDouble(V2_SROW_X + 8 * i);
            h.srowY[i] = b.getDouble(V2_SROW_Y + 8 * i);
            h.srowZ[i] = b.getDouble(V2_SROW_Z + 8 * i);
        }
        h.sliceCode = b.getInt(V2_SLICE_CODE);
        h.xyztUnits = b.getInt(V2_XYZT_UNITS);
        h.intentCode = b.getInt(V2_INTENT_CODE);
        h.intentName = string(b, V2_INTENT_NAME, INTENT_NAME_LEN);
        h.dimInfo = b.get(V2_DIM_INFO) & 0xFF;
        h.unusedStr = bytes(b, V2_UNUSED_STR, 15);
    }

    /**
     * Encodes {@code h} as {@link NiftiHeader#version} says, into a fresh
     * array of exactly {@link NiftiVersion#headerSize} bytes.
     *
     * <p>{@code sizeof_hdr} and the magic are written from the version and
     * {@link NiftiHeader#singleFile}, never from a stored copy, so they cannot
     * disagree with the layout actually being emitted.</p>
     *
     * <p>Writing NIfTI-1 narrows every field back to its original width, and a
     * value that does not fit is an {@link IOException} naming the field — a
     * truncated {@code dim} or a rounded {@code vox_offset} would produce a
     * file that reads without complaint and is wrong.</p>
     */
    public static byte[] encode(NiftiHeader h) throws IOException {
        if (!h.version.writable()) {
            throw new IOException(h.version + " is read-only; write NIfTI-1 or NIfTI-2 instead");
        }
        byte[] out = new byte[h.version.headerSize];
        ByteBuffer b = ByteBuffer.wrap(out).order(h.byteOrder);
        if (h.version == NiftiVersion.NIFTI2) {
            encodeV2(b, h);
        } else {
            encodeV1(b, h);
        }
        return out;
    }

    private static void encodeV1(ByteBuffer b, NiftiHeader h) throws IOException {
        b.putInt(V1_SIZEOF_HDR, NIFTI1_HEADER_SIZE);
        putBytes(b, V1_DATA_TYPE, 10, h.analyzeDataType, "data_type");
        putBytes(b, V1_DB_NAME, 18, h.dbName, "db_name");
        b.putInt(V1_EXTENTS, h.extents);
        b.putShort(V1_SESSION_ERROR, h.sessionError);
        b.put(V1_REGULAR, h.regular);
        b.put(V1_DIM_INFO, toByte(h.dimInfo, "dim_info"));
        for (int i = 0; i < 8; i++) {
            b.putShort(V1_DIM + 2 * i, toShort(h.dim[i], "dim[" + i + "]"));
        }
        b.putFloat(V1_INTENT_P1, toFloat(h.intentP1, "intent_p1"));
        b.putFloat(V1_INTENT_P2, toFloat(h.intentP2, "intent_p2"));
        b.putFloat(V1_INTENT_P3, toFloat(h.intentP3, "intent_p3"));
        b.putShort(V1_INTENT_CODE, toShort(h.intentCode, "intent_code"));
        b.putShort(V1_DATATYPE, toShort(h.datatype, "datatype"));
        b.putShort(V1_BITPIX, toShort(h.bitpix, "bitpix"));
        b.putShort(V1_SLICE_START, toShort(h.sliceStart, "slice_start"));
        for (int i = 0; i < 8; i++) {
            b.putFloat(V1_PIXDIM + 4 * i, toFloat(h.pixdim[i], "pixdim[" + i + "]"));
        }
        b.putFloat(V1_VOX_OFFSET, exactFloat(h.voxOffset, "vox_offset"));
        b.putFloat(V1_SCL_SLOPE, toFloat(h.sclSlope, "scl_slope"));
        b.putFloat(V1_SCL_INTER, toFloat(h.sclInter, "scl_inter"));
        b.putShort(V1_SLICE_END, toShort(h.sliceEnd, "slice_end"));
        b.put(V1_SLICE_CODE, toByte(h.sliceCode, "slice_code"));
        b.put(V1_XYZT_UNITS, toByte(h.xyztUnits, "xyzt_units"));
        b.putFloat(V1_CAL_MAX, toFloat(h.calMax, "cal_max"));
        b.putFloat(V1_CAL_MIN, toFloat(h.calMin, "cal_min"));
        b.putFloat(V1_SLICE_DURATION, toFloat(h.sliceDuration, "slice_duration"));
        b.putFloat(V1_TOFFSET, toFloat(h.toffset, "toffset"));
        b.putInt(V1_GLMAX, h.glmax);
        b.putInt(V1_GLMIN, h.glmin);
        putString(b, V1_DESCRIP, DESCRIP_LEN, h.descrip, "descrip");
        putString(b, V1_AUX_FILE, AUX_FILE_LEN, h.auxFile, "aux_file");
        b.putShort(V1_QFORM_CODE, toShort(h.qformCode, "qform_code"));
        b.putShort(V1_SFORM_CODE, toShort(h.sformCode, "sform_code"));
        b.putFloat(V1_QUATERN_B, toFloat(h.quaternB, "quatern_b"));
        b.putFloat(V1_QUATERN_C, toFloat(h.quaternC, "quatern_c"));
        b.putFloat(V1_QUATERN_D, toFloat(h.quaternD, "quatern_d"));
        b.putFloat(V1_QOFFSET_X, toFloat(h.qoffsetX, "qoffset_x"));
        b.putFloat(V1_QOFFSET_Y, toFloat(h.qoffsetY, "qoffset_y"));
        b.putFloat(V1_QOFFSET_Z, toFloat(h.qoffsetZ, "qoffset_z"));
        for (int i = 0; i < 4; i++) {
            b.putFloat(V1_SROW_X + 4 * i, toFloat(h.srowX[i], "srow_x[" + i + "]"));
            b.putFloat(V1_SROW_Y + 4 * i, toFloat(h.srowY[i], "srow_y[" + i + "]"));
            b.putFloat(V1_SROW_Z + 4 * i, toFloat(h.srowZ[i], "srow_z[" + i + "]"));
        }
        putString(b, V1_INTENT_NAME, INTENT_NAME_LEN, h.intentName, "intent_name");
        b.put(V1_MAGIC, h.singleFile
                ? NiftiVersion.MAGIC_NIFTI1_SINGLE : NiftiVersion.MAGIC_NIFTI1_PAIR);
    }

    private static void encodeV2(ByteBuffer b, NiftiHeader h) throws IOException {
        b.putInt(V2_SIZEOF_HDR, NIFTI2_HEADER_SIZE);
        b.put(V2_MAGIC, h.singleFile
                ? NiftiVersion.MAGIC_NIFTI2_SINGLE : NiftiVersion.MAGIC_NIFTI2_PAIR);
        b.putShort(V2_DATATYPE, toShort(h.datatype, "datatype"));
        b.putShort(V2_BITPIX, toShort(h.bitpix, "bitpix"));
        for (int i = 0; i < 8; i++) {
            b.putLong(V2_DIM + 8 * i, h.dim[i]);
        }
        b.putDouble(V2_INTENT_P1, h.intentP1);
        b.putDouble(V2_INTENT_P2, h.intentP2);
        b.putDouble(V2_INTENT_P3, h.intentP3);
        for (int i = 0; i < 8; i++) {
            b.putDouble(V2_PIXDIM + 8 * i, h.pixdim[i]);
        }
        b.putLong(V2_VOX_OFFSET, h.voxOffset);
        b.putDouble(V2_SCL_SLOPE, h.sclSlope);
        b.putDouble(V2_SCL_INTER, h.sclInter);
        b.putDouble(V2_CAL_MAX, h.calMax);
        b.putDouble(V2_CAL_MIN, h.calMin);
        b.putDouble(V2_SLICE_DURATION, h.sliceDuration);
        b.putDouble(V2_TOFFSET, h.toffset);
        b.putLong(V2_SLICE_START, h.sliceStart);
        b.putLong(V2_SLICE_END, h.sliceEnd);
        putString(b, V2_DESCRIP, DESCRIP_LEN, h.descrip, "descrip");
        putString(b, V2_AUX_FILE, AUX_FILE_LEN, h.auxFile, "aux_file");
        b.putInt(V2_QFORM_CODE, h.qformCode);
        b.putInt(V2_SFORM_CODE, h.sformCode);
        b.putDouble(V2_QUATERN_B, h.quaternB);
        b.putDouble(V2_QUATERN_C, h.quaternC);
        b.putDouble(V2_QUATERN_D, h.quaternD);
        b.putDouble(V2_QOFFSET_X, h.qoffsetX);
        b.putDouble(V2_QOFFSET_Y, h.qoffsetY);
        b.putDouble(V2_QOFFSET_Z, h.qoffsetZ);
        for (int i = 0; i < 4; i++) {
            b.putDouble(V2_SROW_X + 8 * i, h.srowX[i]);
            b.putDouble(V2_SROW_Y + 8 * i, h.srowY[i]);
            b.putDouble(V2_SROW_Z + 8 * i, h.srowZ[i]);
        }
        b.putInt(V2_SLICE_CODE, h.sliceCode);
        b.putInt(V2_XYZT_UNITS, h.xyztUnits);
        b.putInt(V2_INTENT_CODE, h.intentCode);
        putString(b, V2_INTENT_NAME, INTENT_NAME_LEN, h.intentName, "intent_name");
        b.put(V2_DIM_INFO, toByte(h.dimInfo, "dim_info"));
        putBytes(b, V2_UNUSED_STR, 15, h.unusedStr, "unused_str");
        // data_type, db_name, extents, session_error, regular, glmax and glmin
        // have no NIfTI-2 field. They have been unused since ANALYZE, so they
        // are dropped rather than being a reason to refuse the write.
    }

    // ---- narrowing to NIfTI-1's widths, with the failures named ----

    private static short toShort(long v, String field) throws IOException {
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw new IOException(field + " is " + v
                    + ", which a NIfTI-1 header cannot hold (it is a 16-bit field);"
                    + " write NIfTI-2 instead");
        }
        return (short) v;
    }

    private static byte toByte(int v, String field) throws IOException {
        if (v < 0 || v > 0xFF) {
            throw new IOException(field + " is " + v
                    + ", which a NIfTI-1 header cannot hold (it is an 8-bit field)");
        }
        return (byte) v;
    }

    /**
     * A {@code double} narrowed to the {@code float} a NIfTI-1 header spells
     * it in. Precision is lost by design — the field is a {@code float} — but
     * a finite value that becomes infinite has overflowed, and that is a
     * different thing from rounding.
     */
    private static float toFloat(double v, String field) throws IOException {
        float f = (float) v;
        if (Double.isFinite(v) && Float.isInfinite(f)) {
            throw new IOException(field + " is " + v
                    + ", which overflows the 32-bit float a NIfTI-1 header spells it in;"
                    + " write NIfTI-2 instead");
        }
        return f;
    }

    /**
     * An offset narrowed to NIfTI-1's {@code float} {@code vox_offset}, which
     * must survive the trip exactly: past 2^24 a float can no longer count
     * single bytes, and an offset off by one reads the voxel array shifted.
     */
    private static float exactFloat(long v, String field) throws IOException {
        float f = (float) v;
        if ((long) f != v) {
            throw new IOException(field + " is " + v
                    + ", which a NIfTI-1 header cannot express exactly (it is a 32-bit"
                    + " float, exact only to 2^24); write NIfTI-2 instead");
        }
        return f;
    }

    /**
     * NIfTI-1's {@code vox_offset} read back from its {@code float}. A
     * fractional value is truncated, as the reference implementation does; a
     * value that is not finite has no offset in it at all.
     */
    private static long offsetFromFloat(float f) throws IOException {
        if (!Float.isFinite(f)) {
            throw new IOException("vox_offset is " + f + ", which is not a file offset");
        }
        return (long) f;
    }

    // ---- fixed-width fields ----

    /**
     * A NUL-padded fixed-width field as a string.
     *
     * <p>ISO-8859-1 because it maps every byte to exactly one character and
     * back, so a round-trip through {@code String} cannot corrupt a field
     * holding bytes some tool wrote that are not text. Only trailing NULs are
     * stripped — the padding — so a field that runs the full width keeps its
     * last character, and one holding junk after its terminator (which
     * uninitialized-memory writers leave) keeps that too and writes it back
     * unchanged.</p>
     */
    private static String string(ByteBuffer b, int off, int len) {
        byte[] raw = new byte[len];
        b.get(off, raw);
        int end = len;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.ISO_8859_1);
    }

    private static void putString(ByteBuffer b, int off, int len, String s, String field)
            throws IOException {
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        if (raw.length > len) {
            throw new IOException(field + " is " + raw.length
                    + " bytes; the field holds " + len);
        }
        b.put(off, raw, 0, raw.length);
    }

    private static byte[] bytes(ByteBuffer b, int off, int len) {
        byte[] raw = new byte[len];
        b.get(off, raw);
        return raw;
    }

    private static void putBytes(ByteBuffer b, int off, int len, byte[] v, String field)
            throws IOException {
        if (v.length > len) {
            throw new IOException(field + " is " + v.length + " bytes; the field holds " + len);
        }
        b.put(off, v, 0, v.length);
    }

    // ---- reading before the byte order is known ----

    private static int intAt(byte[] a, int off, ByteOrder order) {
        return ByteBuffer.wrap(a, off, 4).order(order).getInt();
    }

    private static short shortAt(byte[] a, int off, ByteOrder order) {
        return ByteBuffer.wrap(a, off, 2).order(order).getShort();
    }

    private static boolean matches(byte[] a, int off, byte[] want) {
        if (a.length < off + want.length) {
            return false;
        }
        return Arrays.equals(a, off, off + want.length, want, 0, want.length);
    }

    private static String describe(byte[] a, int off, int len) {
        StringBuilder s = new StringBuilder(len + 2).append('"');
        for (int i = off; i < off + len && i < a.length; i++) {
            int c = a[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                s.append((char) c);
            } else {
                s.append(String.format("\\x%02x", c));
            }
        }
        return s.append('"').toString();
    }
}
