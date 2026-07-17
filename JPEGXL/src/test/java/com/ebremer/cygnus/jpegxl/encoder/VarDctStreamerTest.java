package com.ebremer.cygnus.jpegxl.encoder;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.cygnus.jpegxl.codestream.BitDepth;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The streamer always writes a multi-section TOC, but the format wants a
 * single-entry TOC for a one-group, one-pass frame. Its only caller diverts
 * single-group images to the buffered path; the constructor has to hold that
 * line itself, so a new caller cannot quietly emit an unreadable frame.
 */
class VarDctStreamerTest {

    @Test
    void refusesSingleGroupImages() {
        assertThrows(IllegalArgumentException.class, () -> new VarDctStreamer(
                new ByteArrayOutputStream(), 200, 150, BitDepth.of(8), false,
                List.of(), 1.5f, false));
    }
}
