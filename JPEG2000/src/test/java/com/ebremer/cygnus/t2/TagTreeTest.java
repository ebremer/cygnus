package com.ebremer.cygnus.t2;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TagTreeTest {

    @Test
    void fullValuesRoundTrip() {
        for (int seed = 0; seed < 20; seed++) {
            Random rnd = new Random(seed);
            int w = 1 + rnd.nextInt(9);
            int h = 1 + rnd.nextInt(9);
            int[] values = new int[w * h];
            for (int i = 0; i < values.length; i++) {
                values[i] = rnd.nextInt(12);
            }
            PacketBitWriter bw = new PacketBitWriter();
            TagTreeEncoder enc = new TagTreeEncoder(w, h, values);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    enc.encodeValue(bw, x, y);
                }
            }
            byte[] data = bw.finish();
            PacketBitReader br = new PacketBitReader(data, 0, data.length);
            TagTree dec = new TagTree(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    assertEquals(values[y * w + x], dec.decodeValue(br, x, y),
                            "seed " + seed + " leaf " + x + "," + y);
                }
            }
        }
    }

    @Test
    void incrementalThresholdsRoundTrip() {
        Random rnd = new Random(42);
        int w = 6;
        int h = 5;
        int[] values = new int[w * h];
        for (int i = 0; i < values.length; i++) {
            values[i] = rnd.nextInt(8);
        }
        PacketBitWriter bw = new PacketBitWriter();
        TagTreeEncoder enc = new TagTreeEncoder(w, h, values);
        for (int t = 1; t <= 9; t++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    enc.encode(bw, x, y, t);
                }
            }
        }
        byte[] data = bw.finish();
        PacketBitReader br = new PacketBitReader(data, 0, data.length);
        TagTree dec = new TagTree(w, h);
        for (int t = 1; t <= 9; t++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    assertEquals(values[y * w + x] < t, dec.decode(br, x, y, t),
                            "threshold " + t + " leaf " + x + "," + y);
                }
            }
        }
    }
}
