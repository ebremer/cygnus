package com.ebremer.cygnus.nifti.io;

import com.ebremer.cygnus.nifti.NiftiDataType;
import com.ebremer.cygnus.nifti.NiftiHeader;
import com.ebremer.cygnus.nifti.NiftiVersion;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The numbers a header turns into allocations, and what stops them. */
class BoundsTest {

    private static NiftiHeader header(long... dims) {
        return NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.INT16, dims);
    }

    // ---- dim[0] ----

    @Test
    void dimZeroMustSayHowManyDimensionsThereAre() {
        for (long nd : new long[] {-1, 0, 8, 9, 1000, Long.MAX_VALUE, Long.MIN_VALUE}) {
            NiftiHeader h = header(4, 4);
            h.dim[0] = nd;
            IOException e = assertThrows(IOException.class, () -> Bounds.voxelCount(h),
                    "dim[0] of " + nd);
            assertTrue(e.getMessage().contains("dim[0]"), e.getMessage());
        }
        for (long nd = 1; nd <= 7; nd++) {
            NiftiHeader h = header(2, 2, 2, 2, 2, 2, 2);
            h.dim[0] = nd;
            assertEquals(1L << nd, assertDoesNotThrow(h), nd + " dimensions of 2");
        }
    }

    private static long assertDoesNotThrow(NiftiHeader h) {
        try {
            return Bounds.voxelCount(h);
        } catch (IOException e) {
            throw new AssertionError("expected a clean count", e);
        }
    }

    // ---- dim[i] ----

    @Test
    void aNegativeExtentIsRejectedByName() {
        NiftiHeader h = header(64, 64, 30);
        h.dim[2] = -64;
        IOException e = assertThrows(IOException.class, () -> Bounds.voxelCount(h));
        assertTrue(e.getMessage().contains("dim[2]"), e.getMessage());
        assertTrue(e.getMessage().contains("-64"), e.getMessage());
    }

    @Test
    void aZeroExtentCountsAsOneBecauseRealFilesWriteIt() throws IOException {
        NiftiHeader h = header(64, 64, 30, 0);
        assertEquals(64L * 64 * 30, Bounds.voxelCount(h),
                "a declared but zero fourth dimension is one volume, not none");
    }

    @Test
    void aProductThatOverflowsLongIsRejectedRatherThanWrapping() {
        NiftiHeader h = header(1, 1, 1, 1, 1, 1, 1);
        h.dim[1] = Long.MAX_VALUE;
        h.dim[2] = 3;
        IOException e = assertThrows(IOException.class, () -> Bounds.voxelCount(h));
        assertTrue(e.getMessage().contains("multiply past"), e.getMessage());

        // and every dim at its maximum: seven factors, each enough on its own
        NiftiHeader all = header(1, 1, 1, 1, 1, 1, 1);
        for (int i = 1; i <= 7; i++) {
            all.dim[i] = Long.MAX_VALUE;
        }
        assertThrows(IOException.class, () -> Bounds.voxelCount(all));
    }

    @Test
    void aProductPastIntButNotPastLongIsCountedNotRejected() throws IOException {
        // 2^33 voxels: an int would have wrapped to zero. A NIfTI-2 volume is
        // allowed to be this big, so the count is simply a long.
        NiftiHeader h = header(1 << 17, 1 << 16);
        assertEquals(1L << 33, Bounds.voxelCount(h));
        assertEquals(1L << 34, Bounds.volumeBytes(h, NiftiDataType.INT16));
    }

    @Test
    void volumeBytesOverflowIsRejected() {
        NiftiHeader h = header(1L << 62, 4);
        assertThrows(IOException.class, () -> Bounds.volumeBytes(h, NiftiDataType.FLOAT64));
    }

    // ---- vox_offset against the file ----

    @Test
    void aVolumeMustFitInTheFileItClaimsToBeIn() throws IOException {
        NiftiHeader h = header(64, 64, 30);
        h.voxOffset = 544;                      // this one is NIfTI-2
        long bytes = 64L * 64 * 30 * 2;

        Bounds.checkVolumeFits(h, NiftiDataType.INT16, 544 + bytes);
        Bounds.checkVolumeFits(h, NiftiDataType.INT16, 544 + bytes + 1000);

        IOException e = assertThrows(IOException.class,
                () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, 544 + bytes - 1));
        assertTrue(e.getMessage().contains(String.valueOf(bytes)), e.getMessage());
        assertTrue(e.getMessage().contains("this one is"), e.getMessage());
    }

    @Test
    void anAbsurdVoxelCountIsCaughtByTheFileNotByAGuess() {
        // The header claims more voxels than there are atoms nearby. Nothing
        // has to decide it is unreasonable: the bytes are not there.
        NiftiHeader h = header(1L << 40, 1L << 20);
        h.voxOffset = 544;
        IOException e = assertThrows(IOException.class,
                () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, 1_000_000));
        assertTrue(e.getMessage().contains("this one is 1000000"), e.getMessage());
    }

    @Test
    void aNegativeVoxOffsetIsRejected() {
        NiftiHeader h = header(4, 4);
        h.voxOffset = -1;
        IOException e = assertThrows(IOException.class,
                () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, 1 << 20));
        assertTrue(e.getMessage().contains("negative"), e.getMessage());
    }

    @Test
    void aVoxOffsetInsideTheHeaderIsRejectedForASingleFile() {
        NiftiHeader h = header(4, 4);
        h.singleFile = true;
        for (long offset : new long[] {0, 1, 100, 540, 543}) {
            h.voxOffset = offset;
            IOException e = assertThrows(IOException.class,
                    () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, 1 << 20),
                    "vox_offset " + offset);
            assertTrue(e.getMessage().contains("544"), e.getMessage());
        }
        h.voxOffset = 544;
        assertDoesNotThrowFits(h, 1 << 20);
    }

    @Test
    void aPairPutsItsVoxelsAtTheStartOfTheImageFile() {
        NiftiHeader h = header(4, 4);
        h.singleFile = false;
        h.voxOffset = 0;
        assertDoesNotThrowFits(h, 32);
        assertThrows(IOException.class,
                () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, 31));
    }

    private static void assertDoesNotThrowFits(NiftiHeader h, long available) {
        try {
            Bounds.checkVolumeFits(h, NiftiDataType.INT16, available);
        } catch (IOException e) {
            throw new AssertionError("expected this to fit", e);
        }
    }

    @Test
    void anOffsetPlusLengthThatOverflowsIsRejected() {
        NiftiHeader h = header(1 << 20, 1 << 20);
        h.voxOffset = Long.MAX_VALUE - 8;
        IOException e = assertThrows(IOException.class,
                () -> Bounds.checkVolumeFits(h, NiftiDataType.INT16, Long.MAX_VALUE));
        assertTrue(e.getMessage().contains("overflow"), e.getMessage());
    }

    // ---- a single read ----

    @Test
    void aReadIsHeldToWhatOneArrayCanIndex() throws IOException {
        assertEquals(1000, Bounds.readableVoxels(1000, NiftiDataType.INT16, "a slice"));

        IOException e = assertThrows(IOException.class, () -> Bounds.readableVoxels(
                1L << 40, NiftiDataType.INT16, "a slice"));
        assertTrue(e.getMessage().contains("a slice"), e.getMessage());
        assertTrue(e.getMessage().contains(Bounds.MAX_VOXELS_PROPERTY),
                "the message says how to raise it: " + e.getMessage());

        assertThrows(IOException.class,
                () -> Bounds.readableVoxels(-1, NiftiDataType.INT16, "a slice"));
    }

    @Test
    void aMultiComponentReadCountsItsComponents() {
        // Integer.MAX_VALUE voxels is within the voxel ceiling, but three
        // components each is not an array length.
        IOException e = assertThrows(IOException.class, () -> Bounds.readableVoxels(
                Integer.MAX_VALUE, NiftiDataType.RGB24, "a slab"));
        assertTrue(e.getMessage().contains("components"), e.getMessage());

        assertThrows(IOException.class, () -> Bounds.readableVoxels(
                Integer.MAX_VALUE, NiftiDataType.COMPLEX64, "a slab"));
    }

    @Test
    void theVoxelCeilingIsReadLiveAndClamped() throws IOException {
        String saved = System.getProperty(Bounds.MAX_VOXELS_PROPERTY);
        try {
            System.clearProperty(Bounds.MAX_VOXELS_PROPERTY);
            assertEquals(Integer.MAX_VALUE, Bounds.maxVoxels());

            System.setProperty(Bounds.MAX_VOXELS_PROPERTY, "1000");
            assertEquals(1000, Bounds.maxVoxels());
            assertEquals(1000, Bounds.readableVoxels(1000, NiftiDataType.UINT8, "a slice"));
            assertThrows(IOException.class,
                    () -> Bounds.readableVoxels(1001, NiftiDataType.UINT8, "a slice"));

            System.setProperty(Bounds.MAX_VOXELS_PROPERTY, "0");
            assertEquals(1, Bounds.maxVoxels(), "clamped up: a zero ceiling reads nothing");

            System.setProperty(Bounds.MAX_VOXELS_PROPERTY, "99999999999");
            assertEquals(Integer.MAX_VALUE, Bounds.maxVoxels(),
                    "clamped down: one array is as large as it gets");
        } finally {
            if (saved == null) {
                System.clearProperty(Bounds.MAX_VOXELS_PROPERTY);
            } else {
                System.setProperty(Bounds.MAX_VOXELS_PROPERTY, saved);
            }
        }
    }

    // ---- ranges and lengths ----

    @Test
    void aRangeIsCheckedWithItsEndComputedInLong() throws IOException {
        Bounds.checkRange(0, 100, 100, "a slab");
        Bounds.checkRange(99, 1, 100, "a slab");

        assertThrows(IOException.class, () -> Bounds.checkRange(0, 101, 100, "a slab"));
        assertThrows(IOException.class, () -> Bounds.checkRange(-1, 1, 100, "a slab"));
        assertThrows(IOException.class, () -> Bounds.checkRange(0, -1, 100, "a slab"));

        IOException e = assertThrows(IOException.class, () -> Bounds.checkRange(
                Long.MAX_VALUE - 1, 100, Long.MAX_VALUE, "a slab"));
        assertTrue(e.getMessage().contains("overflow"),
                "the end must not wrap to a small number: " + e.getMessage());
    }

    @Test
    void anArrayLengthMustBeOne() throws IOException {
        assertEquals(0, Bounds.arrayLength(0, "x"));
        assertEquals(Integer.MAX_VALUE, Bounds.arrayLength(Integer.MAX_VALUE, "x"));
        assertThrows(IOException.class, () -> Bounds.arrayLength(-1, "x"));
        assertThrows(IOException.class,
                () -> Bounds.arrayLength((long) Integer.MAX_VALUE + 1, "x"));
    }

    // ---- what none of these may ever be ----

    @Test
    void everyRejectionIsAnIoExceptionAndNothingElse() {
        // The whole point of the layer: a malformed header is a malformed file,
        // not an OutOfMemoryError or a NegativeArraySizeException escaping from
        // an allocation that should never have been attempted.
        NiftiHeader huge = header(4, 4);
        huge.dim[0] = 7;
        for (int i = 1; i <= 7; i++) {
            huge.dim[i] = 1L << 40;
        }
        huge.voxOffset = 544;

        for (Runnable attempt : new Runnable[] {
            () -> tryIt(() -> Bounds.voxelCount(huge)),
            () -> tryIt(() -> Bounds.volumeBytes(huge, NiftiDataType.FLOAT64)),
            () -> tryIt(() -> {
                Bounds.checkVolumeFits(huge, NiftiDataType.FLOAT64, 1024);
                return 0L;
            }),
            () -> tryIt(() -> (long) Bounds.readableVoxels(
                    Long.MAX_VALUE, NiftiDataType.FLOAT64, "everything")),
        }) {
            attempt.run();
        }
    }

    private interface Attempt {
        long run() throws IOException;
    }

    private static void tryIt(Attempt attempt) {
        try {
            attempt.run();
        } catch (IOException expected) {
            return;
        } catch (RuntimeException | Error wrong) {
            throw new AssertionError("a malformed header must give an IOException, not "
                    + wrong.getClass().getName(), wrong);
        }
    }
}
