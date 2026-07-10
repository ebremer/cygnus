package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/** RestorationFilter bundle: gaborish and edge-preserving filter parameters. */
public final class RestorationFilter {

    public boolean gab = true;
    public final float[] gab1Weights = {0.115169525f, 0.115169525f, 0.115169525f};
    public final float[] gab2Weights = {0.061248592f, 0.061248592f, 0.061248592f};
    public int epfIterations = 2;
    public final float[] epfSharpLut = new float[8];
    public final float[] epfChannelScale = {40f, 5f, 3.5f};
    public float epfQuantMul = 0.46f;
    public float epfPass0SigmaScale = 0.9f;
    public float epfPass2SigmaScale = 6.5f;
    public float epfBorderSadMul = 2f / 3f;
    public float epfSigmaForModular = 1f;

    public RestorationFilter() {
        for (int i = 0; i < 8; i++) {
            epfSharpLut[i] = i / 7f;
        }
    }

    public static RestorationFilter read(Bits in, boolean modular) throws IOException {
        RestorationFilter rf = new RestorationFilter();
        boolean allDefault = in.bool();
        if (!allDefault) {
            rf.gab = in.bool();
            if (rf.gab && in.bool()) { // gab_custom
                for (int i = 0; i < 3; i++) {
                    rf.gab1Weights[i] = in.f16();
                    rf.gab2Weights[i] = in.f16();
                }
            }
            rf.epfIterations = in.u(2);
            if (rf.epfIterations > 0) {
                if (!modular && in.bool()) { // epf_sharp_custom
                    for (int i = 0; i < 8; i++) {
                        rf.epfSharpLut[i] = in.f16();
                    }
                }
                if (in.bool()) { // epf_weight_custom
                    for (int i = 0; i < 3; i++) {
                        rf.epfChannelScale[i] = in.f16();
                    }
                    in.u(32);
                }
                if (in.bool()) { // epf_sigma_custom
                    if (!modular) {
                        rf.epfQuantMul = in.f16();
                    }
                    rf.epfPass0SigmaScale = in.f16();
                    rf.epfPass2SigmaScale = in.f16();
                    rf.epfBorderSadMul = in.f16();
                }
                if (modular) {
                    rf.epfSigmaForModular = in.f16();
                }
            }
            ImageMetadata.readExtensions(in);
        }
        for (int i = 0; i < 8; i++) {
            rf.epfSharpLut[i] *= rf.epfQuantMul;
        }
        return rf;
    }
}
