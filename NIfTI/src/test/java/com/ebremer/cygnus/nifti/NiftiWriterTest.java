package com.ebremer.cygnus.nifti;

import com.ebremer.cygnus.nifti.io.VoxelCodec;
import com.ebremer.cygnus.nifti.testutil.NiftiBuilder;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The writer, and what comes back when the reader is pointed at what it wrote. */
class NiftiWriterTest {

    private static final long[] SHAPE = {5, 4, 3};
    private static final int VOXELS = 60;

    @TempDir
    Path dir;

    /** Every layout, named the way each is named. */
    private record Layout(String label, String fileName, NiftiWriter.Options options) {
    }

    private static List<Layout> layouts() {
        return List.of(
                new Layout("single .nii", "img.nii", NiftiWriter.Options.defaults()),
                new Layout("single .nii.gz", "img.nii.gz", NiftiWriter.Options.defaults()),
                new Layout("pair .hdr/.img", "img.hdr", NiftiWriter.Options.defaults()),
                new Layout("pair .hdr.gz/.img.gz", "img.hdr.gz", NiftiWriter.Options.defaults()));
    }

    // =====================================================================
    // The round-trip matrix.
    // =====================================================================

    @TestFactory
    List<DynamicTest> everyTypeVersionOrderAndLayoutRoundTrips() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NiftiDataType type : Arrays.stream(NiftiDataType.values())
                .filter(t -> t.supported).toList()) {
            for (NiftiVersion version : new NiftiVersion[] {
                    NiftiVersion.NIFTI1, NiftiVersion.NIFTI2}) {
                for (ByteOrder order : new ByteOrder[] {
                        ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                    tests.add(DynamicTest.dynamicTest(type + " " + version + " "
                            + (order == ByteOrder.LITTLE_ENDIAN ? "LE" : "BE"), () -> {
                        byte[] raw = NiftiBuilder.ramp(type, order, VOXELS);
                        Object voxels = VoxelCodec.decode(raw, 0, type, order, VOXELS);

                        for (Layout layout : layouts()) {
                            Path sub = Files.createDirectories(
                                    dir.resolve(type + "-" + version + "-" + order.toString()
                                            .charAt(0) + "-" + layout.label().hashCode()));
                            Path path = sub.resolve(layout.fileName());

                            NiftiHeader header = NiftiHeader.of(version, type, SHAPE);
                            header.byteOrder = order;
                            NiftiHeader written = NiftiWriter.write(path, header,
                                    List.of(), voxels, layout.options());

                            try (NiftiReader r = NiftiReader.open(path)) {
                                assertEquals(written, r.header(),
                                        layout.label() + ": the header read back differs");
                                assertSame(type, r.dataType(), layout.label());
                                assertArrayEquals(raw,
                                        r.readRawBytes(new long[3], SHAPE),
                                        layout.label() + ": the voxels are not bit-identical");
                            }
                        }
                    }));
                }
            }
        }
        return tests;
    }

    @Test
    void whatWasWrittenIsWhatTheHeaderSaysWasWritten() throws IOException {
        // sizeof_hdr, the magic and vox_offset are derived, so the file cannot
        // describe itself wrongly however the caller filled the header in.
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        header.bitpix = 0;                       // never set
        header.voxOffset = 0;                    // never set
        Path path = dir.resolve("derived.nii");
        NiftiHeader written = NiftiWriter.write(path, header, List.of(),
                new short[VOXELS]);

        assertEquals(16, written.bitpix, "bitpix comes from the datatype");
        assertEquals(352, written.voxOffset, "vox_offset comes from the extensions");
        assertTrue(written.singleFile);
        assertEquals(352 + VOXELS * 2L, Files.size(path));
    }

    // =====================================================================
    // vox_offset.
    // =====================================================================

    @Test
    void voxOffsetFollowsTheExtensions() throws IOException {
        record Case(int count, int payload, long expectedV1, long expectedV2) {
        }
        List<Case> cases = List.of(
                new Case(0, 0, 352, 544),
                new Case(1, 8, 352 + 16, 544 + 16),
                new Case(1, 200, 352 + 208, 544 + 208),
                new Case(3, 40, 352 + 3 * 48, 544 + 3 * 48));

        for (Case c : cases) {
            List<NiftiExtension> exts = new ArrayList<>();
            for (int i = 0; i < c.count(); i++) {
                exts.add(new NiftiExtension(NiftiEcode.COMMENT, new byte[c.payload()]));
            }
            for (NiftiVersion v : new NiftiVersion[] {
                    NiftiVersion.NIFTI1, NiftiVersion.NIFTI2}) {
                long want = v == NiftiVersion.NIFTI1 ? c.expectedV1() : c.expectedV2();
                Path path = dir.resolve("ext-" + v + "-" + c.count() + "-" + c.payload() + ".nii");
                NiftiHeader written = NiftiWriter.write(path,
                        NiftiHeader.of(v, NiftiDataType.UINT8, SHAPE), exts, new byte[VOXELS]);

                assertEquals(want, written.voxOffset, v + " with " + c.count() + " extensions");
                assertEquals(0, written.voxOffset % 16, "and it stays 16-aligned");
                assertEquals(want + VOXELS, Files.size(path));

                try (NiftiReader r = NiftiReader.open(path)) {
                    assertEquals(c.count(), r.extensions().size());
                    assertEquals(want, r.header().voxOffset);
                    assertArrayEquals(new byte[VOXELS], (byte[]) r.readAll());
                }
            }
        }
    }

    @Test
    void aLargerVoxOffsetIsPaddingAndIsHonoured() throws IOException {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE);
        header.voxOffset = 1024;
        Path path = dir.resolve("padded.nii");
        NiftiHeader written = NiftiWriter.write(path, header, List.of(), new byte[VOXELS]);

        assertEquals(1024, written.voxOffset);
        assertEquals(1024 + VOXELS, Files.size(path));
        try (NiftiReader r = NiftiReader.open(path)) {
            assertEquals(1024, r.header().voxOffset);
            assertArrayEquals(new byte[VOXELS], (byte[]) r.readAll());
        }
    }

    @Test
    void aVoxOffsetSmallerThanTheExtensionsNeedIsRefused() {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE);
        header.voxOffset = 352;                  // no room for the extension below
        IOException e = assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("clash.nii"), header,
                List.of(NiftiExtension.ofText(NiftiEcode.COMMENT, "too late")),
                new byte[VOXELS]));
        assertTrue(e.getMessage().contains("vox_offset"), e.getMessage());
        assertTrue(e.getMessage().contains("368"), e.getMessage());
    }

    // =====================================================================
    // Choosing the version.
    // =====================================================================

    @Test
    void aDimTooBigForV1UpgradesToV2() throws IOException {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8,
                40000, 2, 1);
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(header));

        Path path = dir.resolve("wide.nii");
        NiftiHeader written = NiftiWriter.write(path, header, List.of(), new byte[80000]);
        assertSame(NiftiVersion.NIFTI2, written.version, "NIfTI-1 cannot say 40000");
        assertEquals(544, written.voxOffset, "and the offset grew with the header");

        try (NiftiReader r = NiftiReader.open(path)) {
            assertSame(NiftiVersion.NIFTI2, r.header().version);
            assertEquals(40000, r.shape()[0]);
        }
    }

    @Test
    void aVoxOffsetPastFloatPrecisionUpgradesToV2() {
        // A float counts single bytes only to 2^24, then in twos, fours, and so
        // on -- so a 16-aligned offset survives all the way to 2^28, and it is
        // past there, or at any odd value above 2^24, that NIfTI-1 runs out.
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);

        header.voxOffset = 1 << 24;
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.smallestVersion(header),
                "2^24 is the last byte a float can count singly, and it can count that one");
        header.voxOffset = (1 << 24) + 16;
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.smallestVersion(header),
                "above 2^24 a float steps in twos, so an even offset is still exact");
        header.voxOffset = (1 << 28);
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.smallestVersion(header),
                "and it steps in sixteens up to 2^28, which 16-aligned offsets survive");

        header.voxOffset = (1 << 24) + 1;
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(header),
                "an odd offset past 2^24 cannot be said exactly");
        header.voxOffset = (1L << 28) + 16;
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(header),
                "past 2^28 a float steps in thirty-twos, so 16-alignment stops being enough");
        header.voxOffset = (1L << 40) + 16;
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(header));

        // ...and it is precision that runs out, not magnitude: a power of two
        // is exact at any size a float can reach, so a terabyte-in is fine if
        // that is where the offset happens to land.
        header.voxOffset = 1L << 40;
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.smallestVersion(header));
    }

    @Test
    void aFieldThatOverflowsAFloatUpgradesToV2() throws IOException {
        // cal_max is a float in NIfTI-1 and a double in NIfTI-2, so a finite
        // value past 3.4e38 is a reason to write the newer version.
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);
        header.calMax = 1e300;
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(header));

        Path path = dir.resolve("bigcal.nii");
        NiftiHeader written = NiftiWriter.write(path, header, List.of(), new byte[16]);
        assertSame(NiftiVersion.NIFTI2, written.version);
        assertEquals(544, written.voxOffset, "and the offset grew with the header");
        try (NiftiReader r = NiftiReader.open(path)) {
            assertEquals(1e300, r.header().calMax);
            assertArrayEquals(new byte[16], (byte[]) r.readAll());
        }
    }

    @Test
    void aPadOutToALargeVoxOffsetIsWrittenInBlocks() throws IOException {
        // A megabyte of padding, to be sure the gap is not written a byte at a
        // time and that what follows it is still where the header says.
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, 4, 4);
        header.voxOffset = 1 << 20;
        Path path = dir.resolve("padded-far.nii");
        NiftiWriter.write(path, header, List.of(), new byte[16]);
        assertEquals((1 << 20) + 16, Files.size(path));
        try (NiftiReader r = NiftiReader.open(path)) {
            assertEquals(1 << 20, r.header().voxOffset);
            assertArrayEquals(new byte[16], (byte[]) r.readAll());
        }
    }

    @Test
    void aHeaderThatFitsStaysWhereItWasPut() throws IOException {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.smallestVersion(header));
        assertSame(NiftiVersion.NIFTI1, NiftiWriter.write(dir.resolve("small.nii"),
                header, List.of(), new short[VOXELS]).version);

        // and one that asked for NIfTI-2 is not demoted to save space
        NiftiHeader two = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.INT16, SHAPE);
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.smallestVersion(two));
        assertSame(NiftiVersion.NIFTI2, NiftiWriter.write(dir.resolve("two.nii"),
                two, List.of(), new short[VOXELS]).version);
    }

    @Test
    void strictModeRefusesRatherThanUpgrading() {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8,
                40000, 2, 1);
        IOException e = assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("strict.nii"), header, List.of(), new byte[80000],
                NiftiWriter.Options.defaults().strict()));
        assertTrue(e.getMessage().contains("dim[1]"), e.getMessage());
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
        assertTrue(e.getMessage().contains("16-bit"), e.getMessage());
    }

    // =====================================================================
    // Layout and compression, chosen by name or by hand.
    // =====================================================================

    @Test
    void theNameChoosesTheLayoutAndTheCompression() throws IOException {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE);
        byte[] voxels = new byte[VOXELS];

        NiftiWriter.write(dir.resolve("a.nii"), header, List.of(), voxels);
        assertTrue(Files.exists(dir.resolve("a.nii")));
        assertTrue(NiftiReader.readHeader(dir.resolve("a.nii")).singleFile);

        NiftiWriter.write(dir.resolve("b.nii.gz"), header, List.of(), voxels);
        assertTrue(NiftiFiles.looksGzipped(Files.readAllBytes(dir.resolve("b.nii.gz"))),
                ".gz means gzipped");

        NiftiWriter.write(dir.resolve("c.hdr"), header, List.of(), voxels);
        assertTrue(Files.exists(dir.resolve("c.hdr")), "a .hdr means a pair");
        assertTrue(Files.exists(dir.resolve("c.img")));
        assertEquals(VOXELS, Files.size(dir.resolve("c.img")),
                "the .img holds the voxels and nothing else");

        NiftiWriter.write(dir.resolve("d.hdr.gz"), header, List.of(), voxels);
        assertTrue(Files.exists(dir.resolve("d.hdr.gz")));
        assertTrue(Files.exists(dir.resolve("d.img.gz")));
    }

    @Test
    void theOptionsOverrideTheName() throws IOException {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE);
        byte[] voxels = new byte[VOXELS];

        // a .nii written as a pair anyway
        NiftiWriter.write(dir.resolve("forced.nii"), header, List.of(), voxels,
                NiftiWriter.Options.defaults().layout(NiftiWriter.Options.Layout.PAIR));
        assertTrue(Files.exists(dir.resolve("forced.hdr")));
        assertTrue(Files.exists(dir.resolve("forced.img")));

        // a .nii.gz written uncompressed
        NiftiWriter.write(dir.resolve("plain.nii.gz"), header, List.of(), voxels,
                NiftiWriter.Options.defaults()
                        .compression(NiftiWriter.Options.Compression.NONE));
        assertTrue(!NiftiFiles.looksGzipped(Files.readAllBytes(dir.resolve("plain.nii.gz"))));
        try (NiftiReader r = NiftiReader.open(dir.resolve("plain.nii.gz"))) {
            assertEquals(VOXELS, ((byte[]) r.readAll()).length,
                    "and it still reads, since content decides");
        }

        // a .hdr written as one file
        NiftiWriter.write(dir.resolve("lone.hdr"), header, List.of(), voxels,
                NiftiWriter.Options.defaults()
                        .layout(NiftiWriter.Options.Layout.SINGLE_FILE));
        assertTrue(!Files.exists(dir.resolve("lone.img")));
        assertTrue(NiftiReader.readHeader(dir.resolve("lone.hdr")).singleFile);
    }

    // =====================================================================
    // Streaming.
    // =====================================================================

    @Test
    void aVolumeWrittenSliceBySliceIsByteIdenticalToOneWrittenWhole() throws IOException {
        NiftiDataType type = NiftiDataType.FLOAT32;
        byte[] raw = NiftiBuilder.ramp(type, ByteOrder.LITTLE_ENDIAN, VOXELS);
        float[] all = (float[]) VoxelCodec.decode(raw, 0, type, ByteOrder.LITTLE_ENDIAN, VOXELS);

        for (boolean gzip : new boolean[] {false, true}) {
            String suffix = gzip ? ".nii.gz" : ".nii";
            Path whole = dir.resolve("whole" + suffix);
            Path streamed = dir.resolve("streamed" + suffix);

            NiftiWriter.write(whole,
                    NiftiHeader.of(NiftiVersion.NIFTI1, type, SHAPE), List.of(), all);

            try (NiftiWriter w = NiftiWriter.create(streamed,
                    NiftiHeader.of(NiftiVersion.NIFTI1, type, SHAPE), List.of(),
                    NiftiWriter.Options.defaults())) {
                assertEquals(VOXELS, w.expectedVoxels());
                for (int k = 0; k < 3; k++) {          // one 5x4 plane at a time
                    w.write(all, k * 20, 20);
                    assertEquals((k + 1) * 20L, w.writtenVoxels());
                }
            }

            assertArrayEquals(Files.readAllBytes(whole), Files.readAllBytes(streamed),
                    (gzip ? "gzipped" : "plain") + ": streaming must produce the same file");
        }
    }

    @Test
    void aStreamedPairIsIdenticalToAWrittenOne() throws IOException {
        NiftiDataType type = NiftiDataType.INT16;
        short[] all = (short[]) VoxelCodec.decode(
                NiftiBuilder.ramp(type, ByteOrder.BIG_ENDIAN, VOXELS), 0, type,
                ByteOrder.BIG_ENDIAN, VOXELS);
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI2, type, SHAPE);
        header.byteOrder = ByteOrder.BIG_ENDIAN;

        NiftiWriter.write(dir.resolve("wholepair.hdr"), header, List.of(), all);
        try (NiftiWriter w = NiftiWriter.create(dir.resolve("streampair.hdr"), header,
                List.of(), NiftiWriter.Options.defaults())) {
            for (int k = 0; k < 3; k++) {
                w.write(all, k * 20, 20);
            }
        }
        assertArrayEquals(Files.readAllBytes(dir.resolve("wholepair.hdr")),
                Files.readAllBytes(dir.resolve("streampair.hdr")));
        assertArrayEquals(Files.readAllBytes(dir.resolve("wholepair.img")),
                Files.readAllBytes(dir.resolve("streampair.img")));
    }

    @Test
    void writingFewerVoxelsThanTheHeaderDescribesIsRefusedOnClose() throws IOException {
        NiftiWriter w = NiftiWriter.create(dir.resolve("short.nii"),
                NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE),
                List.of(), NiftiWriter.Options.defaults());
        w.write(new byte[10], 0, 10);
        IOException e = assertThrows(IOException.class, w::close);
        assertTrue(e.getMessage().contains("60"), e.getMessage());
        assertTrue(e.getMessage().contains("10"), e.getMessage());
        // the stream is closed even so, rather than leaked
        assertThrows(IOException.class, () -> w.write(new byte[1], 0, 1));
    }

    @Test
    void writingMoreVoxelsThanTheHeaderDescribesIsRefusedAtOnce() throws IOException {
        try (NiftiWriter w = NiftiWriter.create(dir.resolve("long.nii"),
                NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE),
                List.of(), NiftiWriter.Options.defaults())) {
            w.write(new byte[VOXELS], 0, VOXELS);
            IOException e = assertThrows(IOException.class,
                    () -> w.write(new byte[1], 0, 1));
            assertTrue(e.getMessage().contains("61"), e.getMessage());
        }
    }

    @Test
    void rawBytesGoStraightThroughIncludingForATypeThatIsNotDecoded() throws IOException {
        // FLOAT128 has no Java carrier, so its voxels can only be copied. That
        // is enough to rewrite such a file without losing it.
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI2, NiftiDataType.UINT8, 4, 3, 2);
        header.datatype = NiftiDataType.FLOAT128.code;
        header.bitpix = NiftiDataType.FLOAT128.bitpix;
        byte[] voxels = new byte[24 * 16];
        for (int i = 0; i < voxels.length; i++) {
            voxels[i] = (byte) (i * 7);
        }

        Path path = dir.resolve("longdouble.nii");
        NiftiWriter.writeRaw(path, header, List.of(), voxels,
                NiftiWriter.Options.defaults());
        try (NiftiReader r = NiftiReader.open(path)) {
            assertSame(NiftiDataType.FLOAT128, r.dataType());
            assertArrayEquals(voxels, r.readRawBytes(new long[3], new long[] {4, 3, 2}));
            assertThrows(IOException.class, r::readAll);
        }
    }

    @Test
    void aPartialRawWriteIsRefusedRatherThanPaddedOut() throws IOException {
        try (NiftiWriter w = NiftiWriter.create(dir.resolve("ragged.nii"),
                NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT32, SHAPE),
                List.of(), NiftiWriter.Options.defaults())) {
            IOException e = assertThrows(IOException.class,
                    () -> w.writeRaw(new byte[6], 0, 6));
            assertTrue(e.getMessage().contains("whole number"), e.getMessage());
            w.write(new int[VOXELS], 0, VOXELS);
        }
    }

    // =====================================================================
    // Headers that contradict themselves.
    // =====================================================================

    @Test
    void aSelfContradictingHeaderIsRefusedRatherThanRepaired() {
        NiftiHeader mismatched = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        mismatched.bitpix = 8;
        IOException e = assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("x1.nii"), mismatched, List.of(), new short[VOXELS]));
        assertTrue(e.getMessage().contains("cannot both be right"), e.getMessage());

        NiftiHeader negative = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        negative.dim[2] = -4;
        assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("x2.nii"), negative, List.of(), new short[VOXELS]));

        NiftiHeader ranked = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        ranked.dim[0] = 9;
        assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("x3.nii"), ranked, List.of(), new short[VOXELS]));

        NiftiHeader unknownType = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.INT16, SHAPE);
        unknownType.datatype = 999;
        assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("x4.nii"), unknownType, List.of(), new short[VOXELS]));
    }

    @Test
    void analyzeIsNotWritten() {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.UINT8, SHAPE);
        header.version = NiftiVersion.ANALYZE75;
        IOException e = assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("legacy.hdr"), header, List.of(), new byte[VOXELS]));
        assertTrue(e.getMessage().contains("read-only"), e.getMessage());
    }

    @Test
    void aWrongNumberOfComponentsIsRefused() {
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI1, NiftiDataType.RGB24, SHAPE);
        IOException e = assertThrows(IOException.class, () -> NiftiWriter.write(
                dir.resolve("odd.nii"), header, List.of(), new byte[VOXELS * 3 - 1]));
        assertTrue(e.getMessage().contains("whole number"), e.getMessage());
    }

    // =====================================================================
    // In memory.
    // =====================================================================

    @Test
    void toBytesAndToPairBytesMatchWhatGoesToDisk() throws IOException {
        NiftiDataType type = NiftiDataType.FLOAT64;
        double[] voxels = new double[VOXELS];
        for (int i = 0; i < VOXELS; i++) {
            voxels[i] = i * 0.5;
        }
        List<NiftiExtension> exts = List.of(
                NiftiExtension.ofText(NiftiEcode.COMMENT, "in memory"));
        NiftiHeader header = NiftiHeader.of(NiftiVersion.NIFTI2, type, SHAPE);

        byte[] single = NiftiWriter.toBytes(header, exts, voxels);
        NiftiWriter.write(dir.resolve("mem.nii"), header, exts, voxels);
        assertArrayEquals(Files.readAllBytes(dir.resolve("mem.nii")), single);

        byte[][] pair = NiftiWriter.toPairBytes(header, exts, voxels);
        NiftiWriter.write(dir.resolve("mem.hdr"), header, exts, voxels);
        assertArrayEquals(Files.readAllBytes(dir.resolve("mem.hdr")), pair[0]);
        assertArrayEquals(Files.readAllBytes(dir.resolve("mem.img")), pair[1]);

        try (NiftiReader r = NiftiReader.open(single)) {
            assertArrayEquals(voxels, (double[]) r.readAll());
            assertEquals("in memory", r.extensions().get(0).text());
        }
        try (NiftiReader r = NiftiReader.open(pair[0], pair[1])) {
            assertArrayEquals(voxels, (double[]) r.readAll());
        }
        assertNotEquals(0, single.length);
    }

    // =====================================================================
    // Everything the header carries, out and back.
    // =====================================================================

    @Test
    void geometryUnitsAndTextSurviveTheTrip() throws IOException {
        for (NiftiVersion version : new NiftiVersion[] {
                NiftiVersion.NIFTI1, NiftiVersion.NIFTI2}) {
            NiftiHeader header = NiftiHeader.of(version, NiftiDataType.INT16, SHAPE);
            NiftiAffine.ofRowMajor(
                    -2, 0, 0, 90,
                    0, 2, 0, -126,
                    0, 0, 2, -72,
                    0, 0, 0, 1).writeSformTo(header, NiftiXform.MNI_152);
            NiftiAffine.of(header).writeQformTo(header, NiftiXform.SCANNER_ANAT);
            header.setUnits(NiftiUnits.MM, NiftiUnits.MSEC);
            header.setDimInfo(1, 2, 3);
            header.intentCode = NiftiIntent.ZSCORE;
            header.intentName = "z";
            header.descrip = "written by cygnus";
            header.auxFile = "notes.txt";
            header.sliceCode = NiftiSlice.ALT_INC;
            header.sliceStart = 1;
            header.sliceEnd = 2;
            header.sliceDuration = 0.0625;
            header.toffset = 0.5;
            header.calMin = -100;
            header.calMax = 100;
            header.sclSlope = 0.25;
            header.sclInter = -8;

            Path path = dir.resolve("full-" + version + ".nii");
            NiftiHeader written = NiftiWriter.write(path, header, List.of(), new short[VOXELS]);

            try (NiftiReader r = NiftiReader.open(path)) {
                NiftiHeader back = r.header();
                assertEquals(written, back, version.toString());
                assertEquals("LAS", r.affine().orientation(), version.toString());
                assertArrayEquals(new double[] {90, -126, -72},
                        r.affine().apply(0, 0, 0), 1e-4);
                assertSame(NiftiAffine.Source.SFORM, r.affineSource());
                assertEquals(-1, back.qfac(), "the left-handed qform came back");
                assertEquals(NiftiUnits.MM, back.spaceUnits());
                assertEquals(NiftiUnits.MSEC, back.timeUnits());
                assertEquals(1, back.freqDim());
                assertEquals(3, back.sliceDim());
                assertEquals("written by cygnus", back.descrip);
                assertEquals("notes.txt", back.auxFile);
                assertEquals("z", back.intentName);
                assertEquals(NiftiIntent.ZSCORE, back.intentCode);
                assertEquals(0.25, back.sclSlope);
                assertTrue(back.scalingApplies());
            }
        }
    }
}
