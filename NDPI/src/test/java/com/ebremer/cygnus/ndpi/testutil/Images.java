package com.ebremer.cygnus.ndpi.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Test images, and comparisons that report where two of them differ. */
public final class Images {

    private Images() {
    }

    /** A colourful, non-uniform image: gradients plus a checkerboard. */
    public static BufferedImage pattern(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = x * 255 / Math.max(1, width - 1);
                int g = y * 255 / Math.max(1, height - 1);
                int b = (x / 16 + y / 16) % 2 == 0 ? 40 : 200;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    public static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /**
     * The pixels an ImageIO region read should produce: the source region,
     * subsampled, which is how {@code ImageReader.computeRegions} defines it.
     */
    public static BufferedImage region(BufferedImage source, Rectangle region,
                                       int subX, int subY) {
        int width = Math.ceilDiv(region.width, subX);
        int height = Math.ceilDiv(region.height, subY);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, source.getRGB(region.x + x * subX, region.y + y * subY));
            }
        }
        return out;
    }

    /** Asserts the images agree to within {@code tolerance} in every channel. */
    public static void assertSimilar(BufferedImage expected, BufferedImage actual,
                                     int tolerance, String what) {
        assertEquals(expected.getWidth(), actual.getWidth(), what + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getRGB(x, y);
                int b = actual.getRGB(x, y);
                if (difference(a, b) > tolerance) {
                    fail(String.format("%s: pixel (%d,%d) is #%06X, expected #%06X "
                                    + "(tolerance %d)", what, x, y, b & 0xFFFFFF, a & 0xFFFFFF,
                            tolerance));
                }
            }
        }
    }

    /** Asserts every pixel is {@code rgb}, to within {@code tolerance}. */
    public static void assertUniform(BufferedImage image, int rgb, int tolerance, String what) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int actual = image.getRGB(x, y);
                if (difference(rgb, actual) > tolerance) {
                    fail(String.format("%s: pixel (%d,%d) is #%06X, expected #%06X "
                                    + "(tolerance %d)", what, x, y, actual & 0xFFFFFF,
                            rgb & 0xFFFFFF, tolerance));
                }
            }
        }
    }

    /** Largest per-channel difference between two packed RGB pixels. */
    public static int difference(int a, int b) {
        int red = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int green = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int blue = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(red, Math.max(green, blue));
    }
}
