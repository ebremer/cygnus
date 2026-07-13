package com.ebremer.cygnus.ndpi.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.ndpi.Ndpi;
import com.ebremer.cygnus.ndpi.testutil.Images;
import com.ebremer.cygnus.ndpi.testutil.NdpiBuilder;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageReadParam;
import javax.imageio.metadata.IIOMetadata;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The reader over a slide whose levels are one JPEG apiece, cut into tiles by
 * restart markers.
 */
class NDPIImageReaderTest {

    private static final int WIDTH = 512;
    private static final int HEIGHT = 64;
    private static final int RESTART_INTERVAL = 8;   // MCUs between restart markers
    private static final int MACRO_RGB = 0xC02020;

    private static final BufferedImage LEVEL0 = Images.pattern(WIDTH, HEIGHT);
    private static final BufferedImage LEVEL1 = Images.pattern(WIDTH / 2, HEIGHT / 2);
    private static final BufferedImage LEVEL2 = Images.pattern(WIDTH / 4, HEIGHT / 4);

    private static byte[] slide() throws IOException {
        return new NdpiBuilder()
                .property("Objective.Lens.Magnificant", "20")
                .level(LEVEL0, RESTART_INTERVAL, 20)
                .level(LEVEL1, RESTART_INTERVAL, 10)
                .plainLevel(LEVEL2, 5)                     // small enough to need no restarts
                .associated(Images.solid(64, 32, MACRO_RGB), Ndpi.SOURCE_LENS_MACRO)
                .build();
    }

    private static NDPIImageReader reader(byte[] ndpi) {
        NDPIImageReader reader =
                (NDPIImageReader) new NDPIImageReaderSpi().createReaderInstance(null);
        reader.setInput(new ByteArrayImageInputStream(ndpi));
        return reader;
    }

    /** Exactly what the reader must produce: each tile encoded on its own, and decoded. */
    private static BufferedImage expected(BufferedImage source, NDPIImageReader reader, int level)
            throws IOException {
        return NdpiBuilder.decodeAsTiles(source, reader.getTileWidth(level),
                reader.getTileHeight(level));
    }

    @Test
    void imageIndicesArePyramidLevels() throws IOException {
        NDPIImageReader reader = reader(slide());

        assertEquals(4, reader.getStructure().directories().size());
        assertEquals(3, reader.getNumImages(true));
        assertEquals(3, reader.getNumLevels());

        assertEquals(WIDTH, reader.getWidth(0));
        assertEquals(HEIGHT, reader.getHeight(0));
        assertEquals(WIDTH / 2, reader.getWidth(1));
        assertEquals(WIDTH / 4, reader.getWidth(2));

        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(3));
    }

    /** A tile is one restart interval: {@code restartInterval} MCUs wide, one MCU tall. */
    @Test
    void theTileGridIsTheGridOfRestartIntervals() throws IOException {
        NDPIImageReader reader = reader(slide());

        assertTrue(reader.isImageTiled(0));
        int tileWidth = reader.getTileWidth(0);
        int tileHeight = reader.getTileHeight(0);

        assertEquals(0, WIDTH % tileWidth, "the restart markers line up with the tile grid");
        assertEquals(RESTART_INTERVAL * tileHeight, tileWidth,
                "a tile is restartInterval MCUs wide and one MCU tall, and MCUs are square here");

        // The deepest level is one ordinary JPEG, with no restart markers to tile it by.
        assertFalse(reader.isImageTiled(2));
        assertEquals(WIDTH / 4, reader.getTileWidth(2));
    }

    @Test
    void levelsDecodeToTheirTiles() throws IOException {
        NDPIImageReader reader = reader(slide());

        Images.assertSimilar(expected(LEVEL0, reader, 0), reader.read(0, null), 0, "level 0");
        Images.assertSimilar(expected(LEVEL1, reader, 1), reader.read(1, null), 0, "level 1");
    }

    @Test
    void aLevelWithNoRestartMarkersIsJustAJpeg() throws IOException {
        NDPIImageReader reader = reader(slide());

        BufferedImage level2 = reader.read(2, null);

        assertEquals(WIDTH / 4, level2.getWidth());
        assertEquals(HEIGHT / 4, level2.getHeight());
        Images.assertSimilar(expected(LEVEL2, reader, 2), level2, 0, "level 2");
    }

    @Test
    void regionReadCrossesTileBoundaries() throws IOException {
        NDPIImageReader reader = reader(slide());
        BufferedImage full = reader.read(0, null);

        Rectangle region = new Rectangle(100, 20, 183, 33);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);

        Images.assertSimilar(Images.region(full, region, 1, 1), reader.read(0, param), 0,
                "region read");
    }

    @Test
    void subsampledRegionReadPicksTheRightSourcePixels() throws IOException {
        NDPIImageReader reader = reader(slide());
        BufferedImage full = reader.read(0, null);

        Rectangle region = new Rectangle(96, 16, 200, 40);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);
        param.setSourceSubsampling(2, 2, 0, 0);

        BufferedImage actual = reader.read(0, param);

        assertEquals(100, actual.getWidth());
        assertEquals(20, actual.getHeight());
        Images.assertSimilar(Images.region(full, region, 2, 2), actual, 0, "subsampled region");
    }

    @Test
    void readTileMapsOntoTheRestartIntervals() throws IOException {
        NDPIImageReader reader = reader(slide());
        BufferedImage full = reader.read(0, null);
        int tileWidth = reader.getTileWidth(0);
        int tileHeight = reader.getTileHeight(0);

        BufferedImage tile = reader.readTile(0, 1, 1);

        assertEquals(tileWidth, tile.getWidth());
        assertEquals(tileHeight, tile.getHeight());
        Images.assertSimilar(
                Images.region(full, new Rectangle(tileWidth, tileHeight, tileWidth, tileHeight),
                        1, 1),
                tile, 0, "tile (1,1)");

        assertThrows(IllegalArgumentException.class,
                () -> reader.readTile(0, WIDTH / tileWidth, 0));
    }

    @Test
    void theMacroImageIsReachableByName() throws IOException {
        NDPIImageReader reader = reader(slide());

        assertEquals(List.of("macro"), reader.getAssociatedImageNames());

        BufferedImage macro = reader.readAssociatedImage("macro");
        assertEquals(64, macro.getWidth());
        assertEquals(32, macro.getHeight());
        Images.assertUniform(macro, MACRO_RGB, 4, "macro");

        assertThrows(IllegalArgumentException.class, () -> reader.readAssociatedImage("label"));
    }

    @Test
    void downsamplesAndPropertiesComeFromTheSlide() throws IOException {
        NDPIImageReader reader = reader(slide());

        assertEquals(1.0, reader.getLevelDownsample(0), 1e-9);
        assertEquals(2.0, reader.getLevelDownsample(1), 1e-9);
        assertEquals(4.0, reader.getLevelDownsample(2), 1e-9);
        assertEquals(1, reader.getBestLevelForDownsample(2.0));
        assertEquals(2, reader.getBestLevelForDownsample(9.0));

        assertEquals(20.0, reader.getMagnification().getAsDouble(), 1e-6);
        assertEquals(0.25, reader.getMicronsPerPixelX().getAsDouble(), 1e-9);
        assertEquals("20", reader.getProperties().get("Objective.Lens.Magnificant"));
    }

    @Test
    void metadataDescribesTheLevelAndItsPixelSize() throws IOException {
        NDPIImageReader reader = reader(slide());
        IIOMetadata metadata = reader.getImageMetadata(1);

        Element level = (Element) ((Element) metadata.getAsTree(NDPIMetadata.NATIVE_FORMAT))
                .getElementsByTagName("Level").item(0);
        assertEquals("1", level.getAttribute("index"));
        assertEquals("2.0", level.getAttribute("downsample"));
        assertEquals("10.0", level.getAttribute("sourceLens"));

        Element size = (Element) ((Element) metadata.getAsTree("javax_imageio_1.0"))
                .getElementsByTagName("HorizontalPixelSize").item(0);
        assertEquals(0.25 * 2 / 1000, Double.parseDouble(size.getAttribute("value")), 1e-12);
    }

    /**
     * OpenSlide calls the recorded MCU starts "unreliable" and re-derives them
     * when they do not hold up. So does this reader: the restart markers are in
     * the entropy data whether or not the table finds them.
     */
    @Test
    void aLevelWithABadMcuStartTableIsStillRead() throws IOException {
        byte[] ndpi = new NdpiBuilder()
                .corruptMcuStarts()
                .level(LEVEL0, RESTART_INTERVAL, 20)
                .build();

        NDPIImageReader reader = reader(ndpi);

        assertEquals(WIDTH, reader.getWidth(0));
        Images.assertSimilar(expected(LEVEL0, reader, 0), reader.read(0, null), 0,
                "a level whose MCU start table is wrong");
    }

    /**
     * The reason NDPI exists: a level wider than the 65535 a JPEG's frame header
     * can express. Hamamatsu writes a zero there and puts the real size in the
     * TIFF tags, so a reader that believes the JPEG gets nothing.
     */
    @Test
    void aLevelTooWideForItsJpegToDescribeIsStillRead() throws IOException {
        int width = 65536;                       // one past what a SOF can hold
        int height = 32;
        BufferedImage source = Images.pattern(width, height);

        byte[] ndpi = new NdpiBuilder()
                .level(source, RESTART_INTERVAL, 20)
                .build();

        NDPIImageReader reader = reader(ndpi);

        assertEquals(width, reader.getWidth(0), "the size comes from the TIFF tags, not the SOF");
        assertEquals(height, reader.getHeight(0));

        // Read a window that straddles the point a 16-bit width would have wrapped at.
        Rectangle region = new Rectangle(65500, 0, 36, 16);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);

        BufferedImage window = reader.read(0, param);

        assertEquals(36, window.getWidth());
        Images.assertSimilar(
                Images.region(expected(source, reader, 0), region, 1, 1), window, 0,
                "window at the far end of an over-wide level");
    }
}
