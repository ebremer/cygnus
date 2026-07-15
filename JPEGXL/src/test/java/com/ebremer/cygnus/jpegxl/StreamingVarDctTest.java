package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import com.ebremer.cygnus.jpegxl.testutil.TestImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The streaming lossy (VarDCT) encoder, which never holds more than one band of
 * the image. The two things it had to answer for — masking's activity reference
 * and the frame's single coefficient code, both of which the whole-image encoder
 * derives from every pixel at once — are what most of these pin down.
 */
class StreamingVarDctTest {

    @TempDir
    static Path tempDir;

    private static byte[] stream(int[][] planes, int w, int h, int bits, boolean grey,
            boolean alpha, float distance, int chunkRows) throws IOException {
        return stream(planes, w, h, bits, grey, alpha, distance, chunkRows, false);
    }

    private static byte[] stream(int[][] planes, int w, int h, int bits, boolean grey,
            boolean alpha, float distance, int chunkRows, boolean rateControl)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlStreamingEncoder enc = rateControl
                ? JxlStreamingEncoder.targetingQuality(out, w, h, bits, grey, alpha, false,
                        distance)
                : new JxlStreamingEncoder(out, w, h, bits, grey, alpha, false, distance)) {
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

    /** Weighted mean absolute sRGB error over a row range, the encoder's own yardstick. */
    private static double error(int[][] planes, int w, int bits, boolean grey, byte[] jxl,
            int y0, int y1) throws IOException {
        JxlImage image = JxlDecoder.decode(jxl);
        int[][] out = image.frames.get(0).channels;
        long sum = 0;
        for (int i = y0 * w; i < y1 * w; i++) {
            sum += grey
                    ? 4L * Math.abs(out[0][i] - planes[0][i])
                    : Math.abs(out[0][i] - planes[0][i]) + 2L * Math.abs(out[1][i] - planes[1][i])
                            + Math.abs(out[2][i] - planes[2][i]);
        }
        return sum / (4.0 * (y1 - y0) * w) * (255.0 / ((1 << bits) - 1));
    }

    /**
     * A slide: blank glass over most of the frame, dense detail in a band across
     * the middle. Distinguishes an encoder that looks at the whole image from one
     * that only ever sees the top of it, and punishes any global statistic frozen
     * from the first band — which on this image is nothing but background.
     */
    private static int[][] slide(int w, int h) {
        Random r = new Random(4);
        int[][] px = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (y > h * 0.45 && y < h * 0.70) {
                    int base = 150 + r.nextInt(60);
                    px[0][i] = Math.min(255, base + 40 + r.nextInt(30));
                    px[1][i] = Math.max(0, base - 40 + r.nextInt(30));
                    px[2][i] = Math.min(255, base + 20 + r.nextInt(30));
                } else {
                    int g = 245 + r.nextInt(8);
                    px[0][i] = g;
                    px[1][i] = g;
                    px[2][i] = g;
                }
            }
        }
        return px;
    }

    /**
     * An image no taller than one band is a window over the whole image, so the
     * streaming encoder has all of it in hand and must reach the identical
     * decisions — same masking reference, same transforms, same single code.
     * Byte equality is the tightest statement available that the band pipeline
     * (halo and all) is not an approximation.
     */
    @ParameterizedTest
    @ValueSource(floats = {0.5f, 1.0f, 3.0f})
    void oneBandIsByteIdenticalToTheWholeImageEncoder(float distance) throws IOException {
        for (int[] shape : new int[][] {{1024, 256}, {600, 256}, {700, 200}, {300, 130}}) {
            int w = shape[0];
            int h = shape[1];
            int[][] px = TestImages.mixed(w, h, 3, 8, 11);
            byte[] whole = VarDctEncoder.encode(px, w, h, 8, false, false, false, distance);
            byte[] streamed = stream(px, w, h, 8, false, false, distance, 64);
            assertArrayEquals(whole, streamed, w + "x" + h + " at distance " + distance);
        }
    }

    /** How the rows are handed over must not reach the output. */
    @Test
    void outputIndependentOfRowBatching() throws IOException {
        int w = 500;
        int h = 900;
        int[][] px = TestImages.mixed(w, h, 3, 8, 12);
        byte[] byOne = stream(px, w, h, 8, false, false, 1.5f, 1);
        assertArrayEquals(byOne, stream(px, w, h, 8, false, false, 1.5f, 7));
        assertArrayEquals(byOne, stream(px, w, h, 8, false, false, 1.5f, 256));
        assertArrayEquals(byOne, stream(px, w, h, 8, false, false, 1.5f, h));
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of(512, 512, 8, false, false),      // 2 bands
                Arguments.of(700, 900, 8, false, false),      // 4 bands
                Arguments.of(512, 2048, 8, false, false),     // 8 bands: a code each, exactly
                Arguments.of(512, 2100, 8, false, false),     // 9 bands: the code pool shares
                Arguments.of(300, 3000, 8, false, false),     // spans two LF group rows
                Arguments.of(640, 480, 8, true, false),       // greyscale
                Arguments.of(640, 480, 16, false, false),     // 16-bit
                Arguments.of(900, 700, 8, false, true),       // alpha, carried losslessly
                Arguments.of(257, 259, 8, false, false),      // ragged: one pixel into band 2
                Arguments.of(512, 257, 8, false, false),      // a band of a single row
                // narrower than a group, so there is one group column and the
                // per-group work never fans out
                Arguments.of(256, 512, 8, false, false),
                Arguments.of(100, 600, 8, false, false),
                Arguments.of(8, 300, 8, false, false),
                // shorter than the halo the gaborish pre-sharpening asks for
                Arguments.of(512, 3, 8, false, false),
                Arguments.of(300, 8, 8, false, false));
    }

    /**
     * Whatever the streaming encoder gives up by not seeing the image at once, it
     * is not size and it is not fidelity: per-band coefficient codes buy back more
     * than the drifting masking reference costs.
     */
    @ParameterizedTest
    @MethodSource("shapes")
    void tracksTheWholeImageEncoderOnSizeAndFidelity(int w, int h, int bits, boolean grey,
            boolean alpha) throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] px = TestImages.mixed(w, h, planes, bits, 13);
        for (float d : new float[] {1.0f, 3.0f}) {
            byte[] whole = VarDctEncoder.encode(px, w, h, bits, grey, alpha, false, d);
            byte[] streamed = stream(px, w, h, bits, grey, alpha, d, 64);
            assertTrue(streamed.length < whole.length * 1.05,
                    "streamed " + streamed.length + " vs whole " + whole.length);
            double wholeError = error(px, w, bits, grey, whole, 0, h);
            double streamedError = error(px, w, bits, grey, streamed, 0, h);
            assertTrue(streamedError < wholeError * 1.15,
                    "error " + streamedError + " vs whole " + wholeError);
        }
    }

    /**
     * However the image is shaped, what comes out is the image: right size, and
     * the same bytes whatever the rows were batched into. The narrow and
     * short-of-the-halo shapes are here because they take the code paths the
     * ordinary ones skip — one group column, or a band with no room for the
     * three rows of context the pre-sharpening wants on each side.
     */
    @ParameterizedTest
    @MethodSource("shapes")
    void decodesToTheRightImageWhateverTheShape(int w, int h, int bits, boolean grey,
            boolean alpha) throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] px = TestImages.mixed(w, h, planes, bits, 16);
        byte[] jxl = stream(px, w, h, bits, grey, alpha, 1.5f, 37);
        assertArrayEquals(jxl, stream(px, w, h, bits, grey, alpha, 1.5f, 1), "batched by 1");
        assertArrayEquals(jxl, stream(px, w, h, bits, grey, alpha, 1.5f, h), "all at once");
        JxlImage image = JxlDecoder.decode(jxl);
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        if (alpha) {
            assertArrayEquals(px[planes - 1], image.frames.get(0).channels[grey ? 1 : 3],
                    "alpha plane");
        }
    }

    /**
     * Rate control has to hold up over the whole input space, not just the RGB
     * eight-bit case it was tuned on.
     *
     * <p>What it promises is not a smaller error — an image the open loop codes
     * <em>better</em> than asked for should be coded more cheaply instead, which
     * moves the error up. What it promises is that the error ends up closer to
     * the distance that was requested than leaving the quantiser where the
     * distance put it. That is the miss shrinking, in either direction.
     */
    @ParameterizedTest
    @MethodSource("rateControlShapes")
    void rateControlHoldsAcrossTheInputSpace(int w, int h, int bits, boolean grey,
            boolean alpha) throws IOException {
        int planes = (grey ? 1 : 3) + (alpha ? 1 : 0);
        int[][] px = TestImages.mixed(w, h, planes, bits, 17);
        byte[] open = stream(px, w, h, bits, grey, alpha, 1.0f, 64, false);
        byte[] measured = stream(px, w, h, bits, grey, alpha, 1.0f, 64, true);
        JxlImage image = JxlDecoder.decode(measured);
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        if (alpha) {
            assertArrayEquals(px[planes - 1], image.frames.get(0).channels[grey ? 1 : 3],
                    "alpha plane");
        }
        double target = 0.9;   // targetError(1.0)
        double openError = error(px, w, bits, grey, open, 0, h);
        double measuredError = error(px, w, bits, grey, measured, 0, h);

        // The trade must never be a bad one. Spending more bits and getting a
        // worse picture is the shape of the failure this guards: a control loop
        // that coarsens the smooth bands (saving almost nothing, because smooth
        // was already cheap) while refining the busy ones past the point where
        // refining does anything.
        if (measured.length > open.length) {
            assertTrue(measuredError < openError,
                    "spent " + (measured.length - open.length) + " more bytes for a worse error: "
                            + measuredError + " vs open loop " + openError);
        }
        double openMiss = Math.abs(Math.log(openError / target));
        double measuredMiss = Math.abs(Math.log(measuredError / target));
        assertTrue(measuredMiss <= openMiss * 1.05,
                "rate control moved away from the target: miss " + measuredMiss
                        + " vs open loop " + openMiss);
    }

    static Stream<Arguments> rateControlShapes() {
        return Stream.of(
                Arguments.of(640, 1000, 8, false, false),
                Arguments.of(640, 1000, 8, true, false),      // greyscale
                Arguments.of(640, 1000, 16, false, false),    // 16-bit
                Arguments.of(640, 1000, 8, false, true),      // alpha
                Arguments.of(100, 900, 8, false, false),      // one group column
                Arguments.of(512, 256, 8, false, false),      // a single band
                Arguments.of(200, 200, 8, false, false));     // a single group
    }

    /** Alpha rides losslessly through the lossy path, as it does whole-image. */
    @Test
    void alphaSurvivesExactly() throws IOException {
        int w = 600;
        int h = 700;
        int[][] px = TestImages.mixed(w, h, 4, 8, 14);
        byte[] jxl = stream(px, w, h, 8, false, true, 2.0f, 64);
        JxlImage image = JxlDecoder.decode(jxl);
        assertArrayEquals(px[3], image.frames.get(0).channels[3], "alpha plane");
    }

    /**
     * The activity reference is a running mean, so an image whose content only
     * starts halfway down still gets a sane quantiser there. This is the case a
     * reference frozen on the first band would ruin.
     */
    @Test
    void contentArrivingLateIsStillCodedWell() throws IOException {
        int w = 512;
        int h = 2000;
        int[][] px = slide(w, h);
        int t0 = (int) (h * 0.45);
        int t1 = (int) (h * 0.70);
        byte[] whole = VarDctEncoder.encode(px, w, h, 1.0f);
        byte[] streamed = stream(px, w, h, 8, false, false, 1.0f, 100);
        double wholeTissue = error(px, w, 8, false, whole, t0, t1);
        double streamedTissue = error(px, w, 8, false, streamed, t0, t1);
        assertTrue(streamedTissue < wholeTissue * 1.10,
                "tissue error " + streamedTissue + " vs whole-image " + wholeTissue);
    }

    /**
     * A blank band and a dense band have nothing in common in their coefficient
     * statistics, and a frame that made them share one code would pay for it. The
     * pool is what stops that, and this is the shape that would expose its absence:
     * the file must stay in the whole-image encoder's league, not multiples of it.
     */
    @Test
    void blankAndDenseBandsDoNotShareOneAlphabet() throws IOException {
        int w = 512;
        int h = 3000;
        int[][] px = slide(w, h);
        byte[] whole = VarDctEncoder.encode(px, w, h, 1.0f);
        byte[] streamed = stream(px, w, h, 8, false, false, 1.0f, 64);
        assertTrue(streamed.length < whole.length * 1.05,
                "streamed " + streamed.length + " vs whole " + whole.length);
    }

    /**
     * Rate control has to work without the whole image, and on a slide it should
     * beat the whole-image loop rather than merely match it: that loop drives the
     * error averaged over the entire frame to the target, and on a frame that is
     * mostly blank glass, the average is the glass.
     */
    @Test
    void rateControlPutsBitsWhereThePictureIs() throws IOException {
        int w = 512;
        int h = 2000;
        int[][] px = slide(w, h);
        int t0 = (int) (h * 0.45);
        int t1 = (int) (h * 0.70);
        byte[] loop = VarDctEncoder.encodeToTarget(px, w, h, 1.0f);
        byte[] streamed = stream(px, w, h, 8, false, false, 1.0f, 100, true);

        double target = 0.9 * Math.pow(1.0, 0.9);
        double overall = error(px, w, 8, false, streamed, 0, h);
        assertTrue(overall > target * 0.6 && overall < target * 1.4,
                "overall error " + overall + " should track the target " + target);

        double loopTissue = error(px, w, 8, false, loop, t0, t1);
        double streamedTissue = error(px, w, 8, false, streamed, t0, t1);
        assertTrue(streamedTissue < loopTissue,
                "tissue error " + streamedTissue + " vs whole-image loop " + loopTissue);
    }

    /** Rate control also moves the error toward the target as the distance changes. */
    @ParameterizedTest
    @ValueSource(floats = {1.0f, 2.0f})
    void rateControlTracksTheRequestedDistance(float distance) throws IOException {
        int w = 512;
        int h = 1200;
        int[][] px = slide(w, h);
        byte[] jxl = stream(px, w, h, 8, false, false, distance, 100, true);
        double target = 0.9 * Math.pow(distance, 0.9);
        double err = error(px, w, 8, false, jxl, 0, h);
        assertTrue(err > target * 0.6 && err < target * 1.4,
                "error " + err + " at distance " + distance + ", target " + target);
    }

    @Test
    void rejectsWrongUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // VarDCT is defined for at most 16 bits per sample
        assertThrows(IllegalArgumentException.class,
                () -> new JxlStreamingEncoder(out, 400, 400, 24, false, false, false, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlStreamingEncoder(out, 400, 400, 8, false, false, false, -1f));
        // rate control is meaningless without a distance to aim at
        assertThrows(IllegalArgumentException.class,
                () -> JxlStreamingEncoder.targetingQuality(out, 400, 400, 8, false, false, false,
                        0f));
        assertThrows(IllegalStateException.class, () -> {
            JxlStreamingEncoder enc =
                    new JxlStreamingEncoder(out, 400, 400, 8, false, false, false, 1.0f);
            enc.writeRows(new int[3][400 * 10], 10);
            enc.finish();   // 390 rows still missing
        });
    }

    /**
     * Decoded by a decoder that is not ours. The multi-code HF layout the streaming
     * encoder leans on is the part our own decoder could be wrong about in exactly
     * the same way our encoder is, so it has to be read by something else.
     */
    @Test
    void ffmpegDecodesStreamedLossyOutput() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg with JPEG XL support not available");
        // one code, a code per band, and a shared pool: every regime of the pool
        for (int[] shape : new int[][] {{1024, 256}, {512, 1000}, {512, 2100}}) {
            int w = shape[0];
            int h = shape[1];
            int[][] px = TestImages.mixed(w, h, 3, 8, 15);
            byte[] jxl = stream(px, w, h, 8, false, false, 1.0f, 37);

            Path jxlFile = tempDir.resolve("lossy.jxl");
            Path rawFile = tempDir.resolve("lossy.raw");
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
            int[][] ours = JxlDecoder.decode(jxl).frames.get(0).channels;
            long sum = 0;
            for (int i = 0; i < w * h; i++) {
                for (int c = 0; c < 3; c++) {
                    sum += Math.abs((raw[i * 3 + c] & 0xff) - ours[c][i]);
                }
            }
            // two independent inverse transforms, so not bit-exact; but the same image
            double mean = sum / (3.0 * w * h);
            assertTrue(mean < 1.0, w + "x" + h + ": ffmpeg differs from us by " + mean);
        }
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
