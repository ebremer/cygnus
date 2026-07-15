package com.ebremer.cygnus.jpegxl.color;

import java.awt.color.ICC_Profile;
import java.awt.color.ICC_ProfileRGB;
import java.util.function.DoubleUnaryOperator;

/**
 * Colour-manages the samples of an image whose embedded ICC profile is a
 * matrix/TRC RGB profile — the ordinary kind a photograph carries (sRGB,
 * Display&nbsp;P3, Adobe&nbsp;RGB, a scanner's profile) — mapping them to sRGB
 * for display. The device samples are linearised through the profile's tone
 * curves, taken to the D50 connection space by its colorant matrix, and brought
 * to sRGB there (its primaries Bradford-adapted to D50, the connection white),
 * then sRGB-encoded. This is the relative-colorimetric transform a colour-managed
 * viewer performs, and it reproduces lcms2 — the engine libjxl and most systems
 * use — to within a single eight-bit step on the conformance profiles.
 *
 * <p>The profile is <em>parsed</em> through {@code java.awt.color} (its matrix
 * and white-point readers are reliable) but the transform is done here, because
 * {@code java.awt}'s own colour engine does not reproduce lcms2 and cannot read
 * the parametric ({@code para}) tone curves these profiles use. Profiles that are
 * not matrix/TRC RGB — LUT-based ({@code A2B0}), CMYK, greyscale — return
 * {@code null} from {@link #forProfile}; those need a full colour-management
 * module, and the caller falls back to treating the samples as sRGB.
 */
public final class IccColorTransform {

    /** XYZ (D50 PCS) to linear sRGB: sRGB primaries Bradford-adapted to D50, inverted. */
    private static final double[][] XYZ_D50_TO_SRGB = {
        {3.1338561, -1.6168667, -0.4906146},
        {-0.9787684, 1.9161415, 0.0334540},
        {0.0719453, -0.2289914, 1.4052427},
    };

    private final double[][] combined;          // XYZ_D50_TO_SRGB · colorant matrix
    private final DoubleUnaryOperator[] trc;    // per-channel device -> linear

    private IccColorTransform(double[][] combined, DoubleUnaryOperator[] trc) {
        this.combined = combined;
        this.trc = trc;
    }

    /**
     * A transform for {@code iccBytes}, or {@code null} if the profile is not a
     * matrix/TRC RGB profile this can render exactly (in which case the caller
     * should treat the samples as sRGB rather than mis-render them).
     */
    public static IccColorTransform forProfile(byte[] iccBytes) {
        if (iccBytes == null) {
            return null;
        }
        try {
            ICC_Profile profile = ICC_Profile.getInstance(iccBytes);
            if (!(profile instanceof ICC_ProfileRGB rgb)) {
                return null;   // not RGB matrix/TRC (LUT, CMYK, grey, ...)
            }
            float[][] matrix = rgb.getMatrix();   // linear RGB -> XYZ (D50)
            double[][] combined = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    double sum = 0;
                    for (int k = 0; k < 3; k++) {
                        sum += XYZ_D50_TO_SRGB[i][k] * matrix[k][j];
                    }
                    combined[i][j] = sum;
                }
            }
            int[] tags = {ICC_Profile.icSigRedTRCTag, ICC_Profile.icSigGreenTRCTag,
                ICC_Profile.icSigBlueTRCTag};
            DoubleUnaryOperator[] trc = new DoubleUnaryOperator[3];
            for (int c = 0; c < 3; c++) {
                trc[c] = parseTrc(profile.getData(tags[c]));
                if (trc[c] == null) {
                    return null;
                }
            }
            return new IccColorTransform(combined, trc);
        } catch (RuntimeException e) {
            return null;   // any parse trouble: fall back rather than mis-render
        }
    }

    /**
     * Maps the three integer colour planes from the profile's device encoding to
     * sRGB in place. {@code max} is the full-scale value for the sample depth
     * ({@code (1 << bits) - 1}).
     */
    public void toSrgb(int[] r, int[] g, int[] b, int max) {
        double inv = 1.0 / max;
        int n = r.length;
        for (int i = 0; i < n; i++) {
            double lr = trc[0].applyAsDouble(r[i] * inv);
            double lg = trc[1].applyAsDouble(g[i] * inv);
            double lb = trc[2].applyAsDouble(b[i] * inv);
            r[i] = encode(combined[0][0] * lr + combined[0][1] * lg + combined[0][2] * lb, max);
            g[i] = encode(combined[1][0] * lr + combined[1][1] * lg + combined[1][2] * lb, max);
            b[i] = encode(combined[2][0] * lr + combined[2][1] * lg + combined[2][2] * lb, max);
        }
    }

    private static int encode(double linear, int max) {
        double v = linear <= 0 ? 0 : linear >= 1 ? 1
                : linear <= 0.0031308 ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        int s = (int) Math.round(v * max);
        return s < 0 ? 0 : Math.min(s, max);
    }

    /** The device->linear tone curve of one channel, or {@code null} if the tag is unreadable. */
    private static DoubleUnaryOperator parseTrc(byte[] tag) {
        if (tag == null || tag.length < 12) {
            return null;
        }
        String type = new String(tag, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (type.equals("curv")) {
            int n = i32(tag, 8);
            if ((long) 12 + (long) n * 2 > tag.length) {
                return null;
            }
            if (n == 0) {
                return x -> x;                       // identity
            }
            if (n == 1) {
                double gamma = u16(tag, 12) / 256.0; // u8Fixed8 gamma
                return x -> Math.pow(x, gamma);
            }
            double[] lut = new double[n];
            for (int i = 0; i < n; i++) {
                lut[i] = u16(tag, 12 + i * 2) / 65535.0;
            }
            return x -> {
                double p = x * (n - 1);
                int i = (int) p;
                if (i >= n - 1) {
                    return lut[n - 1];
                }
                if (i < 0) {
                    return lut[0];
                }
                double f = p - i;
                return lut[i] * (1 - f) + lut[i + 1] * f;
            };
        }
        if (type.equals("para")) {
            int ft = u16(tag, 8);
            int need = 12 + 4 * (ft == 0 ? 1 : ft == 1 ? 3 : ft == 2 ? 4 : ft == 3 ? 5 : 7);
            if (need > tag.length) {
                return null;
            }
            double g = s15f16(tag, 12);
            if (ft == 0) {
                return x -> Math.pow(x, g);
            }
            double a = s15f16(tag, 16);
            double b = s15f16(tag, 20);
            if (ft == 1) {
                return x -> x >= -b / a ? Math.pow(a * x + b, g) : 0;
            }
            double c = s15f16(tag, 24);
            if (ft == 2) {
                return x -> x >= -b / a ? Math.pow(a * x + b, g) + c : c;
            }
            double d = s15f16(tag, 28);
            if (ft == 3) {
                return x -> x >= d ? Math.pow(a * x + b, g) : c * x;
            }
            double e = s15f16(tag, 32);
            double f = s15f16(tag, 36);
            return x -> x >= d ? Math.pow(a * x + b, g) + e : c * x + f;
        }
        return null;   // 'mft'/'mAB'/... need a full CMM
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
    }

    private static int i32(byte[] d, int o) {
        return ((d[o] & 0xff) << 24) | ((d[o + 1] & 0xff) << 16)
                | ((d[o + 2] & 0xff) << 8) | (d[o + 3] & 0xff);
    }

    private static double s15f16(byte[] d, int o) {
        return i32(d, o) / 65536.0;
    }
}
