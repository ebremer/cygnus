package com.ebremer.jpegxl;

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

/** Progressive, upsampling, noise, and patches, validated against djxl. */
class CjxlFeatureTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void checkTools() {
        assumeTrue(JxlTools.available(), "cjxl/djxl not available");
    }

    static int[][] photo(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = (int) (120 + 80 * Math.sin(x * 0.04) * Math.cos(y * 0.03));
                p[1][i] = (int) (110 + 70 * Math.sin((x + y) * 0.02));
                p[2][i] = (int) (100 + 60 * Math.cos(x * 0.015 - y * 0.025));
            }
        }
        return p;
    }

    static Stream<Arguments> tolerantCases() {
        return Stream.of(
                Arguments.of("progressive-vardct", new String[] {"--distance=1.0", "--progressive"}, 300, 270),
                Arguments.of("progressive-dc", new String[] {"--distance=1.0", "--progressive_dc=1"}, 600, 420),
                Arguments.of("upsampling2", new String[] {"--distance=1.0", "--resampling=2"}, 300, 270),
                Arguments.of("upsampling4", new String[] {"--distance=2.0", "--resampling=4"}, 260, 200),
                Arguments.of("upsampling8", new String[] {"--distance=4.0", "--resampling=8"}, 256, 192),
                Arguments.of("noise", new String[] {"--distance=1.0", "--photon_noise_iso=3200"}, 300, 270),
                Arguments.of("patches", new String[] {"--distance=1.0", "--patches=1", "--effort=9"}, 300, 270)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tolerantCases")
    void featureMatchesDjxl(String name, String[] args, int w, int h) throws Exception {
        int[][] planes = photo(w, h);
        byte[] jxl = JxlTools.cjxl(tempDir, name, planes, w, h, 8, false, false, args);
        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] reference = JxlTools.djxl(tempDir, name, jxl);
        assertEquals(w, ours.width, name);
        assertEquals(h, ours.height, name);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            double meanDiff = JxlTools.meanAbsDiff(frame.channels[c], reference[c]);
            assertTrue(maxDiff <= 12, name + " plane " + c + " max diff " + maxDiff);
            assertTrue(meanDiff <= 0.7, name + " plane " + c + " mean diff " + meanDiff);
        }
    }

    @Test
    void patchesLosslessIsExact() throws Exception {
        // repeated tiles make the patch detector fire; lossless, so bit-exact
        int w = 300;
        int h = 260;
        int[][] planes = new int[3][w * h];
        java.util.Random rnd = new java.util.Random(9);
        int[][] tile = new int[3][24 * 24];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < tile[c].length; i++) {
                tile[c][i] = rnd.nextInt(256);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inTile = (x % 60) < 24 && (y % 60) < 24;
                for (int c = 0; c < 3; c++) {
                    planes[c][y * w + x] = inTile
                            ? tile[c][(y % 60) * 24 + (x % 60)]
                            : 230;
                }
            }
        }
        byte[] jxl = JxlTools.cjxl(tempDir, "patches-lossless", planes, w, h, 8, false, false,
                "--distance=0", "--effort=9", "--patches=1");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            assertEquals(0, JxlTools.maxAbsDiff(planes[c], frame.channels[c]), "plane " + c);
        }
    }

    @Test
    void progressiveLossless16Bit() throws Exception {
        int w = 200;
        int h = 160;
        int[][] planes = TestImages.mixed(w, h, 3, 16, 21);
        byte[] jxl = JxlTools.cjxl(tempDir, "prog16", planes, w, h, 16, false, false,
                "--distance=0", "--progressive", "--effort=7");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            assertEquals(0, JxlTools.maxAbsDiff(planes[c], frame.channels[c]), "plane " + c);
        }
    }

    @Test
    void animationTimingInImageIOMetadata() throws Exception {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assumeTrue(p.waitFor() == 0, "ffmpeg not available");
        } catch (Exception e) {
            assumeTrue(false, "ffmpeg not available");
        }
        java.nio.file.Path gif = tempDir.resolve("anim.gif");
        JxlTools.run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5", "-frames:v", "3",
                gif.toString());
        java.nio.file.Path jxlFile = tempDir.resolve("anim.jxl");
        JxlTools.run(JxlTools.find("cjxl"), gif.toString(), jxlFile.toString(), "-d", "0");
        byte[] jxl = java.nio.file.Files.readAllBytes(jxlFile);

        javax.imageio.ImageReader reader =
                javax.imageio.ImageIO.getImageReadersByFormatName("jxl").next();
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO
                .createImageInputStream(new java.io.ByteArrayInputStream(jxl))) {
            reader.setInput(in);
            assertEquals(3, reader.getNumImages(true));
            org.w3c.dom.Node tree = reader.getImageMetadata(0)
                    .getAsTree(com.ebremer.jpegxl.imageio.JXLMetadata.NATIVE_FORMAT);
            org.w3c.dom.Node animation = null;
            org.w3c.dom.Node frameNode = null;
            for (org.w3c.dom.Node n = tree.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeName().equals("Animation")) {
                    animation = n;
                }
                if (n.getNodeName().equals("Frame")) {
                    frameNode = n;
                }
            }
            org.junit.jupiter.api.Assertions.assertNotNull(animation, "Animation node");
            org.junit.jupiter.api.Assertions.assertNotNull(frameNode, "Frame node");
            int tpsNum = Integer.parseInt(animation.getAttributes()
                    .getNamedItem("tpsNumerator").getNodeValue());
            assertTrue(tpsNum > 0, "ticks per second");
            double seconds = Double.parseDouble(frameNode.getAttributes()
                    .getNamedItem("durationSeconds").getNodeValue());
            assertTrue(Math.abs(seconds - 0.2) < 0.05, "frame duration " + seconds);
        } finally {
            reader.dispose();
        }
    }

    @Test
    void independentAlphaResampling() throws Exception {
        int w = 200;
        int h = 160;
        int[][] planes = new int[4][w * h];
        int[][] photo = photo(w, h);
        System.arraycopy(photo, 0, planes, 0, 3);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                planes[3][y * w + x] = (x / 20 + y / 20) % 2 == 0
                        ? 255 : (int) (128 + 90 * Math.sin(x * 0.1));
            }
        }
        byte[] jxl = JxlTools.cjxl(tempDir, "ecres", planes, w, h, 8, false, true,
                "--distance=1.0", "--ec_resampling=2");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        int[][] reference = JxlTools.djxl(tempDir, "ecres", jxl);
        for (int c = 0; c < 4; c++) {
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            assertTrue(maxDiff <= 4, "plane " + c + " max diff " + maxDiff);
        }
    }

    /**
     * Smooth 32-bit float content crossing zero: the sign flips make the
     * residuals wrap mod 2^32, exercising the 32-bit weighted-predictor error
     * arithmetic that wider intermediates would get wrong.
     */
    @Test
    void floatLosslessWithSignFlipsIsExact() throws Exception {
        int w = 96;
        int h = 80;
        float[][] planes = new float[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    planes[c][y * w + x] =
                            (float) (Math.sin(x * 0.31 + c) * Math.cos(y * 0.17) * 0.6 - 0.05);
                }
            }
        }
        byte[] jxl = JxlTools.cjxlPfm(tempDir, "floatsign", planes, w, h, "--effort=7");
        JxlImage ours = JxlDecoder.decode(jxl);
        JxlFrame frame = ours.frames.get(0);
        float[][] reference = JxlTools.djxlPfm(tempDir, "floatsign", jxl, w, h);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                assertEquals(Float.floatToIntBits(reference[c][i]),
                        Float.floatToIntBits(frame.floatChannels[c][i]),
                        "plane " + c + " index " + i);
            }
        }
    }
}
