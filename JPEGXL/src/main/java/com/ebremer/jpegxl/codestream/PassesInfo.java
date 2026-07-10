package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/** Passes bundle: progressive pass shifts and downsampling markers. */
public final class PassesInfo {

    public final int numPasses;
    public final int[] shift;      // coefficient shift per pass (VarDCT refinement)
    public final int[] downSample; // terminated by 1
    public final int[] lastPass;   // terminated by numPasses - 1

    public PassesInfo() {
        numPasses = 1;
        shift = new int[1];
        downSample = new int[] {1};
        lastPass = new int[] {0};
    }

    private PassesInfo(int numPasses, int[] shift, int[] downSample, int[] lastPass) {
        this.numPasses = numPasses;
        this.shift = shift;
        this.downSample = downSample;
        this.lastPass = lastPass;
    }

    public static PassesInfo read(Bits in) throws IOException {
        int numPasses = in.u32(1, 0, 2, 0, 3, 0, 4, 3);
        if (numPasses == 1) {
            return new PassesInfo();
        }
        int numDS = in.u32(0, 0, 1, 0, 2, 0, 3, 1);
        if (numDS >= numPasses) {
            throw new IOException("num_ds must be less than num_passes");
        }
        int[] shift = new int[numPasses];
        for (int i = 0; i < numPasses - 1; i++) {
            shift[i] = in.u(2);
        }
        int[] downSample = new int[numDS + 1];
        int[] lastPass = new int[numDS + 1];
        for (int i = 0; i < numDS; i++) {
            downSample[i] = 1 << in.u(2);
        }
        for (int i = 0; i < numDS; i++) {
            lastPass[i] = in.u32(0, 0, 1, 0, 2, 0, 0, 3);
        }
        downSample[numDS] = 1;
        lastPass[numDS] = numPasses - 1;
        return new PassesInfo(numPasses, shift, downSample, lastPass);
    }

    /**
     * The minimum modular channel shift for a pass; channels whose
     * {@code min(hshift, vshift)} lies in [minShift, maxShift) are coded in
     * that pass's groups.
     */
    public int minShift(int pass) {
        for (int i = 0; i < lastPass.length; i++) {
            if (lastPass[i] == pass) {
                return Bits.ceilLog2(downSample[i]);
            }
        }
        return maxShift(pass);
    }

    public int maxShift(int pass) {
        return pass > 0 ? minShift(pass - 1) : 3;
    }
}
