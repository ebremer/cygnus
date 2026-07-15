package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import com.ebremer.cygnus.jpegxl.testutil.JxlTools;
import com.ebremer.cygnus.jpegxl.testutil.TestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-validation against ffmpeg's libjxl build (the reference
 * implementation). Skipped when no suitable ffmpeg is on the PATH.
 */
class FfmpegInteropTest {

    @TempDir
    static Path tempDir;

    private static boolean ffmpegAvailable;

    @BeforeAll
    static void checkFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            ffmpegAvailable = out.contains("libjxl");
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(120, 90, 8, false, false, "rgb24"),
                Arguments.of(300, 270, 8, false, false, "rgb24"),   // multiple groups
                Arguments.of(120, 90, 8, true, false, "gray"),
                Arguments.of(120, 90, 8, false, true, "rgba"),
                Arguments.of(64, 48, 16, false, false, "rgb48le"),
                Arguments.of(64, 48, 16, true, false, "gray16le"),
                Arguments.of(64, 48, 16, false, true, "rgba64le")
        );
    }

    @ParameterizedTest(name = "our encoder → ffmpeg: {0}x{1} {5}")
    @MethodSource("formats")
    void ffmpegDecodesOurOutput(int w, int h, int bits, boolean grey, boolean alpha, String pixFmt)
            throws Exception {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] original = TestImages.mixed(w, h, planes, bits, 99);
        byte[] jxl = JxlEncoder.encode(deepCopy(original), w, h, bits, grey, alpha, false);

        Path jxlFile = tempDir.resolve("ours-" + w + "x" + h + "-" + pixFmt + ".jxl");
        Path rawFile = tempDir.resolve("ffdec-" + w + "x" + h + "-" + pixFmt + ".raw");
        Files.write(jxlFile, jxl);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", pixFmt, rawFile.toString());

        int[][] decoded = readRaw(Files.readAllBytes(rawFile), w, h, bits, planes);
        for (int p = 0; p < planes; p++) {
            assertArrayEquals(original[p], decoded[p], "plane " + p);
        }
    }

    /** Few-colour content: exercises the palette transform and LZ77 runs. */
    @Test
    void ffmpegDecodesOurPaletteOutput() throws Exception {
        int w = 320;
        int h = 200;
        int[][] original = new int[3][w * h];
        int[][] pal = new int[12][3];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < pal.length; i++) {
            for (int c = 0; c < 3; c++) {
                pal[i][c] = rnd.nextInt(256);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = (x / 17 + y / 11) % pal.length;
                for (int c = 0; c < 3; c++) {
                    original[c][y * w + x] = pal[k][c];
                }
            }
        }
        byte[] jxl = JxlEncoder.encode(deepCopy(original), w, h, 8, false, false, false);

        Path jxlFile = tempDir.resolve("ours-palette.jxl");
        Path rawFile = tempDir.resolve("ffdec-palette.raw");
        Files.write(jxlFile, jxl);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] decoded = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        for (int p = 0; p < 3; p++) {
            assertArrayEquals(original[p], decoded[p], "plane " + p);
        }
    }

    /**
     * A large smooth image encoded lossily uses the 32x32 transform; libjxl must
     * reconstruct it the same way we do, to within a rounding step.
     */
    @Test
    void ffmpegAgreesOnOurDct32Output() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 384;
        int h = 320;
        int[][] src = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                src[0][i] = Math.max(0, Math.min(255, 128 + (int) (55 * Math.sin(x * 0.009))));
                src[1][i] = Math.max(0, Math.min(255, 120 + (int) (45 * Math.cos(y * 0.007))));
                src[2][i] = Math.max(0, Math.min(255, 100 + (x + y) / 10));
            }
        }
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encode(
                deepCopy(src), w, h, 8, false, false, false, 1.5f);
        Path jxlFile = tempDir.resolve("ours-dct32.jxl");
        Path rawFile = tempDir.resolve("ffdec-dct32.raw");
        Files.write(jxlFile, jxl);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        int[][] ours = JxlDecoder.decode(jxl).frames.get(0).channels;
        int worst = 0;
        for (int p = 0; p < 3; p++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(ours[p][i] - theirs[p][i]));
            }
        }
        assertTrue(worst <= 1, "our decode and libjxl's differ by " + worst + " on a DCT32 image");
    }

    /**
     * A frame carrying a photon-noise model synthesizes grain the same way in
     * libjxl and in us — the synthesis is normative, so if the model is written
     * right the two decoders land on the same pixels, to a rounding step.
     */
    @Test
    void ffmpegSynthesizesOurNoiseIdentically() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 256;
        int h = 192;
        int[][] src = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                src[0][i] = 100 + x * 80 / w;
                src[1][i] = 110 + y * 70 / h;
                src[2][i] = 120;
            }
        }
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encodeWithPhotonNoise(
                src, w, h, 8, false, java.util.List.of(), 1.5f, 6400);
        Path jxlFile = tempDir.resolve("ours-noise.jxl");
        Path rawFile = tempDir.resolve("ffdec-noise.raw");
        Files.write(jxlFile, jxl);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        int[][] ours = JxlDecoder.decode(jxl).frames.get(0).channels;
        int worst = 0;
        for (int p = 0; p < 3; p++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(ours[p][i] - theirs[p][i]));
            }
        }
        assertTrue(worst <= 1, "libjxl's synthesized noise differs from ours by " + worst);
    }

    /**
     * An XYB image with an sRGB-like embedded ICC profile now displays with the
     * right brightness — the sRGB transfer applied — so our eight-bit decode
     * tracks libjxl's rather than coming out far too dark (which it did while the
     * linear samples were shown as if already gamma-encoded).
     */
    @Test
    void ffmpegAgreesOnOurIccDisplay() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        Path input = Path.of("test-data", "conformance", "patches", "input.jxl");
        assumeTrue(Files.exists(input), "conformance corpus not present");
        JxlImage img = JxlDecoder.decode(Files.readAllBytes(input));
        assumeTrue(img.metadata.xybEncoded && img.metadata.iccProfile != null,
                "expected an XYB image with an embedded ICC");
        int w = img.width;
        int h = img.height;

        Path rawFile = tempDir.resolve("ffdec-icc.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", input.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        int[][] ours = img.frames.get(0).channels;
        long sum = 0;
        for (int p = 0; p < 3; p++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(ours[p][i] - theirs[p][i]);
            }
        }
        double mean = sum / (3.0 * w * h);
        // an sRGB-ish profile: within a couple of levels of libjxl (a raw linear
        // display was tens of levels off)
        assertTrue(mean < 3, "our ICC display is far from libjxl's: mean " + mean);
    }

    /** Smooth gradients favour the weighted predictor. */
    @Test
    void ffmpegDecodesOurWeightedPredictorOutput() throws Exception {
        int w = 280;
        int h = 220;
        int[][] original = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                original[0][y * w + x] = (int) (127 + 120 * Math.sin(x * 0.011 + y * 0.007));
                original[1][y * w + x] = (int) (127 + 120 * Math.sin(x * 0.009 - y * 0.013));
                original[2][y * w + x] = (x + y) % 251;
            }
        }
        byte[] jxl = JxlEncoder.encode(deepCopy(original), w, h, 8, false, false, false);

        Path jxlFile = tempDir.resolve("ours-wp.jxl");
        Path rawFile = tempDir.resolve("ffdec-wp.raw");
        Files.write(jxlFile, jxl);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] decoded = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        for (int p = 0; p < 3; p++) {
            assertArrayEquals(original[p], decoded[p], "plane " + p);
        }
    }

    static Stream<Arguments> ffmpegEncodeCases() {
        return Stream.of(
                Arguments.of(120, 90, 8, false, false, "rgb24", 1),
                Arguments.of(120, 90, 8, false, false, "rgb24", 3),
                Arguments.of(120, 90, 8, false, false, "rgb24", 7),
                Arguments.of(300, 270, 8, false, false, "rgb24", 7), // multiple groups
                Arguments.of(120, 90, 8, true, false, "gray", 7),
                Arguments.of(120, 90, 8, false, true, "rgba", 7),
                Arguments.of(64, 48, 16, false, false, "rgb48le", 7),
                Arguments.of(64, 48, 16, false, true, "rgba64le", 7),
                Arguments.of(500, 400, 8, false, false, "rgb24", 9)
        );
    }

    @ParameterizedTest(name = "ffmpeg -e{6} → our decoder: {0}x{1} {5}")
    @MethodSource("ffmpegEncodeCases")
    void weDecodeFfmpegOutput(int w, int h, int bits, boolean grey, boolean alpha, String pixFmt,
            int effort) throws Exception {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] original = TestImages.mixed(w, h, planes, bits, 1234 + effort);

        Path rawFile = tempDir.resolve("in-" + w + "x" + h + "-" + pixFmt + "-" + effort + ".raw");
        Path jxlFile = tempDir.resolve("ffenc-" + w + "x" + h + "-" + pixFmt + "-" + effort + ".jxl");
        Files.write(rawFile, writeRaw(original, w, h, bits));
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", pixFmt, "-video_size", w + "x" + h,
                "-i", rawFile.toString(),
                "-frames:v", "1", "-c:v", "libjxl", "-distance", "0", "-effort",
                Integer.toString(effort), jxlFile.toString());

        JxlImage image = JxlDecoder.decode(Files.readAllBytes(jxlFile));
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        JxlFrame frame = image.frames.get(0);
        for (int p = 0; p < planes; p++) {
            assertArrayEquals(original[p], frame.channels[p], "plane " + p);
        }
    }

    @Test
    void ffmpegDecodesOurContainerOutput() throws Exception {
        int w = 77;
        int h = 55;
        int[][] original = TestImages.mixed(w, h, 3, 8, 6);
        byte[] bare = JxlEncoder.encode(deepCopy(original), w, h, 8, false, false, false);
        byte[] boxed = com.ebremer.cygnus.jpegxl.container.Container.wrap(bare);
        Path jxlFile = tempDir.resolve("boxed.jxl");
        Path rawFile = tempDir.resolve("boxed.raw");
        Files.write(jxlFile, boxed);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] decoded = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        for (int p = 0; p < 3; p++) {
            assertArrayEquals(original[p], decoded[p], "plane " + p);
        }
    }

    @Test
    void lossyFilesDecode() throws Exception {
        // ffmpeg's libjxl wrapper encodes VarDCT at distance 1; decode it and
        // compare against ffmpeg's own decode of the same file
        int w = 64;
        int h = 48;
        int[][] original = TestImages.mixed(w, h, 3, 8, 5);
        Path rawFile = tempDir.resolve("lossy-in.raw");
        Path jxlFile = tempDir.resolve("lossy.jxl");
        Path outFile = tempDir.resolve("lossy-out.raw");
        Files.write(rawFile, writeRaw(original, w, h, 8));
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", w + "x" + h,
                "-i", rawFile.toString(),
                "-frames:v", "1", "-c:v", "libjxl", "-distance", "1", jxlFile.toString());
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", outFile.toString());
        JxlImage ours = JxlDecoder.decode(Files.readAllBytes(jxlFile));
        int[][] reference = readRaw(Files.readAllBytes(outFile), w, h, 8, 3);
        JxlFrame frame = ours.frames.get(0);
        for (int c = 0; c < 3; c++) {
            int maxDiff = JxlTools.maxAbsDiff(frame.channels[c], reference[c]);
            assertEquals(true, maxDiff <= 6, "plane " + c + " max diff " + maxDiff);
        }
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("ffmpeg timed out");
        }
        if (p.exitValue() != 0) {
            throw new AssertionError("ffmpeg failed (" + p.exitValue() + "): " + out);
        }
    }

    /** Interleaved raw frame → planes. */
    private static int[][] readRaw(byte[] raw, int w, int h, int bits, int planes) {
        int[][] out = new int[planes][w * h];
        int bytesPerSample = bits > 8 ? 2 : 1;
        int expected = w * h * planes * bytesPerSample;
        assertEquals(expected, raw.length, "raw frame size");
        int idx = 0;
        for (int i = 0; i < w * h; i++) {
            for (int p = 0; p < planes; p++) {
                if (bytesPerSample == 1) {
                    out[p][i] = raw[idx++] & 0xff;
                } else {
                    out[p][i] = (raw[idx] & 0xff) | ((raw[idx + 1] & 0xff) << 8);
                    idx += 2;
                }
            }
        }
        return out;
    }

    /** Planes → interleaved raw frame (little-endian for 16-bit). */
    private static byte[] writeRaw(int[][] planes, int w, int h, int bits) {
        int bytesPerSample = bits > 8 ? 2 : 1;
        byte[] raw = new byte[w * h * planes.length * bytesPerSample];
        int idx = 0;
        for (int i = 0; i < w * h; i++) {
            for (int[] plane : planes) {
                if (bytesPerSample == 1) {
                    raw[idx++] = (byte) plane[i];
                } else {
                    raw[idx++] = (byte) plane[i];
                    raw[idx++] = (byte) (plane[i] >> 8);
                }
            }
        }
        return raw;
    }

    private static int[][] deepCopy(int[][] planes) {
        int[][] out = new int[planes.length][];
        for (int i = 0; i < planes.length; i++) {
            out[i] = planes[i].clone();
        }
        return out;
    }
}
