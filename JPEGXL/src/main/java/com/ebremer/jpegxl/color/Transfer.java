package com.ebremer.jpegxl.color;

import com.ebremer.jpegxl.codestream.ColourEncoding;

/** Transfer functions: linear light to display-encoded values, both in [0,1]. */
public final class Transfer {

    private Transfer() {
    }

    /** Applies the encoding named by the ColourEncoding to a linear plane in place. */
    public static void fromLinear(ColourEncoding ce, float[] plane) {
        if (ce.gamma > 0) {
            double g = ce.gamma / 1e7;
            for (int i = 0; i < plane.length; i++) {
                plane[i] = (float) Math.pow(Math.max(plane[i], 0f), g);
            }
            return;
        }
        switch (ce.transferFunction) {
            case ColourEncoding.TF_LINEAR -> {
            }
            case ColourEncoding.TF_SRGB -> {
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = srgbFromLinear(plane[i]);
                }
            }
            case ColourEncoding.TF_BT709 -> {
                // libjxl uses the classic rounded Rec.709 constants
                // (0.018 / 1.099 / -0.099), not the exact-continuity variants
                for (int i = 0; i < plane.length; i++) {
                    float v = plane[i];
                    // negatives take the linear segment (unlike sRGB's mirror)
                    plane[i] = v < 0.018f ? 4.5f * v
                            : (float) (1.099 * Math.pow(v, 0.45) - 0.099);
                }
            }
            case ColourEncoding.TF_DCI -> {
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = (float) Math.pow(Math.max(plane[i], 0f), 1.0 / 2.6);
                }
            }
            case ColourEncoding.TF_PQ -> {
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = pqFromLinear(plane[i]);
                }
            }
            case ColourEncoding.TF_HLG -> {
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = hlgFromLinear(plane[i]);
                }
            }
            default -> {
                // unknown: leave as sRGB, the most common assumption
                for (int i = 0; i < plane.length; i++) {
                    plane[i] = srgbFromLinear(plane[i]);
                }
            }
        }
    }

    public static float srgbFromLinear(float v) {
        if (v < 0f) {
            // out-of-gamut values encode through the sign-mirrored curve
            return -srgbFromLinear(-v);
        }
        if (v <= 0.0031308f) {
            return 12.92f * v;
        }
        return (float) (1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
    }

    private static float pqFromLinear(float v) {
        double x = Math.max(v, 0f);
        double xp = Math.pow(x, 0.1593017578125);
        return (float) Math.pow((0.8359375 + 18.8515625 * xp) / (1 + 18.6875 * xp), 78.84375);
    }

    private static float hlgFromLinear(float v) {
        double x = Math.max(v, 0f);
        if (x <= 1.0 / 12.0) {
            return (float) Math.sqrt(3.0 * x);
        }
        double a = 0.17883277;
        double b = 1 - 4 * a;
        double c = 0.5 - a * Math.log(4 * a);
        return (float) (a * Math.log(12.0 * x - b) + c);
    }
}
