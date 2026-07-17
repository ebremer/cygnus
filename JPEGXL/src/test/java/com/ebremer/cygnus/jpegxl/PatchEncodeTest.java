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

    /**
     * Two glyphs that agree in their low 16 bits must stay two glyphs: the
     * tile-identity key has to carry all of a deep sample's bits, or one glyph
     * is silently stamped over the other's sites. (Regression: the key kept
     * only the low 16 bits of each sample.)
     */
    @Test
    void deepGlyphsCollidingInTheLow16BitsStayDistinct() throws Exception {
        int w = 256;
        int h = 256;
        int bits = 17;
        int[][] src = new int[3][w * h];
        for (int c = 0; c < 3; c++) {
            java.util.Arrays.fill(src[c], 70000);
        }
        int[] glyph = new int[16 * 16 * 3];
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < glyph.length; i++) {
            glyph[i] = r.nextInt(1 << bits);
        }
        for (int ty = 0; ty < h / 16; ty++) {
            for (int tx = 0; tx < w / 16; tx++) {
                if ((tx + ty) % 4 != 0) {
                    continue;
                }
                int flip = tx % 2 == 0 ? 0 : 1 << 16; // second glyph: bit 16 flipped
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            src[c][(ty * 16 + y) * w + tx * 16 + x] =
                                    glyph[(c * 16 + y) * 16 + x] ^ flip;
                        }
                    }
                }
            }
        }
        byte[] plain = JxlEncoder.encode(src, w, h, bits, false, false, false);
        byte[] patched = JxlEncoder.encodeWithPatches(src, w, h, bits, false);
        assertTrue(patched.length < plain.length,
                "patches should engage: " + patched.length + " vs " + plain.length);
        int[][] out = JxlDecoder.decode(patched).frames.get(0).channels;
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
