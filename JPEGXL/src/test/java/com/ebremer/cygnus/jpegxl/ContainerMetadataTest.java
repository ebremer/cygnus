package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ebremer.cygnus.jpegxl.codestream.ColourEncoding;
import com.ebremer.cygnus.jpegxl.container.Container;
import com.ebremer.cygnus.jpegxl.container.GainMap;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Container metadata boxes: Exif, XMP, brob compression and the gain map. */
class ContainerMetadataTest {

    private static byte[] smallCodestream(int seed) throws Exception {
        int w = 48;
        int h = 32;
        int[][] p = new int[3][w * h];
        for (int i = 0; i < w * h; i++) {
            p[0][i] = (i + seed) & 255;
            p[1][i] = (i * 3 + seed) & 255;
            p[2][i] = 200;
        }
        return JxlEncoder.encode(p, w, h, 8, false, false, false);
    }

    private static final byte[] EXIF = {
        'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0,
    };
    private static final byte[] XMP =
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">test</x:xmpmeta>"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void exifAndXmpRoundTrip() throws Exception {
        byte[] cs = smallCodestream(0);
        for (boolean brob : new boolean[] {false, true}) {
            byte[] file = Container.wrap(cs, EXIF, XMP, brob);
            JxlImage image = JxlDecoder.decode(file);
            assertArrayEquals(EXIF, image.exif, "exif (brob=" + brob + ")");
            assertArrayEquals(XMP, image.xmp, "xmp (brob=" + brob + ")");
            assertEquals(48, image.width);
            assertNull(image.gainMap);
        }
    }

    @Test
    void bareCodestreamHasNoMetadata() throws Exception {
        JxlImage image = JxlDecoder.decode(smallCodestream(0));
        assertNull(image.exif);
        assertNull(image.xmp);
        assertNull(image.gainMap);
    }

    @Test
    void gainMapRoundTrip() throws Exception {
        byte[] cs = smallCodestream(1);
        GainMap gm = new GainMap();
        gm.version = 0;
        gm.metadata = new byte[] {1, 2, 3, 4, 5};
        gm.colourEncoding = new ColourEncoding();
        gm.altIcc = new byte[] {9, 8, 7};
        gm.gainMapCodestream = smallCodestream(7);

        byte[] file = Container.wrap(cs, EXIF, null, gm, false);
        JxlImage image = JxlDecoder.decode(file);
        assertNotNull(image.gainMap, "gain map surfaced");
        assertEquals(0, image.gainMap.version);
        assertArrayEquals(gm.metadata, image.gainMap.metadata);
        assertNotNull(image.gainMap.colourEncoding);
        assertArrayEquals(gm.altIcc, image.gainMap.altIcc);
        assertArrayEquals(gm.gainMapCodestream, image.gainMap.gainMapCodestream);
        assertArrayEquals(EXIF, image.exif);

        // the embedded gain map is itself a decodable JPEG XL codestream
        JxlImage gmImage = JxlDecoder.decode(image.gainMap.gainMapCodestream);
        assertEquals(48, gmImage.width);
        assertEquals(32, gmImage.height);
    }

    @Test
    void gainMapWithoutColourEncoding() throws Exception {
        GainMap gm = new GainMap();
        gm.gainMapCodestream = smallCodestream(3);
        GainMap back = GainMap.parse(gm.toBytes());
        assertNull(back.colourEncoding);
        assertEquals(0, back.metadata.length);
        assertArrayEquals(gm.gainMapCodestream, back.gainMapCodestream);
    }
}
