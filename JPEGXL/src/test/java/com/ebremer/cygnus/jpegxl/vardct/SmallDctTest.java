package com.ebremer.cygnus.jpegxl.vardct;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The DCT2 and DCT4 forward transforms are exact inverses of the decoder's
 * {@link Transforms} paths: a block forward-transformed then inverted comes back.
 */
class SmallDctTest {

    private static double roundTrip(TransformType tt, float[] block) {
        float[] coeffs = new float[64];
        float[] s0 = new float[256];
        float[] s1 = new float[256];
        switch (tt) {
            case DCT2 -> SmallDct.forwardDct2(block, coeffs);
            case DCT4 -> SmallDct.forwardDct4(block, coeffs, s0, s1);
            case DCT4_8 -> SmallDct.forwardDct4x8(block, coeffs, s0, s1);
            case DCT8_4 -> SmallDct.forwardDct8x4(block, coeffs, s0, s1);
            default -> throw new IllegalArgumentException();
        }
        float[] out = new float[64];
        float[] s2 = new float[64];
        float[] s3 = new float[64];
        Transforms.invert(tt, coeffs, 8, 0, 0, out, 0, 8, s0, s1, s2, s3);
        double worst = 0;
        for (int i = 0; i < 64; i++) {
            worst = Math.max(worst, Math.abs(out[i] - block[i]));
        }
        return worst;
    }

    @Test
    void dct2InvertsExactly() {
        Random rnd = new Random(7);
        for (TransformType tt : new TransformType[] {TransformType.DCT2, TransformType.DCT4,
                TransformType.DCT4_8, TransformType.DCT8_4}) {
            for (int trial = 0; trial < 200; trial++) {
                float[] block = new float[64];
                for (int i = 0; i < 64; i++) {
                    block[i] = rnd.nextFloat() * 512 - 256;
                }
                double worst = roundTrip(tt, block);
                assertTrue(worst < 1e-2, tt + " round trip worst " + worst);
            }
        }
    }

    @Test
    void constantBlockGivesOneCoefficient() {
        // a flat block is pure DC: forward leaves only coefficient 0
        float[] block = new float[64];
        java.util.Arrays.fill(block, 100f);
        float[] coeffs = new float[64];
        SmallDct.forwardDct2(block, coeffs);
        assertTrue(Math.abs(coeffs[0] - 100f) < 1e-3, "DC should be the mean, got " + coeffs[0]);
        for (int i = 1; i < 64; i++) {
            assertTrue(Math.abs(coeffs[i]) < 1e-3, "AC " + i + " should vanish, got " + coeffs[i]);
        }
    }
}
