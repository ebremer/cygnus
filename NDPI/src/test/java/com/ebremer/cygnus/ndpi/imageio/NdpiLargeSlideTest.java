package com.ebremer.cygnus.ndpi.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.ndpi.Ndpi;
import com.ebremer.cygnus.ndpi.NdpiTiff;
import com.ebremer.cygnus.ndpi.testutil.Images;
import com.ebremer.cygnus.ndpi.testutil.NdpiBuilder;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A slide past 4 GiB, which is what NDPI's whole shape is for.
 *
 * <p>Everything past the header sits beyond the 4 GiB a 32-bit offset reaches,
 * so the directories can only be found through NDPI's 8-byte pointers and the
 * strip only through the high half of its offset, kept in the directory's
 * extension block. A TIFF reader truncates both to 32 bits and lands in the
 * wrong place — or, worse, in a plausible-looking one.</p>
 *
 * <p>The file is written with a hole in it, so it costs a few hundred kilobytes
 * of disk rather than four gigabytes.</p>
 */
class NdpiLargeSlideTest {

    private static final int WIDTH = 512;
    private static final int HEIGHT = 64;
    private static final int RESTART_INTERVAL = 8;

    private static final BufferedImage SOURCE = Images.pattern(WIDTH, HEIGHT);

    @Test
    void aSlideWhoseOffsetsNeedMoreThan32BitsIsRead(@TempDir Path directory) throws IOException {
        // Without sparse files this would still work, but it would write 4 GiB to do it.
        assumeTrue(Files.getFileStore(directory).getUsableSpace() > 16L << 30,
                "not enough room to risk a 4 GiB file");

        Path path = directory.resolve("large.ndpi");
        long dataOffset = (4L << 30) + 4096;

        new NdpiBuilder()
                .dataAt(dataOffset)
                .property("Product", "NanoZoomer")
                .level(SOURCE, RESTART_INTERVAL, 20)
                .associated(Images.solid(64, 32, 0xC02020), Ndpi.SOURCE_LENS_MACRO)
                .writeTo(path);

        assertTrue(Files.size(path) > (4L << 30),
                "the slide has to actually be past 4 GiB to be testing anything");

        try (ImageInputStream stream = new FileImageInputStream(path.toFile())) {
            assertTrue(NdpiTiff.isNdpi(stream),
                    "the first directory is found through the 64-bit pointer");

            NDPIImageReader reader =
                    (NDPIImageReader) new NDPIImageReaderSpi().createReaderInstance(null);
            reader.setInput(stream);
            try {
                assertEquals(WIDTH, reader.getWidth(0));
                assertEquals(HEIGHT, reader.getHeight(0));
                assertEquals("NanoZoomer", reader.getProperties().get("Product"));

                BufferedImage expected = NdpiBuilder.decodeAsTiles(SOURCE,
                        reader.getTileWidth(0), reader.getTileHeight(0));
                Images.assertSimilar(expected, reader.read(0, null), 0,
                        "a level whose strip is past 4 GiB");

                Rectangle region = new Rectangle(200, 16, 120, 32);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(region);
                Images.assertSimilar(Images.region(expected, region, 1, 1),
                        reader.read(0, param), 0, "a region of it");

                Images.assertUniform(reader.readAssociatedImage("macro"), 0xC02020, 4, "macro");
            } finally {
                reader.dispose();
            }
        }
    }
}
