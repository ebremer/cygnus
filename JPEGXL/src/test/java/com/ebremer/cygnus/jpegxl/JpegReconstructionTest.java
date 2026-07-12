package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.jpeg.JpegReconstructor;
import com.ebremer.cygnus.jpegxl.testutil.JxlTools;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Byte-exact JPEG reconstruction from cjxl-recompressed files (jbrd boxes):
 * the reconstructed bytes must equal the original JPEG exactly.
 */
class JpegReconstructionTest {

    @TempDir
    static Path tempDir;

    /** A photo-like RGB test image. */
    private static BufferedImage photo(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (int) (120 + 80 * Math.sin(x * 0.05) * Math.cos(y * 0.04));
                int g = (int) (110 + 70 * Math.sin((x + 2 * y) * 0.02));
                int b = (int) (100 + 60 * Math.cos(x * 0.03 - y * 0.02) + 20 * Math.sin(x * y * 0.001));
                img.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static byte[] writeJpeg(BufferedImage img, boolean progressive, float quality)
            throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        if (progressive) {
            p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }

    @ParameterizedTest(name = "{3} {0}x{1}")
    @CsvSource({
        "336,256,false,baseline",
        "200,150,false,odd-dims",       // frame padded beyond the coded size
        "336,256,true,progressive",
        "129,97,true,progressive-odd",
    })
    void reconstructsByteExactly(int w, int h, boolean progressive, String name)
            throws Exception {
        assumeTrue(JxlTools.available(), "cjxl not available");
        byte[] jpeg = writeJpeg(photo(w, h), progressive, 0.82f);
        Path jpegFile = tempDir.resolve(name + ".jpg");
        Files.write(jpegFile, jpeg);
        Path jxlFile = tempDir.resolve(name + ".jxl");
        JxlTools.run(JxlTools.find("cjxl"), jpegFile.toString(), jxlFile.toString(),
                "--lossless_jpeg=1");
        byte[] jxl = Files.readAllBytes(jxlFile);

        assertTrue(JpegReconstructor.hasJpegData(jxl), "jbrd box expected");
        byte[] rec = JpegReconstructor.reconstruct(jxl);
        assertArrayEquals(jpeg, rec, "reconstruction must be byte-exact");
    }

    @ParameterizedTest(name = "gray {0}x{1}")
    @CsvSource({"180,140"})
    void reconstructsGrayscale(int w, int h) throws Exception {
        assumeTrue(JxlTools.available(), "cjxl not available");
        BufferedImage colour = photo(w, h);
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        var g = gray.createGraphics();
        g.drawImage(colour, 0, 0, null);
        g.dispose();
        byte[] jpeg = writeJpeg(gray, false, 0.85f);
        Path jpegFile = tempDir.resolve("gray.jpg");
        Files.write(jpegFile, jpeg);
        Path jxlFile = tempDir.resolve("gray.jxl");
        JxlTools.run(JxlTools.find("cjxl"), jpegFile.toString(), jxlFile.toString(),
                "--lossless_jpeg=1");
        byte[] rec = JpegReconstructor.reconstruct(Files.readAllBytes(jxlFile));
        assertArrayEquals(jpeg, rec);
    }

    @org.junit.jupiter.api.Test
    void reportsMissingJbrd() throws Exception {
        // a plain (non-recompressed) encode has no reconstruction data
        int[][] rgb = new int[3][64 * 48];
        byte[] jxl = com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.encode(rgb, 64, 48, 8,
                false, false, false);
        assertTrue(!JpegReconstructor.hasJpegData(jxl));
    }
}
