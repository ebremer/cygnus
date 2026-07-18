package com.ebremer.cygnus.jpegxl.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.cygnus.jpegxl.features.PatchesDictionary;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The patch-dictionary writer is the inverse of {@link PatchesDictionary#read}. */
class PatchDictWriterTest {

    @Test
    void dictionaryRoundTrips() throws Exception {
        List<PatchDictWriter.Patch> patches = List.of(
                new PatchDictWriter.Patch(1, 0, 0, 8, 8,
                        new int[][] {{10, 12}, {40, 12}, {70, 60}}),
                new PatchDictWriter.Patch(1, 8, 0, 5, 9,
                        new int[][] {{100, 4}}));

        BitWriter w = new BitWriter();
        PatchDictWriter.write(w, patches, 0);   // no extra channels
        w.zeroPadToByte();
        byte[] bytes = w.toByteArray();

        PatchesDictionary dict = PatchesDictionary.read(new Bits(bytes), 0, 0, 256 * 256);
        assertEquals(2, dict.patches.size());

        PatchesDictionary.Patch a = dict.patches.get(0);
        assertEquals(1, a.ref());
        assertEquals(0, a.x0());
        assertEquals(0, a.y0());
        assertEquals(8, a.width());
        assertEquals(8, a.height());
        assertEquals(3, a.positions().length);
        assertEquals(10, a.positions()[0][0]);
        assertEquals(12, a.positions()[0][1]);
        assertEquals(40, a.positions()[1][0]);
        assertEquals(70, a.positions()[2][0]);
        assertEquals(60, a.positions()[2][1]);
        assertEquals(PatchesDictionary.MODE_REPLACE, a.blendings()[0][0].mode());

        PatchesDictionary.Patch b = dict.patches.get(1);
        assertEquals(8, b.x0());
        assertEquals(5, b.width());
        assertEquals(9, b.height());
        assertEquals(100, b.positions()[0][0]);
        assertEquals(4, b.positions()[0][1]);
    }
}
