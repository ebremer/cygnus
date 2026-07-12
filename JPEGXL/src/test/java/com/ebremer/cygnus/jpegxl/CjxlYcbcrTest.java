package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.testutil.JxlTools;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recompressed-JPEG (YCbCr) frames: cjxl wraps a JPEG's DCT coefficients in a
 * VarDCT frame with do_YCbCr and the JPEG's chroma subsampling. Pixel output
 * is compared against djxl. Byte-exact JPEG reconstruction (jbrd) is out of
 * scope; these tests cover the pixel path.
 */
class CjxlYcbcrTest {

    @TempDir
    static Path tempDir;

    private static boolean ffmpegAvailable;

    @BeforeAll
    static void checkTools() {
        assumeTrue(JxlTools.available(), "cjxl/djxl not available");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            ffmpegAvailable = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
        assumeTrue(ffmpegAvailable, "ffmpeg not available");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"yuvj444p", "yuvj422p", "yuvj420p"})
    void recompressedJpegMatchesDjxl(String pixFmt) throws Exception {
        Path jpg = tempDir.resolve(pixFmt + ".jpg");
        JxlTools.run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=200x160:rate=1", "-frames:v", "1",
                "-pix_fmt", pixFmt, jpg.toString());
        Path jxl = tempDir.resolve(pixFmt + ".jxl");
        JxlTools.run(JxlTools.find("cjxl"), jpg.toString(), jxl.toString());
        byte[] bytes = Files.readAllBytes(jxl);

        JxlImage ours = JxlDecoder.decode(bytes);
        assertEquals(200, ours.width);
        assertEquals(160, ours.height);
        JxlFrame frame = ours.frames.get(0);

        Path refPng = tempDir.resolve(pixFmt + "-ref.png");
        JxlTools.run(JxlTools.find("djxl"), jxl.toString(), refPng.toString());
        BufferedImage ref = ImageIO.read(refPng.toFile());

        int max = 0;
        long sum = 0;
        for (int y = 0; y < 160; y++) {
            for (int x = 0; x < 200; x++) {
                int rgb = ref.getRGB(x, y);
                int[] rv = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
                for (int c = 0; c < 3; c++) {
                    int d = Math.abs(frame.channels[c][y * 200 + x] - rv[c]);
                    max = Math.max(max, d);
                    sum += d;
                }
            }
        }
        double mean = sum / (double) (200 * 160 * 3);
        assertTrue(max <= 2, pixFmt + " max diff " + max);
        assertTrue(mean <= 0.35, pixFmt + " mean diff " + mean);
    }
}
