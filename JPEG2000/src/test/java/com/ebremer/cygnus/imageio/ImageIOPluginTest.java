package com.ebremer.cygnus.imageio;

import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageIOPluginTest {

    private static byte[] encodeGray(int w, int h, int[] samples) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        return MiniJ2kEncoder.encode(new int[][] {samples}, cfg);
    }

    private static byte[] encodeRgb(int w, int h, int[][] comps) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        cfg.rct = true;
        return MiniJ2kEncoder.encode(comps, cfg);
    }

    private static int[][] random(int n, int len, long seed) {
        Random rnd = new Random(seed);
        int[][] out = new int[n][len];
        for (int[] a : out) {
            for (int i = 0; i < len; i++) {
                a[i] = rnd.nextInt(256);
            }
        }
        return out;
    }

    /** Wraps a codestream in a minimal JP2 container. */
    private static byte[] jp2Wrap(byte[] cs, int w, int h, int nc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // signature box
        out.writeBytes(new byte[] {0, 0, 0, 12, 'j', 'P', ' ', ' ',
            0x0D, 0x0A, (byte) 0x87, 0x0A});
        // ftyp box
        byte[] ftyp = {'f', 't', 'y', 'p', 'j', 'p', '2', ' ', 0, 0, 0, 0,
            'j', 'p', '2', ' '};
        writeBox(out, ftyp);
        // jp2h superbox with ihdr + colr
        ByteArrayOutputStream jp2h = new ByteArrayOutputStream();
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(new byte[] {'i', 'h', 'd', 'r'});
        u32(ihdr, h);
        u32(ihdr, w);
        ihdr.write(nc >> 8);
        ihdr.write(nc & 0xFF);
        ihdr.write(7);   // bpc: 8-bit unsigned
        ihdr.write(7);   // compression type
        ihdr.write(0);   // colourspace known
        ihdr.write(0);   // no IPR
        writeBox(jp2h, ihdr.toByteArray());
        ByteArrayOutputStream colr = new ByteArrayOutputStream();
        colr.writeBytes(new byte[] {'c', 'o', 'l', 'r', 1, 0, 0});
        u32(colr, nc >= 3 ? 16 : 17); // sRGB or greyscale
        writeBox(jp2h, colr.toByteArray());
        ByteArrayOutputStream jp2hBox = new ByteArrayOutputStream();
        jp2hBox.writeBytes(new byte[] {'j', 'p', '2', 'h'});
        jp2hBox.writeBytes(jp2h.toByteArray());
        writeBox(out, jp2hBox.toByteArray());
        // jp2c
        ByteArrayOutputStream jp2c = new ByteArrayOutputStream();
        jp2c.writeBytes(new byte[] {'j', 'p', '2', 'c'});
        jp2c.writeBytes(cs);
        writeBox(out, jp2c.toByteArray());
        return out.toByteArray();
    }

    private static void writeBox(ByteArrayOutputStream out, byte[] typeAndPayload) {
        u32(out, typeAndPayload.length + 4);
        out.writeBytes(typeAndPayload);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    @Test
    void spiIsDiscovered() {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("jpeg2000");
        assertTrue(it.hasNext(), "reader registered under 'jpeg2000'");
        assertTrue(it.next() instanceof CygnusImageReader);
        assertTrue(ImageIO.getImageReadersByMIMEType("image/jp2").hasNext());
        assertTrue(ImageIO.getImageReadersBySuffix("jp2").hasNext());
    }

    @Test
    void imageIoReadsRawCodestream() throws Exception {
        int w = 31, h = 23;
        int[][] comps = random(1, w * h, 21);
        byte[] j2k = encodeGray(w, h, comps[0]);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(j2k));
        assertNotNull(img, "ImageIO.read found and used the plugin");
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        Raster r = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals(comps[0][y * w + x], r.getSample(x, y, 0),
                        "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void imageIoReadsJp2Container() throws Exception {
        int w = 40, h = 25;
        int[][] comps = random(3, w * h, 22);
        byte[] jp2 = jp2Wrap(encodeRgb(w, h, comps), w, h, 3);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jp2));
        assertNotNull(img);
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        Raster r = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(comps[c][y * w + x], r.getSample(x, y, c),
                            "pixel " + x + "," + y + " band " + c);
                }
            }
        }
    }

    @Test
    void sizeQueriesAndRegionReads() throws Exception {
        int w = 48, h = 32;
        int[][] comps = random(1, w * h, 23);
        byte[] j2k = encodeGray(w, h, comps[0]);
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k)));
        assertEquals(1, reader.getNumImages(true));
        assertEquals(w, reader.getWidth(0));
        assertEquals(h, reader.getHeight(0));

        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new java.awt.Rectangle(8, 4, 20, 16));
        param.setSourceSubsampling(2, 2, 0, 0);
        BufferedImage img = reader.read(0, param);
        assertEquals(10, img.getWidth());
        assertEquals(8, img.getHeight());
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 10; x++) {
                assertEquals(comps[0][(4 + 2 * y) * w + (8 + 2 * x)],
                        img.getRaster().getSample(x, y, 0));
            }
        }
        reader.dispose();
    }

    @Test
    void tileApiAndSelectiveRegionReads() throws Exception {
        int w = 96, h = 80;
        int[][] comps = random(1, w * h, 25);
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k)));
        assertTrue(reader.isImageTiled(0));
        assertEquals(32, reader.getTileWidth(0));
        assertEquals(32, reader.getTileHeight(0));
        assertEquals(0, reader.getTileGridXOffset(0));
        assertEquals(0, reader.getTileGridYOffset(0));

        // readTile: stitch every tile and compare each pixel to the source
        for (int ty = 0; ty < 3; ty++) {
            for (int tx = 0; tx < 3; tx++) {
                BufferedImage tile = reader.readTile(0, tx, ty);
                int tw = Math.min(32, w - tx * 32);
                int th = Math.min(32, h - ty * 32);
                assertEquals(tw, tile.getWidth());
                assertEquals(th, tile.getHeight());
                for (int y = 0; y < th; y++) {
                    for (int x = 0; x < tw; x++) {
                        assertEquals(comps[0][(ty * 32 + y) * w + (tx * 32 + x)],
                                tile.getRaster().getSample(x, y, 0),
                                "tile " + tx + "," + ty + " pixel " + x + "," + y);
                    }
                }
            }
        }

        // region read crossing tile boundaries
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new java.awt.Rectangle(20, 25, 40, 30));
        BufferedImage crop = reader.read(0, param);
        assertEquals(40, crop.getWidth());
        assertEquals(30, crop.getHeight());
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                assertEquals(comps[0][(25 + y) * w + (20 + x)],
                        crop.getRaster().getSample(x, y, 0));
            }
        }
        assertTrue(reader.readTile(0, 0, 0) != null);
        reader.dispose();
    }

    @Test
    void resolutionReductionReadParam() throws Exception {
        int w = 65, h = 49;
        int[][] comps = random(1, w * h, 26);
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.levels = 3;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        // reference: direct decoder API at reduction 1
        com.ebremer.cygnus.decoder.Jpeg2000Decoder dec =
                new com.ebremer.cygnus.decoder.Jpeg2000Decoder();
        dec.open(j2k);
        com.ebremer.cygnus.decoder.DecodedImage expect = dec.decode(1);

        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k)));
        ImageReadParam param = reader.getDefaultReadParam();
        assertTrue(param instanceof CygnusImageReadParam);
        ((CygnusImageReadParam) param).setResolutionReduction(1);
        BufferedImage img = reader.read(0, param);
        assertEquals(33, img.getWidth());   // ceil(65/2)
        assertEquals(25, img.getHeight());  // ceil(49/2)
        for (int y = 0; y < 25; y++) {
            for (int x = 0; x < 33; x++) {
                assertEquals(expect.samples[0][y * 33 + x],
                        img.getRaster().getSample(x, y, 0), "pixel " + x + "," + y);
            }
        }

        // reduced read with a source region, in reduced coordinates
        param = reader.getDefaultReadParam();
        ((CygnusImageReadParam) param).setResolutionReduction(1);
        param.setSourceRegion(new java.awt.Rectangle(5, 7, 12, 10));
        BufferedImage crop = reader.read(0, param);
        assertEquals(12, crop.getWidth());
        assertEquals(10, crop.getHeight());
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 12; x++) {
                assertEquals(expect.samples[0][(7 + y) * 33 + (5 + x)],
                        crop.getRaster().getSample(x, y, 0));
            }
        }
        reader.dispose();
    }

    @Test
    void readRasterReturnsAllChannels() throws Exception {
        int w = 16, h = 12;
        int[][] comps = random(3, w * h, 24);
        byte[] j2k = encodeRgb(w, h, comps);
        ImageReader reader = ImageIO.getImageReadersByFormatName("jp2").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k)));
        assertTrue(reader.canReadRaster());
        Raster raster = reader.readRaster(0, null);
        assertEquals(3, raster.getNumBands());
        for (int c = 0; c < 3; c++) {
            assertEquals(comps[c][0], raster.getSample(0, 0, c));
        }
        reader.dispose();
    }
}
