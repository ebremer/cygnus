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
import com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder;
import com.ebremer.cygnus.jpegxl.testutil.TestImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The streaming encoder must produce valid lossless codestreams from rows
 * pushed incrementally, independent of how the rows are batched, without ever
 * seeing the whole image.
 */
class StreamingEncoderTest {

    @TempDir
    static Path tempDir;

    /** Streams {@code planes} in bands of {@code chunkRows} and returns the file. */
    private static byte[] encodeStreaming(int[][] planes, int w, int h, int bits,
            boolean grey, boolean alpha, int chunkRows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlStreamingEncoder enc = new JxlStreamingEncoder(out, w, h, bits,
                grey, alpha, false)) {
            int[][] chunk = new int[planes.length][];
            for (int y = 0; y < h; y += chunkRows) {
                int rows = Math.min(chunkRows, h - y);
                for (int c = 0; c < planes.length; c++) {
                    chunk[c] = new int[rows * w];
                    System.arraycopy(planes[c], y * w, chunk[c], 0, rows * w);
                }
                enc.writeRows(chunk, rows);
            }
        }
        return out.toByteArray();
    }

    private static void assertDecodesTo(byte[] jxl, int[][] planes, int w, int h) throws IOException {
        JxlImage image = JxlDecoder.decode(jxl);
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        JxlFrame frame = image.frames.get(0);
        for (int c = 0; c < planes.length; c++) {
            assertArrayEquals(planes[c], frame.channels[c], "plane " + c);
        }
    }

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(600, 420, 8, false, false),   // 3x2 groups
                Arguments.of(513, 40, 8, false, false),    // three groups wide, short band
                Arguments.of(31, 900, 8, true, true),      // four groups tall, thin
                Arguments.of(300, 700, 16, true, false),   // deep grey
                Arguments.of(520, 300, 8, false, true),    // alpha
                Arguments.of(400, 300, 28, false, false),  // beyond float32 precision
                Arguments.of(200, 150, 8, false, false),   // single group: buffered path
                Arguments.of(1, 1, 8, true, false)
        );
    }

    @ParameterizedTest(name = "{0}x{1} bits={2} grey={3} alpha={4}")
    @MethodSource("configurations")
    void streamedFileDecodesLosslessly(int w, int h, int bits, boolean grey, boolean alpha)
            throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] original = TestImages.mixed(w, h, planes, bits, 31);
        byte[] jxl = encodeStreaming(original, w, h, bits, grey, alpha, 100);
        assertDecodesTo(jxl, original, w, h);
    }

    @Test
    void outputIndependentOfRowBatching() throws IOException {
        int w = 600;
        int h = 420;
        int[][] original = TestImages.mixed(w, h, 3, 8, 32);
        byte[] oneRow = encodeStreaming(original, w, h, 8, false, false, 1);
        byte[] oddChunks = encodeStreaming(original, w, h, 8, false, false, 37);
        byte[] bandChunks = encodeStreaming(original, w, h, 8, false, false, 256);
        byte[] allAtOnce = encodeStreaming(original, w, h, 8, false, false, h);
        assertArrayEquals(oneRow, oddChunks);
        assertArrayEquals(oneRow, bandChunks);
        assertArrayEquals(oneRow, allAtOnce);
    }

    @Test
    void sizeStaysCloseToWholeImageEncoder() throws IOException {
        int w = 600;
        int h = 420;
        int[][] original = TestImages.mixed(w, h, 3, 8, 33);
        int[][] copy = new int[3][];
        for (int c = 0; c < 3; c++) {
            copy[c] = original[c].clone();
        }
        byte[] whole = JxlEncoder.encode(copy, w, h, 8, false, false, false);
        byte[] streamed = encodeStreaming(original, w, h, 8, false, false, 256);
        assertTrue(streamed.length < whole.length * 1.35,
                "streamed " + streamed.length + " vs whole " + whole.length);
    }

    @Test
    void streamedFileSupportsRegionReads() throws IOException {
        int w = 700;
        int h = 500;
        int[][] original = TestImages.mixed(w, h, 3, 8, 34);
        byte[] jxl = encodeStreaming(original, w, h, 8, false, false, 128);
        JxlImage part = JxlDecoder.decode(jxl, new java.awt.Rectangle(300, 200, 150, 120));
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < 120; y++) {
                for (int x = 0; x < 150; x++) {
                    assertEquals(original[c][(200 + y) * w + 300 + x],
                            part.frames.get(0).channels[c][y * 150 + x],
                            "channel " + c + " at (" + x + ", " + y + ")");
                }
            }
        }
    }

    @Test
    void rejectsWrongUsage() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JxlStreamingEncoder enc = new JxlStreamingEncoder(out, 300, 300, 8,
                false, false, false);
        int[][] rows = TestImages.mixed(300, 10, 3, 8, 35);
        enc.writeRows(rows, 10);
        assertThrows(IllegalStateException.class, enc::finish);   // rows missing
        assertThrows(IllegalStateException.class, enc::close);    // still missing
        int[][] tooMany = TestImages.mixed(300, 300, 3, 8, 36);
        assertThrows(IllegalArgumentException.class, () -> enc.writeRows(tooMany, 300));
        int[][] rest = TestImages.mixed(300, 290, 3, 8, 37);
        enc.writeRows(rest, 290);
        enc.finish();
        enc.finish(); // idempotent
        assertThrows(IllegalStateException.class, () -> enc.writeRows(rows, 10));
        assertTrue(out.size() > 0);
    }

    @Test
    void ffmpegDecodesStreamedOutput() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg with libjxl not available");
        int w = 600;
        int h = 420;
        int[][] original = TestImages.mixed(w, h, 3, 8, 38);
        byte[] jxl = encodeStreaming(original, w, h, 8, false, false, 200);

        Path jxlFile = tempDir.resolve("streamed.jxl");
        Path rawFile = tempDir.resolve("streamed.raw");
        Files.write(jxlFile, jxl);
        Process p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", jxlFile.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", rawFile.toString())
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, "ffmpeg failed: " + log);

        byte[] raw = Files.readAllBytes(rawFile);
        assertEquals(w * h * 3, raw.length);
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(original[c][i], raw[i * 3 + c] & 0xff,
                        "pixel " + i + " channel " + c);
            }
        }
    }

    /**
     * Geometries whose internal products pass 2^31 are refused up front with a
     * clear message. A width of 2^24 used to wrap the band size to zero and
     * die as an opaque AIOOBE on the first rows; 164096 x 1715306752 wraps the
     * group count to exactly one (641 * 6700417 = 2^32 + 1) and used to slip
     * onto the single-group path.
     */
    @Test
    void impossibleGeometriesAreRefusedUpFront() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () ->
                new JxlStreamingEncoder(out, 1 << 24, 300, 8, false, false, false, 0f));
        assertThrows(IllegalArgumentException.class, () ->
                new JxlStreamingEncoder(out, 164096, 1715306752, 8, false, false, false, 0f));
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return out.contains("libjxl");
        } catch (Exception e) {
            return false;
        }
    }
}
