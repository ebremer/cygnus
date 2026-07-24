package com.ebremer.cygnus.nifti;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two spellings of a NIfTI affine, and getting between them. */
class NiftiAffineTest {

    private static final double TOL = 1e-9;

    private static NiftiHeader header() {
        return NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.INT16, 64, 64, 32);
    }

    /** A header carrying the quaternion {@code (b,c,d)}, the given spacings and qfac. */
    private static NiftiHeader qform(double b, double c, double d,
                                     double dx, double dy, double dz, double qfac,
                                     double ox, double oy, double oz) {
        NiftiHeader h = header();
        h.qformCode = NiftiXform.SCANNER_ANAT;
        h.quaternB = b;
        h.quaternC = c;
        h.quaternD = d;
        h.pixdim[0] = qfac;
        h.pixdim[1] = dx;
        h.pixdim[2] = dy;
        h.pixdim[3] = dz;
        h.qoffsetX = ox;
        h.qoffsetY = oy;
        h.qoffsetZ = oz;
        return h;
    }

    // =====================================================================
    // The forward mapping.
    // =====================================================================

    @Test
    void anIdentityQuaternionGivesTheVoxelGridItself() {
        NiftiHeader h = qform(0, 0, 0, 2, 3, 4, 1, 10, 20, 30);
        NiftiAffine a = NiftiAffine.fromQform(h);

        assertArrayEquals(new double[] {10, 20, 30}, a.apply(0, 0, 0), TOL);
        assertArrayEquals(new double[] {12, 20, 30}, a.apply(1, 0, 0), TOL);
        assertArrayEquals(new double[] {10, 23, 30}, a.apply(0, 1, 0), TOL);
        assertArrayEquals(new double[] {10, 20, 34}, a.apply(0, 0, 1), TOL);
        assertEquals(2, a.scale(0), TOL);
        assertEquals(3, a.scale(1), TOL);
        assertEquals(4, a.scale(2), TOL);
        assertEquals("RAS", a.orientation());
    }

    @Test
    void aQuarterTurnAboutZTakesIToJ() {
        // 90 degrees about z: (a,b,c,d) = (cos45, 0, 0, sin45)
        double s = Math.sqrt(0.5);
        NiftiHeader h = qform(0, 0, s, 1, 1, 1, 1, 0, 0, 0);
        NiftiAffine a = NiftiAffine.fromQform(h);
        assertArrayEquals(new double[] {0, 1, 0}, a.apply(1, 0, 0), 1e-12, "i goes to +y");
        assertArrayEquals(new double[] {-1, 0, 0}, a.apply(0, 1, 0), 1e-12, "j goes to -x");
        assertArrayEquals(new double[] {0, 0, 1}, a.apply(0, 0, 1), 1e-12, "k is unchanged");
        assertEquals("ALS", a.orientation(), "i anterior, j left, k superior");
        assertTrue(a.determinant() > 0, "a rotation preserves handedness");
    }

    @Test
    void qfacFlipsTheKAxisAndTheHandednessWithIt() {
        NiftiAffine right = NiftiAffine.fromQform(qform(0, 0, 0, 2, 2, 2, 1, 0, 0, 0));
        NiftiAffine left = NiftiAffine.fromQform(qform(0, 0, 0, 2, 2, 2, -1, 0, 0, 0));

        assertArrayEquals(new double[] {0, 0, 2}, right.apply(0, 0, 1), TOL);
        assertArrayEquals(new double[] {0, 0, -2}, left.apply(0, 0, 1), TOL);
        assertTrue(right.determinant() > 0);
        assertTrue(left.determinant() < 0, "qfac of -1 is what makes an image left-handed");
        assertEquals("RAS", right.orientation());
        assertEquals("RAI", left.orientation());
    }

    @Test
    void anUnsetPixdimZeroMeansQfacOfOne() {
        NiftiAffine a = NiftiAffine.fromQform(qform(0, 0, 0, 1, 1, 1, 0, 0, 0, 0));
        assertArrayEquals(new double[] {0, 0, 1}, a.apply(0, 0, 1), TOL,
                "pixdim[0] of zero is 1, not 0 -- a zero would collapse the k axis");
        assertTrue(a.determinant() > 0);
    }

    @Test
    void anOverlongQuaternionIsScaledBackRatherThanRootingANegative() {
        // b^2+c^2+d^2 > 1, which makes a = sqrt(1 - that) imaginary. The
        // components are normalized instead, giving the nearest rotation.
        NiftiHeader h = qform(0.9, 0.9, 0.9, 1, 1, 1, 1, 0, 0, 0);
        NiftiAffine a = NiftiAffine.fromQform(h);
        for (double v : a.toRowMajor()) {
            assertTrue(Double.isFinite(v), "no NaN escapes into the affine");
        }
        assertEquals(1, Math.abs(a.determinant()), 1e-9, "and it is still a rotation");
    }

    // =====================================================================
    // Round-tripping the quaternion.
    // =====================================================================

    /** Every rotation from a unit quaternion, as an affine and back again. */
    private static void assertQuaternionRoundTrips(double b, double c, double d,
                                                   double qfac, String label) {
        NiftiHeader in = qform(b, c, d, 1.25, 2.5, 3.75, qfac, 7, -8, 9);
        NiftiAffine affine = NiftiAffine.fromQform(in);

        NiftiHeader out = header();
        affine.writeQformTo(out, NiftiXform.SCANNER_ANAT);

        assertTrue(affine.approxEquals(NiftiAffine.fromQform(out), 1e-9),
                label + ": the affine came back as\n" + NiftiAffine.fromQform(out)
                        + "instead of\n" + affine);
        assertEquals(NiftiXform.SCANNER_ANAT, out.qformCode);
        assertEquals(1.25, out.pixdim[1], 1e-9, label + " dx");
        assertEquals(2.5, out.pixdim[2], 1e-9, label + " dy");
        assertEquals(3.75, out.pixdim[3], 1e-9, label + " dz");
        assertEquals(qfac, out.qfac(), label + " qfac");
        assertArrayEquals(new double[] {7, -8, 9},
                new double[] {out.qoffsetX, out.qoffsetY, out.qoffsetZ}, 1e-12,
                label + " offsets");
    }

    @TestFactory
    List<DynamicTest> namedRotationsRoundTrip() {
        double h = Math.sqrt(0.5);
        record Case(String label, double b, double c, double d) {
        }
        List<Case> cases = List.of(
                new Case("identity", 0, 0, 0),
                new Case("90 about x", h, 0, 0),
                new Case("90 about y", 0, h, 0),
                new Case("90 about z", 0, 0, h),
                new Case("180 about x", 1, 0, 0),
                new Case("180 about y", 0, 1, 0),
                new Case("180 about z", 0, 0, 1),
                new Case("180 about the xy diagonal", h, h, 0),
                new Case("180 about the xyz diagonal",
                        1 / Math.sqrt(3), 1 / Math.sqrt(3), 1 / Math.sqrt(3)),
                new Case("120 about the xyz diagonal", 0.5, 0.5, 0.5),
                new Case("just under 180 about x", Math.sin(Math.PI / 2 - 1e-6), 0, 0),
                new Case("just under 180 about y", 0, Math.sin(Math.PI / 2 - 1e-7), 0),
                new Case("small", 0.01, -0.02, 0.03));

        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            for (double qfac : new double[] {1, -1}) {
                tests.add(DynamicTest.dynamicTest(c.label() + ", qfac " + (int) qfac,
                        () -> assertQuaternionRoundTrips(c.b(), c.c(), c.d(), qfac,
                                c.label())));
            }
        }
        return tests;
    }

    @Test
    void halfTurnsAreWhereTheNaiveInverseWouldHaveLostEveryDigit() {
        // At 180 degrees a is 0, and b, c, d come from the trace via a division
        // by a. Approaching it from every side must still round-trip.
        for (double eps : new double[] {0, 1e-15, 1e-12, 1e-9, 1e-6, 1e-3}) {
            double angle = Math.PI - eps;
            double s = Math.sin(angle / 2);
            for (double[] axis : new double[][] {
                    {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
                    {1, 1, 0}, {0, 1, 1}, {1, 0, 1}, {1, 1, 1}, {1, -2, 3}}) {
                double n = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
                assertQuaternionRoundTrips(s * axis[0] / n, s * axis[1] / n, s * axis[2] / n,
                        1, "angle pi-" + eps + " about (" + axis[0] + "," + axis[1]
                                + "," + axis[2] + ")");
            }
        }
    }

    @Test
    void randomRotationsRoundTrip() {
        Random rnd = new Random(20260724L);
        for (int i = 0; i < 2000; i++) {
            // a uniformly random unit quaternion, forced to a >= 0
            double[] q = {rnd.nextGaussian(), rnd.nextGaussian(),
                          rnd.nextGaussian(), rnd.nextGaussian()};
            double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            if (n < 1e-8) {
                continue;
            }
            double sign = q[0] < 0 ? -1 : 1;
            assertQuaternionRoundTrips(sign * q[1] / n, sign * q[2] / n, sign * q[3] / n,
                    rnd.nextBoolean() ? 1 : -1, "random " + i);
        }
    }

    @Test
    void theRecoveredQuaternionIsTheSameRotationAndHasNonNegativeA() {
        Random rnd = new Random(5);
        for (int i = 0; i < 500; i++) {
            double[] q = {Math.abs(rnd.nextGaussian()), rnd.nextGaussian(),
                          rnd.nextGaussian(), rnd.nextGaussian()};
            double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            NiftiHeader in = qform(q[1] / n, q[2] / n, q[3] / n, 1, 1, 1, 1, 0, 0, 0);

            NiftiHeader out = header();
            NiftiAffine.fromQform(in).writeQformTo(out, NiftiXform.ALIGNED_ANAT);

            double recoveredA = 1 - (out.quaternB * out.quaternB
                    + out.quaternC * out.quaternC + out.quaternD * out.quaternD);
            assertTrue(recoveredA >= -1e-9,
                    "b^2+c^2+d^2 must not exceed 1, so a stays real: " + recoveredA);
            assertArrayEquals(
                    new double[] {in.quaternB, in.quaternC, in.quaternD},
                    new double[] {out.quaternB, out.quaternC, out.quaternD}, 1e-9,
                    "the same quaternion comes back, not its negation");
        }
    }

    // =====================================================================
    // sform, and which transform a reader takes.
    // =====================================================================

    /** The MNI152 2mm template's affine, as every file in that space carries it. */
    private static NiftiHeader mni152() {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, 91, 109, 91);
        h.sformCode = NiftiXform.MNI_152;
        h.srowX = new double[] {-2, 0, 0, 90};
        h.srowY = new double[] {0, 2, 0, -126};
        h.srowZ = new double[] {0, 0, 2, -72};
        h.pixdim[1] = 2;
        h.pixdim[2] = 2;
        h.pixdim[3] = 2;
        return h;
    }

    @Test
    void aKnownTemplateAffineMapsItsKnownOrigin() {
        NiftiAffine a = NiftiAffine.of(mni152());
        assertSame(NiftiAffine.Source.SFORM, NiftiAffine.sourceOf(mni152()));
        assertArrayEquals(new double[] {90, -126, -72}, a.apply(0, 0, 0), TOL);
        assertArrayEquals(new double[] {0, 0, 0}, a.apply(45, 63, 36), TOL,
                "voxel (45,63,36) is the anterior commissure, the origin of MNI space");
        assertEquals("LAS", a.orientation(), "the radiological convention");
        assertTrue(a.determinant() < 0, "and it is left-handed");
        assertEquals(2, a.scale(0), TOL);
    }

    @Test
    void anSformSurvivesBeingWrittenAsAQform() {
        NiftiAffine sform = NiftiAffine.of(mni152());
        NiftiHeader h = header();
        sform.writeQformTo(h, NiftiXform.MNI_152);

        assertEquals(-1, h.qfac(), "a left-handed affine needs qfac of -1");
        assertEquals(2, h.pixdim[1], TOL);
        assertEquals(2, h.pixdim[3], TOL);
        assertTrue(sform.approxEquals(NiftiAffine.fromQform(h), 1e-9),
                "got\n" + NiftiAffine.fromQform(h) + "instead of\n" + sform);
    }

    @Test
    void anAffineSurvivesBeingWrittenAsAnSform() {
        NiftiAffine a = NiftiAffine.of(mni152());
        NiftiHeader h = header();
        a.writeSformTo(h, NiftiXform.TALAIRACH);
        assertEquals(NiftiXform.TALAIRACH, h.sformCode);
        assertEquals(a, NiftiAffine.fromSform(h), "an sform is stored exactly, not decomposed");
        assertArrayEquals(new double[] {-2, 0, 0, 90}, h.srowX, TOL);
    }

    @Test
    void sformWinsWhenBothAreThereAndTheyDisagree() {
        NiftiHeader h = mni152();
        h.qformCode = NiftiXform.SCANNER_ANAT;
        h.quaternB = 0;
        h.quaternC = 0;
        h.quaternD = 0;
        h.qoffsetX = 999;      // deliberately nothing like the sform
        h.qoffsetY = 999;
        h.qoffsetZ = 999;

        assertSame(NiftiAffine.Source.SFORM, NiftiAffine.sourceOf(h));
        assertArrayEquals(new double[] {90, -126, -72}, NiftiAffine.of(h).apply(0, 0, 0), TOL);
        // ...and the qform is still there to be looked at
        assertArrayEquals(new double[] {999, 999, 999},
                NiftiAffine.fromQform(h).apply(0, 0, 0), TOL);
    }

    @Test
    void qformIsTakenWhenTheSformIsAbsent() {
        NiftiHeader h = mni152();
        h.sformCode = NiftiXform.UNKNOWN;
        h.qformCode = NiftiXform.SCANNER_ANAT;
        h.qoffsetX = 5;
        h.qoffsetY = 6;
        h.qoffsetZ = 7;
        assertSame(NiftiAffine.Source.QFORM, NiftiAffine.sourceOf(h));
        assertArrayEquals(new double[] {5, 6, 7}, NiftiAffine.of(h).apply(0, 0, 0), TOL);
    }

    @Test
    void withNeitherCodeSetThereIsOnlyPixdimAndNoOrientation() {
        NiftiHeader h = mni152();
        h.sformCode = NiftiXform.UNKNOWN;
        h.qformCode = NiftiXform.UNKNOWN;
        assertSame(NiftiAffine.Source.PIXDIM, NiftiAffine.sourceOf(h));

        NiftiAffine a = NiftiAffine.of(h);
        assertArrayEquals(new double[] {0, 0, 0}, a.apply(0, 0, 0), TOL,
                "no offset is known, so there is none");
        assertArrayEquals(new double[] {2, 0, 0}, a.apply(1, 0, 0), TOL,
                "the voxel size is all that is left");
        assertEquals("RAS", a.orientation(),
                "which is a default, not a measurement -- the file never said");
    }

    @Test
    void anAnalyzeHeaderHasNoGeometryEvenIfTheBytesAtThoseOffsetsAreNonzero() {
        NiftiHeader h = mni152();
        h.version = NiftiVersion.ANALYZE75;
        assertSame(NiftiAffine.Source.PIXDIM, NiftiAffine.sourceOf(h),
                "ANALYZE has no qform or sform to take");
    }

    // =====================================================================
    // Orientation.
    // =====================================================================

    @Test
    void orientationNamesWhereEachAxisPoints() {
        assertEquals("RAS", NiftiAffine.identity().orientation());
        assertEquals("LAS", diagonal(-1, 1, 1).orientation());
        assertEquals("LPS", diagonal(-1, -1, 1).orientation());
        assertEquals("RAI", diagonal(1, 1, -1).orientation());
        assertEquals("LPI", diagonal(-1, -1, -1).orientation());

        // an axis permutation: i runs superior, j right, k anterior
        assertEquals("SRA", NiftiAffine.ofRowMajor(
                0, 1, 0, 0,
                0, 0, 1, 0,
                1, 0, 0, 0,
                0, 0, 0, 1).orientation());
    }

    private static NiftiAffine diagonal(double x, double y, double z) {
        return NiftiAffine.ofRowMajor(
                x, 0, 0, 0,
                0, y, 0, 0,
                0, 0, z, 0,
                0, 0, 0, 1);
    }

    @Test
    void anObliqueImageGetsTheNearestCardinalAnswer() {
        // rotated 20 degrees about x: still essentially RAS
        double c = Math.cos(Math.toRadians(20));
        double s = Math.sin(Math.toRadians(20));
        assertEquals("RAS", NiftiAffine.ofRowMajor(
                1, 0, 0, 0,
                0, c, -s, 0,
                0, s, c, 0,
                0, 0, 0, 1).orientation());

        // rotated 70 degrees: j is now closer to superior than anterior
        c = Math.cos(Math.toRadians(70));
        s = Math.sin(Math.toRadians(70));
        assertEquals("RSP", NiftiAffine.ofRowMajor(
                1, 0, 0, 0,
                0, c, -s, 0,
                0, s, c, 0,
                0, 0, 0, 1).orientation());
    }

    @Test
    void everyOrientationIsThreeDistinctAxes() {
        // A greedy per-column choice can name the same axis twice. Over a few
        // thousand random affines, no letter pair may collide.
        Random rnd = new Random(99);
        for (int i = 0; i < 3000; i++) {
            double[] v = new double[16];
            for (int j = 0; j < 12; j++) {
                v[j] = rnd.nextGaussian();
            }
            v[15] = 1;
            String o = NiftiAffine.ofRowMajor(v).orientation();
            assertEquals(3, o.length());
            assertEquals(3, o.chars().map(NiftiAffineTest::axisOf).distinct().count(),
                    "three different axes in " + o);
        }
    }

    private static int axisOf(int letter) {
        return switch (letter) {
            case 'R', 'L' -> 0;
            case 'A', 'P' -> 1;
            default -> 2;
        };
    }

    @Test
    void aCollapsedAxisIsAdmittedRatherThanGuessedAt() {
        assertEquals("???", diagonal(1, 1, 0).orientation());
        assertEquals("???", diagonal(1, Double.NaN, 1).orientation());
    }

    // =====================================================================

    @Test
    void theMatrixAccessorsAgreeWithEachOther() {
        NiftiAffine a = NiftiAffine.of(mni152());
        double[] flat = a.toRowMajor();
        double[][] square = a.toMatrix();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(flat[r * 4 + c], a.get(r, c));
                assertEquals(flat[r * 4 + c], square[r][c]);
            }
        }
        flat[0] = 999;
        square[0][0] = 999;
        assertEquals(-2, a.get(0, 0), "the accessors hand back copies");
        assertEquals(a, NiftiAffine.ofRowMajor(a.toRowMajor()));
        assertEquals(a.hashCode(), NiftiAffine.ofRowMajor(a.toRowMajor()).hashCode());
    }

    @Test
    void toStringLeadsWithTheOrientation() {
        assertTrue(NiftiAffine.of(mni152()).toString().startsWith("LAS"));
    }
}
