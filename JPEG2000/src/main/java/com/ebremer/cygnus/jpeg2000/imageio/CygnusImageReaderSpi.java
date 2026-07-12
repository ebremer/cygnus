package com.ebremer.cygnus.jpeg2000.imageio;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * Service provider for the Cygnus pure-Java JPEG 2000 reader. Recognizes
 * JP2 files (signature box) and raw JPEG 2000 codestreams (SOC + SIZ).
 */
public final class CygnusImageReaderSpi extends ImageReaderSpi {

    private static final String[] NAMES = {
        "jpeg2000", "JPEG2000", "jpeg 2000", "JPEG 2000", "jp2", "JP2"
    };
    private static final String[] SUFFIXES = {"jp2", "j2k", "j2c", "jpc", "jph", "jhc"};
    private static final String[] MIME_TYPES = {
        "image/jp2", "image/jpeg2000", "image/x-jpeg2000-image", "image/jph"
    };

    public CygnusImageReaderSpi() {
        super(
                "ebremer.com",
                "0.1.0",
                NAMES,
                SUFFIXES,
                MIME_TYPES,
                "com.ebremer.cygnus.jpeg2000.imageio.CygnusImageReader",
                new Class<?>[] {ImageInputStream.class},
                new String[] {"com.ebremer.cygnus.jpeg2000.imageio.CygnusImageWriterSpi"},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return false;
        }
        byte[] head = new byte[12];
        stream.mark();
        try {
            int n = 0;
            while (n < head.length) {
                int r = stream.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n >= 4 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0x4F
                    && (head[2] & 0xFF) == 0xFF && (head[3] & 0xFF) == 0x51) {
                return true; // raw codestream: SOC immediately followed by SIZ
            }
            return n >= 12
                    && head[0] == 0 && head[1] == 0 && head[2] == 0 && head[3] == 12
                    && head[4] == 'j' && head[5] == 'P' && head[6] == ' ' && head[7] == ' '
                    && (head[8] & 0xFF) == 0x0D && (head[9] & 0xFF) == 0x0A
                    && (head[10] & 0xFF) == 0x87 && (head[11] & 0xFF) == 0x0A;
        } finally {
            stream.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new CygnusImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Cygnus pure-Java JPEG 2000 (ISO/IEC 15444-1) image reader";
    }
}
