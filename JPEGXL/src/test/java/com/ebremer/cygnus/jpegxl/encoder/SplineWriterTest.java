package com.ebremer.cygnus.jpegxl.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.cygnus.jpegxl.features.Splines;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import org.junit.jupiter.api.Test;

/** The spline dictionary writer is the exact inverse of {@link Splines#read}. */
class SplineWriterTest {

    static Splines twoSplines() {
        Splines s = new Splines();
        s.numSplines = 2;
        s.quantAdjust = -3;
        s.controlX = new int[][] {{20, 45, 63, 90}, {100, 110, 130}};
        s.controlY = new int[][] {{20, 55, 31, 66}, {40, 80, 50}};
        s.coeffX = new int[2][32];
        s.coeffY = new int[2][32];
        s.coeffB = new int[2][32];
        s.coeffSigma = new int[2][32];
        // some varied, signed coefficients so every context codes real values
        for (int i = 0; i < 2; i++) {
            s.coeffY[i][0] = 40 + 5 * i;
            s.coeffY[i][3] = -7;
            s.coeffX[i][0] = 6;
            s.coeffX[i][1] = -2;
            s.coeffB[i][0] = -11;
            s.coeffSigma[i][0] = 12 + i;
            s.coeffSigma[i][2] = 4;
        }
        return s;
    }

    @Test
    void roundTripsThroughRead() throws Exception {
        Splines s = twoSplines();
        BitWriter w = new BitWriter();
        SplineWriter.write(w, s);
        w.zeroPadToByte();
        Splines back = Splines.read(new Bits(w.toByteArray()));

        assertEquals(s.numSplines, back.numSplines);
        assertEquals(s.quantAdjust, back.quantAdjust);
        for (int i = 0; i < s.numSplines; i++) {
            assertArrayEquals(s.controlX[i], back.controlX[i], "controlX " + i);
            assertArrayEquals(s.controlY[i], back.controlY[i], "controlY " + i);
            assertArrayEquals(s.coeffX[i], back.coeffX[i], "coeffX " + i);
            assertArrayEquals(s.coeffY[i], back.coeffY[i], "coeffY " + i);
            assertArrayEquals(s.coeffB[i], back.coeffB[i], "coeffB " + i);
            assertArrayEquals(s.coeffSigma[i], back.coeffSigma[i], "coeffSigma " + i);
        }
    }

    @Test
    void singleSplineRoundTrips() throws Exception {
        Splines s = new Splines();
        s.numSplines = 1;
        s.quantAdjust = 0;
        s.controlX = new int[][] {{30, 60}};
        s.controlY = new int[][] {{30, 90}};
        s.coeffX = new int[1][32];
        s.coeffY = new int[1][32];
        s.coeffB = new int[1][32];
        s.coeffSigma = new int[1][32];
        s.coeffY[0][0] = 50;
        s.coeffSigma[0][0] = 10;

        BitWriter w = new BitWriter();
        SplineWriter.write(w, s);
        w.zeroPadToByte();
        Splines back = Splines.read(new Bits(w.toByteArray()));
        assertEquals(1, back.numSplines);
        assertArrayEquals(s.controlX[0], back.controlX[0]);
        assertArrayEquals(s.coeffSigma[0], back.coeffSigma[0]);
    }
}
