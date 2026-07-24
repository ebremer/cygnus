package com.ebremer.cygnus.nifti;

import com.ebremer.cygnus.nifti.io.VoxelCodec;
import com.ebremer.cygnus.nifti.testutil.NiftiBuilder;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader, against volumes whose every voxel holds its own coordinates —
 * one decimal digit per dimension, {@code i} in the units place — so a read
 * that came back off by a stride says which stride on sight.
 */
class NiftiReaderTest {

    private static final NiftiDataType TYPE = NiftiDataType.INT32;

    /** A single-file image of the given shape, every voxel holding its coordinates. */
    private static byte[] coordinateImage(NiftiVersion version, ByteOrder order,
                                          long... shape) throws IOException {
        NiftiHeader h = NiftiHeader.of(version, TYPE, shape);
        h.byteOrder = order;
        return NiftiBuilder.singleFile(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, order, shape));
    }

    /** What the slab at {@code origin} of size {@code extent} should hold, in storage order. */
    private static int[] expected(long[] origin, long[] extent) {
        int voxels = 1;
        for (long e : extent) {
            voxels *= (int) e;
        }
        int[] out = new int[voxels];
        long[] cursor = new long[extent.length];
        for (int n = 0; n < voxels; n++) {
            long[] absolute = new long[extent.length];
            for (int d = 0; d < extent.length; d++) {
                absolute[d] = origin[d] + cursor[d];
            }
            out[n] = (int) NiftiBuilder.coordinateValue(absolute);
            for (int d = 0; d < extent.length; d++) {
                if (++cursor[d] < extent[d]) {
                    break;
                }
                cursor[d] = 0;
            }
        }
        return out;
    }

    private static long[] zeros(int n) {
        return new long[n];
    }

    // =====================================================================
    // The whole image, and arbitrary slabs of it.
    // =====================================================================

    @Test
    void everyVoxelOfAWholeVolumeIsWhereItSaysItIs() throws IOException {
        long[] shape = {5, 4, 3};
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, shape))) {
            assertArrayEquals(shape, r.shape());
            assertEquals(3, r.rank());
            assertEquals(60, r.voxelCount());
            assertEquals(3, r.sliceCount());
            assertEquals(1, r.volumeCount());
            assertSame(TYPE, r.dataType());
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @TestFactory
    List<DynamicTest> slabsAtEveryCornerAndSizeReadCorrectly() throws IOException {
        long[] shape = {5, 4, 3, 2};
        byte[] image = coordinateImage(NiftiVersion.NIFTI2, ByteOrder.BIG_ENDIAN, shape);

        record Slab(String label, long[] origin, long[] extent) {
        }
        List<Slab> slabs = List.of(
                new Slab("everything", zeros(4), shape),
                new Slab("one voxel at the origin", zeros(4), new long[] {1, 1, 1, 1}),
                new Slab("one voxel at the far corner",
                        new long[] {4, 3, 2, 1}, new long[] {1, 1, 1, 1}),
                new Slab("one row", zeros(4), new long[] {5, 1, 1, 1}),
                new Slab("a partial row", new long[] {1, 0, 0, 0}, new long[] {3, 1, 1, 1}),
                new Slab("one plane", zeros(4), new long[] {5, 4, 1, 1}),
                new Slab("one volume", zeros(4), new long[] {5, 4, 3, 1}),
                new Slab("the second volume", new long[] {0, 0, 0, 1}, new long[] {5, 4, 3, 1}),
                new Slab("a box in the middle",
                        new long[] {1, 1, 1, 0}, new long[] {3, 2, 2, 2}),
                new Slab("a column through k",
                        new long[] {2, 2, 0, 1}, new long[] {1, 1, 3, 1}),
                new Slab("a column through t",
                        new long[] {2, 2, 2, 0}, new long[] {1, 1, 1, 2}),
                new Slab("nothing at all", zeros(4), new long[] {0, 0, 0, 0}),
                new Slab("an empty extent in one dimension only",
                        zeros(4), new long[] {5, 0, 3, 2}));

        List<DynamicTest> tests = new ArrayList<>();
        for (Slab s : slabs) {
            tests.add(DynamicTest.dynamicTest(s.label(), () -> {
                try (NiftiReader r = NiftiReader.open(image)) {
                    assertArrayEquals(expected(s.origin(), s.extent()),
                            (int[]) r.read(s.origin(), s.extent()), s.label());
                }
            }));
        }
        return tests;
    }

    @Test
    void aPartialReadAgreesWithTheWholeOne() throws IOException {
        // The contiguous fast path and the stepped one are different code;
        // reading a volume both ways must give the same voxels.
        long[] shape = {7, 5, 4, 3};
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, shape))) {
            int[] whole = (int[]) r.readAll();
            for (int t = 0; t < 3; t++) {
                int[] volume = (int[]) r.readVolume(t);
                assertArrayEquals(Arrays.copyOfRange(whole, t * 140, (t + 1) * 140), volume,
                        "volume " + t);
            }
            // and one voxel at a time, the slowest path there is
            for (int k = 0; k < 4; k++) {
                for (int j = 0; j < 5; j++) {
                    for (int i = 0; i < 7; i++) {
                        int[] one = (int[]) r.read(new long[] {i, j, k, 2},
                                new long[] {1, 1, 1, 1});
                        assertEquals(whole[2 * 140 + k * 35 + j * 7 + i], one[0],
                                "voxel (" + i + "," + j + "," + k + ",2)");
                    }
                }
            }
        }
    }

    // =====================================================================
    // Slices and volumes by index.
    // =====================================================================

    @Test
    void slicesAreNumberedInStorageOrder() throws IOException {
        long[] shape = {4, 3, 2, 3};      // 2 slices per volume, 3 volumes: 6 planes
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, shape))) {
            assertEquals(6, r.sliceCount());
            assertEquals(3, r.volumeCount());

            long n = 0;
            for (int t = 0; t < 3; t++) {
                for (int k = 0; k < 2; k++, n++) {
                    assertArrayEquals(new long[] {0, 0, k, t}, r.sliceOrigin(n),
                            "slice " + n + " is k=" + k + ", t=" + t);
                    assertEquals(n, r.sliceIndexOf(k, t), "and back again");
                    assertArrayEquals(
                            expected(new long[] {0, 0, k, t}, new long[] {4, 3, 1, 1}),
                            (int[]) r.readSlice(n), "slice " + n);
                }
            }
            assertArrayEquals(new long[] {4, 3, 1, 1}, r.sliceExtent());
        }
    }

    @Test
    void volumesAreNumberedOverDimensionsFourAndUp() throws IOException {
        long[] shape = {3, 3, 2, 2, 2};   // 4 volumes across dims 4 and 5
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, shape))) {
            assertEquals(4, r.volumeCount());
            assertEquals(8, r.sliceCount());
            assertArrayEquals(new long[] {0, 0, 0, 1, 0}, r.volumeOrigin(1));
            assertArrayEquals(new long[] {0, 0, 0, 0, 1}, r.volumeOrigin(2));
            assertArrayEquals(new long[] {3, 3, 2, 1, 1}, r.volumeExtent());
            assertArrayEquals(
                    expected(new long[] {0, 0, 0, 1, 1}, new long[] {3, 3, 2, 1, 1}),
                    (int[]) r.readVolume(3));
        }
    }

    @Test
    void anOutOfRangeSliceOrVolumeIsRefused() throws IOException {
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, 4, 4, 3))) {
            assertThrows(IndexOutOfBoundsException.class, () -> r.sliceOrigin(3));
            assertThrows(IndexOutOfBoundsException.class, () -> r.sliceOrigin(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> r.readVolume(1));
        }
    }

    // =====================================================================
    // Every rank from one to seven.
    // =====================================================================

    @TestFactory
    List<DynamicTest> everyRankFromOneToSevenReads() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int rank = 1; rank <= 7; rank++) {
            int n = rank;
            tests.add(DynamicTest.dynamicTest(rank + "-dimensional", () -> {
                long[] shape = new long[n];
                Arrays.fill(shape, 2);
                shape[0] = 3;
                try (NiftiReader r = NiftiReader.open(
                        coordinateImage(NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, shape))) {
                    assertEquals(n, r.rank());
                    assertArrayEquals(expected(zeros(n), shape), (int[]) r.readAll());

                    // and a slab that steps every dimension there is
                    long[] origin = new long[n];
                    long[] extent = new long[n];
                    Arrays.fill(extent, 1);
                    extent[0] = 2;
                    for (int d = 1; d < n; d++) {
                        origin[d] = 1;
                    }
                    assertArrayEquals(expected(origin, extent), (int[]) r.read(origin, extent));
                }
            }));
        }
        return tests;
    }

    @Test
    void dimensionsPastDimZeroAreIgnoredAndAZeroCountsAsOne() throws IOException {
        // dim[0] says 3, but dim[4..7] hold leftovers; and dim[3] is the zero
        // some writers leave for a dimension they declared and did not use.
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, 4, 3, 2);
        h.dim[4] = 99;
        h.dim[5] = 99;
        byte[] image = NiftiBuilder.singleFile(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, 4, 3, 2));
        try (NiftiReader r = NiftiReader.open(image)) {
            assertEquals(3, r.rank());
            assertEquals(24, r.voxelCount(), "dim[4] and dim[5] are past dim[0]");
            assertArrayEquals(expected(zeros(3), new long[] {4, 3, 2}), (int[]) r.readAll());
        }

        NiftiHeader zeroed = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, 4, 3, 0);
        byte[] withZero = NiftiBuilder.singleFile(zeroed, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, 4, 3, 1));
        try (NiftiReader r = NiftiReader.open(withZero)) {
            assertEquals(12, r.voxelCount(), "a declared but zero extent counts as one");
            assertArrayEquals(new long[] {4, 3, 1}, r.shape());
        }
    }

    // =====================================================================
    // Slabs that are not there.
    // =====================================================================

    @Test
    void aSlabOutsideTheImageIsRefusedRatherThanRead() throws IOException {
        try (NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, 5, 4, 3))) {
            assertThrows(IOException.class,
                    () -> r.read(new long[] {0, 0, 0}, new long[] {6, 4, 3}));
            assertThrows(IOException.class,
                    () -> r.read(new long[] {5, 0, 0}, new long[] {1, 4, 3}));
            assertThrows(IOException.class,
                    () -> r.read(new long[] {-1, 0, 0}, new long[] {1, 1, 1}));
            assertThrows(IOException.class,
                    () -> r.read(new long[] {0, 0, 0}, new long[] {1, 1, -1}));
            assertThrows(IOException.class,
                    () -> r.read(new long[] {0, 0, 0}, new long[] {1, 1, 1, 1}),
                    "a slab must have as many dimensions as the image");
            assertThrows(IOException.class,
                    () -> r.read(new long[] {0, 0}, new long[] {1, 1}));

            // the last voxel is still readable, which is what makes the above
            // a bound rather than an off-by-one
            assertArrayEquals(new int[] {(int) NiftiBuilder.coordinateValue(4, 3, 2)},
                    (int[]) r.read(new long[] {4, 3, 2}, new long[] {1, 1, 1}));
        }
    }

    @Test
    void aClosedReaderReadsNothing() throws IOException {
        NiftiReader r = NiftiReader.open(
                coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, 4, 4, 2));
        r.readAll();
        r.close();
        IOException e = assertThrows(IOException.class, r::readAll);
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
        r.close();      // and closing twice is not an error
    }

    // =====================================================================
    // Raw bytes and scaled values.
    // =====================================================================

    @Test
    void rawBytesAreTheFilesOwnBytesInItsOwnOrder() throws IOException {
        long[] shape = {4, 3, 2};
        for (ByteOrder order : new ByteOrder[] {
                ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] voxels = NiftiBuilder.coordinateVoxels(TYPE, order, shape);
            NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, shape);
            h.byteOrder = order;
            try (NiftiReader r = NiftiReader.open(
                    NiftiBuilder.singleFile(h, List.of(), voxels))) {
                assertArrayEquals(voxels, r.readRawBytes(zeros(3), shape), order.toString());
                assertArrayEquals(Arrays.copyOfRange(voxels, 0, 48),
                        r.readSliceRawBytes(0), "one plane of raw bytes");
            }
        }
    }

    @Test
    void scaledValuesApplyTheHeadersSlopeAndIntercept() throws IOException {
        long[] shape = {4, 3, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        h.sclSlope = 0.5;
        h.sclInter = -3;
        byte[] image = NiftiBuilder.singleFile(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));

        try (NiftiReader r = NiftiReader.open(image)) {
            int[] raw = (int[]) r.readAll();
            double[] scaled = r.readScaled(zeros(3), shape);
            assertEquals(raw.length, scaled.length);
            for (int i = 0; i < raw.length; i++) {
                assertEquals(0.5 * raw[i] - 3, scaled[i], 1e-12, "voxel " + i);
            }
            assertArrayEquals(Arrays.copyOf(scaled, 12), r.readSliceScaled(0), 1e-12);
        }
    }

    // =====================================================================
    // The layouts, at file level.
    // =====================================================================

    @Test
    void aSingleFileOnDiskReadsTheSameAsInMemory(@TempDir Path dir) throws IOException {
        long[] shape = {5, 4, 3};
        byte[] image = coordinateImage(NiftiVersion.NIFTI1, ByteOrder.LITTLE_ENDIAN, shape);
        Path file = NiftiBuilder.write(dir.resolve("brain.nii"), image);
        try (NiftiReader r = NiftiReader.open(file)) {
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @Test
    void aGzippedSingleFileReads(@TempDir Path dir) throws IOException {
        long[] shape = {5, 4, 3};
        byte[] image = coordinateImage(NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, shape);
        Path file = NiftiBuilder.write(dir.resolve("brain.nii.gz"), NiftiBuilder.gzip(image));
        try (NiftiReader r = NiftiReader.open(file)) {
            assertEquals(NiftiVersion.NIFTI2, r.header().version);
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @Test
    void aPairReadsFromEitherHalfOfIt(@TempDir Path dir) throws IOException {
        long[] shape = {5, 4, 3};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        Path hdr = NiftiBuilder.write(dir.resolve("brain.hdr"), halves[0]);
        Path img = NiftiBuilder.write(dir.resolve("brain.img"), halves[1]);

        for (Path entry : new Path[] {hdr, img}) {
            try (NiftiReader r = NiftiReader.open(entry)) {
                assertTrue(!r.header().singleFile, "opened by " + entry.getFileName());
                assertEquals(0, r.header().voxOffset);
                assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll(),
                        "opened by " + entry.getFileName());
            }
        }
    }

    @Test
    void aGzippedPairReads(@TempDir Path dir) throws IOException {
        long[] shape = {4, 4, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        NiftiBuilder.write(dir.resolve("brain.hdr.gz"), NiftiBuilder.gzip(halves[0]));
        NiftiBuilder.write(dir.resolve("brain.img.gz"), NiftiBuilder.gzip(halves[1]));

        try (NiftiReader r = NiftiReader.open(dir.resolve("brain.hdr.gz"))) {
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @Test
    void aPairWithOnlyOneHalfCompressedStillReads(@TempDir Path dir) throws IOException {
        long[] shape = {4, 4, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        Path hdr = NiftiBuilder.write(dir.resolve("brain.hdr"), halves[0]);
        NiftiBuilder.write(dir.resolve("brain.img.gz"), NiftiBuilder.gzip(halves[1]));

        try (NiftiReader r = NiftiReader.open(hdr)) {
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @Test
    void aHeaderWithoutItsImageSaysSo(@TempDir Path dir) throws IOException {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, 4, 4, 2);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, 4, 4, 2));
        Path hdr = NiftiBuilder.write(dir.resolve("lonely.hdr"), halves[0]);
        IOException e = assertThrows(IOException.class, () -> NiftiReader.open(hdr));
        assertTrue(e.getMessage().contains("lonely.img"), e.getMessage());
    }

    @Test
    void anImageWithoutItsHeaderSaysSo(@TempDir Path dir) throws IOException {
        Path img = NiftiBuilder.write(dir.resolve("orphan.img"), new byte[128]);
        IOException e = assertThrows(IOException.class, () -> NiftiReader.open(img));
        assertTrue(e.getMessage().contains("orphan.hdr"), e.getMessage());
        assertTrue(e.getMessage().contains("raw voxels"), e.getMessage());
    }

    @Test
    void aPairsBytesInMemoryReadTheSameWay() throws IOException {
        long[] shape = {4, 3, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        try (NiftiReader r = NiftiReader.open(halves[0], halves[1])) {
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
        IOException e = assertThrows(IOException.class, () -> NiftiReader.open(halves[0]));
        assertTrue(e.getMessage().contains("separate file"), e.getMessage());
    }

    // =====================================================================
    // ANALYZE 7.5.
    // =====================================================================

    @Test
    void anAnalyzePairReadsWithoutInventingGeometry(@TempDir Path dir) throws IOException {
        short[] dim = {3, 4, 3, 2, 0, 0, 0, 0};
        byte[] hdr = NiftiBuilder.analyzeHeader(ByteOrder.LITTLE_ENDIAN,
                TYPE.code, TYPE.bitpix, dim, new float[] {0, 1.5f, 1.5f, 3, 0, 0, 0, 0});
        byte[] img = NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, 4, 3, 2);
        NiftiBuilder.write(dir.resolve("legacy.hdr"), hdr);
        NiftiBuilder.write(dir.resolve("legacy.img"), img);

        try (NiftiReader r = NiftiReader.open(dir.resolve("legacy.hdr"))) {
            assertEquals(NiftiVersion.ANALYZE75, r.header().version);
            assertArrayEquals(new long[] {4, 3, 2}, r.shape());
            assertArrayEquals(expected(zeros(3), new long[] {4, 3, 2}), (int[]) r.readAll());

            assertSame(NiftiAffine.Source.PIXDIM, r.affineSource(),
                    "ANALYZE has no qform or sform, and none is invented");
            assertEquals(0, r.header().qformCode);
            assertEquals(1.5, r.affine().scale(0), 1e-9, "pixdim is what there is");
            assertEquals(3, r.affine().scale(2), 1e-9);
        }
    }

    // =====================================================================
    // Extensions, geometry, and the cheap header path.
    // =====================================================================

    @Test
    void extensionsComeBackWithTheImage(@TempDir Path dir) throws IOException {
        List<NiftiExtension> exts = List.of(
                NiftiExtension.ofText(NiftiEcode.COMMENT, "acquired on a Tuesday"),
                new NiftiExtension(NiftiEcode.CIFTI, "<CIFTI/>".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));
        long[] shape = {4, 3, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, TYPE, shape);
        byte[] image = NiftiBuilder.singleFile(h, exts,
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));

        for (NiftiReader r : new NiftiReader[] {
                NiftiReader.open(image),
                NiftiReader.open(NiftiBuilder.write(dir.resolve("x.nii.gz"),
                        NiftiBuilder.gzip(image)))}) {
            try (r) {
                assertEquals(2, r.extensions().size());
                assertEquals("acquired on a Tuesday", r.extensions().get(0).text());
                assertEquals(NiftiEcode.CIFTI, r.extensions().get(1).ecode());
                assertTrue(r.header().voxOffset > 544, "the voxels come after the chain");
                assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll(),
                        "and the voxels are still found");
            }
        }
    }

    @Test
    void extensionsOnAPairRunToTheEndOfTheHeaderFile(@TempDir Path dir) throws IOException {
        List<NiftiExtension> exts = List.of(
                NiftiExtension.ofText(NiftiEcode.COMMENT, "in the .hdr, not the .img"));
        long[] shape = {4, 3, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, exts,
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        NiftiBuilder.write(dir.resolve("p.hdr"), halves[0]);
        NiftiBuilder.write(dir.resolve("p.img"), halves[1]);

        try (NiftiReader r = NiftiReader.open(dir.resolve("p.hdr"))) {
            assertEquals(1, r.extensions().size());
            assertEquals("in the .hdr, not the .img", r.extensions().get(0).text());
            assertArrayEquals(expected(zeros(3), shape), (int[]) r.readAll());
        }
    }

    @Test
    void theGeometryIsTheOneTheHeaderChose() throws IOException {
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, 4, 3, 2);
        h.sformCode = NiftiXform.MNI_152;
        h.srowX = new double[] {-2, 0, 0, 90};
        h.srowY = new double[] {0, 2, 0, -126};
        h.srowZ = new double[] {0, 0, 2, -72};
        byte[] image = NiftiBuilder.singleFile(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, 4, 3, 2));
        try (NiftiReader r = NiftiReader.open(image)) {
            assertSame(NiftiAffine.Source.SFORM, r.affineSource());
            assertEquals("LAS", r.affine().orientation());
            assertArrayEquals(new double[] {90, -126, -72}, r.affine().apply(0, 0, 0), 1e-9);
        }
    }

    @Test
    void readHeaderTakesTheHeaderAndNothingElse(@TempDir Path dir) throws IOException {
        long[] shape = {16, 16, 16};
        byte[] image = coordinateImage(NiftiVersion.NIFTI2, ByteOrder.LITTLE_ENDIAN, shape);
        Path plain = NiftiBuilder.write(dir.resolve("h.nii"), image);
        Path gzipped = NiftiBuilder.write(dir.resolve("h.nii.gz"), NiftiBuilder.gzip(image));

        for (Path file : new Path[] {plain, gzipped}) {
            NiftiHeader h = NiftiReader.readHeader(file);
            assertEquals(NiftiVersion.NIFTI2, h.version, file.getFileName().toString());
            assertArrayEquals(new long[] {3, 16, 16, 16, 1, 1, 1, 1}, h.dim);
            assertEquals(TYPE.code, h.datatype);
        }

        // and it works through the .img of a pair, which has no header of its own
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI1, TYPE, shape);
        byte[][] halves = NiftiBuilder.pair(h, List.of(),
                NiftiBuilder.coordinateVoxels(TYPE, ByteOrder.LITTLE_ENDIAN, shape));
        NiftiBuilder.write(dir.resolve("pair.hdr"), halves[0]);
        Path img = NiftiBuilder.write(dir.resolve("pair.img"), halves[1]);
        assertEquals(NiftiVersion.NIFTI1, NiftiReader.readHeader(img).version);
    }

    // =====================================================================
    // Types other than the coordinate volume's.
    // =====================================================================

    @TestFactory
    List<DynamicTest> everyDecodableTypeReadsThroughTheReader() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : Arrays.stream(NiftiDataType.values())
                .filter(t -> t.supported).toList()) {
            tests.add(DynamicTest.dynamicTest(type.toString(), () -> {
                long[] shape = {4, 3, 2};
                int voxels = 24;
                byte[] raw = NiftiBuilder.ramp(type, ByteOrder.BIG_ENDIAN, voxels);
                NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, type, shape);
                h.byteOrder = ByteOrder.BIG_ENDIAN;

                try (NiftiReader r = NiftiReader.open(
                        NiftiBuilder.singleFile(h, List.of(), raw))) {
                    assertSame(type, r.dataType());
                    assertArrayEquals(raw, r.readRawBytes(zeros(3), shape), "raw bytes");

                    Object all = r.readAll();
                    assertEquals(voxels * type.components,
                            java.lang.reflect.Array.getLength(all), "components");
                    Object expectedArray = VoxelCodec.decode(raw, 0, type,
                            ByteOrder.BIG_ENDIAN, voxels);
                    assertEquals(expectedArray.getClass(), all.getClass());
                    for (int i = 0; i < voxels * type.components; i++) {
                        assertEquals(VoxelCodec.toDouble(expectedArray, i, type),
                                VoxelCodec.toDouble(all, i, type), "component " + i);
                    }
                }
            }));
        }
        return tests;
    }

    @Test
    void anUndecodableTypeStillGivesItsHeaderItsGeometryAndItsBytes() throws IOException {
        long[] shape = {4, 3, 2};
        NiftiHeader h = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.UINT8, shape);
        h.datatype = NiftiDataType.FLOAT128.code;
        h.bitpix = NiftiDataType.FLOAT128.bitpix;
        h.qformCode = NiftiXform.SCANNER_ANAT;
        byte[] voxels = new byte[24 * 16];
        Arrays.fill(voxels, (byte) 0x5A);

        try (NiftiReader r = NiftiReader.open(
                NiftiBuilder.singleFile(h, List.of(), voxels))) {
            assertSame(NiftiDataType.FLOAT128, r.dataType());
            assertArrayEquals(shape, r.shape());
            assertSame(NiftiAffine.Source.QFORM, r.affineSource());
            assertArrayEquals(voxels, r.readRawBytes(zeros(3), shape),
                    "the bytes are still reachable");

            IOException e = assertThrows(IOException.class, r::readAll);
            assertTrue(e.getMessage().contains("FLOAT128"), e.getMessage());
            assertTrue(e.getMessage().contains("long double"), e.getMessage());
        }
    }
}
