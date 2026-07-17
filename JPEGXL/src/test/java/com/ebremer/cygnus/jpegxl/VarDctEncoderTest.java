package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import com.ebremer.cygnus.jpegxl.testutil.JxlTools;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The basic lossy (VarDCT) encoder, checked with our decoder and djxl. */
class VarDctEncoderTest {

    @TempDir
    static Path tempDir;

    private static int[][] photo(int w, int h) {
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

    @Test
    void selfDecodeWithinTolerance() throws Exception {
        int w = 300;
        int h = 260; // multiple groups
        int[][] rgb = photo(w, h);
        byte[] jxl = VarDctEncoder.encode(rgb, w, h, 1.0f);
        assertTrue(jxl.length < w * h, "should compress: " + jxl.length);
        JxlImage image = JxlDecoder.decode(jxl);
        assertEquals(w, image.width);
        assertEquals(h, image.height);
        JxlFrame frame = image.frames.get(0);
        long sum = 0;
        int max = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                int d = Math.abs(frame.channels[c][i] - rgb[c][i]);
                max = Math.max(max, d);
                sum += d;
            }
        }
        assertTrue(max <= 24, "max diff " + max);
        assertTrue(sum / (double) (w * h * 3) <= 1.5, "mean diff " + sum / (double) (w * h * 3));
    }

    @Test
    void greyThroughVarDct() throws Exception {
        int w = 300;
        int h = 260;
        int[][] grey = new int[1][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                grey[0][y * w + x] = (int) (120 + 80 * Math.sin(x * 0.04) * Math.cos(y * 0.03));
            }
        }
        byte[] jxl = VarDctEncoder.encode(grey, w, h, 8, true, false, false, 1.0f);
        JxlImage image = JxlDecoder.decode(jxl);
        JxlFrame frame = image.frames.get(0);
        assertEquals(1, frame.channels.length, "grey output has one channel");
        long sum = 0;
        for (int i = 0; i < w * h; i++) {
            sum += Math.abs(frame.channels[0][i] - grey[0][i]);
        }
        assertTrue(sum / (double) (w * h) <= 1.5, "mean diff " + sum / (double) (w * h));
    }

    @Test
    void sixteenBitThroughVarDct() throws Exception {
        int w = 300;
        int h = 260;
        int[][] rgb = photo(w, h);
        int[][] wide = new int[3][w * h];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                wide[c][i] = rgb[c][i] * 257; // 8-bit values widened to 16
            }
        }
        byte[] jxl = VarDctEncoder.encode(wide, w, h, 16, false, false, false, 1.0f);
        JxlImage image = JxlDecoder.decode(jxl);
        JxlFrame frame = image.frames.get(0);
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(frame.channels[c][i] - wide[c][i]);
            }
        }
        double mean8 = sum / (double) (w * h * 3) / 257.0;
        assertTrue(mean8 <= 1.5, "mean diff (8-bit scale) " + mean8);
    }

    @Test
    void alphaRidesLosslesslyBothLayouts() throws Exception {
        for (int[] dims : new int[][] {{200, 160}, {640, 400}}) { // single- and multi-group
            int w = dims[0];
            int h = dims[1];
            int[][] rgb = photo(w, h);
            int[][] planes = {rgb[0], rgb[1], rgb[2], new int[w * h]};
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    planes[3][y * w + x] = x < w / 3 ? 255 : (x < 2 * w / 3 ? 128 : (y & 63));
                }
            }
            byte[] jxl = VarDctEncoder.encode(planes, w, h, 8, false, true, false, 1.0f);
            JxlImage image = JxlDecoder.decode(jxl);
            JxlFrame frame = image.frames.get(0);
            assertEquals(4, frame.channels.length, "colour + alpha");
            for (int i = 0; i < w * h; i++) {
                assertEquals(planes[3][i], frame.channels[3][i],
                        w + "x" + h + " alpha sample " + i);
            }
            long sum = 0;
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < w * h; i++) {
                    sum += Math.abs(frame.channels[c][i] - rgb[c][i]);
                }
            }
            assertTrue(sum / (double) (w * h * 3) <= 1.5, "mean colour diff at " + w + "x" + h);
        }
    }

    @Test
    void djxlDecodesOurLossyOutput() throws Exception {
        assumeTrue(JxlTools.available(), "djxl not available");
        int w = 200;
        int h = 160;
        int[][] rgb = photo(w, h);
        byte[] jxl = VarDctEncoder.encode(rgb, w, h, 1.0f);
        int[][] reference = JxlTools.djxl(tempDir, "vardct", jxl);
        long sum = 0;
        int max = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                int d = Math.abs(reference[c][i] - rgb[c][i]);
                max = Math.max(max, d);
                sum += d;
            }
        }
        assertTrue(max <= 24, "max diff vs djxl " + max);
        assertTrue(sum / (double) (w * h * 3) <= 1.5, "mean diff " + sum / (double) (w * h * 3));
    }

    /** Mixed smooth/busy content: half sine gradients, half noisy texture. */
    private static int[][] mixedContent(int w, int h, int seed) {
        int[][] p = photo(w, h);
        java.util.Random rnd = new java.util.Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = w / 2; x < w; x++) {
                int i = y * w + x;
                int base = 90 + 50 * (((x / 4) + (y / 4)) % 2);
                for (int c = 0; c < 3; c++) {
                    p[c][i] = Math.max(0, Math.min(255, base + rnd.nextInt(50) - 25));
                }
            }
        }
        return p;
    }

    @Test
    void djxlDecodesAdaptiveQuantAndDct16Output() throws Exception {
        assumeTrue(JxlTools.available(), "djxl not available");
        // large and smooth enough that 16x16 blocks and per-block multipliers
        // are actually chosen
        int w = 512;
        int h = 320;
        int[][] rgb = mixedContent(w, h, 3);
        byte[] jxl = VarDctEncoder.encode(rgb, w, h, 2.0f);
        int[][] reference = JxlTools.djxl(tempDir, "vardct16", jxl);
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(reference[c][i] - rgb[c][i]);
            }
        }
        double mean = sum / (double) (w * h * 3);
        assertTrue(mean <= 8.0, "mean diff vs djxl " + mean);
    }

    @Test
    void rateControlTracksTarget() throws Exception {
        int w = 400;
        int h = 300;
        int[][] rgb = mixedContent(w, h, 7);
        byte[] near = VarDctEncoder.encodeToTarget(rgb, w, h, 1.0f);
        byte[] far = VarDctEncoder.encodeToTarget(rgb, w, h, 6.0f);
        assertTrue(far.length < near.length,
                "coarser target must be smaller: " + far.length + " vs " + near.length);
        JxlFrame frame = JxlDecoder.decode(near).frames.get(0);
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(frame.channels[c][i] - rgb[c][i]);
            }
        }
        double mean = sum / (double) (w * h * 3);
        assertTrue(mean <= 2.5, "distance-1 target should stay near-lossless, mean " + mean);
    }

    @Test
    void imageIoLossyQuality() throws Exception {
        int w = 160;
        int h = 120;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int[][] rgb = photo(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                img.setRGB(x, y, (rgb[0][i] << 16) | (rgb[1][i] << 8) | rgb[2][i]);
            }
        }
        javax.imageio.ImageWriter writer =
                javax.imageio.ImageIO.getImageWritersByFormatName("jxl").next();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream out =
                javax.imageio.ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("lossy");
            param.setCompressionQuality(0.95f);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        byte[] lossy = bytes.toByteArray();
        assertTrue(lossy.length < w * h, "lossy output should be small: " + lossy.length);

        java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(lossy));
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = img.getRGB(x, y);
                int b = back.getRGB(x, y);
                sum += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
            }
        }
        assertTrue(sum / (double) (w * h * 3) <= 2.0,
                "mean diff " + sum / (double) (w * h * 3));
    }

    /** Mean absolute error of a self-decode, on the 8-bit scale. */
    private static double selfError(int[][] src, int w, int h, float distance)
            throws Exception {
        byte[] jxl = VarDctEncoder.encode(src, w, h, distance);
        int[][] out = JxlDecoder.decode(jxl).frames.get(0).channels;
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(out[c][i] - src[c][i]);
            }
        }
        return sum / (3.0 * w * h);
    }

    /** High-frequency content: the hardest thing for the encoder to hand back. */
    private static int[][] fine(int w, int h) {
        int[][] p = new int[3][w * h];
        java.util.Random rnd = new java.util.Random(7);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                int x = i % w;
                int y = i / w;
                // a checkerboard at the Nyquist limit, plus noise, plus a ramp
                int detail = ((x + y) & 1) == 0 ? 60 : -60;
                p[c][i] = Math.max(0, Math.min(255,
                        128 + detail + rnd.nextInt(21) - 10 + (x * 40) / w));
            }
        }
        return p;
    }

    /**
     * A finer quantiser has to keep buying quality all the way down.
     *
     * <p>It did not. The encoder pre-sharpens its input so that the decoder's
     * gaborish blur lands back on the source, and that pre-sharpening is a solve,
     * run as a fixed-point iteration — but it was stopped after three steps, which
     * at the frequencies gaborish attenuates most is nowhere near converged. What
     * was left over did not depend on the quantiser, so it sat under every lossy
     * encode as a floor: this image used to come back with a mean error of about
     * 6/255 at distance 2 and about 6/255 at distance 0.1, no matter how many bits
     * it was given. Nothing measured the ceiling, so nothing noticed.
     */
    @Test
    void fineDistanceKeepsBuyingQuality() throws Exception {
        int w = 128;
        int h = 128;
        int[][] src = fine(w, h);
        double coarse = selfError(src, w, h, 2.0f);
        double fine = selfError(src, w, h, 0.1f);
        assertTrue(fine < 0.5,
                "distance 0.1 should be near-exact, got mean error " + fine);
        assertTrue(fine < coarse / 4,
                "a 20x finer quantiser bought almost nothing: " + coarse + " -> " + fine);
    }

    /** And the error has to fall monotonically as the quantiser tightens. */
    @Test
    void errorFallsWithDistance() throws Exception {
        int w = 128;
        int h = 128;
        int[][] src = fine(w, h);
        double previous = Double.MAX_VALUE;
        for (float d : new float[] {3f, 2f, 1.5f, 1f, 0.5f, 0.25f}) {
            double err = selfError(src, w, h, d);
            assertTrue(err < previous,
                    "distance " + d + " gave " + err + ", no better than " + previous);
            previous = err;
        }
    }

    /** A smooth low-frequency image, where the encoder reaches for the 32x32 transform. */
    private static int[][] sky(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = Math.max(0, Math.min(255, 128 + (int) (50 * Math.sin(x * 0.01))));
                p[1][i] = Math.max(0, Math.min(255, 120 + (int) (40 * Math.cos(y * 0.008))));
                p[2][i] = Math.max(0, Math.min(255, 100 + (x + y) / 12));
            }
        }
        return p;
    }

    /**
     * Large smooth regions get the 32x32 transform, which has to reconstruct
     * exactly as the decoder reads it — a 4x4 low-frequency corner drawn from the
     * DC image and the rest as high frequency, over sixteen cells. If any of that
     * were off, a smooth gradient would come back as garbage rather than a close
     * match; that it stays close is what says the whole DCT32 path is right.
     */
    @Test
    void largeSmoothImageWithDct32RoundTrips() throws Exception {
        int w = 256;
        int h = 256;
        int[][] src = sky(w, h);
        for (float d : new float[] {1f, 2f, 3f}) {
            double err = selfError(src, w, h, d);
            assertTrue(err < d, "smooth image at distance " + d + " decoded far off: " + err);
        }
    }

    /** The 32x32 transform never crosses a group edge, so streaming reaches it too. */
    @Test
    void streamedSmoothImageWithDct32() throws Exception {
        int w = 600;
        int h = 500;
        int[][] src = sky(w, h);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder enc =
                new com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder(
                        out, w, h, 8, false, false, false, 1.5f)) {
            for (int y = 0; y < h; y++) {
                int[][] row = new int[3][w];
                for (int c = 0; c < 3; c++) {
                    System.arraycopy(src[c], y * w, row[c], 0, w);
                }
                enc.writeRows(row, 1);
            }
        }
        int[][] back = JxlDecoder.decode(out.toByteArray()).frames.get(0).channels;
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(back[c][i] - src[c][i]);
            }
        }
        assertTrue(sum / (3.0 * w * h) < 2.0, "streamed smooth image decoded far off");
    }

    /** A NaN or infinite distance is a caller bug: refuse it at the API. */
    @Test
    void nonFiniteDistancesAreRefused() {
        int[][] rgb = photo(64, 48);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encode(rgb, 64, 48, Float.NaN));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeToTarget(rgb, 64, 48, Float.NaN));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeToTarget(rgb, 64, 48, Float.POSITIVE_INFINITY));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder.targetingQuality(
                        new java.io.ByteArrayOutputStream(), 64, 48, 8,
                        false, false, false, Float.NaN));
    }

    /**
     * NaN samples make every quality measurement NaN, so no round ever beats
     * the first — the loop has to keep round zero rather than return null and
     * turn into a caller NPE far from the cause.
     */
    @Test
    void nanPlanesDoNotNullOutTheTargetLoop() throws Exception {
        float[][] planes = new float[3][32 * 32];
        for (float[] p : planes) {
            java.util.Arrays.fill(p, Float.NaN);
        }
        byte[] jxl = VarDctEncoder.encodeFloatToTarget(planes, 32, 32,
                com.ebremer.cygnus.jpegxl.codestream.BitDepth.float32(),
                false, false, false, 1.5f);
        org.junit.jupiter.api.Assertions.assertNotNull(jxl);
    }
}
