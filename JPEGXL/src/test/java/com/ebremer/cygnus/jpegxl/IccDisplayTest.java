package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlFrame;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * An XYB frame with an embedded ICC profile is coded in linear light — the
 * profile, not an enumerated transfer, defines its output — so its eight-bit
 * (display) decode has to gamma-encode it, or the picture comes out far too
 * dark. The float decode stays linear, matching the profile-plus-linear form of
 * the conformance references.
 */
class IccDisplayTest {

    private static Path vector(String name) {
        return Path.of("test-data", "conformance", name, "input.jxl");
    }

    private static float srgbOetf(float v) {
        if (v <= 0f) {
            return 0f;
        }
        if (v >= 1f) {
            return 1f;
        }
        return v <= 0.0031308f ? 12.92f * v : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
    }

    @Test
    void xybIccEightBitDecodeIsGammaEncoded() throws Exception {
        Path file = vector("patches");   // XYB, embedded ICC
        assumeTrue(Files.exists(file), "conformance corpus not present");
        byte[] bytes = Files.readAllBytes(file);
        JxlImage floatImg = JxlDecoder.decodeToFloats(bytes);
        JxlImage intImg = JxlDecoder.decode(bytes);
        assumeTrue(intImg.metadata.xybEncoded && intImg.metadata.iccProfile != null,
                "expected an XYB image with an embedded ICC");

        float[] lin = floatImg.frames.get(0).floatChannels[1];   // linear green
        int[] shown = intImg.frames.get(0).channels[1];          // eight-bit green
        int n = intImg.width * intImg.height;

        int gammaOff = 0;
        int linearOff = 0;
        int midtones = 0;
        for (int i = 0; i < n; i++) {
            if (lin[i] < 0.05f || lin[i] > 0.5f) {
                continue;      // where gamma vs linear differ most
            }
            midtones++;
            gammaOff += Math.abs(shown[i] - Math.round(srgbOetf(lin[i]) * 255));
            linearOff += Math.abs(shown[i] - Math.round(lin[i] * 255));
        }
        assumeTrue(midtones > 1000, "not enough mid-tone pixels to judge");
        // the eight-bit output tracks the sRGB curve, not the raw linear values
        assertTrue(gammaOff < linearOff / 8,
                "eight-bit decode should be gamma-encoded, not linear: gamma-miss " + gammaOff
                        + " vs linear-miss " + linearOff);
    }

    @Test
    void floatDecodeStaysLinearForTheReference() throws Exception {
        Path file = vector("patches");
        assumeTrue(Files.exists(file), "conformance corpus not present");
        byte[] bytes = Files.readAllBytes(file);
        JxlImage floatImg = JxlDecoder.decodeToFloats(bytes);
        JxlImage intImg = JxlDecoder.decode(bytes);
        assumeTrue(intImg.metadata.xybEncoded && intImg.metadata.iccProfile != null,
                "expected an XYB image with an embedded ICC");

        float[] lin = floatImg.frames.get(0).floatChannels[1];
        int[] shown = intImg.frames.get(0).channels[1];
        int n = intImg.width * intImg.height;
        // the float decode is not gamma-encoded: a mid-tone that reads bright in
        // the eight-bit output is dark in the float one
        int brighter = 0;
        int midtones = 0;
        for (int i = 0; i < n; i++) {
            if (lin[i] < 0.05f || lin[i] > 0.5f) {
                continue;
            }
            midtones++;
            if (shown[i] > Math.round(lin[i] * 255) + 10) {
                brighter++;
            }
        }
        assumeTrue(midtones > 1000, "not enough mid-tone pixels");
        assertTrue(brighter > midtones * 9 / 10,
                "the eight-bit output should be visibly brighter than the linear float");
    }

    /** An enumerated-sRGB XYB frame (no ICC) is already gamma-encoded, and unchanged. */
    @Test
    void enumeratedSrgbXybIsUntouched() throws Exception {
        // bike carries no ICC; both decodes gamma-encode it the same way
        Path file = vector("bike");
        assumeTrue(Files.exists(file), "conformance corpus not present");
        byte[] bytes = Files.readAllBytes(file);
        JxlImage img = JxlDecoder.decode(bytes);
        assumeTrue(img.metadata.iccProfile == null, "bike should have no embedded ICC");
        JxlFrame frame = img.frames.get(0);
        // just a sanity decode; the point is no exception and a plausible image
        assertTrue(frame.channels[0] != null);
    }
}
