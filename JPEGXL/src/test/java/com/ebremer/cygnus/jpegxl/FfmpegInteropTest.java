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

    /**
     * Our lossy XYB-modular output is a valid codestream libjxl reads, and our
     * inverse XYB agrees with its own to within rounding — the whole quantise,
     * decorrelate ({@code B - Y}) and dequantise round-trip lands on the same
     * pixels the reference does.
     */
    @Test
    void ffmpegDecodesOurXybModular() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 160;
        int h = 120;
        int[][] rgb = TestImages.mixed(w, h, 3, 8, 20260715L);
        byte[] jxl = JxlEncoder.encodeXyb(rgb, w, h, 8, 1.0f);
        JxlImage ours = JxlDecoder.decode(jxl);
        assertTrue(ours.metadata.xybEncoded, "our file should declare XYB");

        Path jxlFile = tempDir.resolve("xybmod.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("xybmod.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        int[][] od = ours.frames.get(0).channels;

        int worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(od[c][i] - theirs[c][i]));
            }
        }
        assertTrue(worst <= 2, "our XYB-modular decode disagrees with libjxl: worst " + worst);
    }

    /**
     * Our rectangular (8x16 / 16x8) VarDCT blocks reconstruct the same pixels in
     * libjxl — the flipped coefficient layout, the shared 8x16 dequant matrix and
     * the rectangular low-frequency corner all land where the reference reads
     * them.
     */
    @Test
    void ffmpegAgreesOnOurRectangularBlocks() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 256;
        int h = 192;
        int[][] rgb = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                // directional: bands down one half, across the other
                int v = x < w / 2
                        ? (int) (128 + 120 * Math.sin(y * 0.20))
                        : (int) (128 + 120 * Math.sin(x * 0.20));
                v = Math.max(0, Math.min(255, v));
                rgb[0][i] = v;
                rgb[1][i] = Math.min(255, v + 20);
                rgb[2][i] = 255 - v;
            }
        }
        long before = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.RECT_BLOCKS.get();
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encode(rgb, w, h, 1.0f);
        long fired = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.RECT_BLOCKS.get() - before;
        assertTrue(fired > 0, "the directional image should use rectangular blocks");

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] od = ours.frames.get(0).channels;
        Path jxlFile = tempDir.resolve("rect.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("rect.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);

        int worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(od[c][i] - theirs[c][i]));
            }
        }
        assertTrue(worst <= 2, "our rectangular-block decode disagrees with libjxl: worst " + worst);
    }

    /**
     * The larger 32x16 / 16x32 rectangular blocks — the 32x32-scale directional
     * transforms — also reconstruct identically in libjxl, their 4x2 / 2x4
     * low-frequency corners and flipped layouts included.
     */
    @Test
    void ffmpegAgreesOnOurLargerRectangularBlocks() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 384;
        int h = 320;
        int[][] rgb = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                // wide bands (~64px) so a 32-long block covers a smooth run
                int v = x < w / 2
                        ? (int) (128 + 110 * Math.sin(y * 0.09))
                        : (int) (128 + 110 * Math.sin(x * 0.09));
                v = Math.max(0, Math.min(255, v));
                rgb[0][i] = v;
                rgb[1][i] = Math.min(255, v + 15);
                rgb[2][i] = 255 - v;
            }
        }
        long tall = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(10);
        long wide = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(11);
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encode(rgb, w, h, 1.5f);
        long fired = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(10) - tall
                + com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(11) - wide;
        assertTrue(fired > 0, "the wide image should use 32-scale rectangular blocks");

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] od = ours.frames.get(0).channels;
        Path jxlFile = tempDir.resolve("bigrect.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("bigrect.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);

        int worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(od[c][i] - theirs[c][i]));
            }
        }
        assertTrue(worst <= 2, "our 32-scale rectangular decode disagrees with libjxl: worst " + worst);
    }

    /**
     * Our DCT2 and DCT4 varblocks — the hierarchical-Hadamard and four-4x4
     * transforms for piecewise-flat blocks — reconstruct identically in libjxl.
     */
    @Test
    void ffmpegAgreesOnOurSmallTransforms() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 256;
        int h = 192;
        int[][] rgb = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = x < w * 43 / 100 || y < h * 37 / 100 ? 235 : 25;
                if (((x / 24) + (y / 24)) % 3 == 0) {
                    v = 255 - v;
                }
                if (x % 37 < 3 || y % 41 < 3) {
                    v = 128;
                }
                rgb[0][i] = v;
                rgb[1][i] = v;
                rgb[2][i] = v;
            }
        }
        long before = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(2)
                + com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(3);
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encode(rgb, w, h, 1.5f);
        long fired = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(2)
                + com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(3) - before;
        assertTrue(fired > 0, "the edged image should use DCT2/DCT4 blocks");

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] od = ours.frames.get(0).channels;
        Path jxlFile = tempDir.resolve("small.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("small.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);

        int worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(od[c][i] - theirs[c][i]));
            }
        }
        assertTrue(worst <= 2, "our DCT2/DCT4 decode disagrees with libjxl: worst " + worst);
    }

    /** The DCT4x8 / DCT8x4 varblocks reconstruct identically in libjxl too. */
    @Test
    void ffmpegAgreesOnOurRectangularSmallTransforms() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 256;
        int h = 192;
        int[][] rgb = new int[3][w * h];
        for (int i = 0; i < w * h; i++) {
            rgb[0][i] = rgb[1][i] = rgb[2][i] = 128;
        }
        for (int by = 0; by < h / 8; by++) {
            for (int bx = 0; bx < w / 8; bx++) {
                if (((bx * 5 + by * 11) % 6) != 0) {
                    continue;
                }
                boolean t48 = ((bx + by) & 1) == 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int v = t48
                                ? (y < 4 ? Math.min(255, 30 + x * 24) : Math.max(0, 230 - x * 24))
                                : (x < 4 ? Math.min(255, 30 + y * 24) : Math.max(0, 230 - y * 24));
                        int i = (by * 8 + y) * w + bx * 8 + x;
                        rgb[0][i] = rgb[1][i] = rgb[2][i] = v;
                    }
                }
            }
        }
        long before = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(12)
                + com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(13);
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.encode(rgb, w, h, 1.0f);
        long fired = com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(12)
                + com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder.TYPE_HIST.get(13) - before;
        assertTrue(fired > 0, "the split-block image should use DCT4x8/DCT8x4");

        JxlImage ours = JxlDecoder.decode(jxl);
        int[][] od = ours.frames.get(0).channels;
        Path jxlFile = tempDir.resolve("rect48.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("rect48.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        int worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                worst = Math.max(worst, Math.abs(od[c][i] - theirs[c][i]));
            }
        }
        assertTrue(worst <= 2, "our DCT4x8/DCT8x4 decode disagrees with libjxl: worst " + worst);
    }

    /**
     * A patch-coded image — a reference frame of glyphs stamped over the canvas —
     * is a valid two-frame codestream libjxl reads back exactly, reference-only
     * frame, {@code REPLACE} stamps and all.
     */
    @Test
    void ffmpegDecodesOurPatches() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not available");
        int w = 256;
        int h = 256;
        int[][] rgb = new int[3][w * h];
        for (int i = 0; i < w * h; i++) {
            rgb[0][i] = 240;
            rgb[1][i] = 240;
            rgb[2][i] = 245;
        }
        int[][] glyphs = new int[4][16 * 16 * 3];
        java.util.Random r = new java.util.Random(1);
        for (int g = 0; g < 4; g++) {
            for (int k = 0; k < glyphs[g].length; k++) {
                glyphs[g][k] = r.nextInt(256);
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
                            rgb[c][(ty * 16 + y) * w + tx * 16 + x] = glyphs[g][(c * 16 + y) * 16 + x];
                        }
                    }
                }
            }
        }
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.encodeWithPatches(rgb, w, h, 8, false);
        // confirm patches were actually used (a two-frame file), not the plain fallback
        assertTrue(JxlDecoder.decode(jxl).frames.get(0).channels[0][0] == rgb[0][0]);

        Path jxlFile = tempDir.resolve("patch.jxl");
        Files.write(jxlFile, jxl);
        Path rawFile = tempDir.resolve("patch.raw");
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString());
        int[][] theirs = readRaw(Files.readAllBytes(rawFile), w, h, 8, 3);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(rgb[c], theirs[c], "libjxl must decode our patches exactly, channel " + c);
        }
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
