package com.ebremer.cygnus.jpeg2000;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.jpeg2000.testutil.MiniJ2kEncoder;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.IIOException;
import org.junit.jupiter.api.Test;

/**
 * A POC marker's progression orders come from the file, so an undefined one is
 * bad input: it has to be rejected with an {@code IIOException} at parse time,
 * not escape as an {@code IllegalArgumentException} from deep inside packet
 * iteration. (COD progressions were already validated; POC was not.)
 */
class PocValidationTest {

    @Test
    void unknownPocProgressionIsRejectedAsBadInput() {
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

        // splice a POC into the main header: full component/resolution/layer
        // range, Ppoc = 5, which no part of the standard defines
        int sot = indexOfSot(j2k);
        byte[] poc = {(byte) 0xFF, 0x5F, 0x00, 0x09,
            0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x05};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j2k, 0, sot);
        out.writeBytes(poc);
        out.write(j2k, sot, j2k.length - sot);
        byte[] bad = out.toByteArray();

        assertThrows(IIOException.class, () -> new Jpeg2000Decoder().decode(bad));
    }

    private static int indexOfSot(byte[] cs) {
        for (int i = 2; i + 1 < cs.length; i++) {
            if ((cs[i] & 0xFF) == 0xFF && (cs[i + 1] & 0xFF) == 0x90) {
                return i;
            }
        }
        throw new AssertionError("no SOT in seed codestream");
    }
}
