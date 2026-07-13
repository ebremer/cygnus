package com.ebremer.cygnus.svs.imageio;

import com.ebremer.cygnus.svs.SVSStructure;
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
 * Service provider for the Aperio SVS reader. Recognizes a TIFF or BigTIFF
 * whose first directory carries an Aperio {@code ImageDescription}, which is
 * what separates a slide from any other TIFF.
 */
public final class SVSImageReaderSpi extends ImageReaderSpi {

    private static final String[] NAMES = {"svs", "SVS", "aperio", "Aperio"};
    private static final String[] SUFFIXES = {"svs"};
    private static final String[] MIME_TYPES = {"image/x-aperio-svs"};

    /**
     * The TIFF readers that also accept a slide. They would decode it as a
     * plain multi-page TIFF — handing back the full-resolution level whole —
     * so this reader is ordered ahead of them.
     */
    private static final String[] TIFF_SPIS = {
        "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi",
        "com.twelvemonkeys.imageio.plugins.tiff.BigTIFFImageReaderSpi",
        "com.sun.imageio.plugins.tiff.TIFFImageReaderSpi",
    };

    public SVSImageReaderSpi() {
        super(
                "ebremer.com",
                "0.1.0",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                "com.ebremer.cygnus.svs.imageio.SVSImageReader",
                new Class<?>[] {ImageInputStream.class},
                null,               // read-only: no writer
                false, null, null, null, null,
                true, SVSMetadata.NATIVE_FORMAT, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof ImageInputStream stream && SVSStructure.isAperioTiff(stream);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        // By now ImageIO has finished scanning, so the TIFF providers this one has to be
        // ordered ahead of are all registered, which they may not have been at onRegistration.
        prioritize();
        return new SVSImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Aperio SVS whole-slide image reader";
    }

    /**
     * Orders this reader ahead of the generic TIFF readers in ImageIO's
     * registry, so that {@link javax.imageio.ImageIO#getImageReaders} hands
     * back this one for a slide.
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
        SVSImageReaderSpi svs = registry.getServiceProviderByClass(SVSImageReaderSpi.class);
        if (svs != null) {
            svs.orderAheadOfTiffReaders(registry, ImageReaderSpi.class);
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
