package com.ebremer.jpegxl.color;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BandedSampleModel;
import java.awt.image.ColorConvertOp;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Converts linear sRGB planes into the space described by an embedded ICC
 * profile, using the JDK's colour management engine. Returns the device
 * samples encoded per the profile (what {@code djxl} also outputs).
 */
public final class IccApplier {

    private IccApplier() {
    }

    /**
     * @return device-encoded planes in [0,1] with the profile's component
     *         count (1 for grey, 3 for RGB), or null when the profile cannot
     *         be applied (unsupported class or parse failure)
     */
    public static float[][] fromLinearSrgb(float[] r, float[] g, float[] b,
            int width, int height, byte[] iccBytes) {
        try {
            ICC_Profile profile = ICC_Profile.getInstance(iccBytes);
            ICC_ColorSpace target = new ICC_ColorSpace(profile);
            int comps = target.getNumComponents();
            if (comps != 1 && comps != 3) {
                return null; // CMYK and friends are out of scope
            }
            // going through sRGB avoids the JDK's quirky built-in linear profile
            ColorSpace source = ColorSpace.getInstance(ColorSpace.CS_sRGB);

            int n = width * height;
            short[][] srcData = new short[3][n];
            for (int i = 0; i < n; i++) {
                srcData[0][i] = (short) clamp16(Transfer.srgbFromLinear(r[i]));
                srcData[1][i] = (short) clamp16(Transfer.srgbFromLinear(g[i]));
                srcData[2][i] = (short) clamp16(Transfer.srgbFromLinear(b[i]));
            }
            WritableRaster src = Raster.createWritableRaster(
                    new BandedSampleModel(DataBuffer.TYPE_USHORT, width, height, 3),
                    new java.awt.image.DataBufferUShort(srcData, n), null);
            WritableRaster dst = Raster.createWritableRaster(
                    new BandedSampleModel(DataBuffer.TYPE_USHORT, width, height, comps),
                    new java.awt.image.DataBufferUShort(comps == 1
                            ? new short[][] {new short[n]}
                            : new short[][] {new short[n], new short[n], new short[n]}, n), null);
            new ColorConvertOp(source, target, null).filter(src, dst);

            java.awt.image.DataBufferUShort out =
                    (java.awt.image.DataBufferUShort) dst.getDataBuffer();
            float[][] planes = new float[comps][n];
            for (int c = 0; c < comps; c++) {
                short[] data = out.getData(c);
                float scale = 1f / 65535f;
                for (int i = 0; i < n; i++) {
                    planes[c][i] = (data[i] & 0xFFFF) * scale;
                }
            }
            return planes;
        } catch (RuntimeException e) {
            return null; // malformed profile: fall back to the enumerated encoding
        }
    }

    private static int clamp16(float v) {
        int x = Math.round(v * 65535f);
        return x < 0 ? 0 : Math.min(x, 65535);
    }
}
