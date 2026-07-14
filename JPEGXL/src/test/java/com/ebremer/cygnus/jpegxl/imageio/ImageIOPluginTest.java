package com.ebremer.cygnus.jpegxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

/** End-to-end tests of the ImageIO service providers. */
class ImageIOPluginTest {

    @Test
    void readerIsRegistered() {
        assertTrue(ImageIO.getImageReadersByFormatName("jxl").hasNext());
        assertTrue(ImageIO.getImageReadersByFormatName("JPEG XL").hasNext());
        assertTrue(ImageIO.getImageReadersBySuffix("jxl").hasNext());
        assertTrue(ImageIO.getImageReadersByMIMEType("image/jxl").hasNext());
        assertTrue(ImageIO.getImageWritersByFormatName("jxl").hasNext());
    }

    @Test
    void readsContainerFromFileStream(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws IOException {
        int w = 260;
        int h = 200;
        int[][] planes = new int[3][w * h];
        Random rnd = new Random(4);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < planes[c].length; i++) {
                planes[c][i] = rnd.nextInt(256);
            }
        }
        byte[] cs = com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.encode(
                planes, w, h, 8, false, false, false);
        java.nio.file.Path file = dir.resolve("stream.jxl");
        java.nio.file.Files.write(file, com.ebremer.cygnus.jpegxl.container.Container.wrap(cs));

        // File input goes through the seekable streaming source
        BufferedImage img = ImageIO.read(file.toFile());
        assertNotNull(img);
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                assertEquals(planes[0][y * w + x], (rgb >> 16) & 255);
                assertEquals(planes[1][y * w + x], (rgb >> 8) & 255);
                assertEquals(planes[2][y * w + x], rgb & 255);
            }
        }
    }

    @Test
    void previewBecomesThumbnail() throws IOException {
        int[][] main = new int[3][64 * 48];
        int[][] preview = new int[3][16 * 12];
        Random rnd = new Random(9);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < main[c].length; i++) {
                main[c][i] = rnd.nextInt(256);
            }
            for (int i = 0; i < preview[c].length; i++) {
                preview[c][i] = rnd.nextInt(256);
            }
        }
        byte[] jxl;
        try {
            jxl = com.ebremer.cygnus.jpegxl.encoder.JxlEncoder.encodeWithPreview(
                    main, 64, 48, 8, false, false, false, preview, 16, 12);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jxl))) {
            reader.setInput(in);
            assertTrue(reader.readerSupportsThumbnails());
            assertEquals(1, reader.getNumThumbnails(0));
            assertEquals(16, reader.getThumbnailWidth(0, 0));
            assertEquals(12, reader.getThumbnailHeight(0, 0));
            BufferedImage thumb = reader.readThumbnail(0, 0);
            assertEquals(16, thumb.getWidth());
            assertEquals(12, thumb.getHeight());
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 16; x++) {
                    int rgb = thumb.getRGB(x, y);
                    assertEquals(preview[0][y * 16 + x], (rgb >> 16) & 255, "R " + x + "," + y);
                    assertEquals(preview[1][y * 16 + x], (rgb >> 8) & 255, "G " + x + "," + y);
                    assertEquals(preview[2][y * 16 + x], rgb & 255, "B " + x + "," + y);
                }
            }
        } finally {
            reader.dispose();
        }
    }

    /**
     * A float raster in and a float raster out. The reader has always handed
     * float images back on a TYPE_FLOAT component raster; the writer now takes
     * one, so the round trip closes and a float image can go through plain
     * {@code ImageIO.write} / {@code ImageIO.read} without ever being quantised.
     */
    @Test
    void floatRasterRoundTripsBitExactly() throws IOException {
        for (boolean alpha : new boolean[] {false, true}) {
            BufferedImage img = floatImage(211, 137, alpha);
            BufferedImage back = writeAndRead(img);
            assertEquals(java.awt.image.DataBuffer.TYPE_FLOAT,
                    back.getRaster().getDataBuffer().getDataType(),
                    "a float image should come back as floats");
            int bands = img.getRaster().getNumBands();
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    for (int b = 0; b < bands; b++) {
                        assertEquals(
                                Float.floatToRawIntBits(img.getRaster().getSampleFloat(x, y, b)),
                                Float.floatToRawIntBits(back.getRaster().getSampleFloat(x, y, b)),
                                "alpha=" + alpha + " sample " + x + "," + y + " band " + b);
                    }
                }
            }
        }
    }

    /** And lossily, where the point is that nothing is quantised onto an integer grid first. */
    @Test
    void floatRasterWritesLossily() throws IOException {
        BufferedImage img = floatImage(200, 150, false);
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("lossy");
        param.setCompressionQuality(0.9f);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        }
        writer.dispose();
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(java.awt.image.DataBuffer.TYPE_FLOAT,
                back.getRaster().getDataBuffer().getDataType());
        double error = 0;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                for (int b = 0; b < 3; b++) {
                    error += Math.abs(img.getRaster().getSampleFloat(x, y, b)
                            - back.getRaster().getSampleFloat(x, y, b));
                    n++;
                }
            }
        }
        assertTrue(error / n < 0.1, "mean error " + (error / n) + " on samples running to +-2");
    }

    /**
     * The progressive knob is the standard one. A prefix of the bytes it produces
     * is the whole picture at low resolution, where a prefix of an ordinary file
     * is part of the picture and a lot of black.
     */
    @Test
    void progressiveModeWritesAResponsiveFile() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_INT_RGB, 600, 400, false);
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        }
        writer.dispose();
        byte[] jxl = bos.toByteArray();

        BufferedImage back = ImageIO.read(new ByteArrayInputStream(jxl));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y) | 0xff000000, back.getRGB(x, y),
                        "progressive must still be lossless: pixel " + x + "," + y);
            }
        }

        int[][] coarse = com.ebremer.cygnus.jpegxl.decoder.JxlDecoder
                .decodePartial(java.util.Arrays.copyOf(jxl, jxl.length / 5))
                .frames.get(0).channels;
        int covered = 0;
        for (int i = 0; i < 600 * 400; i++) {
            if (coarse[0][i] != 0 || coarse[1][i] != 0 || coarse[2][i] != 0) {
                covered++;
            }
        }
        assertEquals(600 * 400, covered,
                "a fifth of a progressive file should already cover the whole image");
    }

    /** A float raster and squeeze do not go together, and it says so. */
    @Test
    void progressiveRefusesFloatSamples() {
        BufferedImage img = floatImage(64, 64, false);
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        assertThrows(UnsupportedOperationException.class, () -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (javax.imageio.stream.ImageOutputStream ios =
                    ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            }
        });
    }

    /** A TYPE_FLOAT component raster, the shape the reader hands back. */
    private static BufferedImage floatImage(int w, int h, boolean alpha) {
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                alpha, false,
                alpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE,
                java.awt.image.DataBuffer.TYPE_FLOAT);
        java.awt.image.WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
        Random r = new Random(3);
        int bands = raster.getNumBands();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < bands; b++) {
                    raster.setSample(x, y, b, (float) (Math.sin(x * 0.05 + b)
                            * Math.cos(y * 0.04) * 2 + 0.001 * r.nextGaussian()));
                }
            }
        }
        return new BufferedImage(cm, raster, false, null);
    }

    @Test
    void rgbRoundTrip() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_INT_RGB, 211, 137, false);
        BufferedImage back = writeAndRead(img);
        assertEquals(img.getWidth(), back.getWidth());
        assertEquals(img.getHeight(), back.getHeight());
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y) | 0xff000000, back.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void argbRoundTrip() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_INT_ARGB, 90, 260, true);
        BufferedImage back = writeAndRead(img);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void greyRoundTrip() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_BYTE_GRAY, 300, 300, false);
        BufferedImage back = writeAndRead(img);
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, back.getType());
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRaster().getSample(x, y, 0),
                        back.getRaster().getSample(x, y, 0));
            }
        }
    }

    @Test
    void ushortGreyRoundTrip() throws IOException {
        BufferedImage img = new BufferedImage(64, 80, BufferedImage.TYPE_USHORT_GRAY);
        Random rnd = new Random(11);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.getRaster().setSample(x, y, 0, rnd.nextInt(65536));
            }
        }
        BufferedImage back = writeAndRead(img);
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, back.getType());
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRaster().getSample(x, y, 0),
                        back.getRaster().getSample(x, y, 0));
            }
        }
    }

    @Test
    void dimensionsWithoutFullDecode() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_INT_RGB, 123, 45, false);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jxl", baos));
        try (ImageInputStream in = ImageIO.createImageInputStream(
                new ByteArrayInputStream(baos.toByteArray()))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            assertEquals(123, reader.getWidth(0));
            assertEquals(45, reader.getHeight(0));
            reader.dispose();
        }
    }

    @Test
    void standardMetadata() throws IOException {
        BufferedImage img = testImage(BufferedImage.TYPE_INT_ARGB, 40, 30, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jxl", baos));
        try (ImageInputStream in = ImageIO.createImageInputStream(
                new ByteArrayInputStream(baos.toByteArray()))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            IIOMetadata meta = reader.getImageMetadata(0);
            assertNotNull(meta);
            Node tree = meta.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
            assertNotNull(tree);
            String xml = toXml(tree);
            assertTrue(xml.contains("BitsPerSample"), xml);
            assertTrue(xml.contains("Alpha"), xml);
            reader.dispose();
        }
    }

    private static String toXml(Node node) {
        StringBuilder sb = new StringBuilder();
        dump(node, sb);
        return sb.toString();
    }

    private static void dump(Node node, StringBuilder sb) {
        sb.append('<').append(node.getNodeName());
        if (node.getAttributes() != null) {
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                Node attr = node.getAttributes().item(i);
                sb.append(' ').append(attr.getNodeName()).append("='")
                        .append(attr.getNodeValue()).append('\'');
            }
        }
        sb.append('>');
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            dump(c, sb);
        }
        sb.append("</").append(node.getNodeName()).append('>');
    }

    private static BufferedImage testImage(int type, int w, int h, boolean varyAlpha) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random rnd = new Random(31);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255 / Math.max(1, w - 1));
                int g = (y * 255 / Math.max(1, h - 1));
                int b = rnd.nextInt(256);
                int a = varyAlpha ? (x + y) % 256 : 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static BufferedImage writeAndRead(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jxl", baos), "no jxl writer found");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
        assertNotNull(back, "no jxl reader found");
        return back;
    }
}
