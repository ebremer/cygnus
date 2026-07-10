package com.ebremer.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.jpegxl.container.Container;
import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlFrame;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.encoder.JxlEncoder;
import com.ebremer.jpegxl.testutil.TestImages;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Encoder → decoder round trips must be bit-exact. */
class RoundTripTest {

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(1, 1, 8, true, false),
                Arguments.of(7, 5, 8, true, false),
                Arguments.of(64, 64, 8, false, false),
                Arguments.of(256, 256, 8, false, true),
                Arguments.of(257, 130, 8, false, false),   // spans two groups horizontally
                Arguments.of(300, 300, 8, false, true),    // 2x2 groups
                Arguments.of(513, 40, 8, false, false),    // three groups wide
                Arguments.of(31, 900, 8, true, true),      // four groups tall
                Arguments.of(40, 30, 16, false, false),
                Arguments.of(300, 270, 16, false, true),
                Arguments.of(129, 65, 16, true, false)
        );
    }

    @ParameterizedTest(name = "{0}x{1} bits={2} grey={3} alpha={4}")
    @MethodSource("configurations")
    void roundTrip(int width, int height, int bits, boolean grey, boolean alpha) throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] original = TestImages.mixed(width, height, planes, bits, 42);
        verifyRoundTrip(original, width, height, bits, grey, alpha);
    }

    @Test
    void flatImage() throws IOException {
        int[][] original = TestImages.flat(100, 80, 3, 200);
        verifyRoundTrip(original, 100, 80, 8, false, false);
    }

    @Test
    void deepIntegerRoundTrip() throws IOException {
        // 28-bit samples exceed float32 precision; the exact integer path
        // must carry them through unchanged
        int[][] original = TestImages.mixed(200, 130, 4, 28, 77);
        verifyRoundTrip(original, 200, 130, 28, false, true);
    }

    @Test
    void thirtyOneBitRoundTrip() throws IOException {
        int w = 300;
        int h = 220; // multiple groups
        java.util.Random rnd = new java.util.Random(5);
        int[][] original = new int[3][w * h];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                original[c][i] = rnd.nextInt() & 0x7FFFFFFF;
            }
        }
        verifyRoundTrip(original, w, h, 31, false, false);
    }

    @Test
    void previewRoundTrip() throws IOException {
        int[][] main = TestImages.mixed(120, 90, 3, 8, 5);
        int[][] preview = TestImages.mixed(30, 22, 3, 8, 6);
        byte[] jxl = JxlEncoder.encodeWithPreview(main, 120, 90, 8, false, false, false,
                preview, 30, 22);
        JxlImage image = JxlDecoder.decode(jxl);
        assertEquals(120, image.width);
        assertEquals(1, image.frames.size());
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(main[c], image.frames.get(0).channels[c], "main plane " + c);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(image.preview, "preview frame");
        assertEquals(30, image.preview.width);
        assertEquals(22, image.preview.height);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(preview[c], image.preview.channels[c], "preview plane " + c);
        }
    }

    @Test
    void noiseImage() throws IOException {
        int[][] original = TestImages.noise(333, 222, 4, 8, 7);
        verifyRoundTrip(original, 333, 222, 8, false, true);
    }

    @Test
    void flat16BitSingleValue() throws IOException {
        int[][] original = TestImages.flat(64, 64, 1, 65535);
        verifyRoundTrip(original, 64, 64, 16, true, false);
    }

    @Test
    void containerWrapping() throws IOException {
        int[][] original = TestImages.mixed(50, 40, 3, 8, 1);
        byte[] bare = JxlEncoder.encode(original, 50, 40, 8, false, false, false);
        byte[] boxed = Container.wrap(bare);
        JxlImage image = JxlDecoder.decode(boxed);
        JxlFrame frame = image.frames.get(0);
        for (int p = 0; p < 3; p++) {
            assertArrayEquals(original[p], frame.channels[p]);
        }
    }

    private static void verifyRoundTrip(int[][] original, int width, int height, int bits,
            boolean grey, boolean alpha) throws IOException {
        int[][] copies = new int[original.length][];
        for (int i = 0; i < original.length; i++) {
            copies[i] = original[i].clone();
        }
        byte[] encoded = JxlEncoder.encode(copies, width, height, bits, grey, alpha, false);
        JxlImage image = JxlDecoder.decode(encoded);
        assertEquals(width, image.width);
        assertEquals(height, image.height);
        assertEquals(1, image.frames.size());
        JxlFrame frame = image.frames.get(0);
        assertEquals(original.length, frame.channels.length);
        for (int p = 0; p < original.length; p++) {
            assertArrayEquals(original[p], frame.channels[p], "plane " + p);
        }
    }
}
