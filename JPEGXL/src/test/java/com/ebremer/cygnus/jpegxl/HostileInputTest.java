package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.cygnus.jpegxl.codestream.SizeHeader;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.encoder.JxlEncoder;
import com.ebremer.cygnus.jpegxl.io.BitWriter;
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
