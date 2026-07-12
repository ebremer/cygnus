package com.ebremer.cygnus.jpeg2000.imageio;

import com.ebremer.cygnus.jpeg2000.testutil.MiniJ2kEncoder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Image metadata, band selection and deep-sample raster reads. */
class MetadataTest {

    private static int[][] random(MiniJ2kEncoder.Config cfg, long seed) {
        Random rnd = new Random(seed);
        int[][] comps = new int[cfg.precision.length][];
        for (int c = 0; c < comps.length; c++) {
            int cw = (cfg.width + cfg.xr[c] - 1) / cfg.xr[c];
            int ch = (cfg.height + cfg.yr[c] - 1) / cfg.yr[c];
            comps[c] = new int[cw * ch];
            for (int i = 0; i < comps[c].length; i++) {
                comps[c][i] = rnd.nextInt(1 << cfg.precision[c]);
            }
        }
        return comps;
    }

    private static byte[] encodeRgb(int w, int h, int[][] comps) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.rct = true;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        return MiniJ2kEncoder.encode(comps, cfg);
    }

    /** Minimal JP2 wrapper with ihdr, colr and a capture-resolution box. */
    private static byte[] jp2WrapWithRes(byte[] cs, int w, int h, int nc,
                                         int hres, int vres) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, 12, 'j', 'P', ' ', ' ',
            0x0D, 0x0A, (byte) 0x87, 0x0A});
        box(out, new byte[] {'f', 't', 'y', 'p', 'j', 'p', '2', ' ', 0, 0, 0, 0,
            'j', 'p', '2', ' '});
        ByteArrayOutputStream jp2h = new ByteArrayOutputStream();
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(new byte[] {'i', 'h', 'd', 'r'});
        u32(ihdr, h);
        u32(ihdr, w);
        ihdr.write(nc >> 8);
        ihdr.write(nc & 0xFF);
        ihdr.writeBytes(new byte[] {7, 7, 0, 0});
        box(jp2h, ihdr.toByteArray());
        ByteArrayOutputStream colr = new ByteArrayOutputStream();
        colr.writeBytes(new byte[] {'c', 'o', 'l', 'r', 1, 0, 0});
        u32(colr, nc >= 3 ? 16 : 17);
        box(jp2h, colr.toByteArray());
        // res superbox with a resc child
        ByteArrayOutputStream resc = new ByteArrayOutputStream();
        resc.writeBytes(new byte[] {'r', 'e', 's', 'c'});
        u16(resc, vres);
        u16(resc, 1);
        u16(resc, hres);
        u16(resc, 1);
        resc.write(0);
        resc.write(0);
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        res.writeBytes(new byte[] {'r', 'e', 's', ' '});
        box(res, resc.toByteArray());
        box(jp2h, res.toByteArray());

        ByteArrayOutputStream jp2hBox = new ByteArrayOutputStream();
        jp2hBox.writeBytes(new byte[] {'j', 'p', '2', 'h'});
        jp2hBox.writeBytes(jp2h.toByteArray());
        box(out, jp2hBox.toByteArray());
        ByteArrayOutputStream jp2c = new ByteArrayOutputStream();
        jp2c.writeBytes(new byte[] {'j', 'p', '2', 'c'});
        jp2c.writeBytes(cs);
        box(out, jp2c.toByteArray());
        return out.toByteArray();
    }

    private static void box(ByteArrayOutputStream out, byte[] payload) {
        u32(out, payload.length + 4);
        out.writeBytes(payload);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static ImageReader open(byte[] data) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(data)));
        return reader;
    }

    private static String childValue(Node root, String section, String name) {
        NodeList secs = ((Element) root).getElementsByTagName(section);
        if (secs.getLength() == 0) {
            return null;
        }
        NodeList kids = ((Element) secs.item(0)).getElementsByTagName(name);
        if (kids.getLength() == 0) {
            return null;
        }
        Element e = (Element) kids.item(0);
        return e.hasAttribute("value") ? e.getAttribute("value") : e.getAttribute("name");
    }

    @Test
    void standardMetadataTree() throws Exception {
        int w = 24, h = 18;
        byte[] j2k = encodeRgb(w, h, random(cfgRgb(w, h), 1));
        ImageReader reader = open(j2k);
        IIOMetadata md = reader.getImageMetadata(0);
        assertNotNull(md);
        assertTrue(md.isReadOnly());
        assertTrue(md.isStandardMetadataFormatSupported());
        Node root = md.getAsTree("javax_imageio_1.0");
        assertEquals("RGB", childValue(root, "Chroma", "ColorSpaceType"));
        assertEquals("3", childValue(root, "Chroma", "NumChannels"));
        assertEquals("8 8 8", childValue(root, "Data", "BitsPerSample"));
        assertEquals("TRUE", childValue(root, "Compression", "Lossless"));
        assertEquals("JPEG2000", childValue(root, "Compression", "CompressionTypeName"));
        assertEquals("none", childValue(root, "Transparency", "Alpha"));
        assertThrows(IllegalStateException.class, () -> md.mergeTree("javax_imageio_1.0", root));
        reader.dispose();
    }

    private static MiniJ2kEncoder.Config cfgRgb(int w, int h) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.rct = true;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        return cfg;
    }

    @Test
    void nativeMetadataTree() throws Exception {
        int w = 24, h = 18;
        byte[] j2k = encodeRgb(w, h, random(cfgRgb(w, h), 2));
        ImageReader reader = open(j2k);
        IIOMetadata md = reader.getImageMetadata(0);
        Node root = md.getAsTree(CygnusMetadata.NATIVE_FORMAT);
        assertEquals(CygnusMetadata.NATIVE_FORMAT, root.getNodeName());
        Element siz = (Element) ((Element) root).getElementsByTagName("SIZ").item(0);
        assertEquals("24", siz.getAttribute("width"));
        assertEquals("18", siz.getAttribute("height"));
        assertEquals("3", siz.getAttribute("components"));
        Element cod = (Element) ((Element) root).getElementsByTagName("COD").item(0);
        assertEquals("5-3", cod.getAttribute("transform"));
        assertEquals("false", cod.getAttribute("htBlocks"));
        assertEquals("true", cod.getAttribute("multipleComponentTransform"));
        reader.dispose();
    }

    @Test
    void resolutionBoxFeedsDimensionNodes() throws Exception {
        int w = 20, h = 16;
        byte[] j2k = encodeRgb(w, h, random(cfgRgb(w, h), 3));
        byte[] jp2 = jp2WrapWithRes(j2k, w, h, 3, 2000, 4000);
        ImageReader reader = open(jp2);
        Node root = reader.getImageMetadata(0).getAsTree("javax_imageio_1.0");
        assertEquals("0.5", childValue(root, "Dimension", "HorizontalPixelSize"));
        assertEquals("0.25", childValue(root, "Dimension", "VerticalPixelSize"));
        assertEquals("2.0", childValue(root, "Dimension", "PixelAspectRatio"));
        reader.dispose();
    }

    @Test
    void sourceBandSelection() throws Exception {
        int w = 20, h = 14;
        int[][] comps = random(cfgRgb(w, h), 4);
        byte[] j2k = encodeRgb(w, h, comps);

        // single band: blue channel as gray
        ImageReader reader = open(j2k);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceBands(new int[] {2});
        BufferedImage img = reader.read(0, param);
        assertEquals(1, img.getRaster().getNumBands());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals(comps[2][y * w + x], img.getRaster().getSample(x, y, 0));
            }
        }

        // reordered bands
        param = reader.getDefaultReadParam();
        param.setSourceBands(new int[] {2, 1, 0});
        BufferedImage swapped = reader.read(0, param);
        assertEquals(3, swapped.getRaster().getNumBands());
        assertEquals(comps[2][0], swapped.getRaster().getSample(0, 0, 0));
        assertEquals(comps[0][0], swapped.getRaster().getSample(0, 0, 2));

        // invalid band index must be rejected
        ImageReadParam bad = reader.getDefaultReadParam();
        bad.setSourceBands(new int[] {7});
        assertThrows(IllegalArgumentException.class, () -> reader.read(0, bad));
        reader.dispose();
    }

    @Test
    void deepRasterUsesIntSamples() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 20;
        cfg.height = 16;
        cfg.levels = 2;
        cfg.precision = new int[] {18};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = random(cfg, 5);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        ImageReader reader = open(j2k);
        Raster raster = reader.readRaster(0, null);
        assertEquals(DataBuffer.TYPE_INT, raster.getDataBuffer().getDataType());
        for (int y = 0; y < cfg.height; y++) {
            for (int x = 0; x < cfg.width; x++) {
                assertEquals(comps[0][y * cfg.width + x], raster.getSample(x, y, 0),
                        "18-bit sample " + x + "," + y);
            }
        }
        reader.dispose();
    }
}
