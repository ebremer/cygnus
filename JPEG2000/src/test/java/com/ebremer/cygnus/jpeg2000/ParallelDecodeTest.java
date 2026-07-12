package com.ebremer.cygnus.jpeg2000;

import com.ebremer.cygnus.jpeg2000.decoder.DecodedImage;
import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.jpeg2000.testutil.MiniJ2kEncoder;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parallel decoding must produce output identical to sequential decoding,
 * and independent decoder instances must be safe to use concurrently.
 */
class ParallelDecodeTest {

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

    private static void assertSame(DecodedImage a, DecodedImage b, String label) {
        for (int c = 0; c < a.numChannels; c++) {
            assertArrayEquals(a.samples[c], b.samples[c], label + " channel " + c);
        }
    }

    @Test
    void parallelEqualsSequential() throws Exception {
        List<MiniJ2kEncoder.Config> cfgs = new ArrayList<>();

        MiniJ2kEncoder.Config gray = new MiniJ2kEncoder.Config();
        gray.width = 200;
        gray.height = 160;
        gray.levels = 4;
        gray.xcb = 4;
        gray.ycb = 4; // many small code-blocks
        gray.precision = new int[] {8};
        gray.xr = new int[] {1};
        gray.yr = new int[] {1};
        cfgs.add(gray);

        MiniJ2kEncoder.Config rgb = new MiniJ2kEncoder.Config();
        rgb.width = 128;
        rgb.height = 96;
        rgb.rct = true;
        rgb.xtsiz = 48;
        rgb.ytsiz = 48;
        rgb.precision = new int[] {8, 8, 8};
        rgb.xr = new int[] {1, 1, 1};
        rgb.yr = new int[] {1, 1, 1};
        cfgs.add(rgb);

        MiniJ2kEncoder.Config sub = new MiniJ2kEncoder.Config();
        sub.width = 90;
        sub.height = 70;
        sub.levels = 2;
        sub.precision = new int[] {8, 8};
        sub.xr = new int[] {1, 2};
        sub.yr = new int[] {1, 2};
        cfgs.add(sub);

        long seed = 71;
        for (MiniJ2kEncoder.Config cfg : cfgs) {
            byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, seed++), cfg);

            Jpeg2000Decoder seq = new Jpeg2000Decoder();
            seq.setParallelism(1);
            seq.open(j2k);
            Jpeg2000Decoder par = new Jpeg2000Decoder();
            par.setParallelism(8);
            par.open(j2k);

            assertSame(seq.decode(), par.decode(), "full");
            Rectangle region = new Rectangle(11, 9, cfg.width / 2, cfg.height / 2);
            assertSame(seq.decode(region), par.decode(region), "region");
            if (seq.maxReduction() >= 1) {
                assertSame(seq.decode(1), par.decode(1), "reduced");
            }
        }
    }

    @Test
    void independentDecodersRunConcurrently() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 120;
        cfg.height = 100;
        cfg.levels = 3;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = randomComps(cfg, 99);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<int[]>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit((Callable<int[]>) () ->
                        new Jpeg2000Decoder().decode(j2k).samples[0]));
            }
            for (Future<int[]> f : futures) {
                assertArrayEquals(comps[0], f.get(), "concurrent decode");
            }
        } finally {
            pool.shutdown();
        }
        assertTrue(true);
    }
}
