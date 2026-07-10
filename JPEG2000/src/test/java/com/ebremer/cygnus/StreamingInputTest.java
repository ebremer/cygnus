package com.ebremer.cygnus;

import com.ebremer.cygnus.decoder.DecodedImage;
import com.ebremer.cygnus.decoder.Jpeg2000Decoder;
import com.ebremer.cygnus.testutil.MiniJ2kEncoder;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming input: decoding directly from a seekable stream must match the
 * in-memory path, must not read tile bodies outside the requested region,
 * and must fall back to buffering for forward-only streams.
 */
class StreamingInputTest {

    @TempDir
    Path tempDir;

    /** FileImageInputStream that counts the bytes actually read. */
    static final class CountingStream extends FileImageInputStream {
        long bytesRead;

        CountingStream(File f) throws IOException {
            super(f);
        }

        @Override
        public int read() throws IOException {
            int v = super.read();
            if (v >= 0) {
                bytesRead++;
            }
            return v;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }
    }

    private static int[][] randomComps(MiniJ2kEncoder.Config cfg, long seed) {
        Random rnd = new Random(seed);
        int[][] comps = new int[cfg.precision.length][];
        for (int c = 0; c < comps.length; c++) {
            int cw = (cfg.width + cfg.xr[c] - 1) / cfg.xr[c];
            int ch = (cfg.height + cfg.yr[c] - 1) / cfg.yr[c];
            comps[c] = new int[cw * ch];
            for (int i = 0; i < comps[c].length; i++) {
                comps[c][i] = rnd.nextInt(1 << cfg.precision[c]);
            }
        }
        return comps;
    }

    @Test
    void streamedDecodeMatchesBuffered() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 96;
        cfg.height = 80;
        cfg.xtsiz = 32;
        cfg.ytsiz = 32;
        cfg.levels = 2;
        cfg.rct = true;
        cfg.precision = new int[] {8, 8, 8};
        cfg.xr = new int[] {1, 1, 1};
        cfg.yr = new int[] {1, 1, 1};
        byte[] j2k = MiniJ2kEncoder.encode(randomComps(cfg, 61), cfg);
        Path file = tempDir.resolve("stream.j2k");
        Files.write(file, j2k);

        Jpeg2000Decoder mem = new Jpeg2000Decoder();
        mem.open(j2k);
        Jpeg2000Decoder streamed = new Jpeg2000Decoder();
        try (FileImageInputStream in = new FileImageInputStream(file.toFile())) {
            streamed.open(in);

            DecodedImage a = mem.decode();
            DecodedImage b = streamed.decode();
            for (int c = 0; c < a.numChannels; c++) {
                assertArrayEquals(a.samples[c], b.samples[c], "full, channel " + c);
            }

            Rectangle region = new Rectangle(30, 25, 40, 30);
            a = mem.decode(region);
            b = streamed.decode(region);
            for (int c = 0; c < a.numChannels; c++) {
                assertArrayEquals(a.samples[c], b.samples[c], "region, channel " + c);
            }

            a = mem.decode(1);
            b = streamed.decode(1);
            for (int c = 0; c < a.numChannels; c++) {
                assertArrayEquals(a.samples[c], b.samples[c], "reduced, channel " + c);
            }
        }
    }

    @Test
    void regionReadTouchesOnlyNeededBytes() throws Exception {
        // 16-bit random data: each 256x256 tile body is ~128 KiB, far larger
        // than the header reader's buffer, so body skipping is observable
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 1024;
        cfg.height = 1024;
        cfg.xtsiz = 256;
        cfg.ytsiz = 256;
        cfg.levels = 2;
        cfg.precision = new int[] {16};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = randomComps(cfg, 62);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);
        Path file = tempDir.resolve("big.j2k");
        Files.write(file, j2k);
        long fileSize = Files.size(file);
        assertTrue(fileSize > 1_500_000, "test needs a reasonably large file");

        try (CountingStream in = new CountingStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
            reader.setInput(in);
            assertEquals(1024, reader.getWidth(0));
            long afterHeaders = in.bytesRead;
            assertTrue(afterHeaders < fileSize / 4,
                    "header walk read " + afterHeaders + " of " + fileSize);

            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(300, 300, 100, 100)); // one tile
            BufferedImage img = reader.read(0, param);
            assertEquals(100, img.getWidth());
            long total = in.bytesRead;
            assertTrue(total < fileSize / 4,
                    "single-tile region read " + total + " of " + fileSize + " bytes");

            // pixels must match the source exactly (lossless)
            for (int y = 0; y < 100; y += 7) {
                for (int x = 0; x < 100; x += 7) {
                    assertEquals(comps[0][(300 + y) * 1024 + (300 + x)],
                            img.getRaster().getSample(x, y, 0));
                }
            }
            reader.dispose();
        }
    }

    @Test
    void seekForwardOnlyFallsBackToBuffering() throws Exception {
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 40;
        cfg.height = 30;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = randomComps(cfg, 63);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg2000").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k)),
                true, false);
        BufferedImage img = reader.read(0, null);
        assertEquals(40, img.getWidth());
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                assertEquals(comps[0][y * 40 + x], img.getRaster().getSample(x, y, 0));
            }
        }
        reader.dispose();
    }

    @Test
    void unknownLengthStreamWithPsotZero() throws Exception {
        // single tile, then patch the SOT's Psot to 0 ("extends to EOC"):
        // with a MemoryCacheImageInputStream the length is unknown, forcing
        // the scan-to-EOF path
        MiniJ2kEncoder.Config cfg = new MiniJ2kEncoder.Config();
        cfg.width = 48;
        cfg.height = 40;
        cfg.levels = 2;
        cfg.precision = new int[] {8};
        cfg.xr = new int[] {1};
        cfg.yr = new int[] {1};
        int[][] comps = randomComps(cfg, 64);
        byte[] j2k = MiniJ2kEncoder.encode(comps, cfg);

        int sot = -1;
        for (int i = 0; i < j2k.length - 1; i++) {
            if ((j2k[i] & 0xFF) == 0xFF && (j2k[i + 1] & 0xFF) == 0x90) {
                sot = i;
                break;
            }
        }
        assertTrue(sot > 0, "SOT found");
        // SOT(2) Lsot(2) Isot(2) then Psot(4)
        for (int i = 0; i < 4; i++) {
            j2k[sot + 6 + i] = 0;
        }

        Jpeg2000Decoder dec = new Jpeg2000Decoder();
        dec.open(new com.ebremer.cygnus.io.ImageInputStreamSource(
                new MemoryCacheImageInputStream(new ByteArrayInputStream(j2k))));
        DecodedImage img = dec.decode();
        assertArrayEquals(comps[0], img.samples[0]);
    }
}
