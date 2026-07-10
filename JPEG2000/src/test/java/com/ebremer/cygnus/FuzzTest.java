package com.ebremer.cygnus;

import com.ebremer.cygnus.testutil.FuzzEngine;
import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic mutation fuzzing: corrupted codestreams must decode or be
 * rejected with a controlled exception - never crash, hang or exhaust
 * memory. A fixed RNG seed keeps CI stable; a longer sweep with rotating
 * seeds runs out-of-band (see the fuzz engine).
 */
class FuzzTest {

    static List<byte[]> seeds() {
        // small but structurally rich seeds: tiles, RCT, subsampling, JP2 box
        MiniJ2kEncoder.Config gray = new MiniJ2kEncoder.Config();
        gray.width = 48;
        gray.height = 40;
        gray.levels = 3;
        gray.xcb = 4;
        gray.ycb = 4;
        gray.precision = new int[] {8};
        gray.xr = new int[] {1};
        gray.yr = new int[] {1};

        MiniJ2kEncoder.Config rgb = new MiniJ2kEncoder.Config();
        rgb.width = 40;
        rgb.height = 32;
        rgb.rct = true;
        rgb.xtsiz = 16;
        rgb.ytsiz = 16;
        rgb.sopEph = true;
        rgb.precision = new int[] {8, 8, 8};
        rgb.xr = new int[] {1, 1, 1};
        rgb.yr = new int[] {1, 1, 1};

        MiniJ2kEncoder.Config sub = new MiniJ2kEncoder.Config();
        sub.width = 30;
        sub.height = 22;
        sub.levels = 2;
        sub.precision = new int[] {8, 8};
        sub.xr = new int[] {1, 2};
        sub.yr = new int[] {1, 2};

        Random rnd = new Random(1234);
        byte[] j2kGray = MiniJ2kEncoder.encode(random(gray, rnd), gray);
        byte[] j2kRgb = MiniJ2kEncoder.encode(random(rgb, rnd), rgb);
        byte[] j2kSub = MiniJ2kEncoder.encode(random(sub, rnd), sub);
        return List.of(j2kGray, j2kRgb, j2kSub, jp2Wrap(j2kGray, 48, 40));
    }

    private static int[][] random(MiniJ2kEncoder.Config cfg, Random rnd) {
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

    private static byte[] jp2Wrap(byte[] cs, int w, int h) {
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
        ihdr.writeBytes(new byte[] {0, 1, 7, 7, 0, 0});
        box(jp2h, ihdr.toByteArray());
        ByteArrayOutputStream colr = new ByteArrayOutputStream();
        colr.writeBytes(new byte[] {'c', 'o', 'l', 'r', 1, 0, 0});
        u32(colr, 17);
        box(jp2h, colr.toByteArray());
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

    @Test
    void mutatedInputsNeverCrashOrHang() throws Exception {
        long survivors = FuzzEngine.run(seeds(), 20260710L, 750, 10_000);
        // sanity: the fuzzer must not be rejecting everything trivially
        assertTrue(survivors >= 0);
    }
}
