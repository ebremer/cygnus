package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import com.ebremer.cygnus.jpegxl.testutil.TestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Progressive (responsive) lossless encoding: the Squeeze transform rewrites the
 * channels into a small image of the picture followed by the detail that doubles
 * it, and the frame is cut into passes so that a prefix of the bytes is already
 * a whole picture.
 */
class ProgressiveTest {

    @TempDir
    static Path tempDir;

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of(64, 64, 8, false, false),
                Arguments.of(256, 256, 8, false, false),     // one group
                Arguments.of(300, 200, 8, false, false),
                Arguments.of(512, 512, 8, false, false),
                Arguments.of(1024, 768, 8, false, false),
                Arguments.of(2048, 1024, 8, false, false),   // reaches the LF groups
                Arguments.of(640, 480, 8, true, false),      // greyscale
                Arguments.of(640, 480, 16, false, false),    // 16-bit
                Arguments.of(320, 240, 24, false, false),    // 24-bit
                Arguments.of(300, 300, 8, false, true),      // alpha
                Arguments.of(8, 8, 8, false, false),         // smaller than a squeeze step
                Arguments.of(1, 100, 8, false, false),       // one pixel wide
                Arguments.of(100, 1, 8, false, false),
                Arguments.of(17, 33, 8, false, false),       // odd at every level
                // A squeeze on an odd width leaves a residual one column narrower
                // than its average, so where the last group column is a single
                // pixel the residual is clipped to nothing. A channel with nothing
                // of it in a group is left out of that group's stream entirely,
                // which renumbers every channel after it — and the number is an
                // MA-tree property. These are the shapes that catch it.
                Arguments.of(513, 257, 8, false, false),
                Arguments.of(513, 256, 8, false, false),
                Arguments.of(257, 257, 8, false, false),
                Arguments.of(1025, 768, 8, false, false),
                Arguments.of(1024, 769, 8, false, false));
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void progressiveIsStillLossless(int w, int h, int bits, boolean grey, boolean alpha)
            throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        for (String kind : new String[] {"mixed", "noise", "flat"}) {
            int[][] px = switch (kind) {
                case "mixed" -> TestImages.mixed(w, h, planes, bits, 3);
                case "noise" -> TestImages.noise(w, h, planes, bits, 3);
                default -> TestImages.flat(w, h, planes, (1 << (bits - 1)) - 1);
            };
            byte[] jxl = JxlEncoder.encodeProgressive(px, w, h, bits, grey, alpha, false);
            JxlImage image = JxlDecoder.decode(jxl);
            assertEquals(w, image.width);
            assertEquals(h, image.height);
            for (int c = 0; c < planes; c++) {
                assertArrayEquals(px[c], image.frames.get(0).channels[c],
                        kind + " " + w + "x" + h + " plane " + c);
            }
        }
    }

    /**
     * Squeeze changes the layout, not the ratio: the file should stay in the same
     * league as the plain one. (It is usually a little larger — the residuals cost
     * roughly what the samples did, and there are more sub-streams to spend
     * headers on.)
     */
    @Test
    void ratioStaysCloseToPlainLossless() throws IOException {
        int w = 1024;
        int h = 768;
        int[][] px = TestImages.mixed(w, h, 3, 8, 3);
        int plain = JxlEncoder.encode(px, w, h, 8, false, false, false).length;
        int prog = JxlEncoder.encodeProgressive(px, w, h, 8, false, false, false).length;
        assertTrue(prog < plain * 1.20, "progressive " + prog + " vs plain " + plain);
    }

    /**
     * The point of the exercise. A prefix of a progressive file decodes to the
     * <em>whole</em> image, coarsely — the missing residuals simply do not sharpen
     * anything. A prefix of a plain file decodes to part of the image and leaves
     * the rest black, because what is missing there is whole groups.
     */
    @Test
    void aPrefixOfTheBytesIsAWholeImage() throws IOException {
        int w = 1024;
        int h = 768;
        int n = w * h;
        int[][] px = TestImages.mixed(w, h, 3, 8, 3);
        byte[] prog = JxlEncoder.encodeProgressive(px, w, h, 8, false, false, false);
        byte[] plain = JxlEncoder.encode(px, w, h, 8, false, false, false);

        int[][] coarse = JxlDecoder.decodePartial(
                Arrays.copyOf(prog, prog.length / 5)).frames.get(0).channels;
        assertEquals(100.0, covered(coarse, n), 0.001,
                "a fifth of a progressive file should still cover the whole image");
        assertTrue(meanError(px, coarse, n) < 40,
                "and resemble it: mean error " + meanError(px, coarse, n));

        int[][] partial = JxlDecoder.decodePartial(
                Arrays.copyOf(plain, plain.length / 5)).frames.get(0).channels;
        assertTrue(covered(partial, n) < 90,
                "a fifth of a plain file leaves holes, by contrast: "
                        + covered(partial, n) + "% covered");
    }

    /** More bytes, more picture: the error must never go up. */
    @Test
    void everyFurtherPassRefinesWhatIsAlreadyThere() throws IOException {
        int w = 700;
        int h = 500;
        int n = w * h;
        int[][] px = TestImages.mixed(w, h, 3, 8, 9);
        byte[] prog = JxlEncoder.encodeProgressive(px, w, h, 8, false, false, false);
        double previous = Double.MAX_VALUE;
        for (double f : new double[] {0.3, 0.5, 0.7, 0.9, 1.0}) {
            int[][] out = JxlDecoder.decodePartial(
                    Arrays.copyOf(prog, (int) (prog.length * f))).frames.get(0).channels;
            double err = meanError(px, out, n);
            assertTrue(err <= previous + 1e-9,
                    "error rose from " + previous + " to " + err + " at " + f);
            previous = err;
        }
        assertEquals(0.0, previous, 1e-9, "the whole file must be exact");
    }

    /** A complete file decodes the same whether or not truncation is allowed. */
    @Test
    void partialDecodeOfAWholeFileIsJustADecode() throws IOException {
        int w = 400;
        int h = 300;
        int[][] px = TestImages.mixed(w, h, 3, 8, 11);
        byte[] jxl = JxlEncoder.encodeProgressive(px, w, h, 8, false, false, false);
        int[][] whole = JxlDecoder.decode(jxl).frames.get(0).channels;
        int[][] partial = JxlDecoder.decodePartial(jxl).frames.get(0).channels;
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(px[c], whole[c], "plane " + c);
            assertArrayEquals(px[c], partial[c], "plane " + c);
        }
    }

    /**
     * A squeeze residual is a difference of samples less a tendency built from
     * further differences, and the format codes it in 32 bits. Deep enough
     * samples overflow that, and it must say so rather than wrap into a wrong
     * picture.
     */
    @ParameterizedTest
    @ValueSource(ints = {25, 26, 27})
    void deepSamplesStayLossless(int bits) throws IOException {
        int w = 96;
        int h = 96;
        int[][] px = extremes(w, h, 3, bits);
        byte[] jxl = JxlEncoder.encodeProgressive(px, w, h, bits, false, false, false);
        int[][] out = JxlDecoder.decode(jxl).frames.get(0).channels;
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(px[c], out[c], bits + "-bit plane " + c);
        }
    }

    @Test
    void samplesTooDeepToSqueezeAreRefused() {
        int w = 96;
        int h = 96;
        int[][] px = extremes(w, h, 3, 31);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeProgressive(px, w, h, 31, false, false, false));
        assertTrue(e.getMessage().contains("32 bits"), e.getMessage());
        // and the ordinary encoder still takes them
        assertEquals(w * h, assertDoesNotThrowEncode(px, w, h, 31));
    }

    private static int assertDoesNotThrowEncode(int[][] px, int w, int h, int bits) {
        try {
            byte[] jxl = JxlEncoder.encode(px, w, h, bits, false, false, false);
            int[][] out = JxlDecoder.decode(jxl).frames.get(0).channels;
            for (int c = 0; c < 3; c++) {
                assertArrayEquals(px[c], out[c], "plane " + c);
            }
            return w * h;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Decoded by libjxl, which is the only way to know that our encoder and our
     * decoder are not simply agreeing with each other about something wrong. The
     * ragged shapes are here because they are what caught exactly that.
     */
    @Test
    void ffmpegDecodesProgressiveOutput() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg with JPEG XL support not available");
        for (int[] shape : new int[][] {{1024, 768}, {300, 200}, {513, 257}, {1025, 768}}) {
            int w = shape[0];
            int h = shape[1];
            int[][] px = TestImages.mixed(w, h, 3, 8, 3);
            byte[] jxl = JxlEncoder.encodeProgressive(px, w, h, 8, false, false, false);

            Path jxlFile = tempDir.resolve("prog.jxl");
            Path rawFile = tempDir.resolve("prog.raw");
            Files.write(jxlFile, jxl);
            Files.deleteIfExists(rawFile);
            Process p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", jxlFile.toString(), "-f", "rawvideo", "-pix_fmt", "rgb24",
                    rawFile.toString()).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0,
                    w + "x" + h + ": ffmpeg failed: " + log);

            byte[] raw = Files.readAllBytes(rawFile);
            assertEquals(w * h * 3, raw.length);
            for (int i = 0; i < w * h; i++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(px[c][i], raw[i * 3 + c] & 0xff,
                            w + "x" + h + " pixel " + i + " channel " + c);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Samples at the extremes of the depth: the widest differences it allows. */
    private static int[][] extremes(int w, int h, int planes, int bits) {
        Random r = new Random(5);
        int[][] px = new int[planes][w * h];
        long max = 1L << bits;
        for (int c = 0; c < planes; c++) {
            for (int i = 0; i < w * h; i++) {
                px[c][i] = (i + c) % 2 == 0 ? 0 : (int) (max - 1);
                if (r.nextInt(4) == 0) {
                    px[c][i] = (int) (r.nextDouble() * max);
                }
            }
        }
        return px;
    }

    private static double meanError(int[][] px, int[][] out, int n) {
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                sum += Math.abs(out[c][i] - px[c][i]);
            }
        }
        return sum / (double) (n * 3);
    }

    /** Percentage of pixels that are not flat zero: a truncated plain file has holes. */
    private static double covered(int[][] out, int n) {
        int nz = 0;
        for (int i = 0; i < n; i++) {
            if (out[0][i] != 0 || out[1][i] != 0 || out[2][i] != 0) {
                nz++;
            }
        }
        return 100.0 * nz / n;
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-decoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return out.contains("jpegxl");
        } catch (Exception e) {
            return false;
        }
    }
}
