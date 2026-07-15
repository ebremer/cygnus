package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import com.ebremer.cygnus.jpegxl.codestream.ExtraChannelInfo;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lossy XYB modular encode: colour turned to quantised XYB, coded modular. */
class XybModularRoundTripTest {

    private static int[][] gradient(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = (x * 255) / (w - 1);                 // red ramp
                p[1][i] = (y * 255) / (h - 1);                 // green ramp
                p[2][i] = ((x + y) * 255) / (w + h - 2);       // blue diagonal
            }
        }
        return p;
    }

    private static double meanAbs(int[][] a, int[][] b, int w, int h) {
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(a[c][i] - b[c][i]);
            }
        }
        return sum / (3.0 * w * h);
    }

    @Test
    void roundTripIsCloseAndXyb() throws Exception {
        int w = 64;
        int h = 48;
        int[][] src = gradient(w, h);
        byte[] jxl = JxlEncoder.encodeXyb(src, w, h, 8, 1.0f);

        JxlImage img = JxlDecoder.decode(jxl);
        assertEquals(w, img.width);
        assertEquals(h, img.height);
        assertTrue(img.metadata.xybEncoded, "file should declare XYB encoding");
        assertEquals(3, img.frames.get(0).channels.length);

        double mean = meanAbs(src, img.frames.get(0).channels, w, h);
        // lossy, but a smooth gradient at distance 1.0 stays visually exact
        assertTrue(mean < 2.0, "mean abs error too high: " + mean);
    }

    @Test
    void coarserDistanceStillDecodes() throws Exception {
        int w = 40;
        int h = 40;
        int[][] src = gradient(w, h);
        byte[] fine = JxlEncoder.encodeXyb(src, w, h, 8, 1.0f);
        byte[] coarse = JxlEncoder.encodeXyb(src, w, h, 8, 8.0f);

        JxlImage a = JxlDecoder.decode(fine);
        JxlImage b = JxlDecoder.decode(coarse);
        double meanFine = meanAbs(src, a.frames.get(0).channels, w, h);
        double meanCoarse = meanAbs(src, b.frames.get(0).channels, w, h);

        // a coarser step loses more but still round-trips to a sane image
        assertTrue(meanCoarse >= meanFine, "coarser should not be more faithful");
        assertTrue(coarse.length <= fine.length, "coarser should not be larger");
        assertTrue(meanCoarse < 30, "coarse decode wildly off: " + meanCoarse);
    }

    @Test
    void alphaRidesBesideTheXybColourLosslessly() throws Exception {
        int w = 48;
        int h = 40;
        int[][] src = gradient(w, h);
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = (x * 255) / (w - 1);   // opaque ramp
            }
        }
        int[][] planes = {src[0], src[1], src[2], alpha};
        List<ExtraChannelInfo> extras = List.of(ExtraChannelInfo.alpha(BitDepth.of(8), false));
        byte[] jxl = JxlEncoder.encodeXyb(planes, w, h, 8, 1.0f, extras);

        JxlImage img = JxlDecoder.decode(jxl);
        assertTrue(img.metadata.xybEncoded);
        int[][] out = img.frames.get(0).channels;
        assertEquals(4, out.length, "colour + alpha");
        // alpha is coded losslessly beside the quantised colour
        for (int i = 0; i < w * h; i++) {
            assertEquals(alpha[i], out[3][i], "alpha must be exact at pixel " + i);
        }
    }
}
