package com.ebremer.cygnus.jpeg2000;

import com.ebremer.cygnus.jpeg2000.decoder.DecodedImage;
import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.jpeg2000.testutil.MiniJ2kEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-validation against ffmpeg's independent JPEG 2000 implementation:
 * files encoded by ffmpeg must decode identically (reversible 5/3) or within
 * a small tolerance (9/7) to ffmpeg's own decoder output, and streams from
 * the test-scope encoder must be readable by ffmpeg. Skipped when ffmpeg is
 * not on the PATH.
 */
@EnabledIf("ffmpegAvailable")
class ExternalCodecTest {

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path workDir() throws IOException {
        Path dir = Path.of("target", "ffmpeg-tests");
        Files.createDirectories(dir);
        return dir;
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] log = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "ffmpeg timed out");
        assertEquals(0, p.exitValue(),
                () -> String.join(" ", cmd) + "\n" + new String(log));
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /**
     * Encodes raw pixels with ffmpeg, decodes the result with both ffmpeg and
     * Cygnus, and compares all component planes.
     *
     * @param planes  per-channel widths/heights, or null for an interleaved
     *                full-resolution format (rgb24, rgba)
     */
    private void ffmpegRoundTrip(String name, String pixFmt, int w, int h,
                                 int[][] planes, int bytesPerSample, byte[] raw,
                                 String container, int tolerance, String... encodeOpts)
            throws Exception {
        boolean interleaved = planes == null;
        int numChan = interleaved
                ? raw.length / (w * h * bytesPerSample)
                : planes.length;
        if (interleaved) {
            planes = fullPlanes(w, h, numChan);
        }
        Path dir = workDir();
        Path inRaw = dir.resolve(name + ".raw");
        Path coded = dir.resolve(name + "." + container);
        Path refRaw = dir.resolve(name + ".ref.raw");
        Files.write(inRaw, raw);

        String[] base = {
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pixel_format", pixFmt, "-video_size", w + "x" + h,
            "-i", inRaw.toString(), "-frames:v", "1", "-c:v", "jpeg2000",
            "-format", container.equals("jp2") ? "jp2" : "j2k"
        };
        String[] cmd = new String[base.length + encodeOpts.length + 2];
        System.arraycopy(base, 0, cmd, 0, base.length);
        System.arraycopy(encodeOpts, 0, cmd, base.length, encodeOpts.length);
        cmd[cmd.length - 2] = "-f";
        cmd[cmd.length - 1] = "image2";
        String[] full = new String[cmd.length + 1];
        System.arraycopy(cmd, 0, full, 0, cmd.length);
        full[full.length - 1] = coded.toString();
        run(full);

        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", coded.toString(),
                "-f", "rawvideo", "-pix_fmt", pixFmt, refRaw.toString());
        byte[] ref = Files.readAllBytes(refRaw);

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(Files.readAllBytes(coded));
        assertEquals(w, img.width, name + ": width");
        assertEquals(h, img.height, name + ": height");
        assertEquals(planes.length, img.numChannels, name + ": channels");

        int offset = 0;
        for (int c = 0; c < planes.length; c++) {
            int cw = planes[c][0];
            int ch = planes[c][1];
            assertEquals(cw, img.chanWidth[c], name + ": chan " + c + " width");
            assertEquals(ch, img.chanHeight[c], name + ": chan " + c + " height");
            int bad = 0;
            int firstBad = -1;
            for (int i = 0; i < cw * ch; i++) {
                int pos = interleaved
                        ? (i * numChan + c) * bytesPerSample
                        : offset + i * bytesPerSample;
                int refV;
                if (bytesPerSample == 1) {
                    refV = ref[pos] & 0xFF;
                } else {
                    refV = (ref[pos] & 0xFF) | ((ref[pos + 1] & 0xFF) << 8); // LE
                }
                int got = img.samples[c][i];
                if (Math.abs(got - refV) > tolerance) {
                    bad++;
                    if (firstBad < 0) {
                        firstBad = i;
                    }
                }
            }
            assertEquals(0, bad, name + ": channel " + c + " mismatches, first at "
                    + firstBad + " " + dec.warnings());
            offset += cw * ch * bytesPerSample;
        }
    }

    private static int[][] fullPlanes(int w, int h, int n) {
        int[][] p = new int[n][];
        for (int i = 0; i < n; i++) {
            p[i] = new int[] {w, h};
        }
        return p;
    }

    // ---- ffmpeg encodes, Cygnus decodes ----

    @Test
    void grayReversible() throws Exception {
        int w = 137, h = 91;
        ffmpegRoundTrip("gray53", "gray", w, h, fullPlanes(w, h, 1), 1,
                randomBytes(w * h, 31), "j2k", 0, "-pred", "dwt53");
    }

    @Test
    void grayIrreversible97() throws Exception {
        int w = 120, h = 84;
        // smooth data (noise is pathological for lossy wavelets); ffmpeg's own
        // integer 9/7 vs our float 9/7 may differ by a few LSBs
        byte[] raw = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raw[y * w + x] = (byte) ((x * 2 + y) % 256);
            }
        }
        ffmpegRoundTrip("gray97", "gray", w, h, fullPlanes(w, h, 1), 1,
                raw, "j2k", 3, "-pred", "dwt97int");
    }

    @Test
    void rgbJp2Container() throws Exception {
        int w = 64, h = 48;
        ffmpegRoundTrip("rgb", "rgb24", w, h, null, 1,
                randomBytes(w * h * 3, 32), "jp2", 0, "-pred", "dwt53");
    }

    @Test
    void yuv420SubsampledComponents() throws Exception {
        int w = 90, h = 70;
        int cw = (w + 1) / 2, ch = (h + 1) / 2;
        byte[] raw = randomBytes(w * h + 2 * cw * ch, 33);
        ffmpegRoundTrip("yuv420", "yuv420p", w, h,
                new int[][] {{w, h}, {cw, ch}, {cw, ch}}, 1, raw, "j2k", 0,
                "-pred", "dwt53");
    }

    @Test
    void tiledImage() throws Exception {
        int w = 200, h = 150;
        ffmpegRoundTrip("tiled", "gray", w, h, fullPlanes(w, h, 1), 1,
                randomBytes(w * h, 34), "j2k", 0,
                "-pred", "dwt53", "-tile_width", "64", "-tile_height", "64");
    }

    @Test
    void sopEphMarkers() throws Exception {
        int w = 100, h = 80;
        ffmpegRoundTrip("sopeph", "gray", w, h, fullPlanes(w, h, 1), 1,
                randomBytes(w * h, 35), "j2k", 0,
                "-pred", "dwt53", "-sop", "1", "-eph", "1");
    }

    @Test
    void positionBasedProgressions() throws Exception {
        int w = 128, h = 96;
        for (String prog : new String[] {"rlcp", "rpcl", "pcrl", "cprl"}) {
            ffmpegRoundTrip("prog_" + prog, "gray", w, h, fullPlanes(w, h, 1), 1,
                    randomBytes(w * h, 36), "j2k", 0,
                    "-pred", "dwt53", "-prog", prog,
                    "-tile_width", "64", "-tile_height", "64");
        }
    }

    @Test
    void sixteenBitGray() throws Exception {
        int w = 80, h = 60;
        ffmpegRoundTrip("gray16", "gray16le", w, h, fullPlanes(w, h, 1), 2,
                randomBytes(w * h * 2, 37), "j2k", 0, "-pred", "dwt53");
    }

    @Test
    void rgbaWithAlphaChannel() throws Exception {
        int w = 60, h = 40;
        ffmpegRoundTrip("rgba", "rgba", w, h, null, 1,
                randomBytes(w * h * 4, 38), "jp2", 0, "-pred", "dwt53");
    }

    @Test
    void multipleQualityLayers() throws Exception {
        int w = 128, h = 128;
        ffmpegRoundTrip("layers", "gray", w, h, fullPlanes(w, h, 1), 1,
                randomBytes(w * h, 39), "j2k", 0,
                "-pred", "dwt53", "-layer_rates", "8,4,1");
    }

    @Test
    void reducedResolutionMatchesFfmpegLowres() throws Exception {
        int w = 200, h = 150;
        byte[] raw = randomBytes(w * h, 41);
        Path dir = workDir();
        Path inRaw = dir.resolve("lowres.raw");
        Path coded = dir.resolve("lowres.j2k");
        Files.write(inRaw, raw);
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "gray", "-video_size", w + "x" + h,
                "-i", inRaw.toString(), "-frames:v", "1", "-c:v", "jpeg2000",
                "-pred", "dwt53", "-tile_width", "64", "-tile_height", "64",
                "-format", "j2k", "-f", "image2", coded.toString());

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(Files.readAllBytes(coded));
        for (int d = 1; d <= 2; d++) {
            Path outRaw = dir.resolve("lowres.d" + d + ".raw");
            run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-lowres", String.valueOf(d), "-i", coded.toString(),
                    "-f", "rawvideo", "-pix_fmt", "gray", outRaw.toString());
            byte[] ref = Files.readAllBytes(outRaw);
            DecodedImage img = dec.decode(d);
            assertEquals((w + (1 << d) - 1) >> d, img.imageWidth, "d=" + d);
            assertEquals((h + (1 << d) - 1) >> d, img.imageHeight, "d=" + d);
            assertEquals(ref.length, img.imageWidth * img.imageHeight, "d=" + d);
            for (int i = 0; i < ref.length; i++) {
                assertEquals(ref[i] & 0xFF, img.samples[0][i],
                        "d=" + d + " pixel " + i);
            }
        }
    }

    // ---- Cygnus (test encoder) encodes, ffmpeg decodes ----

    @Test
    void ffmpegReadsMiniEncoderOutput() throws Exception {
        int w = 64, h = 48;
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = new int[1][w * h];
        Random rnd = new Random(40);
        byte[] expected = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            comps[0][i] = rnd.nextInt(256);
            expected[i] = (byte) comps[0][i];
        }
        Path dir = workDir();
        Path coded = dir.resolve("mini.j2k");
        Path outRaw = dir.resolve("mini.out.raw");
        Files.write(coded, MiniJ2kEncoder.encode(comps, cfg));
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", coded.toString(),
                "-f", "rawvideo", "-pix_fmt", "gray", outRaw.toString());
        byte[] got = Files.readAllBytes(outRaw);
        assertEquals(expected.length, got.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != got[i]) {
                assertEquals(expected[i], got[i], "pixel " + i);
            }
        }
    }
}
