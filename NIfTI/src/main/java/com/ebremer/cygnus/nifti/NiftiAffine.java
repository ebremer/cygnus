package com.ebremer.cygnus.nifti;

import java.util.Arrays;
import java.util.Locale;

/**
 * The mapping from voxel indices to world coordinates: a 4x4 affine, and the
 * two ways a NIfTI header spells one.
 *
 * <p>World coordinates are RAS+ — {@code +x} toward the subject's right,
 * {@code +y} anterior, {@code +z} superior — and are in whatever
 * {@link NiftiHeader#spaceUnits()} says, millimetres in all but a handful of
 * files.</p>
 *
 * <h2>Two spellings, and which one wins</h2>
 *
 * <p>A header can carry the mapping twice. The <b>sform</b> is three explicit
 * rows of an affine, which can shear and scale however it likes. The
 * <b>qform</b> is a unit quaternion plus an offset, which can only rotate and
 * translate — so it cannot represent a resampled image, but it also cannot
 * accidentally represent one, and it is what a scanner writes.</p>
 *
 * <p>Either may be absent, said by a zero {@code sform_code} or
 * {@code qform_code}, and they need not agree: a file aligned to a template
 * usually keeps the scanner's qform and adds the alignment as an sform. The
 * convention every implementation follows, and {@link #of} with it, is
 * sform first, then qform, then nothing but {@code pixdim} — which gives
 * voxel sizes and no orientation at all, and is a guess rather than a
 * measurement.</p>
 *
 * <h2>qfac</h2>
 *
 * <p>A quaternion is a rotation, and a rotation cannot mirror. Some images
 * are stored left-handed, so NIfTI keeps a sign in {@code pixdim[0]} —
 * {@code qfac}, -1 or 1 — that flips the k axis before the rotation is
 * applied. A {@code pixdim[0]} of 0, which is what a writer that never set it
 * leaves, means 1.</p>
 */
public final class NiftiAffine {

    /** Which of a header's transforms an affine came from. */
    public enum Source {
        /** {@code srow_x/y/z}, chosen because {@code sform_code} is nonzero. */
        SFORM,
        /** The quaternion, chosen because {@code qform_code} is nonzero and {@code sform_code} is not. */
        QFORM,
        /** Neither was present: voxel sizes from {@code pixdim}, and no orientation. */
        PIXDIM
    }

    private final double[] m;   // row-major 4x4

    private NiftiAffine(double[] rowMajor16) {
        this.m = rowMajor16;
    }

    /** An affine from 16 row-major values. */
    public static NiftiAffine ofRowMajor(double... values) {
        if (values.length != 16) {
            throw new IllegalArgumentException("a 4x4 affine has 16 values, not " + values.length);
        }
        return new NiftiAffine(values.clone());
    }

    /** The identity. */
    public static NiftiAffine identity() {
        return new NiftiAffine(new double[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1});
    }

    /** Element at {@code row}, {@code col}, both 0..3. */
    public double get(int row, int col) {
        return m[row * 4 + col];
    }

    /** The affine as a fresh row-major array of 16. */
    public double[] toRowMajor() {
        return m.clone();
    }

    /** The affine as a fresh 4x4. */
    public double[][] toMatrix() {
        double[][] out = new double[4][4];
        for (int r = 0; r < 4; r++) {
            System.arraycopy(m, r * 4, out[r], 0, 4);
        }
        return out;
    }

    /** The world coordinates of voxel {@code (i, j, k)}: {@code {x, y, z}}. */
    public double[] apply(double i, double j, double k) {
        return new double[] {
            m[0] * i + m[1] * j + m[2] * k + m[3],
            m[4] * i + m[5] * j + m[6] * k + m[7],
            m[8] * i + m[9] * j + m[10] * k + m[11]};
    }

    /**
     * The determinant of the rotation-and-scale part. Its sign is the image's
     * handedness: negative is what {@code qfac} of -1 encodes.
     */
    public double determinant() {
        return m[0] * (m[5] * m[10] - m[6] * m[9])
                - m[1] * (m[4] * m[10] - m[6] * m[8])
                + m[2] * (m[4] * m[9] - m[5] * m[8]);
    }

    /** The length of column {@code c} of the 3x3 part: the voxel width along that axis. */
    public double scale(int c) {
        return Math.sqrt(m[c] * m[c] + m[4 + c] * m[4 + c] + m[8 + c] * m[8 + c]);
    }

    // =====================================================================
    // Reading a header.
    // =====================================================================

    /**
     * The affine a reader should use, by the usual precedence: the sform if
     * {@code sform_code} is nonzero, else the qform if {@code qform_code} is,
     * else {@code pixdim} alone.
     */
    public static NiftiAffine of(NiftiHeader header) {
        return switch (sourceOf(header)) {
            case SFORM -> fromSform(header);
            case QFORM -> fromQform(header);
            case PIXDIM -> fromPixdim(header);
        };
    }

    /** Which transform {@link #of} would take. */
    public static Source sourceOf(NiftiHeader header) {
        if (header.version.hasGeometry() && header.sformCode != NiftiXform.UNKNOWN) {
            return Source.SFORM;
        }
        if (header.version.hasGeometry() && header.qformCode != NiftiXform.UNKNOWN) {
            return Source.QFORM;
        }
        return Source.PIXDIM;
    }

    /** The sform's three rows as an affine, whatever {@code sform_code} says. */
    public static NiftiAffine fromSform(NiftiHeader header) {
        return new NiftiAffine(new double[] {
            header.srowX[0], header.srowX[1], header.srowX[2], header.srowX[3],
            header.srowY[0], header.srowY[1], header.srowY[2], header.srowY[3],
            header.srowZ[0], header.srowZ[1], header.srowZ[2], header.srowZ[3],
            0, 0, 0, 1});
    }

    /**
     * The quaternion, the voxel sizes and the offsets as an affine, whatever
     * {@code qform_code} says.
     *
     * <p>{@code a} is not stored — it is {@code sqrt(1 - b^2 - c^2 - d^2)},
     * which the specification can leave out because the quaternion is a unit
     * one and {@code a} is taken non-negative. A header whose {@code b, c, d}
     * are slightly too long for that (rounding, or a header written by
     * something careless) would give a negative radicand; the components are
     * scaled back to unit length instead, which is what the reference
     * implementation does and is the nearest rotation to what the file
     * said.</p>
     */
    public static NiftiAffine fromQform(NiftiHeader header) {
        double b = header.quaternB;
        double c = header.quaternC;
        double d = header.quaternD;
        double a;
        double norm = b * b + c * c + d * d;
        if (1.0 - norm < 1e-7) {
            double scale = norm > 0 ? 1.0 / Math.sqrt(norm) : 0;
            b *= scale;
            c *= scale;
            d *= scale;
            a = 0;
        } else {
            a = Math.sqrt(1.0 - norm);
        }

        double dx = header.pixdim[1];
        double dy = header.pixdim[2];
        // qfac flips k, which is how a rotation gets to describe a left-handed image
        double dz = header.pixdim[3] * header.qfac();

        return new NiftiAffine(new double[] {
            (a * a + b * b - c * c - d * d) * dx, (2 * b * c - 2 * a * d) * dy, (2 * b * d + 2 * a * c) * dz, header.qoffsetX,
            (2 * b * c + 2 * a * d) * dx, (a * a + c * c - b * b - d * d) * dy, (2 * c * d - 2 * a * b) * dz, header.qoffsetY,
            (2 * b * d - 2 * a * c) * dx, (2 * c * d + 2 * a * b) * dy, (a * a + d * d - c * c - b * b) * dz, header.qoffsetZ,
            0, 0, 0, 1});
    }

    /**
     * Voxel sizes and nothing else: the diagonal of {@code pixdim[1..3]}, no
     * rotation and no offset. This is not a measurement of where the image is,
     * it is what is left when the file did not say.
     */
    public static NiftiAffine fromPixdim(NiftiHeader header) {
        return new NiftiAffine(new double[] {
            header.pixdim[1], 0, 0, 0,
            0, header.pixdim[2], 0, 0,
            0, 0, header.pixdim[3], 0,
            0, 0, 0, 1});
    }

    // =====================================================================
    // Writing a header.
    // =====================================================================

    /** Writes this affine into {@code srow_x/y/z} and sets {@code sform_code}. */
    public void writeSformTo(NiftiHeader header, int sformCode) {
        for (int i = 0; i < 4; i++) {
            header.srowX[i] = m[i];
            header.srowY[i] = m[4 + i];
            header.srowZ[i] = m[8 + i];
        }
        header.sformCode = sformCode;
    }

    /**
     * Decomposes this affine into a quaternion, offsets, voxel sizes and
     * {@code qfac}, writes them into {@code header}, and sets
     * {@code qform_code}.
     *
     * <p>Only the rotation survives — a qform has nowhere to put shear, and an
     * affine with any will come back rotated rather than sheared. The column
     * lengths become {@code pixdim[1..3]}, and a negative determinant becomes
     * {@code qfac} of -1 with the third column negated, since a quaternion
     * describes a rotation and a rotation cannot mirror.</p>
     */
    public void writeQformTo(NiftiHeader header, int qformCode) {
        header.qoffsetX = m[3];
        header.qoffsetY = m[7];
        header.qoffsetZ = m[11];

        double[] r = new double[9];
        double[] d = new double[3];
        for (int c = 0; c < 3; c++) {
            double len = scale(c);
            d[c] = len;
            double inv = len == 0 ? 0 : 1.0 / len;
            r[c] = m[c] * inv;
            r[3 + c] = m[4 + c] * inv;
            r[6 + c] = m[8 + c] * inv;
        }

        double qfac = 1;
        if (det3(r) < 0) {
            // a left-handed frame: flip k and record the flip in pixdim[0]
            qfac = -1;
            r[2] = -r[2];
            r[5] = -r[5];
            r[8] = -r[8];
        }

        header.pixdim[0] = qfac;
        header.pixdim[1] = d[0];
        header.pixdim[2] = d[1];
        header.pixdim[3] = d[2];

        double[] q = toQuaternion(r);
        header.quaternB = q[1];
        header.quaternC = q[2];
        header.quaternD = q[3];
        header.qformCode = qformCode;
    }

    private static double det3(double[] r) {
        return r[0] * (r[4] * r[8] - r[5] * r[7])
                - r[1] * (r[3] * r[8] - r[5] * r[6])
                + r[2] * (r[3] * r[7] - r[4] * r[6]);
    }

    /**
     * A proper rotation as a unit quaternion {@code {a, b, c, d}} with
     * {@code a >= 0}.
     *
     * <p>The obvious formula divides by {@code a}, and {@code a} goes to zero
     * as the rotation approaches 180 degrees — where the trace approaches -1
     * and the division loses every digit it had. At that point the largest
     * diagonal element is what is well determined, so the quaternion is built
     * from that instead. Half-turns are not exotic in neuroimaging: a scan
     * acquired feet-first is one.</p>
     */
    private static double[] toQuaternion(double[] r) {
        double r11 = r[0];
        double r12 = r[1];
        double r13 = r[2];
        double r21 = r[3];
        double r22 = r[4];
        double r23 = r[5];
        double r31 = r[6];
        double r32 = r[7];
        double r33 = r[8];

        double a = 1.0 + r11 + r22 + r33;
        double b;
        double c;
        double d;
        if (a > 0.5) {
            a = 0.5 * Math.sqrt(a);
            b = 0.25 * (r32 - r23) / a;
            c = 0.25 * (r13 - r31) / a;
            d = 0.25 * (r21 - r12) / a;
        } else {
            double xd = 1.0 + r11 - (r22 + r33);
            double yd = 1.0 + r22 - (r11 + r33);
            double zd = 1.0 + r33 - (r11 + r22);
            if (xd >= yd && xd >= zd) {
                b = 0.5 * Math.sqrt(Math.max(xd, 0));
                c = 0.25 * (r12 + r21) / b;
                d = 0.25 * (r13 + r31) / b;
                a = 0.25 * (r32 - r23) / b;
            } else if (yd >= zd) {
                c = 0.5 * Math.sqrt(Math.max(yd, 0));
                b = 0.25 * (r12 + r21) / c;
                d = 0.25 * (r23 + r32) / c;
                a = 0.25 * (r13 - r31) / c;
            } else {
                d = 0.5 * Math.sqrt(Math.max(zd, 0));
                b = 0.25 * (r13 + r31) / d;
                c = 0.25 * (r23 + r32) / d;
                a = 0.25 * (r21 - r12) / d;
            }
            // a is taken non-negative, and (-a,-b,-c,-d) is the same rotation
            if (a < 0) {
                a = -a;
                b = -b;
                c = -c;
                d = -d;
            }
        }
        return new double[] {a, b, c, d};
    }

    // =====================================================================
    // Orientation.
    // =====================================================================

    /**
     * Which way the {@code i}, {@code j} and {@code k} axes point, as three
     * letters from {@code RLAPSI} — {@code "RAS"} for an image whose columns
     * run to the subject's right, whose rows run anterior and whose slices run
     * superior, {@code "LAS"} for the radiological convention, and so on.
     *
     * <p>Each axis is matched with the anatomical direction it lies closest
     * to, over all assignments of the three, so an oblique image gets the
     * nearest cardinal answer rather than none. An axis with no length at all
     * gives {@code '?'}.</p>
     */
    public String orientation() {
        double[] r = new double[9];
        for (int c = 0; c < 3; c++) {
            double len = scale(c);
            if (len == 0 || !Double.isFinite(len)) {
                return "???";
            }
            r[c] = m[c] / len;
            r[3 + c] = m[4 + c] / len;
            r[6 + c] = m[8 + c] / len;
        }

        // brute force over the six permutations and eight sign patterns: 48
        // possibilities, and the best is the orientation. Greedily taking each
        // column's largest component can assign two columns the same axis.
        int[][] permutations = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        double best = Double.NEGATIVE_INFINITY;
        int[] bestAxis = null;
        int[] bestSign = null;
        for (int[] p : permutations) {
            for (int signs = 0; signs < 8; signs++) {
                int[] s = {
                    (signs & 1) == 0 ? 1 : -1,
                    (signs & 2) == 0 ? 1 : -1,
                    (signs & 4) == 0 ? 1 : -1};
                double value = s[0] * r[p[0] * 3] + s[1] * r[p[1] * 3 + 1] + s[2] * r[p[2] * 3 + 2];
                if (value > best) {
                    best = value;
                    bestAxis = p;
                    bestSign = s;
                }
            }
        }

        StringBuilder out = new StringBuilder(3);
        for (int c = 0; c < 3; c++) {
            out.append(letter(bestAxis[c], bestSign[c]));
        }
        return out.toString();
    }

    /** The direction letter for world axis {@code axis} taken with {@code sign}. */
    private static char letter(int axis, int sign) {
        return switch (axis) {
            case 0 -> sign > 0 ? 'R' : 'L';
            case 1 -> sign > 0 ? 'A' : 'P';
            default -> sign > 0 ? 'S' : 'I';
        };
    }

    // =====================================================================

    /** Whether every element is within {@code tolerance} of {@code other}'s. */
    public boolean approxEquals(NiftiAffine other, double tolerance) {
        for (int i = 0; i < 16; i++) {
            if (!(Math.abs(m[i] - other.m[i]) <= tolerance)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NiftiAffine a && Arrays.equals(m, a.m);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(m);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(160);
        s.append(orientation()).append('\n');
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                s.append(String.format(Locale.ROOT, "%12.6f", m[r * 4 + c]));
            }
            s.append('\n');
        }
        return s.toString();
    }
}
