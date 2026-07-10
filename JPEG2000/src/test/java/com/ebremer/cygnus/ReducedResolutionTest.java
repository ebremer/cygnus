package com.ebremer.cygnus;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.awt.Rectangle;
import java.util.Random;
import javax.imageio.IIOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reduced-resolution decoding: the reversible path must reproduce the
 * forward 5/3 wavelet lowpass exactly, regions must compose with reduction,
 * and invalid reductions must be rejected.
 */
class ReducedResolutionTest {

    // ---- forward 5/3 oracle (mirror of the codec, origin 0) ----

    private static int mirror(int j, int n) {
        if (n == 1) {
            return 0;
        }
        int p = 2 * (n - 1);
        j = Math.floorMod(j, p);
        return j < n ? j : p - j;
    }

    private static void fwd53(int[] t, int pos, int step, int n) {
        if (n <= 1) {
            return;
        }
        for (int j = 1; j < n; j += 2) {
            t[pos + j * step] -= (t[pos + mirror(j - 1, n) * step]
                    + t[pos + mirror(j + 1, n) * step]) >> 1;
        }
        for (int j = 0; j < n; j += 2) {
            t[pos + j * step] += (t[pos + mirror(j - 1, n) * step]
                    + t[pos + mirror(j + 1, n) * step] + 2) >> 2;
        }
    }

    /** d-level 5/3 lowpass of a (0,0)-origin plane; returns the LL plane. */
    private static int[][] lowpass(int[] samples, int w, int h, int d) {
        int[] cur = samples.clone();
        int cw = w, ch = h;
        for (int lev = 0; lev < d; lev++) {
            for (int x = 0; x < cw; x++) {
                fwd53(cur, x, cw, ch);
            }
            for (int y = 0; y < ch; y++) {
                fwd53(cur, y * cw, 1, cw);
            }
            int lw = (cw + 1) / 2;
            int lh = (ch + 1) / 2;
            int[] ll = new int[lw * lh];
            for (int v = 0; v < lh; v++) {
                for (int u = 0; u < lw; u++) {
                    ll[v * lw + u] = cur[(2 * v) * cw + (2 * u)];
                }
            }
            cur = ll;
            cw = lw;
            ch = lh;
        }
        return new int[][] {cur, {cw, ch}};
    }

    private static int[][] randomComps(MiniJ2kEncoder.Config cfg, long seed) {
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

    @Test
    void grayReducedDecodeEqualsWaveletLowpassExactly() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 37;                 // odd at every level
        cfg.height = 29;
        cfg.levels = 3;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = randomComps(cfg, 51);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        int[] shifted = new int[comps[0].length];
        for (int i = 0; i < shifted.length; i++) {
            shifted[i] = comps[0][i] - 128;
        }
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        assertEquals(3, dec.maxReduction());
        for (int d = 0; d <= 3; d++) {
            DecodedImage img = dec.decode(d);
            int[][] oracle = lowpass(shifted, cfg.width, cfg.height, d);
            int ow = oracle[1][0];
            int oh = oracle[1][1];
            assertEquals(ow, img.imageWidth, "d=" + d);
            assertEquals(oh, img.imageHeight, "d=" + d);
            assertEquals(d, img.reduction);
            int[] expect = new int[ow * oh];
            for (int i = 0; i < expect.length; i++) {
                expect[i] = Math.clamp(oracle[0][i] + 128, 0, 255);
            }
            assertArrayEquals(expect, img.samples[0], "reduction " + d);
        }
    }

    @Test
    void rgbRctReducedDecodeMatchesOracle() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 24;
        cfg.height = 18;
        cfg.levels = 2;
        cfg.rct = true;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        int[][] comps = randomComps(cfg, 52);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        // oracle: forward RCT, per-component lowpass, inverse RCT, shift, clamp
        int n = cfg.width * cfg.height;
        int[] y0 = new int[n], y1 = new int[n], y2 = new int[n];
        for (int i = 0; i < n; i++) {
            int r = comps[0][i] - 128;
            int g = comps[1][i] - 128;
            int b = comps[2][i] - 128;
            y0[i] = (r + 2 * g + b) >> 2;
            y1[i] = b - g;
            y2[i] = r - g;
        }
        int d = 2;
        int[][] l0 = lowpass(y0, cfg.width, cfg.height, d);
        int[][] l1 = lowpass(y1, cfg.width, cfg.height, d);
        int[][] l2 = lowpass(y2, cfg.width, cfg.height, d);
        int ow = l0[1][0], oh = l0[1][1];
        int[][] expect = new int[3][ow * oh];
        for (int i = 0; i < ow * oh; i++) {
            int g = l0[0][i] - ((l2[0][i] + l1[0][i]) >> 2);
            int r = l2[0][i] + g;
            int b = l1[0][i] + g;
            expect[0][i] = Math.clamp(r + 128, 0, 255);
            expect[1][i] = Math.clamp(g + 128, 0, 255);
            expect[2][i] = Math.clamp(b + 128, 0, 255);
        }
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        DecodedImage img = dec.decode(d);
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(expect[c], img.samples[c], "component " + c);
        }
    }

    @Test
    void regionComposesWithReduction() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 96;
        cfg.height = 80;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        cfg.levels = 3;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 53), cfg);

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        for (int d = 1; d <= 2; d++) {
            DecodedImage full = dec.decode(d);
            Random rnd = new Random(54 + d);
            for (int i = 0; i < 6; i++) {
                int x = rnd.nextInt(full.imageWidth - 2);
                int y = rnd.nextInt(full.imageHeight - 2);
                int w = 1 + rnd.nextInt(full.imageWidth - x);
                int h = 1 + rnd.nextInt(full.imageHeight - y);
                DecodedImage reg = dec.decode(new Rectangle(x, y, w, h), d);
                assertEquals(w, reg.width);
                assertEquals(h, reg.height);
                for (int c = 0; c < reg.numChannels; c++) {
                    int offX = reg.chanX0[c] - full.chanX0[c];
                    int offY = reg.chanY0[c] - full.chanY0[c];
                    for (int cy = 0; cy < reg.chanHeight[c]; cy++) {
                        for (int cx = 0; cx < reg.chanWidth[c]; cx++) {
                            assertEquals(
                                    full.samples[c][(cy + offY) * full.chanWidth[c] + (cx + offX)],
                                    reg.samples[c][cy * reg.chanWidth[c] + cx],
                                    "d=" + d + " region " + x + "," + y + " " + w + "x" + h);
                        }
                    }
                }
            }
        }
    }

    @Test
    void subsampledComponentsReducedDims() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 60;
        cfg.height = 44;
        cfg.levels = 2;
        cfg.precision = new int[] {8, 8};
        cfg.xr = new int[] {1, 2};
        cfg.yr = new int[] {1, 2};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 55), cfg);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        DecodedImage img = dec.decode(1);
        assertEquals(30, img.imageWidth);
        assertEquals(22, img.imageHeight);
        assertEquals(30, img.chanWidth[0]);   // ceil(60 / 2)
        assertEquals(15, img.chanWidth[1]);   // ceil(60 / (2*2))
        assertEquals(11, img.chanHeight[1]);  // ceil(44 / (2*2))
        assertEquals(2, img.dx[1]);           // subsampling on the reduced grid
    }

    @Test
    void invalidReductionsRejected() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 16;
        cfg.height = 16;
        cfg.levels = 2;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 56), cfg);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(j2k);
        assertEquals(2, dec.maxReduction());
        assertThrows(IIOException.class, () -> dec.decode(3));
        assertThrows(IllegalArgumentException.class, () -> dec.decode(-1));
    }
}
