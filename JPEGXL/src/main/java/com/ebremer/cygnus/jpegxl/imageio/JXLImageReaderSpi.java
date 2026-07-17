package com.ebremer.cygnus.jpegxl.imageio;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/** Service provider for the JPEG XL image reader. */
public final class JXLImageReaderSpi extends ImageReaderSpi {

    private static final String[] NAMES = {"JPEG XL", "jpegxl", "jxl"};
    private static final String[] SUFFIXES = {"jxl"};
    private static final String[] MIME_TYPES = {"image/jxl"};

    public JXLImageReaderSpi() {
        super(
                "Erich Bremer",
                "0.1",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                JXLImageReader.class.getName(),
                new Class<?>[] {ImageInputStream.class},
                new String[] {JXLImageWriterSpi.class.getName()},
                // the reader serves JXLMetadata for both the stream and each
                // image, and it speaks the standard format as well as its own
                true, JXLMetadata.NATIVE_FORMAT,
                JXLMetadataFormat.class.getName(), null, null,
                true, JXLMetadata.NATIVE_FORMAT,
                JXLMetadataFormat.class.getName(), null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return false;
        }
        byte[] header = new byte[12];
        stream.mark();
        try {
            stream.readFully(header);
        } catch (IOException e) {
            return false;
        } finally {
            stream.reset();
        }
        if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0x0a) {
            return true; // bare codestream
        }
        byte[] box = com.ebremer.cygnus.jpegxl.container.Container.SIGNATURE_BOX;
        for (int i = 0; i < box.length; i++) {
            if (header[i] != box[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new JXLImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Pure-Java JPEG XL (ISO/IEC 18181) reader";
    }
}
