package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import org.junit.jupiter.api.Test;

/**
 * Repeated-tile coding through patches: a screenshot's recurring glyphs are coded
 * once into a reference frame and stamped at each site. Lossless, and it never
 * makes an image bigger.
 */
class PatchEncodeTest {

    /** Solid background with a handful of 16x16 glyphs stamped over a grid. */
    private static int[][] screenshot(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int i = 0; i < w * h; i++) {
            p[0][i] = 240;
            p[1][i] = 240;
            p[2][i] = 245;
        }
        int[][] glyphs = new int[4][16 * 16 * 3];
        java.util.Random r = new java.util.Random(1);
        for (int g = 0; g < 4; g++) {
            for (int i = 0; i < glyphs[g].length; i++) {
                glyphs[g][i] = r.nextInt(256);
            }
        }
        for (int ty = 0; ty < h / 16; ty++) {
            for (int tx = 0; tx < w / 16; tx++) {
                if (((tx + ty) % 4) != 0) {
                    continue;
                }
                int g = (tx * 3 + ty * 5) % 4;
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            p[c][(ty * 16 + y) * w + tx * 16 + x] = glyphs[g][(c * 16 + y) * 16 + x];
                        }
                    }
                }
            }
        }
        return p;
    }

    @Test
    void repeatedGlyphsCodeSmallerAndLossless() throws Exception {
        int w = 256;
        int h = 256;
        int[][] src = screenshot(w, h);
        byte[] plain = JxlEncoder.encode(src, w, h, 8, false, false, false);
        byte[] patched = JxlEncoder.encodeWithPatches(src, w, h, 8, false);

        assertTrue(patched.length < plain.length,
                "patches should shrink a repetitive image: " + patched.length + " vs " + plain.length);
        JxlImage img = JxlDecoder.decode(patched);
        int[][] out = img.frames.get(0).channels;
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(src[c], out[c], "channel " + c + " must be lossless");
        }
    }

    /** A non-repetitive image just falls through to the plain encode — never larger. */
    @Test
    void photographFallsThroughUnharmed() throws Exception {
        int w = 128;
        int h = 96;
        int[][] src = new int[3][w * h];
        java.util.Random r = new java.util.Random(9);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                src[0][i] = (x * 255 / (w - 1) + r.nextInt(16)) & 0xff;
                src[1][i] = (y * 255 / (h - 1) + r.nextInt(16)) & 0xff;
                src[2][i] = ((x + y) * 127 / (w + h) + r.nextInt(16)) & 0xff;
            }
        }
        byte[] plain = JxlEncoder.encode(src, w, h, 8, false, false, false);
        byte[] patched = JxlEncoder.encodeWithPatches(src, w, h, 8, false);

        assertTrue(patched.length <= plain.length, "must not grow a non-repetitive image");
        JxlImage img = JxlDecoder.decode(patched);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(src[c], img.frames.get(0).channels[c], "lossless fallthrough");
        }
    }
}
