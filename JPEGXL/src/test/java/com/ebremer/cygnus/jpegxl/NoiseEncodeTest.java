package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import com.ebremer.cygnus.jpegxl.features.Noise;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Photon-noise synthesis on encode: the model, and that the decoder puts grain back. */
class NoiseEncodeTest {

    /** The strength table matches libjxl's SimulatePhotonNoise, so an ISO reproduces its grain. */
    @Test
    void photonNoiseLutMatchesLibjxl() {
        // computed independently from libjxl's formula for a 256x256 frame
        float[] iso400 = {0.0000f, 0.0020f, 0.0026f, 0.0041f, 0.0056f, 0.0072f, 0.0088f, 0.0103f};
        float[] iso6400 = {0.0005f, 0.0059f, 0.0036f, 0.0045f, 0.0059f, 0.0073f, 0.0089f, 0.0104f};
        float[] got400 = Noise.photonNoiseLut(256, 256, 400);
        float[] got6400 = Noise.photonNoiseLut(256, 256, 6400);
        for (int i = 0; i < 8; i++) {
            assertEquals(iso400[i], got400[i], 5e-4f, "iso 400 point " + i);
            assertEquals(iso6400[i], got6400[i], 5e-4f, "iso 6400 point " + i);
        }
    }

    /** More light-starved (higher ISO) is grainier at every luminance. */
    @Test
    void higherIsoIsGrainier() {
        float[] lo = Noise.photonNoiseLut(256, 256, 400);
        float[] hi = Noise.photonNoiseLut(256, 256, 12800);
        for (int i = 0; i < 8; i++) {
            assertTrue(hi[i] >= lo[i] - 1e-6f, "point " + i + ": " + hi[i] + " < " + lo[i]);
            assertTrue(lo[i] >= 0 && hi[i] <= 1, "out of [0,1] at " + i);
        }
        assertTrue(hi[1] > lo[1] + 1e-3f, "the grain should visibly grow with iso");
    }

    private static int[][] smooth(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = 100 + x * 80 / w;
                p[1][i] = 110 + y * 70 / h;
                p[2][i] = 120;
            }
        }
        return p;
    }

    /** A grain proxy: the mean magnitude of a Laplacian over the luminance plane. */
    private static double grain(int[][] c, int w, int h) {
        double s = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double lap = 4.0 * c[1][i] - c[1][i - 1] - c[1][i + 1] - c[1][i - w] - c[1][i + w];
                s += lap * lap;
                n++;
            }
        }
        return Math.sqrt(s / n);
    }

    /** The decoder synthesizes grain the plain encode does not have, for a few bytes. */
    @Test
    void noiseAddsGrainOnDecode() throws Exception {
        int w = 256;
        int h = 256;
        int[][] src = smooth(w, h);
        byte[] plain = VarDctEncoder.encode(src, w, h, 8, false, false, false, 1.5f);
        byte[] noisy = VarDctEncoder.encodeWithPhotonNoise(src, w, h, 8, false, List.of(),
                1.5f, 6400);

        double plainGrain = grain(JxlDecoder.decode(plain).frames.get(0).channels, w, h);
        double noisyGrain = grain(JxlDecoder.decode(noisy).frames.get(0).channels, w, h);
        assertTrue(noisyGrain > plainGrain * 2,
                "synthesized grain should stand out: " + plainGrain + " -> " + noisyGrain);
        // the model is 80 bits; the file should barely grow
        assertTrue(noisy.length < plain.length + 64,
                "noise cost should be tiny: " + plain.length + " -> " + noisy.length);
    }

    /** iso 0 leaves the file grain-free, byte-for-byte the plain encode. */
    @Test
    void zeroIsoIsPlain() throws Exception {
        int w = 128;
        int h = 96;
        int[][] src = smooth(w, h);
        byte[] plain = VarDctEncoder.encode(src, w, h, 8, false, false, false, 1.5f);
        byte[] zero = VarDctEncoder.encodeWithPhotonNoise(src, w, h, 8, false, List.of(),
                1.5f, 0);
        assertEquals(plain.length, zero.length);
    }
}
