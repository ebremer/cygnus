package com.ebremer.jpegxl.testutil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Locates and drives the reference cjxl/djxl binaries for validation tests. */
public final class JxlTools {

    private JxlTools() {
    }

    /** Finds a libjxl tool via the JXL_TOOLS directory or the PATH; null if absent. */
    public static String find(String tool) {
        String dir = System.getenv("JXL_TOOLS");
        if (dir != null) {
            Path p = Path.of(dir, tool + ".exe");
            if (Files.exists(p)) {
                return p.toString();
            }
            p = Path.of(dir, tool);
            if (Files.exists(p)) {
                return p.toString();
            }
        }
        for (String pathDir : System.getenv("PATH").split(File.pathSeparator)) {
            for (String suffix : new String[] {".exe", ""}) {
                Path p = Path.of(pathDir, tool + suffix);
                if (Files.exists(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    public static boolean available() {
        return find("cjxl") != null && find("djxl") != null;
    }

    public static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("tool timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new AssertionError("tool failed (" + p.exitValue() + "): "
                    + String.join(" ", cmd) + "\n" + out);
        }
    }

    /** Writes a 3-channel 32-bit float PFM (bottom-up, little-endian). */
    public static void writePfm(Path file, float[][] planes, int w, int h) throws IOException {
        byte[] header = ("PF\n" + w + " " + h + "\n-1.0\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(header.length + w * h * 12)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put(header);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    buf.putFloat(planes[c][y * w + x]);
                }
            }
        }
        Files.write(file, buf.array());
    }

    /** Reads a 3-channel PFM into top-down float planes. */
    public static float[][] readPfm(Path file, int w, int h) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int pos = 0;
        String[] header = new String[3];
        for (int i = 0; i < 3; i++) {
            StringBuilder sb = new StringBuilder();
            while (data[pos] != '\n') {
                sb.append((char) data[pos++]);
            }
            pos++;
            header[i] = sb.toString().trim();
        }
        if (!header[0].equals("PF")) {
            throw new IOException("not a colour PFM: " + header[0]);
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data, pos, data.length - pos)
                .order(Float.parseFloat(header[2]) < 0
                        ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
        float[][] planes = new float[3][w * h];
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    planes[c][y * w + x] = buf.getFloat();
                }
            }
        }
        return planes;
    }

    /** Encodes float planes losslessly with cjxl via a PFM file. */
    public static byte[] cjxlPfm(Path tempDir, String name, float[][] planes, int w, int h,
            String... cjxlArgs) throws Exception {
        Path pfm = tempDir.resolve(name + ".pfm");
        Path jxl = tempDir.resolve(name + ".jxl");
        writePfm(pfm, planes, w, h);
        List<String> cmd = new ArrayList<>();
        cmd.add(find("cjxl"));
        cmd.add(pfm.toString());
        cmd.add(jxl.toString());
        cmd.add("--distance=0.0");
        for (String a : cjxlArgs) {
            cmd.add(a);
        }
        run(cmd.toArray(String[]::new));
        return Files.readAllBytes(jxl);
    }

    /** Decodes a .jxl to float planes with djxl via a PFM file. */
    public static float[][] djxlPfm(Path tempDir, String name, byte[] jxl, int w, int h)
            throws Exception {
        Path jxlFile = tempDir.resolve(name + "-ref.jxl");
        Path pfm = tempDir.resolve(name + "-ref.pfm");
        Files.write(jxlFile, jxl);
        run(find("djxl"), jxlFile.toString(), pfm.toString());
        return readPfm(pfm, w, h);
    }

    /** Encodes planes with cjxl (via PNG input) and returns the .jxl bytes. */
    public static byte[] cjxl(Path tempDir, String name, int[][] planes, int w, int h, int bits,
            boolean grey, boolean alpha, String... cjxlArgs) throws Exception {
        Path png = tempDir.resolve(name + ".png");
        Path jxl = tempDir.resolve(name + ".jxl");
        ImageIO.write(toImage(planes, w, h, bits, grey, alpha), "png", png.toFile());
        List<String> cmd = new ArrayList<>();
        cmd.add(find("cjxl"));
        cmd.add(png.toString());
        cmd.add(jxl.toString());
        for (String a : cjxlArgs) {
            cmd.add(a);
        }
        run(cmd.toArray(new String[0]));
        return Files.readAllBytes(jxl);
    }

    /** Decodes a .jxl with djxl and returns the per-channel planes of the PNG output. */
    public static int[][] djxl(Path tempDir, String name, byte[] jxl) throws Exception {
        return djxl(tempDir, name, jxl, 0);
    }

    /**
     * Decodes with djxl; when {@code targetBits} is non-zero, samples are
     * rescaled from the PNG's bit depth to that depth (djxl writes 16-bit
     * PNGs for anything deeper than 8 bits).
     */
    public static int[][] djxl(Path tempDir, String name, byte[] jxl, int targetBits)
            throws Exception {
        Path jxlFile = tempDir.resolve(name + "-ref.jxl");
        Path png = tempDir.resolve(name + "-ref.png");
        Files.write(jxlFile, jxl);
        run(find("djxl"), jxlFile.toString(), png.toString());
        BufferedImage img = ImageIO.read(png.toFile());
        int[][] planes = fromImage(img);
        if (targetBits > 0) {
            int pngBits = img.getRaster().getSampleModel().getSampleSize(0);
            if (pngBits != targetBits) {
                double scale = ((1 << targetBits) - 1) / (double) ((1 << pngBits) - 1);
                for (int[] plane : planes) {
                    for (int i = 0; i < plane.length; i++) {
                        plane[i] = (int) Math.round(plane[i] * scale);
                    }
                }
            }
        }
        return planes;
    }

    public static BufferedImage toImage(int[][] planes, int w, int h, int bits,
            boolean grey, boolean alpha) {
        if (grey && !alpha && bits <= 8) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.getRaster().setSample(x, y, 0, planes[0][y * w + x]);
                }
            }
            return img;
        }
        if (bits <= 8) {
            BufferedImage img = new BufferedImage(w, h,
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int r = planes[0][i];
                    int g = grey ? r : planes[1][i];
                    int b = grey ? r : planes[2][i];
                    int a = alpha ? planes[grey ? 1 : 3][i] : 255;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        }
        int bands = (grey ? 1 : 3) + (alpha ? 1 : 0);
        java.awt.color.ColorSpace cs = java.awt.color.ColorSpace.getInstance(
                grey ? java.awt.color.ColorSpace.CS_GRAY : java.awt.color.ColorSpace.CS_sRGB);
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(
                cs, alpha, false,
                alpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE,
                java.awt.image.DataBuffer.TYPE_USHORT);
        java.awt.image.WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
        for (int b = 0; b < bands; b++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    raster.setSample(x, y, b, planes[b][y * w + x]);
                }
            }
        }
        return new BufferedImage(cm, raster, false, null);
    }

    /** Extracts per-channel planes from a PNG decoded by ImageIO. */
    public static int[][] fromImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        java.awt.image.Raster raster = img.getRaster();
        int bands = raster.getNumBands();
        int[][] planes = new int[bands][w * h];
        for (int b = 0; b < bands; b++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    planes[b][y * w + x] = raster.getSample(x, y, b);
                }
            }
        }
        return planes;
    }

    /** Maximum absolute per-sample difference between two planes. */
    public static int maxAbsDiff(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    public static double meanAbsDiff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (double) sum / a.length;
    }
}
