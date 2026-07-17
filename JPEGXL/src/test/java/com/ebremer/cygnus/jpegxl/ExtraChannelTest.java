package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import com.ebremer.cygnus.jpegxl.encoder.JxlStreamingEncoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extra channels beyond a single alpha: any number, of any type the format
 * defines, each with its own depth, name and resolution.
 */
class ExtraChannelTest {

    @TempDir
    static Path tempDir;

    private static boolean ffmpegAvailable;

    @BeforeAll
    static void checkFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-decoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            ffmpegAvailable = out.contains("libjxl");
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
    }

    private static int[] ramp(int w, int h, int max, int seed) {
        int[] p = new int[w * h];
        Random r = new Random(seed);
        for (int i = 0; i < w * h; i++) {
            p[i] = Math.min(max, Math.max(0, (i % w) * max / Math.max(1, w) + r.nextInt(7) - 3));
        }
        return p;
    }

    /** Colour planes plus one plane per extra, each the size its channel asks for. */
    private static int[][] planesFor(int w, int h, List<ExtraChannelInfo> extras) {
        int[][] planes = new int[3 + extras.size()][];
        for (int c = 0; c < 3; c++) {
            planes[c] = ramp(w, h, 255, c);
        }
        for (int i = 0; i < extras.size(); i++) {
            ExtraChannelInfo ec = extras.get(i);
            int max = ec.bitDepth.floatingPoint ? 255
                    : (1 << ec.bitDepth.bitsPerSample) - 1;
            planes[3 + i] = ramp(ec.planeWidth(w), ec.planeHeight(h), max, 40 + i);
        }
        return planes;
    }

    /** One of every type the format defines, at four different bit depths. */
    private static List<ExtraChannelInfo> everyType() {
        List<ExtraChannelInfo> ex = new ArrayList<>();
        ex.add(ExtraChannelInfo.alpha(BitDepth.of(8), false));
        ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"));
        ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_SELECTION_MASK, BitDepth.of(1), "mask"));
        ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_BLACK, BitDepth.of(8), "K"));
        ex.add(ExtraChannelInfo.cfa(BitDepth.of(12), "cfa", 2));
        ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_THERMAL, BitDepth.of(12), "thermal"));
        ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL, BitDepth.of(8), "spare"));
        return ex;
    }

    /**
     * Type 15 says "this channel is required and you do not know what it is",
     * so a conformant decoder has to refuse the file — libjxl does. An encoder
     * that writes one is producing something nobody can read, so it must not.
     */
    @Test
    void theOneTypeThatCannotBeWritten() {
        List<ExtraChannelInfo> ex = List.of(ExtraChannelInfo.of(
                ExtraChannelInfo.TYPE_NON_OPTIONAL, BitDepth.of(8), "required"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JxlEncoder.encode(planesFor(64, 64, ex), 64, 64, 8, false, ex));
        assertTrue(e.getMessage().contains("TYPE_OPTIONAL"), e.getMessage());
    }

    @Test
    void everyTypeAtEveryDepthRoundTrips() throws Exception {
        int w = 300;
        int h = 200;
        List<ExtraChannelInfo> ex = everyType();
        int[][] planes = planesFor(w, h, ex);
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, ex);

        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(ex.size(), img.metadata.numExtraChannels());
        int[][] back = img.frames.get(0).channels;
        for (int i = 0; i < ex.size(); i++) {
            assertArrayEquals(planes[3 + i], back[3 + i],
                    "extra channel " + i + " (" + ex.get(i).name + ")");
        }
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(planes[c], back[c], "colour plane " + c);
        }
    }

    /** The names and types have to survive too, or the channels are anonymous. */
    @Test
    void metadataSurvives() throws Exception {
        List<ExtraChannelInfo> ex = everyType();
        byte[] jxl = JxlEncoder.encode(planesFor(120, 90, ex), 120, 90, 8, false, ex);
        JxlImage img = JxlDecoder.decode(jxl);
        for (int i = 0; i < ex.size(); i++) {
            ExtraChannelInfo want = ex.get(i);
            ExtraChannelInfo got = img.metadata.extraChannels.get(i);
            assertEquals(want.type, got.type, "type of channel " + i);
            assertEquals(want.name, got.name, "name of channel " + i);
            assertEquals(want.bitDepth.bitsPerSample, got.bitDepth.bitsPerSample,
                    "depth of channel " + i);
        }
        assertEquals(2, img.metadata.extraChannels.get(4).cfaChannel);
    }

    /**
     * A reduced-resolution channel is stored smaller and stretched back out, so
     * what it costs is fewer samples, not fewer bytes per sample.
     */
    @Test
    void dimShiftStoresFewerSamples() throws Exception {
        int w = 300;
        int h = 200;
        long previous = Long.MAX_VALUE;
        for (int shift = 0; shift <= 3; shift++) {
            ExtraChannelInfo ec = ExtraChannelInfo.of(
                    ExtraChannelInfo.TYPE_SELECTION_MASK, BitDepth.of(8), "mask");
            ec.dimShift = shift;
            assertEquals((w + ec.step() - 1) / ec.step(), ec.planeWidth(w));
            List<ExtraChannelInfo> ex = List.of(ec);
            byte[] jxl = JxlEncoder.encode(planesFor(w, h, ex), w, h, 8, false, ex);
            // the decoder hands it back at full size, having upsampled it
            int[] back = JxlDecoder.decode(jxl).frames.get(0).channels[3];
            assertEquals(w * h, back.length, "shift " + shift + " should decode full size");
            assertTrue(jxl.length < previous,
                    "shift " + shift + " should be smaller than shift " + (shift - 1));
            previous = jxl.length;
        }
    }

    /**
     * A reduced-resolution channel in an image wide enough for several groups,
     * with content chosen so a self-contained per-group section beats the
     * global code: the left group's samples are all multiples of eight, a
     * saving only a local tree's residual multiplier can reach. The local
     * section has to slice the shifted channel exactly as the decoder will, or
     * the group desyncs — caught here by the full-resolution channel coded
     * behind the shifted one, which then comes back as noise.
     */
    @Test
    void dimShiftSurvivesLocalGroupSections() throws Exception {
        int w = 512;
        int h = 256;
        ExtraChannelInfo mask = ExtraChannelInfo.of(
                ExtraChannelInfo.TYPE_SELECTION_MASK, BitDepth.of(8), "mask");
        mask.dimShift = 1;
        ExtraChannelInfo id = ExtraChannelInfo.of(
                ExtraChannelInfo.TYPE_OPTIONAL, BitDepth.of(8), "id");
        List<ExtraChannelInfo> ex = List.of(mask, id);
        int[][] planes = new int[5][];
        Random rnd = new Random(7);
        for (int c = 0; c < 3; c++) {
            planes[c] = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                planes[c][i] = i % w < 256 ? 8 * rnd.nextInt(32) : rnd.nextInt(256);
            }
        }
        int ew = mask.planeWidth(w);
        planes[3] = new int[ew * mask.planeHeight(h)];
        for (int i = 0; i < planes[3].length; i++) {
            planes[3][i] = (i % ew ^ i / ew) & 1;
        }
        planes[4] = ramp(w, h, 255, 99);
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, ex);
        int[][] back = JxlDecoder.decode(jxl).frames.get(0).channels;
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(planes[c], back[c], "colour plane " + c);
        }
        assertEquals(w * h, back[3].length, "the mask comes back upsampled to full size");
        assertArrayEquals(planes[4], back[4], "the full-resolution channel behind the mask");
    }

    /**
     * Hundreds of channels mean hundreds of tree leaves — more contexts than
     * the cluster merge's quadratic matrix comfortably holds, so the encoder
     * coarsely pre-folds them first. The fold must keep the context mapping
     * sound: every channel back exactly.
     */
    @Test
    void hundredsOfExtraChannelsRoundTrip() throws Exception {
        int w = 32;
        int h = 32;
        int count = 400;
        List<ExtraChannelInfo> ex = new ArrayList<>();
        int[][] planes = new int[3 + count][];
        for (int c = 0; c < 3; c++) {
            planes[c] = ramp(w, h, 255, c);
        }
        Random r = new Random(11);
        for (int i = 0; i < count; i++) {
            ex.add(ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL, BitDepth.of(8), "c" + i));
            int[] p = new int[w * h];
            int bias = r.nextInt(200);
            for (int j = 0; j < p.length; j++) {
                p[j] = bias + r.nextInt(56);
            }
            planes[3 + i] = p;
        }
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, ex);
        int[][] back = JxlDecoder.decode(jxl).frames.get(0).channels;
        for (int i = 0; i < planes.length; i++) {
            assertArrayEquals(planes[i], back[i], "plane " + i);
        }
    }

    /** A spot colour is an ink: the decoder mixes it onto the picture. */
    @Test
    void spotColourIsComposited() throws Exception {
        int w = 64;
        int h = 64;
        ExtraChannelInfo ink = ExtraChannelInfo.spot(BitDepth.of(8), "ink", 1f, 0f, 0f, 1f);
        List<ExtraChannelInfo> ex = List.of(ink);
        int[][] planes = new int[4][w * h];
        for (int c = 0; c < 3; c++) {
            java.util.Arrays.fill(planes[c], 0);   // black
        }
        // left half uninked, right half fully inked
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                planes[3][y * w + x] = x < w / 2 ? 0 : 255;
            }
        }
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, ex);
        int[][] back = JxlDecoder.decode(jxl).frames.get(0).channels;
        assertArrayEquals(planes[3], back[3], "the coverage map itself is exact");
        // black stays black where there is no ink, and turns red where there is
        assertEquals(0, back[0][0]);
        assertTrue(back[0][w - 1] > 250, "inked pixel should be red, got " + back[0][w - 1]);
        assertEquals(0, back[1][w - 1]);
    }

    /** Lossy colour, exact extras: a depth buffer must not be smeared by the DCT. */
    @Test
    void lossyColourKeepsExtrasExact() throws Exception {
        for (int[] wh : new int[][] {{200, 150}, {700, 500}}) { // one group, then many
            int w = wh[0];
            int h = wh[1];
            List<ExtraChannelInfo> ex = List.of(
                    ExtraChannelInfo.alpha(BitDepth.of(8), false),
                    ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"),
                    ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL, BitDepth.of(8), "id"));
            int[][] planes = planesFor(w, h, ex);
            byte[] jxl = VarDctEncoder.encode(planes, w, h, 8, false, ex, 1.5f);
            int[][] back = JxlDecoder.decode(jxl).frames.get(0).channels;
            for (int i = 0; i < ex.size(); i++) {
                assertArrayEquals(planes[3 + i], back[3 + i],
                        w + "x" + h + " extra channel " + i);
            }
            long err = 0;
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < w * h; i++) {
                    err += Math.abs(back[c][i] - planes[c][i]);
                }
            }
            assertTrue(err / (3.0 * w * h) < 5, "colour should be lossy but close");
        }
    }

    /** Streaming, lossless and lossy, has to reach the same place. */
    @Test
    void streamedExtrasMatchTheWholeImageEncoder() throws Exception {
        int w = 700;
        int h = 500;
        List<ExtraChannelInfo> ex = List.of(
                ExtraChannelInfo.alpha(BitDepth.of(8), false),
                ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"),
                ExtraChannelInfo.of(ExtraChannelInfo.TYPE_THERMAL, BitDepth.of(8), "heat"));
        int[][] planes = planesFor(w, h, ex);
        for (float distance : new float[] {0f, 1.5f}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (JxlStreamingEncoder enc =
                    new JxlStreamingEncoder(out, w, h, 8, false, ex, distance)) {
                for (int y = 0; y < h; y++) {
                    int[][] row = new int[3 + ex.size()][w];
                    for (int c = 0; c < row.length; c++) {
                        System.arraycopy(planes[c], y * w, row[c], 0, w);
                    }
                    enc.writeRows(row, 1);
                }
            }
            int[][] back = JxlDecoder.decode(out.toByteArray()).frames.get(0).channels;
            for (int i = 0; i < ex.size(); i++) {
                assertArrayEquals(planes[3 + i], back[3 + i],
                        "streamed at distance " + distance + ", extra channel " + i);
            }
        }
    }

    /**
     * A row-at-a-time encoder takes full-width rows, so a channel that is stored
     * at a quarter of the width has nowhere to put itself. Say so, rather than
     * silently write a channel of the wrong shape.
     */
    @Test
    void streamingRefusesReducedResolutionChannels() {
        ExtraChannelInfo ec = ExtraChannelInfo.of(
                ExtraChannelInfo.TYPE_SELECTION_MASK, BitDepth.of(8), "small");
        ec.dimShift = 2;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JxlStreamingEncoder(new ByteArrayOutputStream(), 700, 500, 8,
                        false, List.of(ec), 0f));
        assertTrue(e.getMessage().contains("full resolution"), e.getMessage());
    }

    /** A float image can carry an 8-bit mask without widening it to 32 bits. */
    @Test
    void floatColourWithMixedDepthExtras() throws Exception {
        int w = 160;
        int h = 120;
        List<ExtraChannelInfo> ex = List.of(
                ExtraChannelInfo.alpha(BitDepth.float32(), false),
                ExtraChannelInfo.of(ExtraChannelInfo.TYPE_OPTIONAL, BitDepth.of(8), "mask"));
        float[][] planes = new float[3 + ex.size()][w * h];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                planes[c][i] = (float) Math.sin(i * 0.01 + c) * 3f;
            }
        }
        for (int i = 0; i < w * h; i++) {
            planes[3][i] = (float) Math.cos(i * 0.02) * 100f;   // float alpha, out of [0,1]
            planes[4][i] = (i % 256) / 255f;                    // 8-bit mask coverage
        }
        byte[] jxl = JxlEncoder.encodeFloat(planes, w, h, BitDepth.float32(), false, ex);
        JxlFrame frame = JxlDecoder.decode(jxl).frames.get(0);
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < w * h; i++) {
                assertEquals(Float.floatToRawIntBits(planes[c][i]),
                        Float.floatToRawIntBits(frame.floatChannels[c][i]),
                        "float plane " + c + " sample " + i);
            }
        }
        for (int i = 0; i < w * h; i++) {
            assertEquals(Math.round(planes[4][i] * 255), frame.channels[4][i],
                    "8-bit mask sample " + i);
        }
    }

    /** Squeeze rewrites every channel, extras included. */
    @Test
    void progressiveCarriesExtras() throws Exception {
        int w = 260;
        int h = 180;
        List<ExtraChannelInfo> ex = List.of(
                ExtraChannelInfo.alpha(BitDepth.of(8), false),
                ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"));
        int[][] planes = planesFor(w, h, ex);
        byte[] jxl = JxlEncoder.encodeProgressive(planes, w, h, 8, false, ex);
        int[][] back = JxlDecoder.decode(jxl).frames.get(0).channels;
        for (int i = 0; i < 3 + ex.size(); i++) {
            assertArrayEquals(planes[i], back[i], "plane " + i);
        }
    }

    /** The header's half-precision floats have to survive the round trip. */
    @Test
    void everyFiniteHalfRoundTrips() throws Exception {
        for (int bits = 0; bits < (1 << 16); bits++) {
            if (((bits >> 10) & 0x1f) == 31) {
                continue; // infinity and NaN: refused on the way in, rejected on the way out
            }
            float value = Float.float16ToFloat((short) bits);
            BitWriter w = new BitWriter();
            w.writeF16(value);
            w.zeroPadToByte();
            float back = new Bits(w.toByteArray()).f16();
            assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits(back),
                    "half 0x" + Integer.toHexString(bits));
        }
    }

    /** Half cannot hold every float, and a spot colour says what it stored. */
    @Test
    void spotColourSnapsToWhatTheHeaderCanHold() {
        ExtraChannelInfo ink = ExtraChannelInfo.spot(BitDepth.of(8), "ink", 0.9f, 0.1f, 0f, 1f);
        assertEquals(0.9f, ink.spotColour[0], 0.001f);
        assertTrue(ink.spotColour[0] != 0.9f, "0.9 is not a half; it should have been snapped");
        assertEquals(BitWriter.roundF16(0.9f), ink.spotColour[0]);
        assertThrows(IllegalArgumentException.class,
                () -> new BitWriter().writeF16(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new BitWriter().writeF16(1e30f));
    }

    /** libjxl has to agree, or we have only convinced ourselves. */
    @Test
    void libjxlReadsOurExtraChannels() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not on the PATH");
        int w = 300;
        int h = 200;
        List<ExtraChannelInfo> ex = everyType();
        // an ink whose coverage varies, so libjxl's compositing is exercised too
        ex.add(ExtraChannelInfo.spot(BitDepth.of(8), "ink", 0.9f, 0.1f, 0.25f, 1f));
        int[][] planes = planesFor(w, h, ex);
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, false, ex);

        Path file = tempDir.resolve("extras.jxl");
        Files.write(file, jxl);
        Path raw = tempDir.resolve("extras.raw");
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgba", "-y", raw.toString())
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        p.waitFor(120, TimeUnit.SECONDS);
        assertEquals(0, p.exitValue(), "ffmpeg could not read our file: " + log);

        byte[] got = Files.readAllBytes(raw);
        assertEquals(4L * w * h, got.length);
        int[][] ours = JxlDecoder.decode(jxl).frames.get(0).channels;
        // alpha is our first extra channel, and comes back exactly
        long worstAlpha = 0;
        long worstColour = 0;
        for (int i = 0; i < w * h; i++) {
            worstAlpha = Math.max(worstAlpha, Math.abs((got[4 * i + 3] & 0xff) - ours[3][i]));
            for (int c = 0; c < 3; c++) {
                worstColour = Math.max(worstColour,
                        Math.abs((got[4 * i + c] & 0xff) - ours[c][i]));
            }
        }
        assertEquals(0, worstAlpha, "libjxl's alpha should be exactly ours");
        // the colour has the ink composited into it by both decoders; they may
        // differ by a rounding step, and by no more than that
        assertTrue(worstColour <= 1,
                "libjxl's spot-composited colour differs by " + worstColour);
    }
}
