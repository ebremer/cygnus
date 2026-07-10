package com.ebremer.jpegxl.modular;

/** A parsed modular transform. Squeeze transforms are flattened to one entry per step. */
public sealed interface Transform {

    record Rct(int beginC, int type) implements Transform {
    }

    record Palette(int beginC, int numC, int nbColours, int nbDeltas, int dPred) implements Transform {
    }

    record Squeeze(boolean horizontal, boolean inPlace, int beginC, int numC) implements Transform {
    }
}
