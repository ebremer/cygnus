package com.ebremer.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlFrame;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.testutil.JxlTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Official conformance test inputs (from libjxl/conformance), decoded and
 * compared against djxl. The directory is provided via the JXL_CONFORMANCE
 * environment variable; tests are skipped when absent.
 */
class ConformanceTest {

    @TempDir
    static Path tempDir;

    private static Path conformanceDir;

    @BeforeAll
    static void locate() {
        String dir = System.getenv("JXL_CONFORMANCE");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
                "JXL_CONFORMANCE not set");
        assumeTrue(JxlTools.available(), "djxl not available");
        conformanceDir = Path.of(dir);
    }

    static Stream<Arguments> cases() {
        // {name, max tolerance, colour comparable}; files whose colours depend
        // on an embedded ICC profile can only be compared structurally until
        // ICC reconstruction is implemented
        return Stream.of(
                Arguments.of("patches", 6, false),        // colours behind an ICC profile
                Arguments.of("patches_lossless", 0, true),
                Arguments.of("noise", 12, true),
                Arguments.of("upsampling", 8, true),
                Arguments.of("progressive", 10, false),   // colours behind an ICC profile
                Arguments.of("delta_palette", 0, true),
                Arguments.of("lz77_flower", 0, true),
                Arguments.of("bicycles", 6, true),
                Arguments.of("grayscale", 6, false),      // colours behind an ICC profile
                Arguments.of("sunset_logo", 32, true),
                Arguments.of("opsin_inverse", 16, true),
                Arguments.of("bike", 32, true),
                Arguments.of("spot", 1, true)
        );
    }

    @org.junit.jupiter.api.Test
    void losslessPfmIsExact() throws Exception {
        Path input = conformanceDir.resolve("lossless_pfm.jxl");
        assumeTrue(Files.exists(input), "lossless_pfm.jxl not present");
        byte[] jxl = Files.readAllBytes(input);
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);

        Path jxlFile = tempDir.resolve("pfm.jxl");
        Path pfm = tempDir.resolve("pfm-ref.pfm");
        Files.write(jxlFile, jxl);
        JxlTools.run(JxlTools.find("djxl"), jxlFile.toString(), pfm.toString());
        float[][] reference = readPfm(pfm, ours.width, ours.height);

        for (int c = 0; c < reference.length; c++) {
            float[] mine = frame.floatChannels[c];
            org.junit.jupiter.api.Assertions.assertNotNull(mine, "expected float plane " + c);
            for (int i = 0; i < mine.length; i++) {
                assertEquals(Float.floatToIntBits(reference[c][i]), Float.floatToIntBits(mine[i]),
                        "float sample mismatch in plane " + c + " at " + i);
            }
        }
    }

    /** Minimal PFM reader (PF/Pf, little- or big-endian, bottom-up rows). */
    private static float[][] readPfm(Path path, int width, int height) throws Exception {
        byte[] data = Files.readAllBytes(path);
        int pos = 0;
        String[] header = new String[3];
        for (int i = 0; i < 3; i++) {
            StringBuilder sb = new StringBuilder();
            while (data[pos] != '\n') {
                sb.append((char) data[pos++]);
            }
            pos++;
            header[i] = sb.toString().trim();
        }
        int channels = header[0].equals("PF") ? 3 : 1;
        String[] dims = header[1].split("\\s+");
        assertEquals(width, Integer.parseInt(dims[0]), "pfm width");
        assertEquals(height, Integer.parseInt(dims[1]), "pfm height");
        boolean littleEndian = Float.parseFloat(header[2]) < 0;
        float[][] planes = new float[channels][width * height];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data, pos, data.length - pos)
                .order(littleEndian ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
        for (int y = height - 1; y >= 0; y--) { // bottom-up
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < channels; c++) {
                    planes[c][y * width + x] = buf.getFloat();
                }
            }
        }
        return planes;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void conformance(String name, int tolerance, boolean colourComparable) throws Exception {
        Path input = conformanceDir.resolve(name + ".jxl");
        assumeTrue(Files.exists(input), name + ".jxl not present");
        byte[] jxl = Files.readAllBytes(input);

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] reference = JxlTools.djxl(tempDir, "conf-" + name, jxl,
                ours.metadata.bitDepth.bitsPerSample);

        JxlFrame frame = ours.frames.get(0);
        int channels = Math.min(frame.channels.length, reference.length);
        assertTrue(channels >= 1, "no channels to compare");
        int colourChannels = ours.metadata.colourEncoding.isGrey() ? 1 : 3;
        for (int c = 0; c < channels; c++) {
            assertEquals(frame.channels[c].length, reference[c].length,
                    name + " plane " + c + " size");
            if (!colourComparable && c < colourChannels) {
                continue; // colours go through an ICC profile we do not reconstruct yet
            }
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            double meanDiff = JxlTools.meanAbsDiff(frame.channels[c], reference[c]);
            assertTrue(maxDiff <= tolerance,
                    name + " plane " + c + " max diff " + maxDiff);
            assertTrue(meanDiff <= (tolerance == 0 ? 0.0 : 0.7),
                    name + " plane " + c + " mean diff " + meanDiff);
        }
    }
}
