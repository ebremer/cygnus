package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.AnimationFrame;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lossy (VarDCT) animation: a sequence of full-canvas frames each coded through
 * the quantiser, shown for its own duration. The lossless path keeps every frame
 * exact; this trades that for size.
 */
class AnimationVarDctTest {

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

    /** A distinct picture per frame — a moving wave, so frames must not be confused. */
    private static int[][] frame(int w, int h, int phase) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = (int) (120 + 80 * Math.sin((x + phase * 8) * 0.05) * Math.cos(y * 0.04));
                p[1][i] = (int) (110 + 70 * Math.sin((x + y + phase * 6) * 0.03));
                p[2][i] = (int) (100 + 60 * Math.cos((x - phase * 5) * 0.02));
            }
        }
        return p;
    }

    private static double mean(int[][] a, int[][] b, int w, int h) {
        long s = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                s += Math.abs(a[c][i] - b[c][i]);
            }
        }
        return s / (3.0 * w * h);
    }

    @Test
    void lossyFramesRoundTripWithDurations() throws Exception {
        int w = 192;
        int h = 160;
        int[] durations = {10, 25, 5, 40};
        int[][][] src = new int[durations.length][][];
        List<AnimationFrame> frames = new ArrayList<>();
        for (int i = 0; i < durations.length; i++) {
            src[i] = frame(w, h, i);
            frames.add(AnimationFrame.full(deep(src[i]), w, h, durations[i]));
        }
        byte[] jxl = VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, false, 1.0f, 100, 1, 3);
        assertTrue(jxl.length < w * h * 3 * durations.length, "should compress: " + jxl.length);

        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(img.metadata.haveAnimation);
        assertEquals(100, img.metadata.animTpsNumerator);
        assertEquals(3, img.metadata.animNumLoops);
        assertEquals(durations.length, img.frames.size());
        for (int i = 0; i < durations.length; i++) {
            JxlFrame f = img.frames.get(i);
            assertEquals(durations[i], f.duration, "duration of frame " + i);
            assertTrue(mean(src[i], f.channels, w, h) < 3.0, "frame " + i + " fidelity");
        }
        // the frames are genuinely different pictures, not the same one repeated
        assertTrue(mean(img.frames.get(0).channels, img.frames.get(1).channels, w, h) > 5.0,
                "consecutive frames should differ");
    }

    @Test
    void greyLossyAnimation() throws Exception {
        int w = 128;
        int h = 96;
        List<AnimationFrame> frames = new ArrayList<>();
        int[][] a = {frame(w, h, 0)[1]};
        int[][] b = {frame(w, h, 2)[1]};
        frames.add(AnimationFrame.full(a, w, h, 12));
        frames.add(AnimationFrame.full(b, w, h, 12));
        byte[] jxl = VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, true, 1.0f, 30, 1, 0);
        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(2, img.frames.size());
        assertEquals(12, img.frames.get(0).duration);
        int[] got = img.frames.get(0).channels[0];
        long s = 0;
        for (int i = 0; i < w * h; i++) {
            s += Math.abs(a[0][i] - got[i]);
        }
        assertTrue(s / (double) (w * h) < 3.0, "grey frame fidelity");
    }

    /** libjxl reads our lossy animation and decodes every frame. */
    @Test
    void libjxlReadsOurLossyAnimation() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not on the PATH");
        int w = 320;
        int h = 240;
        int nFrames = 5;
        List<AnimationFrame> frames = new ArrayList<>();
        for (int k = 0; k < nFrames; k++) {
            frames.add(AnimationFrame.full(frame(w, h, k), w, h, 10));
        }
        byte[] jxl = VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, false, 1.5f, 100, 1, 0);
        Path file = tempDir.resolve("lossy-anim.jxl");
        Files.write(file, jxl);

        Process probe = new ProcessBuilder("ffprobe", "-v", "error",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", file.toString())
                .redirectErrorStream(true).start();
        String size = new String(probe.getInputStream().readAllBytes()).trim();
        probe.waitFor(60, TimeUnit.SECONDS);
        assertTrue(size.startsWith("320,240"), "ffprobe size: " + size);

        Path raw = tempDir.resolve("lossy-anim.raw");
        Process dec = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", raw.toString())
                .redirectErrorStream(true).start();
        String log = new String(dec.getInputStream().readAllBytes());
        dec.waitFor(120, TimeUnit.SECONDS);
        assertEquals(0, dec.exitValue(), "ffmpeg could not decode our lossy animation: " + log);
        // libjxl decoded whole frames (ffmpeg repeats each for its tick duration, so the
        // raw is a whole number of frames and at least one output frame per input frame)
        long frameBytes = (long) w * h * 3;
        long raws = Files.size(raw);
        assertTrue(raws % frameBytes == 0 && raws >= (long) nFrames * frameBytes,
                "decoded raw size " + raws + " is not a run of whole " + w + "x" + h + " frames");
    }

    /** Multi-group frames (larger than a group) carry the animation header too. */
    @Test
    void multiGroupRoundTrips() throws Exception {
        int w = 320;
        int h = 240;
        int[][] px = frame(w, h, 0);
        byte[] anim = VarDctEncoder.encodeVarDctAnimation(
                List.of(AnimationFrame.full(deep(px), w, h, 10),
                        AnimationFrame.full(deep(frame(w, h, 3)), w, h, 15)),
                w, h, 8, false, 1.5f, 100, 1, 0);
        JxlImage img = JxlDecoder.decode(anim);
        assertEquals(2, img.frames.size());
        assertEquals(10, img.frames.get(0).duration);
        assertTrue(mean(px, img.frames.get(0).channels, w, h) < 3.0, "multi-group frame fidelity");
    }

    /** Per-frame SMPTE timecodes ride the frame headers and come back exactly. */
    @Test
    void timecodesRoundTrip() throws Exception {
        int w = 128;
        int h = 96;
        long[] tc = {0x01020304L, 0x01020305L, 0x0102030AL};
        List<AnimationFrame> frames = new ArrayList<>();
        for (int i = 0; i < tc.length; i++) {
            frames.add(AnimationFrame.full(frame(w, h, i), w, h, 8).withTimecode(tc[i]));
        }
        byte[] jxl = VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, false, 1.5f, 100, 1, 0);
        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(img.metadata.animHaveTimecodes, "metadata should announce timecodes");
        assertEquals(tc.length, img.frames.size());
        for (int i = 0; i < tc.length; i++) {
            assertEquals(tc[i], img.frames.get(i).timecode, "timecode of frame " + i);
        }
    }

    /** libjxl accepts an animation whose frames carry timecodes. */
    @Test
    void libjxlReadsTimecodes() throws Exception {
        assumeTrue(ffmpegAvailable, "ffmpeg with libjxl not on the PATH");
        int w = 160;
        int h = 128;
        List<AnimationFrame> frames = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            frames.add(AnimationFrame.full(frame(w, h, k), w, h, 10).withTimecode(0x01000000L + k));
        }
        byte[] jxl = VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, false, 1.5f, 100, 1, 0);
        Path file = tempDir.resolve("tc-anim.jxl");
        Files.write(file, jxl);
        Process dec = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", tempDir.resolve("tc.raw").toString())
                .redirectErrorStream(true).start();
        String log = new String(dec.getInputStream().readAllBytes());
        dec.waitFor(120, TimeUnit.SECONDS);
        assertEquals(0, dec.exitValue(), "ffmpeg could not decode our timecoded animation: " + log);
    }

    /** Without withTimecode, the animation carries no timecodes (byte-for-byte the old path). */
    @Test
    void noTimecodesByDefault() throws Exception {
        int w = 96;
        int h = 96;
        List<AnimationFrame> frames =
                List.of(AnimationFrame.full(frame(w, h, 0), w, h, 8),
                        AnimationFrame.full(frame(w, h, 1), w, h, 8));
        JxlImage img = JxlDecoder.decode(
                VarDctEncoder.encodeVarDctAnimation(frames, w, h, 8, false, 1.5f, 100, 1, 0));
        assertTrue(!img.metadata.animHaveTimecodes, "no timecodes announced");
        assertEquals(0, img.frames.get(0).timecode);
    }

    @Test
    void rejectsNonFullFrames() {
        int w = 64;
        int h = 64;
        List<AnimationFrame> patch =
                List.of(AnimationFrame.patch(frame(32, 32, 0), 32, 32, 8, 8, 10));
        assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeVarDctAnimation(patch, w, h, 8, false, 1.0f, 100, 1, 0));
    }

    private static int[][] deep(int[][] x) {
        int[][] y = new int[x.length][];
        for (int i = 0; i < x.length; i++) {
            y[i] = x[i].clone();
        }
        return y;
    }
}
