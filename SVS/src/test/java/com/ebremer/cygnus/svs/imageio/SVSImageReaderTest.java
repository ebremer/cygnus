package com.ebremer.cygnus.svs.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.svs.testutil.Images;
import com.ebremer.cygnus.svs.testutil.SvsBuilder;
import com.ebremer.cygnus.svs.testutil.SvsBuilder.Codec;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageReadParam;
import javax.imageio.metadata.IIOMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

/**
 * The reader over a JPEG-tiled slide: two pyramid levels, a thumbnail, a label
 * and a macro, laid out the way a scanner writes them.
 */
class SVSImageReaderTest {

    private static final int FULL_WIDTH = 300;
    private static final int FULL_HEIGHT = 200;
    private static final int HALF_WIDTH = 150;
    private static final int HALF_HEIGHT = 100;
    private static final int TILE = 64;

    private static final int LEVEL1_RGB = 0x2040C0;
    private static final int THUMBNAIL_RGB = 0xC02020;
    private static final int LABEL_RGB = 0x20C040;
    private static final int MACRO_RGB = 0xE0C020;

    /** JPEG is lossy; solid colours survive it to within a couple of counts. */
    private static final int JPEG_TOLERANCE = 4;

    private static byte[] slide(boolean bigTiff) throws IOException {
        SvsBuilder builder = new SvsBuilder();
        if (bigTiff) {
            builder.bigTiff();
        }
        return builder
                .tiled(Images.pattern(FULL_WIDTH, FULL_HEIGHT), TILE, TILE, Codec.JPEG,
                        SvsBuilder.levelDescription(FULL_WIDTH, FULL_HEIGHT, TILE, TILE,
                                "JPEG/RGB"))
                .stripped(Images.solid(75, 50, THUMBNAIL_RGB), Codec.JPEG,
                        SvsBuilder.thumbnailDescription(FULL_WIDTH, FULL_HEIGHT, 75, 50))
                .tiled(Images.solid(HALF_WIDTH, HALF_HEIGHT, LEVEL1_RGB), TILE, TILE, Codec.JPEG,
                        SvsBuilder.levelDescription(HALF_WIDTH, HALF_HEIGHT, TILE, TILE,
                                "JPEG/RGB"))
                .stripped(Images.solid(40, 30, LABEL_RGB), Codec.UNCOMPRESSED,
                        SvsBuilder.associatedDescription("label", 40, 30))
                .stripped(Images.solid(80, 30, MACRO_RGB), Codec.JPEG,
                        SvsBuilder.associatedDescription("macro", 80, 30))
                .build();
    }

    private static SVSImageReader reader(byte[] svs) {
        SVSImageReader reader = (SVSImageReader) new SVSImageReaderSpi().createReaderInstance(null);
        reader.setInput(new ByteArrayImageInputStream(svs));
        return reader;
    }

    @ParameterizedTest(name = "bigTiff={0}")
    @ValueSource(booleans = {false, true})
    void imageIndicesArePyramidLevels(boolean bigTiff) throws IOException {
        SVSImageReader reader = reader(slide(bigTiff));

        // Five TIFF directories, but only the two tiled ones are levels.
        assertEquals(5, reader.getStructure().directories().size());
        assertEquals(2, reader.getNumImages(true));
        assertEquals(2, reader.getNumLevels());

        assertEquals(FULL_WIDTH, reader.getWidth(0));
        assertEquals(FULL_HEIGHT, reader.getHeight(0));
        assertEquals(HALF_WIDTH, reader.getWidth(1));
        assertEquals(HALF_HEIGHT, reader.getHeight(1));

        assertTrue(reader.isImageTiled(0));
        assertEquals(TILE, reader.getTileWidth(0));
        assertEquals(TILE, reader.getTileHeight(0));

        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(2));
    }

    /**
     * The trap this layout sets: TIFF directory 1 is the thumbnail, so level 1
     * is directory 2. Reading level 1 must not hand back the thumbnail.
     */
    @ParameterizedTest(name = "bigTiff={0}")
    @ValueSource(booleans = {false, true})
    void levelOneIsTheSecondTiledDirectoryNotTheThumbnail(boolean bigTiff) throws IOException {
        SVSImageReader reader = reader(slide(bigTiff));

        BufferedImage level1 = reader.read(1, null);

        assertEquals(HALF_WIDTH, level1.getWidth());
        assertEquals(HALF_HEIGHT, level1.getHeight());
        Images.assertUniform(level1, LEVEL1_RGB, JPEG_TOLERANCE, "level 1");
    }

    @ParameterizedTest(name = "bigTiff={0}")
    @ValueSource(booleans = {false, true})
    void associatedImagesAreNamedByTheirDescription(boolean bigTiff) throws IOException {
        SVSImageReader reader = reader(slide(bigTiff));

        assertEquals(List.of("thumbnail", "label", "macro"), reader.getAssociatedImageNames());

        BufferedImage label = reader.readAssociatedImage("label");
        assertEquals(40, label.getWidth());
        assertEquals(30, label.getHeight());
        Images.assertUniform(label, LABEL_RGB, 0, "label is stored uncompressed");

        Images.assertUniform(reader.readAssociatedImage("macro"), MACRO_RGB,
                JPEG_TOLERANCE, "macro");
        Images.assertUniform(reader.readAssociatedImage("thumbnail"), THUMBNAIL_RGB,
                JPEG_TOLERANCE, "thumbnail");

        assertThrows(IllegalArgumentException.class, () -> reader.readAssociatedImage("nope"));
    }

    @Test
    void thumbnailIsAlsoReachableThroughTheImageIoThumbnailApi() throws IOException {
        SVSImageReader reader = reader(slide(false));

        assertTrue(reader.readerSupportsThumbnails());
        assertEquals(1, reader.getNumThumbnails(0));
        assertEquals(0, reader.getNumThumbnails(1), "only the full-resolution level has one");
        assertEquals(75, reader.getThumbnailWidth(0, 0));
        assertEquals(50, reader.getThumbnailHeight(0, 0));

        BufferedImage thumbnail = reader.readThumbnail(0, 0);
        assertEquals(75, thumbnail.getWidth());
        Images.assertUniform(thumbnail, THUMBNAIL_RGB, JPEG_TOLERANCE, "thumbnail");

        assertThrows(IndexOutOfBoundsException.class, () -> reader.readThumbnail(0, 1));
    }

    @Test
    void downsamplesFollowTheLevelDimensions() throws IOException {
        SVSImageReader reader = reader(slide(false));

        assertEquals(1.0, reader.getLevelDownsample(0), 1e-9);
        assertEquals(2.0, reader.getLevelDownsample(1), 1e-9);

        assertEquals(0, reader.getBestLevelForDownsample(1.0));
        assertEquals(0, reader.getBestLevelForDownsample(1.9));
        assertEquals(1, reader.getBestLevelForDownsample(2.0));
        assertEquals(1, reader.getBestLevelForDownsample(8.0));
    }

    @Test
    void regionReadMatchesTheSamePixelsOfAFullRead() throws IOException {
        SVSImageReader reader = reader(slide(false));
        BufferedImage full = reader.read(0, null);

        Rectangle region = new Rectangle(100, 50, 83, 61); // crosses tile boundaries
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);

        Images.assertSimilar(Images.region(full, region, 1, 1), reader.read(0, param), 0,
                "region read");
    }

    @Test
    void subsampledRegionReadMatchesTheSamePixelsOfAFullRead() throws IOException {
        SVSImageReader reader = reader(slide(false));
        BufferedImage full = reader.read(0, null);

        Rectangle region = new Rectangle(96, 48, 80, 60);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);
        param.setSourceSubsampling(2, 2, 0, 0);

        BufferedImage actual = reader.read(0, param);
        assertEquals(40, actual.getWidth());
        assertEquals(30, actual.getHeight());
        Images.assertSimilar(Images.region(full, region, 2, 2), actual, 0, "subsampled region");
    }

    @Test
    void readTileMapsOntoTheTiffTileGrid() throws IOException {
        SVSImageReader reader = reader(slide(false));
        BufferedImage full = reader.read(0, null);

        BufferedImage tile = reader.readTile(0, 1, 1);
        assertEquals(TILE, tile.getWidth());
        assertEquals(TILE, tile.getHeight());
        Images.assertSimilar(Images.region(full, new Rectangle(TILE, TILE, TILE, TILE), 1, 1),
                tile, 0, "tile (1,1)");

        // The right/bottom edge tiles are clipped to the image, not the tile grid.
        BufferedImage edge = reader.readTile(0, 4, 3);
        assertEquals(FULL_WIDTH - 4 * TILE, edge.getWidth());
        assertEquals(FULL_HEIGHT - 3 * TILE, edge.getHeight());

        assertThrows(IllegalArgumentException.class, () -> reader.readTile(0, 5, 0));
    }

    @Test
    void slidePropertiesComeFromTheAperioDescription() throws IOException {
        SVSImageReader reader = reader(slide(false));

        assertEquals(0.4990, reader.getMicronsPerPixel().getAsDouble(), 1e-9);
        assertEquals(20.0, reader.getMagnification().getAsDouble(), 1e-9);
        assertEquals("CPAPERIOCS", reader.getProperties().get("ScanScope ID"));
        assertTrue(reader.getAperioDescription().isAperio());
    }

    @Test
    void metadataDescribesTheLevelAndItsPixelSize() throws IOException {
        SVSImageReader reader = reader(slide(false));
        IIOMetadata metadata = reader.getImageMetadata(1);

        Element level = (Element) ((Element) metadata.getAsTree(SVSMetadata.NATIVE_FORMAT))
                .getElementsByTagName("Level").item(0);
        assertEquals("1", level.getAttribute("index"));
        assertEquals("2", level.getAttribute("tiffDirectory"), "level 1 is TIFF directory 2");
        assertEquals("2.0", level.getAttribute("downsample"));
        assertEquals("JPEG", level.getAttribute("compressionName"));

        // MPP is microns per pixel at level 0; the standard tree wants mm at this level.
        Element size = (Element) ((Element) metadata.getAsTree("javax_imageio_1.0"))
                .getElementsByTagName("HorizontalPixelSize").item(0);
        assertEquals(0.4990 * 2 / 1000, Double.parseDouble(size.getAttribute("value")), 1e-12);
    }

    @Test
    void aNonAperioTiffStillReadsButWarns() throws IOException {
        byte[] tiff = new SvsBuilder()
                .tiled(Images.pattern(128, 128), TILE, TILE, Codec.JPEG, "Created with libtiff")
                .build();

        SVSImageReader reader = reader(tiff);
        StringBuilder warnings = new StringBuilder();
        reader.addIIOReadWarningListener((source, warning) -> warnings.append(warning));

        assertEquals(1, reader.getNumImages(true));
        assertFalse(reader.getAperioDescription().isAperio());
        assertTrue(warnings.toString().contains("Not an Aperio SVS file"),
                "expected a warning, got: " + warnings);
    }
}
