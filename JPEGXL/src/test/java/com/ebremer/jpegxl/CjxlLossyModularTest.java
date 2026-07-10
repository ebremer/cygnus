package com.ebremer.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlFrame;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.testutil.JxlTools;
import com.ebremer.jpegxl.testutil.TestImages;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * XYB-encoded modular ("lossy modular") files produced by cjxl, compared
 * against djxl's output within a small tolerance (the pipeline is float).
 */
class CjxlLossyModularTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void checkTools() {
        assumeTrue(JxlTools.available(), "cjxl/djxl not available");
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("smooth", 210, 160, 1.0, 7),
                Arguments.of("smooth", 210, 160, 2.0, 7),
                Arguments.of("smooth", 300, 270, 1.0, 5),   // multiple groups
                Arguments.of("noisy", 120, 90, 1.0, 7),
                Arguments.of("smooth", 120, 90, 1.0, 3)
        );
    }

    @ParameterizedTest(name = "{0} {1}x{2} d={3} e={4}")
    @MethodSource("cases")
    void lossyModularMatchesDjxl(String kind, int w, int h, double distance, int effort)
            throws Exception {
        int[][] planes = kind.equals("smooth")
                ? smooth(w, h)
                : TestImages.noise(w, h, 3, 8, 17);
        String name = kind + "-m-" + w + "x" + h + "-" + distance + "-" + effort;
        byte[] jxl = JxlTools.cjxl(tempDir, name, planes, w, h, 8, false, false,
                "--modular=1", "--distance=" + distance, "--effort=" + effort);

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] reference = JxlTools.djxl(tempDir, name, jxl);

        assertEquals(w, ours.width);
        assertEquals(h, ours.height);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            double meanDiff = JxlTools.meanAbsDiff(frame.channels[c], reference[c]);
            // small deviations from libjxl are expected: the float pipeline uses
            // exact math where libjxl uses fast approximations
            assertTrue(maxDiff <= 3, "plane " + c + " max diff " + maxDiff);
            assertTrue(meanDiff <= 0.5, "plane " + c + " mean diff " + meanDiff);
        }
    }

    @Test
    void progressiveLosslessIsExact() throws Exception {
        // -p triggers squeeze-based progressive coding: multi-pass modular with
        // LF-group channels; lossless, so the result must be bit-exact
        int w = 333;
        int h = 261;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 3);
        byte[] jxl = JxlTools.cjxl(tempDir, "prog-lossless", planes, w, h, 8, false, false,
                "--distance=0", "--effort=7", "--progressive");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(planes[c], frame.channels[c], "plane " + c);
        }
    }

    @Test
    void responsiveLosslessIsExact() throws Exception {
        // --responsive forces the squeeze transform without multiple passes
        int w = 210;
        int h = 170;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 4);
        byte[] jxl = JxlTools.cjxl(tempDir, "responsive-lossless", planes, w, h, 8, false, false,
                "--distance=0", "--effort=7", "--responsive=1");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(planes[c], frame.channels[c], "plane " + c);
        }
    }

    private static int[][] smooth(int w, int h) {
        int[][] planes = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                planes[0][i] = (int) (127 + 100 * Math.sin(x * 0.05) * Math.cos(y * 0.07));
                planes[1][i] = (int) (127 + 80 * Math.sin((x + y) * 0.03));
                planes[2][i] = (int) (127 + 60 * Math.cos(x * 0.02 - y * 0.04));
            }
        }
        return planes;
    }
}
