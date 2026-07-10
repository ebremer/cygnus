package com.ebremer.jpegxl.codestream;

import com.ebremer.jpegxl.io.BitWriter;
import com.ebremer.jpegxl.io.Bits;
import java.io.IOException;

/** ColourEncoding bundle: enumerated colour spaces or an embedded ICC profile. */
public final class ColourEncoding {

    public static final int CS_RGB = 0;
    public static final int CS_GREY = 1;
    public static final int CS_XYB = 2;
    public static final int CS_UNKNOWN = 3;

    public static final int WP_D65 = 1;
    public static final int WP_CUSTOM = 2;
    public static final int WP_E = 10;
    public static final int WP_DCI = 11;

    public static final int PR_SRGB = 1;
    public static final int PR_CUSTOM = 2;
    public static final int PR_BT2100 = 9;
    public static final int PR_P3 = 11;

    public static final int TF_BT709 = 1;
    public static final int TF_UNKNOWN = 2;
    public static final int TF_LINEAR = 8;
    public static final int TF_SRGB = 13;
    public static final int TF_PQ = 16;
    public static final int TF_DCI = 17;
    public static final int TF_HLG = 18;

    public boolean allDefault = true;
    public boolean wantIcc;
    public int colourSpace = CS_RGB;
    public int whitePoint = WP_D65;
    public final float[] whiteXY = {0.3127f, 0.3290f};
    public int primaries = PR_SRGB;
    public final float[][] primariesXY = {
        {0.639998686f, 0.330010138f}, {0.300003784f, 0.600003357f}, {0.150002046f, 0.059997204f},
    };
    /** Gamma numerator scaled by 1e7, or 0 when a transfer function enum is used. */
    public int gamma;
    public int transferFunction = TF_SRGB;
    public int renderingIntent = 1; // relative colorimetric

    public static ColourEncoding read(Bits in) throws IOException {
        ColourEncoding c = new ColourEncoding();
        c.allDefault = in.bool();
        if (c.allDefault) {
            return c;
        }
        c.wantIcc = in.bool();
        c.colourSpace = in.enumValue();
        if (c.colourSpace > CS_UNKNOWN) {
            throw new IOException("unknown colour space " + c.colourSpace);
        }
        if (!c.wantIcc) {
            if (c.colourSpace != CS_XYB) {
                c.whitePoint = in.enumValue();
                switch (c.whitePoint) {
                    case WP_D65 -> {
                    }
                    case WP_CUSTOM -> readCustomXY(in, c.whiteXY);
                    case WP_E -> {
                        c.whiteXY[0] = 1 / 3f;
                        c.whiteXY[1] = 1 / 3f;
                    }
                    case WP_DCI -> {
                        c.whiteXY[0] = 0.314f;
                        c.whiteXY[1] = 0.351f;
                    }
                    default -> throw new IOException("unknown white point " + c.whitePoint);
                }
                if (c.colourSpace != CS_GREY) {
                    c.primaries = in.enumValue();
                    switch (c.primaries) {
                        case PR_SRGB -> {
                        }
                        case PR_CUSTOM -> {
                            readCustomXY(in, c.primariesXY[0]);
                            readCustomXY(in, c.primariesXY[1]);
                            readCustomXY(in, c.primariesXY[2]);
                        }
                        case PR_BT2100 -> setPrimaries(c, 0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f);
                        case PR_P3 -> setPrimaries(c, 0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f);
                        default -> throw new IOException("unknown primaries " + c.primaries);
                    }
                }
            }
            if (in.bool()) { // have_gamma
                c.gamma = in.u(24);
                if (c.gamma <= 0 || c.gamma > 10000000) {
                    throw new IOException("bad gamma " + c.gamma);
                }
            } else {
                c.transferFunction = in.enumValue();
            }
            c.renderingIntent = in.enumValue();
            if (c.renderingIntent > 3) {
                throw new IOException("unknown rendering intent " + c.renderingIntent);
            }
        }
        return c;
    }

    /** Writes this encoding; only the shapes produced by the encoder are supported. */
    public void write(BitWriter out) {
        out.writeBool(allDefault);
        if (allDefault) {
            return;
        }
        if (wantIcc) {
            throw new IllegalStateException("cannot encode ICC colour encodings");
        }
        out.writeBool(false); // want_icc
        out.writeEnum(colourSpace);
        if (colourSpace != CS_XYB) {
            if (whitePoint != WP_D65 && whitePoint != WP_E && whitePoint != WP_DCI) {
                throw new IllegalStateException("cannot encode custom white point");
            }
            out.writeEnum(whitePoint);
            if (colourSpace != CS_GREY) {
                if (primaries != PR_SRGB && primaries != PR_BT2100 && primaries != PR_P3) {
                    throw new IllegalStateException("cannot encode custom primaries");
                }
                out.writeEnum(primaries);
            }
        }
        if (gamma > 0) {
            out.writeBool(true);
            out.write(gamma, 24);
        } else {
            out.writeBool(false);
            out.writeEnum(transferFunction);
        }
        out.writeEnum(renderingIntent);
    }

    public boolean isGrey() {
        return colourSpace == CS_GREY;
    }

    private static void setPrimaries(ColourEncoding c, float... xy) {
        for (int i = 0; i < 3; i++) {
            c.primariesXY[i][0] = xy[i * 2];
            c.primariesXY[i][1] = xy[i * 2 + 1];
        }
    }

    private static void readCustomXY(Bits in, float[] xy) throws IOException {
        xy[0] = Bits.unpackSigned(in.u32(0, 19, 0x80000, 19, 0x100000, 20, 0x200000, 21)) / 100000f;
        xy[1] = Bits.unpackSigned(in.u32(0, 19, 0x80000, 19, 0x100000, 20, 0x200000, 21)) / 100000f;
    }
}
