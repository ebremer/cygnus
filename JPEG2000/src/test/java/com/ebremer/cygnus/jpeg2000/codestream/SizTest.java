package com.ebremer.cygnus.jpeg2000.codestream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * T.800 B-7 tile bounds at the far edge of the reference grid: the last
 * tile's (p+1)·XTsiz corner passes 2^31 before its clamp to Xsiz, and a
 * negative bound reads as an empty tile — silently blank samples.
 */
class SizTest {

    @Test
    void lastTileBoundsSurviveGridsNearIntMax() {
        int big = Integer.MAX_VALUE;
        int half = 1 << 30;
        Siz siz = new Siz(0, big, big, 0, 0, half, half, 0, 0,
                new int[] {8}, new boolean[] {false}, new int[] {1}, new int[] {1});
        assertArrayEquals(new int[] {half, half, big, big}, siz.tileBounds(3));
    }
}
