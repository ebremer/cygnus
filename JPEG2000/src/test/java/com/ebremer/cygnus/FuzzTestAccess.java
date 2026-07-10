package com.ebremer.cygnus;

import java.util.List;

/** Exposes the fuzz seed corpus to out-of-band long-running fuzz drivers. */
public final class FuzzTestAccess {
    private FuzzTestAccess() {
    }

    public static List<byte[]> seeds() {
        return FuzzTest.seeds();
    }
}
