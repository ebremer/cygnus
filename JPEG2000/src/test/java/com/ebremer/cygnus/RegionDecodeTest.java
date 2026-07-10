package com.ebremer.cygnus;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.awt.Rectangle;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selective (tile-limited) region decoding: region results must equal the
 * corresponding window of a full decode, and only intersecting tiles may be
 * decoded.
 */
class RegionDecodeTest {

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
            for (int i = 0; i < comps[c].length; i++) {
                comps[c][i] = rnd.nextInt(1 << cfg.precision[c]);
            }
        }
        return comps;
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Region planes must match the full-decode planes over the same window. */
    private static void assertRegionMatchesFull(byte[] j2k, Rectangle region)
            throws Exception {
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        DecodedImage full = dec.decode();
        DecodedImage reg = dec.decode(region);
        assertEquals(region.width, reg.width);
        assertEquals(region.height, reg.height);
        assertEquals(full.numChannels, reg.numChannels);
        for (int c = 0; c < reg.numChannels; c++) {
            int offX = reg.chanX0[c] - full.chanX0[c];
            int offY = reg.chanY0[c] - full.chanY0[c];
            assertTrue(offX >= 0 && offY >= 0, "plane offsets inside full plane");
            for (int y = 0; y < reg.chanHeight[c]; y++) {
                for (int x = 0; x < reg.chanWidth[c]; x++) {
                    assertEquals(
                            full.samples[c][(y + offY) * full.chanWidth[c] + (x + offX)],
                            reg.samples[c][y * reg.chanWidth[c] + x],
                            "channel " + c + " at " + x + "," + y + " region " + region);
                }
            }
        }
    }

    @Test
    void regionsEqualFullCropAcrossConfigs() throws Exception {
        // tiled gray
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 96;
        cfg.height = 80;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 1), cfg);
        Random rnd = new Random(99);
        for (int i = 0; i < 12; i++) {
            int x = rnd.nextInt(90);
            int y = rnd.nextInt(74);
            int w = 1 + rnd.nextInt(96 - x);
            int h = 1 + rnd.nextInt(80 - y);
            assertRegionMatchesFull(j2k, new Rectangle(x, y, w, h));
        }

        // tiled RGB with RCT, odd image origin, offset tile grid
        cfg = new MiniJ2kEncoder.Config();
        cfg.width = 61;
        cfg.height = 47;
        cfg.xosiz = 3;
        cfg.yosiz = 5;
        cfg.xtosiz = 1;
        cfg.ytosiz = 2;
        cfg.xtsiz = 24;
        cfg.ytsiz = 16;
        cfg.rct = true;
        cfg.levels = 2;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        j2k = MiniJ2kEncoder.encode(randomComps(cfg, 2), cfg);
        assertRegionMatchesFull(j2k, new Rectangle(0, 0, 10, 10));
        assertRegionMatchesFull(j2k, new Rectangle(25, 17, 30, 20));
        assertRegionMatchesFull(j2k, new Rectangle(60, 46, 1, 1));

        // subsampled components
        cfg = new MiniJ2kEncoder.Config();
        cfg.width = 60;
        cfg.height = 44;
        cfg.xtsiz = 20;
        cfg.ytsiz = 20;
        cfg.levels = 2;
        cfg.precision = new int[] {8, 8};
        cfg.xr = new int[] {1, 2};
        cfg.yr = new int[] {1, 2};
        j2k = MiniJ2kEncoder.encode(randomComps(cfg, 3), cfg);
        assertRegionMatchesFull(j2k, new Rectangle(11, 7, 23, 19));
        assertRegionMatchesFull(j2k, new Rectangle(39, 21, 21, 23));
    }

    @Test
    void onlyIntersectingTilesAreDecoded() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 96;
        cfg.height = 96;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 4), cfg);

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        AtomicInteger tileTotal = new AtomicInteger(-1);
        dec.setProgressListener((done, total) -> {
            tileTotal.set(total);
            return true;
        });
        dec.open(j2k);

        dec.decode(new Rectangle(40, 40, 10, 10));   // interior of center tile
        assertEquals(1, tileTotal.get(), "single-tile region decodes one tile");

        dec.decode(new Rectangle(30, 40, 10, 10));   // spans two tiles horizontally
        assertEquals(2, tileTotal.get());

        dec.decode(new Rectangle(30, 30, 40, 40));   // 3x3 tile neighbourhood
        assertEquals(9, tileTotal.get());

        dec.decode(new Rectangle(0, 0, 96, 96));
        assertEquals(9, tileTotal.get(), "full image decodes all tiles");
    }

    @Test
    void shapeAnswersWithoutDecoding() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 96;
        cfg.height = 64;
        cfg.xtsiz = 32;
        cfg.ytsiz = 16;
        cfg.precision = new int[] {12};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 5), cfg);

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        AtomicInteger decodedTiles = new AtomicInteger();
        dec.setProgressListener((done, total) -> {
            decodedTiles.incrementAndGet();
            return true;
        });
        dec.open(j2k);
        DecodedImage s = dec.shape();
        assertEquals(0, decodedTiles.get(), "shape() must not decode tiles");
        assertEquals(96, s.imageWidth);
        assertEquals(64, s.imageHeight);
        assertEquals(1, s.numChannels);
        assertEquals(12, s.depth[0]);
        assertEquals(32, s.tileWidth);
        assertEquals(16, s.tileHeight);
        assertEquals(3, s.numXTiles);
        assertEquals(4, s.numYTiles);
        assertEquals(null, s.samples[0]);
    }

    @Test
    void emptyRegionIsRejected() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 16;
        cfg.height = 16;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 6), cfg);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        assertThrows(javax.imageio.IIOException.class,
                () -> dec.decode(new Rectangle(20, 20, 4, 4)));
    }
}
