package com.ebremer.cygnus.ndpi.imageio;

import com.ebremer.cygnus.ndpi.NdpiTiff;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;

/**
 * Service provider for the Hamamatsu NDPI reader. Recognizes a little-endian
 * classic TIFF whose first directory — reached through NDPI's 64-bit pointer —
 * carries {@link com.ebremer.cygnus.ndpi.Ndpi#TAG_FORMAT_FLAG}, which is in
 * every NDPI file and nothing else.
 */
public final class NDPIImageReaderSpi extends ImageReaderSpi {

    private static final String[] NAMES = {"ndpi", "NDPI", "hamamatsu", "Hamamatsu"};
    private static final String[] SUFFIXES = {"ndpi"};
    private static final String[] MIME_TYPES = {"image/x-hamamatsu-ndpi"};

    /**
     * The TIFF readers, which accept an NDPI file because it opens like a TIFF.
     * They would read its offsets truncated to 32 bits and hand its
     * deliberately-mis-sized JPEG to a JPEG decoder, so this reader is ordered
     * ahead of them.
     */
    private static final String[] TIFF_SPIS = {
        "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi",
        "com.twelvemonkeys.imageio.plugins.tiff.BigTIFFImageReaderSpi",
        "com.sun.imageio.plugins.tiff.TIFFImageReaderSpi",
    };

    public NDPIImageReaderSpi() {
        super(
                "ebremer.com",
                "0.1.0",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                "com.ebremer.cygnus.ndpi.imageio.NDPIImageReader",
                new Class<?>[] {ImageInputStream.class},
                null,               // read-only: no writer
                false, null, null, null, null,
                true, NDPIMetadata.NATIVE_FORMAT, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof ImageInputStream stream && NdpiTiff.isNdpi(stream);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        // By now ImageIO has finished scanning, so the TIFF providers this one has to be
        // ordered ahead of are all registered, which they may not have been at onRegistration.
        prioritize();
        return new NDPIImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Hamamatsu NDPI whole-slide image reader";
    }

    /**
     * Orders this reader ahead of the generic TIFF readers in ImageIO's
     * registry, so that {@link javax.imageio.ImageIO#getImageReaders} hands back
     * this one for a slide.
     *
     * <p>ImageIO cannot always establish this by itself. It registers plug-ins
     * in classpath order and offers each one {@link #onRegistration} as it goes,
     * so a TIFF plug-in scanned <em>after</em> this one is not yet there to be
     * ordered against — and with no ordering, which provider ImageIO returns
     * comes down to hash order, which differs from one JVM to the next. This
     * method does the ordering once everything is registered. It is idempotent,
     * and is called whenever this provider creates a reader, so an application
     * that reaches the reader by format name or suffix — both of which name this
     * reader alone and are unaffected — never has to call it. Call it at
     * start-up if {@code ImageIO.read} or {@code ImageIO.getImageReaders} is the
     * first thing that touches a slide.</p>
     */
    public static void prioritize() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        NDPIImageReaderSpi ndpi = registry.getServiceProviderByClass(NDPIImageReaderSpi.class);
        if (ndpi != null) {
            ndpi.orderAheadOfTiffReaders(registry, ImageReaderSpi.class);
        }
    }

    /**
     * Orders this provider ahead of the TIFF providers registered so far.
     * Whether that is all of them depends on the order ImageIO scanned the
     * classpath in; see {@link #prioritize()}.
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        orderAheadOfTiffReaders(registry, category);
    }

    @SuppressWarnings("unchecked")
    private void orderAheadOfTiffReaders(ServiceRegistry registry, Class<?> category) {
        List<ImageReaderSpi> tiffReaders = new ArrayList<>(TIFF_SPIS.length);
        Iterator<ImageReaderSpi> providers =
                registry.getServiceProviders(ImageReaderSpi.class, false);
        while (providers.hasNext()) {
            ImageReaderSpi provider = providers.next();
            if (isTiffReader(provider.getClass().getName())) {
                tiffReaders.add(provider);
            }
        }
        for (ImageReaderSpi tiff : tiffReaders) {
            registry.setOrdering((Class<ImageReaderSpi>) category, this, tiff);
        }
    }

    private static boolean isTiffReader(String className) {
        for (String tiff : TIFF_SPIS) {
            if (tiff.equals(className)) {
                return true;
            }
        }
        return false;
    }
}
