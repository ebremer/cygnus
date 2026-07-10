package com.ebremer.cygnus.imageio;

import java.util.Locale;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * Service provider for the Cygnus pure-Java JPEG 2000 writer (JP2 files and
 * raw codestreams).
 */
public final class CygnusImageWriterSpi extends ImageWriterSpi {

    private static final String[] NAMES = {
        "jpeg2000", "JPEG2000", "jpeg 2000", "JPEG 2000", "jp2", "JP2"
    };
    private static final String[] SUFFIXES = {"jp2", "j2k", "j2c", "jpc"};
    private static final String[] MIME_TYPES = {
        "image/jp2", "image/jpeg2000", "image/x-jpeg2000-image"
    };

    public CygnusImageWriterSpi() {
        super(
                "ebremer.com",
                "0.1.0",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                "com.ebremer.cygnus.imageio.CygnusImageWriter",
                new Class<?>[] {ImageOutputStream.class},
                new String[] {"com.ebremer.cygnus.imageio.CygnusImageReaderSpi"},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        java.awt.image.SampleModel sm = type.getSampleModel();
        if (type.getColorModel() instanceof java.awt.image.IndexColorModel) {
            return true; // expanded through the palette
        }
        for (int b = 0; b < sm.getNumBands(); b++) {
            if (sm.getSampleSize(b) > 26) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new CygnusImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Cygnus pure-Java JPEG 2000 (ISO/IEC 15444-1) image writer";
    }
}
