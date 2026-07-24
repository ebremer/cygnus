package com.ebremer.cygnus.nifti;

import com.ebremer.cygnus.nifti.io.Bounds;
import com.ebremer.cygnus.nifti.testutil.NiftiBuilder;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headers that are wrong, and what has to happen when they are.
 *
 * <p>The contract is one sentence: a malformed file gives an
 * {@link IOException}. Never an {@link OutOfMemoryError}, a
 * {@link NegativeArraySizeException} or an
 * {@link ArrayIndexOutOfBoundsException}, because each of those means a
 * number out of the file reached an allocation or an index before anything
 * checked it — and the difference between "rejected the file" and "rejected
 * the file after trying to allocate two gigabytes" is the whole point of the
 * bounds layer.</p>
 *
 * <p>{@link #nothingIsAllocatedFromANumberBeforeItIsChecked()} measures that
 * second part rather than assuming it.</p>
 */
class HostileHeaderTest {

    // NIfTI-2 field offsets, from the specification's table.
    private static final int V2_SIZEOF_HDR = 0;
    private static final int V2_DATATYPE = 12;
    private static final int V2_BITPIX = 14;
    private static final int V2_DIM = 16;
    private static final int V2_VOX_OFFSET = 168;

    // NIfTI-1 field offsets.
    private static final int V1_DIM = 40;
    private static final int V1_BITPIX = 72;
    private static final int V1_VOX_OFFSET = 108;

    private static final long[] SHAPE = {4, 3, 2};
    private static final NiftiDataType TYPE = NiftiDataType.INT16;

    /** A perfectly good image, which every case below then damages in one place. */
    private static byte[] valid(NiftiVersion version) throws IOException {
        NiftiHeader h = NiftiHeader.of(version, TYPE, SHAPE);
        return NiftiBuilder.singleFile(h, List.of(),
                NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));
    }

    private static byte[] damaged(NiftiVersion version, Consumer<ByteBuffer> damage)
            throws IOException {
        byte[] image = valid(version);
        damage.accept(ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN));
        return image;
    }

    /** Opens {@code image}, requiring an IOException and nothing else. */
    private static IOException reject(byte[] image, String what) {
        try (NiftiReader r = NiftiReader.open(image)) {
            r.readAll();
        } catch (IOException expected) {
            return expected;
        } catch (RuntimeException | Error wrong) {
            throw new AssertionError(what + " gave " + wrong.getClass().getName()
                    + " instead of an IOException", wrong);
        }
        return fail(what + " was accepted");
    }

    // =====================================================================
    // dim
    // =====================================================================

    @TestFactory
    List<DynamicTest> dimZeroOutsideOneToSevenIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (long nd : new long[] {0, 8, -1, 127, Long.MIN_VALUE, Long.MAX_VALUE}) {
            tests.add(DynamicTest.dynamicTest("dim[0] = " + nd, () -> {
                IOException e = reject(damaged(NiftiVersion.NIFTI2,
                        b -> b.putLong(V2_DIM, nd)), "dim[0] of " + nd);
                assertTrue(e.getMessage().contains("dim[0]"), e.getMessage());
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> hostileExtentsAreRejected() {
        record Case(String label, long extent) {
        }
        List<Case> cases = List.of(
                new Case("negative", -4),
                new Case("hugely negative", Long.MIN_VALUE),
                new Case("2^40", 1L << 40),
                new Case("Long.MAX_VALUE", Long.MAX_VALUE));

        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            tests.add(DynamicTest.dynamicTest("dim[1] " + c.label(), () ->
                    reject(damaged(NiftiVersion.NIFTI2,
                            b -> b.putLong(V2_DIM + 8, c.extent())),
                            "dim[1] of " + c.extent())));
        }
        return tests;
    }

    @Test
    void aProductThatOverflowsAnIntIsNotAllowedToWrapToSomethingSmall() throws IOException {
        // 2^16 x 2^16 = 2^32, which is 0 in an int. A reader that multiplied in
        // int would read this as an empty image and then index past its array.
        byte[] image = damaged(NiftiVersion.NIFTI2, b -> {
            b.putLong(V2_DIM, 2);
            b.putLong(V2_DIM + 8, 1 << 16);
            b.putLong(V2_DIM + 16, 1 << 16);
        });
        IOException e = reject(image, "a 2^32-voxel image in a 592-byte file");
        assertTrue(e.getMessage().contains("8589934592"),
                "the byte count is computed, not wrapped: " + e.getMessage());
    }

    @Test
    void aProductThatOverflowsALongIsRejectedRatherThanWrapped() throws IOException {
        byte[] image = damaged(NiftiVersion.NIFTI2, b -> {
            b.putLong(V2_DIM, 7);
            for (int i = 1; i <= 7; i++) {
                b.putLong(V2_DIM + 8 * i, 1L << 40);
            }
        });
        IOException e = reject(image, "seven dimensions of 2^40");
        assertTrue(e.getMessage().contains("multiply past"), e.getMessage());
    }

    @Test
    void aDimBeyondDimZeroIsIgnoredHoweverHostileItIs() throws IOException {
        // dim[0] is 3, so dim[4..7] are not part of the image and cannot make
        // it larger, whatever they say.
        byte[] image = damaged(NiftiVersion.NIFTI2, b -> {
            for (int i = 4; i <= 7; i++) {
                b.putLong(V2_DIM + 8 * i, Long.MIN_VALUE);
            }
        });
        try (NiftiReader r = NiftiReader.open(image)) {
            assertEquals(24, r.voxelCount());
            assertEquals(24, ((short[]) r.readAll()).length);
        }
    }

    // =====================================================================
    // datatype and bitpix
    // =====================================================================

    @TestFactory
    List<DynamicTest> aBitpixContradictingTheDatatypeIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int bitpix : new int[] {1, 7, 8, 32, 64, 255, 32767, -16}) {
            tests.add(DynamicTest.dynamicTest("INT16 with bitpix " + bitpix, () -> {
                IOException e = reject(damaged(NiftiVersion.NIFTI2,
                        b -> b.putShort(V2_BITPIX, (short) bitpix)),
                        "bitpix " + bitpix + " against INT16");
                assertTrue(e.getMessage().contains("bitpix"), e.getMessage());
            }));
        }
        return tests;
    }

    @Test
    void aZeroBitpixIsTakenFromTheDatatypeRatherThanFoughtOver() throws IOException {
        byte[] image = damaged(NiftiVersion.NIFTI2, b -> b.putShort(V2_BITPIX, (short) 0));
        try (NiftiReader r = NiftiReader.open(image)) {
            assertEquals(24, ((short[]) r.readAll()).length);
        }
    }

    @TestFactory
    List<DynamicTest> anImpossibleDatatypeIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int code : new int[] {0, 3, 5, 1234, -1, 32767, Short.MIN_VALUE}) {
            tests.add(DynamicTest.dynamicTest("datatype " + code, () ->
                    reject(damaged(NiftiVersion.NIFTI2, b -> {
                        b.putShort(V2_DATATYPE, (short) code);
                        b.putShort(V2_BITPIX, (short) 0);
                    }), "datatype " + code)));
        }
        return tests;
    }

    // =====================================================================
    // vox_offset
    // =====================================================================

    @TestFactory
    List<DynamicTest> hostileVoxOffsetsAreRejected() {
        record Case(String label, long offset) {
        }
        List<Case> cases = List.of(
                new Case("negative", -1),
                new Case("hugely negative", Long.MIN_VALUE),
                new Case("zero, inside the header", 0),
                new Case("inside the header", 300),
                new Case("one short of the minimum", 543),
                new Case("past the end of the file", 1 << 20),
                new Case("near the top of the range", Long.MAX_VALUE - 8));

        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            tests.add(DynamicTest.dynamicTest("vox_offset " + c.label(), () ->
                    reject(damaged(NiftiVersion.NIFTI2,
                            b -> b.putLong(V2_VOX_OFFSET, c.offset())),
                            "vox_offset " + c.offset())));
        }
        return tests;
    }

    @Test
    void aVoxOffsetLeavingTooFewBytesForTheVoxelsIsRejected() throws IOException {
        // The offset is legal and inside the file; what is not there is the
        // tail of the voxel array.
        byte[] image = damaged(NiftiVersion.NIFTI2, b -> b.putLong(V2_VOX_OFFSET, 560));
        IOException e = reject(image, "voxels running past the end");
        assertTrue(e.getMessage().contains("this one is"), e.getMessage());
    }

    @Test
    void aNonFiniteVoxOffsetIsRejectedBecauseAV1FieldCanSpellOne() throws IOException {
        // vox_offset is a float in NIfTI-1, so NaN and the infinities fit in it.
        for (float bad : new float[] {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            IOException e = reject(damaged(NiftiVersion.NIFTI1,
                    b -> b.putFloat(V1_VOX_OFFSET, bad)), "vox_offset of " + bad);
            assertTrue(e.getMessage().contains("vox_offset"), e.getMessage());
        }
    }

    // =====================================================================
    // sizeof_hdr and the magic
    // =====================================================================

    @TestFactory
    List<DynamicTest> anImpossibleSizeofHdrIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int size : new int[] {0, 1, 347, 349, 539, 541, Integer.MAX_VALUE,
                                   Integer.MIN_VALUE, -348}) {
            tests.add(DynamicTest.dynamicTest("sizeof_hdr " + size, () -> {
                byte[] image = damaged(NiftiVersion.NIFTI2,
                        b -> b.putInt(V2_SIZEOF_HDR, size));
                IOException e = reject(image, "sizeof_hdr " + size);
                assertTrue(e.getMessage().contains("sizeof_hdr")
                                || e.getMessage().contains("magic"),
                        e.getMessage());
            }));
        }
        return tests;
    }

    // =====================================================================
    // Truncation, at each place a file has structure.
    // =====================================================================

    @TestFactory
    List<DynamicTest> aFileTruncatedAtAnyBoundaryIsRejected() throws IOException {
        List<NiftiExtension> extensions = List.of(
                NiftiExtension.ofText(NiftiEcode.COMMENT, "x".repeat(40)));
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, SHAPE);
        byte[] whole = NiftiBuilder.singleFile(h, extensions,
                NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));

        record Cut(String label, int length) {
        }
        List<Cut> cuts = List.of(
                new Cut("nothing at all", 0),
                new Cut("four bytes", 4),
                new Cut("mid-header", 300),
                new Cut("one byte short of the header", 539),
                new Cut("the header exactly, no extension marker", 540),
                new Cut("mid-marker", 542),
                new Cut("mid-extension prologue", 548),
                new Cut("mid-extension payload", 560),
                new Cut("the extensions exactly, no voxels", (int) h.version.headerSize + 4 + 48),
                new Cut("mid-voxels", whole.length - 10),
                new Cut("one byte short", whole.length - 1));

        List<DynamicTest> tests = new ArrayList<>();
        for (Cut cut : cuts) {
            tests.add(DynamicTest.dynamicTest("truncated to " + cut.label(), () -> {
                byte[] cropped = Arrays.copyOf(whole, Math.min(cut.length(), whole.length));
                reject(cropped, "a file truncated to " + cut.length() + " bytes");
            }));
        }
        return tests;
    }

    @Test
    void theWholeFileItselfStillReads() throws IOException {
        // The counterpart to the truncation cases: none of them is rejecting
        // something that was fine to begin with.
        List<NiftiExtension> extensions = List.of(
                NiftiExtension.ofText(NiftiEcode.COMMENT, "x".repeat(40)));
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, SHAPE);
        byte[] whole = NiftiBuilder.singleFile(h, extensions,
                NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));
        try (NiftiReader r = NiftiReader.open(whole)) {
            assertEquals(24, ((short[]) r.readAll()).length);
            assertEquals(1, r.extensions().size());
        }
    }

    // =====================================================================
    // The extension chain, reached through the reader.
    // =====================================================================

    @Test
    void aHostileEsizeIsRejectedThroughTheReader() throws IOException {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, SHAPE);
        byte[] whole = NiftiBuilder.singleFile(h,
                List.of(NiftiExtension.ofText(NiftiEcode.COMMENT, "x".repeat(40))),
                NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));

        for (int esize : new int[] {0, 8, 17, -16, 0x80000000, Integer.MAX_VALUE}) {
            byte[] image = whole.clone();
            ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(544, esize);
            reject(image, "an extension declaring esize " + esize);
        }
    }

    @Test
    void anExtensionRegionPastItsCeilingIsRejected() throws IOException {
        String saved = System.getProperty(Bounds.MAX_EXTENSION_BYTES_PROPERTY);
        try {
            System.setProperty(Bounds.MAX_EXTENSION_BYTES_PROPERTY, "64");
            NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, SHAPE);
            byte[] image = NiftiBuilder.singleFile(h,
                    List.of(NiftiExtension.ofText(NiftiEcode.COMMENT, "x".repeat(200))),
                    NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));
            IOException e = reject(image, "208 bytes of extensions under a 64-byte ceiling");
            assertTrue(e.getMessage().contains(Bounds.MAX_EXTENSION_BYTES_PROPERTY),
                    "the message says how to raise it: " + e.getMessage());
        } finally {
            if (saved == null) {
                System.clearProperty(Bounds.MAX_EXTENSION_BYTES_PROPERTY);
            } else {
                System.setProperty(Bounds.MAX_EXTENSION_BYTES_PROPERTY, saved);
            }
        }
    }

    // =====================================================================
    // What "rejected before allocating" actually means.
    // =====================================================================

    /**
     * The headers below each claim an image far larger than memory. Rejecting
     * them is not enough — rejecting them <em>after</em> asking for the array
     * would be an OutOfMemoryError on a smaller heap, or a multi-second stall
     * on a larger one. So this measures what the thread allocated while doing
     * it.
     *
     * <p>{@code getCurrentThreadAllocatedBytes} counts every heap allocation
     * the thread makes, so the assertion is direct rather than inferred from
     * timing or from {@code freeMemory}, which garbage collection makes
     * meaningless. Where the JVM does not offer it, the test skips rather than
     * pretending to have checked.</p>
     */
    @Test
    void nothingIsAllocatedFromANumberBeforeItIsChecked() throws IOException {
        com.sun.management.ThreadMXBean bean = threadBean();
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported(),
                "this JVM does not report per-thread allocation");

        record Case(String label, Consumer<ByteBuffer> damage) {
        }
        List<Case> cases = List.of(
                new Case("2^40 x 2^10 voxels", b -> {
                    b.putLong(V2_DIM, 2);
                    b.putLong(V2_DIM + 8, 1L << 40);
                    b.putLong(V2_DIM + 16, 1L << 10);
                }),
                new Case("a 2^31-voxel plane", b -> {
                    b.putLong(V2_DIM, 2);
                    b.putLong(V2_DIM + 8, 1L << 20);
                    b.putLong(V2_DIM + 16, 1L << 11);
                }),
                new Case("vox_offset near 2^62", b -> b.putLong(V2_VOX_OFFSET, 1L << 62)),
                new Case("bitpix 32767", b -> b.putShort(V2_BITPIX, (short) 32767)));

        for (Case c : cases) {
            byte[] image = damaged(NiftiVersion.NIFTI2, c.damage());
            reject(image, c.label());          // warm the paths up first

            long before = bean.getCurrentThreadAllocatedBytes();
            reject(image, c.label());
            long allocated = bean.getCurrentThreadAllocatedBytes() - before;

            assertTrue(allocated < 1 << 20,
                    c.label() + " allocated " + allocated
                            + " bytes before rejecting the header; nothing may be sized"
                            + " from a header number before it has been checked");
        }
    }

    /** A hostile extension chain must not size an array from its own esize either. */
    @Test
    void anEsizeIsNotAllocatedFromEither() throws IOException {
        com.sun.management.ThreadMXBean bean = threadBean();
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported(),
                "this JVM does not report per-thread allocation");

        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, SHAPE);
        byte[] whole = NiftiBuilder.singleFile(h,
                List.of(NiftiExtension.ofText(NiftiEcode.COMMENT, "x".repeat(40))),
                NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 24));
        byte[] image = whole.clone();
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(544, Integer.MAX_VALUE - 15);
        reject(image, "esize near 2^31");

        long before = bean.getCurrentThreadAllocatedBytes();
        reject(image, "esize near 2^31");
        long allocated = bean.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 1 << 20,
                "an esize of nearly 2^31 allocated " + allocated + " bytes");
    }

    private static com.sun.management.ThreadMXBean threadBean() {
        return ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean sun ? sun : null;
    }

    // =====================================================================

    @Test
    void aSlabRequestIsHeldToTheVoxelCeiling() throws IOException {
        String saved = System.getProperty(Bounds.MAX_VOXELS_PROPERTY);
        try {
            NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, 100, 100, 10);
            byte[] image = NiftiBuilder.singleFile(h, List.of(),
                    NiftiBuilder.ramp(TYPE, ByteOrder.LITTLE_ENDIAN, 100_000));

            System.setProperty(Bounds.MAX_VOXELS_PROPERTY, "10000");
            try (NiftiReader r = NiftiReader.open(image)) {
                assertEquals(10_000, ((short[]) r.readSlice(0)).length,
                        "a single slice is inside the ceiling");
                IOException e = assertThrows(IOException.class, r::readAll);
                assertTrue(e.getMessage().contains(Bounds.MAX_VOXELS_PROPERTY),
                        "and the whole image is not: " + e.getMessage());
            }
        } finally {
            if (saved == null) {
                System.clearProperty(Bounds.MAX_VOXELS_PROPERTY);
            } else {
                System.setProperty(Bounds.MAX_VOXELS_PROPERTY, saved);
            }
        }
    }

    @Test
    void aV1HeaderIsHeldToTheSameRulesAsAV2One() throws IOException {
        // The 16-bit fields cannot express most of the hostile values above,
        // which is a reason to check the ones they can rather than to assume
        // the version is safe.
        reject(damaged(NiftiVersion.NIFTI1, b -> b.putShort(V1_DIM, (short) 0)), "dim[0] of 0");
        reject(damaged(NiftiVersion.NIFTI1, b -> b.putShort(V1_DIM, (short) 8)), "dim[0] of 8");
        reject(damaged(NiftiVersion.NIFTI1,
                b -> b.putShort(V1_DIM + 2, (short) -4)), "a negative dim[1]");
        reject(damaged(NiftiVersion.NIFTI1,
                b -> b.putShort(V1_BITPIX, (short) 7)), "bitpix 7");
        reject(damaged(NiftiVersion.NIFTI1,
                b -> b.putFloat(V1_VOX_OFFSET, 1e30f)), "vox_offset of 1e30");

        // 32767^3 voxels: the largest a NIfTI-1 header can claim, and far more
        // than the file holds
        reject(damaged(NiftiVersion.NIFTI1, b -> {
            b.putShort(V1_DIM + 2, Short.MAX_VALUE);
            b.putShort(V1_DIM + 4, Short.MAX_VALUE);
            b.putShort(V1_DIM + 6, Short.MAX_VALUE);
        }), "32767 cubed");
    }
}
