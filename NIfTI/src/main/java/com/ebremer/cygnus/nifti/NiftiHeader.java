package com.ebremer.cygnus.nifti;

import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A NIfTI header, held at NIfTI-2's widths whatever version it came from.
 *
 * <p>NIfTI-2 adds no fields to NIfTI-1 — it widens them. So one class covers
 * both: {@code dim} is {@code long[8]} whether the file spelled it
 * {@code int16} or {@code int64}, {@code pixdim} is {@code double[8]} whether
 * the file spelled it {@code float} or {@code double}, and the codes are
 * {@code int}. Reading a NIfTI-1 file widens; writing one narrows, and
 * {@link NiftiHeaderCodec} refuses a value that will not fit rather than
 * truncating it. {@link #version} says which layout the header came from or
 * should be written as; it is a property here, not a type.</p>
 *
 * <p>The fields are public and mutable, because that is what a header is: a
 * transcription of a fixed byte layout that callers legitimately edit before
 * writing. Nothing is validated on assignment. Validation happens where it can
 * be complete — in the codec on encode, and in {@code nifti.io.Bounds} against
 * the file's actual length on decode.</p>
 *
 * <p>Four things are <em>not</em> fields, because they are derived and a stored
 * copy could disagree with the truth: {@code sizeof_hdr} (it is
 * {@link NiftiVersion#headerSize}), the magic (it follows from
 * {@link #version} and {@link #singleFile}), {@link #bitpix} — which is a
 * field only because a file may state it, and which is checked against
 * {@link #datatype} rather than trusted — and {@code vox_offset} in a written
 * single file, which the writer computes from the extensions.</p>
 */
public final class NiftiHeader {

    // ---- what layout this is, which is not itself a header field ----

    /** Which of the three layouts: read from the file, or chosen for writing. */
    public NiftiVersion version = NiftiVersion.NIFTI1;

    /** The byte order every field and every voxel is in. */
    public ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    /** True for {@code n+1}/{@code n+2} (voxels follow the header), false for a {@code .hdr}/{@code .img} pair. */
    public boolean singleFile = true;

    // ---- ANALYZE 7.5 leftovers: present in a v1 header, absent from v2 ----

    /**
     * {@code data_type[10]} — unused since ANALYZE. Kept so a v1 round-trip is
     * byte-exact. Named for what it is rather than for the C field, because
     * {@code data_type} and {@code datatype} are different fields in the C
     * header and collapse to the same name in Java's spelling.
     */
    public byte[] analyzeDataType = new byte[10];

    /** {@code db_name[18]} — unused since ANALYZE. Kept so a v1 round-trip is byte-exact. */
    public byte[] dbName = new byte[18];

    /** {@code extents} — unused; ANALYZE wrote 16384. */
    public int extents;

    /** {@code session_error} — unused. */
    public short sessionError;

    /** {@code regular} — unused; every writer emits {@code 'r'}. */
    public byte regular = 'r';

    /** {@code glmax} — unused in NIfTI, meaningful in ANALYZE. Absent from v2. */
    public int glmax;

    /** {@code glmin} — unused in NIfTI, meaningful in ANALYZE. Absent from v2. */
    public int glmin;

    // ---- the fields both versions carry ----

    /** {@code dim_info}: frequency, phase and slice axes packed two bits each. See {@link #freqDim()}. */
    public int dimInfo;

    /**
     * {@code dim[0..7]}. {@code dim[0]} is how many dimensions are in use, 1
     * to 7; {@code dim[1..3]} are space, {@code dim[4]} is time, and
     * {@code dim[5..7]} are free. Entries past {@code dim[0]} are ignored.
     */
    public long[] dim = new long[8];

    /** {@code intent_p1} — first parameter of {@link #intentCode}. */
    public double intentP1;

    /** {@code intent_p2} — second parameter of {@link #intentCode}. */
    public double intentP2;

    /** {@code intent_p3} — third parameter of {@link #intentCode}. */
    public double intentP3;

    /** {@code intent_code} — what the values mean; see {@link NiftiIntent}. */
    public int intentCode;

    /** {@code datatype} — the voxel type; see {@link NiftiDataType}. */
    public int datatype;

    /** {@code bitpix} — bits per voxel. Must agree with {@link #datatype}. */
    public int bitpix;

    /** {@code slice_start} — index of the first slice acquired. */
    public long sliceStart;

    /**
     * {@code pixdim[0..7]}. {@code pixdim[i]} is the voxel width along
     * dimension {@code i}; {@code pixdim[0]} is not a width but
     * {@code qfac}, the handedness flag of the {@code qform} — see
     * {@link #qfac()}.
     */
    public double[] pixdim = {1, 1, 1, 1, 1, 1, 1, 1};

    /**
     * {@code vox_offset} — where the voxels begin: a file offset in a single
     * file (at least {@link NiftiVersion#minVoxOffset}), an offset into the
     * {@code .img} in a pair (normally 0).
     */
    public long voxOffset;

    /** {@code scl_slope} — see {@link #scaled(double)}. Zero means no scaling. */
    public double sclSlope;

    /** {@code scl_inter} — see {@link #scaled(double)}. */
    public double sclInter;

    /** {@code slice_end} — index of the last slice acquired. */
    public long sliceEnd;

    /** {@code slice_code} — slice timing order; see {@link NiftiSlice}. */
    public int sliceCode;

    /** {@code xyzt_units}: spatial units in bits 0-2, temporal in bits 3-5. See {@link #spaceUnits()}. */
    public int xyztUnits;

    /** {@code cal_max} — the value a display should render as white. */
    public double calMax;

    /** {@code cal_min} — the value a display should render as black. */
    public double calMin;

    /** {@code slice_duration} — time to acquire one slice. */
    public double sliceDuration;

    /** {@code toffset} — shift of the time axis. */
    public double toffset;

    /** {@code descrip[80]} — free text. */
    public String descrip = "";

    /** {@code aux_file[24]} — the name of a companion file. */
    public String auxFile = "";

    /** {@code qform_code} — see {@link NiftiXform}. Zero means no qform. */
    public int qformCode;

    /** {@code sform_code} — see {@link NiftiXform}. Zero means no sform. */
    public int sformCode;

    /** {@code quatern_b} — the i component of the qform's unit quaternion. */
    public double quaternB;

    /** {@code quatern_c} — the j component of the qform's unit quaternion. */
    public double quaternC;

    /** {@code quatern_d} — the k component of the qform's unit quaternion. */
    public double quaternD;

    /** {@code qoffset_x} — the qform's translation along x. */
    public double qoffsetX;

    /** {@code qoffset_y} — the qform's translation along y. */
    public double qoffsetY;

    /** {@code qoffset_z} — the qform's translation along z. */
    public double qoffsetZ;

    /** {@code srow_x[4]} — the first row of the sform affine. */
    public double[] srowX = new double[4];

    /** {@code srow_y[4]} — the second row of the sform affine. */
    public double[] srowY = new double[4];

    /** {@code srow_z[4]} — the third row of the sform affine. */
    public double[] srowZ = new double[4];

    /** {@code intent_name[16]} — a name for what the values are. */
    public String intentName = "";

    /** {@code unused_str[15]} — v2 only; kept so a v2 round-trip is byte-exact. */
    public byte[] unusedStr = new byte[15];

    /**
     * The 348 bytes of an ANALYZE 7.5 header exactly as they were read, or
     * null for a NIfTI header.
     *
     * <p>ANALYZE and NIfTI-1 agree on {@code dim}, {@code datatype},
     * {@code bitpix}, {@code pixdim}, {@code vox_offset}, {@code cal_min}/
     * {@code cal_max}, {@code glmin}/{@code glmax}, {@code descrip} and
     * {@code aux_file}, and on nothing else: where NIfTI-1 has
     * {@code intent_p1..3} ANALYZE has {@code vox_units}/{@code cal_units},
     * where NIfTI-1 has {@code scl_slope} ANALYZE has {@code funused1}, and
     * the whole {@code qform}/{@code sform} block is ANALYZE's patient-history
     * struct. Only the shared fields are decoded; the rest is kept here rather
     * than interpreted, because interpreting it is how a reader invents
     * geometry that was never in the file.</p>
     */
    public byte[] analyzeRaw;

    /** A header with the defaults: NIfTI-1, little-endian, single file, unit pixdim. */
    public NiftiHeader() {
    }

    /**
     * A minimal valid header: {@code dims.length} dimensions of the given
     * extents, the given datatype with its natural {@code bitpix}, unit voxel
     * spacing, and no geometry.
     */
    public static NiftiHeader of(NiftiVersion version, NiftiDataType type, long... dims) {
        if (!version.writable()) {
            throw new IllegalArgumentException(version + " headers are read-only");
        }
        if (dims.length < 1 || dims.length > 7) {
            throw new IllegalArgumentException("a NIfTI image has 1 to 7 dimensions, not " + dims.length);
        }
        NiftiHeader h = new NiftiHeader();
        h.version = version;
        h.datatype = type.code;
        h.bitpix = type.bitpix;
        h.dim[0] = dims.length;
        for (int i = 0; i < dims.length; i++) {
            h.dim[i + 1] = dims[i];
        }
        for (int i = dims.length + 1; i < 8; i++) {
            h.dim[i] = 1;
        }
        return h;
    }

    /** How many dimensions are in use: {@code dim[0]}, which a valid file holds to 1..7. */
    public int numDimensions() {
        return (int) dim[0];
    }

    /**
     * The extent of dimension {@code i} (1-based) as the voxel array actually
     * uses it: {@code dim[i]} for {@code i} within {@code dim[0]}, and 1 both
     * beyond {@code dim[0]} and where the file wrote a zero.
     *
     * <p>The zero case is not hypothetical: a 3-D image whose {@code dim[0]}
     * says 4 and whose {@code dim[4]} says 0 is common enough that the
     * reference implementation coerces it, and so does this. {@link #dim}
     * still holds what the file said.</p>
     */
    public long effectiveDim(int i) {
        if (i < 1 || i > 7) {
            throw new IndexOutOfBoundsException("dimension " + i + " is not in 1..7");
        }
        if (i > dim[0]) {
            return 1;
        }
        return dim[i] <= 0 ? 1 : dim[i];
    }

    /** Voxels along x. */
    public long nx() {
        return effectiveDim(1);
    }

    /** Voxels along y. */
    public long ny() {
        return effectiveDim(2);
    }

    /** Voxels along z. */
    public long nz() {
        return effectiveDim(3);
    }

    /** Time points. */
    public long nt() {
        return effectiveDim(4);
    }

    /**
     * The number of voxels: the product of {@link #effectiveDim} over
     * {@code 1..dim[0]}, saturating at {@link Long#MAX_VALUE} rather than
     * wrapping. A header claiming more than can exist is rejected by the
     * bounds layer, not here.
     */
    public long voxelCount() {
        long n = 1;
        int nd = numDimensions();
        for (int i = 1; i <= nd && i <= 7; i++) {
            try {
                n = Math.multiplyExact(n, effectiveDim(i));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return n;
    }

    /**
     * The number of 2-D {@code dim[1]}x{@code dim[2]} slices in the image:
     * the product of {@link #effectiveDim} over {@code 3..dim[0]}, which is
     * how many ImageIO image indices the plug-in exposes.
     */
    public long sliceCount() {
        long n = 1;
        int nd = numDimensions();
        for (int i = 3; i <= nd && i <= 7; i++) {
            try {
                n = Math.multiplyExact(n, effectiveDim(i));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return n;
    }

    /** The voxel type, or null if {@link #datatype} is not one this module knows. */
    public NiftiDataType voxelType() {
        return NiftiDataType.byCode(datatype);
    }

    /**
     * {@code qfac}: the handedness flag the qform's k axis is multiplied by,
     * stored in {@code pixdim[0]} as -1 or 1. A zero — which is what a writer
     * that never set it leaves — means 1.
     */
    public double qfac() {
        return pixdim[0] < 0 ? -1 : 1;
    }

    /** The true value of a stored sample: {@code scl_slope * v + scl_inter}, or {@code v} when no scaling applies. */
    public double scaled(double v) {
        if (!scalingApplies()) {
            return v;
        }
        return sclSlope * v + sclInter;
    }

    /**
     * Whether {@link #sclSlope}/{@link #sclInter} apply at all: they do not
     * when the slope is zero (the documented "no scaling" spelling) or not
     * finite, and never to RGB24/RGBA32, whose samples are colour components
     * rather than measurements.
     */
    public boolean scalingApplies() {
        if (sclSlope == 0 || !Double.isFinite(sclSlope) || !Double.isFinite(sclInter)) {
            return false;
        }
        NiftiDataType t = voxelType();
        return t == null || t.scalable;
    }

    /** The frequency-encoding axis from {@link #dimInfo}: 0 for unset, else 1..3. */
    public int freqDim() {
        return dimInfo & 0x03;
    }

    /** The phase-encoding axis from {@link #dimInfo}: 0 for unset, else 1..3. */
    public int phaseDim() {
        return (dimInfo >> 2) & 0x03;
    }

    /** The slice axis from {@link #dimInfo}: 0 for unset, else 1..3. */
    public int sliceDim() {
        return (dimInfo >> 4) & 0x03;
    }

    /** Packs the three axes into {@link #dimInfo}; each is 0 for unset or 1..3. */
    public void setDimInfo(int freq, int phase, int slice) {
        dimInfo = (freq & 0x03) | ((phase & 0x03) << 2) | ((slice & 0x03) << 4);
    }

    /** The spatial unit of {@code pixdim[1..3]}: bits 0-2 of {@link #xyztUnits}. See {@link NiftiUnits}. */
    public int spaceUnits() {
        return xyztUnits & 0x07;
    }

    /** The temporal unit of {@code pixdim[4]}: bits 3-5 of {@link #xyztUnits}. See {@link NiftiUnits}. */
    public int timeUnits() {
        return xyztUnits & 0x38;
    }

    /** Packs a spatial and a temporal unit into {@link #xyztUnits}. */
    public void setUnits(int space, int time) {
        xyztUnits = (space & 0x07) | (time & 0x38);
    }

    /** An independent copy, arrays included. */
    public NiftiHeader copy() {
        NiftiHeader h = new NiftiHeader();
        h.version = version;
        h.byteOrder = byteOrder;
        h.singleFile = singleFile;
        h.analyzeDataType = analyzeDataType.clone();
        h.dbName = dbName.clone();
        h.extents = extents;
        h.sessionError = sessionError;
        h.regular = regular;
        h.glmax = glmax;
        h.glmin = glmin;
        h.dimInfo = dimInfo;
        h.dim = dim.clone();
        h.intentP1 = intentP1;
        h.intentP2 = intentP2;
        h.intentP3 = intentP3;
        h.intentCode = intentCode;
        h.datatype = datatype;
        h.bitpix = bitpix;
        h.sliceStart = sliceStart;
        h.pixdim = pixdim.clone();
        h.voxOffset = voxOffset;
        h.sclSlope = sclSlope;
        h.sclInter = sclInter;
        h.sliceEnd = sliceEnd;
        h.sliceCode = sliceCode;
        h.xyztUnits = xyztUnits;
        h.calMax = calMax;
        h.calMin = calMin;
        h.sliceDuration = sliceDuration;
        h.toffset = toffset;
        h.descrip = descrip;
        h.auxFile = auxFile;
        h.qformCode = qformCode;
        h.sformCode = sformCode;
        h.quaternB = quaternB;
        h.quaternC = quaternC;
        h.quaternD = quaternD;
        h.qoffsetX = qoffsetX;
        h.qoffsetY = qoffsetY;
        h.qoffsetZ = qoffsetZ;
        h.srowX = srowX.clone();
        h.srowY = srowY.clone();
        h.srowZ = srowZ.clone();
        h.intentName = intentName;
        h.unusedStr = unusedStr.clone();
        h.analyzeRaw = analyzeRaw == null ? null : analyzeRaw.clone();
        return h;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(160);
        b.append(version).append(' ')
                .append(byteOrder == ByteOrder.LITTLE_ENDIAN ? "LE" : "BE")
                .append(singleFile ? " single" : " pair").append(' ');
        NiftiDataType t = voxelType();
        b.append(t == null ? "datatype " + datatype : t.name()).append(' ');
        int nd = numDimensions();
        for (int i = 1; i <= nd && i <= 7; i++) {
            b.append(i == 1 ? "" : "x").append(effectiveDim(i));
        }
        b.append(" vox_offset=").append(voxOffset);
        if (scalingApplies()) {
            b.append(" scl=").append(sclSlope).append('/').append(sclInter);
        }
        if (qformCode != 0) {
            b.append(" qform=").append(qformCode);
        }
        if (sformCode != 0) {
            b.append(" sform=").append(sformCode);
        }
        if (!descrip.isEmpty()) {
            b.append(" \"").append(descrip).append('"');
        }
        return b.toString();
    }

    /** Field-by-field equality, arrays compared by content. Used by the round-trip tests. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NiftiHeader h)) {
            return false;
        }
        return version == h.version
                && byteOrder.equals(h.byteOrder)
                && singleFile == h.singleFile
                && Arrays.equals(analyzeDataType, h.analyzeDataType)
                && Arrays.equals(dbName, h.dbName)
                && extents == h.extents
                && sessionError == h.sessionError
                && regular == h.regular
                && glmax == h.glmax
                && glmin == h.glmin
                && dimInfo == h.dimInfo
                && Arrays.equals(dim, h.dim)
                && eq(intentP1, h.intentP1)
                && eq(intentP2, h.intentP2)
                && eq(intentP3, h.intentP3)
                && intentCode == h.intentCode
                && datatype == h.datatype
                && bitpix == h.bitpix
                && sliceStart == h.sliceStart
                && arraysEq(pixdim, h.pixdim)
                && voxOffset == h.voxOffset
                && eq(sclSlope, h.sclSlope)
                && eq(sclInter, h.sclInter)
                && sliceEnd == h.sliceEnd
                && sliceCode == h.sliceCode
                && xyztUnits == h.xyztUnits
                && eq(calMax, h.calMax)
                && eq(calMin, h.calMin)
                && eq(sliceDuration, h.sliceDuration)
                && eq(toffset, h.toffset)
                && descrip.equals(h.descrip)
                && auxFile.equals(h.auxFile)
                && qformCode == h.qformCode
                && sformCode == h.sformCode
                && eq(quaternB, h.quaternB)
                && eq(quaternC, h.quaternC)
                && eq(quaternD, h.quaternD)
                && eq(qoffsetX, h.qoffsetX)
                && eq(qoffsetY, h.qoffsetY)
                && eq(qoffsetZ, h.qoffsetZ)
                && arraysEq(srowX, h.srowX)
                && arraysEq(srowY, h.srowY)
                && arraysEq(srowZ, h.srowZ)
                && intentName.equals(h.intentName)
                && Arrays.equals(unusedStr, h.unusedStr)
                && Arrays.equals(analyzeRaw, h.analyzeRaw);
    }

    @Override
    public int hashCode() {
        int r = version.hashCode();
        r = 31 * r + Arrays.hashCode(dim);
        r = 31 * r + datatype;
        r = 31 * r + bitpix;
        r = 31 * r + Long.hashCode(voxOffset);
        r = 31 * r + Arrays.hashCode(pixdim);
        return r;
    }

    // NaN has to compare equal to itself here: it is a legitimate stored value
    // (scl_slope of NaN turns up in the wild) and a round-trip must be able to
    // assert it came back.
    private static boolean eq(double a, double b) {
        return Double.compare(a, b) == 0;
    }

    private static boolean arraysEq(double[] a, double[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!eq(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }
}
