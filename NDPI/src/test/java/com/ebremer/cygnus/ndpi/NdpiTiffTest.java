package com.ebremer.cygnus.ndpi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.ndpi.testutil.Images;
import com.ebremer.cygnus.ndpi.testutil.NdpiBuilder;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;

/**
 * The container: the 8-byte directory pointers and the block of value
 * extensions that make NDPI a 64-bit format wearing a 32-bit TIFF's clothes.
 */
class NdpiTiffTest {

    private static byte[] slide() throws IOException {
        return new NdpiBuilder()
                .property("Objective.Lens.Magnificant", "20")
                .property("Product", "NanoZoomer")
                .level(Images.pattern(512, 64), 8, 20)
                .associated(Images.solid(64, 32, 0xC02020), Ndpi.SOURCE_LENS_MACRO)
                .build();
    }

    private static NdpiTiff read(byte[] ndpi) throws IOException {
        return NdpiTiff.read(new ByteArrayImageInputStream(ndpi));
    }

    @Test
    void readsTheDirectoryChainThroughItsEightBytePointers() throws IOException {
        List<NdpiDirectory> directories = read(slide()).directories();

        assertEquals(2, directories.size());
        assertEquals(512, directories.get(0).width());
        assertEquals(64, directories.get(0).height());
        assertEquals(64, directories.get(1).width());
        assertEquals(Ndpi.COMPRESSION_JPEG, directories.get(0).compression());
        assertTrue(directories.get(0).has(Ndpi.TAG_FORMAT_FLAG));
    }

    @Test
    void sourceLensSaysWhatEachDirectoryIs() throws IOException {
        List<NdpiDirectory> directories = read(slide()).directories();

        assertEquals(20.0, directories.get(0).sourceLens().getAsDouble(), 1e-6);
        assertTrue(directories.get(0).isLevel());

        assertEquals(-1.0, directories.get(1).sourceLens().getAsDouble(), 1e-6);
        assertFalse(directories.get(1).isLevel());
        assertEquals("macro", Ndpi.associatedImageName(directories.get(1).sourceLens()
                .getAsDouble()));
    }

    /**
     * The whole point of the extension block: a value too big for the 32 bits a
     * classic TIFF entry holds is split, and has to be put back together.
     */
    @Test
    void aValuePastFourGibibytesIsWidenedBackFromItsExtension() throws IOException {
        long huge = 5_000_000_000L;             // needs 33 bits
        int tag = 65500;
        byte[] ndpi = new NdpiBuilder()
                .longTag(tag, huge)
                .level(Images.pattern(512, 64), 8, 20)
                .build();

        NdpiDirectory directory = read(ndpi).directories().get(0);

        assertEquals(huge, directory.integer(tag).getAsLong(),
                "a LONG with a non-zero extension is a 64-bit value");
        // And a value that fits is still itself.
        assertEquals(512, directory.width());
    }

    @Test
    void asciiValuesAreOutOfLineEvenWhenTheyWouldFit() throws IOException {
        // "a=b\r\n\0" is 6 bytes, but even a 3-byte one would be out of line in NDPI.
        byte[] ndpi = new NdpiBuilder()
                .property("a", "b")
                .level(Images.pattern(512, 64), 8, 20)
                .build();

        Map<String, String> properties = read(ndpi).directories().get(0).propertyMap();

        assertEquals(Map.of("a", "b"), properties);
    }

    @Test
    void propertyMapIsSplitIntoRecords() throws IOException {
        Map<String, String> properties = read(slide()).directories().get(0).propertyMap();

        assertEquals("20", properties.get("Objective.Lens.Magnificant"));
        assertEquals("NanoZoomer", properties.get("Product"));
    }

    @Test
    void resolutionTagsGiveMicronsPerPixel() throws IOException {
        // 40000 pixels per centimetre is a quarter of a micron each.
        NdpiDirectory directory = read(slide()).directories().get(0);

        assertEquals(0.25, directory.micronsPerPixelX().getAsDouble(), 1e-9);
        assertEquals(0.25, directory.micronsPerPixelY().getAsDouble(), 1e-9);
    }

    /**
     * The MCU-start table is what makes a level's giant JPEG randomly accessible.
     * Read it wrong and the reader still works — it falls back to scanning for
     * restart markers — so it is worth pinning down that it reads right.
     */
    @Test
    void theMcuStartTableIsReadAsAnAscendingRunOfOffsets() throws IOException {
        long[] starts = read(slide()).directories().get(0).integers(Ndpi.TAG_MCU_STARTS);

        assertTrue(starts.length > 1, "a tiled level has a restart marker per tile");
        for (int i = 1; i < starts.length; i++) {
            assertTrue(starts[i] > starts[i - 1],
                    "start " + i + " (" + starts[i] + ") should follow " + starts[i - 1]);
        }
    }

    @Test
    void recognizesAnNdpiFile() throws IOException {
        assertTrue(NdpiTiff.isNdpi(new ByteArrayImageInputStream(slide())));
    }

    @Test
    void anOrdinaryTiffIsNotNdpi() throws IOException {
        // A classic TIFF: the bytes NDPI uses for the high half of the first-directory
        // pointer are, in a real TIFF, the start of the first directory itself.
        byte[] tiff = {
            'I', 'I', 42, 0,
            8, 0, 0, 0,             // first directory at 8
            1, 0,                   // one entry
            0, 1, 3, 0, 1, 0, 0, 0, 16, 0, 0, 0,
            0, 0, 0, 0,
        };
        assertFalse(NdpiTiff.isNdpi(new ByteArrayImageInputStream(tiff)));
    }

    @Test
    void sniffingLeavesTheStreamAsItFoundIt() throws IOException {
        ImageInputStream stream = new ByteArrayImageInputStream(slide());
        assertEquals(ByteOrder.BIG_ENDIAN, stream.getByteOrder(), "ImageIO's default");

        assertTrue(NdpiTiff.isNdpi(stream));

        assertEquals(0, stream.getStreamPosition());
        assertEquals(ByteOrder.BIG_ENDIAN, stream.getByteOrder());
    }
}
