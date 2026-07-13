package com.ebremer.cygnus.ndpi.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.ndpi.testutil.Images;
import com.ebremer.cygnus.ndpi.testutil.NdpiBuilder;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;

/** Recognizing a slide, and being the reader ImageIO hands back for one. */
class NDPIImageReaderSpiTest {

    private static byte[] slide() throws IOException {
        return new NdpiBuilder()
                .level(Images.pattern(512, 64), 8, 20)
                .associated(Images.solid(64, 32, 0xC02020), -1)
                .build();
    }

    @Test
    void recognizesAnNdpiFile() throws IOException {
        assertTrue(new NDPIImageReaderSpi()
                .canDecodeInput(new ByteArrayImageInputStream(slide())));
    }

    @Test
    void rejectsInputThatIsNotNdpi() throws IOException {
        NDPIImageReaderSpi spi = new NDPIImageReaderSpi();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

        assertFalse(spi.canDecodeInput(new ByteArrayImageInputStream(png)));
        assertFalse(spi.canDecodeInput(new ByteArrayImageInputStream(new byte[0])));
        assertFalse(spi.canDecodeInput("not a stream"));
    }

    @Test
    void imageIoFindsTheReaderByNameSuffixAndMimeType() {
        assertInstanceOf(NDPIImageReader.class,
                ImageIO.getImageReadersByFormatName("ndpi").next());
        assertInstanceOf(NDPIImageReader.class,
                ImageIO.getImageReadersBySuffix("ndpi").next());
        assertInstanceOf(NDPIImageReader.class,
                ImageIO.getImageReadersByMIMEType("image/x-hamamatsu-ndpi").next());
    }

    /**
     * An NDPI file opens like a TIFF, so the TIFF readers claim it and would
     * make nonsense of it. This one has to win.
     */
    @Test
    void imageIoPrefersThisReaderOverThePlainTiffReaders() throws IOException {
        NDPIImageReaderSpi.prioritize();

        try (ImageInputStream stream = new ByteArrayImageInputStream(slide())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);

            assertTrue(readers.hasNext(), "no reader claimed the slide");
            assertInstanceOf(NDPIImageReader.class, readers.next(),
                    "a TIFF reader would read the slide's offsets truncated to 32 bits");
        }
    }

    /**
     * The preference has to be an explicit edge in the registry, not luck: with
     * no edge, providers come back in hash order, which differs from one JVM to
     * the next.
     */
    @Test
    void thePreferenceIsAnExplicitOrderingInTheRegistry() {
        NDPIImageReaderSpi.prioritize();

        IIORegistry registry = IIORegistry.getDefaultInstance();
        ImageReaderSpi ndpi = registry.getServiceProviderByClass(NDPIImageReaderSpi.class);
        // Named rather than referenced: the JDK's TIFF plug-in is not an exported package.
        ImageReaderSpi tiff = lookup("com.sun.imageio.plugins.tiff.TIFFImageReaderSpi");
        assertNotNull(ndpi);
        assertNotNull(tiff, "the JDK's own TIFF reader is always registered");

        // unsetOrdering reports whether the ordering was there. Put it back either way.
        boolean ordered = registry.unsetOrdering(ImageReaderSpi.class, ndpi, tiff);
        if (ordered) {
            registry.setOrdering(ImageReaderSpi.class, ndpi, tiff);
        }
        assertTrue(ordered, "NDPI is not explicitly ordered ahead of the TIFF reader, so which "
                + "one ImageIO picks for a slide is left to chance");
    }

    private static ImageReaderSpi lookup(String className) {
        Iterator<ImageReaderSpi> providers = IIORegistry.getDefaultInstance()
                .getServiceProviders(ImageReaderSpi.class, false);
        while (providers.hasNext()) {
            ImageReaderSpi provider = providers.next();
            if (className.equals(provider.getClass().getName())) {
                return provider;
            }
        }
        return null;
    }
}
