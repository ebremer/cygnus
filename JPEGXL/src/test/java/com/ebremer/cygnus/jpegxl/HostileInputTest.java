package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.HostileStreams;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import com.ebremer.cygnus.jpegxl.features.PatchesDictionary;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
import com.ebremer.cygnus.jpegxl.io.Bits;
import com.ebremer.cygnus.jpegxl.io.Bounds;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Hostile-input hardening: crafted codestreams whose declared counts and
 * sizes would otherwise become absurd allocations or bad array indices must
 * fail with a clean {@link IOException} — never {@code OutOfMemoryError},
 * {@code NegativeArraySizeException}, or an index crash. One section per
 * remediated finding (REVIEW.md / TODO.md P0).
 */
class HostileInputTest {

    // ---- the shared bounds layer and its decode ceiling

    /**
     * A bare codestream declaring 65536 x 65535: the pixel count wraps the
     * {@code int} canvas product to a negative length. Bare streams declare no
     * level, so before the ceiling nothing stood between these dimensions and
     * the canvas allocation.
     */
    private static byte[] hugeBareCodestream() {
        BitWriter bw = new BitWriter();
        bw.write(0x0aff, 16);                 // bare codestream signature
        new SizeHeader(65536, 65535).write(bw);
        bw.writeBool(true);                   // ImageMetadata all_default
        bw.writeBool(true);                   // default_m
        bw.zeroPadToByte();
        bw.writeBool(true);                   // FrameHeader all_default
        bw.writeBool(false);                  // TOC not permuted
        bw.zeroPadToByte();
        int sections = 1 + 32 * 32 + 1 + 256 * 256; // LfGlobal, LF groups, HfGlobal, groups
        for (int i = 0; i < sections; i++) {
            bw.write(0, 2);                   // U32 selector 0
            bw.write(0, 10);                  // section size 0
        }
        bw.zeroPadToByte();
        return bw.toByteArray();
    }

    @Test
    void overCeilingBareCodestreamIsRejectedCleanly() {
        byte[] cs = hugeBareCodestream();
        IOException e = assertThrows(IOException.class, () -> JxlDecoder.decode(cs));
        assertTrue(e.getMessage().contains("ceiling"), e.getMessage());
        assertThrows(IOException.class, () -> JxlDecoder.readInfo(cs));
    }

    @Test
    void pixelCeilingIsConfigurable() throws Exception {
        int[][] planes = new int[3][1200 * 900];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < planes[c].length; i++) {
                planes[c][i] = (i * 7 + c * 101) & 0xff;
            }
        }
        byte[] cs = JxlEncoder.encode(planes, 1200, 900, 8, false, false, false);
        assertEquals(1200, JxlDecoder.decode(cs).width); // fine at the default ceiling
        System.setProperty(Bounds.MAX_PIXELS_PROPERTY, "1000000");
        try {
            IOException e = assertThrows(IOException.class, () -> JxlDecoder.decode(cs));
            assertTrue(e.getMessage().contains(Bounds.MAX_PIXELS_PROPERTY), e.getMessage());
        } finally {
            System.clearProperty(Bounds.MAX_PIXELS_PROPERTY);
        }
        assertEquals(1200, JxlDecoder.decode(cs).width);
    }

    // ---- CRIT-1: patch dictionary counts drive allocations

    @Test
    void patchPositionCountWithBit31IsRejected() {
        // one patch whose position count decodes to 1 + 0x7fffffff: the int
        // wraps negative and used to reach new int[count][2]
        byte[] s = HostileStreams.symbolStream(10,
                new int[] {0, 1},                   // numPatches
                new int[] {1, 0},                   // ref
                new int[] {3, 0}, new int[] {3, 0}, // x0, y0
                new int[] {2, 0}, new int[] {2, 0}, // width-1, height-1
                new int[] {7, Integer.MAX_VALUE});  // positions - 1
        assertThrows(IOException.class,
                () -> PatchesDictionary.read(new Bits(s), 0, 0, 100 * 100));
    }

    @Test
    void negativePatchCountIsRejected() {
        // numPatches with bit 31 set: as a signed loop bound it used to skip
        // the loop and return an empty dictionary as if the stream were fine
        byte[] s = HostileStreams.symbolStream(10, new int[] {0, 0xfffffff5});
        assertThrows(IOException.class,
                () -> PatchesDictionary.read(new Bits(s), 0, 0, 100 * 100));
    }

    @Test
    void patchPositionsBeyondTheFrameBudgetAreRejected() {
        // a 100x100 frame cannot need 2^20 patch positions
        byte[] s = HostileStreams.symbolStream(10,
                new int[] {0, 1},
                new int[] {1, 0},
                new int[] {3, 0}, new int[] {3, 0},
                new int[] {2, 0}, new int[] {2, 0},
                new int[] {7, (1 << 20) - 1});
        IOException e = assertThrows(IOException.class,
                () -> PatchesDictionary.read(new Bits(s), 0, 0, 100 * 100));
        assertTrue(e.getMessage().contains("patch"), e.getMessage());
    }

    @Test
    void boundsPrimitivesRejectOverflowAndNegatives() {
        assertThrows(IOException.class, () -> Bounds.area(65536, 65535, "test"));
        assertThrows(IOException.class, () -> Bounds.area(-1, 4, "test"));
        assertEquals(12, org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> Bounds.area(3, 4, "test")));
        assertThrows(IOException.class, () -> Bounds.count(-1, 10, "test"));
        assertThrows(IOException.class, () -> Bounds.count(11, 10, "test"));
    }
}
