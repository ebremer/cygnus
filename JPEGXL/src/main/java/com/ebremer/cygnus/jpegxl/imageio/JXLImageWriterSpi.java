package com.ebremer.cygnus.jpegxl.imageio;

import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.util.Locale;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/** Service provider for the JPEG XL (lossless) image writer. */
public final class JXLImageWriterSpi extends ImageWriterSpi {

    private static final String[] NAMES = {"JPEG XL", "jpegxl", "jxl"};
    private static final String[] SUFFIXES = {"jxl"};
    private static final String[] MIME_TYPES = {"image/jxl"};

    public JXLImageWriterSpi() {
        super(
                "Erich Bremer",
                "0.1",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                JXLImageWriter.class.getName(),
                new Class<?>[] {ImageOutputStream.class},
                new String[] {JXLImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        ColorModel cm = type.getColorModel();
        SampleModel sm = type.getSampleModel();
        int bands = sm.getNumBands();
        if (bands < 1 || bands > 4) {
            return false;
        }
        for (int i = 0; i < bands; i++) {
            int size = sm.getSampleSize(i);
            if (size < 1 || size > 16) {
                return false;
            }
        }
        return cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_RGB
                || cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new JXLImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Pure-Java JPEG XL (ISO/IEC 18181) lossless writer";
    }
}
