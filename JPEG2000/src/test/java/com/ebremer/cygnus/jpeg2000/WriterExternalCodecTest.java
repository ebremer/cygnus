package com.ebremer.cygnus.jpeg2000;

import com.ebremer.cygnus.jpeg2000.imageio.CygnusImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-validation of the Cygnus writer against ffmpeg's independent
 * JPEG 2000 implementation: files written by the ImageIO plugin must decode
 * with ffmpeg to the original samples exactly (reversible) or within a small
 * tolerance (9/7). Skipped when ffmpeg is not on the PATH.
 */
@EnabledIf("ffmpegAvailable")
class WriterExternalCodecTest {

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

    private static BufferedImage randomImage(int w, int h, int type, long seed) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random rnd = new Random(seed);
        var raster = img.getRaster();
        for (int b = 0; b < raster.getNumBands(); b++) {
            int max = (1 << raster.getSampleModel().getSampleSize(b)) - 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    raster.setSample(x, y, b, rnd.nextInt(max + 1));
                }
            }
        }
        return img;
    }

    private static byte[] writeWithPlugin(BufferedImage img, ImageWriteParam param)
            throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg2000");
        assertTrue(it.hasNext());
        ImageWriter w = it.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, null), param);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * Writes {@code img} with the plugin, decodes the file with ffmpeg to
     * raw planes in {@code pixFmt}, and compares every band sample.
     */
    private void ffmpegChecks(String name, BufferedImage img, ImageWriteParam param,
                              String pixFmt, int bytesPerSample, int tolerance)
            throws Exception {
        String ext = param instanceof CygnusImageWriteParam cp
                && cp.getWriteCodeStreamOnly() ? "j2k" : "jp2";
        Path dir = workDir();
        Path coded = dir.resolve(name + "." + ext);
        Path outRaw = dir.resolve(name + ".out.raw");
        Files.write(coded, writeWithPlugin(img, param));

        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", coded.toString(),
                "-f", "rawvideo", "-pix_fmt", pixFmt, outRaw.toString());
        byte[] got = Files.readAllBytes(outRaw);

        int w = img.getWidth();
        int h = img.getHeight();
        int bands = img.getRaster().getNumBands();
        assertEquals((long) w * h * bands * bytesPerSample, got.length,
                name + ": decoded size");
        var raster = img.getRaster();
        int bad = 0;
        int firstBad = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < bands; b++) {
                    int pos = ((y * w + x) * bands + b) * bytesPerSample;
                    int refV = bytesPerSample == 1
                            ? got[pos] & 0xFF
                            : (got[pos] & 0xFF) | ((got[pos + 1] & 0xFF) << 8); // LE
                    int expect = raster.getSample(x, y, b);
                    if (Math.abs(expect - refV) > tolerance) {
                        bad++;
                        if (firstBad < 0) {
                            firstBad = y * w + x;
                        }
                    }
                }
            }
        }
        assertEquals(0, bad, name + ": mismatches, first at " + firstBad);
    }

    @Test
    void ffmpegReadsGrayCodestream() throws Exception {
        BufferedImage img = randomImage(137, 91, BufferedImage.TYPE_BYTE_GRAY, 51);
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        ffmpegChecks("wgray", img, p, "gray", 1, 0);
    }

    @Test
    void ffmpegReadsRgbJp2() throws Exception {
        BufferedImage img = randomImage(64, 48, BufferedImage.TYPE_INT_RGB, 52);
        ffmpegChecks("wrgb", img, null, "rgb24", 1, 0);
    }

    @Test
    void ffmpegReadsRgbaJp2WithAlpha() throws Exception {
        BufferedImage img = randomImage(60, 40, BufferedImage.TYPE_INT_ARGB, 53);
        // raster band order is R,G,B,A which matches ffmpeg's rgba
        ffmpegChecks("wrgba", img, null, "rgba", 1, 0);
    }

    @Test
    void ffmpegReadsSixteenBitGray() throws Exception {
        BufferedImage img = randomImage(80, 60, BufferedImage.TYPE_USHORT_GRAY, 54);
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        ffmpegChecks("wgray16", img, p, "gray16le", 2, 0);
    }

    @Test
    void ffmpegReadsTiledImage() throws Exception {
        BufferedImage img = randomImage(200, 150, BufferedImage.TYPE_BYTE_GRAY, 55);
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        p.setTiling(64, 64, 0, 0);
        ffmpegChecks("wtiled", img, p, "gray", 1, 0);
    }

    @Test
    void ffmpegReadsSopEphMarkers() throws Exception {
        BufferedImage img = randomImage(100, 80, BufferedImage.TYPE_BYTE_GRAY, 56);
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        p.setSopMarkers(true);
        p.setEphMarkers(true);
        ffmpegChecks("wsopeph", img, p, "gray", 1, 0);
    }

    @Test
    void ffmpegReadsSmallCodeBlocks() throws Exception {
        BufferedImage img = randomImage(70, 50, BufferedImage.TYPE_BYTE_GRAY, 57);
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        p.setCodeBlockSize(16, 32);
        ffmpegChecks("wsmallcb", img, p, "gray", 1, 0);
    }

    @Test
    void ffmpegReadsLossy97() throws Exception {
        // smooth data; ffmpeg's integer 9/7 vs our float 9/7 plus the
        // quantization at quality 0.9 stay within a few counts
        BufferedImage img = new BufferedImage(120, 84, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 84; y++) {
            for (int x = 0; x < 120; x++) {
                img.getRaster().setSample(x, y, 0, (x * 2 + y) % 256);
            }
        }
        CygnusImageWriteParam p = new CygnusImageWriteParam();
        p.setWriteCodeStreamOnly(true);
        p.setCompressionType(CygnusImageWriteParam.LOSSY);
        p.setCompressionQuality(0.9f);
        ffmpegChecks("wlossy", img, p, "gray", 1, 4);
    }
}
