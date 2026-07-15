package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.encoder.VarDctEncoder;
import org.junit.jupiter.api.Test;

/**
 * Progressive (multi-pass) lossy VarDCT. The AC coefficients are split across
 * passes that sum back on decode, so a full decode is bit-for-bit the single-pass
 * image while an early-terminated one is a coarser valid picture.
 */
class ProgressiveVarDctTest {

    private static int[][] photo(int w, int h) {
        int[][] p = new int[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                p[0][i] = (int) (120 + 80 * Math.sin(x * 0.04) * Math.cos(y * 0.03));
                p[1][i] = (int) (110 + 70 * Math.sin((x + y) * 0.02));
                p[2][i] = (int) (100 + 60 * Math.cos(x * 0.015 - y * 0.025));
            }
        }
        return p;
    }

    private static int[][] deep(int[][] a) {
        int[][] b = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i].clone();
        }
        return b;
    }

    /**
     * A full multi-pass decode reconstructs exactly the coefficients a single-pass
     * encode does, so the two decode to identical pixels — the passes are only a
     * re-partitioning of the same data.
     */
    @Test
    void fullDecodeMatchesSinglePass() throws Exception {
        int w = 300;
        int h = 260; // multiple groups
        int[][] src = photo(w, h);
        int[][] single = JxlDecoder.decode(VarDctEncoder.encode(deep(src), w, h, 1.5f))
                .frames.get(0).channels;
        for (int[] shifts : new int[][] {{1, 0}, {2, 0}, {2, 1, 0}}) {
            byte[] prog = VarDctEncoder.encodeProgressive(deep(src), w, h, 8, false, 1.5f, shifts);
            int[][] got = JxlDecoder.decode(prog).frames.get(0).channels;
            for (int c = 0; c < 3; c++) {
                assertArrayEquals(single[c], got[c],
                        "progressive " + java.util.Arrays.toString(shifts) + " channel " + c);
            }
        }
    }

    /** Works for a single group too (a small image), where every pass is one section. */
    @Test
    void singleGroupProgressive() throws Exception {
        int w = 128;
        int h = 128;
        int[][] src = photo(w, h);
        int[][] single = JxlDecoder.decode(VarDctEncoder.encode(deep(src), w, h, 1.5f))
                .frames.get(0).channels;
        byte[] prog = VarDctEncoder.encodeProgressive(deep(src), w, h, 8, false, 1.5f, new int[] {1, 0});
        int[][] got = JxlDecoder.decode(prog).frames.get(0).channels;
        for (int c = 0; c < 3; c++) {
            assertArrayEquals(single[c], got[c], "single-group progressive channel " + c);
        }
    }

    /**
     * The point of progression: a prefix of the bytes already decodes to a picture,
     * and longer prefixes refine it. As more of the stream arrives the error only
     * falls — each completed section (the DC preview, then the coarse pass, then the
     * refinement) adds detail and none is ever lost.
     */
    @Test
    void progressiveRefinesWithLength() throws Exception {
        int w = 256;
        int h = 256;
        int[][] src = photo(w, h);
        byte[] prog = VarDctEncoder.encodeProgressive(deep(src), w, h, 8, false, 2.0f, new int[] {2, 0});

        double prev = Double.MAX_VALUE;
        for (double f : new double[] {0.5, 0.75, 0.9, 1.0}) {
            int len = Math.max(1, (int) (prog.length * f));
            JxlFrame frame = JxlDecoder.decodePartial(java.util.Arrays.copyOf(prog, len)).frames.get(0);
            assertTrue(frame.channels[0].length == w * h, "partial decode has full size at " + f);
            double e = err(src, frame, w, h);
            assertTrue(e <= prev + 1e-6, "more bytes must not decode worse: " + e + " after " + prev);
            prev = e;
        }
        // and the whole file is a good picture
        assertTrue(prev < 6.0, "full progressive decode should be near the source: " + prev);
    }

    private static double err(int[][] src, JxlFrame f, int w, int h) {
        long sum = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                sum += Math.abs(f.channels[c][i] - src[c][i]);
            }
        }
        return sum / (3.0 * w * h);
    }

    @Test
    void rejectsBadShifts() {
        int[][] src = photo(64, 64);
        assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeProgressive(deep(src), 64, 64, 8, false, 1.0f, new int[] {1, 1, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> VarDctEncoder.encodeProgressive(deep(src), 64, 64, 8, false, 1.0f, new int[] {0}));
    }
}
