package com.ebremer.cygnus.nifti;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extension chain: the marker, the {@code esize} rules, and what survives a round-trip. */
class NiftiExtensionsTest {

    private static final int V1_MARKER = 348;
    private static final int V2_MARKER = 540;

    /** Encodes then decodes over the same range, the way a file does. */
    private static List<NiftiExtension> roundTrip(List<NiftiExtension> in, ByteOrder order)
            throws IOException {
        byte[] chain = NiftiExtensions.encode(in, order);
        byte[] file = new byte[V1_MARKER + chain.length];
        System.arraycopy(chain, 0, file, V1_MARKER, chain.length);
        return NiftiExtensions.decode(file, V1_MARKER, file.length, order);
    }

    @Test
    void noExtensionsIsFourZeroBytes() throws IOException {
        byte[] marker = NiftiExtensions.encode(List.of(), ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new byte[4], marker);
        assertEquals(4, NiftiExtensions.encodedLength(List.of()));
        assertEquals(352, NiftiExtensions.voxOffsetFor(NiftiVersion.NIFTI1, List.of()));
        assertEquals(544, NiftiExtensions.voxOffsetFor(NiftiVersion.NIFTI2, List.of()));
    }

    @Test
    void aZeroMarkerMeansNoExtensionsHoweverManyBytesFollow() throws IOException {
        byte[] file = new byte[V1_MARKER + 4 + 64];
        // marker is zero, but there are 64 further bytes that look like an extension
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(352, 64).putInt(356, 6);
        assertTrue(NiftiExtensions.decode(file, V1_MARKER, file.length,
                ByteOrder.LITTLE_ENDIAN).isEmpty());
    }

    @Test
    void aHeaderWithNoRoomForAMarkerHasNoExtensions() throws IOException {
        // A .hdr of exactly 348 bytes carries no marker, and that is legal.
        assertTrue(NiftiExtensions.decode(new byte[348], V1_MARKER, 348,
                ByteOrder.LITTLE_ENDIAN).isEmpty());
        assertTrue(NiftiExtensions.decode(new byte[350], V1_MARKER, 350,
                ByteOrder.LITTLE_ENDIAN).isEmpty());
    }

    @Test
    void aMarkerWithNothingBehindItIsEmptyNotBroken() throws IOException {
        byte[] file = new byte[V1_MARKER + 4];
        file[V1_MARKER] = 1;
        assertTrue(NiftiExtensions.decode(file, V1_MARKER, file.length,
                ByteOrder.LITTLE_ENDIAN).isEmpty());
    }

    @Test
    void oneExtensionRoundTrips() throws IOException {
        NiftiExtension e = NiftiExtension.ofText(NiftiEcode.COMMENT, "hello, cygnus");
        List<NiftiExtension> back = roundTrip(List.of(e), ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, back.size());
        assertEquals(NiftiEcode.COMMENT, back.get(0).ecode());
        assertEquals("hello, cygnus", back.get(0).text());
    }

    @Test
    void manyExtensionsRoundTripInOrder() throws IOException {
        List<NiftiExtension> in = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            byte[] payload = new byte[i * 7 + 1];
            Arrays.fill(payload, (byte) (i + 1));
            in.add(new NiftiExtension(i * 2, payload));
        }
        for (ByteOrder order : new ByteOrder[] {
                ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            List<NiftiExtension> back = roundTrip(in, order);
            assertEquals(in.size(), back.size(), "count, " + order);
            for (int i = 0; i < in.size(); i++) {
                assertEquals(in.get(i).ecode(), back.get(i).ecode(), "ecode " + i);
                // the payload comes back padded to esize, and starts with what went in
                assertTrue(back.get(i).data().length >= in.get(i).data().length);
                assertArrayEquals(in.get(i).data(),
                        Arrays.copyOf(back.get(i).data(), in.get(i).data().length),
                        "payload " + i);
            }
        }
    }

    @Test
    void esizeIsAlwaysAPositiveMultipleOfSixteen() {
        for (int len = 0; len < 200; len++) {
            NiftiExtension e = new NiftiExtension(NiftiEcode.IGNORE, new byte[len]);
            int esize = e.esize();
            assertEquals(0, esize % 16, "esize for a " + len + "-byte payload");
            assertTrue(esize >= 16, "esize for a " + len + "-byte payload");
            assertTrue(esize >= len + 8, "esize must cover the payload and prologue");
            assertTrue(esize < len + 8 + 16, "esize must not overshoot by a whole block");
            assertEquals(esize - 8 - len, e.padding());
        }
    }

    @Test
    void aPayloadFromAFileRoundTripsWithoutGrowing() throws IOException {
        // esize - 8 is always 8 mod 16, and such a payload re-encodes to the
        // same esize -- so reading and writing a file adds no padding at all.
        for (int esize = 16; esize <= 256; esize += 16) {
            NiftiExtension e = new NiftiExtension(NiftiEcode.AFNI, new byte[esize - 8]);
            assertEquals(esize, e.esize(), "esize " + esize + " is a fixed point");
            assertEquals(0, e.padding());
        }
    }

    @Test
    void voxOffsetStaysSixteenAlignedHoweverManyExtensions() {
        List<NiftiExtension> exts = new ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < 30; i++) {
            exts.add(new NiftiExtension(NiftiEcode.COMMENT, new byte[rnd.nextInt(500)]));
            for (NiftiVersion v : new NiftiVersion[] {
                    NiftiVersion.NIFTI1, NiftiVersion.NIFTI2}) {
                long off = NiftiExtensions.voxOffsetFor(v, exts);
                assertEquals(0, off % 16, v + " vox_offset stays 16-aligned");
                assertTrue(off >= v.minVoxOffset, v + " vox_offset is at least the minimum");
            }
        }
    }

    @Test
    void aCiftiXmlBlobSurvivesByteForByte() throws IOException {
        // A CIFTI-2 file is a NIfTI-2 volume plus this. It is carried, not parsed.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CIFTI Version="2">
                  <Matrix>
                    <MatrixIndicesMap AppliesToMatrixDimension="0"
                                      IndicesMapToDataType="CIFTI_INDEX_TYPE_BRAIN_MODELS">
                      <BrainModel IndexOffset="0" IndexCount="29696"
                                  ModelType="CIFTI_MODEL_TYPE_SURFACE"
                                  BrainStructure="CIFTI_STRUCTURE_CORTEX_LEFT"/>
                    </MatrixIndicesMap>
                  </Matrix>
                </CIFTI>
                """;
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);
        NiftiExtension in = new NiftiExtension(NiftiEcode.CIFTI, payload);

        byte[] chain = NiftiExtensions.encode(List.of(in), ByteOrder.LITTLE_ENDIAN);
        byte[] file = new byte[V2_MARKER + chain.length];
        System.arraycopy(chain, 0, file, V2_MARKER, chain.length);
        List<NiftiExtension> back = NiftiExtensions.decode(file, V2_MARKER, file.length,
                ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, back.size());
        assertEquals(NiftiEcode.CIFTI, back.get(0).ecode());
        assertArrayEquals(payload, Arrays.copyOf(back.get(0).data(), payload.length));
        assertEquals(xml, new String(back.get(0).data(), 0, payload.length,
                StandardCharsets.UTF_8));

        // and re-encoding it yields the identical chain
        assertArrayEquals(chain, NiftiExtensions.encode(back, ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void anUnregisteredEcodeIsCarriedNotRejected() throws IOException {
        NiftiExtension odd = new NiftiExtension(31337, new byte[] {1, 2, 3, 4});
        List<NiftiExtension> back = roundTrip(List.of(odd), ByteOrder.BIG_ENDIAN);
        assertEquals(31337, back.get(0).ecode());
        assertFalse(NiftiEcode.isRegistered(31337));
        assertEquals("ecode 31337", NiftiEcode.name(31337));
        assertTrue(NiftiEcode.isRegistered(NiftiEcode.CIFTI));
        assertEquals("CIFTI", NiftiEcode.name(NiftiEcode.CIFTI));
    }

    @Test
    void trailingSlackEndsTheChainRatherThanFailing() throws IOException {
        // A writer that rounded vox_offset up past its extensions leaves fewer
        // than 16 bytes of nothing. That is slack, not a malformed chain.
        NiftiExtension e = NiftiExtension.ofText(NiftiEcode.COMMENT, "x");
        byte[] chain = NiftiExtensions.encode(List.of(e), ByteOrder.LITTLE_ENDIAN);
        byte[] file = new byte[V1_MARKER + chain.length + 15];
        System.arraycopy(chain, 0, file, V1_MARKER, chain.length);
        assertEquals(1, NiftiExtensions.decode(file, V1_MARKER, file.length,
                ByteOrder.LITTLE_ENDIAN).size());
    }

    // ---- malformed chains ----

    /** A file whose single extension declares {@code esize} and holds {@code bytesAvailable} after it. */
    private static byte[] chainWith(int esize, int bytesAvailable) {
        byte[] file = new byte[V1_MARKER + 4 + bytesAvailable];
        file[V1_MARKER] = 1;
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(V1_MARKER + 4, esize)
                .putInt(V1_MARKER + 8, NiftiEcode.COMMENT);
        return file;
    }

    @Test
    void anEsizeThatIsNotAMultipleOfSixteenIsRejected() {
        for (int esize : new int[] {17, 24, 31, 100, 1000}) {
            byte[] file = chainWith(esize, 4096);
            IOException e = assertThrows(IOException.class,
                    () -> NiftiExtensions.decode(file, V1_MARKER, file.length,
                            ByteOrder.LITTLE_ENDIAN),
                    "esize " + esize + " should be rejected");
            assertTrue(e.getMessage().contains("multiple of 16"), e.getMessage());
        }
    }

    @Test
    void anEsizeBelowTheMinimumIsRejected() {
        for (int esize : new int[] {0, 8, -16}) {
            byte[] file = chainWith(esize, 4096);
            assertThrows(IOException.class,
                    () -> NiftiExtensions.decode(file, V1_MARKER, file.length,
                            ByteOrder.LITTLE_ENDIAN),
                    "esize " + esize + " should be rejected");
        }
    }

    @Test
    void anEsizeWithBitThirtyOneSetIsRejectedRatherThanAllocatedFrom() {
        // Read as an int this is negative; read as unsigned it is 2 GB. Either
        // way it must not reach an array allocation.
        byte[] file = chainWith(0x80000000, 4096);
        assertThrows(IOException.class,
                () -> NiftiExtensions.decode(file, V1_MARKER, file.length,
                        ByteOrder.LITTLE_ENDIAN));

        byte[] huge = chainWith(Integer.MAX_VALUE - 15, 4096);
        IOException e = assertThrows(IOException.class,
                () -> NiftiExtensions.decode(huge, V1_MARKER, huge.length,
                        ByteOrder.LITTLE_ENDIAN));
        assertTrue(e.getMessage().contains("bytes remain"), e.getMessage());
    }

    @Test
    void anEsizeRunningPastTheLimitIsRejected() {
        // 64 bytes declared, 48 available: the extension claims what is not there
        byte[] file = chainWith(64, 48);
        IOException e = assertThrows(IOException.class,
                () -> NiftiExtensions.decode(file, V1_MARKER, file.length,
                        ByteOrder.LITTLE_ENDIAN));
        assertTrue(e.getMessage().contains("bytes remain"), e.getMessage());
    }

    @Test
    void anEsizeRunningPastVoxOffsetIsRejectedEvenWhenTheFileIsLonger() {
        // The bytes exist -- they are the voxels. The chain still may not reach
        // into them, which is what makes vox_offset the limit rather than EOF.
        byte[] file = chainWith(4096, 8192);
        IOException e = assertThrows(IOException.class,
                () -> NiftiExtensions.decode(file, V1_MARKER, 400,
                        ByteOrder.LITTLE_ENDIAN));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
    }

    @Test
    void aChainThatCannotAdvanceIsImpossibleByConstruction() throws IOException {
        // Every accepted esize is at least 16, so the cursor always moves and
        // the loop is bounded by the byte range whatever the file says.
        byte[] file = new byte[V1_MARKER + 4 + 16 * 500];
        file[V1_MARKER] = 1;
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 500; i++) {
            b.putInt(V1_MARKER + 4 + i * 16, 16);
            b.putInt(V1_MARKER + 8 + i * 16, NiftiEcode.IGNORE);
        }
        assertEquals(500, NiftiExtensions.decode(file, V1_MARKER, file.length,
                ByteOrder.LITTLE_ENDIAN).size());
    }

    @Test
    void aBackwardsRangeIsRejected() {
        assertThrows(IOException.class, () -> NiftiExtensions.decode(
                new byte[400], 352, 100, ByteOrder.LITTLE_ENDIAN));
        assertThrows(IOException.class, () -> NiftiExtensions.decode(
                new byte[400], -1, 400, ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void aLimitPastTheEndOfTheBytesReadsOnlyWhatIsThere() throws IOException {
        NiftiExtension e = NiftiExtension.ofText(NiftiEcode.COMMENT, "short");
        byte[] chain = NiftiExtensions.encode(List.of(e), ByteOrder.LITTLE_ENDIAN);
        byte[] file = new byte[V1_MARKER + chain.length];
        System.arraycopy(chain, 0, file, V1_MARKER, chain.length);
        // claim the file is a megabyte long; only the real bytes are read
        assertEquals(1, NiftiExtensions.decode(file, V1_MARKER, 1 << 20,
                ByteOrder.LITTLE_ENDIAN).size());
    }

    @Test
    void textStripsPaddingButNotContent() {
        NiftiExtension e = new NiftiExtension(NiftiEcode.COMMENT,
                "note\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("note", e.text());

        NiftiExtension embedded = new NiftiExtension(NiftiEcode.COMMENT,
                "a\0b\0\0".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("a\0b", embedded.text(), "only trailing NULs are padding");
    }

    @Test
    void equalityIsByValueSoRoundTripsCanBeAsserted() throws IOException {
        NiftiExtension a = new NiftiExtension(NiftiEcode.DICOM, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        NiftiExtension b = new NiftiExtension(NiftiEcode.DICOM, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(List.of(a), roundTrip(List.of(a), ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void theDecodedListIsImmutable() throws IOException {
        List<NiftiExtension> back = roundTrip(
                List.of(NiftiExtension.ofText(NiftiEcode.COMMENT, "x")),
                ByteOrder.LITTLE_ENDIAN);
        assertThrows(UnsupportedOperationException.class,
                () -> back.add(NiftiExtension.ofText(NiftiEcode.COMMENT, "y")));
        assertThrows(UnsupportedOperationException.class,
                () -> NiftiExtensions.decode(new byte[352], 348, 352,
                        ByteOrder.LITTLE_ENDIAN).add(null));
    }
}
