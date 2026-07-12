package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.testutil.JxlTools;
import com.ebremer.cygnus.jpegxl.testutil.TestImages;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** VarDCT (lossy) files produced by cjxl, compared against djxl within tolerance. */
class CjxlVarDctTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void checkTools() {
        assumeTrue(JxlTools.available(), "cjxl/djxl not available");
    }

    static int[][] photo(int w, int h, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double base = 110 + 70 * Math.sin(x * 0.021) * Math.cos(y * 0.017)
                        + 40 * Math.sin((x + 2 * y) * 0.005);
                int edge = (x / 37 + y / 29) % 2 == 0 ? 18 : -14;
                int n = rnd.nextInt(7) - 3;
                p[0][i] = clamp(base + edge + n + 20 * Math.sin(y * 0.05));
                p[1][i] = clamp(base + 0.6 * edge + n);
                p[2][i] = clamp(base * 0.8 + 30 + n - edge);
            }
        }
        return p;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(120, 90, "1.0", 7),    // single group
                Arguments.of(120, 90, "4.0", 7),
                Arguments.of(300, 270, "1.0", 7),   // multiple groups
                Arguments.of(300, 270, "2.5", 5),
                Arguments.of(600, 420, "1.0", 8),   // larger transforms at higher effort
                Arguments.of(600, 420, "3.0", 9),
                Arguments.of(120, 90, "1.0", 1),    // fastest effort
                Arguments.of(300, 270, "1.5", 3)
        );
    }

    @ParameterizedTest(name = "{0}x{1} d={2} e={3}")
    @MethodSource("cases")
    void varDctMatchesDjxl(int w, int h, String distance, int effort) throws Exception {
        int[][] planes = photo(w, h, 1000L + effort);
        String name = "vardct-" + w + "x" + h + "-" + distance + "-" + effort;
        byte[] jxl = JxlTools.cjxl(tempDir, name, planes, w, h, 8, false, false,
                "--distance=" + distance, "--effort=" + effort);

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] reference = JxlTools.djxl(tempDir, name, jxl);

        assertEquals(w, ours.width);
        assertEquals(h, ours.height);
        JxlFrame frame = ours.frames.get(0);
        // isolated few-LSB deviations from djxl grow with the quantisation
        // scale; the mean bound is what catches real decoding errors
        int maxTolerance = 4 + (int) Math.ceil(2 * Double.parseDouble(distance));
        for (int c = 0; c < 3; c++) {
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            double meanDiff = JxlTools.meanAbsDiff(frame.channels[c], reference[c]);
            assertTrue(maxDiff <= maxTolerance, "plane " + c + " max diff " + maxDiff);
            assertTrue(meanDiff <= 0.6, "plane " + c + " mean diff " + meanDiff);
        }
    }

    @ParameterizedTest(name = "grey {0}x{1} d={2} e={3}")
    @MethodSource("greyCases")
    void greyVarDct(int w, int h, String distance, int effort) throws Exception {
        int[][] grey = new int[1][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                grey[0][y * w + x] = clamp(128 + 90 * Math.sin(x * 0.03) * Math.cos(y * 0.02));
            }
        }
        String name = "vardct-grey-" + w + "x" + h + "-" + effort;
        byte[] jxl = JxlTools.cjxl(tempDir, name, grey, w, h, 8, true, false,
                "--distance=" + distance, "--effort=" + effort);
        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] reference = JxlTools.djxl(tempDir, name, jxl);
        JxlFrame frame = ours.frames.get(0);
        assertEquals(1, frame.channels.length - ours.metadata.numExtraChannels());
        int maxDiff = JxlTools.maxAbsDiff(frame.channels[0], reference[0]);
        assertTrue(maxDiff <= 4, "max diff " + maxDiff);
    }

    static Stream<Arguments> greyCases() {
        return Stream.of(
                Arguments.of(150, 110, "1.0", 7),
                Arguments.of(300, 270, "2.0", 5)
        );
    }
}
