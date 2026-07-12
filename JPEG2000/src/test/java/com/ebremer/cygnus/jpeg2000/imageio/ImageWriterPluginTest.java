package com.ebremer.cygnus.jpeg2000.imageio;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageIO writer plugin tests: service discovery, lossless BufferedImage and
 * Raster round-trips through the Cygnus reader, JP2 and raw codestream
 * containers, tiling, source region/subsampling/band selection, and the
 * lossy path.
 */
class ImageWriterPluginTest {

    private static ImageWriter writer() {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg2000");
        assertTrue(it.hasNext(), "Cygnus writer not registered");
        ImageWriter w = it.next();
        assertTrue(w instanceof CygnusImageWriter);
        return w;
    }

    private static byte[] write(IIOImage image, ImageWriteParam param) throws Exception {
        ImageWriter w = writer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, image, param);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] write(BufferedImage img, ImageWriteParam param) throws Exception {
        return write(new IIOImage(img, null, null), param);
    }

    private static BufferedImage read(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        assertNotNull(img, "reader could not decode writer output");
        return img;
    }

    private static BufferedImage randomImage(int w, int h, int type, long seed) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random rnd = new Random(seed);
        WritableRaster r = img.getRaster();
        for (int b = 0; b < r.getNumBands(); b++) {
            int max = (1 << r.getSampleModel().getSampleSize(b)) - 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    r.setSample(x, y, b, rnd.nextInt(max + 1));
                }
            }
        }
        return img;
    }

    private static void assertSamplesEqual(BufferedImage expected, BufferedImage actual,
                                           String name) {
        assertEquals(expected.getWidth(), actual.getWidth(), name + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), name + ": height");
        Raster e = expected.getRaster();
        Raster a = actual.getRaster();
        assertEquals(e.getNumBands(), a.getNumBands(), name + ": bands");
        int w = expected.getWidth();
        int[] er = new int[w];
        int[] ar = new int[w];
        for (int b = 0; b < e.getNumBands(); b++) {
            for (int y = 0; y < expected.getHeight(); y++) {
                e.getSamples(0, y, w, 1, b, er);
                a.getSamples(0, y, w, 1, b, ar);
                assertArrayEquals(er, ar, name + ": band " + b + " row " + y);
            }
        }
    }

    // ---- service discovery ----

    @Test
    void writerIsDiscoverable() {
        assertTrue(ImageIO.getImageWritersByFormatName("jp2").hasNext());
        assertTrue(ImageIO.getImageWritersByFormatName("JPEG2000").hasNext());
        assertTrue(ImageIO.getImageWritersByMIMEType("image/jp2").hasNext());
        assertTrue(ImageIO.getImageWritersBySuffix("j2k").hasNext());
    }

    @Test
    void readerAndWriterArePaired() throws Exception {
        byte[] data = write(randomImage(8, 8, BufferedImage.TYPE_BYTE_GRAY, 1), null);
        try (MemoryCacheImageInputStream in =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            assertTrue(readers.hasNext());
            ImageReader reader = readers.next();
            ImageWriter paired = ImageIO.getImageWriter(reader);
            assertNotNull(paired);
            assertTrue(paired instanceof CygnusImageWriter);
        }
    }

    @Test
    void imageIoWriteConvenience() throws Exception {
        BufferedImage img = randomImage(31, 17, BufferedImage.TYPE_INT_RGB, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jp2", ImageIO.createImageOutputStream(out)));
        assertSamplesEqual(img, read(out.toByteArray()), "convenience");
    }

    // ---- lossless round-trips per image type ----

    @Test
    void byteGrayRoundTrip() throws Exception {
        BufferedImage img = randomImage(37, 23, BufferedImage.TYPE_BYTE_GRAY, 3);
        assertSamplesEqual(img, read(write(img, null)), "gray8");
    }

    @Test
    void ushortGrayRoundTrip() throws Exception {
        BufferedImage img = randomImage(29, 31, BufferedImage.TYPE_USHORT_GRAY, 4);
        BufferedImage back = read(write(img, null));
        assertEquals(DataBuffer.TYPE_USHORT, back.getRaster().getDataBuffer().getDataType(),
                "16-bit image must come back as 16-bit");
        assertSamplesEqual(img, back, "gray16");
    }

    @Test
    void intRgbRoundTrip() throws Exception {
        BufferedImage img = randomImage(64, 48, BufferedImage.TYPE_INT_RGB, 5);
        assertSamplesEqual(img, read(write(img, null)), "int-rgb");
    }

    @Test
    void threeByteBgrRoundTrip() throws Exception {
        BufferedImage img = randomImage(33, 29, BufferedImage.TYPE_3BYTE_BGR, 6);
        assertSamplesEqual(img, read(write(img, null)), "3byte-bgr");
    }

    @Test
    void intArgbRoundTrip() throws Exception {
        BufferedImage img = randomImage(25, 19, BufferedImage.TYPE_INT_ARGB, 7);
        BufferedImage back = read(write(img, null));
        assertTrue(back.getColorModel().hasAlpha(), "alpha channel lost");
        // band order differs (ARGB packs alpha first, reader emits RGBA);
        // compare via getRGB which normalizes
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y),
                        "argb pixel " + x + "," + y);
            }
        }
    }

    @Test
    void fourByteAbgrRoundTrip() throws Exception {
        BufferedImage img = randomImage(21, 27, BufferedImage.TYPE_4BYTE_ABGR, 8);
        BufferedImage back = read(write(img, null));
        assertTrue(back.getColorModel().hasAlpha());
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y),
                        "abgr pixel " + x + "," + y);
            }
        }
    }

    @Test
    void indexedRoundTrip() throws Exception {
        BufferedImage img = randomImage(40, 30, BufferedImage.TYPE_BYTE_INDEXED, 9);
        BufferedImage back = read(write(img, null));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y),
                        "indexed pixel " + x + "," + y);
            }
        }
    }

    @Test
    void byteBinaryBecomesGray() throws Exception {
        BufferedImage img = new BufferedImage(19, 13, BufferedImage.TYPE_BYTE_BINARY);
        Random rnd = new Random(10);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.getRaster().setSample(x, y, 0, rnd.nextInt(2));
            }
        }
        BufferedImage back = read(write(img, null));
        assertEquals(1, back.getRaster().getNumBands(), "expanded to single grey channel");
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y),
                        "binary pixel " + x + "," + y);
            }
        }
    }

    @Test
    void indexedWithAlphaRoundTrip() throws Exception {
        byte[] r = new byte[16];
        byte[] g = new byte[16];
        byte[] b = new byte[16];
        byte[] a = new byte[16];
        Random rnd = new Random(11);
        rnd.nextBytes(r);
        rnd.nextBytes(g);
        rnd.nextBytes(b);
        rnd.nextBytes(a);
        IndexColorModel icm = new IndexColorModel(8, 16, r, g, b, a);
        BufferedImage img = new BufferedImage(23, 17,
                BufferedImage.TYPE_BYTE_INDEXED, icm);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.getRaster().setSample(x, y, 0, rnd.nextInt(16));
            }
        }
        BufferedImage back = read(write(img, null));
        assertTrue(back.getColorModel().hasAlpha(), "palette alpha lost");
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                assertEquals(img.getRGB(x, y), back.getRGB(x, y),
                        "palette-alpha pixel " + x + "," + y);
            }
        }
    }

    // ---- containers ----

    @Test
    void jp2HeaderAndPatchedLength() throws Exception {
        byte[] data = write(randomImage(30, 20, BufferedImage.TYPE_INT_RGB, 12), null);
        // JP2 signature box
        assertEquals(0x0000000C, u32(data, 0));
        assertEquals(0x6A502020, u32(data, 4));
        // the jp2c box length must be patched to reach exactly the file end
        int pos = 12;
        boolean sawCodestream = false;
        while (pos + 8 <= data.length) {
            long lbox = u32(data, pos) & 0xFFFFFFFFL;
            int tbox = u32(data, pos + 4);
            if (tbox == 0x6A703263) {
                sawCodestream = true;
                assertEquals(data.length - pos, lbox, "jp2c length not patched");
            }
            assertTrue(lbox >= 8, "invalid box length");
            pos += (int) lbox;
        }
        assertEquals(data.length, pos, "boxes must tile the file exactly");
        assertTrue(sawCodestream);
    }

    @Test
    void rawCodestreamOutput() throws Exception {
        BufferedImage img = randomImage(24, 18, BufferedImage.TYPE_BYTE_GRAY, 13);
        ImageWriter w = writer();
        CygnusImageWriteParam p = (CygnusImageWriteParam) w.getDefaultWriteParam();
        p.setWriteCodeStreamOnly(true);
        byte[] data = write(img, p);
        assertEquals(0xFF, data[0] & 0xFF);
        assertEquals(0x4F, data[1] & 0xFF); // SOC: raw codestream, no boxes
        assertSamplesEqual(img, read(data), "raw codestream");
    }

    // ---- param handling ----

    @Test
    void explicitTiling() throws Exception {
        BufferedImage img = randomImage(70, 50, BufferedImage.TYPE_INT_RGB, 14);
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        p.setTiling(32, 16, 0, 0);
        byte[] data = write(img, p);
        assertSamplesEqual(img, read(data), "tiled");
        try (MemoryCacheImageInputStream in =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            assertTrue(reader.isImageTiled(0));
            assertEquals(32, reader.getTileWidth(0));
            assertEquals(16, reader.getTileHeight(0));
            reader.dispose();
        }
    }

    @Test
    void sourceRegionAndSubsampling() throws Exception {
        BufferedImage img = randomImage(60, 40, BufferedImage.TYPE_BYTE_GRAY, 15);
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setSourceRegion(new Rectangle(5, 7, 41, 26));
        p.setSourceSubsampling(3, 2, 1, 1);
        BufferedImage back = read(write(img, p));
        int dw = (41 - 1 + 3 - 1) / 3;
        int dh = (26 - 1 + 2 - 1) / 2;
        assertEquals(dw, back.getWidth());
        assertEquals(dh, back.getHeight());
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = 5 + 1 + x * 3;
                int sy = 7 + 1 + y * 2;
                assertEquals(img.getRaster().getSample(sx, sy, 0),
                        back.getRaster().getSample(x, y, 0),
                        "subsampled pixel " + x + "," + y);
            }
        }
    }

    @Test
    void sourceBandSelection() throws Exception {
        BufferedImage img = randomImage(20, 20, BufferedImage.TYPE_INT_RGB, 16);
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setSourceBands(new int[] {2}); // blue only
        BufferedImage back = read(write(img, p));
        assertEquals(1, back.getRaster().getNumBands());
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                assertEquals(img.getRaster().getSample(x, y, 2),
                        back.getRaster().getSample(x, y, 0),
                        "band-selected pixel " + x + "," + y);
            }
        }
    }

    @Test
    void writeRasterDirectly() throws Exception {
        WritableRaster raster = Raster.createInterleavedRaster(
                DataBuffer.TYPE_USHORT, 26, 14, 2, null);
        Random rnd = new Random(17);
        for (int b = 0; b < 2; b++) {
            for (int y = 0; y < 14; y++) {
                for (int x = 0; x < 26; x++) {
                    raster.setSample(x, y, b, rnd.nextInt(1 << 16));
                }
            }
        }
        ImageWriter w = writer();
        assertTrue(w.canWriteRasters());
        w.dispose();
        byte[] data = write(new IIOImage(raster, null, null), null);
        try (MemoryCacheImageInputStream in =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            Raster back = reader.readRaster(0, null);
            assertEquals(2, back.getNumBands());
            for (int b = 0; b < 2; b++) {
                for (int y = 0; y < 14; y++) {
                    for (int x = 0; x < 26; x++) {
                        assertEquals(raster.getSample(x, y, b), back.getSample(x, y, b),
                                "raster sample " + x + "," + y + " band " + b);
                    }
                }
            }
            reader.dispose();
        }
    }

    // ---- lossy ----

    @Test
    void lossyCompressionQuality() throws Exception {
        BufferedImage img = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 96; x++) {
                int r = (int) (127 + 120 * Math.sin(x * 0.08));
                int g = (int) (127 + 120 * Math.cos(y * 0.06));
                int b = (x + y) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionType(CygnusImageWriteParam.LOSSY);
        p.setCompressionQuality(0.8f);
        byte[] lossy = write(img, p);
        byte[] lossless = write(img, null);
        assertTrue(lossy.length < lossless.length,
                "lossy (" + lossy.length + ") not smaller than lossless ("
                        + lossless.length + ")");
        BufferedImage back = read(lossy);
        double mse = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 96; x++) {
                for (int b = 0; b < 3; b++) {
                    double d = img.getRaster().getSample(x, y, b)
                            - back.getRaster().getSample(x, y, b);
                    mse += d * d;
                }
            }
        }
        mse /= 96 * 64 * 3;
        double psnr = 10 * Math.log10(255.0 * 255.0 / mse);
        assertTrue(psnr > 38, "PSNR " + psnr + " dB too low at quality 0.8");
    }

    @Test
    void losslessIsDefault() throws Exception {
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        assertEquals(CygnusImageWriteParam.LOSSLESS, p.getCompressionType());
        w.dispose();
        // and metadata reports lossless
        byte[] data = write(randomImage(16, 16, BufferedImage.TYPE_BYTE_GRAY, 18), null);
        try (MemoryCacheImageInputStream in =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            var tree = (org.w3c.dom.Element) reader.getImageMetadata(0)
                    .getAsTree("javax_imageio_1.0");
            var comp = tree.getElementsByTagName("Lossless").item(0);
            assertEquals("TRUE", comp.getAttributes().getNamedItem("value").getNodeValue());
            reader.dispose();
        }
    }

    @Test
    void abortStopsWriting() throws Exception {
        BufferedImage img = randomImage(64, 64, BufferedImage.TYPE_BYTE_GRAY, 19);
        ImageWriter w = writer();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        p.setTiling(16, 16, 0, 0);
        final boolean[] aborted = {false};
        w.addIIOWriteProgressListener(new javax.imageio.event.IIOWriteProgressListener() {
            @Override
            public void imageStarted(ImageWriter source, int imageIndex) {
            }

            @Override
            public void imageProgress(ImageWriter source, float percentageDone) {
                if (percentageDone > 20) {
                    source.abort();
                }
            }

            @Override
            public void imageComplete(ImageWriter source) {
            }

            @Override
            public void thumbnailStarted(ImageWriter source, int imageIndex,
                                         int thumbnailIndex) {
            }

            @Override
            public void thumbnailProgress(ImageWriter source, float percentageDone) {
            }

            @Override
            public void thumbnailComplete(ImageWriter source) {
            }

            @Override
            public void writeAborted(ImageWriter source) {
                aborted[0] = true;
            }
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
        assertTrue(aborted[0], "abort was not signalled");
        byte[] full = write(img, p); // same settings, no abort
        assertTrue(bytes.toByteArray().length < full.length,
                "aborted output should stop short of a complete write");
    }

    private static int u32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }
}
