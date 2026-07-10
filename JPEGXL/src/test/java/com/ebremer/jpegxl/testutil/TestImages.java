package com.ebremer.jpegxl.testutil;

import java.util.Random;

/** Deterministic sample-plane generators for tests. */
public final class TestImages {

    private TestImages() {
    }

    /** Mixed content: gradients, flat regions, and noise; exercises varied histograms. */
    public static int[][] mixed(int width, int height, int planes, int bits, long seed) {
        Random rnd = new Random(seed);
        int max = (1 << bits) - 1;
        int[][] out = new int[planes][width * height];
        for (int p = 0; p < planes; p++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int v;
                    if (y < height / 3) {
                        v = (x * max / Math.max(1, width - 1) + p * 17) & max;
                    } else if (y < 2 * height / 3) {
                        v = (p * 31 + 77) & max;
                    } else {
                        v = rnd.nextInt(max + 1);
                    }
                    out[p][y * width + x] = v;
                }
            }
        }
        return out;
    }

    /** A single flat colour; degenerate histograms. */
    public static int[][] flat(int width, int height, int planes, int value) {
        int[][] out = new int[planes][width * height];
        for (int p = 0; p < planes; p++) {
            java.util.Arrays.fill(out[p], value);
        }
        return out;
    }

    /** Pure noise. */
    public static int[][] noise(int width, int height, int planes, int bits, long seed) {
        Random rnd = new Random(seed);
        int[][] out = new int[planes][width * height];
        for (int p = 0; p < planes; p++) {
            for (int i = 0; i < out[p].length; i++) {
                out[p][i] = rnd.nextInt(1 << bits);
            }
        }
        return out;
    }
}
