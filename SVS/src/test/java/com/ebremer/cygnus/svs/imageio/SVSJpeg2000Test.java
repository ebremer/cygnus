package com.ebremer.cygnus.svs.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.svs.testutil.Images;
import com.ebremer.cygnus.svs.testutil.SvsBuilder;
import com.ebremer.cygnus.svs.testutil.SvsBuilder.Codec;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageReadParam;
import org.junit.jupiter.api.Test;

/**
 * Aperio's JPEG 2000 tile compressions, which no TIFF codec covers and which
 * the reader decodes itself.
 *
 * <p>The fixtures are coded losslessly, so an RGB slide (33005) must come back
 * bit-exact. A YCbCr slide (33003) is lossless in the YCbCr domain, so it comes
 * back within the rounding error of the colour conversion.</p>
 */
class SVSJpeg2000Test {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final int TILE = 64;

    /** Two roundings, RGB to YCbCr and back, cost at most a couple of counts. */
    private static final int YCBCR_TOLERANCE = 3;

    private static final BufferedImage SOURCE = Images.pattern(WIDTH, HEIGHT);

    private static byte[] slide(Codec codec) throws IOException {
        return new SvsBuilder()
                .tiled(SOURCE, TILE, TILE, codec,
                        SvsBuilder.levelDescription(WIDTH, HEIGHT, TILE, TILE, "J2K/YUV16"))
                .stripped(Images.solid(40, 30, 0x20C040), Codec.UNCOMPRESSED,
                        SvsBuilder.associatedDescription("label", 40, 30))
                .build();
    }

    private static SVSImageReader reader(Codec codec) throws IOException {
        SVSImageReader reader = (SVSImageReader) new SVSImageReaderSpi().createReaderInstance(null);
        reader.setInput(new ByteArrayImageInputStream(slide(codec)));
        return reader;
    }

    @Test
    void rgbTilesDecodeLosslessly() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        assertTrue(reader.getStructure().levels().get(0).isJpeg2000());
        assertEquals(1, reader.getNumLevels());

        Images.assertSimilar(SOURCE, reader.read(0, null), 0, "JPEG 2000 RGB level");
    }

    @Test
    void ycbcrTilesAreConvertedToRgb() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_YCBCR);

        Images.assertSimilar(SOURCE, reader.read(0, null), YCBCR_TOLERANCE,
                "JPEG 2000 YCbCr level");
    }

    @Test
    void regionReadCrossesTileBoundaries() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        Rectangle region = new Rectangle(50, 40, 71, 55); // spans four tiles
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);

        Images.assertSimilar(Images.region(SOURCE, region, 1, 1), reader.read(0, param), 0,
                "region spanning four JPEG 2000 tiles");
    }

    @Test
    void subsampledRegionReadPicksTheRightSourcePixels() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        Rectangle region = new Rectangle(33, 21, 90, 70);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(3, 2, 0, 0);
        param.setSourceRegion(region);

        BufferedImage actual = reader.read(0, param);
        assertEquals(30, actual.getWidth());
        assertEquals(35, actual.getHeight());
        Images.assertSimilar(Images.region(SOURCE, region, 3, 2), actual, 0,
                "subsampled JPEG 2000 region");
    }

    @Test
    void subsamplingOffsetShiftsTheSourceGrid() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(20, 20, 40, 40));
        param.setSourceSubsampling(2, 2, 1, 1);

        // computeRegions folds the offset into the source origin.
        Images.assertSimilar(Images.region(SOURCE, new Rectangle(21, 21, 39, 39), 2, 2),
                reader.read(0, param), 0, "subsampling offset");
    }

    @Test
    void readTileDecodesASingleCodestream() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        BufferedImage tile = reader.readTile(0, 1, 0);
        assertEquals(TILE, tile.getWidth());
        assertEquals(TILE, tile.getHeight());
        Images.assertSimilar(Images.region(SOURCE, new Rectangle(TILE, 0, TILE, TILE), 1, 1),
                tile, 0, "JPEG 2000 tile (1,0)");

        // The bottom-right tile is padded to the full tile size in the file, clipped on read.
        BufferedImage edge = reader.readTile(0, 2, 1);
        assertEquals(WIDTH - 2 * TILE, edge.getWidth());
        assertEquals(HEIGHT - TILE, edge.getHeight());
        Images.assertSimilar(
                Images.region(SOURCE, new Rectangle(2 * TILE, TILE, WIDTH - 2 * TILE,
                        HEIGHT - TILE), 1, 1),
                edge, 0, "clipped JPEG 2000 edge tile");
    }

    @Test
    void associatedImagesStillComeFromTheTiffReader() throws IOException {
        SVSImageReader reader = reader(Codec.JP2K_RGB);

        Images.assertUniform(reader.readAssociatedImage("label"), 0x20C040, 0,
                "label of a JPEG 2000 slide");
    }
}
