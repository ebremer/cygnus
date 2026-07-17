package com.ebremer.cygnus.jpegxl.encoder;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.cygnus.jpegxl.io.BitWriter;
import org.junit.jupiter.api.Test;

/**
 * The TOC entry's widest branch holds 30 bits; a section past it (or a
 * negative size) must be refused rather than silently masked into a TOC that
 * points everywhere but at the sections.
 */
class TocEntryTest {

    @Test
    void sizesBeyondTheWidestBranchAreRefused() {
        BitWriter w = new BitWriter();
        JxlEncoder.writeTocEntry(w, 4211712 + (1 << 30) - 1); // the largest that fits
        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.writeTocEntry(w, 4211712 + (1 << 30)));
        assertThrows(IllegalArgumentException.class, () -> JxlEncoder.writeTocEntry(w, -1));
    }
}
