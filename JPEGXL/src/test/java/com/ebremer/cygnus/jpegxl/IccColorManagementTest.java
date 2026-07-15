package com.ebremer.cygnus.jpegxl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.cygnus.jpegxl.color.IccColorTransform;
import com.ebremer.cygnus.jpegxl.decoder.JxlDecoder;
import com.ebremer.cygnus.jpegxl.decoder.JxlImage;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Full colour-managed rendering of matrix/TRC RGB ICC profiles — device samples
 * mapped to sRGB the way lcms2 (and so libjxl) does it, in pure Java.
 */
class IccColorManagementTest {

    /** The sRGB profile describes sRGB, so device -> sRGB is the identity. */
    @Test
    void srgbProfileIsNearIdentity() {
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        IccColorTransform t = IccColorTransform.forProfile(icc);
        assertNotNull(t, "the sRGB profile is matrix/TRC RGB");
        int[] r = new int[256];
        int[] g = new int[256];
        int[] b = new int[256];
        for (int i = 0; i < 256; i++) {
            r[i] = g[i] = b[i] = i;
        }
        t.toSrgb(r, g, b, 255);
        int worst = 0;
        for (int i = 0; i < 256; i++) {
            worst = Math.max(worst, Math.abs(r[i] - i));
        }
        assertTrue(worst <= 2, "sRGB profile should map to itself, worst " + worst);
    }

    /** A linear-RGB profile (sRGB primaries, linear TRC) reduces to the sRGB encoding. */
    @Test
    void linearRgbProfileAppliesTheSrgbCurve() {
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        IccColorTransform t = IccColorTransform.forProfile(icc);
        assumeTrue(t != null, "linear RGB is matrix/TRC on this platform");
        int[] r = {0, 64, 128, 192, 255};
        int[] g = r.clone();
        int[] b = r.clone();
        t.toSrgb(r, g, b, 255);
        for (int i = 0; i < r.length; i++) {
            double lin = new int[] {0, 64, 128, 192, 255}[i] / 255.0;
            double srgb = lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
            int expected = (int) Math.round(srgb * 255);
            assertTrue(Math.abs(r[i] - expected) <= 2,
                    "linear " + i + ": got " + r[i] + " expected ~" + expected);
        }
    }

    /** A non-RGB profile (grey here) is not something the matrix/TRC path renders. */
    @Test
    void nonRgbProfileFallsBack() {
        byte[] grey = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
        assertNull(IccColorTransform.forProfile(grey), "a grey profile is not matrix/TRC RGB");
        assertNull(IccColorTransform.forProfile(null), "no profile -> no transform");
    }

    /**
     * The ImageIO reader colour-manages a modular image with an embedded profile:
     * its output differs from the raw device samples, and the skip flag turns it
     * off. Needs a corpus image whose profile is not already sRGB.
     */
    @Test
    void readerColourManagesModularIcc() throws Exception {
        Path file = Path.of("test-data", "conformance", "cafe", "input.jxl");
        assumeTrue(Files.exists(file), "conformance corpus not present");
        byte[] bytes = Files.readAllBytes(file);
        JxlImage img = JxlDecoder.decode(bytes);
        assumeTrue(!img.metadata.xybEncoded && img.metadata.iccProfile != null,
                "expected a modular image with an embedded ICC");
        assumeTrue(IccColorTransform.forProfile(img.metadata.iccProfile) != null,
                "expected a matrix/TRC RGB profile");

        System.setProperty("jxl.skipIcc", "true");
        BufferedImage raw = ImageIO.read(file.toFile());
        System.clearProperty("jxl.skipIcc");
        BufferedImage managed = ImageIO.read(file.toFile());

        int w = img.width;
        int h = img.height;
        int[][] dev = img.frames.get(0).channels;
        long changed = 0;
        long rawMiss = 0;
        for (int y = 0; y < h; y += 7) {
            for (int x = 0; x < w; x += 7) {
                int rw = raw.getRGB(x, y);
                int mg = managed.getRGB(x, y);
                if (rw != mg) {
                    changed++;
                }
                int i = y * w + x;
                // skipIcc output is the raw device samples, unchanged
                int rr = (rw >> 16) & 0xff;
                if (Math.abs(rr - dev[0][i]) > 1) {
                    rawMiss++;
                }
            }
        }
        assertTrue(rawMiss == 0, "skipIcc should pass the raw device samples through");
        assertTrue(changed > 0, "colour management should change the displayed pixels");
    }
}
