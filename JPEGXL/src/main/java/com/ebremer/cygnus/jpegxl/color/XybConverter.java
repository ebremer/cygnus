package com.ebremer.cygnus.jpegxl.color;

import com.ebremer.cygnus.jpegxl.codestream.ImageMetadata;

/** In-place XYB to linear RGB conversion using the opsin inverse matrix. */
public final class XybConverter {

    private final float[] matrix; // 3x3 row-major, scaled by 255/intensityTarget
    private final float[] opsinBias = new float[3];
    private final float[] negCbrtBias = new float[3];

    public XybConverter(ImageMetadata meta) {
        float itScale = 255f / meta.intensityTarget;
        matrix = new float[9];
        for (int i = 0; i < 9; i++) {
            matrix[i] = meta.opsinInverse[i] * itScale;
        }
        for (int c = 0; c < 3; c++) {
            opsinBias[c] = meta.opsinBias[c];
            negCbrtBias[c] = (float) -Math.cbrt(meta.opsinBias[c]);
        }
    }

    /** Converts XYB planes (X, Y, B order) to linear RGB in place. */
    public void invertXYB(float[] xp, float[] yp, float[] bp) {
        int chunk = 1 << 16;
        int n = (xp.length + chunk - 1) / chunk;
        if (n > 1) {
            try {
                com.ebremer.cygnus.jpegxl.decoder.JxlDecoder.parallelFor(n, t ->
                        invertRange(xp, yp, bp, t * chunk, Math.min((t + 1) * chunk, xp.length)));
            } catch (java.io.IOException e) {
                throw new AssertionError(e); // the body does no IO
            }
            return;
        }
        invertRange(xp, yp, bp, 0, xp.length);
    }

    private void invertRange(float[] xp, float[] yp, float[] bp, int from, int to) {
        for (int i = from; i < to; i++) {
            float xybX = xp[i];
            float xybY = yp[i];
            float xybB = bp[i];
            float gammaL = xybY + xybX + negCbrtBias[0];
            float gammaM = xybY - xybX + negCbrtBias[1];
            float gammaS = xybB + negCbrtBias[2];
            float mixL = gammaL * gammaL * gammaL + opsinBias[0];
            float mixM = gammaM * gammaM * gammaM + opsinBias[1];
            float mixS = gammaS * gammaS * gammaS + opsinBias[2];
            xp[i] = matrix[0] * mixL + matrix[1] * mixM + matrix[2] * mixS;
            yp[i] = matrix[3] * mixL + matrix[4] * mixM + matrix[5] * mixS;
            bp[i] = matrix[6] * mixL + matrix[7] * mixM + matrix[8] * mixS;
        }
    }
}
