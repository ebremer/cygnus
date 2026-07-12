package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Content chosen to push libjxl into specific modular features: palettes
 * (few distinct colours), smooth gradients (weighted predictor), repetitive
 * tiles (LZ77), and dithered content (context trees).
 */
class FfmpegFeatureCoverageTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void checkFfmpeg() {
        boolean available;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            available = out.contains("libjxl");
        } catch (Exception e) {
            available = false;
        }
        assumeTrue(available, "ffmpeg with libjxl not available");
    }

    static int[][] palette(int w, int h, int colours, long seed) {
        Random rnd = new Random(seed);
        int[][] lut = new int[colours][3];
        for (int[] c : lut) {
            c[0] = rnd.nextInt(256);
            c[1] = rnd.nextInt(256);
            c[2] = rnd.nextInt(256);
        }
        int[][] out = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] c = lut[(x / 7 + y / 5 + (rnd.nextInt(4) == 0 ? 1 : 0)) % colours];
                out[0][y * w + x] = c[0];
                out[1][y * w + x] = c[1];
                out[2][y * w + x] = c[2];
            }
        }
        return out;
    }

    static int[][] gradient16(int w, int h) {
        int[][] out = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[0][y * w + x] = (x * 65535 / Math.max(1, w - 1));
                out[1][y * w + x] = (y * 65535 / Math.max(1, h - 1));
                out[2][y * w + x] = ((x + y) * 65535 / Math.max(1, w + h - 2));
            }
        }
        return out;
    }

    static int[][] tiles(int w, int h) {
        int[][] out = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = ((x % 16) ^ (y % 16)) * 16;
                out[0][y * w + x] = v;
                out[1][y * w + x] = 255 - v;
                out[2][y * w + x] = (v * 3) & 0xff;
            }
        }
        return out;
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("palette8", palette(200, 150, 8, 3), 8, 7),
                Arguments.of("palette8-e9", palette(200, 150, 8, 3), 8, 9),
                Arguments.of("palette200", palette(300, 280, 200, 4), 8, 7),
                Arguments.of("gradient16", gradient16(320, 240), 16, 7),
                Arguments.of("gradient16-e9", gradient16(320, 240), 16, 9),
                Arguments.of("tiles", tiles(320, 288), 8, 7),
                Arguments.of("tiles-e9", tiles(320, 288), 8, 9)
        );
    }

    @ParameterizedTest(name = "{0} effort {3}")
    @MethodSource("cases")
    void decodeFfmpegEncoded(String name, int[][] planes, int bits, int effort) throws Exception {
        int n = planes[0].length;
        int w = 0;
        int h = 0;
        // recover dimensions from the generators above
        switch (name.split("-")[0]) {
            case "palette8" -> {
                w = 200;
                h = 150;
            }
            case "palette200" -> {
                w = 300;
                h = 280;
            }
            case "gradient16" -> {
                w = 320;
                h = 240;
            }
            case "tiles" -> {
                w = 320;
                h = 288;
            }
            default -> throw new IllegalArgumentException(name);
        }
        if (w * h != n) {
            throw new IllegalStateException();
        }

        String pixFmt = bits > 8 ? "rgb48le" : "rgb24";
        Path rawFile = tempDir.resolve(name + "-" + effort + ".raw");
        Path jxlFile = tempDir.resolve(name + "-" + effort + ".jxl");
        Files.write(rawFile, writeRaw(planes, bits));
        Process p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", pixFmt, "-video_size", w + "x" + h,
                "-i", rawFile.toString(),
                "-frames:v", "1", "-c:v", "libjxl", "-distance", "0",
                "-effort", Integer.toString(effort), jxlFile.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("ffmpeg failed: " + out);
        }

        JxlImage image = JxlDecoder.decode(Files.readAllBytes(jxlFile));
        JxlFrame frame = image.frames.get(0);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(planes[c], frame.channels[c], "plane " + c);
        }
    }

    private static byte[] writeRaw(int[][] planes, int bits) {
        int bytesPerSample = bits > 8 ? 2 : 1;
        byte[] raw = new byte[planes[0].length * planes.length * bytesPerSample];
        int idx = 0;
        for (int i = 0; i < planes[0].length; i++) {
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
}
