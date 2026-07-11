package com.ebremer.jpegxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.jpegxl.decoder.JxlDecoder;
import com.ebremer.jpegxl.decoder.JxlFrame;
import com.ebremer.jpegxl.decoder.JxlImage;
import com.ebremer.jpegxl.encoder.JxlEncoder;
import com.ebremer.jpegxl.encoder.VarDctEncoder;
import com.ebremer.jpegxl.io.CodestreamSource;
import com.ebremer.jpegxl.testutil.TestImages;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Region decoding must produce exactly the corresponding crop of a full
 * decode — bit-for-bit, including lossy VarDCT output (the filters run the
 * same arithmetic inside the region) — while entropy-decoding only the
 * groups that cover the region.
 */
class RegionDecodeTest {

    @TempDir
    static Path tempDir;

    /** A varied set: corners, group-straddling interiors, thin bands, 1x1, full. */
    private static Rectangle[] regionsFor(int w, int h) {
        return new Rectangle[] {
            new Rectangle(0, 0, Math.min(64, w), Math.min(64, h)),
            new Rectangle(w / 3, h / 3, Math.min(150, w - w / 3), Math.min(200, h - h / 3)),
            new Rectangle(w - 1, h - 1, 1, 1),
            new Rectangle(0, h / 2, w, Math.min(8, h - h / 2)),
            new Rectangle(w / 2, 0, Math.min(4, w - w / 2), h),
            new Rectangle(0, 0, w, h),
            new Rectangle(w / 2, h / 2, w, h), // clamps at the far corner
        };
    }

    private static void assertRegionsMatch(byte[] jxl, Rectangle... regions) throws IOException {
        JxlImage full = JxlDecoder.decode(jxl);
        for (Rectangle region : regions) {
            JxlImage part = JxlDecoder.decode(jxl, region);
            Rectangle r = region.intersection(new Rectangle(full.width, full.height));
            assertEquals(r.x, part.regionX, "regionX for " + region);
            assertEquals(r.y, part.regionY, "regionY for " + region);
            assertEquals(full.frames.size(), part.frames.size(), "frame count for " + region);
            for (int f = 0; f < full.frames.size(); f++) {
                JxlFrame ff = full.frames.get(f);
                JxlFrame pf = part.frames.get(f);
                assertEquals(r.width, pf.width, "width for " + region);
                assertEquals(r.height, pf.height, "height for " + region);
                assertEquals(ff.channels.length, pf.channels.length);
                for (int c = 0; c < ff.channels.length; c++) {
                    String what = "frame " + f + " channel " + c + " region " + region;
                    if (ff.channels[c] != null) {
                        assertNotNull(pf.channels[c], what);
                        assertArrayEquals(cropInt(ff.channels[c], ff.width, r),
                                pf.channels[c], what);
                    } else {
                        assertNotNull(pf.floatChannels[c], what);
                        assertArrayEquals(cropFloat(ff.floatChannels[c], ff.width, r),
                                pf.floatChannels[c], what);
                    }
                }
            }
        }
    }

    private static int[] cropInt(int[] plane, int stride, Rectangle r) {
        int[] out = new int[r.width * r.height];
        for (int y = 0; y < r.height; y++) {
            System.arraycopy(plane, (r.y + y) * stride + r.x, out, y * r.width, r.width);
        }
        return out;
    }

    private static float[] cropFloat(float[] plane, int stride, Rectangle r) {
        float[] out = new float[r.width * r.height];
        for (int y = 0; y < r.height; y++) {
            System.arraycopy(plane, (r.y + y) * stride + r.x, out, y * r.width, r.width);
        }
        return out;
    }

    private static int[][] deepCopy(int[][] planes) {
        int[][] out = new int[planes.length][];
        for (int i = 0; i < planes.length; i++) {
            out[i] = planes[i].clone();
        }
        return out;
    }

    // ------------------------------------------------------------ our encoder

    @Test
    void losslessRgbRegions() throws IOException {
        int w = 600;
        int h = 420; // 3x2 groups
        int[][] planes = TestImages.mixed(w, h, 3, 8, 11);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 8, false, false, false);
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void losslessGreyDeepRegions() throws IOException {
        int w = 300;
        int h = 700; // 2x3 groups
        int[][] planes = TestImages.mixed(w, h, 1, 16, 12);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 16, true, false, false);
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void losslessAlphaRegions() throws IOException {
        int w = 520;
        int h = 300;
        int[][] planes = TestImages.mixed(w, h, 4, 8, 13);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 8, false, true, false);
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void deepIntegerRegions() throws IOException {
        // 28-bit samples take the exact-integer path
        int w = 400;
        int h = 300;
        int[][] planes = TestImages.mixed(w, h, 3, 28, 14);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 28, false, false, false);
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void lossyVarDctRegions() throws IOException {
        int w = 600;
        int h = 420;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 15);
        byte[] jxl = VarDctEncoder.encode(deepCopy(planes), w, h, 1.0f);
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void previewImageRegions() throws IOException {
        int w = 500;
        int h = 400;
        int[][] main = TestImages.mixed(w, h, 3, 8, 16);
        int[][] preview = TestImages.mixed(40, 30, 3, 8, 17);
        byte[] jxl = JxlEncoder.encodeWithPreview(deepCopy(main), w, h, 8, false, false, false,
                preview, 40, 30);
        assertRegionsMatch(jxl, regionsFor(w, h));
        // the preview stays complete regardless of the region
        JxlImage part = JxlDecoder.decode(jxl, new Rectangle(10, 10, 50, 50));
        assertNotNull(part.preview);
        assertEquals(40, part.preview.width);
        assertEquals(30, part.preview.height);
    }

    @Test
    void singleGroupImageRegions() throws IOException {
        // fits one group: the region path degenerates to decode-and-crop
        int w = 200;
        int h = 150;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 18);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 8, false, false, false);
        assertRegionsMatch(jxl, new Rectangle(3, 5, 60, 40), new Rectangle(0, 0, w, h));
    }

    // ------------------------------------------------------------ selectivity

    /** Counts the bytes handed out; sections that are skipped are never read. */
    private static final class CountingSource implements CodestreamSource {
        private final CodestreamSource delegate;
        final AtomicLong bytes = new AtomicLong();

        CountingSource(CodestreamSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public void readFully(long offset, byte[] dst, int dstOff, int len) throws IOException {
            bytes.addAndGet(len);
            delegate.readFully(offset, dst, dstOff, len);
        }
    }

    @Test
    void regionReadsFractionOfCodestream() throws IOException {
        int w = 1024;
        int h = 1024; // 4x4 groups of noise: sections have real weight
        int[][] planes = TestImages.noise(w, h, 3, 8, 19);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 8, false, false, false);
        byte[] codestream = com.ebremer.jpegxl.container.Container.extractCodestream(jxl);

        CountingSource fullCount = new CountingSource(new CodestreamSource.ArraySource(codestream));
        JxlDecoder.decode(fullCount, null);
        CountingSource regionCount = new CountingSource(new CodestreamSource.ArraySource(codestream));
        JxlDecoder.decode(regionCount, new Rectangle(0, 0, 64, 64));

        // one group (plus headers) out of sixteen; leave slack for the TOC
        // window parsing, but it must stay well under half the full read
        assertTrue(regionCount.bytes.get() < fullCount.bytes.get() / 3,
                "region read " + regionCount.bytes + " of " + fullCount.bytes + " bytes");
    }

    // ------------------------------------------------------------ ImageIO

    @Test
    void imageIoSourceRegionAndTiles() throws IOException {
        int w = 600;
        int h = 420;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 20);
        byte[] jxl = JxlEncoder.encode(deepCopy(planes), w, h, 8, false, false, false);

        BufferedImage whole;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jxl))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
            reader.setInput(in);
            whole = reader.read(0, null);
            reader.dispose();
        }

        Rectangle region = new Rectangle(120, 200, 260, 150); // straddles group borders
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jxl))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
            reader.setInput(in);

            assertTrue(reader.isImageTiled(0));
            assertEquals(256, reader.getTileWidth(0));
            assertEquals(256, reader.getTileHeight(0));

            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(region);
            BufferedImage sub = reader.read(0, param);
            assertEquals(region.width, sub.getWidth());
            assertEquals(region.height, sub.getHeight());
            for (int y = 0; y < region.height; y++) {
                for (int x = 0; x < region.width; x++) {
                    assertEquals(whole.getRGB(region.x + x, region.y + y), sub.getRGB(x, y),
                            "pixel (" + x + ", " + y + ")");
                }
            }

            // region combined with subsampling
            param.setSourceSubsampling(2, 3, 0, 0);
            BufferedImage sampled = reader.read(0, param);
            assertEquals((region.width + 1) / 2, sampled.getWidth());
            assertEquals((region.height + 2) / 3, sampled.getHeight());
            for (int y = 0; y < sampled.getHeight(); y++) {
                for (int x = 0; x < sampled.getWidth(); x++) {
                    assertEquals(whole.getRGB(region.x + x * 2, region.y + y * 3),
                            sampled.getRGB(x, y), "sampled pixel (" + x + ", " + y + ")");
                }
            }

            // tiles line up with the group grid
            BufferedImage tile = reader.readTile(0, 1, 1);
            assertEquals(256, tile.getWidth());
            assertEquals(h - 256, tile.getHeight()); // bottom row of tiles is short
            for (int y = 0; y < tile.getHeight(); y++) {
                for (int x = 0; x < tile.getWidth(); x++) {
                    assertEquals(whole.getRGB(256 + x, 256 + y), tile.getRGB(x, y),
                            "tile pixel (" + x + ", " + y + ")");
                }
            }
            reader.dispose();
        }
    }

    // ------------------------------------------------------------ ffmpeg files

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return out.contains("libjxl");
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] ffmpegEncode(int[][] planes, int w, int h, String... options)
            throws Exception {
        byte[] raw = new byte[w * h * 3];
        int idx = 0;
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < 3; c++) {
                raw[idx++] = (byte) planes[c][i];
            }
        }
        Path rawFile = tempDir.resolve("region-src-" + raw.length + "-" + options.length + ".raw");
        Path jxlFile = tempDir.resolve("region-src-" + raw.length + "-" + options.length + ".jxl");
        Files.write(rawFile, raw);
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", w + "x" + h,
                "-i", rawFile.toString(), "-frames:v", "1", "-c:v", "libjxl"));
        cmd.addAll(List.of(options));
        cmd.add(jxlFile.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("ffmpeg failed: " + out);
        }
        return Files.readAllBytes(jxlFile);
    }

    @Test
    void ffmpegLosslessRegions() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg with libjxl not available");
        int w = 600;
        int h = 420;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 21);
        byte[] jxl = ffmpegEncode(planes, w, h, "-distance", "0", "-effort", "7");
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void ffmpegVarDctRegions() throws Exception {
        // real libjxl VarDCT output: gaborish + EPF + chroma-from-luma active
        assumeTrue(ffmpegAvailable(), "ffmpeg with libjxl not available");
        int w = 600;
        int h = 420;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 22);
        byte[] jxl = ffmpegEncode(planes, w, h, "-distance", "1.0", "-effort", "7");
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    @Test
    void ffmpegLossyModularSqueezeFallsBack() throws Exception {
        // lossy modular uses a frame-global squeeze; the region decode must
        // transparently fall back to a full decode and still match the crop
        assumeTrue(ffmpegAvailable(), "ffmpeg with libjxl not available");
        int w = 600;
        int h = 420;
        int[][] planes = TestImages.mixed(w, h, 3, 8, 23);
        byte[] jxl;
        try {
            jxl = ffmpegEncode(planes, w, h, "-distance", "1.0", "-modular", "1");
        } catch (AssertionError e) {
            assumeTrue(false, "ffmpeg build lacks the -modular option");
            return;
        }
        assertRegionsMatch(jxl, regionsFor(w, h));
    }

    // ------------------------------------------------------------ conformance

    @Test
    void conformanceCorpusRegions() throws Exception {
        // sweeps every official conformance file (orientation, animation,
        // patches, splines, noise, squeeze, ...): region == crop, whether the
        // selective path handles it or the fallback does
        String dir = System.getenv("JXL_CONFORMANCE");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "JXL_CONFORMANCE not set");
        int checked = 0;
        try (var files = Files.list(Path.of(dir))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jxl")).toList()) {
                byte[] jxl = Files.readAllBytes(file);
                JxlImage full;
                try {
                    full = JxlDecoder.decode(jxl);
                } catch (IOException e) {
                    continue; // not decodable at all; other tests track these
                }
                int w = full.width;
                int h = full.height;
                Rectangle[] regions = {
                    new Rectangle(w / 4, h / 4, Math.max(1, w / 3), Math.max(1, h / 3)),
                    new Rectangle(3, 7, Math.max(1, Math.min(90, w - 3)),
                            Math.max(1, Math.min(70, h - 7))),
                };
                try {
                    assertRegionsMatch(jxl, regions);
                } catch (AssertionError e) {
                    throw new AssertionError(file.getFileName() + ": " + e.getMessage(), e);
                }
                checked++;
            }
        }
        assumeTrue(checked > 0, "no conformance files present");
    }
}
