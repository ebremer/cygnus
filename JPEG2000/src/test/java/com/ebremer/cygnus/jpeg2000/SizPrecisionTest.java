package com.ebremer.cygnus.jpeg2000;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.jpeg2000.testutil.MiniJ2kEncoder;
import java.util.Random;
import javax.imageio.IIOException;
import org.junit.jupiter.api.Test;

/**
 * Component precision is capped where the decoder's integer pipeline actually
 * ends — 31 magnitude bit-planes — so a 38-bit extreme is rejected at SIZ, as
 * the README promises, rather than wrapping somewhere deep in dequantisation.
 */
class SizPrecisionTest {

    @Test
    void precisionBeyondTheSupportedRangeIsRejectedAtSiz() {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 48;
        cfg.height = 40;
        cfg.levels = 3;
        cfg.xcb = 4;
        cfg.ycb = 4;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = new int[1][cfg.width * cfg.height];
        Random rnd = new Random(5);
        for (int i = 0; i < comps[0].length; i++) {
            comps[0][i] = rnd.nextInt(256);
        }
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);
        int siz = indexOfSiz(j2k);
        j2k[siz + 40] = 37; // Ssiz: unsigned, 38 bits
        assertThrows(IIOException.class, () -> new Jpeg2000Decoder().decode(j2k));
    }

    private static int indexOfSiz(byte[] cs) {
        for (int i = 0; i + 1 < cs.length; i++) {
            if ((cs[i] & 0xFF) == 0xFF && (cs[i + 1] & 0xFF) == 0x51) {
                return i;
            }
        }
        throw new AssertionError("no SIZ in seed codestream");
    }
}
