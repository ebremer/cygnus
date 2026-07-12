package com.ebremer.jpegxl.jpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlFrame;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.encoder.JpegRecompressor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JPEG -> JPEG XL recompression must reconstruct the original JPEG byte for
 * byte, and the recompressed file must still decode to matching pixels.
 */
class JpegRecompressTest {

    @TempDir
    static Path tempDir;

    // ------------------------------------------------------------ test JPEGs

    /** Photographic-ish content with gradients, edges and texture. */
    private static BufferedImage testImage(int w, int h, boolean grey) {
        BufferedImage img = new BufferedImage(w, h,
                grey ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255 / Math.max(1, w - 1) + rnd.nextInt(24)) & 255;
                int g = (y * 255 / Math.max(1, h - 1) + ((x / 16 + y / 16) % 2) * 60) & 255;
                int b = ((x + y) / 3 + rnd.nextInt(12)) & 255;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static byte[] imageIoJpeg(BufferedImage img, boolean progressive, float quality)
            throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        if (progressive) {
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        writer.dispose();
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------ assertions

    private static void assertRoundTrip(byte[] jpeg) throws IOException {
        byte[] jxl = JpegRecompressor.encode(jpeg);
        assertTrue(JpegReconstructor.hasJpegData(jxl), "jbrd box missing");
        byte[] rebuilt = JpegReconstructor.reconstruct(jxl);
        assertArrayEquals(jpeg, rebuilt, "reconstructed JPEG differs");
    }

    /** The recompressed file must also decode to (approximately) JPEG pixels. */
    private static void assertPixelsClose(byte[] jpeg) throws IOException {
        BufferedImage viaJpeg = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        JxlImage image = JxlDecoder.decode(JpegRecompressor.encode(jpeg));
        JxlFrame frame = image.frames.get(0);
        assertEquals(viaJpeg.getWidth(), image.width);
        assertEquals(viaJpeg.getHeight(), image.height);
        // compare raw raster samples: getRGB would colour-convert (linear
        // grayscale in particular), while the codecs both carry JPEG samples
        java.awt.image.Raster raster = viaJpeg.getRaster();
        int channels = Math.min(image.numColourChannels(), raster.getNumBands());
        long sumErr = 0;
        long n = 0;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                for (int c = 0; c < channels; c++) {
                    int got = frame.channels[c][y * image.width + x];
                    int exp = raster.getSample(x, y, c);
                    int err = Math.abs(got - exp);
                    assertTrue(err <= 24, "pixel (" + x + ", " + y + ") channel " + c
                            + ": " + got + " vs " + exp);
                    sumErr += err;
                    n++;
                }
            }
        }
        double mean = (double) sumErr / n;
        assertTrue(mean < 1.5, "mean pixel error " + mean);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void parserFeedsWriterExactly() throws IOException {
        // isolates the parser <-> writer pair from the jbrd transport
        for (boolean progressive : new boolean[] {false, true}) {
            byte[] jpeg = imageIoJpeg(testImage(600, 420, false), progressive, 0.85f);
            byte[] rewritten = new JpegWriter(JpegParser.parse(jpeg)).write();
            assertArrayEquals(jpeg, rewritten, "progressive=" + progressive);
        }
    }

    @Test
    void baselineRoundTrip() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(600, 420, false), false, 0.85f);
        assertRoundTrip(jpeg);
        assertPixelsClose(jpeg);
    }

    @Test
    void greyRoundTrip() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(300, 200, true), false, 0.9f);
        assertRoundTrip(jpeg);
        assertPixelsClose(jpeg);
    }

    @Test
    void progressiveRoundTrip() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(600, 420, false), true, 0.8f);
        assertRoundTrip(jpeg);
        assertPixelsClose(jpeg);
    }

    @Test
    void progressiveGreyRoundTrip() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(257, 130, true), true, 0.75f);
        assertRoundTrip(jpeg);
    }

    @Test
    void multiLfGroupRoundTrip() throws IOException {
        // wider than one 2048px LF group
        byte[] jpeg = imageIoJpeg(testImage(2200, 300, false), false, 0.7f);
        assertRoundTrip(jpeg);
    }

    @Test
    void tinyImagesRoundTrip() throws IOException {
        for (int[] dim : new int[][] {{1, 1}, {7, 5}, {8, 8}, {17, 9}, {16, 16}}) {
            byte[] jpeg = imageIoJpeg(testImage(dim[0], dim[1], false), false, 0.9f);
            assertRoundTrip(jpeg);
        }
    }

    @Test
    void lowQualityHighQualityRoundTrip() throws IOException {
        assertRoundTrip(imageIoJpeg(testImage(400, 300, false), false, 0.15f));
        assertRoundTrip(imageIoJpeg(testImage(400, 300, false), false, 1.0f));
    }

    @Test
    void comMarkerAndTailDataRoundTrip() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(120, 90, false), false, 0.85f);
        // splice a COM segment right after SOI, and garbage after EOI
        byte[] comment = "hello jbrd".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xFE);
        out.write(0);
        out.write(comment.length + 2);
        out.writeBytes(comment);
        out.write(jpeg, 2, jpeg.length - 2);
        out.writeBytes(new byte[] {13, 10, 42, 0, (byte) 255});
        assertRoundTrip(out.toByteArray());
    }

    @Test
    void restartIntervalRoundTrip() throws IOException {
        // no local encoder writes restart markers, so re-emit an existing
        // JPEG through JpegWriter with a DRI marker spliced in
        byte[] base = imageIoJpeg(testImage(500, 340, false), false, 0.85f);
        JpegData data = JpegParser.parse(base);
        data.restartInterval = 5;
        byte[] order = new byte[data.markerOrder.length + 1];
        // insert DRI right before the first SOS
        int sos = 0;
        while ((data.markerOrder[sos] & 0xff) != 0xDA) {
            sos++;
        }
        System.arraycopy(data.markerOrder, 0, order, 0, sos);
        order[sos] = (byte) 0xDD;
        System.arraycopy(data.markerOrder, sos, order, sos + 1,
                data.markerOrder.length - sos);
        data.markerOrder = order;
        byte[] withRestarts = new JpegWriter(data).write();
        assertRoundTrip(withRestarts);
    }

    @Test
    void progressiveWithResetPointsAndExtraZeroRuns() throws IOException {
        // exotic encoder behaviours: early EOB-run flushes and useless ZRLs.
        // Hand-built so the Huffman tables contain every symbol the exotic
        // stream needs (optimised tables from real encoders would not).
        JpegData data = new JpegData();
        data.width = 48;
        data.height = 8;
        data.markerOrder = new byte[] {
            (byte) 0xDB, (byte) 0xC2, (byte) 0xC4, (byte) 0xDA, (byte) 0xDA, (byte) 0xD9,
        };

        JpegData.QuantTable qt = new JpegData.QuantTable();
        java.util.Arrays.fill(qt.values, 16);
        data.quant.add(qt);

        JpegData.HuffmanCode dc = new JpegData.HuffmanCode();
        dc.slotId = 0x00;
        dc.isLast = false;
        dc.counts[1] = 2; // symbol 0 plus the sentinel
        dc.values[0] = 0;
        dc.values[1] = 256;
        data.huffmanCodes.add(dc);
        JpegData.HuffmanCode ac = new JpegData.HuffmanCode();
        ac.slotId = 0x10;
        ac.counts[3] = 5; // EOB0, size-1 coeff, EOB2-3, ZRL, sentinel
        ac.values[0] = 0x00;
        ac.values[1] = 0x01;
        ac.values[2] = 0x10;
        ac.values[3] = 0xF0;
        ac.values[4] = 256;
        data.huffmanCodes.add(ac);

        JpegData.Component comp = new JpegData.Component();
        comp.id = 1;
        comp.quantIdx = 0;
        comp.widthInBlocks = 6;
        comp.heightInBlocks = 1;
        comp.coeffs = new short[6 * 64];
        comp.coeffs[1] = 1;           // block 0: one AC coefficient
        comp.coeffs[5 * 64 + 1] = -1; // block 5: one AC coefficient
        data.components.add(comp);

        JpegData.ScanInfo dcScan = new JpegData.ScanInfo();
        dcScan.numComponents = 1;
        data.scanInfo.add(dcScan);
        JpegData.ScanInfo acScan = new JpegData.ScanInfo();
        acScan.numComponents = 1;
        acScan.ss = 1;
        acScan.se = 63;
        acScan.components[0].acTblIdx = 0;
        // blocks 0..2 form one EOB run, the reset point splits off 3..4,
        // and block 5 carries a pointless extra ZRL before its band ends
        acScan.resetPoints = new int[] {3};
        acScan.extraZeroRunBlock = new int[] {5};
        acScan.extraZeroRunCount = new int[] {1};
        data.scanInfo.add(acScan);

        byte[] exotic = new JpegWriter(data).write();
        // the parser must recover exactly these reset points and zero runs
        JpegData reparsed = JpegParser.parse(exotic);
        assertArrayEquals(new int[] {3}, reparsed.scanInfo.get(1).resetPoints);
        assertArrayEquals(new int[] {5}, reparsed.scanInfo.get(1).extraZeroRunBlock);
        assertArrayEquals(new int[] {1}, reparsed.scanInfo.get(1).extraZeroRunCount);
        assertRoundTrip(exotic);
    }


    @Test
    void recompressionShrinksTheFile() throws IOException {
        byte[] jpeg = imageIoJpeg(testImage(600, 420, false), false, 0.85f);
        byte[] jxl = JpegRecompressor.encode(jpeg);
        assertTrue(jxl.length < jpeg.length,
                "jxl " + jxl.length + " vs jpeg " + jpeg.length);
    }

    // ------------------------------------------------------------- ffmpeg

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return out.contains("mjpeg");
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] ffmpegJpeg(int w, int h, String pixFmt, int quality) throws Exception {
        BufferedImage img = testImage(w, h, false);
        byte[] raw = new byte[w * h * 3];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                raw[i++] = (byte) (rgb >> 16);
                raw[i++] = (byte) (rgb >> 8);
                raw[i++] = (byte) rgb;
            }
        }
        Path rawFile = tempDir.resolve("src-" + pixFmt + w + "x" + h + ".raw");
        Path jpgFile = tempDir.resolve("src-" + pixFmt + w + "x" + h + ".jpg");
        Files.write(rawFile, raw);
        Process p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", w + "x" + h,
                "-i", rawFile.toString(), "-frames:v", "1",
                "-c:v", "mjpeg", "-pix_fmt", pixFmt, "-q:v", Integer.toString(quality),
                jpgFile.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("ffmpeg failed: " + log);
        }
        return Files.readAllBytes(jpgFile);
    }

    @Test
    void ffmpegSubsampledRoundTrips() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        for (String pixFmt : new String[] {"yuvj444p", "yuvj420p", "yuvj422p", "yuvj440p"}) {
            assertRoundTrip(ffmpegJpeg(600, 420, pixFmt, 5));
        }
    }

    @Test
    void ffmpegOptimalHuffmanRoundTrip() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        BufferedImage img = testImage(400, 300, false);
        byte[] raw = new byte[400 * 300 * 3];
        int i = 0;
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 400; x++) {
                int rgb = img.getRGB(x, y);
                raw[i++] = (byte) (rgb >> 16);
                raw[i++] = (byte) (rgb >> 8);
                raw[i++] = (byte) rgb;
            }
        }
        Path rawFile = tempDir.resolve("opt.raw");
        Path jpgFile = tempDir.resolve("opt.jpg");
        Files.write(rawFile, raw);
        Process p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "400x300",
                "-i", rawFile.toString(), "-frames:v", "1",
                "-c:v", "mjpeg", "-huffman", "optimal", "-q:v", "4",
                jpgFile.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0,
                "ffmpeg lacks -huffman optimal");
        assertRoundTrip(Files.readAllBytes(jpgFile));
    }
}
