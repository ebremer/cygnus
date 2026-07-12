package com.ebremer.cygnus.jpeg2000.encoder;

import com.ebremer.cygnus.jpeg2000.decoder.DecodedImage;
import com.ebremer.cygnus.jpeg2000.decoder.Jpeg2000Decoder;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips of the production encoder against the Cygnus decoder:
 * reversible streams must reproduce the input exactly; irreversible
 * streams must stay within a quality-dependent error bound.
 */
class Jpeg2000EncoderTest {

    private static Jpeg2000Encoder.Params params(int w, int h, int... precs) {
        Jpeg2000Encoder.Params p = new Jpeg2000Encoder.Params();
        p.width = w;
        p.height = h;
        p.precision = precs.clone();
        return p;
    }

    private static int[][] randomComps(Jpeg2000Encoder.Params p, long seed) {
        Random rnd = new Random(seed);
        int nc = p.precision.length;
        int[][] comps = new int[nc][];
        for (int c = 0; c < nc; c++) {
            int xr = p.xr != null ? p.xr[c] : 1;
            int yr = p.yr != null ? p.yr[c] : 1;
            int cw = (p.width + xr - 1) / xr;
            int ch = (p.height + yr - 1) / yr;
            comps[c] = new int[cw * ch];
            int max = (1 << p.precision[c]) - 1;
            for (int i = 0; i < comps[c].length; i++) {
                comps[c][i] = rnd.nextInt(max + 1);
            }
        }
        return comps;
    }

    private static int[][] smoothComps(Jpeg2000Encoder.Params p) {
        int nc = p.precision.length;
        int[][] comps = new int[nc][];
        for (int c = 0; c < nc; c++) {
            int cw = p.width;
            int ch = p.height;
            comps[c] = new int[cw * ch];
            int max = (1 << p.precision[c]) - 1;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    double v = 0.5 + 0.45 * Math.sin(x * 0.07 + c) * Math.cos(y * 0.05);
                    comps[c][y * cw + x] = (int) Math.round(v * max);
                }
            }
        }
        return comps;
    }

    private static byte[] encode(int[][] comps, Jpeg2000Encoder.Params p) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(new Jpeg2000Encoder(p).encode(comps, out));
        return out.toByteArray();
    }

    private void losslessRoundTrip(Jpeg2000Encoder.Params p, int[][] comps, String name)
            throws Exception {
        byte[] j2k = encode(comps, p);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(j2k);
        assertEquals(p.width, img.width, name + ": width");
        assertEquals(p.height, img.height, name + ": height");
        assertEquals(comps.length, img.numChannels, name + ": channels");
        for (int c = 0; c < comps.length; c++) {
            assertArrayEquals(comps[c], img.samples[c],
                    name + ": component " + c + " " + dec.warnings());
        }
    }

    private static double psnr(int[] a, int[] b, int maxValue) {
        assertEquals(a.length, b.length);
        double mse = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            mse += d * d;
        }
        mse /= a.length;
        return mse == 0 ? Double.POSITIVE_INFINITY
                : 10 * Math.log10((double) maxValue * maxValue / mse);
    }

    @Test
    void losslessGray() throws Exception {
        for (int[] wh : new int[][] {{1, 1}, {3, 2}, {17, 13}, {64, 48}, {130, 67}}) {
            Jpeg2000Encoder.Params p = params(wh[0], wh[1], 8);
            losslessRoundTrip(p, randomComps(p, wh[0] * 31L + wh[1]),
                    wh[0] + "x" + wh[1]);
        }
    }

    @Test
    void losslessRgbWithRct() throws Exception {
        Jpeg2000Encoder.Params p = params(33, 29, 8, 8, 8);
        losslessRoundTrip(p, randomComps(p, 7), "rgb");
    }

    @Test
    void losslessTiled() throws Exception {
        Jpeg2000Encoder.Params p = params(70, 50, 8, 8, 8);
        p.tileWidth = 32;
        p.tileHeight = 24;
        losslessRoundTrip(p, randomComps(p, 8), "tiled rgb");
    }

    @Test
    void losslessSubsampledNoMct() throws Exception {
        Jpeg2000Encoder.Params p = params(30, 22, 8, 8, 8);
        p.xr = new int[] {1, 2, 2};
        p.yr = new int[] {1, 2, 2};
        losslessRoundTrip(p, randomComps(p, 9), "yuv420-style");
    }

    @Test
    void losslessSixteenBit() throws Exception {
        Jpeg2000Encoder.Params p = params(40, 30, 16);
        losslessRoundTrip(p, randomComps(p, 10), "16-bit");
    }

    @Test
    void losslessSopEph() throws Exception {
        Jpeg2000Encoder.Params p = params(20, 20, 8, 8, 8);
        p.sop = true;
        p.eph = true;
        losslessRoundTrip(p, randomComps(p, 11), "sop+eph");
    }

    @Test
    void losslessSmallCodeBlocksManyLevels() throws Exception {
        Jpeg2000Encoder.Params p = params(70, 50, 8);
        p.xcb = 3;
        p.ycb = 5;
        p.levels = 4;
        losslessRoundTrip(p, randomComps(p, 12), "cb8x32");
    }

    @Test
    void lossyGrayQuality() throws Exception {
        Jpeg2000Encoder.Params p = params(120, 84, 8);
        p.reversible = false;
        p.quality = 0.9f;
        int[][] comps = smoothComps(p);
        byte[] j2k = encode(comps, p);
        DecodedImage img = new Jpeg2000Decoder().decode(j2k);
        double db = psnr(comps[0], img.samples[0], 255);
        assertTrue(db > 45, "PSNR " + db + " dB too low at quality 0.9");
    }

    @Test
    void lossyRgbIct() throws Exception {
        Jpeg2000Encoder.Params p = params(64, 48, 8, 8, 8);
        p.reversible = false;
        p.quality = 0.85f;
        int[][] comps = smoothComps(p);
        byte[] j2k = encode(comps, p);
        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        DecodedImage img = dec.decode(j2k);
        for (int c = 0; c < 3; c++) {
            double db = psnr(comps[c], img.samples[c], 255);
            assertTrue(db > 40, "component " + c + " PSNR " + db + " dB "
                    + dec.warnings());
        }
    }

    @Test
    void lossyQualityMonotonicity() throws Exception {
        Jpeg2000Encoder.Params p = params(128, 96, 8);
        int[][] comps = smoothComps(p);
        double lastPsnr = 0;
        long lastSize = 0;
        for (float q : new float[] {0.2f, 0.6f, 1.0f}) {
            p.reversible = false;
            p.quality = q;
            byte[] j2k = encode(comps, p);
            DecodedImage img = new Jpeg2000Decoder().decode(j2k);
            double db = psnr(comps[0], img.samples[0], 255);
            assertTrue(db > lastPsnr, "PSNR not increasing at q=" + q
                    + " (" + db + " <= " + lastPsnr + ")");
            assertTrue(j2k.length > lastSize, "size not increasing at q=" + q);
            lastPsnr = db;
            lastSize = j2k.length;
        }
        assertTrue(lastPsnr > 50, "quality 1.0 PSNR " + lastPsnr);
    }

    @Test
    void lossy16BitStaysAccurate() throws Exception {
        Jpeg2000Encoder.Params p = params(60, 44, 16);
        p.reversible = false;
        p.quality = 1.0f;
        int[][] comps = smoothComps(p);
        byte[] j2k = encode(comps, p);
        DecodedImage img = new Jpeg2000Decoder().decode(j2k);
        double db = psnr(comps[0], img.samples[0], 65535);
        assertTrue(db > 60, "16-bit PSNR " + db);
    }

    @Test
    void multiPrecinctWideImage() throws Exception {
        // 40000 wide at one decomposition level: the full resolution spans
        // two 32768-wide precincts, so packets per precinct must interleave
        Jpeg2000Encoder.Params p = params(40000, 8, 8);
        p.levels = 1;
        losslessRoundTrip(p, randomComps(p, 13), "wide");
    }

    @Test
    void parallelismDoesNotChangeOutput() throws Exception {
        Jpeg2000Encoder.Params p = params(90, 70, 8, 8, 8);
        int[][] comps = randomComps(p, 14);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Jpeg2000Encoder e1 = new Jpeg2000Encoder(p);
        e1.setParallelism(1);
        e1.encode(comps, a);
        Jpeg2000Encoder e8 = new Jpeg2000Encoder(p);
        e8.setParallelism(8);
        e8.encode(comps, b);
        assertArrayEquals(a.toByteArray(), b.toByteArray());
    }

    @Test
    void forwardDwtInvertsInverse97() throws Exception {
        // 9/7 analysis followed by synthesis must reproduce the signal
        Jpeg2000Encoder.Params p = params(37, 23, 8);
        p.reversible = false;
        p.quality = 1.0f;
        int[][] comps = smoothComps(p);
        byte[] j2k = encode(comps, p);
        DecodedImage img = new Jpeg2000Decoder().decode(j2k);
        // near-lossless: quantization only, every sample within a few counts
        int worst = 0;
        for (int i = 0; i < comps[0].length; i++) {
            worst = Math.max(worst, Math.abs(comps[0][i] - img.samples[0][i]));
        }
        assertTrue(worst <= 2, "max sample error " + worst);
    }
}
