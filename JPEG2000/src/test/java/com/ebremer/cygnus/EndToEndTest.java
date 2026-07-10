package com.ebremer.cygnus;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lossless round-trips: encode with the test-scope reversible encoder,
 * decode with Cygnus, compare exactly.
 */
class EndToEndTest {

    private static MiniJ2kEncoder.Config gray(int w, int h, int prec) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.precision = new int[] {prec};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        return cfg;
    }

    private static MiniJ2kEncoder.Config rgb(int w, int h) {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = w;
        cfg.height = h;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        return cfg;
    }

    private static int[][] randomComps(MiniJ2kEncoder.Config cfg, long seed) {
        Random rnd = new Random(seed);
        int nc = cfg.precision.length;
        int xsiz = cfg.xosiz + cfg.width;
        int ysiz = cfg.yosiz + cfg.height;
        int[][] comps = new int[nc][];
        for (int c = 0; c < nc; c++) {
            int cw = ceil(xsiz, cfg.xr[c]) - ceil(cfg.xosiz, cfg.xr[c]);
            int ch = ceil(ysiz, cfg.yr[c]) - ceil(cfg.yosiz, cfg.yr[c]);
            comps[c] = new int[cw * ch];
            int max = (1 << cfg.precision[c]) - 1;
            for (int i = 0; i < comps[c].length; i++) {
                comps[c][i] = rnd.nextInt(max + 1);
            }
        }
        return comps;
    }

    /** Smooth gradient data: exercises long run-length coding paths. */
    private static int[][] smoothComps(MiniJ2kEncoder.Config cfg) {
        int nc = cfg.precision.length;
        int xsiz = cfg.xosiz + cfg.width;
        int ysiz = cfg.yosiz + cfg.height;
        int[][] comps = new int[nc][];
        for (int c = 0; c < nc; c++) {
            int cw = ceil(xsiz, cfg.xr[c]) - ceil(cfg.xosiz, cfg.xr[c]);
            int ch = ceil(ysiz, cfg.yr[c]) - ceil(cfg.yosiz, cfg.yr[c]);
            comps[c] = new int[cw * ch];
            int max = (1 << cfg.precision[c]) - 1;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    comps[c][y * cw + x] = (x + 2 * y + 17 * c) * max / Math.max(1, cw + 2 * ch)
                            % (max + 1);
                }
            }
        }
        return comps;
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }

    private void roundTrip(MiniJ2kEncoder.Config cfg, int[][] comps, String name)
            throws Exception {
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(j2k);
        assertEquals(cfg.width, img.width, name + ": width");
        assertEquals(cfg.height, img.height, name + ": height");
        assertEquals(comps.length, img.numChannels, name + ": channels");
        for (int c = 0; c < comps.length; c++) {
            assertArrayEquals(comps[c], img.samples[c],
                    name + ": component " + c + " " + dec.warnings());
        }
    }

    @Test
    void singlePixel() throws Exception {
        MiniJ2kEncoder.Config cfg = gray(1, 1, 8);
        cfg.levels = 0;
        roundTrip(cfg, randomComps(cfg, 1), "1x1");
    }

    @Test
    void tinyImagesVariousLevels() throws Exception {
        for (int levels = 0; levels <= 3; levels++) {
            for (int[] wh : new int[][] {{3, 2}, {1, 7}, {5, 5}, {8, 8}}) {
                MiniJ2kEncoder.Config cfg = gray(wh[0], wh[1], 8);
                cfg.levels = levels;
                roundTrip(cfg, randomComps(cfg, levels * 100 + wh[0]),
                        wh[0] + "x" + wh[1] + " L" + levels);
            }
        }
    }

    @Test
    void oddSizesGray() throws Exception {
        for (int[] wh : new int[][] {{17, 13}, {64, 48}, {130, 67}}) {
            MiniJ2kEncoder.Config cfg = gray(wh[0], wh[1], 8);
            roundTrip(cfg, randomComps(cfg, wh[0]), wh[0] + "x" + wh[1]);
            roundTrip(cfg, smoothComps(cfg), wh[0] + "x" + wh[1] + " smooth");
        }
    }

    @Test
    void smallCodeBlocks() throws Exception {
        MiniJ2kEncoder.Config cfg = gray(70, 50, 8);
        cfg.xcb = 4;
        cfg.ycb = 4;
        roundTrip(cfg, randomComps(cfg, 5), "cb16");
        cfg = gray(70, 50, 8);
        cfg.xcb = 3;
        cfg.ycb = 5;
        roundTrip(cfg, randomComps(cfg, 6), "cb8x32");
    }

    @Test
    void rgbWithRct() throws Exception {
        MiniJ2kEncoder.Config cfg = rgb(33, 29);
        cfg.rct = true;
        cfg.levels = 2;
        roundTrip(cfg, randomComps(cfg, 7), "rgb rct");
    }

    @Test
    void tiledImage() throws Exception {
        MiniJ2kEncoder.Config cfg = rgb(64, 64);
        cfg.rct = true;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        roundTrip(cfg, randomComps(cfg, 8), "tiled rgb");

        cfg = gray(50, 40, 8);
        cfg.xtsiz = 16;
        cfg.ytsiz = 16;
        cfg.levels = 2;
        roundTrip(cfg, randomComps(cfg, 9), "tiled gray");
    }

    @Test
    void offsetGrids() throws Exception {
        // odd image origin: exercises DWT parity handling
        MiniJ2kEncoder.Config cfg = gray(21, 17, 8);
        cfg.xosiz = 3;
        cfg.yosiz = 1;
        roundTrip(cfg, randomComps(cfg, 10), "odd origin");

        // tile grid offset smaller than the image offset
        cfg = gray(40, 40, 8);
        cfg.xosiz = 7;
        cfg.yosiz = 3;
        cfg.xtosiz = 5;
        cfg.ytosiz = 2;
        cfg.xtsiz = 16;
        cfg.ytsiz = 16;
        cfg.levels = 2;
        roundTrip(cfg, randomComps(cfg, 11), "offset tiles");
    }

    @Test
    void subsampledComponents() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 30;
        cfg.height = 22;
        cfg.precision = new int[] {8, 8};
        cfg.xr = new int[] {1, 2};
        cfg.yr = new int[] {1, 2};
        cfg.levels = 2;
        roundTrip(cfg, randomComps(cfg, 12), "subsampled");
    }

    @Test
    void higherBitDepths() throws Exception {
        MiniJ2kEncoder.Config cfg = gray(25, 25, 12);
        cfg.levels = 2;
        roundTrip(cfg, randomComps(cfg, 13), "12-bit");
        cfg = gray(40, 30, 16);
        roundTrip(cfg, randomComps(cfg, 14), "16-bit");
    }

    @Test
    void sopAndEphMarkers() throws Exception {
        MiniJ2kEncoder.Config cfg = rgb(20, 20);
        cfg.rct = true;
        cfg.sopEph = true;
        roundTrip(cfg, randomComps(cfg, 15), "sop+eph");
    }

    @Test
    void truncatedStreamDecodesGracefully() throws Exception {
        MiniJ2kEncoder.Config cfg = gray(32, 32, 8);
        int[][] comps = randomComps(cfg, 16);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);
        byte[] cut = new byte[j2k.length * 2 / 3];
        System.arraycopy(j2k, 0, cut, 0, cut.length);
        DecodedImage img = new Jpeg2000Decoder().decode(cut);
        assertEquals(32, img.width);
        assertEquals(32, img.height);
        assertTrue(img.samples[0].length == 32 * 32);
    }
}
