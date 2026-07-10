package com.ebremer.jpegxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        byte[] cs = com.ebremer.jpegxl.encoder.JxlEncoder.encode(
                planes, w, h, 8, false, false, false);
        java.nio.file.Path file = dir.resolve("stream.jxl");
        java.nio.file.Files.write(file, com.ebremer.jpegxl.container.Container.wrap(cs));

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
            jxl = com.ebremer.jpegxl.encoder.JxlEncoder.encodeWithPreview(
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
