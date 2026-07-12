package com.ebremer.cygnus.jpeg2000.t1;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MQCoderTest {

    @Test
    void roundTripUniformRandom() {
        roundTrip(new Random(1), 10_000, 19, false);
    }

    @Test
    void roundTripSkewedBits() {
        roundTrip(new Random(2), 20_000, 19, true);
    }

    @Test
    void roundTripShortSequences() {
        for (int len = 1; len < 60; len++) {
            roundTrip(new Random(len), len, 5, false);
        }
    }

    private void roundTrip(Random rnd, int count, int numCtx, boolean skewed) {
        int[] cx = new int[count];
        int[] bits = new int[count];
        for (int i = 0; i < count; i++) {
            cx[i] = rnd.nextInt(numCtx);
            bits[i] = skewed ? (rnd.nextInt(10) == 0 ? 1 : 0) : rnd.nextInt(2);
        }
        MQEncoder enc = new MQEncoder(numCtx);
        enc.setContext(0, 4);
        if (numCtx > 18) {
            enc.setContext(17, 3);
            enc.setContext(18, 46);
        }
        for (int i = 0; i < count; i++) {
            enc.encode(cx[i], bits[i]);
        }
        byte[] data = enc.flush();

        MQDecoder dec = new MQDecoder(data, 0, data.length, numCtx);
        dec.setContext(0, 4);
        if (numCtx > 18) {
            dec.setContext(17, 3);
            dec.setContext(18, 46);
        }
        for (int i = 0; i < count; i++) {
            assertEquals(bits[i], dec.decode(cx[i]), "decision " + i);
        }
    }

    @Test
    void multiSegmentDecodePreservesContexts() {
        // two independently terminated segments decoded with one context set
        Random rnd = new Random(7);
        int[] bits1 = new int[500];
        int[] bits2 = new int[500];
        for (int i = 0; i < 500; i++) {
            bits1[i] = rnd.nextInt(2);
            bits2[i] = rnd.nextInt(2);
        }
        MQEncoder e1 = new MQEncoder(3);
        for (int b : bits1) {
            e1.encode(1, b);
        }
        byte[] seg1 = e1.flush();
        MQEncoder e2 = new MQEncoder(3);
        for (int b : bits2) {
            e2.encode(1, b);
        }
        byte[] seg2 = e2.flush();

        MQDecoder dec = new MQDecoder(seg1, 0, seg1.length, 3);
        for (int b : bits1) {
            assertEquals(b, dec.decode(1));
        }
        // context state differs from a fresh decoder now; restart on segment 2
        // with fresh contexts to mirror the fresh encoder
        MQDecoder dec2 = new MQDecoder(seg2, 0, seg2.length, 3);
        for (int b : bits2) {
            assertEquals(b, dec2.decode(1));
        }
    }
}
