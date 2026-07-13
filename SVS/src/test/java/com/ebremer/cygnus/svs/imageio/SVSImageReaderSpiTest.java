package com.ebremer.cygnus.svs.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.svs.testutil.Images;
import com.ebremer.cygnus.svs.testutil.SvsBuilder;
import com.ebremer.cygnus.svs.testutil.SvsBuilder.Codec;
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Recognizing a slide, and being the reader ImageIO hands back for one. */
class SVSImageReaderSpiTest {

    private static byte[] slide(boolean bigTiff, String description) throws IOException {
        SvsBuilder builder = new SvsBuilder();
        if (bigTiff) {
            builder.bigTiff();
        }
        return builder
                .tiled(Images.pattern(128, 96), 64, 64, Codec.JPEG, description)
                .stripped(Images.solid(32, 24, 0x20C040), Codec.UNCOMPRESSED,
                        SvsBuilder.associatedDescription("label", 32, 24))
                .build();
    }

    private static byte[] slide(boolean bigTiff) throws IOException {
        return slide(bigTiff, SvsBuilder.levelDescription(128, 96, 64, 64, "JPEG/RGB"));
    }

    @ParameterizedTest(name = "bigTiff={0}")
    @ValueSource(booleans = {false, true})
    void recognizesAnAperioTiff(boolean bigTiff) throws IOException {
        assertTrue(new SVSImageReaderSpi()
                .canDecodeInput(new ByteArrayImageInputStream(slide(bigTiff))));
    }

    @Test
    void rejectsATiffThatIsNotAperio() throws IOException {
        byte[] tiff = slide(false, "Created with libtiff");
        assertFalse(new SVSImageReaderSpi().canDecodeInput(new ByteArrayImageInputStream(tiff)));
    }

    @Test
    void rejectsInputThatIsNotATiff() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
        SVSImageReaderSpi spi = new SVSImageReaderSpi();

        assertFalse(spi.canDecodeInput(new ByteArrayImageInputStream(png)));
        assertFalse(spi.canDecodeInput(new ByteArrayImageInputStream(new byte[0])));
        assertFalse(spi.canDecodeInput("not a stream"));
    }

    /**
     * ImageIO offers the same stream to every provider in turn, so sniffing
     * must leave both the position and the byte order as it found them.
     */
    @Test
    void sniffingLeavesTheStreamAsItFoundIt() throws IOException {
        ImageInputStream stream = new ByteArrayImageInputStream(slide(false));
        assertEquals(ByteOrder.BIG_ENDIAN, stream.getByteOrder(), "ImageIO's default");

        assertTrue(new SVSImageReaderSpi().canDecodeInput(stream));

        assertEquals(0, stream.getStreamPosition());
        assertEquals(ByteOrder.BIG_ENDIAN, stream.getByteOrder(),
                "a little-endian TIFF must not leave the stream little-endian");
    }

    @Test
    void imageIoFindsTheReaderByNameSuffixAndMimeType() {
        assertInstanceOf(SVSImageReader.class,
                ImageIO.getImageReadersByFormatName("svs").next());
        assertInstanceOf(SVSImageReader.class,
                ImageIO.getImageReadersBySuffix("svs").next());
        assertInstanceOf(SVSImageReader.class,
                ImageIO.getImageReadersByMIMEType("image/x-aperio-svs").next());
    }

    /**
     * The TIFF readers also accept a slide, and would decode its
     * full-resolution level whole. This one has to win.
     */
    @Test
    void imageIoPrefersThisReaderOverThePlainTiffReaders() throws IOException {
        SVSImageReaderSpi.prioritize();

        try (ImageInputStream stream = new ByteArrayImageInputStream(slide(false))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);

            assertTrue(readers.hasNext(), "no reader claimed the slide");
            assertInstanceOf(SVSImageReader.class, readers.next(),
                    "a TIFF reader would hand back the whole full-resolution level");
        }
    }

    /**
     * The preference has to be an explicit edge in the registry, not luck: with
     * no edge, providers come back in hash order, which differs from one JVM to
     * the next.
     */
    @Test
    void thePreferenceIsAnExplicitOrderingInTheRegistry() {
        SVSImageReaderSpi.prioritize();

        IIORegistry registry = IIORegistry.getDefaultInstance();
        ImageReaderSpi svs = registry.getServiceProviderByClass(SVSImageReaderSpi.class);
        ImageReaderSpi tiff = registry.getServiceProviderByClass(
                com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi.class);
        assertNotNull(svs);
        assertNotNull(tiff);

        // unsetOrdering reports whether the ordering was there. Put it back either way.
        boolean ordered = registry.unsetOrdering(ImageReaderSpi.class, svs, tiff);
        if (ordered) {
            registry.setOrdering(ImageReaderSpi.class, svs, tiff);
        }
        assertTrue(ordered, "SVS is not explicitly ordered ahead of the TwelveMonkeys TIFF "
                + "reader, so which one ImageIO picks for a slide is left to chance");
    }

    /**
     * Creating a reader by any route establishes the ordering too, so an
     * application that reaches this reader by format name never has to ask.
     */
    @Test
    void creatingAReaderEstablishesTheOrdering() throws IOException {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        ImageReaderSpi svs = registry.getServiceProviderByClass(SVSImageReaderSpi.class);
        ImageReaderSpi tiff = registry.getServiceProviderByClass(
                com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi.class);
        registry.unsetOrdering(ImageReaderSpi.class, svs, tiff);

        ImageIO.getImageReadersByFormatName("svs").next();

        try (ImageInputStream stream = new ByteArrayImageInputStream(slide(false))) {
            assertInstanceOf(SVSImageReader.class,
                    ImageIO.getImageReaders(stream).next());
        }
    }

    @Test
    void theTiffReadersAreStillRegisteredForOrdinaryTiffs() {
        boolean tiff = false;
        Iterator<ImageReaderSpi> providers = javax.imageio.spi.IIORegistry.getDefaultInstance()
                .getServiceProviders(ImageReaderSpi.class, true);
        while (providers.hasNext()) {
            if (providers.next().getClass().getName()
                    .equals("com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi")) {
                tiff = true;
            }
        }
        assertTrue(tiff, "the TwelveMonkeys TIFF reader should still be available");
    }
}
