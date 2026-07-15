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
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.AnimationFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Multi-frame animation: durations, timing metadata, patches, blending. */
class AnimationTest {

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

    private static int[][] solid(int w, int h, int r, int g, int b) {
        int[][] p = new int[3][w * h];
        java.util.Arrays.fill(p[0], r);
        java.util.Arrays.fill(p[1], g);
        java.util.Arrays.fill(p[2], b);
        return p;
    }

    private static int[][] noise(int w, int h, int channels, long seed) {
        int[][] p = new int[channels][w * h];
        Random r = new Random(seed);
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < w * h; i++) {
                p[c][i] = (r.nextInt(200) + i / 5) & 0xff;
            }
        }
        return p;
    }

    /** The core: every frame is a whole picture, shown in turn, and comes back exactly. */
    @Test
    void fullFramesRoundTripWithDurations() throws Exception {
        int w = 64;
        int h = 48;
        int[][][] src = {
            solid(w, h, 255, 0, 0), solid(w, h, 0, 255, 0), solid(w, h, 0, 0, 255),
            solid(w, h, 200, 100, 50),
        };
        int[] durations = {10, 25, 5, 40};
        List<AnimationFrame> frames = new ArrayList<>();
        for (int i = 0; i < src.length; i++) {
            frames.add(AnimationFrame.full(src[i], w, h, durations[i]));
        }
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, false, List.of(), 100, 1, 3);

        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(img.metadata.haveAnimation);
        assertEquals(100, img.metadata.animTpsNumerator);
        assertEquals(1, img.metadata.animTpsDenominator);
        assertEquals(3, img.metadata.animNumLoops);
        assertEquals(src.length, img.frames.size());
        for (int i = 0; i < src.length; i++) {
            JxlFrame f = img.frames.get(i);
            assertEquals(durations[i], f.duration, "duration of frame " + i);
            for (int c = 0; c < 3; c++) {
                assertArrayEquals(src[i][c], f.channels[c], "frame " + i + " plane " + c);
            }
        }
    }

    /** A single-frame animation is legal (a still with a declared duration). */
    @Test
    void singleFrameAnimation() throws Exception {
        int w = 40;
        int h = 30;
        int[][] p = noise(w, h, 3, 7);
        byte[] jxl = JxlEncoder.encodeAnimation(
                List.of(AnimationFrame.full(p, w, h, 100)), w, h, 8, false, List.of(), 30, 1, 0);
        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(1, img.frames.size());
        assertEquals(0, img.metadata.animNumLoops); // loop forever
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(p[c], img.frames.get(0).channels[c]);
        }
    }

    /** A patch updates only its rectangle; the rest of the canvas is inherited. */
    @Test
    void patchFrameInheritsTheCanvas() throws Exception {
        int w = 80;
        int h = 60;
        int[][] bg = solid(w, h, 255, 0, 0);
        int pw = 20;
        int ph = 15;
        int px = 10;
        int py = 8;
        int[][] patch = solid(pw, ph, 0, 0, 255);
        List<AnimationFrame> frames = List.of(
                AnimationFrame.full(bg, w, h, 10),
                AnimationFrame.patch(patch, pw, ph, px, py, 10),
                AnimationFrame.full(solid(w, h, 0, 255, 0), w, h, 10));
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, false, List.of(), 100, 1, 0);

        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(3, img.frames.size());
        int[][] f1 = img.frames.get(1).channels;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean inPatch = x >= px && x < px + pw && y >= py && y < py + ph;
                int wantR = inPatch ? 0 : 255;
                int wantB = inPatch ? 255 : 0;
                assertEquals(wantR, f1[0][i], "R at " + x + "," + y);
                assertEquals(0, f1[1][i], "G at " + x + "," + y);
                assertEquals(wantB, f1[2][i], "B at " + x + "," + y);
            }
        }
    }

    /** A blended frame lays itself over the canvas through its alpha. */
    @Test
    void blendedFrameCompositesOverTheCanvas() throws Exception {
        int w = 48;
        int h = 32;
        List<ExtraChannelInfo> alpha = List.of(ExtraChannelInfo.alpha(BitDepth.of(8), false));
        int[][] bg = new int[4][w * h];
        java.util.Arrays.fill(bg[0], 200);
        java.util.Arrays.fill(bg[3], 255);          // opaque red
        int[][] overlay = new int[4][w * h];
        java.util.Arrays.fill(overlay[2], 200);
        java.util.Arrays.fill(overlay[3], 128);      // half-opaque blue
        List<AnimationFrame> frames = List.of(
                AnimationFrame.full(bg, w, h, 10),
                AnimationFrame.blended(overlay, w, h, 10, 0));
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, false, alpha, 100, 1, 0);

        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(2, img.frames.size());
        int[][] c = img.frames.get(1).channels;
        // straight alpha over: result = a*new + (1-a)*old, a = 128/255
        double a = 128.0 / 255.0;
        int expR = (int) Math.round(a * 0 + (1 - a) * 200);
        int expB = (int) Math.round(a * 200 + (1 - a) * 0);
        assertTrue(Math.abs(c[0][0] - expR) <= 1, "R " + c[0][0] + " vs ~" + expR);
        assertTrue(Math.abs(c[2][0] - expB) <= 1, "B " + c[2][0] + " vs ~" + expB);
    }

    /** Animation carries extra channels, exactly, like any other frame. */
    @Test
    void animationWithExtraChannels() throws Exception {
        int w = 50;
        int h = 40;
        List<ExtraChannelInfo> extras = List.of(
                ExtraChannelInfo.alpha(BitDepth.of(8), false),
                ExtraChannelInfo.of(ExtraChannelInfo.TYPE_DEPTH, BitDepth.of(16), "depth"));
        List<AnimationFrame> frames = new ArrayList<>();
        int[][][] src = new int[3][][];
        for (int k = 0; k < 3; k++) {
            src[k] = new int[5][];
            for (int c = 0; c < 3; c++) {
                src[k][c] = noise(w, h, 1, 100L * k + c)[0];
            }
            src[k][3] = noise(w, h, 1, 200L + k)[0];              // 8-bit alpha
            int[] depth = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                depth[i] = (i * 37 + k) & 0xffff;
            }
            src[k][4] = depth;                                    // 16-bit depth
            frames.add(AnimationFrame.full(src[k], w, h, 12));
        }
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, false, extras, 50, 1, 0);
        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(2, img.metadata.numExtraChannels());
        assertEquals(3, img.frames.size());
        for (int k = 0; k < 3; k++) {
            int[][] got = img.frames.get(k).channels;
            for (int c = 0; c < 5; c++) {
                assertArrayEquals(src[k][c], got[c], "frame " + k + " channel " + c);
            }
        }
    }

    /** Greyscale animation: one colour channel throughout. */
    @Test
    void greyscaleAnimation() throws Exception {
        int w = 40;
        int h = 40;
        List<AnimationFrame> frames = new ArrayList<>();
        int[][][] src = new int[4][][];
        for (int k = 0; k < 4; k++) {
            src[k] = new int[][] {noise(w, h, 1, k)[0]};
            frames.add(AnimationFrame.full(src[k], w, h, 8));
        }
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, true, List.of(), 100, 1, 0);
        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(4, img.frames.size());
        for (int k = 0; k < 4; k++) {
            assertArrayEquals(src[k][0], img.frames.get(k).channels[0], "grey frame " + k);
        }
    }

    @Test
    void rejectsBadArguments() {
        int[][] p = solid(8, 8, 1, 2, 3);
        assertThrows(IllegalArgumentException.class, () ->
                JxlEncoder.encodeAnimation(List.of(), 8, 8, 8, false, List.of(), 100, 1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                JxlEncoder.encodeAnimation(List.of(AnimationFrame.full(p, 8, 8, 1)),
                        8, 8, 8, false, List.of(), 0, 1, 0));   // zero tps
        assertThrows(IllegalArgumentException.class, () ->
                AnimationFrame.full(p, 8, 8, -1));               // negative duration
    }

    /** libjxl reads a real animation of ours and reports the right size. */
    @Test
    void libjxlReadsOurAnimation() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not on the PATH");
        int w = 320;
        int h = 240;
        List<AnimationFrame> frames = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            frames.add(AnimationFrame.full(noise(w, h, 3, k), w, h, 10));
        }
        byte[] jxl = JxlEncoder.encodeAnimation(frames, w, h, 8, false, List.of(), 100, 1, 0);
        Path file = tempDir.resolve("anim.jxl");
        Files.write(file, jxl);

        Process probe = new ProcessBuilder("ffprobe", "-v", "error",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", file.toString())
                .redirectErrorStream(true).start();
        String size = new String(probe.getInputStream().readAllBytes()).trim();
        probe.waitFor(60, TimeUnit.SECONDS);
        assertTrue(size.startsWith("320,240"), "ffprobe size: " + size);

        Path raw = tempDir.resolve("anim.raw");
        Process dec = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", raw.toString())
                .redirectErrorStream(true).start();
        String log = new String(dec.getInputStream().readAllBytes());
        dec.waitFor(120, TimeUnit.SECONDS);
        assertEquals(0, dec.exitValue(), "ffmpeg could not decode our animation: " + log);
    }
}
