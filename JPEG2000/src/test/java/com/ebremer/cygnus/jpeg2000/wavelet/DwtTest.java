package com.ebremer.cygnus.jpeg2000.wavelet;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DwtTest {

    /** Forward 1-D 5/3 (mirror of InverseDWT.inv53). */
    private static void fwd53(int[] t, int n, int i0) {
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[0] <<= 1;
            }
            return;
        }
        for (int i = i0 + 1 - (i0 & 1); i < i0 + n; i += 2) {
            int j = i - i0;
            t[j] -= (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)]) >> 1;
        }
        for (int i = i0 + (i0 & 1); i < i0 + n; i += 2) {
            int j = i - i0;
            t[j] += (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)] + 2) >> 2;
        }
    }

    @Test
    void reversible53IdentityAllLengthsAndParities() {
        Random rnd = new Random(3);
        for (int n = 1; n <= 33; n++) {
            for (int i0 = 0; i0 <= 1; i0++) {
                int[] orig = new int[n];
                for (int i = 0; i < n; i++) {
                    orig[i] = rnd.nextInt(512) - 256;
                }
                int[] t = orig.clone();
                fwd53(t, n, i0);
                InverseDWT.inv53(t, 0, 1, n, i0);
                assertArrayEquals(orig, t, "n=" + n + " i0=" + i0);
            }
        }
    }

    /** Forward 1-D 9/7 (analysis: mirror of InverseDWT.inv97). */
    private static void fwd97(float[] t, int n, int i0) {
        if (n == 1) {
            if ((i0 & 1) == 1) {
                t[0] *= 2.0f;
            }
            return;
        }
        float alpha = -1.586134342059924f;
        float beta = -0.052980118572961f;
        float gamma = 0.882911075530934f;
        float delta = 0.443506852043971f;
        float k = 1.230174104914001f;
        int even0 = i0 + (i0 & 1) - i0;
        int odd0 = i0 + 1 - (i0 & 1) - i0;
        for (int j = odd0; j < n; j += 2) {
            t[j] += alpha * (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)]);
        }
        for (int j = even0; j < n; j += 2) {
            t[j] += beta * (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)]);
        }
        for (int j = odd0; j < n; j += 2) {
            t[j] += gamma * (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)]);
        }
        for (int j = even0; j < n; j += 2) {
            t[j] += delta * (t[InverseDWT.mirror(j - 1, n)] + t[InverseDWT.mirror(j + 1, n)]);
        }
        for (int j = even0; j < n; j += 2) {
            t[j] /= k;
        }
        for (int j = odd0; j < n; j += 2) {
            t[j] *= k;
        }
    }

    @Test
    void irreversible97RoundTripWithinTolerance() {
        Random rnd = new Random(4);
        for (int n = 2; n <= 40; n++) {
            for (int i0 = 0; i0 <= 1; i0++) {
                float[] orig = new float[n];
                for (int i = 0; i < n; i++) {
                    orig[i] = rnd.nextFloat() * 200 - 100;
                }
                float[] t = orig.clone();
                fwd97(t, n, i0);
                InverseDWT.inv97(t, 0, 1, n, i0);
                for (int i = 0; i < n; i++) {
                    assertEquals(orig[i], t[i], 1e-3, "n=" + n + " i0=" + i0 + " at " + i);
                }
            }
        }
    }

    @Test
    void irreversible97ConstantSignalHasDcGainOne() {
        // analysis of a constant image yields lowpass == constant, highpass == 0;
        // synthesis must reproduce the constant
        int n = 16;
        float[] t = new float[n];
        for (int j = 0; j < n; j += 2) {
            t[j] = 42.0f;   // lowpass samples at even positions
        }
        InverseDWT.inv97(t, 0, 1, n, 0);
        for (int i = 0; i < n; i++) {
            assertEquals(42.0f, t[i], 1e-4, "position " + i);
        }
    }
}
