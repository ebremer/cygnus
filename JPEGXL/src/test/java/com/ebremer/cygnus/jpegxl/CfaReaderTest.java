package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.color.CfaDemosaic;
import com.ebremer.cygnus.jpegxl.color.CfaDemosaic.Pattern;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The ImageIO reader demosaics a single-channel image tagged as a colour-filter
 * array: its grey plane is a Bayer mosaic, and it comes back as an RGB picture.
 */
class CfaReaderTest {

    private static int[][] scene(int w, int h) {
        int[][] rgb = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                rgb[0][i] = x * 255 / (w - 1);
                rgb[1][i] = y * 255 / (h - 1);
                rgb[2][i] = (x + y) * 255 / (w + h - 2);
            }
        }
        return rgb;
    }

    private static int[] mosaic(int[][] rgb, int w, int h, Pattern p) {
        int[] m = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = (y & 1) == p.redRow() && (x & 1) == p.redCol() ? 0
                        : (y & 1) == p.blueRow() && (x & 1) == p.blueCol() ? 2 : 1;
                m[i] = rgb[c][i];
            }
        }
        return m;
    }

    @Test
    void greyMosaicWithCfaTagDemosaicsToRgb() throws Exception {
        int w = 48;
        int h = 40;
        int[][] rgb = scene(w, h);
        int[] mos = mosaic(rgb, w, h, Pattern.RGGB);

        // a greyscale image whose plane is the mosaic, tagged by a CFA channel
        int[][] planes = {mos, new int[w * h]};
        List<ExtraChannelInfo> extras =
                List.of(ExtraChannelInfo.cfa(BitDepth.of(8), "cfa", 1));
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, true, extras);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jxl));
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType(), "CFA output is colour, not grey");

        // lossless mosaic + the same RGGB demosaic the reader ran: colours that
        // were actually measured come back exactly
        int[][] expect = CfaDemosaic.demosaic(mos, w, h, Pattern.RGGB);
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int i = y * w + x;
                assertEquals(expect[0][i], (argb >> 16) & 0xff, "R at " + x + "," + y);
                assertEquals(expect[1][i], (argb >> 8) & 0xff, "G at " + x + "," + y);
                assertEquals(expect[2][i], argb & 0xff, "B at " + x + "," + y);
                sum += Math.abs(((argb >> 16) & 0xff) - rgb[0][i]);
                sum += Math.abs(((argb >> 8) & 0xff) - rgb[1][i]);
                sum += Math.abs((argb & 0xff) - rgb[2][i]);
            }
        }
        // and the demosaiced picture is close to the scene the mosaic came from
        assertTrue(sum / (3.0 * w * h) < 2.0, "demosaiced picture far from the scene");
    }

    @Test
    void skipFlagLeavesTheRawMosaic() throws Exception {
        int w = 32;
        int h = 32;
        int[] mos = mosaic(scene(w, h), w, h, Pattern.RGGB);
        int[][] planes = {mos, new int[w * h]};
        byte[] jxl = JxlEncoder.encode(planes, w, h, 8, true,
                List.of(ExtraChannelInfo.cfa(BitDepth.of(8), "cfa", 1)));

        System.setProperty("jxl.skipCfa", "true");
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jxl));
            // the raw mosaic shows as a grey image, no demosaic
            assertTrue(img.getType() == BufferedImage.TYPE_BYTE_GRAY
                    || img.getRaster().getNumBands() == 1, "skipCfa should leave it grey");
        } finally {
            System.clearProperty("jxl.skipCfa");
        }
    }
}
